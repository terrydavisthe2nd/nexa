package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// The moderation panel's side of Nexa: the staff (Nexa admins) look
// through accounts, act on them, work the case threads, read the log and
// send announcements. The member's side is in nexa.go; the actions themselves
// are in moderation_actions.go.

func (s *Server) moderationRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/moderation/summary", s.staff(rankMod, s.handleModSummary))
	mux.Handle("GET /api/v1/moderation/users", s.staff(rankMod, s.handleModUsers))
	mux.Handle("GET /api/v1/moderation/users/{id}", s.staff(rankMod, s.handleModUser))
	mux.Handle("GET /api/v1/moderation/users/{id}/alts", s.staff(rankAdmin, s.handleModAlts))
	mux.Handle("GET /api/v1/moderation/users/{id}/calls", s.staff(rankAdmin, s.handleModUserCalls))
	mux.Handle("GET /api/v1/moderation/calls", s.staff(rankAdmin, s.handleModCalls))
	mux.Handle("GET /api/v1/moderation/users/{id}/purge-preview", s.staff(rankDeveloper, s.handleModPurgePreview))
	mux.Handle("POST /api/v1/moderation/users/{id}/actions", s.staff(rankMod, s.handleModAction))
	mux.Handle("GET /api/v1/moderation/cases", s.staff(rankMod, s.handleModCases))
	mux.Handle("GET /api/v1/moderation/cases/{id}", s.staff(rankMod, s.handleModCase))
	mux.Handle("POST /api/v1/moderation/cases/{id}/messages", s.staff(rankMod, s.handleModReply))
	mux.Handle("POST /api/v1/moderation/cases/{id}/lift", s.staff(rankMod, s.handleModLift))
	mux.Handle("POST /api/v1/moderation/cases/{id}/close", s.staff(rankMod, s.handleModClose(true)))
	mux.Handle("POST /api/v1/moderation/cases/{id}/reopen", s.staff(rankMod, s.handleModClose(false)))
	mux.Handle("GET /api/v1/moderation/log", s.staff(rankMod, s.handleModLog))
	mux.Handle("GET /api/v1/moderation/announcements", s.staff(rankAdmin, s.handleModAnnouncements))
	mux.Handle("POST /api/v1/moderation/announcements", s.staff(rankAdmin, s.handleModAnnounce))
	mux.Handle("DELETE /api/v1/moderation/announcements/{id}", s.staff(rankAdmin, s.handleModAnnouncementDelete))
}

// Why an account cannot be acted on, as the codes the panel shows.
const (
	blockSelf    = "self"
	blockSystem  = "system"
	blockDeleted = "deleted"
	blockOwner   = "owner"
	blockStaff   = "staff"
)

// moderationBlock says why me may not act on target, or "" when they may.
// Staff of any rank are out of reach until the owner takes their rank away,
// and the owner's can never be; so nobody can use the panel on the owner, and
// a moderator cannot quietly mute or ban another.
func (s *Server) moderationBlock(ctx context.Context, me, target *store.User) string {
	switch {
	case target.ID == me.ID:
		return blockSelf
	case notAPerson(target):
		return blockSystem
	case target.Flags&store.FlagDeleted != 0:
		return blockDeleted
	}
	if owner, err := s.Store.InstanceOwnerID(ctx); err == nil && owner == target.ID {
		return blockOwner
	}
	if target.Flags&store.StaffFlags != 0 {
		return blockStaff
	}
	return ""
}

func blockError(code string) *Error {
	switch code {
	case blockSelf:
		return E(http.StatusBadRequest, "moderate_self", "You cannot moderate your own account.")
	case blockSystem:
		return E(http.StatusForbidden, "moderate_system", "Nexa is the system account and cannot be moderated.")
	case blockDeleted:
		return E(http.StatusGone, "moderate_deleted", "That account has been deleted.")
	case blockOwner:
		return E(http.StatusForbidden, "moderate_owner", "The owner of Nexa cannot be moderated.")
	default:
		return E(http.StatusForbidden, "moderate_staff", "Staff cannot be moderated. The owner has to take their rank away first.")
	}
}

func (s *Server) handleModSummary(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	counts, err := s.Store.CountModCases(ctx, time.Now().UnixMilli())
	if err != nil {
		WriteError(w, err)
		return
	}
	owner, _ := s.Store.InstanceOwnerID(ctx)
	var nexa ids.ID
	if sys, err := s.systemUser(ctx); err == nil {
		nexa = sys.ID
	}
	staff, err := s.Store.StaffIDs(ctx, store.StaffFlags)
	if err != nil {
		WriteError(w, err)
		return
	}
	me := auth.UserFrom(ctx)
	var bugs *store.BugCounts
	if s.rankOf(ctx, me) >= rankAdmin {
		bugs = s.bugCountsFor(ctx)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"counts": counts, "bugs": bugs, "unread": s.modUnreadFor(ctx, me.ID), "owner_id": owner, "nexa_id": nexa, "staff_ids": staff, "people": s.people(ctx, staff...)})
}

// modUserView is one account in the grid or at the top of its case list:
// its public profile and where it stands.
type modUserView struct {
	store.PublicUser
	AdminFlags    int64  `json:"admin_flags"`
	CaseCount     int    `json:"case_count"`
	OpenCases     int    `json:"open_cases"`
	AwaitingCases int    `json:"awaiting_cases"`
	LastSeenAt    *int64 `json:"last_seen_at"`
	// AltCount is how many other accounts have used one of this account's
	// addresses (alt accounts, or people behind the same network).
	AltCount int              `json:"alt_count"`
	Mute     *restrictionView `json:"mute"`
	Ban      *restrictionView `json:"ban"`
	// Blocked is why this moderator cannot act on the account ("" when they can).
	Blocked string `json:"blocked"`
	// Widgets are the profile's widgets, on one account's page only.
	Widgets json.RawMessage `json:"widgets,omitempty"`
	// Presence is how the account looks to everyone right now: online, idle,
	// do not disturb or offline (as someone invisible looks), and what they
	// are doing. Their custom status is in the profile fields, kept even
	// while they are away.
	Presence *modPresence `json:"presence,omitempty"`
}

// modPresence is an account's presence as the panel shows it.
type modPresence struct {
	Status     string             `json:"status"`
	Activities []gateway.Activity `json:"activities,omitempty"`
}

// presenceFor is u's presence as anyone else sees it, or nil without a gateway.
func (s *Server) presenceFor(ctx context.Context, u *store.User) *modPresence {
	if s.modHub == nil {
		return nil
	}
	p := s.modHub.PresenceOf(ctx, u)
	return &modPresence{Status: p.Status, Activities: p.Activities}
}

func (s *Server) handleModUsers(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 120 {
		limit = 60
	}
	f := store.ModUserFilter{Query: q.Get("q"), Show: q.Get("show"), Sort: q.Get("sort"), Cursor: q.Get("cursor"), Limit: limit, Now: time.Now().UnixMilli()}
	if f.Show == "online" && s.modHub != nil {
		f.Online = s.modHub.OnlineUsers()
	}
	list, err := s.Store.ModUsers(ctx, f)
	if err != nil {
		WriteError(w, err)
		return
	}
	owner, _ := s.Store.InstanceOwnerID(ctx)
	listIDs := make([]ids.ID, 0, len(list))
	for _, m := range list {
		listIDs = append(listIDs, m.ID)
	}
	alts, err := s.Store.AltCounts(ctx, listIDs)
	if err != nil {
		WriteError(w, err)
		return
	}
	out := make([]modUserView, 0, len(list))
	for _, m := range list {
		v := modUserView{PublicUser: m.PublicUser, AdminFlags: m.AdminFlags, CaseCount: m.CaseCount, OpenCases: m.OpenCases, AwaitingCases: m.AwaitingCase, LastSeenAt: m.LastSeenAt,
			Mute: shortRestriction(m.Mute), Ban: shortRestriction(m.Ban), AltCount: alts[m.ID]}
		v.Presence = s.presenceFor(ctx, &store.User{ID: m.ID, PresencePref: m.PresencePref, StatusText: m.StatusText, StatusEmoji: m.StatusEmoji, StatusExpiresAt: m.StatusExpiresAt})
		switch {
		case m.ID == me.ID:
			v.Blocked = blockSelf
		case m.ID == owner:
			v.Blocked = blockOwner
		case m.AdminFlags&store.StaffFlags != 0:
			v.Blocked = blockStaff
		}
		out = append(out, v)
	}
	next := ""
	if len(list) > 0 && len(list) >= limit {
		last := list[len(list)-1]
		if f.Sort == "name" {
			next = last.Username
		} else {
			next = last.ID.String()
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"users": out, "next_cursor": next})
}

// shortRestriction is a restriction without its case's reason, which the grid
// has no room for.
func shortRestriction(r *store.Restriction) *restrictionView {
	if r == nil {
		return nil
	}
	return &restrictionView{Kind: r.Kind, CaseID: r.CaseID, ExpiresAt: r.ExpiresAt, CreatedAt: r.CreatedAt}
}

// modTarget loads the account a staff route names. Deleted accounts are found
// too: their cases stay readable.
func (s *Server) modTarget(w http.ResponseWriter, r *http.Request) *store.User {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	u, err := s.Store.UserByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	return u
}

func (s *Server) modUserDetail(ctx context.Context, me, u *store.User) (modUserView, error) {
	now := time.Now().UnixMilli()
	v := modUserView{PublicUser: u.Public(), AdminFlags: u.Flags, Blocked: s.moderationBlock(ctx, me, u), Presence: s.presenceFor(ctx, u)}
	if seen, err := s.Store.LastSeen(ctx, u.ID); err == nil {
		v.LastSeenAt = seen
	}
	if alts, err := s.Store.AltCounts(ctx, []ids.ID{u.ID}); err == nil {
		v.AltCount = alts[u.ID]
	}
	if raw, err := s.Store.UserWidgets(ctx, u.ID); err == nil && raw != "" {
		v.Widgets = json.RawMessage(raw)
	}
	mute, ban, err := s.Store.ActiveRestrictions(ctx, u.ID, now)
	if err != nil {
		return v, err
	}
	for _, pair := range []struct {
		r   *store.Restriction
		dst **restrictionView
	}{{mute, &v.Mute}, {ban, &v.Ban}} {
		if pair.r == nil {
			continue
		}
		rv := shortRestriction(pair.r)
		if c, err := s.Store.ModCaseByID(ctx, pair.r.CaseID); err == nil {
			rv.CaseNumber, rv.Reason, rv.ModeratorID = c.Number, c.Reason, c.ModeratorID
		}
		*pair.dst = rv
	}
	return v, nil
}

// handleModUser is one account with every case about it, newest first.
func (s *Server) handleModUser(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	u := s.modTarget(w, r)
	if u == nil {
		return
	}
	if notAPerson(u) {
		WriteError(w, blockError(blockSystem))
		return
	}
	view, err := s.modUserDetail(ctx, me, u)
	if err != nil {
		WriteError(w, err)
		return
	}
	cases, err := s.Store.ModCasesForUser(ctx, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	caseIDs := make([]ids.ID, 0, len(cases))
	who := []ids.ID{u.ID}
	for _, c := range cases {
		caseIDs = append(caseIDs, c.ID)
		who = append(who, casePeople(c)...)
	}
	last, err := s.Store.LastModMessages(ctx, caseIDs)
	if err != nil {
		WriteError(w, err)
		return
	}
	cs := s.caseStateFor(ctx, u.ID)
	views := make([]staffCase, 0, len(cases))
	for _, c := range cases {
		views = append(views, toStaffCase(c, cs, last[c.ID]))
		if m := last[c.ID]; m != nil {
			who = append(who, m.AuthorID)
		}
	}
	view.CaseCount = len(cases)
	for _, c := range cases {
		if c.Status == store.CaseOpen {
			view.OpenCases++
			if c.Awaiting == store.AwaitStaff {
				view.AwaitingCases++
			}
		}
	}
	// where else their profile shows: the servers they own, and the ones they
	// go by a nickname or a picture of their own in
	servers, err := s.Store.ModMemberships(ctx, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// and the sticker and emoji packs they made
	packs, err := s.personPacks(r, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"user": view, "cases": views, "people": s.people(ctx, who...), "servers": servers, "packs": packs})
}

// modAlt is one account that shares an address with the one asked about.
type modAlt struct {
	User         modUserView `json:"user"`
	SharedIPs    int         `json:"shared_ips"`
	LastSharedAt int64       `json:"last_shared_at"`
}

// handleModAlts lists the accounts seen at the same addresses as this one.
// The addresses themselves are not shown, only how many are shared.
func (s *Server) handleModAlts(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	u := s.modTarget(w, r)
	if u == nil {
		return
	}
	alts, err := s.Store.AltAccounts(ctx, u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	out := make([]modAlt, 0, len(alts))
	for _, a := range alts {
		au, err := s.Store.UserByID(ctx, a.UserID)
		if err != nil {
			continue
		}
		view, err := s.modUserDetail(ctx, me, au)
		if err != nil {
			WriteError(w, err)
			return
		}
		out = append(out, modAlt{User: view, SharedIPs: a.SharedIPs, LastSharedAt: a.LastSharedAt})
	}
	WriteJSON(w, http.StatusOK, map[string]any{"alts": out})
}

func (s *Server) handleModPurgePreview(w http.ResponseWriter, r *http.Request) {
	u := s.modTarget(w, r)
	if u == nil {
		return
	}
	counts, err := s.Store.PurgePreview(r.Context(), u.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, counts)
}

// handleModCases pages cases for the Active Cases tab, with the counts every
// view of it shows.
func (s *Server) handleModCases(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	now := time.Now().UnixMilli()
	limit, _ := strconv.Atoi(q.Get("limit"))
	beforeAt, _ := strconv.ParseInt(q.Get("before_at"), 10, 64)
	beforeID, _ := ids.Parse(q.Get("before_id"))
	view := q.Get("view")
	if !OneOf(view, "needs_reply", "open", "in_effect", "closed", "all") {
		view = "open"
	}
	cases, err := s.Store.ModCaseList(ctx, store.ModCaseFilter{View: view, BeforeAt: beforeAt, BeforeID: beforeID, Limit: limit, Now: now})
	if err != nil {
		WriteError(w, err)
		return
	}
	caseIDs := make([]ids.ID, 0, len(cases))
	var who []ids.ID
	for _, c := range cases {
		caseIDs = append(caseIDs, c.ID)
		who = append(who, casePeople(c)...)
	}
	last, err := s.Store.LastModMessages(ctx, caseIDs)
	if err != nil {
		WriteError(w, err)
		return
	}
	states := map[ids.ID]caseState{}
	views := make([]staffCase, 0, len(cases))
	for _, c := range cases {
		st, ok := states[c.UserID]
		if !ok {
			st = s.caseStateFor(ctx, c.UserID)
			states[c.UserID] = st
		}
		views = append(views, toStaffCase(c, st, last[c.ID]))
		if m := last[c.ID]; m != nil {
			who = append(who, m.AuthorID)
		}
	}
	counts, err := s.Store.CountModCases(ctx, now)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"cases": views, "counts": counts, "people": s.people(ctx, who...)})
}

// staffCaseFor loads the case a staff route names.
func (s *Server) staffCaseFor(w http.ResponseWriter, r *http.Request) *store.ModCase {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	c, err := s.Store.ModCaseByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	return c
}

// handlesCase says whether me may lift, close or reopen c, and refuses when
// not: a mod handles only the cases they opened, admins and above any.
func (s *Server) handlesCase(w http.ResponseWriter, r *http.Request, me *store.User, c *store.ModCase) bool {
	if c.ModeratorID == me.ID || s.rankOf(r.Context(), me) >= rankAdmin {
		return true
	}
	WriteError(w, E(http.StatusForbidden, "not_your_case", "Mods can only lift, close or reopen the cases they opened."))
	return false
}

func (s *Server) handleModCase(w http.ResponseWriter, r *http.Request) {
	c := s.staffCaseFor(w, r)
	if c == nil {
		return
	}
	out, err := s.threadResponse(r.Context(), c, true)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleModReply is a member of staff writing in a case's thread. The member
// sees it as coming from Nexa, with the name of whoever wrote it.
func (s *Server) handleModReply(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	c := s.staffCaseFor(w, r)
	if c == nil {
		return
	}
	target, err := s.Store.UserByID(r.Context(), c.UserID)
	if err != nil || target.Flags&store.FlagDeleted != 0 {
		WriteError(w, E(http.StatusGone, "moderate_deleted", "That account has been deleted, so nobody would read a reply."))
		return
	}
	line, ok := readCaseLine(w, r)
	if !ok {
		return
	}
	msg, extra, updated, err := s.Store.AddModMessage(r.Context(), c.ID, me.ID, store.ModMsgStaff, line.Body, line.Files)
	if err != nil {
		WriteError(w, caseWriteError(err))
		return
	}
	lines := append(extra, msg)
	s.emitCase(r.Context(), updated, lines...)
	WriteJSON(w, http.StatusCreated, map[string]any{"message": msg, "messages": lines, "case": toStaffCase(updated, s.caseStateFor(r.Context(), c.UserID), msg)})
}

// handleModLift ends a case's mute or ban early, with a reason the member reads.
func (s *Server) handleModLift(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	c := s.staffCaseFor(w, r)
	if c == nil {
		return
	}
	if !s.handlesCase(w, r, me, c) {
		return
	}
	var req struct {
		Reason string `json:"reason"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	action := store.LogUnmute
	if c.Action == store.ActionBan {
		action = store.LogUnban
	}
	log := &store.ModLogEntry{Action: action, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &c.UserID, TargetUsername: c.TargetUsername, Reason: reason}
	if target, err := s.Store.UserByID(ctx, c.UserID); err == nil {
		log.TargetUsername = target.Username
	}
	updated, line, err := s.Store.ModLift(ctx, c.ID, me.ID, reason, log)
	if errors.Is(err, store.ErrNotRestricted) {
		WriteError(w, E(http.StatusConflict, "not_in_effect", "That case's mute or ban is no longer in force."))
		return
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	s.emitCase(ctx, updated, line)
	s.emitLog(ctx, log)
	WriteJSON(w, http.StatusOK, map[string]any{"case": toStaffCase(updated, s.caseStateFor(ctx, c.UserID), line)})
}

func (s *Server) handleModClose(closed bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		me := auth.UserFrom(ctx)
		c := s.staffCaseFor(w, r)
		if c == nil || !s.handlesCase(w, r, me, c) {
			return
		}
		updated, line, err := s.Store.SetModCaseClosed(ctx, c.ID, me.ID, closed)
		if err != nil {
			WriteError(w, err)
			return
		}
		if line != nil {
			s.emitCase(ctx, updated, line)
		}
		WriteJSON(w, http.StatusOK, map[string]any{"case": toStaffCase(updated, s.caseStateFor(ctx, c.UserID), line)})
	}
}

// emitLog shows a new log line to the staff as it is written.
func (s *Server) emitLog(ctx context.Context, e *store.ModLogEntry) {
	who := []ids.ID{e.ModeratorID}
	if e.TargetID != nil {
		who = append(who, *e.TargetID)
	}
	s.emitStaff(ctx, rankMod, "mod_log", map[string]any{"entry": e, "people": s.people(ctx, who...)})
}

func (s *Server) handleModLog(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	before, _ := ids.Parse(q.Get("before"))
	target, _ := ids.Parse(q.Get("target"))
	var actions []string
	for _, a := range strings.Split(q.Get("action"), ",") {
		if a = strings.TrimSpace(a); a != "" && len(actions) < 20 {
			actions = append(actions, a)
		}
	}
	entries, err := s.Store.ModLog(ctx, store.ModLogFilter{Actions: actions, TargetID: target, Query: q.Get("q"), Before: before, Limit: limit})
	if err != nil {
		WriteError(w, err)
		return
	}
	var who []ids.ID
	for _, e := range entries {
		who = append(who, e.ModeratorID)
		if e.TargetID != nil {
			who = append(who, *e.TargetID)
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"entries": entries, "people": s.people(ctx, who...)})
}

// --- announcements --------------------------------------------------------------

const (
	maxAnnouncementTitle = 120
	maxAnnouncementBody  = 4000
)

func (s *Server) handleModAnnouncements(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
	list, err := s.Store.Announcements(ctx, 0, before, 50)
	if err != nil {
		WriteError(w, err)
		return
	}
	views := make([]announcementView, 0, len(list))
	var who []ids.ID
	for _, a := range list {
		views = append(views, toAnnouncementView(a))
		who = append(who, a.AuthorID)
	}
	recipients, err := s.Store.AccountIDs(ctx)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"announcements": views, "people": s.people(ctx, who...), "recipients": len(recipients)})
}

// handleModAnnounce sends an announcement to everyone through Nexa.
func (s *Server) handleModAnnounce(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	var req struct {
		Title string `json:"title"`
		Body  string `json:"body"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	title, ok := CleanText(strings.ReplaceAll(req.Title, "\n", " "), maxAnnouncementTitle)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "title_too_long", "Titles are at most 120 characters."))
		return
	}
	body, ok := CleanText(req.Body, maxAnnouncementBody)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "body_too_long", "Announcements are at most 4000 characters."))
		return
	}
	if body == "" {
		WriteError(w, E(http.StatusBadRequest, "empty_message", "Write the announcement first."))
		return
	}
	a := &store.Announcement{AuthorID: me.ID, Title: title, Body: body}
	details, _ := json.Marshal(map[string]any{"title": title, "length": len([]rune(body))})
	log := &store.ModLogEntry{Action: store.LogAnnouncement, ModeratorID: me.ID, ModeratorUsername: me.Username, Details: details}
	if err := s.Store.CreateAnnouncement(ctx, a, log); err != nil {
		WriteError(w, err)
		return
	}
	view := toAnnouncementView(a)
	people := s.people(ctx, me.ID)
	if everyone, err := s.Store.AccountIDs(ctx); err == nil {
		s.Bcast.EmitUsers(everyone, "nexa_announcement", map[string]any{"announcement": view, "people": people})
	}
	s.emitLog(ctx, log)
	WriteJSON(w, http.StatusCreated, map[string]any{"announcement": view, "people": people})
}

// handleModAnnouncementDelete retracts an announcement from everyone's Nexa
// conversation. The log keeps that it was sent, and who took it back.
func (s *Server) handleModAnnouncementDelete(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	log := &store.ModLogEntry{Action: store.LogAnnouncementDelete, ModeratorID: me.ID, ModeratorUsername: me.Username}
	if _, err := s.Store.DeleteAnnouncement(ctx, id, log); err != nil {
		WriteError(w, err)
		return
	}
	if everyone, err := s.Store.AccountIDs(ctx); err == nil {
		s.Bcast.EmitUsers(everyone, "nexa_announcement_delete", map[string]any{"id": id})
	}
	s.emitLog(ctx, log)
	w.WriteHeader(http.StatusNoContent)
}
