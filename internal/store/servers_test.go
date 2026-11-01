package store

import (
	"context"
	"errors"
	"testing"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
)

func TestServerLifecycleAndPerms(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	owner, mod, plain, outsider := mkUser(t, s, "owner"), mkUser(t, s, "mod"), mkUser(t, s, "plain"), mkUser(t, s, "outsider")
	srv, err := s.CreateServer(ctx, owner.ID, "My Server", DefaultNames{})
	if err != nil {
		t.Fatal(err)
	}
	chans, _ := s.ServerChannels(ctx, srv.ID)
	if len(chans) != 4 || srv.SystemChannelID == nil {
		t.Fatalf("defaults: %d channels", len(chans))
	}
	var general, textCat *Channel
	for _, c := range chans {
		if c.Name == "general" {
			general = c
		}
		if c.Name == "Text Channels" {
			textCat = c
		}
	}
	if general == nil || textCat == nil || general.ParentID == nil || *general.ParentID != textCat.ID || general.KeyConvID != srv.ID {
		t.Fatalf("general/category wiring: %+v", general)
	}
	roles, _ := s.Roles(ctx, srv.ID)
	if len(roles) != 1 || roles[0].ID != srv.ID || roles[0].Name != "@everyone" || roles[0].Permissions != perms.DefaultEveryone {
		t.Fatalf("everyone role: %+v", roles)
	}
	for _, u := range []*User{mod, plain} {
		if _, err := s.AddMember(ctx, srv.ID, u.ID); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := s.AddMember(ctx, srv.ID, plain.ID); !errors.Is(err, ErrAlreadyExists) {
		t.Error("double join")
	}
	list, _ := s.ServersForUser(ctx, plain.ID)
	if len(list) != 1 || list[0].ID != srv.ID {
		t.Error("servers for user")
	}
	// owner has everything; plain has defaults; outsider nothing
	if p, _ := s.ServerPerms(ctx, srv.ID, owner.ID); p != perms.All {
		t.Error("owner perms")
	}
	if p, _ := s.ServerPerms(ctx, srv.ID, plain.ID); p != perms.DefaultEveryone {
		t.Errorf("plain perms %d", p)
	}
	if p, _ := s.ServerPerms(ctx, srv.ID, outsider.ID); p != 0 {
		t.Error("outsider perms")
	}
	// mod role with manage messages
	role, err := s.CreateRole(ctx, srv.ID, "Mod", perms.DefaultEveryone|perms.ManageMessages|perms.KickMembers, 0xff0000)
	if err != nil || role.Position != 1 {
		t.Fatalf("create role: %v %+v", err, role)
	}
	_ = s.AddMemberRole(ctx, srv.ID, mod.ID, role.ID)
	m, _ := s.Member(ctx, srv.ID, mod.ID)
	if len(m.RoleIDs) != 1 || m.RoleIDs[0] != role.ID {
		t.Errorf("member roles: %v", m.RoleIDs)
	}
	if p, _, _ := s.ChannelPerms(ctx, general.ID, mod.ID); !p.Has(perms.ManageMessages) || !p.Has(perms.SendMessages) {
		t.Error("mod channel perms")
	}
	// private channel: deny @everyone view, allow the mod role
	private, err := s.CreateServerChannel(ctx, srv.ID, "text", "staff", &textCat.ID, "")
	if err != nil || private.Position != 1 {
		t.Fatalf("create channel: %v %+v", err, private)
	}
	_ = s.SetOverwrite(ctx, Overwrite{ChannelID: private.ID, TargetID: srv.ID, TargetType: "role", Deny: perms.ViewChannel})
	_ = s.SetOverwrite(ctx, Overwrite{ChannelID: private.ID, TargetID: role.ID, TargetType: "role", Allow: perms.ViewChannel})
	if p, _, _ := s.ChannelPerms(ctx, private.ID, plain.ID); p != 0 {
		t.Errorf("plain should not see staff: %d", p)
	}
	if p, _, _ := s.ChannelPerms(ctx, private.ID, mod.ID); !p.Has(perms.ViewChannel) {
		t.Error("mod should see staff")
	}
	viewers, _ := s.ChannelViewers(ctx, private.ID)
	if len(viewers) != 2 {
		t.Errorf("viewers = %v", viewers)
	}
	ent, _ := s.Entitled(ctx, private.ID)
	if len(ent) != 2 {
		t.Errorf("entitled for private channel = %v", ent)
	}
	ent, _ = s.Entitled(ctx, srv.ID)
	if len(ent) != 3 {
		t.Errorf("entitled for server = %v", ent)
	}
	mine, _ := s.ChannelsForUser(ctx, plain.ID)
	if len(mine) != 2 { // general + General voice (categories excluded, staff hidden)
		t.Errorf("channels for plain = %d", len(mine))
	}
	vis, _ := s.ViewableServerChannels(ctx, srv.ID, mod.ID)
	if len(vis) != 5 || !vis[private.ID].Has(perms.ViewChannel) {
		t.Errorf("viewable for mod = %d", len(vis))
	}
	// timeout strips send
	until := time.Now().Add(time.Hour).UnixMilli()
	up := &until
	_ = s.UpdateMember(ctx, srv.ID, mod.ID, MemberPatch{TimeoutUntil: &up, Nickname: ptr("moddy")})
	if p, _, _ := s.ChannelPerms(ctx, general.ID, mod.ID); p.Has(perms.SendMessages) || !p.Has(perms.ViewChannel) {
		t.Errorf("timed out perms %d", p)
	}
	m, _ = s.Member(ctx, srv.ID, mod.ID)
	if m.Nickname != "moddy" {
		t.Error("nickname")
	}
	// private channel gets its own epoch -> key conv switches
	if err := s.CreateEpoch(ctx, &KeyEpoch{ConvID: private.ID, Epoch: 0, CreatedBy: owner.ID}); err != nil {
		t.Fatal(err)
	}
	ch, _ := s.ChannelByID(ctx, private.ID)
	if ch.KeyConvID != private.ID {
		t.Error("key conv should be the channel once it has epochs")
	}
	chans, _ = s.ServerChannels(ctx, srv.ID)
	for _, c := range chans {
		if c.ID == private.ID && c.KeyConvID != private.ID {
			t.Error("ServerChannels key conv")
		}
	}
	// reorder: move staff above general
	if err := s.SetChannelPositions(ctx, srv.ID, []ChannelPosition{{ID: private.ID, Position: 0}, {ID: general.ID, Position: 1}}); err != nil {
		t.Fatal(err)
	}
	chans, _ = s.ServerChannels(ctx, srv.ID)
	var order []string
	for _, c := range chans {
		if c.ParentID != nil && *c.ParentID == textCat.ID {
			order = append(order, c.Name)
		}
	}
	if len(order) != 2 || order[0] != "staff" {
		t.Errorf("order after reorder: %v", order)
	}
	// deleting the category unparents children
	if err := s.DeleteChannel(ctx, textCat.ID); err != nil {
		t.Fatal(err)
	}
	ch, _ = s.ChannelByID(ctx, general.ID)
	if ch.ParentID != nil {
		t.Error("children should be unparented")
	}
	// role hierarchy helpers via store data
	if err := s.DeleteRole(ctx, srv.ID); !errors.Is(err, ErrConflict) {
		t.Error("@everyone must not be deletable")
	}
	if err := s.DeleteRole(ctx, role.ID); err != nil {
		t.Fatal(err)
	}
	m, _ = s.Member(ctx, srv.ID, mod.ID)
	if len(m.RoleIDs) != 0 {
		t.Error("role assignments should cascade")
	}
	ows, _ := s.Overwrites(ctx, private.ID)
	for _, ow := range ows {
		if ow.TargetID == role.ID {
			t.Error("role overwrites should be removed")
		}
	}
	// members listing + search
	members, _ := s.Members(ctx, srv.ID, 0, 10, "mod")
	if len(members) != 1 || members[0].UserID != mod.ID {
		t.Errorf("member search: %+v", members)
	}
	n, _ := s.MemberCount(ctx, srv.ID)
	if n != 3 {
		t.Errorf("count = %d", n)
	}
	if err := s.RemoveMember(ctx, srv.ID, plain.ID); err != nil {
		t.Fatal(err)
	}
	if ok, _ := s.IsMember(ctx, srv.ID, plain.ID); ok {
		t.Error("removed")
	}
	// transfer + delete
	if err := s.UpdateServer(ctx, srv.ID, ServerPatch{OwnerID: &mod.ID, Name: ptr("Renamed")}); err != nil {
		t.Fatal(err)
	}
	got, _ := s.ServerByID(ctx, srv.ID)
	if got.OwnerID != mod.ID || got.Name != "Renamed" {
		t.Error("patch")
	}
	if err := s.DeleteServer(ctx, srv.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ServerByID(ctx, srv.ID); !errors.Is(err, ErrNotFound) {
		t.Error("deleted")
	}
	if _, err := s.ChannelByID(ctx, general.ID); !errors.Is(err, ErrNotFound) {
		t.Error("channels cascade")
	}
	if eps, _ := s.Epochs(ctx, private.ID); len(eps) != 0 {
		t.Error("key epochs of deleted channels should be gone")
	}
}

func TestInvitesBansAudit(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	owner, u := mkUser(t, s, "owner"), mkUser(t, s, "u")
	srv, _ := s.CreateServer(ctx, owner.ID, "S", DefaultNames{})
	in, err := s.CreateInvite(ctx, &srv.ID, srv.SystemChannelID, owner.ID, 0, 2)
	if err != nil || len(in.Code) != 8 {
		t.Fatalf("invite: %v %+v", err, in)
	}
	got, err := s.InviteByCode(ctx, in.Code)
	if err != nil || got.MaxUses != 2 {
		t.Fatal("by code", err)
	}
	if err := s.UseInvite(ctx, in.Code); err != nil {
		t.Fatal(err)
	}
	if err := s.UseInvite(ctx, in.Code); err != nil {
		t.Fatal(err)
	}
	if err := s.UseInvite(ctx, in.Code); !errors.Is(err, ErrNotFound) {
		t.Error("exhausted invite must fail")
	}
	if _, err := s.InviteByCode(ctx, in.Code); !errors.Is(err, ErrNotFound) {
		t.Error("exhausted invite hidden")
	}
	expired, _ := s.CreateInvite(ctx, &srv.ID, nil, owner.ID, 1, 0)
	exp := nowMs() - 5000
	_, _ = s.db.W.Exec(`UPDATE invites SET expires_at = ? WHERE code = ?`, exp, expired.Code)
	if _, err := s.InviteByCode(ctx, expired.Code); !errors.Is(err, ErrNotFound) {
		t.Error("expired invite hidden")
	}
	live, _ := s.CreateInvite(ctx, &srv.ID, nil, owner.ID, 3600, 0)
	list, _ := s.InvitesForServer(ctx, srv.ID)
	if len(list) != 1 || list[0].Code != live.Code {
		t.Errorf("live invites: %+v", list)
	}
	if err := s.DeleteInvite(ctx, live.Code); err != nil {
		t.Fatal(err)
	}
	// bans
	if err := s.Ban(ctx, &Ban{ServerID: srv.ID, UserID: u.ID, Reason: "spam", BannedBy: owner.ID}); err != nil {
		t.Fatal(err)
	}
	if ok, _ := s.IsBanned(ctx, srv.ID, u.ID); !ok {
		t.Error("banned")
	}
	bans, _ := s.Bans(ctx, srv.ID)
	if len(bans) != 1 || bans[0].Reason != "spam" {
		t.Error("bans list")
	}
	if err := s.Unban(ctx, srv.ID, u.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.Unban(ctx, srv.ID, u.ID); !errors.Is(err, ErrNotFound) {
		t.Error("unban twice")
	}
	// audit
	for i := 0; i < 3; i++ {
		if err := s.Audit(ctx, &AuditEntry{ServerID: srv.ID, ActorID: owner.ID, Action: "member_ban", TargetType: "user", TargetID: &u.ID, Reason: "r"}); err != nil {
			t.Fatal(err)
		}
	}
	entries, _ := s.AuditEntries(ctx, srv.ID, 0, 2)
	if len(entries) != 2 || entries[0].ID < entries[1].ID || string(entries[0].Changes) != "{}" {
		t.Errorf("audit page: %+v", entries)
	}
	more, _ := s.AuditEntries(ctx, srv.ID, entries[1].ID, 10)
	if len(more) != 1 {
		t.Error("audit paging")
	}
	_ = ids.New
}
