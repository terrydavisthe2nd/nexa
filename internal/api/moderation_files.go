package api

import (
	"errors"
	"net/http"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/store"
)

// Pictures and recordings in a case's thread. Staff and the member a case is
// about both send them: a screenshot of what was reported, the profile as it
// was, a member's side of it. A file goes up first, one request each, the
// bytes as the body (as the app sends bug report files), and the line that
// carries it names it when it is sent. They are private blobs: only the
// case's member and the staff can fetch them, through /media/cases/{id}, and
// like everything in a case they are not end-to-end encrypted.

func (s *Server) caseFileRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/nexa/cases/attachments", s.authed(s.handleCaseUpload))
	mux.Handle("POST /api/v1/moderation/cases/attachments", s.staff(rankMod, s.handleCaseUpload))
	mux.Handle("GET /media/cases/{id}", s.authed(s.handleCaseFile))
}

func init() {
	// the member's side of a case stays open to muted and banned accounts:
	// a case thread is where a mute or a ban is appealed
	for _, p := range []string{"POST /api/v1/nexa/cases/attachments", "GET /media/cases/{id}"} {
		banAllowed[p] = true
		muteAllowed[p] = true
	}
}

// handleCaseUpload takes one picture or recording for a line not sent yet.
func (s *Server) handleCaseUpload(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if s.Media == nil {
		WriteError(w, E(http.StatusServiceUnavailable, "no_media", "This server cannot take files right now."))
		return
	}
	if ok, retry := s.limBugFile.Allow("casefile:" + me.ID.String()); !ok {
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
	b, err := s.Media.PutPrivate(r.Context(), store.CaseBlobKind, r.Body, bugLimits(), me.ID)
	if err != nil {
		WriteError(w, bugFileError(err))
		return
	}
	name, _ := CleanText(strings.ReplaceAll(r.URL.Query().Get("name"), "\n", " "), 1<<10)
	name = truncateRunes(name, maxBugFileName)
	WriteJSON(w, http.StatusCreated, store.CaseFile{BlobID: b.ID, Name: name, Mime: b.Mime, Size: b.Size, Width: b.Width, Height: b.Height})
}

// handleCaseFile serves a case file to the member the case is about and to the
// staff, and to whoever uploaded it before it is sent. Anyone else is told
// there is no such file. ?size= asks for a picture no larger than that.
func (s *Server) handleCaseFile(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil || s.Media == nil {
		http.NotFound(w, r)
		return
	}
	b, err := s.Store.BlobByID(r.Context(), id)
	if err != nil || b.Kind != store.CaseBlobKind {
		http.NotFound(w, r)
		return
	}
	member, sent, err := s.Store.CaseFileOwner(r.Context(), id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	staff := me.Flags&store.StaffFlags != 0 && sent
	if member != me.ID && !staff {
		http.NotFound(w, r)
		return
	}
	s.Media.ServePrivate(w, r, b, media.VariantSize(r.URL.Query().Get("size")))
}

// caseLine is what a member or staff wrote in a thread: words, files, or both.
type caseLine struct {
	Body  string
	Files []store.CaseFile
}

// readCaseLine checks a line before it is written.
func readCaseLine(w http.ResponseWriter, r *http.Request) (caseLine, bool) {
	var req struct {
		Body  string `json:"body"`
		Files []struct {
			ID   ids.ID `json:"id"`
			Name string `json:"name"`
		} `json:"files"`
	}
	if err := ReadJSON(w, r, &req, 32<<10); err != nil {
		WriteError(w, err)
		return caseLine{}, false
	}
	body, ok := CleanText(req.Body, maxCaseMessage)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "too_long", "Messages here are at most 2000 characters."))
		return caseLine{}, false
	}
	if len(req.Files) > store.MaxCaseFiles {
		WriteError(w, E(http.StatusBadRequest, "too_many_files", "Send at most four files at a time."))
		return caseLine{}, false
	}
	line := caseLine{Body: body}
	seen := map[ids.ID]bool{}
	for _, f := range req.Files {
		if f.ID.IsZero() || seen[f.ID] {
			continue
		}
		seen[f.ID] = true
		name, _ := CleanText(strings.ReplaceAll(f.Name, "\n", " "), 1<<10)
		line.Files = append(line.Files, store.CaseFile{BlobID: f.ID, Name: truncateRunes(name, maxBugFileName)})
	}
	if body == "" && len(line.Files) == 0 {
		WriteError(w, E(http.StatusBadRequest, "empty_message", "Write something first."))
		return caseLine{}, false
	}
	return line, true
}

// caseWriteError says why a line could not be written, in words about cases.
func caseWriteError(err error) error {
	switch {
	case errors.Is(err, store.ErrCaseClosed):
		return E(http.StatusConflict, "case_closed", "This case is closed, so nobody can write in it.")
	case errors.Is(err, store.ErrCaseFile):
		return E(http.StatusBadRequest, "bad_file", "A file could not be sent with this. Add it again and send.")
	}
	return err
}
