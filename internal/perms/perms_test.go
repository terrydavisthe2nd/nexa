package perms

import (
	"encoding/json"
	"testing"

	"evilion.xyz/chat/internal/ids"
)

const (
	srv   = ids.ID(1)
	rMod  = ids.ID(2)
	rMute = ids.ID(3)
	uid   = ids.ID(10)
)

var roles = []Role{
	{ID: srv, Position: 0, Permissions: ViewChannel | SendMessages | ReadMessageHistory | Connect},
	{ID: rMod, Position: 5, Permissions: KickMembers | ManageMessages},
	{ID: rMute, Position: 2, Permissions: 0},
}

func TestJSONString(t *testing.T) {
	b, _ := json.Marshal(struct{ P Perm }{ViewChannel | CreatePolls})
	if string(b) != `{"P":"562949953422336"}` {
		t.Errorf("got %s", b)
	}
	var out struct{ P Perm }
	if err := json.Unmarshal([]byte(`{"P":"1024"}`), &out); err != nil || out.P != ViewChannel {
		t.Errorf("string unmarshal: %v %d", err, out.P)
	}
	if err := json.Unmarshal([]byte(`{"P":8}`), &out); err != nil || out.P != Administrator {
		t.Errorf("number unmarshal: %v", err)
	}
	if All&Administrator == 0 || DefaultEveryone.Has(Administrator) || !DefaultEveryone.Has(ViewChannel|SendMessages) {
		t.Error("constants")
	}
}

func TestCompute(t *testing.T) {
	ow := func(kind string, target ids.ID, allow, deny Perm) Overwrite {
		return Overwrite{TargetID: target, Kind: kind, Allow: allow, Deny: deny}
	}
	cases := []struct {
		name  string
		m     Member
		owner bool
		ows   []Overwrite
		want  Perm
	}{
		{"everyone only", Member{UserID: uid}, false, nil, ViewChannel | SendMessages | ReadMessageHistory | Connect},
		{"role adds", Member{UserID: uid, RoleIDs: []ids.ID{rMod}}, false, nil, ViewChannel | SendMessages | ReadMessageHistory | Connect | KickMembers | ManageMessages},
		{"owner has all", Member{UserID: uid}, true, []Overwrite{ow("role", srv, 0, ViewChannel)}, All},
		{"everyone deny view hides channel", Member{UserID: uid}, false, []Overwrite{ow("role", srv, 0, ViewChannel)}, 0},
		{"role overwrite re-allows view", Member{UserID: uid, RoleIDs: []ids.ID{rMod}}, false, []Overwrite{ow("role", srv, 0, ViewChannel), ow("role", rMod, ViewChannel, 0)}, ViewChannel | SendMessages | ReadMessageHistory | Connect | KickMembers | ManageMessages},
		{"role deny send", Member{UserID: uid, RoleIDs: []ids.ID{rMute}}, false, []Overwrite{ow("role", rMute, 0, SendMessages)}, ViewChannel | ReadMessageHistory | Connect},
		{"role allow beats role deny", Member{UserID: uid, RoleIDs: []ids.ID{rMute, rMod}}, false, []Overwrite{ow("role", rMute, 0, SendMessages), ow("role", rMod, SendMessages, 0)}, ViewChannel | SendMessages | ReadMessageHistory | Connect | KickMembers | ManageMessages},
		{"member deny beats role allow", Member{UserID: uid, RoleIDs: []ids.ID{rMod}}, false, []Overwrite{ow("role", rMod, SendMessages, 0), ow("member", uid, 0, SendMessages)}, ViewChannel | ReadMessageHistory | Connect | KickMembers | ManageMessages},
		{"member allow beats everyone deny", Member{UserID: uid}, false, []Overwrite{ow("role", srv, 0, ViewChannel), ow("member", uid, ViewChannel, 0)}, ViewChannel | SendMessages | ReadMessageHistory | Connect},
		{"other member overwrite ignored", Member{UserID: uid}, false, []Overwrite{ow("member", ids.ID(99), 0, ViewChannel)}, ViewChannel | SendMessages | ReadMessageHistory | Connect},
		{"timeout keeps only view+history", Member{UserID: uid, RoleIDs: []ids.ID{rMod}, TimedOut: true}, false, nil, ViewChannel | ReadMessageHistory},
		{"timeout on hidden channel is nothing", Member{UserID: uid, TimedOut: true}, false, []Overwrite{ow("role", srv, 0, ViewChannel)}, 0},
		{"admin role ignores overwrites", Member{UserID: uid, RoleIDs: []ids.ID{ids.ID(7)}}, false, []Overwrite{ow("role", srv, 0, ViewChannel)}, All},
	}
	adminRoles := append(roles, Role{ID: 7, Position: 9, Permissions: Administrator})
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			rs := roles
			if c.name == "admin role ignores overwrites" {
				rs = adminRoles
			}
			got := Compute(srv, rs, c.m, c.owner, c.ows)
			if got != c.want {
				t.Errorf("got %d want %d", got, c.want)
			}
		})
	}
}

func TestBaseTimeout(t *testing.T) {
	mod := Member{UserID: uid, RoleIDs: []ids.ID{rMod}}
	if got := Base(srv, roles, mod, false); !got.Has(KickMembers) {
		t.Errorf("moderator base %d", got)
	}
	mod.TimedOut = true
	if got := Base(srv, roles, mod, false); got != ViewChannel|ReadMessageHistory {
		t.Errorf("timed-out moderator keeps %d", got)
	}
	adminRoles := append(roles, Role{ID: 7, Position: 9, Permissions: Administrator})
	if got := Base(srv, adminRoles, Member{UserID: uid, RoleIDs: []ids.ID{7}, TimedOut: true}, false); got != All {
		t.Errorf("administrators are not held by a timeout: %d", got)
	}
}

func TestHierarchy(t *testing.T) {
	if HighestPosition(roles, Member{RoleIDs: []ids.ID{rMute, rMod}}) != 5 || HighestPosition(roles, Member{}) != 0 {
		t.Error("highest position")
	}
	if !CanManageRole(5, false, Role{Position: 2}) || CanManageRole(5, false, Role{Position: 5}) || !CanManageRole(0, true, Role{Position: 99}) {
		t.Error("manage role")
	}
	if !CanActOn(5, 2, false, false) || CanActOn(5, 5, false, false) || CanActOn(9, 0, false, true) || !CanActOn(0, 9, true, false) {
		t.Error("act on")
	}
	if len(Names) != 44 {
		t.Errorf("names = %d", len(Names))
	}
	var all Perm
	for _, p := range Names {
		all |= p
	}
	if all != All {
		t.Error("Names must cover All")
	}
}

// A thread answers to its parent channel, except that talking in it is its
// own permission and a private one is only for the people in it and those who
// manage threads.
func TestThreadPermissions(t *testing.T) {
	member := ViewChannel | ReadMessageHistory | SendMessages | SendMessagesInThreads | CreatePublicThreads
	if got := Thread(member, false, false); !got.Has(ViewChannel|SendMessages) || got.Has(ManageThreads) {
		t.Errorf("public thread for a member: %d", got)
	}
	// can talk in the channel but not in its threads
	if got := Thread(ViewChannel|SendMessages, false, true); got.Has(SendMessages) || !got.Has(ViewChannel) {
		t.Errorf("Send Messages alone sends in threads: %d", got)
	}
	// can talk in threads though not in the channel itself
	if got := Thread(ViewChannel|SendMessagesInThreads, false, false); !got.Has(SendMessages) {
		t.Errorf("Send Messages in Threads does not send: %d", got)
	}
	if Thread(member, true, false) != 0 {
		t.Error("an outsider sees a private thread")
	}
	if !Thread(member, true, true).Has(ViewChannel) {
		t.Error("a member of a private thread cannot see it")
	}
	if !Thread(member|ManageThreads, true, false).Has(ViewChannel) {
		t.Error("Manage Threads does not see private threads")
	}
	if Thread(SendMessagesInThreads, false, true) != 0 {
		t.Error("a thread of a channel one cannot see is visible")
	}
	if Thread(All, true, false) != All {
		t.Error("an administrator is held back in a thread")
	}
}

// Quarantine holds a member as a timeout does.
func TestQuarantine(t *testing.T) {
	srv := ids.ID(1)
	everyone := []Role{{ID: srv, Permissions: DefaultEveryone}}
	if got := Base(srv, everyone, Member{UserID: 9, Quarantined: true}, false); got != DefaultEveryone&TimedOutKeep {
		t.Errorf("quarantined base: %d", got)
	}
}
