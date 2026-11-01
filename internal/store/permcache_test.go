package store

import (
	"context"
	"slices"
	"sync"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

// permWorld is a server with a private channel, and the people around it.
type permWorld struct {
	s                       *Store
	srv                     *Server
	owner, alice, bob, carl *User
	general, private        ids.ID
	staff                   *Role
}

func newPermWorld(t *testing.T) *permWorld {
	t.Helper()
	s := newStore(t)
	ctx := context.Background()
	w := &permWorld{s: s, owner: mkUser(t, s, "owner"), alice: mkUser(t, s, "alice"), bob: mkUser(t, s, "bob"), carl: mkUser(t, s, "carl")}
	var err error
	if w.srv, err = s.CreateServer(ctx, w.owner.ID, "S", DefaultNames{}); err != nil {
		t.Fatal(err)
	}
	w.general = *w.srv.SystemChannelID
	for _, u := range []*User{w.alice, w.bob} {
		if _, err := s.AddMember(ctx, w.srv.ID, u.ID); err != nil {
			t.Fatal(err)
		}
	}
	ch, err := s.CreateServerChannel(ctx, w.srv.ID, "text", "staff", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	w.private = ch.ID
	if err := s.SetOverwrite(ctx, Overwrite{ChannelID: w.private, TargetID: w.srv.ID, TargetType: "role", Deny: perms.ViewChannel}); err != nil {
		t.Fatal(err)
	}
	if w.staff, err = s.CreateRole(ctx, w.srv.ID, "staff", perms.DefaultEveryone, 0); err != nil {
		t.Fatal(err)
	}
	if err := s.SetOverwrite(ctx, Overwrite{ChannelID: w.private, TargetID: w.staff.ID, TargetType: "role", Allow: perms.ViewChannel}); err != nil {
		t.Fatal(err)
	}
	return w
}

// sees answers "may uid see the private channel" through every entry point at
// once, which also warms the snapshot the next write has to invalidate.
func (w *permWorld) sees(t *testing.T, uid ids.ID, channel ids.ID) bool {
	t.Helper()
	ctx := context.Background()
	p, _, err := w.s.ChannelPerms(ctx, channel, uid)
	if err != nil {
		t.Fatal(err)
	}
	viewers, err := w.s.ChannelViewers(ctx, channel)
	if err != nil {
		t.Fatal(err)
	}
	inViewers := slices.Contains(viewers, uid)
	mine, err := w.s.ChannelsForUser(ctx, uid)
	if err != nil {
		t.Fatal(err)
	}
	inMine := slices.Contains(mine, channel)
	if p.Has(perms.ViewChannel) != inViewers || inViewers != inMine {
		t.Fatalf("entry points disagree about %v in %v: perms %v, viewers %v, channels-for-user %v", uid, channel, p.Has(perms.ViewChannel), inViewers, inMine)
	}
	return inViewers
}

// Every write that changes who may see what is seen at once, although the
// answers before it came from memory.
func TestPermissionCacheFollowsEveryWrite(t *testing.T) {
	ctx := context.Background()
	steps := []struct {
		name  string
		write func(t *testing.T, w *permWorld)
		// who sees the private channel afterwards
		alice, bob, carl bool
	}{
		{"assigning a role", func(t *testing.T, w *permWorld) {
			if err := w.s.AddMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID); err != nil {
				t.Fatal(err)
			}
		}, true, false, false},
		{"setting a member's roles", func(t *testing.T, w *permWorld) {
			if err := w.s.SetMemberRoles(ctx, w.srv.ID, w.bob.ID, []ids.ID{w.staff.ID}); err != nil {
				t.Fatal(err)
			}
		}, false, true, false},
		{"a member overwrite", func(t *testing.T, w *permWorld) {
			if err := w.s.SetOverwrite(ctx, Overwrite{ChannelID: w.private, TargetID: w.bob.ID, TargetType: "member", Allow: perms.ViewChannel}); err != nil {
				t.Fatal(err)
			}
		}, false, true, false},
		{"lifting @everyone's deny", func(t *testing.T, w *permWorld) {
			if err := w.s.DeleteOverwrite(ctx, w.private, w.srv.ID); err != nil {
				t.Fatal(err)
			}
		}, true, true, false},
		{"a role's permissions", func(t *testing.T, w *permWorld) {
			_ = w.s.AddMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID)
			w.sees(t, w.alice.ID, w.private)
			admin := perms.Administrator
			if err := w.s.DeleteOverwrite(ctx, w.private, w.staff.ID); err != nil {
				t.Fatal(err)
			}
			w.sees(t, w.alice.ID, w.private)
			if err := w.s.UpdateRole(ctx, w.staff.ID, RolePatch{Permissions: &admin}); err != nil {
				t.Fatal(err)
			}
		}, true, false, false},
		{"deleting a role", func(t *testing.T, w *permWorld) {
			_ = w.s.AddMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID)
			w.sees(t, w.alice.ID, w.private)
			if err := w.s.DeleteRole(ctx, w.staff.ID); err != nil {
				t.Fatal(err)
			}
		}, false, false, false},
		{"taking a role away", func(t *testing.T, w *permWorld) {
			_ = w.s.AddMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID)
			w.sees(t, w.alice.ID, w.private)
			if err := w.s.RemoveMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID); err != nil {
				t.Fatal(err)
			}
		}, false, false, false},
		{"joining", func(t *testing.T, w *permWorld) {
			if err := w.s.DeleteOverwrite(ctx, w.private, w.srv.ID); err != nil {
				t.Fatal(err)
			}
			if _, err := w.s.AddMember(ctx, w.srv.ID, w.carl.ID); err != nil {
				t.Fatal(err)
			}
		}, true, true, true},
		{"joining by invite", func(t *testing.T, w *permWorld) {
			if err := w.s.DeleteOverwrite(ctx, w.private, w.srv.ID); err != nil {
				t.Fatal(err)
			}
			in, err := w.s.CreateInvite(ctx, &w.srv.ID, nil, w.owner.ID, 0, 0)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := w.s.JoinByInvite(ctx, w.srv.ID, w.carl.ID, in.Code); err != nil {
				t.Fatal(err)
			}
		}, true, true, true},
		{"leaving", func(t *testing.T, w *permWorld) {
			_ = w.s.AddMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID)
			w.sees(t, w.alice.ID, w.private)
			if err := w.s.RemoveMember(ctx, w.srv.ID, w.alice.ID); err != nil {
				t.Fatal(err)
			}
		}, false, false, false},
		{"becoming a Nexa admin", func(t *testing.T, w *permWorld) {
			if _, err := w.s.ChangeUserFlags(ctx, w.bob.ID, FlagAdmin, 0); err != nil {
				t.Fatal(err)
			}
		}, false, true, false},
		{"an admin's account being disabled", func(t *testing.T, w *permWorld) {
			_ = w.s.SetUserFlags(ctx, w.bob.ID, FlagAdmin)
			w.sees(t, w.bob.ID, w.private)
			if err := w.s.SetUserFlags(ctx, w.bob.ID, FlagAdmin|FlagDisabled); err != nil {
				t.Fatal(err)
			}
		}, false, false, false},
		{"an admin's account being deleted", func(t *testing.T, w *permWorld) {
			_ = w.s.SetUserFlags(ctx, w.bob.ID, FlagAdmin)
			w.sees(t, w.bob.ID, w.private)
			if err := w.s.DeleteAccount(ctx, w.bob.ID); err != nil {
				t.Fatal(err)
			}
		}, false, false, false},
		{"handing the server over", func(t *testing.T, w *permWorld) {
			if err := w.s.UpdateServer(ctx, w.srv.ID, ServerPatch{OwnerID: &w.alice.ID}); err != nil {
				t.Fatal(err)
			}
		}, true, false, false},
		{"a member's leftovers purged", func(t *testing.T, w *permWorld) {
			_ = w.s.SetOverwrite(ctx, Overwrite{ChannelID: w.private, TargetID: w.bob.ID, TargetType: "member", Allow: perms.ViewChannel})
			w.sees(t, w.bob.ID, w.private)
			if err := w.s.PurgeUserLeftovers(ctx, w.bob.ID); err != nil {
				t.Fatal(err)
			}
		}, false, false, false},
	}
	for _, step := range steps {
		t.Run(step.name, func(t *testing.T) {
			w := newPermWorld(t)
			// warm the snapshot: nobody but the owner sees the private channel
			for _, u := range []*User{w.alice, w.bob, w.carl} {
				if w.sees(t, u.ID, w.private) {
					t.Fatalf("%s sees the private channel before anything happened", u.Username)
				}
			}
			step.write(t, w)
			for _, c := range []struct {
				u    *User
				want bool
			}{{w.alice, step.alice}, {w.bob, step.bob}, {w.carl, step.carl}} {
				if got := w.sees(t, c.u.ID, w.private); got != c.want {
					t.Errorf("after %s, %s sees the private channel: %v, want %v", step.name, c.u.Username, got, c.want)
				}
			}
		})
	}
}

// A timeout takes effect when it is set and runs out by itself, with nothing
// written in between.
func TestPermissionCacheTimeouts(t *testing.T) {
	w := newPermWorld(t)
	ctx := context.Background()
	if p, _, _ := w.s.ChannelPerms(ctx, w.general, w.alice.ID); !p.Has(perms.SendMessages) {
		t.Fatal("alice can send before the timeout")
	}
	until := time.Now().Add(300 * time.Millisecond).UnixMilli()
	pu := &until
	if err := w.s.UpdateMember(ctx, w.srv.ID, w.alice.ID, MemberPatch{TimeoutUntil: &pu}); err != nil {
		t.Fatal(err)
	}
	if p, _, _ := w.s.ChannelPerms(ctx, w.general, w.alice.ID); p.Has(perms.SendMessages) || !p.Has(perms.ViewChannel) {
		t.Errorf("timed out: %v", p)
	}
	time.Sleep(400 * time.Millisecond)
	if p, _, _ := w.s.ChannelPerms(ctx, w.general, w.alice.ID); !p.Has(perms.SendMessages) {
		t.Errorf("a timeout that ran out still holds: %v", p)
	}
}

// Channels come and go with the server's snapshot.
func TestPermissionCacheChannels(t *testing.T) {
	w := newPermWorld(t)
	ctx := context.Background()
	before, _ := w.s.ChannelsForUser(ctx, w.alice.ID)
	ch, err := w.s.CreateServerChannel(ctx, w.srv.ID, "text", "new", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	after, _ := w.s.ChannelsForUser(ctx, w.alice.ID)
	if len(after) != len(before)+1 || !slices.Contains(after, ch.ID) {
		t.Fatalf("a new channel is not listed: %v -> %v", before, after)
	}
	if !w.sees(t, w.alice.ID, ch.ID) {
		t.Fatal("alice should see the new channel")
	}
	if err := w.s.DeleteChannel(ctx, ch.ID); err != nil {
		t.Fatal(err)
	}
	if after, _ := w.s.ChannelsForUser(ctx, w.alice.ID); slices.Contains(after, ch.ID) {
		t.Error("a deleted channel is still listed")
	}
	if p, err := w.s.PermsIn(ctx, ch, w.alice.ID); err != nil || p != 0 {
		t.Errorf("a deleted channel still grants %v (%v)", p, err)
	}
	if err := w.s.DeleteServer(ctx, w.srv.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := w.s.ServerPerms(ctx, w.srv.ID, w.owner.ID); err == nil {
		t.Error("a deleted server still answers")
	}
	if mine, _ := w.s.ChannelsForUser(ctx, w.alice.ID); len(mine) != 0 {
		t.Errorf("channels of a deleted server: %v", mine)
	}
}

// A snapshot read while a write lands is not kept: the next question sees the
// write. Run with -race.
func TestPermissionCacheUnderConcurrentWrites(t *testing.T) {
	w := newPermWorld(t)
	ctx := context.Background()
	stop := make(chan struct{})
	var readers sync.WaitGroup
	for i := 0; i < 4; i++ {
		readers.Add(1)
		go func() {
			defer readers.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				_, _ = w.s.ChannelViewers(ctx, w.private)
				_, _, _ = w.s.ChannelPerms(ctx, w.private, w.alice.ID)
			}
		}()
	}
	for i := 0; i < 20; i++ {
		if err := w.s.AddMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID); err != nil {
			t.Fatal(err)
		}
		if p, _, _ := w.s.ChannelPerms(ctx, w.private, w.alice.ID); !p.Has(perms.ViewChannel) {
			t.Fatalf("round %d: the role just given is not seen", i)
		}
		if err := w.s.RemoveMemberRole(ctx, w.srv.ID, w.alice.ID, w.staff.ID); err != nil {
			t.Fatal(err)
		}
		if p, _, _ := w.s.ChannelPerms(ctx, w.private, w.alice.ID); p.Has(perms.ViewChannel) {
			t.Fatalf("round %d: the role just taken is still seen", i)
		}
	}
	close(stop)
	readers.Wait()
}

// A change behind the store's back (someone editing the database by hand) is
// seen within the snapshot's lifetime.
func TestPermissionCacheExpires(t *testing.T) {
	w := newPermWorld(t)
	ctx := context.Background()
	old := permSnapshotTTL
	permSnapshotTTL = 50 * time.Millisecond
	t.Cleanup(func() { permSnapshotTTL = old })
	if w.sees(t, w.alice.ID, w.private) {
		t.Fatal("setup")
	}
	if _, err := w.s.db.W.ExecContext(ctx, `INSERT INTO member_roles (server_id, user_id, role_id) VALUES (?,?,?)`, int64(w.srv.ID), int64(w.alice.ID), int64(w.staff.ID)); err != nil {
		t.Fatal(err)
	}
	time.Sleep(80 * time.Millisecond)
	if !w.sees(t, w.alice.ID, w.private) {
		t.Error("a hand-made change is never seen")
	}
}
