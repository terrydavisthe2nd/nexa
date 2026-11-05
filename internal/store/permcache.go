package store

import (
	"context"
	"database/sql"
	"errors"
	"sort"
	"sync"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

// Every event in a server channel asks who may see it, and every request that
// touches a channel asks what the person making it may do there. Answered from
// the database each time, that was seven queries for the asker and seven more
// plus one permission computation per member for the audience — on every
// message, reaction, edit, typing indicator and read ack — against a reader
// pool of eight connections, so the cost of one message grew with the size of
// the largest server rather than with anything its sender did.
//
// So each server's permission inputs — owner, roles, members with their roles
// and timeouts, overwrites by channel, and which channels exist — are loaded once into a snapshot and answered from memory. Every
// write that could change an answer goes through this package and bumps a
// version once it commits; a snapshot built against an older version is never
// used, and one being built while a write lands is not kept. Snapshots also
// expire after a minute, which bounds how long a change made behind chatd's
// back (someone editing the database by hand) can go unseen.

// permSnapshotTTL is how long a snapshot is trusted with no write telling it
// otherwise.
var permSnapshotTTL = time.Minute

// maxPermSnapshots bounds how many servers' snapshots are kept at once; past
// it the least recently used half go.
const maxPermSnapshots = 1024

// permMember is one membership as the resolver needs it.
type permMember struct {
	roleIDs      []ids.ID
	timeoutUntil *int64
	flags        int64
	// userFlags are the account's own flags, for whether it is a bot that
	// may hold the server's keys (keyEligible)
	userFlags int64
}

// keyEligible reports whether an account may hold a conversation's keys:
// everyone but a bot whose application lacks the Message Content intent.
func keyEligible(userFlags int64) bool {
	return userFlags&FlagBot == 0 || userFlags&FlagBotReadsAll != 0
}

// serverSnapshot is one server's permission inputs, as of one version. Nothing
// in it changes once it is built, apart from the viewer lists it memoises.
type serverSnapshot struct {
	serverID ids.ID
	ownerID  ids.ID
	roles    []perms.Role
	// memberOrder keeps members in id order, so lists built from them are
	// stable from call to call
	memberOrder []ids.ID
	members     map[ids.ID]*permMember
	overwrites  map[ids.ID][]perms.Overwrite
	// channels maps each channel of the server to its type, in channelOrder
	channels     map[ids.ID]string
	channelOrder []ids.ID
	// threads maps each thread to its parent channel; private threads also
	// list the people in them, who with moderators are all who see them
	threads       map[ids.ID]ids.ID
	threadMembers map[ids.ID]map[ids.ID]bool
	// archived threads, which ready leaves out of the read states it sends
	archived map[ids.ID]bool
	version  uint64
	loadedAt time.Time
	lastUsed time.Time

	// viewers memoises who can see each channel. Timeouts leave a member able
	// to view (perms.TimedOutKeep), so the answer does not change with the
	// clock and lasts as long as the snapshot.
	viewMu  sync.Mutex
	viewers map[ids.ID][]ids.ID
}

// permCache holds the snapshots.
type permCache struct {
	mu sync.Mutex
	// global is bumped by writes that reach past one server (an account's
	// flags or deletion); servers[id] by writes to one server. A snapshot's
	// version is their sum, and both only grow, so any write changes it.
	global  uint64
	servers map[ids.ID]uint64
	snaps   map[ids.ID]*serverSnapshot
	loading map[ids.ID]*permLoad
}

type permLoad struct {
	done    chan struct{}
	snap    *serverSnapshot
	adm     map[ids.ID]bool
	version uint64
	err     error
}

func newPermCache() *permCache {
	return &permCache{servers: map[ids.ID]uint64{}, snaps: map[ids.ID]*serverSnapshot{}, loading: map[ids.ID]*permLoad{}}
}

// versionLocked is the version a snapshot of serverID must carry to be
// current; the caller holds mu.
func (c *permCache) versionLocked(serverID ids.ID) uint64 {
	return c.global + c.servers[serverID]
}

// serverChanged invalidates one server's snapshot; call it after the write
// commits.
func (s *Store) serverChanged(serverID ids.ID) {
	c := s.perm
	c.mu.Lock()
	c.servers[serverID]++
	delete(c.snaps, serverID)
	c.mu.Unlock()
}

// everythingChanged invalidates every snapshot: for writes
// whose reach is not one server, such as an account's flags or deletion.
func (s *Store) everythingChanged() {
	c := s.perm
	c.mu.Lock()
	c.global++
	clear(c.snaps)
	c.mu.Unlock()
}

// channelServer is the server a channel belongs to, or zero for a
// conversation or a channel that is gone.
func channelServer(ctx context.Context, q querier, channelID ids.ID) (ids.ID, error) {
	var server sql.NullInt64
	err := q.QueryRowContext(ctx, `SELECT server_id FROM channels WHERE id = ?`, int64(channelID)).Scan(&server)
	if errors.Is(err, sql.ErrNoRows) || (err == nil && !server.Valid) {
		return 0, nil
	}
	return ids.ID(server.Int64), err
}

// changedIn invalidates what a write to a channel of server could change:
// that server's snapshot, or every one when the server could not be named.
func (s *Store) changedIn(server ids.ID, lookupErr error) {
	switch {
	case lookupErr != nil:
		s.everythingChanged()
	case !server.IsZero():
		s.serverChanged(server)
	}
}

// snapshot returns the current permission inputs of a server, loading them
// when there is no current snapshot. Loads of one server are shared by
// everyone asking at once. ErrNotFound when the server does not exist.
func (s *Store) snapshot(ctx context.Context, serverID ids.ID) (*serverSnapshot, error) {
	c := s.perm
	for {
		c.mu.Lock()
		now := time.Now()
		want := c.versionLocked(serverID)
		if snap := c.snaps[serverID]; snap != nil && snap.version == want && now.Sub(snap.loadedAt) < permSnapshotTTL {
			snap.lastUsed = now
			c.mu.Unlock()
			return snap, nil
		}
		if l := c.loading[serverID]; l != nil {
			c.mu.Unlock()
			select {
			case <-l.done:
			case <-ctx.Done():
				return nil, ctx.Err()
			}
			if l.err != nil {
				return nil, l.err
			}
			if l.version == s.currentVersion(serverID) {
				return l.snap, nil
			}
			// a write landed while it loaded: ask again
			continue
		}
		l := &permLoad{done: make(chan struct{}), version: want}
		c.loading[serverID] = l
		c.mu.Unlock()

		snap, err := s.loadSnapshot(ctx, serverID, want)
		c.mu.Lock()
		delete(c.loading, serverID)
		// kept only if no write landed while it was read
		if err == nil && c.versionLocked(serverID) == want {
			if len(c.snaps) >= maxPermSnapshots {
				c.evictLocked()
			}
			c.snaps[serverID] = snap
		}
		c.mu.Unlock()
		l.snap, l.err = snap, err
		close(l.done)
		// even a snapshot that could not be kept answers this one call as of
		// the moment it was read, which is all a read overlapping a write can
		// promise anyway
		return snap, err
	}
}

func (s *Store) currentVersion(serverID ids.ID) uint64 {
	s.perm.mu.Lock()
	defer s.perm.mu.Unlock()
	return s.perm.versionLocked(serverID)
}

// evictLocked drops the least recently used half of the snapshots.
func (c *permCache) evictLocked() {
	times := make([]time.Time, 0, len(c.snaps))
	for _, snap := range c.snaps {
		times = append(times, snap.lastUsed)
	}
	sort.Slice(times, func(i, j int) bool { return times[i].Before(times[j]) })
	cutoff := times[len(times)/2]
	for id, snap := range c.snaps {
		if !snap.lastUsed.After(cutoff) {
			delete(c.snaps, id)
		}
	}
}

// loadSnapshot reads a server's permission inputs.
func (s *Store) loadSnapshot(ctx context.Context, serverID ids.ID, version uint64) (*serverSnapshot, error) {
	snap := &serverSnapshot{serverID: serverID, members: map[ids.ID]*permMember{}, overwrites: map[ids.ID][]perms.Overwrite{},
		channels: map[ids.ID]string{}, threads: map[ids.ID]ids.ID{}, threadMembers: map[ids.ID]map[ids.ID]bool{}, archived: map[ids.ID]bool{},
		version: version, loadedAt: time.Now(), viewers: map[ids.ID][]ids.ID{}}
	snap.lastUsed = snap.loadedAt
	if err := s.db.R.QueryRowContext(ctx, `SELECT owner_id FROM servers WHERE id = ?`, int64(serverID)).Scan(&snap.ownerID); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	roles, err := s.Roles(ctx, serverID)
	if err != nil {
		return nil, err
	}
	snap.roles = toPermRoles(roles)

	rows, err := s.db.R.QueryContext(ctx, `SELECT m.user_id, m.timeout_until, m.flags, coalesce(u.flags, 0) FROM server_members m LEFT JOIN users u ON u.id = m.user_id WHERE m.server_id = ? ORDER BY m.user_id`, int64(serverID))
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var uid ids.ID
		var timeout sql.NullInt64
		var flags, userFlags int64
		if err := rows.Scan(&uid, &timeout, &flags, &userFlags); err != nil {
			rows.Close()
			return nil, err
		}
		snap.members[uid] = &permMember{roleIDs: []ids.ID{}, timeoutUntil: int64Ptr(timeout), flags: flags, userFlags: userFlags}
		snap.memberOrder = append(snap.memberOrder, uid)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows, err = s.db.R.QueryContext(ctx, `SELECT user_id, role_id FROM member_roles WHERE server_id = ?`, int64(serverID))
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var uid, role ids.ID
		if err := rows.Scan(&uid, &role); err != nil {
			rows.Close()
			return nil, err
		}
		if m := snap.members[uid]; m != nil {
			m.roleIDs = append(m.roleIDs, role)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}

	ows, err := s.OverwritesForServer(ctx, serverID)
	if err != nil {
		return nil, err
	}
	for ch, list := range ows {
		snap.overwrites[ch] = toPermOverwrites(list)
	}

	rows, err = s.db.R.QueryContext(ctx, `SELECT id, type, parent_id, archived FROM channels WHERE server_id = ? ORDER BY coalesce(parent_id, 0), position, id`, int64(serverID))
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var id ids.ID
		var typ string
		var parent sql.NullInt64
		var archived int64
		if err := rows.Scan(&id, &typ, &parent, &archived); err != nil {
			rows.Close()
			return nil, err
		}
		snap.channels[id] = typ
		snap.channelOrder = append(snap.channelOrder, id)
		if IsThread(typ) && parent.Valid {
			snap.threads[id] = ids.ID(parent.Int64)
			if archived == 1 {
				snap.archived[id] = true
			}
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows, err = s.db.R.QueryContext(ctx, `SELECT t.thread_id, t.user_id FROM thread_members t JOIN channels c ON c.id = t.thread_id WHERE c.server_id = ? AND c.type = 'private_thread'`, int64(serverID))
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var thread, uid ids.ID
		if err := rows.Scan(&thread, &uid); err != nil {
			rows.Close()
			return nil, err
		}
		if snap.threadMembers[thread] == nil {
			snap.threadMembers[thread] = map[ids.ID]bool{}
		}
		snap.threadMembers[thread][uid] = true
	}
	rows.Close()
	return snap, rows.Err()
}

// member returns uid's membership as perms needs it, and whether there is one.
func (snap *serverSnapshot) member(uid ids.ID, now int64) (perms.Member, bool) {
	m := snap.members[uid]
	if m == nil {
		return perms.Member{}, false
	}
	return perms.Member{UserID: uid, RoleIDs: m.roleIDs, TimedOut: m.timeoutUntil != nil && *m.timeoutUntil > now, Quarantined: m.flags&MemberQuarantined != 0}, true
}

// privileged reports whether uid holds every permission in this server: its
// owner alone. Nexa's staff, Developers included, are ordinary members of the
// servers they join (the owner's rule, 2026-11-04: rights in other people's
// servers were a privacy invasion).
func (snap *serverSnapshot) privileged(uid ids.ID) bool {
	return snap.ownerID == uid
}

// channelPerms is what uid may do in one of the server's channels: 0 for
// someone who is not a member. A thread answers to its parent channel
// (perms.Thread).
func (snap *serverSnapshot) channelPerms(channelID, uid ids.ID, now int64) perms.Perm {
	pm, ok := snap.member(uid, now)
	if !ok {
		return 0
	}
	if parent, isThread := snap.threads[channelID]; isThread {
		p := perms.Compute(snap.serverID, snap.roles, pm, snap.privileged(uid), snap.overwrites[parent])
		private := snap.channels[channelID] == ChannelPrivateThread
		return perms.Thread(p, private, snap.threadMembers[channelID][uid])
	}
	return perms.Compute(snap.serverID, snap.roles, pm, snap.privileged(uid), snap.overwrites[channelID])
}

// serverPerms is what uid may do server-wide: 0 for someone who is not a member.
func (snap *serverSnapshot) serverPerms(uid ids.ID, now int64) perms.Perm {
	pm, ok := snap.member(uid, now)
	if !ok {
		return 0
	}
	return perms.Base(snap.serverID, snap.roles, pm, snap.privileged(uid))
}

// viewersOf lists the members who can see a channel, in member id order. The
// list is shared: callers must not change it.
func (snap *serverSnapshot) viewersOf(channelID ids.ID) []ids.ID {
	snap.viewMu.Lock()
	defer snap.viewMu.Unlock()
	if v, ok := snap.viewers[channelID]; ok {
		return v
	}
	now := nowMs()
	out := make([]ids.ID, 0, len(snap.memberOrder))
	for _, uid := range snap.memberOrder {
		if snap.channelPerms(channelID, uid, now).Has(perms.ViewChannel) {
			out = append(out, uid)
		}
	}
	// capped at its length, so a caller's append makes a copy instead of
	// writing into the list everyone else is reading
	out = out[:len(out):len(out)]
	snap.viewers[channelID] = out
	return out
}
