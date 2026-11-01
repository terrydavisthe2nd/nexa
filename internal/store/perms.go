package store

import (
	"context"
	"database/sql"
	"errors"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

// Permissions are answered from each server's snapshot (permcache.go), which
// every write to what they depend on invalidates.

func toPermRoles(roles []*Role) []perms.Role {
	out := make([]perms.Role, len(roles))
	for i, r := range roles {
		out[i] = perms.Role{ID: r.ID, Position: r.Position, Permissions: r.Permissions}
	}
	return out
}

func toPermOverwrites(list []Overwrite) []perms.Overwrite {
	out := make([]perms.Overwrite, len(list))
	for i, ow := range list {
		out[i] = perms.Overwrite{TargetID: ow.TargetID, Kind: ow.TargetType, Allow: ow.Allow, Deny: ow.Deny}
	}
	return out
}

// IsInstanceAdmin reports whether an account runs Nexa. In any server
// it is a member of, such an account holds every permission its owner does.
// A lookup that fails answers no; the permission functions below return the
// error instead, so a database hiccup is a 500 there rather than a 403.
func (s *Store) IsInstanceAdmin(ctx context.Context, uid ids.ID) bool {
	admins, err := s.admins(ctx)
	return err == nil && admins[uid]
}

// instanceAdmins reads every Nexa admin.
func (s *Store) instanceAdmins(ctx context.Context) (map[ids.ID]bool, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id FROM users WHERE flags & ? != 0 AND flags & ? = 0`, FlagAdmin, FlagDisabled|FlagDeleted)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[ids.ID]bool{}
	for rows.Next() {
		var id ids.ID
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}

// ServerPerms resolves a user's server-level permissions (0 for non-members).
func (s *Store) ServerPerms(ctx context.Context, serverID, uid ids.ID) (perms.Perm, error) {
	snap, err := s.snapshot(ctx, serverID)
	if err != nil {
		return 0, err
	}
	return snap.serverPerms(uid, nowMs()), nil
}

// MembersWithPerm lists the members whose server-level permissions include need.
func (s *Store) MembersWithPerm(ctx context.Context, serverID ids.ID, need perms.Perm) ([]ids.ID, error) {
	snap, err := s.snapshot(ctx, serverID)
	if err != nil {
		return nil, err
	}
	now := nowMs()
	out := []ids.ID{}
	for _, uid := range snap.memberOrder {
		if snap.serverPerms(uid, now).Has(need) {
			out = append(out, uid)
		}
	}
	return out, nil
}

// ChannelPerms resolves a user's permissions in a channel. DM participants get
// every text permission; non-participants and non-members get 0.
func (s *Store) ChannelPerms(ctx context.Context, channelID, uid ids.ID) (perms.Perm, *Channel, error) {
	ch, err := s.ChannelByID(ctx, channelID)
	if err != nil {
		return 0, nil, err
	}
	p, err := s.PermsIn(ctx, ch, uid)
	return p, ch, err
}

// PermsIn is ChannelPerms for a channel the caller has already loaded, which
// is how a request that has just read the channel avoids reading it again.
func (s *Store) PermsIn(ctx context.Context, ch *Channel, uid ids.ID) (perms.Perm, error) {
	if ch.ServerID == nil {
		for _, id := range ch.Recipients {
			if id == uid {
				return perms.All, nil
			}
		}
		return 0, nil
	}
	snap, err := s.snapshot(ctx, *ch.ServerID)
	if err != nil {
		return 0, err
	}
	if _, ok := snap.channels[ch.ID]; !ok {
		// deleted since the caller read it
		return 0, nil
	}
	return snap.channelPerms(ch.ID, uid, nowMs()), nil
}

// EveryonePermsIn is what @everyone may do in a server channel, which is
// what an application added to someone's account (and not to the server)
// gets there when that person uses it.
func (s *Store) EveryonePermsIn(ctx context.Context, ch *Channel) (perms.Perm, error) {
	if ch.ServerID == nil {
		return 0, nil
	}
	snap, err := s.snapshot(ctx, *ch.ServerID)
	if err != nil {
		return 0, err
	}
	target := ch.ID
	if parent, ok := snap.threads[ch.ID]; ok {
		target = parent
	}
	return perms.Compute(snap.serverID, snap.roles, perms.Member{}, false, snap.overwrites[target]), nil
}

// ChannelViewers lists who can see a channel: a server channel's members who
// may view it, or a conversation's participants.
func (s *Store) ChannelViewers(ctx context.Context, channelID ids.ID) ([]ids.ID, error) {
	var typ string
	var server sql.NullInt64
	err := s.db.R.QueryRowContext(ctx, `SELECT type, server_id FROM channels WHERE id = ?`, int64(channelID)).Scan(&typ, &server)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if !server.Valid {
		return s.Participants(ctx, channelID)
	}
	return s.channelViewers(ctx, ids.ID(server.Int64), channelID)
}

// channelViewers lists the members of a server who can view one of its
// channels. The list is shared with other callers: read it, do not change it.
func (s *Store) channelViewers(ctx context.Context, serverID, channelID ids.ID) ([]ids.ID, error) {
	snap, err := s.snapshot(ctx, serverID)
	if err != nil {
		return nil, err
	}
	if _, ok := snap.channels[channelID]; !ok {
		return []ids.ID{}, nil
	}
	return snap.viewersOf(channelID), nil
}

// ServerIDsOf lists the servers uid is a member of, oldest membership first.
func (s *Store) ServerIDsOf(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	return s.serversOf(ctx, uid)
}

// serversOf lists the servers uid is a member of.
func (s *Store) serversOf(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT server_id FROM server_members WHERE user_id = ? ORDER BY joined_at, server_id`, int64(uid))
}

// ChannelsForUser lists every server channel the user can view.
func (s *Store) ChannelsForUser(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	servers, err := s.serversOf(ctx, uid)
	if err != nil {
		return nil, err
	}
	out := []ids.ID{}
	now := nowMs()
	for _, serverID := range servers {
		snap, err := s.snapshot(ctx, serverID)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		if _, ok := snap.members[uid]; !ok {
			continue
		}
		for _, ch := range snap.channelOrder {
			// read states are for channels and for threads still going
			if HoldsNoMessages(snap.channels[ch]) || snap.archived[ch] {
				continue
			}
			if snap.channelPerms(ch, uid, now).Has(perms.ViewChannel) {
				out = append(out, ch)
			}
		}
	}
	return out, nil
}

// ViewableServerChannels is ChannelsForUser restricted to one server, with
// what the user may do in each; ErrNotFound for someone who is not a member.
func (s *Store) ViewableServerChannels(ctx context.Context, serverID, uid ids.ID) (map[ids.ID]perms.Perm, error) {
	snap, err := s.snapshot(ctx, serverID)
	if err != nil {
		return nil, err
	}
	if _, ok := snap.members[uid]; !ok {
		return nil, ErrNotFound
	}
	now := nowMs()
	out := map[ids.ID]perms.Perm{}
	for _, ch := range snap.channelOrder {
		if p := snap.channelPerms(ch, uid, now); p.Has(perms.ViewChannel) {
			out[ch] = p
		}
	}
	return out, nil
}

// CoMembers lists everyone who shares a server with uid, uid included when
// they are in any, each once.
func (s *Store) CoMembers(ctx context.Context, uid ids.ID) ([]ids.ID, error) {
	servers, err := s.serversOf(ctx, uid)
	if err != nil {
		return nil, err
	}
	seen := map[ids.ID]bool{}
	out := []ids.ID{}
	for _, serverID := range servers {
		snap, err := s.snapshot(ctx, serverID)
		if errors.Is(err, ErrNotFound) {
			continue
		}
		if err != nil {
			return nil, err
		}
		for _, m := range snap.memberOrder {
			if !seen[m] {
				seen[m] = true
				out = append(out, m)
			}
		}
	}
	return out, nil
}

// keyFilter keeps, of list, the members of a server who may hold its keys
// (keyEligible), read from its snapshot.
func (s *Store) keyFilter(ctx context.Context, serverID ids.ID, list []ids.ID) ([]ids.ID, error) {
	snap, err := s.snapshot(ctx, serverID)
	if err != nil {
		return nil, err
	}
	out := make([]ids.ID, 0, len(list))
	for _, uid := range list {
		if m := snap.members[uid]; m == nil || keyEligible(m.userFlags) {
			out = append(out, uid)
		}
	}
	return out, nil
}

// keyParticipants lists the people of a conversation who hold its keys:
// every participant but a bot, which reads only the copies sealed to it.
func (s *Store) keyParticipants(ctx context.Context, convID ids.ID) ([]ids.ID, error) {
	return s.idList(ctx, `SELECT p.user_id FROM dm_participants p JOIN users u ON u.id = p.user_id WHERE p.channel_id = ? AND u.flags & ? = 0 ORDER BY p.added_at, p.user_id`, int64(convID), FlagBot)
}

// serverMemberIDs lists a server's members from its snapshot.
func (s *Store) serverMemberIDs(ctx context.Context, serverID ids.ID) ([]ids.ID, error) {
	snap, err := s.snapshot(ctx, serverID)
	if err != nil {
		return nil, err
	}
	return append([]ids.ID{}, snap.memberOrder...), nil
}
