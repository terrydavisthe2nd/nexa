package api

import (
	"context"
	"net/http"

	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// needBits maps the channel access needs used by DM handlers onto permissions.
func needBits(need string) perms.Perm {
	switch need {
	case NeedSend:
		return perms.ViewChannel | perms.SendMessages
	case NeedManage:
		return perms.ViewChannel | perms.ManageMessages
	case NeedPin:
		return perms.ViewChannel | perms.PinMessages
	case NeedExternalEmoji:
		return perms.ViewChannel | perms.UseExternalEmojis
	case NeedHistory:
		return perms.ViewChannel | perms.ReadMessageHistory
	case NeedEmbed:
		return perms.ViewChannel | perms.EmbedLinks
	case NeedReact:
		return perms.ViewChannel | perms.AddReactions
	case NeedAttach:
		return perms.ViewChannel | perms.AttachFiles
	case "mention_everyone":
		return perms.ViewChannel | perms.MentionEveryone
	case NeedWebhooks:
		return perms.ViewChannel | perms.ManageWebhooks
	case NeedPolls:
		return perms.ViewChannel | perms.CreatePolls
	default:
		return perms.ViewChannel
	}
}

// ServerView is a server as the ready payload and join responses carry it.
// Threads are apart from Channels, so a client that knows nothing of them
// does not draw them as channels: the ones still going that this member can
// see, and the ones they are in.
type ServerView struct {
	*store.Server
	Channels      []*store.Channel `json:"channels"`
	Threads       []*store.Channel `json:"threads"`
	JoinedThreads []ids.ID         `json:"joined_threads"`
	Roles         []*store.Role    `json:"roles"`
	Members       []*store.Member  `json:"members"`
	MemberCount   int              `json:"member_count"`
	Me            *store.Member    `json:"me"`
	MyPermissions perms.Perm       `json:"my_permissions"`
}

// serverCreateEvent is a server as server_create carries it: with the people
// in its member list. The tab that made or joined the server has them from its
// reply; every other tab of the account showed the members nameless until it
// fetched them one by one.
type serverCreateEvent struct {
	*ServerView
	Users []store.PublicUser `json:"users"`
}

const readyMemberLimit = 500

// serverView assembles a member's view of a server (only channels they can see).
func (s *Server) serverView(ctx context.Context, srv *store.Server, uid ids.ID) (*ServerView, []store.PublicUser, error) {
	view, full, err := s.serverViewUsers(ctx, srv, uid)
	if err != nil {
		return nil, nil, err
	}
	var users []store.PublicUser
	for _, u := range full {
		users = append(users, u.Public())
	}
	return view, users, nil
}

// serverViewUsers is serverView with the listed members' whole accounts, which
// ready builds their presence from instead of reading each one again.
func (s *Server) serverViewUsers(ctx context.Context, srv *store.Server, uid ids.ID) (*ServerView, []*store.User, error) {
	me, err := s.Store.Member(ctx, srv.ID, uid)
	if err != nil {
		return nil, nil, err
	}
	roles, err := s.Store.Roles(ctx, srv.ID)
	if err != nil {
		return nil, nil, err
	}
	visible, err := s.Store.ViewableServerChannels(ctx, srv.ID, uid)
	if err != nil {
		return nil, nil, err
	}
	all, err := s.Store.ServerChannels(ctx, srv.ID)
	if err != nil {
		return nil, nil, err
	}
	chans := make([]*store.Channel, 0, len(all))
	for _, c := range all {
		if _, ok := visible[c.ID]; ok {
			chans = append(chans, c)
		}
	}
	count, err := s.Store.MemberCount(ctx, srv.ID)
	if err != nil {
		return nil, nil, err
	}
	threads := []*store.Channel{}
	if active, err := s.Store.ActiveThreads(ctx, srv.ID); err == nil {
		for _, th := range active {
			if _, ok := visible[th.ID]; ok {
				threads = append(threads, th)
			}
		}
	} else {
		return nil, nil, err
	}
	joined, err := s.Store.JoinedThreads(ctx, srv.ID, uid)
	if err != nil {
		return nil, nil, err
	}
	view := &ServerView{Server: srv, Channels: chans, Threads: threads, JoinedThreads: joined, Roles: roles, Members: []*store.Member{}, MemberCount: count, Me: me}
	view.MyPermissions, err = s.Store.ServerPerms(ctx, srv.ID, uid)
	if err != nil {
		return nil, nil, err
	}
	var users []*store.User
	// A big server's members come a page at a time, but the bots that read
	// every message they can see come at once: members' apps mark the
	// channels those bots can read, and need their roles to tell which.
	if count > readyMemberLimit {
		if readers, err := s.Store.ReadingBotIDs(ctx, srv.ID); err == nil && len(readers) > 0 {
			byID, err := s.Store.UsersByIDs(ctx, readers)
			if err != nil {
				return nil, nil, err
			}
			for _, id := range readers {
				m, err := s.Store.Member(ctx, srv.ID, id)
				if err != nil || byID[id] == nil {
					continue
				}
				view.Members = append(view.Members, m)
				users = append(users, byID[id])
			}
		}
	}
	if count <= readyMemberLimit {
		members, err := s.Store.Members(ctx, srv.ID, 0, readyMemberLimit, "")
		if err != nil {
			return nil, nil, err
		}
		view.Members = members
		list := make([]ids.ID, 0, len(members))
		for _, m := range members {
			list = append(list, m.UserID)
		}
		byID, err := s.Store.UsersByIDs(ctx, list)
		if err != nil {
			return nil, nil, err
		}
		for _, id := range list {
			if u := byID[id]; u != nil {
				users = append(users, u)
			}
		}
	}
	return view, users, nil
}

// WireServers installs the server-aware hooks on the API server and the hub.
func (s *Server) WireServers(hub *gateway.Hub) {
	s.ChannelAccess = func(ctx context.Context, u *store.User, ch *store.Channel, need string) (bool, error) {
		// the channel is the one the request already read
		p, err := s.Store.PermsIn(ctx, ch, u.ID)
		if err != nil {
			return false, err
		}
		return p.Has(needBits(need)), nil
	}
	s.ConvForChannel = func(ctx context.Context, ch *store.Channel) (ids.ID, error) {
		if ch.KeyConvID.IsZero() {
			fresh, err := s.Store.ChannelByID(ctx, ch.ID)
			if err != nil {
				return 0, err
			}
			return fresh.KeyConvID, nil
		}
		return ch.KeyConvID, nil
	}
	s.ServerChannelPatch = s.handleServerChannelPatch
	s.InviteCheck = func(ctx context.Context, code string) bool {
		_, err := s.Store.InviteByCode(ctx, code)
		return err == nil
	}
	s.Profile.MutualServers = func(r *http.Request, me, other ids.ID) ([]ids.ID, error) {
		mine, err := s.Store.ServersForUser(r.Context(), me)
		if err != nil {
			return nil, err
		}
		out := []ids.ID{}
		for _, srv := range mine {
			if ok, _ := s.Store.IsMember(r.Context(), srv.ID, other); ok {
				out = append(out, srv.ID)
			}
		}
		return out, nil
	}
	if hub == nil {
		return
	}
	hub.Access = func(ctx context.Context, uid ids.ID, ch *store.Channel) (bool, error) {
		p, err := s.Store.PermsIn(ctx, ch, uid)
		if err != nil {
			return false, err
		}
		return p.Has(perms.ViewChannel), nil
	}
	// typing is a promise of a message, so it needs the same right as sending one
	hub.SendAccess = func(ctx context.Context, uid ids.ID, ch *store.Channel) (bool, error) {
		p, err := s.Store.PermsIn(ctx, ch, uid)
		if err != nil {
			return false, err
		}
		return p.Has(needBits(NeedSend)), nil
	}
	hub.Interested = s.Store.CoMembers
	hub.ExtraChannels = func(ctx context.Context, uid ids.ID) ([]ids.ID, error) { return s.Store.ChannelsForUser(ctx, uid) }
	hub.ReadyExtras = func(ctx context.Context, u *store.User, p *gateway.ReadyPayload) error {
		servers, err := s.Store.ServersForUser(ctx, u.ID)
		if err != nil {
			return err
		}
		if p.Emojis, err = s.Store.EmojisForUser(ctx, u.ID); err != nil {
			return err
		}
		if p.StickerPacks, err = s.Store.PacksForUser(ctx, u.ID); err != nil {
			return err
		}
		seen := map[ids.ID]bool{u.ID: true}
		for _, usr := range p.Users {
			seen[usr.ID] = true
		}
		for _, srv := range servers {
			view, users, err := s.serverViewUsers(ctx, srv, u.ID)
			if err != nil {
				return err
			}
			p.Servers = append(p.Servers, view)
			var fresh []*store.User
			for _, usr := range users {
				if !seen[usr.ID] {
					seen[usr.ID] = true
					p.Users = append(p.Users, usr.Public())
					fresh = append(fresh, usr)
				}
			}
			// presence is built from the accounts just read, asking the hub
			// who is online once per server rather than once per member
			list := make([]ids.ID, len(fresh))
			for i, usr := range fresh {
				list[i] = usr.ID
			}
			online := map[ids.ID]bool{}
			for _, id := range hub.OnlineAmong(list) {
				online[id] = true
			}
			for _, usr := range fresh {
				if online[usr.ID] {
					if pr := hub.PresenceOf(ctx, usr); pr.Status != "offline" {
						p.Presences = append(p.Presences, pr)
					}
				}
			}
		}
		return nil
	}
}
