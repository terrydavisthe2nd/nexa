package api

import (
	"errors"
	"net/http"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// Sound packs: the files behind the sounds someone picked for themselves.
// The app seals each one before it leaves the device, and the packs (which
// file plays for what, and the keys) travel in the account's encrypted
// settings, so all the server keeps is the sealed bytes and that they belong
// to this account. The account's own devices fetch them back like any
// attachment it uploaded (/media/attachments/{id}).

func (s *Server) soundRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/users/@me/sounds", s.authed(s.handleSounds))
	mux.Handle("POST /api/v1/users/@me/sounds", s.authed(s.handleSoundUpload))
	mux.Handle("DELETE /api/v1/users/@me/sounds/{blob}", s.authed(s.handleSoundDelete))
}

func (s *Server) handleSounds(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	list, err := s.Store.UserSounds(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"sounds": list, "max": store.MaxUserSounds, "max_bytes": store.MaxSoundBytes})
}

func (s *Server) handleSoundUpload(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if ok, retry := s.limUpload.Allow("up:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	limit := int64(store.MaxSoundBytes)
	size := limit
	if r.ContentLength > 0 && r.ContentLength < size {
		size = r.ContentLength
	}
	if r.ContentLength > limit {
		WriteError(w, E(http.StatusRequestEntityTooLarge, "sound_too_large", "That sound is too long. Pick a shorter one."))
		return
	}
	// counted before the bytes are taken, so a full set of sounds does not upload a file only to refuse it
	if have, err := s.Store.UserSounds(r.Context(), me.ID); err != nil {
		WriteError(w, err)
		return
	} else if len(have) >= store.MaxUserSounds {
		WriteError(w, errTooManySounds)
		return
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
	// a file that cannot be kept is left unheld, and swept like any upload nothing named
	switch err := s.Store.AddUserSound(r.Context(), me.ID, b.ID); {
	case errors.Is(err, store.ErrTooMany):
		WriteError(w, errTooManySounds)
		return
	case err != nil:
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusCreated, map[string]any{"blob_id": b.ID, "size": b.Size})
}

var errTooManySounds = E(http.StatusBadRequest, "too_many_sounds", "You have as many custom sounds as an account can keep. Remove some to add more.")

func (s *Server) handleSoundDelete(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("blob"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.RemoveUserSound(r.Context(), me.ID, id); err != nil {
		WriteError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
