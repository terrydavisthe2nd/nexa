package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/unfurl"
)

var reEmojiName = regexp.MustCompile(`^[A-Za-z0-9_]{2,32}$`)

const (
	maxPackName    = 100
	maxPackDesc    = 500
	maxStickerName = 30
	maxStickerTags = 200
)

func (s *Server) expressionRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/servers/{id}/emojis", s.authed(s.handleEmojis))
	mux.Handle("POST /api/v1/servers/{id}/emojis", s.userLimited(s.limExpr, s.handleEmojiCreate))
	mux.Handle("PATCH /api/v1/servers/{id}/emojis/{eid}", s.authed(s.handleEmojiPatch))
	mux.Handle("DELETE /api/v1/servers/{id}/emojis/{eid}", s.authed(s.handleEmojiDelete))
	mux.Handle("GET /api/v1/stickers/packs", s.authed(s.handlePacks))
	mux.Handle("POST /api/v1/stickers/packs", s.stepUpUser(s.stepBuild, s.handlePackCreate))
	mux.Handle("GET /api/v1/stickers/recent", s.authed(s.handleRecents))
	mux.Handle("POST /api/v1/stickers/recent", s.authed(s.handleRecentTouch))
	mux.Handle("DELETE /api/v1/stickers/recent", s.authed(s.handleRecentClear))
	mux.Handle("GET /api/v1/stickers/discover", s.userLimited(s.limSearch, s.handlePacksDiscover))
	mux.Handle("GET /api/v1/stickers/packs/public", s.authed(s.handlePacksPublic))
	mux.Handle("GET /api/v1/stickers/packs/{id}", s.authed(s.handlePackGet))
	mux.Handle("PATCH /api/v1/stickers/packs/{id}", s.authed(s.handlePackPatch))
	mux.Handle("DELETE /api/v1/stickers/packs/{id}", s.authed(s.handlePackDelete))
	mux.Handle("POST /api/v1/stickers/packs/{id}/stickers", s.userLimited(s.limExpr, s.handleStickerCreate))
	mux.Handle("PATCH /api/v1/stickers/packs/{id}/stickers", s.authed(s.handleStickerPositions))
	mux.Handle("PATCH /api/v1/stickers/packs/{id}/stickers/{sid}", s.authed(s.handleStickerPatch))
	mux.Handle("DELETE /api/v1/stickers/packs/{id}/stickers/{sid}", s.authed(s.handleStickerDelete))
	mux.Handle("GET /api/v1/stickers/{sid}", s.authed(s.handleStickerInfo))
	mux.Handle("PUT /api/v1/stickers/packs/{id}/install", s.authed(s.handlePackInstall(true)))
	mux.Handle("DELETE /api/v1/stickers/packs/{id}/install", s.authed(s.handlePackInstall(false)))
	mux.Handle("POST /api/v1/stickers/packs/import/telegram", s.userLimited(s.limImport, s.handleTelegramImport))
	mux.Handle("GET /api/v1/gif/status", s.authed(s.handleGifStatus))
	mux.Handle("GET /api/v1/gif/search", s.userLimited(s.limSearch, s.handleGifSearch))
	mux.Handle("GET /api/v1/gif/trending", s.userLimited(s.limSearch, s.handleGifTrending))
}

// --- recent stickers ------------------------------------------------------

// handleRecents lists the caller's last-used stickers, newest first, for the
// picker's recent row. It is per account, so every device offers the same row.
func (s *Server) handleRecents(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.RecentStickers(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

// handleRecentTouch records that the caller sent a sticker. The sticker has
// to exist; anything else about it was already checked when it was sent.
func (s *Server) handleRecentTouch(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		StickerID ids.ID `json:"sticker_id"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.StickerID.IsZero() {
		WriteError(w, E(http.StatusBadRequest, "bad_sticker", "Name the sticker just sent."))
		return
	}
	st, err := s.Store.StickerByID(r.Context(), req.StickerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// a sticker from a pack the person cannot see stays unseen: its name,
	// tags and picture would otherwise come back in the recent list
	pack, err := s.Store.PackByID(r.Context(), st.PackID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if view, _, err := s.packRights(r.Context(), pack, me.ID); err != nil || !view {
		WriteError(w, ErrNotFound)
		return
	}
	if err := s.Store.TouchRecentSticker(r.Context(), me.ID, st.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "sticker_recent", map[string]any{"sticker": st})
	w.WriteHeader(http.StatusNoContent)
}

// handleRecentClear empties the caller's recent row on all their devices.
func (s *Server) handleRecentClear(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if err := s.Store.ClearRecentStickers(r.Context(), me.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "sticker_recent_clear", map[string]any{})
	w.WriteHeader(http.StatusNoContent)
}

// --- server emoji --------------------------------------------------------

func (s *Server) emitEmojis(ctx context.Context, serverID ids.ID) {
	list, err := s.Store.ServerEmojis(ctx, serverID)
	if err != nil {
		return
	}
	s.emitServer(ctx, serverID, "emoji_update", map[string]any{"server_id": serverID, "emojis": list})
}

func (s *Server) handleEmojis(w http.ResponseWriter, r *http.Request) {
	srv, _, _ := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	list, err := s.Store.ServerEmojis(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

func (s *Server) handleEmojiCreate(w http.ResponseWriter, r *http.Request) {
	srv, me, p := s.serverCtx(w, r, 0)
	if srv == nil {
		return
	}
	// adding is Create Expressions, or Manage Expressions, which covers it
	if !p.Has(perms.ManageEmojis) && !p.Has(perms.CreateExpressions) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot add emoji to this server."))
		return
	}
	name := strings.TrimSpace(r.URL.Query().Get("name"))
	if !reEmojiName.MatchString(name) {
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "Emoji names are 2–32 letters, numbers or underscores."))
		return
	}
	lim := s.limitsFor("emoji")
	r.Body = http.MaxBytesReader(w, r.Body, lim.MaxBytes+1)
	b, err := s.Media.PutPublic(r.Context(), "emoji", r.Body, lim, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	e := &store.Emoji{ServerID: srv.ID, Name: name, BlobID: b.ID, Animated: b.Animated, CreatorID: me.ID}
	if err := s.Store.CreateEmoji(r.Context(), e); err != nil {
		s.releaseBlob(r.Context(), b.ID)
		if errors.Is(err, store.ErrAlreadyExists) {
			WriteError(w, E(http.StatusConflict, "name_taken", "An emoji with that name already exists here."))
			return
		}
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "emoji_create", "emoji", &e.ID, map[string]any{"name": name}, "")
	s.emitEmojis(r.Context(), srv.ID)
	WriteJSON(w, http.StatusCreated, e)
}

// emojiTarget loads an emoji for someone who may change it: anyone with
// Manage Expressions, or whoever added it with Create Expressions.
func (s *Server) emojiTarget(w http.ResponseWriter, r *http.Request) (*store.Server, *store.User, *store.Emoji) {
	srv, me, p := s.serverCtx(w, r, 0)
	if srv == nil {
		return nil, nil, nil
	}
	id, err := ids.Parse(r.PathValue("eid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil, nil
	}
	e, err := s.Store.EmojiByID(r.Context(), id)
	if err != nil || e.ServerID != srv.ID {
		WriteError(w, ErrNotFound)
		return nil, nil, nil
	}
	if !p.Has(perms.ManageEmojis) && !(p.Has(perms.CreateExpressions) && e.CreatorID == me.ID) {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You can change the emoji you added, or any with Manage Expressions."))
		return nil, nil, nil
	}
	return srv, me, e
}

func (s *Server) handleEmojiPatch(w http.ResponseWriter, r *http.Request) {
	srv, me, e := s.emojiTarget(w, r)
	if srv == nil {
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	name := strings.TrimSpace(req.Name)
	if !reEmojiName.MatchString(name) {
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "Emoji names are 2–32 letters, numbers or underscores."))
		return
	}
	if err := s.Store.RenameEmoji(r.Context(), e.ID, name); err != nil {
		if errors.Is(err, store.ErrAlreadyExists) {
			WriteError(w, E(http.StatusConflict, "name_taken", "An emoji with that name already exists here."))
			return
		}
		WriteError(w, err)
		return
	}
	e.Name = name
	s.audit(r, srv.ID, me.ID, "emoji_update", "emoji", &e.ID, map[string]any{"name": name}, "")
	s.emitEmojis(r.Context(), srv.ID)
	WriteJSON(w, http.StatusOK, e)
}

func (s *Server) handleEmojiDelete(w http.ResponseWriter, r *http.Request) {
	srv, me, e := s.emojiTarget(w, r)
	if srv == nil {
		return
	}
	if err := s.Store.DeleteEmoji(r.Context(), e.ID); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "emoji_delete", "emoji", &e.ID, map[string]any{"name": e.Name}, "")
	s.emitEmojis(r.Context(), srv.ID)
	w.WriteHeader(http.StatusNoContent)
}

// --- sticker packs -------------------------------------------------------

// packRights resolves what the caller may do with a pack.
//
// The error is kept apart from the answer. Dropping it meant a read that
// merely failed came back as "you cannot see this pack", which packTarget
// answers with a 404 — and a client told a pack does not exist has every
// reason to drop it from its collection for good. A 500 is honest and the
// client tries again.
func (s *Server) packRights(ctx context.Context, p *store.StickerPack, me ids.ID) (view, manage bool, err error) {
	if p.OwnerUserID != nil && *p.OwnerUserID == me {
		return true, true, nil
	}
	if p.ServerID != nil {
		perm, err := s.Store.ServerPerms(ctx, *p.ServerID, me)
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			return false, false, err
		}
		if err == nil && perm != 0 {
			return true, perm.Has(perms.ManageEmojis), nil
		}
	}
	if p.Public {
		return true, false, nil
	}
	installed, err := s.Store.Installed(ctx, me, p.ID)
	if err != nil {
		return false, false, err
	}
	return installed, false, nil
}

// notifyPack tells everyone who can see a pack that it changed.
func (s *Server) notifyPack(ctx context.Context, p *store.StickerPack, deleted bool) {
	s.emitPack(s.packTargets(ctx, p), p, deleted)
}

// packTargets lists everyone who can see a pack: its owner, the members of
// its server and whoever installed it.
func (s *Server) packTargets(ctx context.Context, p *store.StickerPack) []ids.ID {
	targets := map[ids.ID]bool{}
	if p.OwnerUserID != nil {
		targets[*p.OwnerUserID] = true
	}
	if p.ServerID != nil {
		if members, err := s.Store.MemberIDs(ctx, *p.ServerID); err == nil {
			for _, id := range members {
				targets[id] = true
			}
		}
	}
	if installers, err := s.Store.Installers(ctx, p.ID); err == nil {
		for _, id := range installers {
			targets[id] = true
		}
	}
	list := make([]ids.ID, 0, len(targets))
	for id := range targets {
		list = append(list, id)
	}
	return list
}

func (s *Server) emitPack(targets []ids.ID, p *store.StickerPack, deleted bool) {
	payload := map[string]any{"pack_id": p.ID, "deleted": deleted}
	if !deleted {
		payload["pack"] = p
	}
	s.Bcast.EmitUsers(targets, "sticker_update", payload)
}

func (s *Server) handlePacks(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	list, err := s.Store.PacksForUser(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

func (s *Server) handlePacksPublic(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
	list, err := s.Store.PublicPacks(r.Context(), me.ID, r.URL.Query().Get("q"), limit)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, list)
}

// handlePacksDiscover is Discovery's pack sections: a page of public packs of
// one kind, each with a preview of its stickers, its counts and who made it.
func (s *Server) handlePacksDiscover(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	q := r.URL.Query()
	offset, _ := strconv.Atoi(q.Get("offset"))
	limit, _ := strconv.Atoi(q.Get("limit"))
	packs, more, err := s.Store.DiscoverPacks(r.Context(), me.ID, store.DiscoverQuery{Q: q.Get("q"), Kind: q.Get("kind"), Sort: q.Get("sort"), Offset: offset, Limit: limit})
	if err != nil {
		WriteError(w, err)
		return
	}
	s.fillPackOwners(r.Context(), packs)
	out := map[string]any{"packs": packs, "has_more": more}
	if more {
		out["next_offset"] = max(offset, 0) + len(packs)
	}
	WriteJSON(w, http.StatusOK, out)
}

// fillPackOwners says who made each pack. One that cannot be found stays
// without a name; the rest of the answer is still good.
func (s *Server) fillPackOwners(ctx context.Context, packs []*store.StickerPack) {
	want := make([]ids.ID, 0, len(packs))
	for _, p := range packs {
		if p.OwnerUserID != nil {
			want = append(want, *p.OwnerUserID)
		}
	}
	if len(want) == 0 {
		return
	}
	byID, err := s.Store.UsersByIDs(ctx, want)
	if err != nil {
		return
	}
	for _, p := range packs {
		if p.OwnerUserID == nil {
			continue
		}
		if u := byID[*p.OwnerUserID]; u != nil {
			public := u.Public()
			p.Owner = &public
		}
	}
}

func cleanPackFields(name, desc string) (string, string, *Error) {
	n, ok := CleanText(strings.Join(strings.Fields(name), " "), maxPackName)
	if !ok || n == "" {
		return "", "", E(http.StatusBadRequest, "invalid_name", "Pack names are 1–100 characters.")
	}
	d, ok := CleanText(desc, maxPackDesc)
	if !ok {
		return "", "", E(http.StatusBadRequest, "invalid_description", "Descriptions are at most 500 characters.")
	}
	return n, d, nil
}

func (s *Server) handlePackCreate(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	var req struct {
		Name        string  `json:"name"`
		Description string  `json:"description"`
		ServerID    *ids.ID `json:"server_id"`
		Kind        string  `json:"kind"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	name, desc, apiErr := cleanPackFields(req.Name, req.Description)
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	if req.Kind != "emoji" {
		req.Kind = "sticker"
	}
	p := &store.StickerPack{Name: name, Description: desc, Kind: req.Kind}
	if req.ServerID != nil {
		perm, err := s.Store.ServerPerms(r.Context(), *req.ServerID, me.ID)
		if err != nil || perm == 0 {
			WriteError(w, ErrNotFound)
			return
		}
		if !perm.Has(perms.ManageEmojis) {
			WriteError(w, E(http.StatusForbidden, "missing_permission", "You need Manage Expressions to create server packs."))
			return
		}
		p.ServerID = req.ServerID
	} else {
		uid := me.ID
		p.OwnerUserID = &uid
	}
	if err := s.Store.CreatePack(r.Context(), p); err != nil {
		WriteError(w, err)
		return
	}
	if p.ServerID != nil {
		s.audit(r, *p.ServerID, me.ID, "sticker_pack_create", "sticker_pack", &p.ID, map[string]any{"name": name}, "")
	}
	s.notifyPack(r.Context(), p, false)
	WriteJSON(w, http.StatusCreated, p)
}

func (s *Server) packTarget(w http.ResponseWriter, r *http.Request, needManage bool) (*store.StickerPack, *store.User) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	p, err := s.Store.PackByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	view, manage, err := s.packRights(r.Context(), p, me.ID)
	if err != nil {
		WriteError(w, err)
		return nil, nil
	}
	if !view {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	if needManage && !manage {
		WriteError(w, E(http.StatusForbidden, "missing_permission", "You cannot edit this pack."))
		return nil, nil
	}
	return p, me
}

func (s *Server) handlePackGet(w http.ResponseWriter, r *http.Request) {
	p, me := s.packTarget(w, r, false)
	if p == nil {
		return
	}
	installed, err := s.Store.Installed(r.Context(), me.ID, p.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	p.Installed = installed
	// who made it and how many have it: what someone looking at the pack would ask
	s.fillPackOwners(r.Context(), []*store.StickerPack{p})
	if counts, err := s.Store.PackInstallCounts(r.Context(), []ids.ID{p.ID}); err == nil {
		n := counts[p.ID]
		p.InstallCount = &n
	}
	WriteJSON(w, http.StatusOK, p)
}

// handleStickerInfo tells someone who saw a sticker in a message where it
// comes from. Only a public pack is shown, with every sticker in it and who
// made them. A sticker from any other pack stays the picture and name its
// message already carries, so it is answered as not found.
func (s *Server) handleStickerInfo(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	sid, err := ids.Parse(r.PathValue("sid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	st, err := s.Store.StickerByID(r.Context(), sid)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	p, err := s.Store.PackByID(r.Context(), st.PackID)
	if err != nil || !p.Public {
		WriteError(w, ErrNotFound)
		return
	}
	installed, err := s.Store.Installed(r.Context(), me.ID, p.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	p.Installed = installed
	want := map[ids.ID]bool{st.CreatorID: true}
	if p.OwnerUserID != nil {
		want[*p.OwnerUserID] = true
	}
	for _, x := range p.Stickers {
		want[x.CreatorID] = true
	}
	list := make([]ids.ID, 0, len(want))
	for id := range want {
		list = append(list, id)
	}
	users := []store.PublicUser{}
	if byID, err := s.Store.UsersByIDs(r.Context(), list); err == nil {
		for _, u := range byID {
			users = append(users, u.Public())
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"sticker": st, "pack": p, "users": users})
}

func (s *Server) handlePackPatch(w http.ResponseWriter, r *http.Request) {
	p, me := s.packTarget(w, r, true)
	if p == nil {
		return
	}
	var raw map[string]json.RawMessage
	if err := ReadJSON(w, r, &raw, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	var patch store.StickerPackPatch
	if v, ok := raw["name"]; ok {
		var name string
		if json.Unmarshal(v, &name) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Pack names are 1–100 characters."))
			return
		}
		clean, _, apiErr := cleanPackFields(name, "")
		if apiErr != nil {
			WriteError(w, apiErr)
			return
		}
		patch.Name = &clean
	}
	if v, ok := raw["description"]; ok {
		var d string
		if string(v) != "null" && json.Unmarshal(v, &d) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_description", "Description is malformed."))
			return
		}
		_, clean, apiErr := cleanPackFields("x", d)
		if apiErr != nil {
			WriteError(w, apiErr)
			return
		}
		patch.Description = &clean
	}
	if v, ok := raw["public"]; ok {
		var pub bool
		if json.Unmarshal(v, &pub) != nil {
			WriteError(w, E(http.StatusBadRequest, "invalid_value", "public must be true or false."))
			return
		}
		// a pack the staff took out of Discover stays out until they let it back
		if pub && !p.Public {
			blocked, err := s.Store.PackDiscoverBlocked(r.Context(), p.ID)
			if err != nil {
				WriteError(w, err)
				return
			}
			if blocked {
				WriteError(w, E(http.StatusForbidden, "discover_blocked", "Nexa took this pack out of Discover, so it can't be made public. It still works for you and everyone who installed it."))
				return
			}
			// a public pack is listed for everyone to find: a captcha away, as Discovery is
			if !s.requireCaptcha(w, r, "pack_publish") {
				return
			}
		}
		patch.Public = &pub
	}
	if v, ok := raw["cover_sticker_id"]; ok {
		if string(v) == "null" {
			var nilID *ids.ID
			patch.CoverStickerID = &nilID
		} else {
			var id ids.ID
			if json.Unmarshal(v, &id) != nil {
				WriteError(w, E(http.StatusBadRequest, "invalid_sticker", "cover_sticker_id is malformed."))
				return
			}
			found := false
			for _, st := range p.Stickers {
				if st.ID == id {
					found = true
				}
			}
			if !found {
				WriteError(w, E(http.StatusBadRequest, "invalid_sticker", "The cover must be a sticker of this pack."))
				return
			}
			ptr := &id
			patch.CoverStickerID = &ptr
		}
	}
	if err := s.Store.UpdatePack(r.Context(), p.ID, patch); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.PackByID(r.Context(), p.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if p.ServerID != nil {
		s.audit(r, *p.ServerID, me.ID, "sticker_pack_update", "sticker_pack", &p.ID, nil, "")
	}
	s.notifyPack(r.Context(), updated, false)
	WriteJSON(w, http.StatusOK, updated)
}

func (s *Server) handlePackDelete(w http.ResponseWriter, r *http.Request) {
	p, me := s.packTarget(w, r, true)
	if p == nil {
		return
	}
	// installs go with the pack, so whoever had it installed is found first
	targets := s.packTargets(r.Context(), p)
	if err := s.Store.DeletePack(r.Context(), p.ID); err != nil {
		WriteError(w, err)
		return
	}
	if p.ServerID != nil {
		s.audit(r, *p.ServerID, me.ID, "sticker_pack_delete", "sticker_pack", &p.ID, map[string]any{"name": p.Name}, "")
	}
	s.emitPack(targets, p, true)
	w.WriteHeader(http.StatusNoContent)
}

func stickerFormat(mime string, animated bool) string {
	switch mime {
	case "image/png":
		if animated {
			return "apng"
		}
		return "png"
	case "image/gif":
		return "gif"
	case "image/webp":
		return "webp"
	case "application/x-tgsticker":
		return "tgs"
	case "video/webm":
		return "webm"
	}
	return ""
}

func (s *Server) handleStickerCreate(w http.ResponseWriter, r *http.Request) {
	p, me := s.packTarget(w, r, true)
	if p == nil {
		return
	}
	name, ok := CleanText(strings.Join(strings.Fields(r.URL.Query().Get("name")), " "), maxStickerName)
	if !ok || name == "" {
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "Sticker names are 1–30 characters."))
		return
	}
	tags, ok := CleanText(strings.Join(strings.Fields(r.URL.Query().Get("tags")), " "), maxStickerTags)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "invalid_tags", "Tags are at most 200 characters."))
		return
	}
	kind := "sticker"
	if p.Kind == "emoji" {
		kind = "emoji"
	}
	lim := s.limitsFor(kind)
	r.Body = http.MaxBytesReader(w, r.Body, lim.MaxBytes+1)
	b, err := s.Media.PutPublic(r.Context(), kind, r.Body, lim, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	format := stickerFormat(b.Mime, b.Animated)
	if format == "" && kind == "emoji" && b.Mime == "image/jpeg" {
		format = "jpeg"
	}
	if kind == "emoji" && (format == "tgs" || format == "webm") {
		s.releaseBlob(r.Context(), b.ID)
		WriteError(w, E(http.StatusBadRequest, "unsupported_format", "Images must be PNG, APNG, GIF or WebP."))
		return
	}
	if format == "" {
		s.releaseBlob(r.Context(), b.ID)
		if kind == "sticker" {
			WriteError(w, E(http.StatusBadRequest, "unsupported_format", "Stickers must be PNG, APNG, GIF, WebP, TGS or WebM video."))
			return
		}
		WriteError(w, E(http.StatusBadRequest, "unsupported_format", "Images must be PNG, APNG, GIF or WebP."))
		return
	}
	if kind == "emoji" && !reEmojiName.MatchString(strings.ReplaceAll(name, " ", "_")) {
		s.releaseBlob(r.Context(), b.ID)
		WriteError(w, E(http.StatusBadRequest, "invalid_name", "Emoji names are 2–32 letters, numbers or underscores."))
		return
	}
	if kind == "emoji" {
		name = strings.ReplaceAll(name, " ", "_")
	}
	st := &store.Sticker{PackID: p.ID, Name: name, Tags: tags, BlobID: b.ID, Format: format, CreatorID: me.ID, Width: b.Width, Height: b.Height, Animated: b.Animated}
	if err := s.Store.AddSticker(r.Context(), st); err != nil {
		s.releaseBlob(r.Context(), b.ID)
		WriteError(w, err)
		return
	}
	if p.CoverStickerID == nil {
		ptr := &st.ID
		_ = s.Store.UpdatePack(r.Context(), p.ID, store.StickerPackPatch{CoverStickerID: &ptr})
	}
	if updated, err := s.Store.PackByID(r.Context(), p.ID); err == nil {
		s.notifyPack(r.Context(), updated, false)
	}
	WriteJSON(w, http.StatusCreated, st)
}

func (s *Server) stickerTarget(w http.ResponseWriter, r *http.Request) (*store.StickerPack, *store.Sticker) {
	p, _ := s.packTarget(w, r, true)
	if p == nil {
		return nil, nil
	}
	id, err := ids.Parse(r.PathValue("sid"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil, nil
	}
	for _, st := range p.Stickers {
		if st.ID == id {
			return p, st
		}
	}
	WriteError(w, ErrNotFound)
	return nil, nil
}

func (s *Server) handleStickerPatch(w http.ResponseWriter, r *http.Request) {
	p, st := s.stickerTarget(w, r)
	if p == nil {
		return
	}
	var req struct {
		Name *string `json:"name"`
		Tags *string `json:"tags"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	if req.Name != nil {
		clean, ok := CleanText(strings.Join(strings.Fields(*req.Name), " "), maxStickerName)
		if !ok || clean == "" {
			WriteError(w, E(http.StatusBadRequest, "invalid_name", "Sticker names are 1–30 characters."))
			return
		}
		req.Name = &clean
	}
	if req.Tags != nil {
		clean, ok := CleanText(strings.Join(strings.Fields(*req.Tags), " "), maxStickerTags)
		if !ok {
			WriteError(w, E(http.StatusBadRequest, "invalid_tags", "Tags are at most 200 characters."))
			return
		}
		req.Tags = &clean
	}
	if err := s.Store.UpdateSticker(r.Context(), st.ID, req.Name, req.Tags); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.PackByID(r.Context(), p.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.notifyPack(r.Context(), updated, false)
	for _, x := range updated.Stickers {
		if x.ID == st.ID {
			WriteJSON(w, http.StatusOK, x)
			return
		}
	}
	WriteJSON(w, http.StatusOK, st)
}

func (s *Server) handleStickerDelete(w http.ResponseWriter, r *http.Request) {
	p, st := s.stickerTarget(w, r)
	if p == nil {
		return
	}
	if err := s.Store.DeleteSticker(r.Context(), st.ID); err != nil {
		WriteError(w, err)
		return
	}
	if updated, err := s.Store.PackByID(r.Context(), p.ID); err == nil {
		s.notifyPack(r.Context(), updated, false)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleStickerPositions(w http.ResponseWriter, r *http.Request) {
	p, _ := s.packTarget(w, r, true)
	if p == nil {
		return
	}
	var req []struct {
		ID       ids.ID `json:"id"`
		Position int    `json:"position"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	positions := map[ids.ID]int{}
	for _, item := range req {
		positions[item.ID] = item.Position
	}
	if err := s.Store.SetStickerPositions(r.Context(), p.ID, positions); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.PackByID(r.Context(), p.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.notifyPack(r.Context(), updated, false)
	WriteJSON(w, http.StatusOK, updated)
}

func (s *Server) handlePackInstall(install bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		me := auth.UserFrom(r.Context())
		id, err := ids.Parse(r.PathValue("id"))
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		p, err := s.Store.PackByID(r.Context(), id)
		if err != nil {
			WriteError(w, ErrNotFound)
			return
		}
		if install {
			view, _, err := s.packRights(r.Context(), p, me.ID)
			if err != nil {
				WriteError(w, err)
				return
			}
			if !view || !p.Public {
				WriteError(w, E(http.StatusForbidden, "not_public", "Only public packs can be installed."))
				return
			}
			if err := s.Store.InstallPack(r.Context(), me.ID, p.ID); err != nil {
				WriteError(w, err)
				return
			}
			p.Installed = true
			s.Bcast.EmitUsers([]ids.ID{me.ID}, "sticker_update", map[string]any{"pack_id": p.ID, "pack": p, "deleted": false})
			WriteJSON(w, http.StatusOK, p)
			return
		}
		if err := s.Store.UninstallPack(r.Context(), me.ID, p.ID); err != nil {
			WriteError(w, err)
			return
		}
		// The pack leaves your list unless it is still there for another reason:
		// you made it, or it belongs to a server you are in.
		stays := p.OwnerUserID != nil && *p.OwnerUserID == me.ID
		if !stays && p.ServerID != nil {
			stays, _ = s.Store.IsMember(r.Context(), *p.ServerID, me.ID)
		}
		if stays {
			p.Installed = false
			s.Bcast.EmitUsers([]ids.ID{me.ID}, "sticker_update", map[string]any{"pack_id": p.ID, "pack": p, "deleted": false})
		} else {
			s.Bcast.EmitUsers([]ids.ID{me.ID}, "sticker_update", map[string]any{"pack_id": p.ID, "deleted": true})
		}
		w.WriteHeader(http.StatusNoContent)
	}
}

// --- GIF search (Klipy) ----------------------------------------------------
//
// The pictures themselves are rewritten through this server's proxy, so
// opening the picker never hands the reader's address to whoever hosts them.

type gifResult struct {
	ID      string `json:"id"`
	URL     string `json:"url"`
	Preview string `json:"preview"`
	W       int    `json:"w"`
	H       int    `json:"h"`
	Title   string `json:"title"`
}

type gifPage struct {
	Results []gifResult `json:"results"`
	Next    string      `json:"next"`
}

var gifCache = struct {
	sync.Mutex
	m map[string]struct {
		at   time.Time
		page gifPage
	}
}{m: map[string]struct {
	at   time.Time
	page gifPage
}{}}

var gifClient = &http.Client{Timeout: 8 * time.Second}

func (s *Server) gifEnabled() bool { return s.Cfg.KlipyAPIKey != "" }

func (s *Server) handleGifStatus(w http.ResponseWriter, r *http.Request) {
	WriteJSON(w, http.StatusOK, map[string]any{"enabled": s.gifEnabled()})
}

func (s *Server) handleGifSearch(w http.ResponseWriter, r *http.Request) {
	s.gifProxy(w, r, strings.TrimSpace(r.URL.Query().Get("q")))
}

func (s *Server) handleGifTrending(w http.ResponseWriter, r *http.Request) {
	s.gifProxy(w, r, "")
}

// gifProxy answers a search, or trending when the query is empty. `pos` is
// whatever the provider called the next page last time.
func (s *Server) gifProxy(w http.ResponseWriter, r *http.Request, query string) {
	if !s.gifEnabled() {
		WriteError(w, E(http.StatusNotFound, "gif_disabled", "GIF search is not configured on this server."))
		return
	}
	pos := r.URL.Query().Get("pos")
	cacheKey := query + "|" + pos
	gifCache.Lock()
	if hit, ok := gifCache.m[cacheKey]; ok && time.Since(hit.at) < 2*time.Minute {
		gifCache.Unlock()
		WriteJSON(w, http.StatusOK, hit.page)
		return
	}
	gifCache.Unlock()

	page, err := s.klipyPage(r, query, pos)
	if err != nil {
		WriteError(w, err)
		return
	}
	gifCache.Lock()
	if len(gifCache.m) > 500 {
		gifCache.m = map[string]struct {
			at   time.Time
			page gifPage
		}{}
	}
	gifCache.m[cacheKey] = struct {
		at   time.Time
		page gifPage
	}{time.Now(), page}
	gifCache.Unlock()
	WriteJSON(w, http.StatusOK, page)
}

var errGifUpstream = E(http.StatusBadGateway, "gif_unavailable", "GIF search is unavailable right now.")

// fetchGif performs the upstream request and returns the body.
func fetchGif(r *http.Request, url string) ([]byte, error) {
	req, err := http.NewRequestWithContext(r.Context(), "GET", url, nil)
	if err != nil {
		return nil, err
	}
	res, err := gifClient.Do(req)
	if err != nil {
		return nil, errGifUpstream
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	if res.StatusCode != http.StatusOK {
		return nil, errGifUpstream
	}
	return body, nil
}

// klipyFile is one rendition of one GIF.
type klipyFile struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	Height int    `json:"height"`
	Size   int64  `json:"size"`
}

// pick returns the first rendition present, in the order asked for.
func pickKlipy(sizes map[string]map[string]klipyFile, order []string, format string) klipyFile {
	for _, size := range order {
		if f, ok := sizes[size][format]; ok && f.URL != "" {
			return f
		}
	}
	return klipyFile{}
}

// gifToSend picks the file a GIF is sent as: the first GIF, from medium down,
// small enough for the media proxy to pass, since one it refuses shows as a
// broken picture to everyone (a medium GIF can be over 50 MB). Failing that, an
// animated WebP, which is a fraction of the size, then the medium GIF anyway.
// A result with no GIF at all is not offered.
func gifToSend(sizes map[string]map[string]klipyFile) klipyFile {
	order := []string{"md", "hd", "sm", "xs"}
	gif := pickKlipy(sizes, order, "gif")
	if gif.URL == "" {
		return gif
	}
	for _, format := range []string{"gif", "webp"} {
		for _, size := range order {
			if f, ok := sizes[size][format]; ok && f.URL != "" && f.Size <= unfurl.MaxMediaBytes {
				return f
			}
		}
	}
	return gif
}

// gifPreviewWidth is how many pixels across a picker preview needs to stay
// sharp: a cell is a little over 200 CSS pixels wide, doubled on most phones.
const gifPreviewWidth = 400

// gifPreview picks the moving picture shown in the picker. Sharpness comes
// first: the smallest rendition at least gifPreviewWidth across, WebP before
// GIF since it moves the same at a fraction of the size. Only when nothing is
// that wide does it settle for the widest there is.
func gifPreview(sizes map[string]map[string]klipyFile) klipyFile {
	formats := []string{"webp", "gif"}
	for _, format := range formats {
		var best klipyFile
		for _, byFormat := range sizes {
			if f, ok := byFormat[format]; ok && f.URL != "" && f.Width >= gifPreviewWidth && (best.URL == "" || f.Width < best.Width) {
				best = f
			}
		}
		if best.URL != "" {
			return best
		}
	}
	for _, format := range formats {
		var best klipyFile
		for _, byFormat := range sizes {
			if f, ok := byFormat[format]; ok && f.URL != "" && (best.URL == "" || f.Width > best.Width) {
				best = f
			}
		}
		if best.URL != "" {
			return best
		}
	}
	return klipyFile{}
}

func (s *Server) klipyPage(r *http.Request, query, pos string) (gifPage, error) {
	page := 1
	if pos != "" {
		if n, err := strconv.Atoi(pos); err == nil && n > 1 {
			page = n
		}
	}
	params := url.Values{}
	params.Set("per_page", "30")
	params.Set("page", strconv.Itoa(page))
	params.Set("content_filter", "medium")
	endpoint := "trending"
	if query != "" {
		endpoint = "search"
		params.Set("q", query)
	}
	body, err := fetchGif(r, "https://api.klipy.com/api/v1/"+url.PathEscape(s.Cfg.KlipyAPIKey)+"/gifs/"+endpoint+"?"+params.Encode())
	if err != nil {
		return gifPage{}, err
	}
	var parsed struct {
		Data struct {
			CurrentPage int  `json:"current_page"`
			HasNext     bool `json:"has_next"`
			Data        []struct {
				Slug  string                          `json:"slug"`
				Title string                          `json:"title"`
				File  map[string]map[string]klipyFile `json:"file"`
			} `json:"data"`
		} `json:"data"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return gifPage{}, E(http.StatusBadGateway, "gif_unavailable", "GIF search returned something unexpected.")
	}
	out := gifPage{Results: []gifResult{}}
	if parsed.Data.HasNext {
		out.Next = strconv.Itoa(parsed.Data.CurrentPage + 1)
	}
	for _, item := range parsed.Data.Data {
		full := gifToSend(item.File)
		if full.URL == "" {
			continue
		}
		preview := gifPreview(item.File)
		if preview.URL == "" {
			preview = full
		}
		out.Results = append(out.Results, gifResult{
			ID:      item.Slug,
			URL:     s.ProxyURL(full.URL),
			Preview: s.ProxyURL(preview.URL),
			W:       full.Width,
			H:       full.Height,
			Title:   item.Title,
		})
	}
	return out, nil
}
