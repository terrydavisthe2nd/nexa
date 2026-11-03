package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"net/http"
	"sort"
	"strconv"
	"sync"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/textutil"
)

// Nexa is the account the service itself speaks as. It delivers moderation notices
// and announcements, and each notice opens onto its case: a private thread
// with the moderation team where the member can appeal or ask questions.
//
// This file is the member's side of that: what their app is told on arrival,
// their Nexa conversation, and their own case threads. The staff's side is in
// moderation.go. Nothing here is end-to-end encrypted — a case thread is a
// conversation with Nexa itself — and the app says so where one is
// written.

// nexaMemberRoutes are the member's own side of Nexa. They stay open to muted
// and banned accounts (moderation_gate.go): a case thread is where a mute or a
// ban is appealed.
var nexaMemberRoutes = []string{
	"GET /api/v1/nexa",
	"GET /api/v1/nexa/feed",
	"POST /api/v1/nexa/seen",
	"GET /api/v1/nexa/cases/{id}",
	"POST /api/v1/nexa/cases/{id}/messages",
	"POST /api/v1/nexa/cases/{id}/read",
	"POST /api/v1/nexa/cases/{id}/ack",
}

func (s *Server) nexaRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/nexa", s.authed(s.handleNexa))
	mux.Handle("GET /api/v1/nexa/feed", s.authed(s.handleNexaFeed))
	mux.Handle("POST /api/v1/nexa/seen", s.authed(s.handleNexaSeen))
	mux.Handle("GET /api/v1/nexa/cases/{id}", s.authed(s.handleNexaCase))
	mux.Handle("POST /api/v1/nexa/cases/{id}/messages", s.authed(s.handleNexaReply))
	mux.Handle("POST /api/v1/nexa/cases/{id}/read", s.authed(s.handleNexaRead))
	mux.Handle("POST /api/v1/nexa/cases/{id}/ack", s.authed(s.handleNexaAck))
}

// maxCaseMessage is the longest line a member or staff can write in a thread.
const maxCaseMessage = 2000

// --- people -------------------------------------------------------------------

// person is as much of an account as the moderation views need: enough to
// draw a name and avatar and open the full profile. Responses carry a map of
// them, keyed by id, and the views refer to people by id.
type person struct {
	ID          ids.ID  `json:"id"`
	Username    string  `json:"username"`
	DisplayName string  `json:"display_name"`
	AvatarID    *ids.ID `json:"avatar_id"`
	Flags       int64   `json:"flags"`
	Deleted     bool    `json:"deleted,omitempty"`
}

func personOf(u *store.User) person {
	return person{ID: u.ID, Username: u.Username, DisplayName: u.DisplayName, AvatarID: u.AvatarID, Flags: u.Flags & (store.StaffFlags | store.FlagSystem), Deleted: u.Flags&store.FlagDeleted != 0}
}

// people loads the named accounts; ids that are zero or gone are left out.
func (s *Server) people(ctx context.Context, list ...ids.ID) map[string]person {
	out := map[string]person{}
	want := make([]ids.ID, 0, len(list))
	seen := map[ids.ID]bool{}
	for _, id := range list {
		if !id.IsZero() && !seen[id] {
			seen[id] = true
			want = append(want, id)
		}
	}
	if len(want) == 0 {
		return out
	}
	users, err := s.Store.UsersByIDs(ctx, want)
	if err != nil {
		s.Log.Warn("moderation: load people", "err", err)
		return out
	}
	for _, u := range users {
		out[u.ID.String()] = personOf(u)
	}
	return out
}

// --- views --------------------------------------------------------------------

// memberCase is a case as the member it is about sees it: what was done, why,
// by whom, and where it stands.
type memberCase struct {
	ID          ids.ID          `json:"id"`
	Number      int64           `json:"number"`
	Action      string          `json:"action"`
	Reason      string          `json:"reason"`
	Details     json.RawMessage `json:"details"`
	ModeratorID ids.ID          `json:"moderator_id"`
	CreatedAt   int64           `json:"created_at"`
	ExpiresAt   *int64          `json:"expires_at"`
	LiftedAt    *int64          `json:"lifted_at"`
	LiftedBy    *ids.ID         `json:"lifted_by"`
	LiftReason  string          `json:"lift_reason"`
	Status      string          `json:"status"`
	// InEffect is true while the case's mute or ban holds; Ended once it has
	// run out or a later case replaced it (a lift sets LiftedAt instead).
	InEffect       bool  `json:"in_effect"`
	Ended          bool  `json:"ended"`
	Unread         int   `json:"unread"`
	Acknowledged   bool  `json:"acknowledged"`
	LastActivityAt int64 `json:"last_activity_at"`
}

// staffCase is a case as the staff see it: the member view, plus who it is
// about as they were when it happened, and the thread's state.
type staffCase struct {
	memberCase
	UserID            ids.ID            `json:"user_id"`
	TargetUsername    string            `json:"target_username"`
	TargetDisplayName string            `json:"target_display_name"`
	Awaiting          string            `json:"awaiting"`
	ClosedAt          *int64            `json:"closed_at"`
	ClosedBy          *ids.ID           `json:"closed_by"`
	NoticeAckAt       *int64            `json:"notice_ack_at"`
	LastMessage       *store.ModMessage `json:"last_message"`
}

// caseState is what decides the live parts of a case view: which cases'
// restrictions are in force, and how many unread staff replies each has.
type caseState struct {
	active map[ids.ID]bool
	unread map[ids.ID]int
}

func (s *Server) caseStateFor(ctx context.Context, uid ids.ID) caseState {
	st := caseState{active: map[ids.ID]bool{}, unread: map[ids.ID]int{}}
	if mute, ban, err := s.Store.ActiveRestrictions(ctx, uid, time.Now().UnixMilli()); err == nil {
		for _, r := range []*store.Restriction{mute, ban} {
			if r != nil {
				st.active[r.CaseID] = true
			}
		}
	}
	if n, err := s.Store.ModUnreadByCase(ctx, uid); err == nil {
		st.unread = n
	}
	return st
}

func toMemberCase(c *store.ModCase, st caseState) memberCase {
	v := memberCase{ID: c.ID, Number: c.Number, Action: c.Action, Reason: c.Reason, Details: c.Details, ModeratorID: c.ModeratorID,
		CreatedAt: c.CreatedAt, ExpiresAt: c.ExpiresAt, LiftedAt: c.LiftedAt, LiftedBy: c.LiftedBy, LiftReason: c.LiftReason,
		Status: c.Status, InEffect: st.active[c.ID], Unread: st.unread[c.ID], Acknowledged: c.NoticeAckAt != nil, LastActivityAt: c.LastActivityAt}
	v.Ended = c.Restricts() != "" && !v.InEffect && c.LiftedAt == nil
	return v
}

func toStaffCase(c *store.ModCase, st caseState, last *store.ModMessage) staffCase {
	v := staffCase{memberCase: toMemberCase(c, st), UserID: c.UserID, TargetUsername: c.TargetUsername, TargetDisplayName: c.TargetDisplayName,
		Awaiting: c.Awaiting, ClosedAt: c.ClosedAt, ClosedBy: c.ClosedBy, NoticeAckAt: c.NoticeAckAt, LastMessage: last}
	// how many replies the member has not read is theirs to know, not the staff's
	v.Unread = 0
	return v
}

// casePeople lists everyone a case view names.
func casePeople(c *store.ModCase) []ids.ID {
	out := []ids.ID{c.UserID, c.ModeratorID}
	if c.LiftedBy != nil {
		out = append(out, *c.LiftedBy)
	}
	if c.ClosedBy != nil {
		out = append(out, *c.ClosedBy)
	}
	return out
}

// restrictionView is a mute or ban in force, with enough of its case to say why.
type restrictionView struct {
	Kind        string `json:"kind"`
	CaseID      ids.ID `json:"case_id"`
	CaseNumber  int64  `json:"case_number"`
	Reason      string `json:"reason"`
	ModeratorID ids.ID `json:"moderator_id"`
	ExpiresAt   *int64 `json:"expires_at"`
	CreatedAt   int64  `json:"created_at"`
}

// nexaState is the account's standing as its app needs it on arrival: carried
// by /users/@me (and so by sign-in) and by the gateway's ready.
type nexaState struct {
	AccountID ids.ID           `json:"account_id"`
	Mute      *restrictionView `json:"mute"`
	Ban       *restrictionView `json:"ban"`
	// Pending are the notices not dismissed yet, oldest first; each pops up
	// until it is.
	Pending []memberCase `json:"pending"`
	Unread  int          `json:"unread"`
	// Latest is when Nexa last had anything for this account (0: never),
	// which decides whether the Nexa conversation is listed at all, where it
	// sorts among the account's conversations, and whether one it closed
	// comes back.
	Latest int64             `json:"latest"`
	People map[string]person `json:"people"`
	// OwnerID is the owner of Nexa (0: none yet), for every account: the
	// app crowns the owner wherever they are listed.
	OwnerID ids.ID        `json:"owner_id"`
	Staff   *staffSummary `json:"staff,omitempty"`
}

// staffSummary is what staff see on arrival: how much is waiting on them.
type staffSummary struct {
	Counts  store.ModCaseCounts `json:"counts"`
	OwnerID ids.ID              `json:"owner_id"`
	// Bugs counts the bug reports in each view of their tab (nil when they
	// could not be read).
	Bugs *store.BugCounts `json:"bugs,omitempty"`
	// Unread is the threads waiting on the staff this person has not read,
	// which the panel's badges count.
	Unread store.ModUnread `json:"unread"`
}

func (s *Server) nexaState(ctx context.Context, u *store.User) (*nexaState, error) {
	now := time.Now().UnixMilli()
	st := &nexaState{Pending: []memberCase{}}
	if sys, err := s.systemUser(ctx); err == nil {
		st.AccountID = sys.ID
	}
	mute, ban, err := s.Store.ActiveRestrictions(ctx, u.ID, now)
	if err != nil {
		return nil, err
	}
	var who []ids.ID
	view := func(r *store.Restriction) *restrictionView {
		if r == nil {
			return nil
		}
		v := &restrictionView{Kind: r.Kind, CaseID: r.CaseID, ExpiresAt: r.ExpiresAt, CreatedAt: r.CreatedAt}
		if c, err := s.Store.ModCaseByID(ctx, r.CaseID); err == nil {
			v.CaseNumber, v.Reason, v.ModeratorID = c.Number, c.Reason, c.ModeratorID
			who = append(who, c.ModeratorID)
		}
		return v
	}
	st.Mute, st.Ban = view(mute), view(ban)
	pending, err := s.Store.PendingModNotices(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	if len(pending) > 0 {
		cs := s.caseStateFor(ctx, u.ID)
		for _, c := range pending {
			st.Pending = append(st.Pending, toMemberCase(c, cs))
			who = append(who, casePeople(c)...)
		}
	}
	if st.Unread, err = s.Store.NexaUnread(ctx, u.ID, u.CreatedAt); err != nil {
		return nil, err
	}
	if st.Latest, err = s.Store.NexaLatest(ctx, u.ID, u.CreatedAt); err != nil {
		return nil, err
	}
	st.OwnerID, _ = s.Store.InstanceOwnerID(ctx)
	if rank := s.rankOf(ctx, u); rank != rankNone {
		counts, err := s.Store.CountModCases(ctx, now)
		if err != nil {
			return nil, err
		}
		st.Staff = &staffSummary{Counts: counts, OwnerID: st.OwnerID, Unread: s.modUnreadFor(ctx, u.ID)}
		if rank >= rankAdmin {
			st.Staff.Bugs = s.bugCountsFor(ctx)
		}
	}
	st.People = s.people(ctx, who...)
	return st, nil
}

// --- events -------------------------------------------------------------------

// emitNexaState tells every device of an account its standing changed.
func (s *Server) emitNexaState(ctx context.Context, uid ids.ID) {
	u, err := s.Store.UserByID(ctx, uid)
	if err != nil || u.Flags&store.FlagDeleted != 0 {
		return
	}
	st, err := s.nexaState(ctx, u)
	if err != nil {
		s.Log.Warn("nexa: state", "user", uid.String(), "err", err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, "nexa_state", st)
}

// emitCase tells the member and the staff that a case moved: its new state,
// and the lines added to its thread, if any. The member hears their own
// standing with it, since any change to a case can change what is unread.
func (s *Server) emitCase(ctx context.Context, c *store.ModCase, lines ...*store.ModMessage) {
	if lines == nil {
		lines = []*store.ModMessage{}
	}
	s.emitCaseStaff(ctx, c, lines...)
	u, err := s.Store.UserByID(ctx, c.UserID)
	if err != nil || u.Flags&store.FlagDeleted != 0 {
		return
	}
	st, err := s.nexaState(ctx, u)
	if err != nil {
		s.Log.Warn("nexa: state", "user", c.UserID.String(), "err", err)
		return
	}
	who := casePeople(c)
	for _, m := range lines {
		who = append(who, m.AuthorID)
	}
	s.Bcast.EmitUsers([]ids.ID{c.UserID}, "nexa_case", map[string]any{"case": toMemberCase(c, s.caseStateFor(ctx, c.UserID)), "messages": lines, "people": s.people(ctx, who...), "state": st})
}

// emitStaff sends an event to every member of staff of at least rank need.
func (s *Server) emitStaff(ctx context.Context, need staffRank, event string, data any) {
	staff, err := s.Store.StaffIDs(ctx, staffBits(need))
	if err != nil {
		s.Log.Warn("moderation: staff list", "err", err)
		return
	}
	s.Bcast.EmitUsers(staff, event, data)
}

// --- handlers -----------------------------------------------------------------

func (s *Server) handleNexa(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	st, err := s.nexaState(r.Context(), me)
	if err != nil {
		WriteError(w, err)
		return
	}
	var account any
	if sys, err := s.systemUser(r.Context()); err == nil {
		account = sys.Public()
	}
	WriteJSON(w, http.StatusOK, map[string]any{"state": st, "account": account})
}

// feedItem is one thing in the member's Nexa conversation: a notice about a
// case, a lifted mute or ban, an announcement, a bug report they sent, or a
// reply or a change of status in one.
type feedItem struct {
	Kind         string            `json:"kind"` // notice | lift | announcement | bug | bug_reply | bug_status | report | report_outcome
	At           int64             `json:"at"`
	Case         *memberCase       `json:"case,omitempty"`
	Announcement *announcementView `json:"announcement,omitempty"`
	// Bug is the report the item is about; Message is the staff reply or the
	// status line, for bug_reply and bug_status.
	Bug     *memberBug        `json:"bug,omitempty"`
	Message *store.BugMessage `json:"message,omitempty"`
	// Report is a safety report the member sent (its receipt: never staff
	// notes), for report and report_outcome.
	Report map[string]any `json:"report,omitempty"`
}

// announcementView is an announcement as anyone sees it; the author is in the
// response's people, so the post can name them and link to their profile.
type announcementView struct {
	ID        ids.ID `json:"id"`
	Title     string `json:"title"`
	Body      string `json:"body"`
	AuthorID  ids.ID `json:"author_id"`
	CreatedAt int64  `json:"created_at"`
}

func toAnnouncementView(a *store.Announcement) announcementView {
	return announcementView{ID: a.ID, Title: a.Title, Body: a.Body, AuthorID: a.AuthorID, CreatedAt: a.CreatedAt}
}

const feedPage = 50

// handleNexaFeed pages the member's Nexa conversation, newest first. `before`
// is a time: the page holds what came before it.
func (s *Server) handleNexaFeed(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	before, _ := strconv.ParseInt(r.URL.Query().Get("before"), 10, 64)
	cases, err := s.Store.ModCasesForUser(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	anns, err := s.Store.Announcements(ctx, me.CreatedAt, before, feedPage+1)
	if err != nil {
		WriteError(w, err)
		return
	}
	cs := s.caseStateFor(ctx, me.ID)
	items := make([]feedItem, 0, len(cases)+len(anns))
	keep := func(at int64) bool { return before <= 0 || at < before }
	for _, c := range cases {
		v := toMemberCase(c, cs)
		if keep(c.CreatedAt) {
			items = append(items, feedItem{Kind: "notice", At: c.CreatedAt, Case: &v})
		}
		if c.LiftedAt != nil && keep(*c.LiftedAt) {
			items = append(items, feedItem{Kind: "lift", At: *c.LiftedAt, Case: &v})
		}
	}
	for _, a := range anns {
		v := toAnnouncementView(a)
		items = append(items, feedItem{Kind: "announcement", At: a.CreatedAt, Announcement: &v})
	}
	// the reports they sent, and what the team said in them since
	reports, err := s.Store.BugReportsForUser(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	bugs, err := s.memberBugs(ctx, me.ID, reports)
	if err != nil {
		WriteError(w, err)
		return
	}
	bugByID := make(map[ids.ID]*memberBug, len(bugs))
	for i := range bugs {
		b := &bugs[i]
		bugByID[b.ID] = b
		if keep(b.CreatedAt) {
			items = append(items, feedItem{Kind: "bug", At: b.CreatedAt, Bug: b})
		}
	}
	news, err := s.Store.BugNewsForUser(ctx, me.ID, before, feedPage+1)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, m := range news {
		b := bugByID[m.ReportID]
		if b == nil {
			continue
		}
		kind := "bug_reply"
		if m.Kind == store.ModMsgSystem {
			kind = "bug_status"
		}
		items = append(items, feedItem{Kind: kind, At: m.CreatedAt, Bug: b, Message: m})
	}
	// the safety reports they sent, and what staff told them on closing each
	sent, err := s.Store.SafetyReportsForReporter(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, v := range sent {
		receipt := reportReceipt(v)
		if keep(v.CreatedAt) {
			items = append(items, feedItem{Kind: "report", At: v.CreatedAt, Report: receipt})
		}
		if v.OutcomeAt > 0 && keep(v.OutcomeAt) {
			items = append(items, feedItem{Kind: "report_outcome", At: v.OutcomeAt, Report: receipt})
		}
	}
	sort.SliceStable(items, func(i, j int) bool { return items[i].At > items[j].At })
	more := len(items) > feedPage
	if more {
		items = items[:feedPage]
	}
	var who []ids.ID
	for _, it := range items {
		if it.Case != nil {
			who = append(who, it.Case.ModeratorID)
			if it.Case.LiftedBy != nil {
				who = append(who, *it.Case.LiftedBy)
			}
		}
		if it.Announcement != nil {
			who = append(who, it.Announcement.AuthorID)
		}
		if it.Message != nil {
			who = append(who, it.Message.AuthorID)
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"items": items, "has_more": more, "people": s.people(ctx, who...)})
}

// handleNexaSeen records that the member read their Nexa conversation up to a
// moment (now, unless they say otherwise): what it shows, and the case threads
// and bug reports it counts as waiting.
func (s *Server) handleNexaSeen(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		At int64 `json:"at"`
	}
	if r.ContentLength != 0 {
		if err := ReadJSON(w, r, &req, 1<<10); err != nil {
			WriteError(w, err)
			return
		}
	}
	now := time.Now().UnixMilli()
	if req.At <= 0 || req.At > now {
		req.At = now
	}
	if err := s.Store.MarkNexaRead(r.Context(), me.ID, req.At); err != nil {
		WriteError(w, err)
		return
	}
	s.emitNexaState(r.Context(), me.ID)
	w.WriteHeader(http.StatusNoContent)
}

// memberCaseFor loads a case of the caller's own; anyone else's is not found.
func (s *Server) memberCaseFor(w http.ResponseWriter, r *http.Request) (*store.ModCase, *store.User) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	c, err := s.Store.ModCaseByID(r.Context(), id)
	if err != nil || c.UserID != me.ID {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	return c, me
}

// threadResponse is a case with its whole thread, for either side.
func (s *Server) threadResponse(ctx context.Context, c *store.ModCase, staff bool) (map[string]any, error) {
	msgs, err := s.Store.ModMessages(ctx, c.ID, 0, store.MaxModMessages)
	if err != nil {
		return nil, err
	}
	who := casePeople(c)
	for _, m := range msgs {
		who = append(who, m.AuthorID)
	}
	cs := s.caseStateFor(ctx, c.UserID)
	out := map[string]any{"messages": msgs, "people": s.people(ctx, who...)}
	if staff {
		var last *store.ModMessage
		if n := len(msgs); n > 0 {
			last = msgs[n-1]
		}
		out["case"] = toStaffCase(c, cs, last)
	} else {
		out["case"] = toMemberCase(c, cs)
	}
	return out, nil
}

func (s *Server) handleNexaCase(w http.ResponseWriter, r *http.Request) {
	c, _ := s.memberCaseFor(w, r)
	if c == nil {
		return
	}
	out, err := s.threadResponse(r.Context(), c, false)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleNexaReply is the member writing in their own case's thread.
func (s *Server) handleNexaReply(w http.ResponseWriter, r *http.Request) {
	c, me := s.memberCaseFor(w, r)
	if c == nil {
		return
	}
	if ok, retry := s.limMsg.Allow("nexa:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	line, ok := readCaseLine(w, r)
	if !ok {
		return
	}
	msg, extra, updated, err := s.Store.AddModMessage(r.Context(), c.ID, me.ID, store.ModMsgMember, line.Body, line.Files)
	if err != nil {
		WriteError(w, caseWriteError(err))
		return
	}
	lines := append(extra, msg)
	s.emitCase(r.Context(), updated, lines...)
	cs := s.caseStateFor(r.Context(), me.ID)
	WriteJSON(w, http.StatusCreated, map[string]any{"message": msg, "messages": lines, "case": toMemberCase(updated, cs)})
}

// handleNexaRead moves the member's read position in one of their threads.
func (s *Server) handleNexaRead(w http.ResponseWriter, r *http.Request) {
	c, me := s.memberCaseFor(w, r)
	if c == nil {
		return
	}
	var req struct {
		MessageID ids.ID `json:"message_id"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.MarkModCaseRead(r.Context(), c.ID, me.ID, req.MessageID); err != nil {
		WriteError(w, err)
		return
	}
	s.emitNexaState(r.Context(), me.ID)
	w.WriteHeader(http.StatusNoContent)
}

// handleNexaAck dismisses a case's notice, on every device at once.
func (s *Server) handleNexaAck(w http.ResponseWriter, r *http.Request) {
	c, me := s.memberCaseFor(w, r)
	if c == nil {
		return
	}
	if err := s.Store.AckModNotice(r.Context(), c.ID, me.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.emitNexaState(r.Context(), me.ID)
	// the staff see that the notice was read
	if updated, err := s.Store.ModCaseByID(r.Context(), c.ID); err == nil {
		cs := s.caseStateFor(r.Context(), me.ID)
		var last *store.ModMessage
		if m, err := s.Store.LastModMessages(r.Context(), []ids.ID{c.ID}); err == nil {
			last = m[c.ID]
		}
		counts, _ := s.Store.CountModCases(r.Context(), time.Now().UnixMilli())
		s.emitStaff(r.Context(), rankMod, "mod_case", map[string]any{"case": toStaffCase(updated, cs, last), "messages": []*store.ModMessage{}, "people": s.people(r.Context(), casePeople(updated)...), "counts": counts})
	}
	w.WriteHeader(http.StatusNoContent)
}

// --- the system account ---------------------------------------------------------

// Nexa's account row: a real account so that it has a profile, its name is
// taken, and everything that shows a user can show it. It has no password
// (auth_hash "!" matches nothing) and no private key: nothing is ever
// encrypted to it, because nobody can open a conversation with it.
var nexaUsernames = []string{"nexa", "nexa.system", "nexa_official", "nexa.team"}

const nexaAbout = "The official Nexa account. Moderation notices and announcements from the Nexa team arrive here. Nexa will never ask for your password or recovery key."

type systemAccount struct {
	mu sync.Mutex
	u  *store.User
}

// systemUser returns the Nexa account, making it the first time it is asked
// for on a database that has none.
func (s *Server) systemUser(ctx context.Context) (*store.User, error) {
	s.system.mu.Lock()
	defer s.system.mu.Unlock()
	if s.system.u != nil {
		return s.system.u, nil
	}
	u, err := s.Store.SystemUser(ctx)
	if errors.Is(err, store.ErrNotFound) {
		u, err = s.createSystemUser(ctx)
	}
	if err != nil {
		return nil, err
	}
	s.system.u = u
	return u, nil
}

// isSystem reports whether an account is Nexa's.
func isSystem(u *store.User) bool { return u != nil && u.Flags&store.FlagSystem != 0 }

// isWebhookAccount reports whether an account is the one webhook messages are
// filed under. To everyone it is not there: nobody can message, befriend or
// block it.
func isWebhookAccount(u *store.User) bool { return u != nil && u.Flags&store.FlagWebhook != 0 }

// notAPerson covers both accounts nobody holds, which staff can neither
// moderate nor disable.
func notAPerson(u *store.User) bool { return u != nil && u.Flags&store.NotPeople != 0 }

func (s *Server) createSystemUser(ctx context.Context) (*store.User, error) {
	random := func(n int) []byte {
		b := make([]byte, n)
		_, _ = rand.Read(b)
		return b
	}
	var lastErr error
	for _, name := range nexaUsernames {
		u := &store.User{Username: name, DisplayName: "Nexa", AuthHash: "!", KDFSalt: random(16), KDFParams: defaultKDF,
			PublicKey: random(32), EncPrivateKey: []byte{}, AboutMe: nexaAbout, Flags: store.FlagSystem, DMPrivacy: "nobody"}
		err := s.Store.CreateUser(ctx, u)
		if err == nil {
			s.Log.Info("nexa: created the system account", "id", u.ID.String(), "username", name)
			return u, nil
		}
		if !errors.Is(err, store.ErrUsernameTaken) {
			return nil, err
		}
		lastErr = err
	}
	return nil, lastErr
}

// errNexaNoDM answers anyone trying to open a conversation with Nexa.
var errNexaNoDM = E(http.StatusForbidden, "nexa_system", "Nexa is the system account and doesn't take direct messages. To talk to the moderation team, reply in one of your case threads.")

// reservedDisplayName reports a name only the official account may use, so
// nobody can dress up as Nexa in a conversation. It compares what names look
// like, not their letters: "Nexa" spelt with a Cyrillic е, with a zero-width
// space in it, in full-width letters or with a shield emoji after it is the
// same name to the person reading it. Usernames and guest names get the same
// check.
func reservedDisplayName(name string) bool {
	switch textutil.Skeleton(name) {
	case "nexa", "nexastaff", "nexateam", "nexasystem", "nexamoderation", "nexamoderator", "nexamoderators", "nexamod", "nexamods",
		"nexasupport", "nexaofficial", "nexaadmin", "nexaadmins", "nexasafety", "nexasecurity", "nexahelp":
		return true
	}
	return false
}

// --- wiring -----------------------------------------------------------------------

// WireModeration installs the moderation hooks on the hub: banned accounts are
// turned away at identify, muted ones cannot type or set a status, and ready
// carries every account's standing.
func (s *Server) WireModeration(hub *gateway.Hub) {
	if hub == nil {
		return
	}
	s.modHub = hub
	hub.Restricted = func(ctx context.Context, uid ids.ID) (bool, bool) {
		mute, ban, err := s.Store.ActiveRestrictions(ctx, uid, time.Now().UnixMilli())
		if err != nil {
			// every route the account could act through checks again itself
			return false, false
		}
		return mute != nil, ban != nil
	}
	prev := hub.ReadyExtras
	hub.ReadyExtras = func(ctx context.Context, u *store.User, p *gateway.ReadyPayload) error {
		if prev != nil {
			if err := prev(ctx, u, p); err != nil {
				return err
			}
		}
		// The account in ready is the one /users/@me answers with, every
		// field of it. The gateway's own shorter copy left out the second
		// accent colour, two-factor state and the rest; the app took it as
		// the whole account, and the next profile save sent the missing
		// colour back as "none", wiping it for everyone.
		me := s.meView(ctx, u)
		if me.Nexa == nil {
			st, err := s.nexaState(ctx, u)
			if err != nil {
				return err
			}
			me.Nexa = st
		}
		p.User = me
		p.Nexa = me.Nexa
		return nil
	}
}

// restrictionSweep is how often mutes and bans that ran out are ended.
const restrictionSweep = 15 * time.Second

// StartModeration makes sure the Nexa account exists and ends mutes and bans
// as their time comes, telling the members and the staff, until ctx ends.
func (s *Server) StartModeration(ctx context.Context) {
	s.background.Go(func() {
		for attempt := 0; ; attempt++ {
			if _, err := s.systemUser(ctx); err == nil {
				break
			} else if attempt%20 == 0 {
				s.Log.Warn("nexa: system account", "err", err)
			}
			select {
			case <-ctx.Done():
				return
			case <-time.After(3 * time.Second):
			}
		}
	})
	s.background.Go(func() {
		t := time.NewTicker(restrictionSweep)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				s.sweepRestrictions(ctx)
			}
		}
	})
}

func (s *Server) sweepRestrictions(ctx context.Context) {
	ended, err := s.Store.EndExpiredRestrictions(ctx, time.Now().UnixMilli())
	if err != nil {
		s.Log.Warn("moderation: ending restrictions", "err", err)
	}
	for _, e := range ended {
		c, err := s.Store.ModCaseByID(ctx, e.CaseID)
		if err != nil {
			continue
		}
		s.emitCase(ctx, c, e.Line)
	}
}
