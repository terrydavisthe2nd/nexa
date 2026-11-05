package api

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/store"
)

var reportParts = map[string][]string{
	"user":        {"whole", "username", "display_name", "avatar", "banner", "about_me", "pronouns", "status", "widgets", "typing_style", "name_style", "server_profile", "activity", "custom"},
	"server":      {"whole", "name", "icon", "banner", "description", "tag", "discovery", "custom"},
	"channel":     {"whole", "name", "topic", "icon", "tags", "custom"},
	"message":     {"whole"},
	"emoji":       {"whole", "name", "image", "custom"},
	"sticker":     {"whole", "name", "image", "tags", "custom"},
	"pack":        {"whole", "name", "description", "image", "custom"},
	"note":        {"whole", "title", "text", "attachment", "custom"},
	"application": {"whole", "name", "description", "icon", "links", "commands", "custom"},
	"role":        {"whole", "name", "icon", "custom"},
	"other":       {"custom"},
}
// reportReasons in the order the forms list them. A reason a kind of content
// cannot have (reasonKinds) is left out of its form and refused.
var reportReasons = []string{"harassment", "hate", "violence", "self_harm", "sexual", "child_safety", "underage", "spam", "scam", "privacy", "ban_evasion", "illegal", "other"}

// reasonKinds limits the reasons that are about a person rather than content:
// someone under 13 and an account made to get around a mute or ban.
var reasonKinds = map[string][]string{
	"underage":    {"user", "message", "other"},
	"ban_evasion": {"user", "message", "other"},
}

func reasonsFor(kind string) []string {
	out := make([]string, 0, len(reportReasons))
	for _, reason := range reportReasons {
		if kinds, limited := reasonKinds[reason]; limited && !OneOf(kind, kinds...) {
			continue
		}
		out = append(out, reason)
	}
	return out
}

func (s *Server) reportRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/reports/target", s.authed(s.handleReportTarget))
	mux.Handle("POST /api/v1/reports", s.authed(s.handleReportCreate))
	mux.Handle("GET /api/v1/reports", s.authed(s.handleReportsMine))
	mux.Handle("POST /api/v1/reports/attachments", s.authed(s.handleReportUpload))
	mux.Handle("GET /media/reports/{id}", s.authed(s.handleReportFile))
	mux.Handle("GET /api/v1/moderation/reports", s.staff(rankMod, s.handleModReports))
	mux.Handle("GET /api/v1/moderation/reports/{id}", s.staff(rankMod, s.handleModReport))
	mux.Handle("POST /api/v1/moderation/reports/{id}/remove-message", s.staff(rankAdmin, s.handleReportRemoveMessage))
	mux.Handle("PATCH /api/v1/moderation/reports/{id}", s.staff(rankMod, s.handleModReportReview))
}

type reportTarget struct {
	Kind      string         `json:"kind"`
	ID        ids.ID         `json:"id"`
	SubjectID ids.ID         `json:"subject_id"`
	ServerID  ids.ID         `json:"server_id"`
	Parts     []string       `json:"parts"`
	Reasons   []string       `json:"reasons"`
	Snapshot  map[string]any `json:"snapshot"`
	// AlreadyReported is the caller's own report about this item that still
	// waits on staff, so the form can say so before they send another.
	AlreadyReported map[string]any `json:"already_reported,omitempty"`
}

// Only explicit readable fields enter a report. Never serialize identity keys,
// note key wrappers, conversation roots, or bot application credentials.
func (s *Server) reportTarget(r *http.Request, kind string, id, contextServer ids.ID) (*reportTarget, error) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	parts, ok := reportParts[kind]
	if !ok {
		return nil, E(400, "bad_target", "Choose a kind of content to report.")
	}
	t := &reportTarget{Kind: kind, ID: id, Parts: parts, Reasons: reasonsFor(kind), Snapshot: map[string]any{}}
	if kind == "other" {
		t.ID = 0
		return t, nil
	}
	if id == 0 {
		return nil, ErrNotFound
	}
	member := func(server ids.ID) bool { ok, err := s.Store.IsMember(ctx, server, me.ID); return err == nil && ok }
	setServer := func(server ids.ID) error {
		sv, err := s.Store.ServerByID(ctx, server)
		if err != nil {
			return ErrNotFound
		}
		t.ServerID = sv.ID
		t.SubjectID = sv.OwnerID
		return nil
	}
	sexualAllowed := true
	channel := func(cid ids.ID, need string) (*store.Channel, error) {
		ch, err := s.Store.ChannelByID(ctx, cid)
		if err != nil {
			return nil, ErrNotFound
		}
		ok, err := s.canAccess(r, me, ch, need)
		if err != nil {
			return nil, err
		}
		if !ok || !s.unlocked(r, ch) {
			return nil, ErrNotFound
		}
		if ch.ServerID != nil {
			if err := setServer(*ch.ServerID); err != nil {
				return nil, err
			}
		} else if ch.OwnerID != nil {
			t.SubjectID = *ch.OwnerID
		}
		if ch.ServerID == nil || ch.NSFW {
			sexualAllowed = false
		}
		// Threads inherit their parent channel's content designation.
		parent := ch.ParentID
		seen := map[ids.ID]bool{ch.ID: true}
		for parent != nil && !seen[*parent] {
			seen[*parent] = true
			p, err := s.Store.ChannelByID(ctx, *parent)
			if err != nil {
				return nil, err
			}
			if p.NSFW {
				sexualAllowed = false
			}
			parent = p.ParentID
		}
		return ch, nil
	}
	pack := func(pid ids.ID) (*store.StickerPack, error) {
		p, err := s.Store.PackByID(ctx, pid)
		if err != nil {
			return nil, ErrNotFound
		}
		view, _, err := s.packRights(ctx, p, me.ID)
		if err != nil {
			return nil, err
		}
		if !view {
			return nil, ErrNotFound
		}
		if p.ServerID != nil {
			if err := setServer(*p.ServerID); err != nil {
				return nil, err
			}
		} else if p.OwnerUserID != nil {
			t.SubjectID = *p.OwnerUserID
		}
		return p, nil
	}
	switch kind {
	case "user":
		u, err := s.Store.UserByID(ctx, id)
		if err != nil || u.Flags&store.FlagDeleted != 0 {
			return nil, ErrNotFound
		}
		t.SubjectID = u.ID
		t.Snapshot = map[string]any{"username": u.Username, "display_name": u.DisplayName, "avatar_id": u.AvatarID, "banner_id": u.BannerID, "about_me": u.AboutMe, "pronouns": u.Pronouns, "status_text": u.StatusText, "status_emoji": u.StatusEmoji, "name_style": u.NameStyle, "typing_style": u.TypingStyle}
		if widgets, err := s.Store.UserWidgets(ctx, id); err == nil && json.Valid([]byte(widgets)) {
			t.Snapshot["widgets"] = json.RawMessage(widgets)
		}
		if contextServer != 0 {
			if !member(contextServer) {
				return nil, ErrNotFound
			}
			m, err := s.Store.Member(ctx, contextServer, id)
			if err != nil {
				return nil, ErrNotFound
			}
			t.ServerID = contextServer
			t.Snapshot["server_profile"] = m
		}
	case "server":
		sv, err := s.Store.ServerByID(ctx, id)
		if err != nil || (!member(id) && (!sv.Discoverable || sv.DiscoveryHidden)) {
			return nil, ErrNotFound
		}
		t.ServerID = id
		t.SubjectID = sv.OwnerID
		t.Snapshot = map[string]any{"name": sv.Name, "description": sv.Description, "icon_id": sv.IconID, "banner_id": sv.BannerID, "tag": sv.Tag, "discovery_tags": sv.DiscoveryTags, "discovery_category": sv.DiscoveryCategory}
	case "channel":
		ch, err := channel(id, NeedView)
		if err != nil {
			return nil, err
		}
		t.Snapshot = map[string]any{"name": ch.Name, "topic": ch.Topic, "icon_id": ch.IconID, "glyph": ch.Glyph, "type": ch.Type, "forum": ch.Forum, "url": ch.URL}
	case "message":
		m, err := s.Store.MessageByID(ctx, id, me.ID)
		if err != nil {
			return nil, ErrNotFound
		}
		ch, err := channel(m.ChannelID, NeedHistory)
		if err != nil {
			return nil, err
		}
		t.SubjectID = m.AuthorID
		t.Snapshot = map[string]any{"channel_id": ch.ID, "author_id": m.AuthorID, "created_at": m.CreatedAt, "edited_at": m.EditedAt, "type": m.Type, "reply_to_id": m.ReplyToID, "reactions": m.Reactions, "poll_results": m.Poll, "mentions": m.Mentions, "flags": m.Flags}
	case "emoji":
		e, err := s.Store.EmojiByID(ctx, id)
		if err != nil {
			return nil, ErrNotFound
		}
		sv, err := s.Store.ServerByID(ctx, e.ServerID)
		if err != nil || (!member(e.ServerID) && (!sv.Discoverable || sv.DiscoveryHidden)) {
			return nil, ErrNotFound
		}
		if err := setServer(e.ServerID); err != nil {
			return nil, err
		}
		t.Snapshot = map[string]any{"name": e.Name, "blob_id": e.BlobID, "creator_id": e.CreatorID}
	case "pack":
		p, err := pack(id)
		if err != nil {
			return nil, err
		}
		t.Snapshot = map[string]any{"name": p.Name, "description": p.Description, "kind": p.Kind, "cover_sticker_id": p.CoverStickerID}
		if p.CoverStickerID != nil {
			if cover, err := s.Store.StickerByID(ctx, *p.CoverStickerID); err == nil {
				t.Snapshot["cover_blob_id"] = cover.BlobID
				t.Snapshot["cover_format"] = cover.Format
			}
		} else if len(p.Stickers) > 0 {
			t.Snapshot["cover_blob_id"] = p.Stickers[0].BlobID
			t.Snapshot["cover_format"] = p.Stickers[0].Format
		}
	case "sticker":
		st, err := s.Store.StickerByID(ctx, id)
		if err != nil {
			return nil, ErrNotFound
		}
		if _, err := pack(st.PackID); err != nil {
			return nil, err
		}
		t.Snapshot = map[string]any{"name": st.Name, "tags": st.Tags, "blob_id": st.BlobID, "pack_id": st.PackID, "format": st.Format, "creator_id": st.CreatorID}
	case "note":
		n, err := s.Store.NoteFor(ctx, id, me.ID)
		if err != nil {
			return nil, ErrNotFound
		}
		t.SubjectID = n.OwnerID
		t.Snapshot = map[string]any{"owner_id": n.OwnerID, "version": n.Version, "updated_at": n.UpdatedAt}
	case "application":
		a, err := s.Store.ApplicationByID(ctx, id)
		if err != nil {
			return nil, ErrNotFound
		}
		t.SubjectID = a.OwnerID
		t.Snapshot = map[string]any{"name": a.Name, "description": a.Description, "icon_id": a.IconID, "tags": a.Tags, "terms_url": a.TermsURL, "privacy_url": a.PrivacyURL}
	case "role":
		role, err := s.Store.RoleByID(ctx, id)
		if err != nil || !member(role.ServerID) {
			return nil, ErrNotFound
		}
		if err := setServer(role.ServerID); err != nil {
			return nil, err
		}
		t.Snapshot = map[string]any{"name": role.Name, "icon_id": role.IconID, "icon_emoji": role.IconEmoji}
	}
	if t.ServerID != 0 {
		sv, err := s.Store.ServerByID(ctx, t.ServerID)
		if err != nil {
			return nil, err
		}
		if sv.NSFW {
			sexualAllowed = false
		}
	}
	if !sexualAllowed {
		all := t.Reasons
		t.Reasons = make([]string, 0, len(all))
		for _, reason := range all {
			if reason != "sexual" {
				t.Reasons = append(t.Reasons, reason)
			}
		}
	}
	return t, nil
}
func (s *Server) handleReportTarget(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	id, _ := ids.Parse(q.Get("id"))
	server, _ := ids.Parse(q.Get("server_id"))
	t, err := s.reportTarget(r, q.Get("kind"), id, server)
	if err != nil {
		WriteError(w, err)
		return
	}
	if t.ID != 0 {
		if old, err := s.Store.ActiveSafetyReportBy(r.Context(), auth.UserFrom(r.Context()).ID, t.Kind, t.ID); err == nil {
			t.AlreadyReported = reportReceipt(old)
		}
	}
	WriteJSON(w, 200, t)
}

func (s *Server) handleReportCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		RequestID   string                `json:"request_id"`
		Kind        string                `json:"target_kind"`
		ID          ids.ID                `json:"target_id"`
		Server      ids.ID                `json:"server_id"`
		Part        string                `json:"part"`
		Custom      string                `json:"custom_part"`
		Reason      string                `json:"reason"`
		Description string                `json:"description"`
		Excerpt     string                `json:"excerpt"`
		Files       []store.BugAttachment `json:"files"`
		Message     *reportedMessage      `json:"message"`
	}
	if err := ReadJSON(w, r, &req, 512<<10); err != nil {
		WriteError(w, err)
		return
	}

	if len(req.RequestID) < 16 || len(req.RequestID) > 64 || strings.Trim(req.RequestID, "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-") != "" {
		WriteError(w, E(400, "bad_request_id", "Send a unique request reference with the report."))
		return
	}
	if old, err := s.Store.SafetyReportByRequest(r.Context(), me.ID, req.RequestID); err == nil {
		WriteJSON(w, 201, map[string]any{"report": reportReceipt(old)})
		return
	} else if !errors.Is(err, store.ErrNotFound) {
		WriteError(w, err)
		return
	}
	target, err := s.reportTarget(r, req.Kind, req.ID, req.Server)
	if err != nil {
		WriteError(w, err)
		return
	}
	if !OneOf(req.Part, target.Parts...) || !OneOf(req.Reason, target.Reasons...) {
		WriteError(w, E(400, "bad_report", "Choose what you are reporting and why."))
		return
	}
	if req.Kind == "message" {
		if err := s.captureReportedMessage(r, target, req.Message); err != nil {
			WriteError(w, err)
			return
		}
	}
	description, ok := CleanText(req.Description, 4000)
	if !ok || len([]rune(description)) < 3 {
		WriteError(w, E(400, "report_description", "Describe what happened in 3 to 4000 characters."))
		return
	}
	custom, ok := CleanText(req.Custom, 120)
	if !ok || (req.Part == "custom" && custom == "") {
		WriteError(w, E(400, "report_part", "Describe the specific part in up to 120 characters."))
		return
	}
	excerpt, ok := CleanText(req.Excerpt, 16000)
	if !ok {
		WriteError(w, E(400, "report_evidence", "Selected evidence can be up to 16000 characters."))
		return
	}
	if len(req.Files) > 5 {
		WriteError(w, E(400, "too_many_files", "A report can have up to 5 files."))
		return
	}
	seen := map[ids.ID]bool{}
	var total int64
	for i, f := range req.Files {
		if f.BlobID == 0 || seen[f.BlobID] {
			WriteError(w, errBugFileGone)
			return
		}
		seen[f.BlobID] = true
		b, err := s.Store.BlobByID(r.Context(), f.BlobID)
		if err != nil || b.Kind != store.ReportBlobKind || b.OwnerID == nil || *b.OwnerID != me.ID || b.RefCount != 0 {
			WriteError(w, errBugFileGone)
			return
		}
		total += b.Size
		req.Files[i].Name = truncateRunes(strings.ReplaceAll(f.Name, "\n", " "), 255)
	}
	if total > 100<<20 {
		WriteError(w, E(413, "files_too_large", "The files come to more than 100 MB."))
		return
	}
	if !s.requireCaptcha(w, r, "safety_report") {
		return
	}
	if ok, retry := s.limReport.Allow("report:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	v := &store.SafetyReport{RequestID: req.RequestID, ReporterID: me.ID, TargetKind: target.Kind, TargetID: target.ID, SubjectID: target.SubjectID, ServerID: target.ServerID, Part: req.Part, CustomPart: custom, Reason: req.Reason, Description: description, Excerpt: excerpt, Snapshot: mustJSON(target.Snapshot)}
	if err := s.Store.CreateSafetyReport(r.Context(), v, req.Files); err != nil {
		if errors.Is(err, store.ErrBugAttachment) {
			WriteError(w, errBugFileGone)
		} else {
			WriteError(w, err)
		}
		return
	}
	s.emitReportChanges(r.Context(), true, true, v)
	WriteJSON(w, 201, map[string]any{"report": reportReceipt(v)})
}

// Receipts omit evidence, identities and internal staff decisions/notes. The
// label is the name the reporter saw when they sent it (from the snapshot taken
// then), so they can tell their reports apart; a message or something else has none.
func reportReceipt(v *store.SafetyReport) map[string]any {
	return map[string]any{"id": v.ID, "target_kind": v.TargetKind, "target_id": v.TargetID, "target_label": reportLabel(v), "part": v.Part, "custom_part": v.CustomPart, "reason": v.Reason, "description": v.Description, "status": v.Status, "outcome": v.Outcome, "outcome_at": v.OutcomeAt, "created_at": v.CreatedAt, "updated_at": v.UpdatedAt}
}

func reportLabel(v *store.SafetyReport) string {
	var snap struct {
		Name        string `json:"name"`
		DisplayName string `json:"display_name"`
		Username    string `json:"username"`
	}
	_ = json.Unmarshal(v.Snapshot, &snap)
	switch {
	case snap.DisplayName != "":
		return snap.DisplayName
	case snap.Username != "":
		return snap.Username
	}
	return snap.Name
}

// reportListView is a report as the staff queue lists it: without the private
// file descriptors and the reported message's content.
func reportListView(v *store.SafetyReport) *store.SafetyReport {
	c := *v
	publicReportSnapshot(&c)
	return &c
}

// emitReportChanges tells staff about new or changed reports (with the queue's
// counts; fresh when the first was just sent) and, unless the first report's status stayed the same, each reporter
// about their own report's new status.
func (s *Server) emitReportChanges(ctx context.Context, statusChanged, fresh bool, list ...*store.SafetyReport) {
	if len(list) == 0 {
		return
	}
	counts, err := s.Store.SafetyReportCounts(ctx)
	if err != nil {
		s.Log.Warn("reports: counts", "err", err)
		return
	}
	views := make([]*store.SafetyReport, 0, len(list))
	who := []ids.ID{}
	for _, v := range list {
		views = append(views, reportListView(v))
		who = append(who, v.ReporterID, v.SubjectID, v.AssignedTo)
	}
	_ = s.Store.FillReportDuplicates(ctx, views)
	// "new" marks a report just sent, which the apps tell staff about
	s.emitStaff(ctx, rankMod, gateway.EvModReport, map[string]any{"reports": views, "counts": counts, "people": s.people(ctx, who...), "new": fresh})
	told := map[ids.ID]bool{}
	for i, v := range list {
		if i > 0 || statusChanged {
			s.Bcast.EmitUsers([]ids.ID{v.ReporterID}, gateway.EvReportUpdate, reportReceipt(v))
			told[v.ReporterID] = true
		}
	}
	// a new item, or an outcome, in their Nexa conversation moves its unread count
	for uid := range told {
		s.emitNexaState(ctx, uid)
	}
}
func (s *Server) handleReportsMine(w http.ResponseWriter, r *http.Request) {
	before, _ := ids.Parse(r.URL.Query().Get("before"))
	list, err := s.Store.SafetyReports(r.Context(), store.ReportFilter{Reporter: auth.UserFrom(r.Context()).ID, Before: before, Limit: 51})
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > 50
	if more {
		list = list[:50]
	}
	out := []map[string]any{}
	for _, v := range list {
		out = append(out, reportReceipt(v))
	}
	WriteJSON(w, 200, map[string]any{"reports": out, "has_more": more})
}

func (s *Server) handleModReports(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	status := q.Get("status")
	if status != "" && status != "active" && status != "closed" && !store.ValidReportStatus(status) {
		WriteError(w, E(400, "bad_status", "Choose a report status."))
		return
	}
	kind := q.Get("kind")
	if kind != "" && reportParts[kind] == nil {
		WriteError(w, E(400, "bad_target", "Choose a kind of content."))
		return
	}
	reason := q.Get("reason")
	if reason != "" && !OneOf(reason, reportReasons...) {
		WriteError(w, E(400, "bad_reason", "Choose a reason."))
		return
	}
	before, _ := ids.Parse(q.Get("before"))
	after, _ := ids.Parse(q.Get("after"))
	// "group=item" shows each reported item once (the newest report about it), as the queue does
	f := store.ReportFilter{Status: status, Kind: kind, Reason: reason, Query: truncateRunes(q.Get("q"), 200), Before: before, Limit: 51, Duplicates: true, Grouped: q.Get("group") == "item"}
	if q.Get("sort") == "oldest" {
		f.Oldest, f.After, f.Before = true, after, 0
	}
	// "mine=1" is what apps before the queue's assignment filter send.
	switch {
	case q.Get("assigned") == "me" || q.Get("mine") == "1":
		f.Assigned = auth.UserFrom(r.Context()).ID
	case q.Get("assigned") == "none":
		f.Unassigned = true
	}
	list, err := s.Store.SafetyReports(r.Context(), f)
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > 50
	if more {
		list = list[:50]
	}
	counts, err := s.Store.SafetyReportCounts(r.Context())
	if err != nil {
		WriteError(w, err)
		return
	}
	who := []ids.ID{}
	for _, v := range list {
		publicReportSnapshot(v)
		who = append(who, v.ReporterID, v.SubjectID, v.AssignedTo)
	}
	WriteJSON(w, 200, map[string]any{"reports": list, "counts": counts, "has_more": more, "people": s.people(r.Context(), who...)})
}
func (s *Server) staffReport(w http.ResponseWriter, r *http.Request) *store.SafetyReport {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	v, err := s.Store.SafetyReportByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	return v
}
func (s *Server) handleModReport(w http.ResponseWriter, r *http.Request) {
	v := s.staffReport(w, r)
	if v == nil {
		return
	}
	events, err := s.Store.SafetyReportEvents(r.Context(), v.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	files, err := s.Store.SafetyReportFiles(r.Context(), v.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, a := range messageReportFiles(v) {
		// A client cannot hide an original file by labelling it a preview.
		files = append(files, store.BugAttachment{BlobID: a.BlobID, Name: a.Name, Mime: a.Type, Size: a.Size})
	}
	publicReportSnapshot(v)
	who := []ids.ID{v.ReporterID, v.SubjectID, v.AssignedTo}
	for _, e := range events {
		who = append(who, e.ActorID, e.AssignedTo)
	}
	related, err := s.Store.RelatedSafetyReports(r.Context(), v, 25)
	if err != nil {
		WriteError(w, err)
		return
	}
	for _, x := range related {
		publicReportSnapshot(x)
		// the panel shows who sent each report about the same thing
		who = append(who, x.SubjectID, x.ReporterID)
	}
	_ = s.Store.FillReportDuplicates(r.Context(), append([]*store.SafetyReport{v}, related...))
	stats, err := s.Store.SafetyReportStatsFor(r.Context(), v.ReporterID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, 200, map[string]any{"report": v, "events": events, "files": files, "people": s.people(r.Context(), who...), "related": related, "reporter_stats": stats, "subject": s.reportSubject(r.Context(), v.SubjectID)})
}

// reportSubject is where the responsible account stands, for the staff beside
// a report: its cases, restrictions and alt accounts. Nil for no account, a
// deleted one, or Nexa itself.
func (s *Server) reportSubject(ctx context.Context, id ids.ID) any {
	if id == 0 {
		return nil
	}
	u, err := s.Store.UserByID(ctx, id)
	if err != nil || notAPerson(u) {
		return nil
	}
	view, err := s.modUserDetail(ctx, auth.UserFrom(ctx), u)
	if err != nil {
		return nil
	}
	view.Widgets = nil
	if cases, err := s.Store.ModCasesForUser(ctx, id); err == nil {
		view.CaseCount = len(cases)
		for _, c := range cases {
			if c.Status == "open" {
				view.OpenCases++
			}
		}
	}
	return view
}
func (s *Server) handleModReportReview(w http.ResponseWriter, r *http.Request) {
	v := s.staffReport(w, r)
	if v == nil {
		return
	}
	var req struct {
		Version  int    `json:"version"`
		Status   string `json:"status"`
		Note     string `json:"note"`
		Assigned ids.ID `json:"assigned_to"`
		// Related closes the other active reports about the same item too.
		Related bool `json:"resolve_related"`
		// ReporterMessage is what the reporter is told with a closing decision,
		// in their Nexa conversation; empty tells them the outcome alone.
		ReporterMessage string `json:"reporter_message"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	note, ok := CleanText(req.Note, 2000)
	told, okTold := CleanText(req.ReporterMessage, 1000)
	if !ok || !okTold || !store.ValidReportStatus(req.Status) || req.Version <= 0 {
		WriteError(w, E(400, "bad_review", "Choose a status and keep notes under 2000 characters."))
		return
	}
	if (req.Status == "actioned" || req.Status == "dismissed") && req.Status != v.Status && len([]rune(note)) < 3 {
		WriteError(w, E(400, "review_reason", "Explain the outcome before closing the report."))
		return
	}
	me := auth.UserFrom(r.Context())
	if req.Assigned != 0 && req.Assigned != me.ID && req.Assigned != v.AssignedTo {
		WriteError(w, E(403, "bad_assignment", "You can claim a report for yourself or return it to the queue."))
		return
	}
	closed, err := s.Store.ReviewSafetyReport(r.Context(), v.ID, me.ID, req.Version, req.Status, note, told, req.Assigned, req.Related)
	if err != nil {
		if errors.Is(err, store.ErrReportConflict) {
			WriteError(w, E(409, "report_changed", "Another staff member changed this report. Reload it before saving."))
		} else {
			WriteError(w, err)
		}
		return
	}
	if now, err := s.Store.SafetyReportByID(r.Context(), v.ID); err == nil {
		s.emitReportChanges(r.Context(), now.Status != v.Status, false, append([]*store.SafetyReport{now}, closed...)...)
	}
	s.handleModReport(w, r)
}

func (s *Server) handleReportUpload(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if s.Media == nil {
		WriteError(w, E(503, "no_media", "Uploads are unavailable."))
		return
	}
	if ok, retry := s.limBugFile.Allow("reportfile:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	const limit = 50 << 20
	if r.ContentLength > limit {
		WriteError(w, bugFileError(media.ErrTooLarge))
		return
	}
	size := int64(limit)
	if r.ContentLength > 0 {
		size = r.ContentLength
	}
	release, err := s.reserveUpload(r.Context(), me.ID, size, false, false)
	if err != nil {
		WriteError(w, err)
		return
	}
	defer release()
	r.Body = http.MaxBytesReader(w, r.Body, limit+1)
	b, err := s.Media.PutPrivate(r.Context(), store.ReportBlobKind, r.Body, bugLimits(), me.ID)
	if err != nil {
		WriteError(w, bugFileError(err))
		return
	}
	name, _ := CleanText(strings.ReplaceAll(r.URL.Query().Get("name"), "\n", " "), 255)
	WriteJSON(w, 201, store.BugAttachment{BlobID: b.ID, Name: name, Mime: b.Mime, Size: b.Size, Width: b.Width, Height: b.Height})
}
func (s *Server) handleReportFile(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil || s.Media == nil {
		http.NotFound(w, r)
		return
	}
	blob, err := s.Store.BlobByID(r.Context(), id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	if blob.Kind == store.ReportBlobKind {
		owner, sent, err := s.Store.SafetyReportFileOwner(r.Context(), id)
		if err != nil || (owner != me.ID && !(sent && s.rankOf(r.Context(), me) >= rankMod)) {
			http.NotFound(w, r)
			return
		}
	} else {
		if s.rankOf(r.Context(), me) < rankMod {
			http.NotFound(w, r)
			return
		}
		saved, err := s.Store.SafetyReportSnapshotBlob(r.Context(), id)
		if err != nil || !saved {
			http.NotFound(w, r)
			return
		}
	}
	if blob.Kind == "attachment" {
		if s.rankOf(r.Context(), me) < rankMod {
			http.NotFound(w, r)
			return
		}
		raw, err := s.Store.SafetyReportAttachment(r.Context(), id)
		var a reportedAttachment
		if err != nil || json.Unmarshal(raw, &a) != nil {
			http.NotFound(w, r)
			return
		}
		key, _ := base64.StdEncoding.DecodeString(a.Key)
		s.Media.ServeReportedAttachment(w, r, blob, key, a.Size, a.Type)
		return
	}
	b := blob
	s.Media.ServePrivate(w, r, b, media.VariantSize(r.URL.Query().Get("size")))
}

func (s *Server) handleReportRemoveMessage(w http.ResponseWriter, r *http.Request) {
	v := s.staffReport(w, r)
	if v == nil {
		return
	}
	if v.TargetKind != "message" {
		WriteError(w, E(400, "bad_target", "This report is not about a message."))
		return
	}
	var req struct {
		Version         int    `json:"version"`
		Reason          string `json:"reason"`
		ReporterMessage string `json:"reporter_message"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	told, ok := CleanText(req.ReporterMessage, 1000)
	if !ok {
		WriteError(w, E(400, "bad_review", "Keep the message to the reporter under 1000 characters."))
		return
	}
	me := auth.UserFrom(r.Context())
	target, err := s.Store.UserByID(r.Context(), v.SubjectID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if block := s.moderationBlock(r.Context(), me, target); block != "" {
		WriteError(w, blockError(block))
		return
	}
	message, err := s.Store.MessageByID(r.Context(), v.TargetID, me.ID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	details := mustJSON(map[string]any{"message_id": v.TargetID, "channel_id": message.ChannelID})
	c := &store.ModCase{UserID: target.ID, ModeratorID: me.ID, Action: "message_remove", Reason: reason, TargetUsername: target.Username, TargetDisplayName: target.DisplayName, Details: details}
	log := &store.ModLogEntry{Action: c.Action, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &target.ID, TargetUsername: target.Username, Reason: reason, Details: details}
	channel, closed, err := s.Store.RemoveReportedMessage(r.Context(), v, req.Version, told, c, log)
	if err != nil {
		if errors.Is(err, store.ErrReportConflict) {
			WriteError(w, E(409, "report_changed", "This report changed. Reload it before taking action."))
		} else {
			WriteError(w, err)
		}
		return
	}
	ev := map[string]any{"id": message.ID, "channel_id": channel, "author_id": message.AuthorID, "mentions": message.Mentions, "mention_roles": message.MentionRoles, "mention_everyone": message.MentionEveryone}
	s.withLastMessage(r.Context(), ev, channel)
	s.Bcast.EmitChannel(channel, "message_delete", ev)
	if now, err := s.Store.SafetyReportByID(r.Context(), v.ID); err == nil {
		s.emitReportChanges(r.Context(), now.Status != v.Status, false, append([]*store.SafetyReport{now}, closed...)...)
	}
	if ch, err := s.Store.ChannelByID(r.Context(), channel); err == nil && ch.Type == store.ChannelAnnouncement {
		s.originalsGone(r.Context(), []ids.ID{message.ID})
	}
	s.actionDone(w, r.Context(), c, log)
}
