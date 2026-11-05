package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/store"
)

// Bug reports: a member tells the Nexa team something is broken, with
// screenshots or a recording if they want, and the team answers in a thread
// that works like a moderation case's. The reporter reads it in their Nexa
// conversation; the staff work the reports from the Bug Reports tab of the
// moderation panel. None of it is end-to-end encrypted, since the team has to
// read it, and the form says so.
//
// Files go up first, one request each (POST /bugs/attachments, the bytes as
// the body, as the app sends avatars and emoji; staff use
// /moderation/bugs/attachments), and the report or the line of its thread
// that carries them names them when it is sent. They are private blobs: only
// the reporter and the staff can fetch them, through /media/bugs/{id}. A
// report staff finish (resolved, won't fix or closed) is closed to everyone
// until they set it going again.

// Who may file reports, and when. A muted account still can: a report goes to
// the staff alone, never to anyone the mute protects, and the limits below
// keep it from becoming a way to flood them. A banned account cannot send new
// ones or write in old ones: it is out of Nexa, and its ban's case
// thread is already its line to the staff. It can still read what it sent.
var bugReadRoutes = []string{
	"GET /api/v1/bugs",
	"GET /api/v1/bugs/{id}",
	"POST /api/v1/bugs/{id}/read",
	"GET /media/bugs/{id}",
}

var bugWriteRoutes = []string{
	"POST /api/v1/bugs/attachments",
	"POST /api/v1/bugs",
	"POST /api/v1/bugs/{id}/messages",
}

func init() {
	for _, p := range bugReadRoutes {
		banAllowed[p] = true
		muteAllowed[p] = true
	}
	for _, p := range bugWriteRoutes {
		muteAllowed[p] = true
	}
}

func (s *Server) bugRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/bugs/attachments", s.authed(s.handleBugUpload))
	mux.Handle("POST /api/v1/bugs", s.authed(s.handleBugCreate))
	mux.Handle("GET /api/v1/bugs", s.authed(s.handleBugsMine))
	mux.Handle("GET /api/v1/bugs/{id}", s.authed(s.handleBugMine))
	mux.Handle("POST /api/v1/bugs/{id}/messages", s.authed(s.handleBugReply))
	mux.Handle("POST /api/v1/bugs/{id}/read", s.authed(s.handleBugRead))
	mux.Handle("GET /media/bugs/{id}", s.authed(s.handleBugFile))

	mux.Handle("POST /api/v1/moderation/bugs/attachments", s.staff(rankHelper, s.handleBugUpload))
	mux.Handle("GET /api/v1/moderation/bugs", s.staff(rankHelper, s.handleModBugs))
	mux.Handle("GET /api/v1/moderation/bugs/{id}", s.staff(rankHelper, s.handleModBug))
	mux.Handle("POST /api/v1/moderation/bugs/{id}/messages", s.staff(rankHelper, s.handleModBugReply))
	mux.Handle("POST /api/v1/moderation/bugs/{id}/status", s.staff(rankHelper, s.handleModBugStatus))
}

const (
	minBugSubject     = 3
	maxBugSubject     = 120
	minBugDescription = 10
	maxBugDescription = 4000
	// A handful of screenshots and a short recording: enough to show a bug,
	// not a place to keep files.
	maxBugFiles      = 5
	maxBugImageBytes = 10 << 20
	maxBugVideoBytes = 50 << 20
	maxBugFilesBytes = 100 << 20
	maxBugFileName   = 120
	// A screenshot of a very wide desktop fits; its thumbnail is made by
	// decoding all of it, which is what the pixel cap bounds.
	maxBugImageSide   = 16384
	maxBugImagePixels = 50_000_000
	maxBugContextText = 120
)

// bugContextKeys are the facts about the reporter's app and device a report
// may carry, all of them shown to the reporter before sending. Nothing else a
// client sends is kept.
var bugContextKeys = map[string]bool{
	"app": true, "app_version": true, "build": true, "browser": true, "os": true, "device": true,
	"screen": true, "window": true, "language": true, "theme": true, "custom_css": true, "plugins": true, "opened_from": true,
}

func bugLimits() media.PrivateLimits {
	return media.PrivateLimits{ImageBytes: maxBugImageBytes, VideoBytes: maxBugVideoBytes, MaxSide: maxBugImageSide, MaxPixels: maxBugImagePixels}
}

// --- views ------------------------------------------------------------------------

// memberBug is a report as its reporter sees it.
type memberBug struct {
	ID          ids.ID                `json:"id"`
	Number      int64                 `json:"number"`
	Subject     string                `json:"subject"`
	Description string                `json:"description"`
	Context     json.RawMessage       `json:"context"`
	Status      string                `json:"status"`
	StatusAt    *int64                `json:"status_at"`
	StatusBy    *ids.ID               `json:"status_by"`
	Awaiting    string                `json:"awaiting"`
	Attachments []store.BugAttachment `json:"attachments"`
	// Unread is the staff replies and status changes the reporter has not
	// read; the staff's view leaves it at zero.
	Unread         int   `json:"unread"`
	LastActivityAt int64 `json:"last_activity_at"`
	CreatedAt      int64 `json:"created_at"`
}

// staffBug is a report as the staff see it: who sent it, as they were when
// they sent it, and the newest line of its thread.
type staffBug struct {
	memberBug
	UserID              ids.ID            `json:"user_id"`
	ReporterUsername    string            `json:"reporter_username"`
	ReporterDisplayName string            `json:"reporter_display_name"`
	LastMessage         *store.BugMessage `json:"last_message"`
}

func toMemberBug(r *store.BugReport, files []store.BugAttachment, unread int) memberBug {
	if files == nil {
		files = []store.BugAttachment{}
	}
	return memberBug{ID: r.ID, Number: r.Number, Subject: r.Subject, Description: r.Description, Context: r.Context, Status: r.Status,
		StatusAt: r.StatusAt, StatusBy: r.StatusBy, Awaiting: r.Awaiting, Attachments: files, Unread: unread, LastActivityAt: r.LastActivityAt, CreatedAt: r.CreatedAt}
}

func toStaffBug(r *store.BugReport, files []store.BugAttachment, last *store.BugMessage) staffBug {
	return staffBug{memberBug: toMemberBug(r, files, 0), UserID: r.UserID, ReporterUsername: r.ReporterUsername, ReporterDisplayName: r.ReporterDisplayName, LastMessage: last}
}

// bugPeople lists everyone a report's view names.
func bugPeople(r *store.BugReport, lines ...*store.BugMessage) []ids.ID {
	out := []ids.ID{r.UserID}
	if r.StatusBy != nil {
		out = append(out, *r.StatusBy)
	}
	for _, m := range lines {
		if m != nil {
			out = append(out, m.AuthorID)
		}
	}
	return out
}

// memberBugs builds the reporter's views of their reports, with their files
// and how much in each they have not read.
func (s *Server) memberBugs(ctx context.Context, uid ids.ID, list []*store.BugReport) ([]memberBug, error) {
	reportIDs := make([]ids.ID, 0, len(list))
	for _, r := range list {
		reportIDs = append(reportIDs, r.ID)
	}
	files, err := s.Store.BugAttachments(ctx, reportIDs)
	if err != nil {
		return nil, err
	}
	unread, err := s.Store.BugUnreadByReport(ctx, uid)
	if err != nil {
		return nil, err
	}
	out := make([]memberBug, 0, len(list))
	for _, r := range list {
		out = append(out, toMemberBug(r, files[r.ID], unread[r.ID]))
	}
	return out, nil
}

// memberBugView is one report as its reporter sees it.
func (s *Server) memberBugView(ctx context.Context, r *store.BugReport) (memberBug, error) {
	views, err := s.memberBugs(ctx, r.UserID, []*store.BugReport{r})
	if err != nil {
		return memberBug{}, err
	}
	return views[0], nil
}

// staffBugView is one report as the staff see it.
func (s *Server) staffBugView(ctx context.Context, r *store.BugReport, last *store.BugMessage) (staffBug, error) {
	files, err := s.Store.BugAttachments(ctx, []ids.ID{r.ID})
	if err != nil {
		return staffBug{}, err
	}
	if last == nil {
		if m, err := s.Store.LastBugMessages(ctx, []ids.ID{r.ID}); err == nil {
			last = m[r.ID]
		}
	}
	return toStaffBug(r, files[r.ID], last), nil
}

// --- events -----------------------------------------------------------------------

// emitBug tells the reporter and the staff that a report moved: its new
// state, and the lines added to its thread, if any. The reporter hears their
// Nexa standing with it, since a new report or a staff reply changes what is
// unread and when Nexa last had something for them.
func (s *Server) emitBug(ctx context.Context, r *store.BugReport, lines ...*store.BugMessage) {
	if lines == nil {
		lines = []*store.BugMessage{}
	}
	people := s.people(ctx, bugPeople(r, lines...)...)
	var last *store.BugMessage
	if n := len(lines); n > 0 {
		last = lines[n-1]
	}
	if view, err := s.staffBugView(ctx, r, last); err == nil {
		counts, _ := s.Store.CountBugReports(ctx)
		s.emitStaff(ctx, rankHelper, "mod_bug", map[string]any{"report": view, "messages": lines, "people": people, "counts": counts})
	}
	// what waits on the staff, and so what each of them has not read, may have changed
	s.emitModUnreadAll(ctx)
	u, err := s.Store.UserByID(ctx, r.UserID)
	if err != nil || u.Flags&store.FlagDeleted != 0 {
		return
	}
	st, err := s.nexaState(ctx, u)
	if err != nil {
		s.Log.Warn("nexa: state", "user", r.UserID.String(), "err", err)
		return
	}
	view, err := s.memberBugView(ctx, r)
	if err != nil {
		s.Log.Warn("bugs: view", "report", r.ID.String(), "err", err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{r.UserID}, "nexa_bug", map[string]any{"report": view, "messages": lines, "people": people, "state": st})
}

// --- the reporter's side ----------------------------------------------------------

// handleBugUpload takes one screenshot or recording for a report that is
// still being written.
func (s *Server) handleBugUpload(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if s.Media == nil {
		WriteError(w, E(http.StatusServiceUnavailable, "no_media", "This server cannot take files right now."))
		return
	}
	if ok, retry := s.limBugFile.Allow("bugfile:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, E(http.StatusTooManyRequests, "rate_limited", "You have added a lot of files in a short time. Wait a little, then try again."))
		return
	}
	limit := int64(max(maxBugImageBytes, maxBugVideoBytes))
	if r.ContentLength > limit {
		WriteError(w, bugFileError(media.ErrVideoTooLarge))
		return
	}
	size := limit
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
	b, err := s.Media.PutPrivate(r.Context(), store.BugBlobKind, r.Body, bugLimits(), me.ID)
	if err != nil {
		WriteError(w, bugFileError(err))
		return
	}
	name, _ := CleanText(strings.ReplaceAll(r.URL.Query().Get("name"), "\n", " "), 1<<10)
	name = truncateRunes(name, maxBugFileName)
	WriteJSON(w, http.StatusCreated, store.BugAttachment{BlobID: b.ID, Name: name, Mime: b.Mime, Size: b.Size, Width: b.Width, Height: b.Height})
}

// bugFileError says what was wrong with a file, in words about reports.
func bugFileError(err error) error {
	var maxErr *http.MaxBytesError
	switch {
	case errors.Is(err, media.ErrImageTooLarge):
		return E(http.StatusRequestEntityTooLarge, "image_too_large", "Pictures can be up to 10 MB.")
	case errors.Is(err, media.ErrVideoTooLarge), errors.Is(err, media.ErrTooLarge), errors.As(err, &maxErr):
		return E(http.StatusRequestEntityTooLarge, "video_too_large", "Recordings can be up to 50 MB.")
	case errors.Is(err, media.ErrUnsupported):
		return E(http.StatusBadRequest, "unsupported_type", "Add a picture (PNG, JPEG, GIF or WebP) or a video (MP4, MOV or WebM).")
	case errors.Is(err, media.ErrBadDimensions):
		return E(http.StatusBadRequest, "bad_dimensions", "That picture is too big to show. Try a smaller screenshot.")
	}
	return err
}

func truncateRunes(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// cleanBugContext keeps the facts a report may carry, each on one line and
// short. The server's own version is added by the server.
func (s *Server) cleanBugContext(in map[string]string) json.RawMessage {
	if len(in) == 0 {
		return json.RawMessage(`{}`)
	}
	out := map[string]string{}
	for k, v := range in {
		if !bugContextKeys[k] {
			continue
		}
		v, _ = CleanText(strings.ReplaceAll(v, "\n", " "), 1<<10)
		if v = truncateRunes(v, maxBugContextText); v != "" {
			out[k] = v
		}
	}
	if len(out) == 0 {
		return json.RawMessage(`{}`)
	}
	out["server"] = s.Version
	return mustJSON(out)
}

// handleBugCreate sends a report, with the files uploaded for it.
func (s *Server) handleBugCreate(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	var req struct {
		Subject     string            `json:"subject"`
		Description string            `json:"description"`
		Context     map[string]string `json:"context"`
		Attachments []struct {
			ID   ids.ID `json:"id"`
			Name string `json:"name"`
		} `json:"attachments"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	subject, ok := CleanText(strings.ReplaceAll(req.Subject, "\n", " "), maxBugSubject)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "subject_too_long", "Subjects are at most 120 characters."))
		return
	}
	if len([]rune(subject)) < minBugSubject {
		WriteError(w, E(http.StatusBadRequest, "subject_required", "Give the report a short subject."))
		return
	}
	description, ok := CleanText(req.Description, maxBugDescription)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "description_too_long", "Descriptions are at most 4000 characters."))
		return
	}
	if len([]rune(description)) < minBugDescription {
		WriteError(w, E(http.StatusBadRequest, "description_required", "Describe what happened in a sentence or two."))
		return
	}
	if len(req.Attachments) > maxBugFiles {
		WriteError(w, E(http.StatusBadRequest, "too_many_files", "A report can have up to 5 files."))
		return
	}
	files := make([]store.BugAttachment, 0, len(req.Attachments))
	seen := map[ids.ID]bool{}
	var total int64
	for _, a := range req.Attachments {
		if a.ID.IsZero() || seen[a.ID] {
			WriteError(w, E(http.StatusBadRequest, "bad_attachment", "One of the files is listed twice or not at all."))
			return
		}
		seen[a.ID] = true
		b, err := s.Store.BlobByID(ctx, a.ID)
		if err != nil || b.Kind != store.BugBlobKind || b.OwnerID == nil || *b.OwnerID != me.ID || b.RefCount != 0 {
			WriteError(w, errBugFileGone)
			return
		}
		total += b.Size
		name, _ := CleanText(strings.ReplaceAll(a.Name, "\n", " "), 1<<10)
		files = append(files, store.BugAttachment{BlobID: a.ID, Name: truncateRunes(name, maxBugFileName)})
	}
	if total > maxBugFilesBytes {
		WriteError(w, E(http.StatusRequestEntityTooLarge, "files_too_large", "The files come to more than 100 MB. Remove one and try again."))
		return
	}
	// a report lands in front of the staff, so a script cannot send them a flood
	if !s.requireCaptcha(w, r, "bug_report") {
		return
	}
	// counted once the report is sound, so a few mistakes in the form do not
	// use up someone's reports for the hour
	if ok, retry := s.limBug.Allow("bug:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, E(http.StatusTooManyRequests, "rate_limited", "You have sent several reports in a short time. Wait a little, then send this one."))
		return
	}
	report := &store.BugReport{UserID: me.ID, ReporterUsername: me.Username, ReporterDisplayName: me.DisplayName, Subject: subject, Description: description, Context: s.cleanBugContext(req.Context)}
	if err := s.Store.CreateBugReport(ctx, report, files); err != nil {
		if errors.Is(err, store.ErrBugAttachment) {
			WriteError(w, errBugFileGone)
			return
		}
		WriteError(w, err)
		return
	}
	s.emitBug(ctx, report)
	view, err := s.memberBugView(ctx, report)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"report": view, "messages": []*store.BugMessage{}, "people": s.people(ctx, me.ID)})
}

var errBugFileGone = E(http.StatusBadRequest, "attachment_gone", "One of the files is no longer there. Remove it, add it again, then send.")

// bugFiles names the files of a line for a report's thread.
func bugFiles(files []store.CaseFile) []store.BugAttachment {
	out := make([]store.BugAttachment, 0, len(files))
	for _, f := range files {
		out = append(out, store.BugAttachment{BlobID: f.BlobID, Name: f.Name})
	}
	return out
}

// bugWriteError says why a line could not be written in a report's thread.
func bugWriteError(err error) error {
	switch {
	case errors.Is(err, store.ErrBugClosed):
		return E(http.StatusConflict, "report_closed", "This report is finished, so nobody can write in it.")
	case errors.Is(err, store.ErrCaseFile):
		return E(http.StatusBadRequest, "bad_file", "A file could not be sent with this. Add it again and send.")
	}
	return err
}

// handleBugsMine lists the caller's own reports, newest first.
func (s *Server) handleBugsMine(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	list, err := s.Store.BugReportsForUser(ctx, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	views, err := s.memberBugs(ctx, me.ID, list)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"reports": views})
}

// ownBug loads a report of the caller's own; anyone else's is not found.
func (s *Server) ownBug(w http.ResponseWriter, r *http.Request) (*store.BugReport, *store.User) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	b, err := s.Store.BugReportByID(r.Context(), id)
	if err != nil || b.UserID != me.ID {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	return b, me
}

// bugThread is a report with its whole thread, for either side.
func (s *Server) bugThread(ctx context.Context, b *store.BugReport, staff bool) (map[string]any, error) {
	msgs, err := s.Store.BugMessages(ctx, b.ID, 0, store.MaxBugMessages)
	if err != nil {
		return nil, err
	}
	out := map[string]any{"messages": msgs, "people": s.people(ctx, bugPeople(b, msgs...)...)}
	if staff {
		var last *store.BugMessage
		if n := len(msgs); n > 0 {
			last = msgs[n-1]
		}
		out["report"], err = s.staffBugView(ctx, b, last)
	} else {
		out["report"], err = s.memberBugView(ctx, b)
	}
	return out, err
}

func (s *Server) handleBugMine(w http.ResponseWriter, r *http.Request) {
	b, _ := s.ownBug(w, r)
	if b == nil {
		return
	}
	out, err := s.bugThread(r.Context(), b, false)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleBugReply is the reporter writing in their own report's thread.
func (s *Server) handleBugReply(w http.ResponseWriter, r *http.Request) {
	b, me := s.ownBug(w, r)
	if b == nil {
		return
	}
	if ok, retry := s.limMsg.Allow("bug:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	line, ok := readCaseLine(w, r)
	if !ok {
		return
	}
	msg, updated, err := s.Store.AddBugMessage(r.Context(), b.ID, me.ID, store.ModMsgMember, line.Body, bugFiles(line.Files))
	if err != nil {
		WriteError(w, bugWriteError(err))
		return
	}
	s.emitBug(r.Context(), updated, msg)
	view, err := s.memberBugView(r.Context(), updated)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"message": msg, "messages": []*store.BugMessage{msg}, "report": view})
}

// handleBugRead moves the reporter's read position in one of their reports.
func (s *Server) handleBugRead(w http.ResponseWriter, r *http.Request) {
	b, me := s.ownBug(w, r)
	if b == nil {
		return
	}
	var req struct {
		MessageID ids.ID `json:"message_id"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if err := s.Store.MarkBugRead(r.Context(), b.ID, me.ID, req.MessageID); err != nil {
		WriteError(w, err)
		return
	}
	s.emitNexaState(r.Context(), me.ID)
	w.WriteHeader(http.StatusNoContent)
}

// handleBugFile serves a report's screenshot or recording to its reporter
// (who may also see a file they have uploaded and not sent yet) and to the
// staff, and to nobody else: anyone else is told there is no such file.
// ?size= asks for a picture no larger than that, for thumbnails.
func (s *Server) handleBugFile(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil || s.Media == nil {
		http.NotFound(w, r)
		return
	}
	b, err := s.Store.BlobByID(r.Context(), id)
	if err != nil || b.Kind != store.BugBlobKind {
		http.NotFound(w, r)
		return
	}
	owner, sent, err := s.Store.BugFileOwner(r.Context(), id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	staff := s.rankOf(r.Context(), me) >= rankHelper && sent
	if owner != me.ID && !staff {
		http.NotFound(w, r)
		return
	}
	s.Media.ServePrivate(w, r, b, media.VariantSize(r.URL.Query().Get("size")))
}

// --- the staff's side ---------------------------------------------------------------

// handleModBugs pages reports for the Bug Reports tab, with the counts every
// view of it shows.
func (s *Server) handleModBugs(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 100 {
		limit = 50
	}
	beforeAt, _ := strconv.ParseInt(q.Get("before_at"), 10, 64)
	beforeID, _ := ids.Parse(q.Get("before_id"))
	status := q.Get("status")
	if status != "active" && !store.ValidBugStatus(status) {
		status = ""
	}
	sort := q.Get("sort")
	if !OneOf(sort, "activity", "newest", "oldest") {
		sort = "activity"
	}
	f := store.BugFilter{Status: status, Waiting: q.Get("waiting") == "1", WithFiles: q.Get("files") == "1", Query: q.Get("q"), Sort: sort, BeforeAt: beforeAt, BeforeID: beforeID, Limit: limit}
	list, err := s.Store.BugReportList(ctx, f)
	if err != nil {
		WriteError(w, err)
		return
	}
	reportIDs := make([]ids.ID, 0, len(list))
	for _, b := range list {
		reportIDs = append(reportIDs, b.ID)
	}
	files, err := s.Store.BugAttachments(ctx, reportIDs)
	if err != nil {
		WriteError(w, err)
		return
	}
	last, err := s.Store.LastBugMessages(ctx, reportIDs)
	if err != nil {
		WriteError(w, err)
		return
	}
	views := make([]staffBug, 0, len(list))
	var who []ids.ID
	for _, b := range list {
		views = append(views, toStaffBug(b, files[b.ID], last[b.ID]))
		who = append(who, bugPeople(b, last[b.ID])...)
	}
	counts, err := s.Store.CountBugReports(ctx)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"reports": views, "counts": counts, "people": s.people(ctx, who...), "has_more": len(list) == limit})
}

// staffBugFor loads the report a staff route names.
func (s *Server) staffBugFor(w http.ResponseWriter, r *http.Request) *store.BugReport {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	b, err := s.Store.BugReportByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	return b
}

func (s *Server) handleModBug(w http.ResponseWriter, r *http.Request) {
	b := s.staffBugFor(w, r)
	if b == nil {
		return
	}
	out, err := s.bugThread(r.Context(), b, true)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, out)
}

// handleModBugReply is a member of staff writing in a report's thread. The
// reporter reads it as coming from Nexa, with the name of whoever wrote it.
func (s *Server) handleModBugReply(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	b := s.staffBugFor(w, r)
	if b == nil {
		return
	}
	if reporter, err := s.Store.UserByID(ctx, b.UserID); err != nil || reporter.Flags&store.FlagDeleted != 0 {
		WriteError(w, E(http.StatusGone, "reporter_deleted", "That account has been deleted, so nobody would read a reply."))
		return
	}
	line, ok := readCaseLine(w, r)
	if !ok {
		return
	}
	msg, updated, err := s.Store.AddBugMessage(ctx, b.ID, me.ID, store.ModMsgStaff, line.Body, bugFiles(line.Files))
	if err != nil {
		WriteError(w, bugWriteError(err))
		return
	}
	s.emitBug(ctx, updated, msg)
	view, err := s.staffBugView(ctx, updated, msg)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"message": msg, "messages": []*store.BugMessage{msg}, "report": view})
}

// handleModBugStatus moves a report to another status; the reporter sees the
// change in the thread.
func (s *Server) handleModBugStatus(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	b := s.staffBugFor(w, r)
	if b == nil {
		return
	}
	var req struct {
		Status string `json:"status"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !store.ValidBugStatus(req.Status) {
		WriteError(w, E(http.StatusBadRequest, "bad_status", "Pick open, in progress, resolved, won't fix or closed."))
		return
	}
	updated, line, err := s.Store.SetBugStatus(ctx, b.ID, me.ID, req.Status)
	if err != nil {
		WriteError(w, err)
		return
	}
	lines := []*store.BugMessage{}
	if line != nil {
		lines = append(lines, line)
		s.emitBug(ctx, updated, line)
	}
	view, err := s.staffBugView(ctx, updated, line)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"report": view, "messages": lines, "people": s.people(ctx, bugPeople(updated, lines...)...)})
}

// bugCountsFor is the staff's bug counts on arrival, or nothing when they
// cannot be read (the panel fetches them again).
func (s *Server) bugCountsFor(ctx context.Context) *store.BugCounts {
	c, err := s.Store.CountBugReports(ctx)
	if err != nil {
		s.Log.Warn("bugs: counts", "err", err)
		return nil
	}
	return &c
}
