package api

import (
	"context"
	"net/http"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// What each member of staff has read of the case threads and bug reports
// (store/moderation_reads.go). The badges on the panel's rail icon, its
// sidebar row and its tabs count the threads waiting on the staff that hold
// something new to them. Opening a thread reads it, and Mark as Read reads
// everything at once, the way any conversation is read.

func (s *Server) modReadRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/moderation/read", s.staff(rankHelper, s.handleModReadAll))
	mux.Handle("POST /api/v1/moderation/cases/{id}/read", s.staff(rankHelper, s.handleModThreadRead(store.ModReadCase)))
	mux.Handle("POST /api/v1/moderation/bugs/{id}/read", s.staff(rankHelper, s.handleModThreadRead(store.ModReadBug)))
}

// modUnreadFor is what a member of staff has not read, or empty lists when it
// cannot be read (the next change sends it again).
func (s *Server) modUnreadFor(ctx context.Context, uid ids.ID) store.ModUnread {
	u, err := s.Store.ModUnreadFor(ctx, uid)
	if err != nil {
		s.Log.Warn("moderation: unread", "user", uid.String(), "err", err)
		return store.ModUnread{Cases: []ids.ID{}, Bugs: []ids.ID{}}
	}
	// every rank handles bug reports, so they wait on all staff
	if me, err := s.Store.UserByID(ctx, uid); err != nil || s.rankOf(ctx, me) < rankHelper {
		u.Bugs = []ids.ID{}
	}
	return u
}

// emitModUnread tells each of uids' devices what it has not read now.
func (s *Server) emitModUnread(ctx context.Context, uids ...ids.ID) {
	for _, uid := range uids {
		s.Bcast.EmitUsers([]ids.ID{uid}, "mod_unread", s.modUnreadFor(ctx, uid))
	}
}

// emitModUnreadAll tells every member of staff what they have not read, after
// something that changes what waits on the staff: a member writing, a new
// report, a reply or a change of state.
func (s *Server) emitModUnreadAll(ctx context.Context) {
	staff, err := s.Store.StaffIDs(ctx, store.StaffFlags)
	if err != nil {
		s.Log.Warn("moderation: staff list", "err", err)
		return
	}
	s.emitModUnread(ctx, staff...)
}

// handleModReadAll is Mark as Read on the panel: every thread, read up to now.
func (s *Server) handleModReadAll(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if err := s.Store.MarkModReadAll(r.Context(), me.ID, ids.New()); err != nil {
		WriteError(w, err)
		return
	}
	unread := s.modUnreadFor(r.Context(), me.ID)
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "mod_unread", unread)
	WriteJSON(w, http.StatusOK, unread)
}

// handleModThreadRead reads one case thread or bug report up to a line (all
// of it without one), on every device of the staff member at once.
func (s *Server) handleModThreadRead(kind string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		me := auth.UserFrom(ctx)
		id, err := ids.Parse(r.PathValue("id"))
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		var req struct {
			MessageID ids.ID `json:"message_id"`
		}
		if r.ContentLength != 0 {
			if err := ReadJSON(w, r, &req, 1<<10); err != nil {
				WriteError(w, err)
				return
			}
		}
		if kind == store.ModReadCase {
			_, err = s.Store.ModCaseByID(ctx, id)
		} else {
			_, err = s.Store.BugReportByID(ctx, id)
		}
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		if err := s.Store.MarkModRead(ctx, me.ID, kind, id, req.MessageID); err != nil {
			WriteError(w, err)
			return
		}
		unread := s.modUnreadFor(ctx, me.ID)
		s.Bcast.EmitUsers([]ids.ID{me.ID}, "mod_unread", unread)
		WriteJSON(w, http.StatusOK, unread)
	}
}
