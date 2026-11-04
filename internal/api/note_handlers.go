package api

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net/http"
	"strconv"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// Notes: documents people keep, and write with others.
//
// A note is sealed on the device that writes it (see the migration), so
// everything here handles ciphertext: the server decides who may read and
// write a note and which save came first, and can read none of it. A save
// names the version it was made from; one made from an older version is
// refused with the newer version's number, and the app puts the two together
// and saves again. Sharing is by invitation, which the person invited accepts
// or declines, or by a link, whose key is in the part of it the server never
// sees.
//
// Writing together is live. Each app keeps a note as a CRDT and sends every
// change the moment it is made, sealed (POST .../updates): the server numbers
// it, keeps it, and passes it on to everyone else the note is shared with,
// whose app puts it into the note they have open; the CRDT puts everyone's
// changes together the same way everywhere, so nothing is lost or doubled.
// Every few seconds of writing an app also saves the note whole, saying which
// changes that holds, and those are let go. Who has a note open is said by
// the apps themselves and only passed along. Earlier states of a note are set
// aside as it is saved, to look back through and restore.

func (s *Server) noteRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/notes", s.authed(s.handleNotes))
	mux.Handle("POST /api/v1/notes", s.authed(s.handleNoteCreate))
	mux.Handle("GET /api/v1/notes/{id}", s.authed(s.handleNoteGet))
	mux.Handle("PUT /api/v1/notes/{id}", s.authed(s.handleNoteSave))
	mux.Handle("POST /api/v1/notes/{id}/updates", s.authed(s.handleNoteUpdatePost))
	mux.Handle("GET /api/v1/notes/{id}/updates", s.authed(s.handleNoteUpdates))
	mux.Handle("DELETE /api/v1/notes/{id}", s.authed(s.handleNoteDelete))
	mux.Handle("POST /api/v1/notes/{id}/rekey", s.authed(s.handleNoteRekey))
	mux.Handle("GET /api/v1/notes/{id}/versions", s.authed(s.handleNoteVersions))
	mux.Handle("GET /api/v1/notes/{id}/versions/{version}", s.authed(s.handleNoteVersion))
	mux.Handle("POST /api/v1/notes/{id}/presence", s.authed(s.handleNotePresence))
	mux.Handle("POST /api/v1/notes/{id}/attachments", s.authed(s.handleNoteUpload))
	mux.Handle("GET /api/v1/notes/{id}/sharing", s.authed(s.handleNoteSharing))
	mux.Handle("PATCH /api/v1/notes/{id}/members/{user}", s.authed(s.handleNoteMemberPatch))
	mux.Handle("DELETE /api/v1/notes/{id}/members/{user}", s.authed(s.handleNoteMemberRemove))
	mux.Handle("POST /api/v1/notes/{id}/invites", s.authed(s.handleNoteInvite))
	mux.Handle("DELETE /api/v1/notes/{id}/invites/{invite}", s.authed(s.handleNoteInviteCancel))
	mux.Handle("POST /api/v1/note-invites/{invite}/accept", s.authed(s.handleNoteInviteAccept))
	mux.Handle("DELETE /api/v1/note-invites/{invite}", s.authed(s.handleNoteInviteDecline))
	mux.Handle("POST /api/v1/notes/{id}/links", s.stepUpUser(s.stepBuild, s.handleNoteLinkCreate))
	mux.Handle("DELETE /api/v1/notes/{id}/links/{link}", s.authed(s.handleNoteLinkDelete))
	mux.Handle("GET /api/v1/note-links/{token}", s.authed(s.handleNoteLinkGet))
	mux.Handle("POST /api/v1/note-links/{token}/join", s.authed(s.handleNoteLinkJoin))
}

// sealed reports whether v is something a device sealed: not empty, within
// max bytes, and with nothing in it but what base64 and a version tag use.
// The server cannot check more than that of a ciphertext.
func sealed(v string, max int) bool {
	if v == "" || len(v) > max {
		return false
	}
	for i := 0; i < len(v); i++ {
		c := v[i]
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '+' || c == '/' || c == '=' || c == '-' || c == '_' || c == '.') {
			return false
		}
	}
	return true
}

var errNoteContent = E(http.StatusBadRequest, "bad_note", "That note could not be saved as it was sent.")

// noteTokenHash is how a share link's token is kept: by its hash, so the
// database alone does not open any note.
func noteTokenHash(token string) string {
	sum := sha256.Sum256([]byte("nexa-note-link\x00" + token))
	return hex.EncodeToString(sum[:])
}

// note loads the note in the path for the caller, who must hold at least
// `need` in it. It writes the error itself.
func (s *Server) note(w http.ResponseWriter, r *http.Request, need int) (*store.User, ids.ID, int) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, 0, 0
	}
	role, err := s.Store.NoteRole(r.Context(), id, me.ID)
	if err != nil {
		WriteError(w, err)
		return nil, 0, 0
	}
	if role < need {
		WriteError(w, E(http.StatusForbidden, "note_role", "You cannot do that with this note."))
		return nil, 0, 0
	}
	return me, id, role
}

// emitNote tells a note's members that it changed: its new head for their
// lists, and the version, so one that is open knows to fetch the rest.
func (s *Server) emitNote(r *http.Request, id ids.ID, d map[string]any) {
	members, err := s.Store.NoteMemberIDs(r.Context(), id)
	if err != nil || len(members) == 0 {
		return
	}
	d["id"] = id
	s.Bcast.EmitUsers(members, "note_update", d)
}

func (s *Server) handleNotes(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	notes, err := s.Store.NotesFor(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	invites, err := s.Store.NoteInvitesTo(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"notes": notes, "invites": s.noteInviteViews(r, invites)})
}

// noteInviteViews are invitations with who sent each, for the person invited.
func (s *Server) noteInviteViews(r *http.Request, invites []*store.NoteInvite) []map[string]any {
	out := make([]map[string]any, 0, len(invites))
	for _, inv := range invites {
		from, err := s.Store.UserByID(r.Context(), inv.FromID)
		if err != nil {
			continue
		}
		out = append(out, map[string]any{"id": inv.ID, "note_id": inv.NoteID, "role": inv.Role, "key_sealed": inv.KeySealed, "head": inv.Head, "created_at": inv.CreatedAt, "from": from.Public()})
	}
	return out
}

func (s *Server) handleNoteCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if ok, retry := s.limNote.Allow("note-new:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		Head      string `json:"head"`
		Body      string `json:"body"`
		KeySealed string `json:"key_sealed"`
	}
	if err := ReadJSON(w, r, &req, store.MaxNoteBodyBytes+store.MaxNoteHeadBytes+8<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !sealed(req.Head, store.MaxNoteHeadBytes) || !sealed(req.Body, store.MaxNoteBodyBytes) || !sealed(req.KeySealed, store.MaxNoteKeyBytes) {
		WriteError(w, errNoteContent)
		return
	}
	n := &store.Note{OwnerID: me.ID, Head: req.Head, Body: req.Body}
	if err := s.Store.CreateNote(r.Context(), n, req.KeySealed); err != nil {
		if errors.Is(err, store.ErrTooMany) {
			WriteError(w, E(http.StatusBadRequest, "too_many_notes", "You have as many notes as an account can hold. Delete some to make more."))
			return
		}
		WriteError(w, err)
		return
	}
	n.Body = ""
	// the owner's other devices see it appear
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_update", map[string]any{"id": n.ID, "version": n.Version, "head": n.Head, "updated_at": n.UpdatedAt, "edited_by": me.ID, "members": true})
	WriteJSON(w, http.StatusCreated, n)
}

func (s *Server) handleNoteGet(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	n, err := s.Store.NoteFor(r.Context(), id, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, n)
}

func (s *Server) handleNoteSave(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteEditor)
	if me == nil {
		return
	}
	if ok, retry := s.limNoteSave.Allow("note-save:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		Head  string   `json:"head"`
		Body  string   `json:"body"`
		Base  int64    `json:"base_version"`
		Files []ids.ID `json:"files"`
		// Keep sets the state being replaced aside as a version whatever the
		// clock says: sent when an old version is being put back.
		Keep bool `json:"keep"`
		// Upto is the newest change the body holds. An app from before notes
		// were written change by change sends none.
		Upto *int64 `json:"upto"`
	}
	if err := ReadJSON(w, r, &req, store.MaxNoteBodyBytes+store.MaxNoteHeadBytes+64<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !sealed(req.Head, store.MaxNoteHeadBytes) || !sealed(req.Body, store.MaxNoteBodyBytes) || len(req.Files) > store.MaxNoteFiles {
		WriteError(w, errNoteContent)
		return
	}
	upto := int64(-1)
	if req.Upto != nil {
		if *req.Upto < 0 {
			WriteError(w, errNoteContent)
			return
		}
		upto = *req.Upto
	}
	version, updated, err := s.Store.SaveNote(r.Context(), id, me.ID, req.Base, req.Head, req.Body, req.Files, req.Keep, upto)
	var files *store.NoteFilesError
	switch {
	case errors.Is(err, store.ErrConflict):
		// it says it holds changes that were never made
		WriteError(w, errNoteContent)
		return
	case errors.Is(err, store.ErrStale):
		// not an error to the app: it fetches the newer note, puts its own
		// changes into it, and saves again
		WriteJSON(w, http.StatusConflict, map[string]any{"error": map[string]string{"code": "note_stale", "message": "This note was changed somewhere else."}, "version": version})
		return
	case errors.As(err, &files):
		// which ones, so the app can take them out of the note and save the rest
		WriteJSON(w, http.StatusBadRequest, map[string]any{"error": map[string]string{"code": "note_file", "message": "A file in this note could not be kept. Add it again."}, "files": files.Files})
		return
	case errors.Is(err, store.ErrTooMany):
		WriteError(w, E(http.StatusBadRequest, "note_files_full", "This note holds as many files as a note can."))
		return
	case err != nil:
		WriteError(w, err)
		return
	}
	// Sent on to everyone else in the note as it lands, so the note they have
	// open follows without asking for it. A long note goes by its version
	// alone, and whoever has it open fetches it.
	ev := map[string]any{"version": version, "head": req.Head, "updated_at": updated, "edited_by": me.ID}
	if len(req.Body) <= noteLiveBytes {
		ev["body"] = req.Body
	}
	if upto >= 0 {
		ev["upto"] = upto
	} else {
		// saved whole by an app that knows nothing of changes: the note is what it says
		ev["reset"] = true
	}
	s.emitNote(r, id, ev)
	WriteJSON(w, http.StatusOK, map[string]any{"version": version, "updated_at": updated})
}

// handleNoteUpdatePost keeps one change to a note and passes it on, sealed,
// to everyone the note is shared with; the app that made it knows it by `cid`
// when it comes back. A change sealed with a key the note no longer has, or
// to a note that has gone too long without being saved whole, is refused
// with a code that says what the app should do first.
func (s *Server) handleNoteUpdatePost(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteEditor)
	if me == nil {
		return
	}
	if ok, retry := s.limNoteUpdate.Allow("note-update:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		Update string `json:"update"`
		Epoch  int    `json:"epoch"`
		// which open copy of the note sent it, so that copy can tell its own change when it comes back
		CID string `json:"cid"`
	}
	if err := ReadJSON(w, r, &req, store.MaxNoteUpdateBytes+4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !sealed(req.Update, store.MaxNoteUpdateBytes) || len(req.CID) > 32 || !sealedOrEmpty(req.CID) {
		WriteError(w, errNoteContent)
		return
	}
	seq, at, err := s.Store.AppendNoteUpdate(r.Context(), id, me.ID, req.Epoch, req.Update)
	switch {
	case errors.Is(err, store.ErrRekeyed):
		WriteError(w, E(http.StatusConflict, "note_rekeyed", "This note has a new key. Open it again."))
		return
	case errors.Is(err, store.ErrSaveFirst):
		WriteError(w, E(http.StatusConflict, "note_save_first", "Save this note before changing it further."))
		return
	case err != nil:
		WriteError(w, err)
		return
	}
	ev := map[string]any{"id": id, "seq": seq, "user_id": me.ID, "cid": req.CID, "at": at}
	// a big change goes by its number alone, and whoever has the note open fetches it
	if len(req.Update) <= noteLiveBytes {
		ev["update"] = req.Update
	}
	if members, err := s.Store.NoteMemberIDs(r.Context(), id); err == nil && len(members) > 0 {
		s.Bcast.EmitUsers(members, "note_crdt", ev)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"seq": seq, "at": at})
}

// sealedOrEmpty is sealed() for a value that may be left out.
func sealedOrEmpty(v string) bool { return v == "" || sealed(v, len(v)) }

// handleNoteUpdates lists a note's changes after ?after=, for an app catching
// up: on opening the note (the changes since it was last saved whole), or
// after missing some while its connection was down.
func (s *Server) handleNoteUpdates(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)
	if after < 0 {
		after = 0
	}
	list, seq, upto, err := s.Store.NoteUpdates(r.Context(), id, me.ID, after, noteUpdatesPage)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"updates": list, "crdt_seq": seq, "crdt_upto": upto, "more": len(list) == noteUpdatesPage})
}

// noteUpdatesPage is how many changes one fetch brings back.
const noteUpdatesPage = 500

// noteLiveBytes is the longest sealed note sent along with the news that it
// changed. Past it only the news goes, and the note is fetched by those who
// have it open.
const noteLiveBytes = 96 << 10

// handleNotePresence passes on that the caller has a note open, or has closed
// it, to everyone else the note is shared with. The server keeps no list:
// each app says it is there every so often and forgets anyone who stops
// saying so, and one that has just arrived asks (`hello`) for the others to
// say so at once.
func (s *Server) handleNotePresence(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	if ok, retry := s.limNotePresence.Allow("note-here:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		Here  bool `json:"here"`
		Hello bool `json:"hello"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	members, err := s.Store.NoteMemberIDs(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	others := make([]ids.ID, 0, len(members))
	for _, m := range members {
		if m != me.ID {
			others = append(others, m)
		}
	}
	if len(others) > 0 {
		s.Bcast.EmitUsers(others, "note_presence", map[string]any{"id": id, "user_id": me.ID, "here": req.Here, "hello": req.Here && req.Hello})
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleNoteVersions lists a note's earlier states, with the note's earlier
// keys: a state sealed before the key changed is opened with the key of its
// time, which the newer key opens.
func (s *Server) handleNoteVersions(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	versions, err := s.Store.NoteVersions(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	keys, err := s.Store.NoteKeys(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"versions": versions, "keys": keys})
}

func (s *Server) handleNoteVersion(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	vid, err := ids.Parse(r.PathValue("version"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	v, err := s.Store.NoteVersionByID(r.Context(), id, vid)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, v)
}

func (s *Server) handleNoteDelete(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	invited, _ := s.Store.NoteInvitesOf(r.Context(), id)
	members, err := s.Store.DeleteNote(r.Context(), id)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers(members, "note_delete", map[string]any{"id": id})
	for _, inv := range invited {
		s.Bcast.EmitUsers([]ids.ID{inv.ToID}, "note_invite_delete", map[string]any{"id": inv.ID})
	}
	w.WriteHeader(http.StatusNoContent)
}

// handleNoteUpload takes a file for a note: the same sealed bytes a message's
// attachment is, from someone who may edit the note. The note's people may
// read it straight away (note_uploads), as everyone writing in the note sees
// it the moment it is added; it belongs to the note once a save names it, and
// one never named is swept away like any upload nothing refers to.
func (s *Server) handleNoteUpload(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteEditor)
	if me == nil {
		return
	}
	if ok, retry := s.limUpload.Allow("up:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	limit := s.Cfg.MaxUploadBytes
	if limit > MaxSingleUploadBytes {
		limit = MaxSingleUploadBytes
	}
	size := limit
	if r.ContentLength > 0 && r.ContentLength < size {
		size = r.ContentLength
	}
	release, err := s.reserveUpload(r.Context(), me.ID, size, false, true)
	if err != nil {
		WriteError(w, err)
		return
	}
	defer release()
	r.Body = http.MaxBytesReader(w, r.Body, limit+1)
	b, err := s.Media.PutEncrypted(r.Context(), r.Body, limit, r.ContentLength, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// the others writing in the note see it at once, so they may fetch it at once
	if err := s.Store.AddNoteUpload(r.Context(), id, b.ID); err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"blob_id": b.ID, "size": b.Size, "note_id": id})
}

// handleNoteSharing is who a note is shared with. Every member sees the
// members; the invitations out and the links are the owner's to see.
func (s *Server) handleNoteSharing(w http.ResponseWriter, r *http.Request) {
	me, id, role := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	ctx := r.Context()
	members, err := s.Store.NoteMembers(ctx, id)
	if err != nil {
		WriteError(w, err)
		return
	}
	people := make([]map[string]any, 0, len(members))
	for _, m := range members {
		u, err := s.Store.UserByID(ctx, m.UserID)
		if err != nil {
			continue
		}
		people = append(people, map[string]any{"user": u.Public(), "role": m.Role, "created_at": m.CreatedAt})
	}
	out := map[string]any{"members": people, "invites": []any{}, "links": []any{}}
	if role == store.NoteOwner {
		invites, err := s.Store.NoteInvitesOf(ctx, id)
		if err != nil {
			WriteError(w, err)
			return
		}
		waiting := make([]map[string]any, 0, len(invites))
		for _, inv := range invites {
			u, err := s.Store.UserByID(ctx, inv.ToID)
			if err != nil {
				continue
			}
			waiting = append(waiting, map[string]any{"id": inv.ID, "role": inv.Role, "created_at": inv.CreatedAt, "user": u.Public()})
		}
		links, err := s.Store.NoteLinks(ctx, id)
		if err != nil {
			WriteError(w, err)
			return
		}
		out["invites"], out["links"] = waiting, links
	}
	WriteJSON(w, http.StatusOK, out)
}

func noteRole(v int) bool { return v == store.NoteViewer || v == store.NoteEditor }

func (s *Server) handleNoteMemberPatch(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	uid, err := ids.Parse(r.PathValue("user"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	var req struct {
		Role int `json:"role"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !noteRole(req.Role) {
		WriteError(w, E(http.StatusBadRequest, "bad_role", "A note is shared to read or to edit."))
		return
	}
	if err := s.Store.SetNoteMemberRole(r.Context(), id, uid, req.Role); err != nil {
		WriteError(w, err)
		return
	}
	s.emitNote(r, id, map[string]any{"members": true})
	w.WriteHeader(http.StatusNoContent)
}

// handleNoteMemberRemove takes someone out of a note: its owner removing
// them, or a member leaving ("@me").
func (s *Server) handleNoteMemberRemove(w http.ResponseWriter, r *http.Request) {
	me, id, role := s.note(w, r, store.NoteViewer)
	if me == nil {
		return
	}
	uid := me.ID
	if who := r.PathValue("user"); who != "@me" {
		parsed, err := ids.Parse(who)
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		uid = parsed
	}
	if uid != me.ID && role != store.NoteOwner {
		WriteError(w, E(http.StatusForbidden, "note_role", "You cannot do that with this note."))
		return
	}
	if err := s.Store.RemoveNoteMember(r.Context(), id, uid); err != nil {
		if errors.Is(err, store.ErrNotFound) && uid == me.ID && role == store.NoteOwner {
			WriteError(w, E(http.StatusBadRequest, "note_owner", "A note's owner cannot leave it. Delete the note instead."))
			return
		}
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{uid}, "note_delete", map[string]any{"id": id})
	// whoever has it open sees them leave it at once: their own app can no longer say so
	if members, err := s.Store.NoteMemberIDs(r.Context(), id); err == nil && len(members) > 0 {
		s.Bcast.EmitUsers(members, "note_presence", map[string]any{"id": id, "user_id": uid, "here": false, "hello": false})
	}
	// removed: says the note's key should be changed, which the owner's app does
	s.emitNote(r, id, map[string]any{"members": true, "removed": true})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleNoteInvite(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "note_invite") {
		return
	}
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	if ok, retry := s.limNoteInvite.Allow("note-invite:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		UserID    ids.ID `json:"user_id"`
		Role      int    `json:"role"`
		KeySealed string `json:"key_sealed"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !noteRole(req.Role) || !sealed(req.KeySealed, store.MaxNoteKeyBytes) {
		WriteError(w, errNoteContent)
		return
	}
	ctx := r.Context()
	target, err := s.Store.UserByID(ctx, req.UserID)
	if err != nil || target.Flags&store.FlagDeleted != 0 || isWebhookAccount(target) || isSystem(target) {
		WriteError(w, E(http.StatusNotFound, "user_not_found", "No user with that username."))
		return
	}
	if target.ID == me.ID {
		WriteError(w, E(http.StatusBadRequest, "self", "This note is yours already."))
		return
	}
	// someone who has blocked you is not asked things by you, and the other way round
	ab, ba, err := s.Store.Blocked(ctx, me.ID, target.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if ab || ba {
		WriteError(w, E(http.StatusForbidden, "blocked", "You cannot invite this user."))
		return
	}
	inv := &store.NoteInvite{NoteID: id, FromID: me.ID, ToID: target.ID, Role: req.Role, KeySealed: req.KeySealed}
	switch err := s.Store.CreateNoteInvite(ctx, inv); {
	case errors.Is(err, store.ErrAlreadyExists):
		WriteError(w, E(http.StatusConflict, "already_member", "They are in this note already."))
		return
	case errors.Is(err, store.ErrTooMany):
		WriteError(w, E(http.StatusBadRequest, "note_full", "This note is shared with as many people as a note can be."))
		return
	case err != nil:
		WriteError(w, err)
		return
	}
	if full, err := s.Store.NoteInviteByID(ctx, inv.ID); err == nil {
		if views := s.noteInviteViews(r, []*store.NoteInvite{full}); len(views) == 1 {
			s.Bcast.EmitUsers([]ids.ID{target.ID}, "note_invite", views[0])
		}
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_update", map[string]any{"id": id, "members": true})
	WriteJSON(w, http.StatusCreated, map[string]any{"id": inv.ID, "role": inv.Role, "created_at": inv.CreatedAt, "user": target.Public()})
}

func (s *Server) handleNoteInviteCancel(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	invID, err := ids.Parse(r.PathValue("invite"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	inv, err := s.Store.NoteInviteByID(r.Context(), invID)
	if err != nil || inv.NoteID != id {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.DeleteNoteInvite(r.Context(), invID); err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{inv.ToID}, "note_invite_delete", map[string]any{"id": invID})
	// removed: the invitation carried the note's key, so the note gets a new one
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_update", map[string]any{"id": id, "members": true, "removed": true})
	w.WriteHeader(http.StatusNoContent)
}

// invite loads the invitation in the path, which must be to the caller.
func (s *Server) invite(w http.ResponseWriter, r *http.Request) (*store.User, *store.NoteInvite) {
	me := auth.UserFrom(r.Context())
	invID, err := ids.Parse(r.PathValue("invite"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	inv, err := s.Store.NoteInviteByID(r.Context(), invID)
	if err != nil || inv.ToID != me.ID {
		WriteError(w, E(http.StatusNotFound, "no_invite", "That invitation is no longer there."))
		return nil, nil
	}
	return me, inv
}

func (s *Server) handleNoteInviteAccept(w http.ResponseWriter, r *http.Request) {
	me, inv := s.invite(w, r)
	if me == nil {
		return
	}
	if _, err := s.Store.AcceptNoteInvite(r.Context(), inv.ID, me.ID); err != nil {
		if errors.Is(err, store.ErrTooMany) {
			WriteError(w, E(http.StatusBadRequest, "note_full", "This note is shared with as many people as a note can be."))
			return
		}
		WriteError(w, err)
		return
	}
	n, err := s.Store.NoteFor(r.Context(), inv.NoteID, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_invite_delete", map[string]any{"id": inv.ID})
	s.emitNote(r, inv.NoteID, map[string]any{"version": n.Version, "head": n.Head, "updated_at": n.UpdatedAt, "members": true})
	WriteJSON(w, http.StatusOK, n)
}

func (s *Server) handleNoteInviteDecline(w http.ResponseWriter, r *http.Request) {
	me, inv := s.invite(w, r)
	if me == nil {
		return
	}
	if err := s.Store.DeleteNoteInvite(r.Context(), inv.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_invite_delete", map[string]any{"id": inv.ID})
	s.Bcast.EmitUsers([]ids.ID{inv.FromID}, "note_update", map[string]any{"id": inv.NoteID, "members": true, "removed": true})
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleNoteLinkCreate(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	var req struct {
		// the hash of the link's token, made on the device: the token itself
		// only reaches the server when somebody opens the link
		TokenHash  string `json:"token_hash"`
		Role       int    `json:"role"`
		KeyWrapped string `json:"key_wrapped"`
		LinkSealed string `json:"link_sealed"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	if raw, err := hex.DecodeString(req.TokenHash); err != nil || len(raw) != sha256.Size || strings.ToLower(req.TokenHash) != req.TokenHash {
		WriteError(w, errNoteContent)
		return
	}
	if !noteRole(req.Role) || !sealed(req.KeyWrapped, store.MaxNoteKeyBytes) || !sealed(req.LinkSealed, 2*store.MaxNoteKeyBytes) {
		WriteError(w, errNoteContent)
		return
	}
	l := &store.NoteLink{NoteID: id, TokenHash: req.TokenHash, Role: req.Role, KeyWrapped: req.KeyWrapped, LinkSealed: req.LinkSealed, CreatedBy: &me.ID}
	if err := s.Store.CreateNoteLink(r.Context(), l); err != nil {
		if errors.Is(err, store.ErrTooMany) {
			WriteError(w, E(http.StatusBadRequest, "too_many_links", "This note has as many links as a note can. Remove one to make another."))
			return
		}
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_update", map[string]any{"id": id, "members": true})
	WriteJSON(w, http.StatusCreated, l)
}

func (s *Server) handleNoteLinkDelete(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	linkID, err := ids.Parse(r.PathValue("link"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.DeleteNoteLink(r.Context(), id, linkID); err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "note_update", map[string]any{"id": id, "members": true, "removed": true})
	w.WriteHeader(http.StatusNoContent)
}

// noteLink finds the share link whose token is in the path.
func (s *Server) noteLink(w http.ResponseWriter, r *http.Request) (*store.User, *store.NoteLink) {
	me := auth.UserFrom(r.Context())
	token := r.PathValue("token")
	if len(token) < 16 || len(token) > 128 {
		WriteError(w, E(http.StatusNotFound, "no_link", "That link no longer works."))
		return nil, nil
	}
	// guessing tokens is metered per account, like any lookup of a secret
	if ok, retry := s.limNoteLink.Allow("note-link:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return nil, nil
	}
	l, err := s.Store.NoteLinkByHash(r.Context(), noteTokenHash(token))
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "no_link", "That link no longer works."))
		return nil, nil
	}
	return me, l
}

// handleNoteLinkGet is what a share link leads to, for whoever holds it: the
// note's sealed head and the key the link's own key opens, so the app can
// show what the note is before the person joins it.
func (s *Server) handleNoteLinkGet(w http.ResponseWriter, r *http.Request) {
	me, l := s.noteLink(w, r)
	if me == nil {
		return
	}
	ctx := r.Context()
	// read as the owner would, since the caller may not be a member yet; the
	// owner is the first of the members
	members, err := s.Store.NoteMembers(ctx, l.NoteID)
	if err != nil || len(members) == 0 {
		WriteError(w, E(http.StatusNotFound, "no_link", "That link no longer works."))
		return
	}
	owner := members[0].UserID
	n, err := s.Store.NoteFor(ctx, l.NoteID, owner)
	if err != nil {
		WriteError(w, E(http.StatusNotFound, "no_link", "That link no longer works."))
		return
	}
	out := map[string]any{"note_id": l.NoteID, "role": l.Role, "key_wrapped": l.KeyWrapped, "head": n.Head, "updated_at": n.UpdatedAt, "members": len(members), "member_role": 0}
	if u, err := s.Store.UserByID(ctx, owner); err == nil {
		out["owner"] = u.Public()
	}
	if role, err := s.Store.NoteRole(ctx, l.NoteID, me.ID); err == nil {
		out["member_role"] = role
	}
	WriteJSON(w, http.StatusOK, out)
}

func (s *Server) handleNoteLinkJoin(w http.ResponseWriter, r *http.Request) {
	me, l := s.noteLink(w, r)
	if me == nil {
		return
	}
	// joining from a link, as joining a server from an invite is
	if !s.requireCaptcha(w, r, "note_join") {
		return
	}
	var req struct {
		KeySealed string `json:"key_sealed"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !sealed(req.KeySealed, store.MaxNoteKeyBytes) {
		WriteError(w, errNoteContent)
		return
	}
	link, joined, err := s.Store.JoinNoteLink(r.Context(), l.TokenHash, me.ID, req.KeySealed)
	if err != nil {
		if errors.Is(err, store.ErrTooMany) {
			WriteError(w, E(http.StatusBadRequest, "note_full", "This note is shared with as many people as a note can be."))
			return
		}
		WriteError(w, err)
		return
	}
	n, err := s.Store.NoteFor(r.Context(), link.NoteID, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if joined {
		s.emitNote(r, link.NoteID, map[string]any{"version": n.Version, "head": n.Head, "updated_at": n.UpdatedAt, "members": true})
	}
	WriteJSON(w, http.StatusOK, n)
}

func (s *Server) handleNoteRekey(w http.ResponseWriter, r *http.Request) {
	me, id, _ := s.note(w, r, store.NoteOwner)
	if me == nil {
		return
	}
	var req struct {
		Head string `json:"head"`
		Body string `json:"body"`
		Base int64  `json:"base_version"`
		// the key being replaced, sealed with the new one
		PrevKey string `json:"prev_key"`
		Members []struct {
			UserID    ids.ID `json:"user_id"`
			KeySealed string `json:"key_sealed"`
		} `json:"members"`
		Invites []struct {
			ID        ids.ID `json:"id"`
			KeySealed string `json:"key_sealed"`
		} `json:"invites"`
		Links []struct {
			ID         ids.ID `json:"id"`
			KeyWrapped string `json:"key_wrapped"`
			LinkSealed string `json:"link_sealed"`
		} `json:"links"`
		// the newest change the body holds, which has to be the newest there is
		Upto *int64 `json:"upto"`
	}
	if err := ReadJSON(w, r, &req, store.MaxNoteBodyBytes+store.MaxNoteHeadBytes+256<<10); err != nil {
		WriteError(w, err)
		return
	}
	if !sealed(req.Head, store.MaxNoteHeadBytes) || !sealed(req.Body, store.MaxNoteBodyBytes) || !sealed(req.PrevKey, store.MaxNoteKeyBytes) {
		WriteError(w, errNoteContent)
		return
	}
	rekey := store.NoteRekey{Head: req.Head, Body: req.Body, PrevKey: req.PrevKey, Members: map[ids.ID]string{}, Invites: map[ids.ID]string{}, Links: map[ids.ID][2]string{}, Upto: -1}
	if req.Upto != nil && *req.Upto >= 0 {
		rekey.Upto = *req.Upto
	}
	for _, m := range req.Members {
		if !sealed(m.KeySealed, store.MaxNoteKeyBytes) {
			WriteError(w, errNoteContent)
			return
		}
		rekey.Members[m.UserID] = m.KeySealed
	}
	for _, inv := range req.Invites {
		if sealed(inv.KeySealed, store.MaxNoteKeyBytes) {
			rekey.Invites[inv.ID] = inv.KeySealed
		}
	}
	for _, l := range req.Links {
		if sealed(l.KeyWrapped, store.MaxNoteKeyBytes) && sealed(l.LinkSealed, 2*store.MaxNoteKeyBytes) {
			rekey.Links[l.ID] = [2]string{l.KeyWrapped, l.LinkSealed}
		}
	}
	version, updated, err := s.Store.RekeyNote(r.Context(), id, me.ID, req.Base, rekey)
	switch {
	case errors.Is(err, store.ErrStale):
		WriteJSON(w, http.StatusConflict, map[string]any{"error": map[string]string{"code": "note_stale", "message": "This note was changed somewhere else."}, "version": version})
		return
	case errors.Is(err, store.ErrConflict):
		WriteError(w, E(http.StatusConflict, "note_members_changed", "Who this note is shared with changed. Try again."))
		return
	case err != nil:
		WriteError(w, err)
		return
	}
	// rekeyed: everyone's copy of the key changed, so each fetches the note again
	s.emitNote(r, id, map[string]any{"version": version, "head": req.Head, "updated_at": updated, "edited_by": me.ID, "members": true, "rekeyed": true})
	WriteJSON(w, http.StatusOK, map[string]any{"version": version, "updated_at": updated})
}
