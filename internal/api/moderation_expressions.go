package api

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// Stickers and emoji in the moderation panel. The staff see every pack on
// Nexa (anyone's, any server's, public or not), every server's emoji and the
// newest uploads, all of which are pictures anyone can be shown, and take
// down what breaks the rules: single stickers or emoji, a whole pack, a
// pack's name or description, or its public listing. A picture taken down is
// blocked by its content, so no copy of it is served and nobody can upload it
// again. Each takedown opens a case for whoever answers for it: the pack's
// owner, or the server's owner for a server's pack or emoji. They read why
// in their Nexa conversation and can answer there.

func (s *Server) modExpressionRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/moderation/packs", s.staff(rankMod, s.handleModPacks))
	mux.Handle("GET /api/v1/moderation/packs/{id}", s.staff(rankMod, s.handleModPack))
	mux.Handle("POST /api/v1/moderation/packs/{id}/actions", s.staff(rankMod, s.handleModPackAction))
	mux.Handle("POST /api/v1/moderation/packs/{id}/relist", s.staff(rankAdmin, s.handleModPackRelist))
	mux.Handle("POST /api/v1/moderation/servers/{id}/emojis/actions", s.staff(rankMod, s.handleModEmojiAction))
	mux.Handle("GET /api/v1/moderation/uploads", s.staff(rankMod, s.handleModUploads))
}

// packPreview is how many of a pack's stickers come with it in a list.
const packPreview = 4

// modPackView is a pack in the panel, and whether this moderator may act on it.
type modPackView struct {
	*store.ModPack
	// OwnerID is who answers for the pack: its owner, or its server's.
	OwnerID ids.ID `json:"owner_id"`
	// Blocked is why the viewer cannot act on it ("" when they can).
	Blocked string `json:"blocked"`
}

// modServerRef names a server a pack or an emoji belongs to.
type modServerRef struct {
	ID      ids.ID  `json:"id"`
	Name    string  `json:"name"`
	IconID  *ids.ID `json:"icon_id"`
	OwnerID ids.ID  `json:"owner_id"`
}

// packOwner is who answers for a pack, and its server when it is one's.
func (s *Server) packOwner(ctx context.Context, p *store.ModPack) (*store.User, *store.Server) {
	if p.OwnerUserID != nil {
		u, err := s.Store.UserByID(ctx, *p.OwnerUserID)
		if err != nil {
			return nil, nil
		}
		return u, nil
	}
	if p.ServerID != nil {
		srv, err := s.Store.ServerByID(ctx, *p.ServerID)
		if err != nil {
			return nil, nil
		}
		u, err := s.Store.UserByID(ctx, srv.OwnerID)
		if err != nil {
			return nil, srv
		}
		return u, srv
	}
	return nil, nil
}

// modPackViews dresses packs for the panel, with the people and servers they name.
func (s *Server) modPackViews(r *http.Request, list []*store.ModPack) ([]modPackView, map[string]person, map[string]modServerRef) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	out := make([]modPackView, 0, len(list))
	who := []ids.ID{}
	servers := map[string]modServerRef{}
	for _, p := range list {
		v := modPackView{ModPack: p}
		owner, srv := s.packOwner(ctx, p)
		if owner != nil {
			v.OwnerID = owner.ID
			v.Blocked = s.moderationBlock(ctx, me, owner)
			who = append(who, owner.ID)
		}
		if srv != nil {
			servers[srv.ID.String()] = modServerRef{ID: srv.ID, Name: srv.Name, IconID: srv.IconID, OwnerID: srv.OwnerID}
		}
		out = append(out, v)
	}
	return out, s.people(ctx, who...), servers
}

func (s *Server) handleModPacks(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 120 {
		limit = 60
	}
	offset, _ := strconv.Atoi(q.Get("offset"))
	sort := q.Get("sort")
	if !OneOf(sort, "newest", "oldest", "installs", "name") {
		sort = "newest"
	}
	scope := q.Get("scope")
	if !OneOf(scope, "all", "discover", "personal", "server", "hidden") {
		scope = "all"
	}
	list, err := s.Store.ModPacks(ctx, store.ModPackFilter{Query: q.Get("q"), Scope: scope, Sort: sort, Offset: offset, Limit: limit + 1, Preview: packPreview})
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > limit
	if more {
		list = list[:limit]
	}
	packs, people, servers := s.modPackViews(r, list)
	WriteJSON(w, http.StatusOK, map[string]any{"packs": packs, "people": people, "servers": servers, "has_more": more})
}

// modPackFor loads the pack a staff route names.
func (s *Server) modPackFor(w http.ResponseWriter, r *http.Request) *store.ModPack {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	p, err := s.Store.ModPackByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	return p
}

// handleModPack is one pack, every sticker in it, and the cases about it.
func (s *Server) handleModPack(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	p := s.modPackFor(w, r)
	if p == nil {
		return
	}
	views, people, servers := s.modPackViews(r, []*store.ModPack{p})
	view := views[0]
	cases := []staffCase{}
	if !view.OwnerID.IsZero() {
		var err error
		if cases, err = s.expressionCases(r, view.OwnerID, func(d expressionDetails) bool { return d.PackID == p.ID }); err != nil {
			WriteError(w, err)
			return
		}
		who := []ids.ID{view.OwnerID}
		for _, c := range cases {
			who = append(who, c.ModeratorID)
		}
		people = s.people(ctx, who...)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"pack": view, "cases": cases, "people": people, "servers": servers})
}

// expressionDetails is what an expression_takedown case records.
type expressionDetails struct {
	PackID     ids.ID         `json:"pack_id,omitempty"`
	PackName   string         `json:"pack_name,omitempty"`
	PackKind   string         `json:"pack_kind,omitempty"`
	ServerID   ids.ID         `json:"server_id,omitempty"`
	ServerName string         `json:"server_name,omitempty"`
	Stickers   []takenDown    `json:"stickers,omitempty"`
	Emojis     []takenDown    `json:"emojis,omitempty"`
	Fields     []string       `json:"fields,omitempty"`
	Deleted    bool           `json:"deleted,omitempty"`
	Blocked    bool           `json:"blocked,omitempty"`
	Old        map[string]any `json:"old,omitempty"`
	New        map[string]any `json:"new,omitempty"`
}

// takenDown names one sticker or emoji a case took down.
type takenDown struct {
	ID     ids.ID `json:"id"`
	Name   string `json:"name"`
	BlobID ids.ID `json:"blob_id"`
}

// expressionCases are someone's expression_takedown cases that match.
func (s *Server) expressionCases(r *http.Request, owner ids.ID, match func(expressionDetails) bool) ([]staffCase, error) {
	ctx := r.Context()
	all, err := s.Store.ModCasesForUser(ctx, owner)
	if err != nil {
		return nil, err
	}
	cs := s.caseStateFor(ctx, owner)
	out := []staffCase{}
	for _, c := range all {
		if c.Action != store.ActionExpressionTakedown {
			continue
		}
		var d expressionDetails
		if json.Unmarshal(c.Details, &d) != nil || !match(d) {
			continue
		}
		out = append(out, toStaffCase(c, cs, nil))
	}
	return out, nil
}

// expressionBlockError says why who answers for a pack or an emoji is out of
// this moderator's reach.
func expressionBlockError(code string) *Error {
	switch code {
	case blockSelf:
		return E(http.StatusBadRequest, "moderate_self", "That is yours. Change it in its settings.")
	case blockOwner:
		return E(http.StatusForbidden, "moderate_owner", "Packs and emoji of the owner of Nexa cannot be moderated.")
	case blockStaff:
		return E(http.StatusForbidden, "moderate_staff", "That belongs to staff. Take away their admin access first.")
	}
	return blockError(code)
}

// handleModPackAction takes down stickers in a pack, the whole pack, or its
// name, description or public listing.
func (s *Server) handleModPackAction(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	p := s.modPackFor(w, r)
	if p == nil {
		return
	}
	owner, srv := s.packOwner(ctx, p)
	if owner == nil {
		WriteError(w, ErrNotFound)
		return
	}
	if block := s.moderationBlock(ctx, me, owner); block != "" {
		WriteError(w, expressionBlockError(block))
		return
	}
	var req struct {
		Stickers []ids.ID `json:"stickers"`
		Fields   []string `json:"fields"`
		Delete   bool     `json:"delete"`
		Block    bool     `json:"block"`
		Reason   string   `json:"reason"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	inPack := map[ids.ID]*store.Sticker{}
	for _, st := range p.Stickers {
		inPack[st.ID] = st
	}
	d := expressionDetails{PackID: p.ID, PackName: p.Name, PackKind: p.Kind, Deleted: req.Delete, Old: map[string]any{}, New: map[string]any{}}
	if srv != nil {
		d.ServerID, d.ServerName = srv.ID, srv.Name
	}
	t := store.ModExpressionTakedown{PackID: &p.ID, DeletePack: req.Delete, Block: req.Block}
	if req.Delete {
		// the whole pack: every sticker in it is named on the case
		for _, st := range p.Stickers {
			d.Stickers = append(d.Stickers, takenDown{ID: st.ID, Name: st.Name, BlobID: st.BlobID})
		}
	} else {
		seen := map[ids.ID]bool{}
		for _, id := range req.Stickers {
			st := inPack[id]
			if st == nil {
				WriteError(w, E(http.StatusBadRequest, "not_in_pack", "One of those stickers is not in this pack any more."))
				return
			}
			if !seen[id] {
				seen[id] = true
				t.Stickers = append(t.Stickers, id)
				d.Stickers = append(d.Stickers, takenDown{ID: st.ID, Name: st.Name, BlobID: st.BlobID})
			}
		}
		chosen := map[string]bool{}
		for _, f := range req.Fields {
			if !OneOf(f, "name", "description", "public") {
				WriteError(w, E(http.StatusBadRequest, "unknown_field", "Only a pack's name, description and public listing can be taken back."))
				return
			}
			if chosen[f] {
				continue
			}
			chosen[f] = true
			d.Fields = append(d.Fields, f)
		}
		switch {
		case chosen["description"] && p.Description == "":
			WriteError(w, E(http.StatusBadRequest, "field_empty", "That pack has no description to clear."))
			return
		case chosen["public"] && !p.Public:
			WriteError(w, E(http.StatusBadRequest, "not_public", "That pack is already private."))
			return
		}
		if chosen["name"] {
			name := resetPrefix + randomSuffix()
			t.Name = &name
			d.Old["name"], d.New["name"] = p.Name, name
		}
		if chosen["description"] {
			t.ClearDescription = true
			d.Old["description"], d.New["description"] = clipRunes(p.Description, maxKeptOldValue), ""
		}
		t.Unlist = chosen["public"]
		if len(t.Stickers) == 0 && len(d.Fields) == 0 {
			WriteError(w, E(http.StatusBadRequest, "nothing_selected", "Choose at least one sticker or part of the pack."))
			return
		}
	}
	d.Blocked = req.Block && len(d.Stickers) > 0
	t.Block = d.Blocked
	details := mustJSON(d)
	c := &store.ModCase{UserID: owner.ID, ModeratorID: me.ID, Action: store.ActionExpressionTakedown, Reason: reason, Details: details, TargetUsername: owner.Username, TargetDisplayName: owner.DisplayName}
	log := &store.ModLogEntry{Action: store.ActionExpressionTakedown, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &owner.ID, TargetUsername: owner.Username, Reason: reason, Details: details}
	// everyone who can see the pack is found before a deletion takes its installs with it
	targets := s.packTargets(ctx, &p.StickerPack)
	if err := s.Store.ModTakeDownExpressions(ctx, c, t, log); err != nil {
		if errors.Is(err, store.ErrNotInPack) || errors.Is(err, store.ErrNotFound) {
			WriteError(w, E(http.StatusConflict, "changed", "The pack changed while you were looking at it. Open it again."))
			return
		}
		WriteError(w, err)
		return
	}
	if req.Delete {
		s.emitPack(targets, &p.StickerPack, true)
	} else if updated, err := s.Store.PackByID(ctx, p.ID); err == nil {
		s.emitPack(targets, updated, false)
	}
	s.actionDone(w, ctx, c, log)
}

// handleModPackRelist lets a pack the staff made private be made public again
// by its owner. It is a correction, so the log records it without a case.
func (s *Server) handleModPackRelist(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	p := s.modPackFor(w, r)
	if p == nil {
		return
	}
	if !p.DiscoverBlocked {
		WriteError(w, E(http.StatusBadRequest, "not_blocked", "That pack can already be made public."))
		return
	}
	var req struct {
		Reason string `json:"reason"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	owner, _ := s.packOwner(ctx, p)
	log := &store.ModLogEntry{Action: store.LogExpressionRelist, ModeratorID: me.ID, ModeratorUsername: me.Username, Reason: reason, Details: mustJSON(map[string]any{"pack_id": p.ID, "pack_name": p.Name})}
	if owner != nil {
		log.TargetID, log.TargetUsername = &owner.ID, owner.Username
	}
	if err := s.Store.ModRelistPack(ctx, p.ID, log); err != nil {
		WriteError(w, err)
		return
	}
	s.emitLog(ctx, log)
	w.WriteHeader(http.StatusNoContent)
}

// handleModEmojiAction takes down some of a server's emoji.
func (s *Server) handleModEmojiAction(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	m := s.modServerFor(w, r)
	if m == nil {
		return
	}
	owner, err := s.Store.UserByID(ctx, m.OwnerID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	if block := s.moderationBlock(ctx, me, owner); block != "" {
		WriteError(w, serverBlockError(block))
		return
	}
	var req struct {
		Emojis []ids.ID `json:"emojis"`
		Block  bool     `json:"block"`
		Reason string   `json:"reason"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	list, err := s.Store.ServerEmojis(ctx, m.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	inServer := map[ids.ID]*store.Emoji{}
	for _, e := range list {
		inServer[e.ID] = e
	}
	d := expressionDetails{ServerID: m.ID, ServerName: m.Name, Blocked: req.Block}
	t := store.ModExpressionTakedown{ServerID: &m.ID, Block: req.Block}
	seen := map[ids.ID]bool{}
	for _, id := range req.Emojis {
		e := inServer[id]
		if e == nil {
			WriteError(w, E(http.StatusBadRequest, "not_in_server", "One of those emoji is not in this server any more."))
			return
		}
		if !seen[id] {
			seen[id] = true
			t.Emojis = append(t.Emojis, id)
			d.Emojis = append(d.Emojis, takenDown{ID: e.ID, Name: e.Name, BlobID: e.BlobID})
		}
	}
	if len(t.Emojis) == 0 {
		WriteError(w, E(http.StatusBadRequest, "nothing_selected", "Choose at least one emoji."))
		return
	}
	details := mustJSON(d)
	c := &store.ModCase{UserID: owner.ID, ModeratorID: me.ID, Action: store.ActionExpressionTakedown, Reason: reason, Details: details, TargetUsername: owner.Username, TargetDisplayName: owner.DisplayName}
	log := &store.ModLogEntry{Action: store.ActionExpressionTakedown, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &owner.ID, TargetUsername: owner.Username, Reason: reason, Details: details}
	if err := s.Store.ModTakeDownExpressions(ctx, c, t, log); err != nil {
		if errors.Is(err, store.ErrNotInPack) {
			WriteError(w, E(http.StatusConflict, "changed", "The server's emoji changed while you were looking. Open it again."))
			return
		}
		WriteError(w, err)
		return
	}
	s.emitEmojis(ctx, m.ID)
	s.actionDone(w, ctx, c, log)
}

// handleModUploads is the newest stickers and emoji on Nexa, a page at a time.
func (s *Server) handleModUploads(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 200 {
		limit = 120
	}
	before, _ := strconv.ParseInt(q.Get("before"), 10, 64)
	beforeID, _ := ids.Parse(q.Get("before_id"))
	list, err := s.Store.ModRecentUploads(ctx, before, beforeID, limit+1)
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > limit
	if more {
		list = list[:limit]
	}
	who := []ids.ID{}
	servers := map[string]modServerRef{}
	for _, u := range list {
		who = append(who, u.CreatorID)
		if u.OwnerID != nil {
			who = append(who, *u.OwnerID)
		}
		if u.ServerID != nil {
			key := u.ServerID.String()
			if _, ok := servers[key]; ok {
				continue
			}
			if srv, err := s.Store.ServerByID(ctx, *u.ServerID); err == nil {
				servers[key] = modServerRef{ID: srv.ID, Name: srv.Name, IconID: srv.IconID, OwnerID: srv.OwnerID}
				who = append(who, srv.OwnerID)
			}
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"uploads": list, "people": s.people(ctx, who...), "servers": servers, "has_more": more})
}

// serverExpressions are a server's emoji and packs, for its page in the panel.
func (s *Server) serverExpressions(r *http.Request, serverID ids.ID) ([]*store.Emoji, []modPackView, error) {
	ctx := r.Context()
	emojis, err := s.Store.ServerEmojis(ctx, serverID)
	if err != nil {
		return nil, nil, err
	}
	packs, err := s.Store.ModPacks(ctx, store.ModPackFilter{ServerID: &serverID, Sort: "name", Limit: 200, Preview: packPreview})
	if err != nil {
		return nil, nil, err
	}
	views, _, _ := s.modPackViews(r, packs)
	return emojis, views, nil
}

// personPacks are the packs someone owns, for their page in the panel.
func (s *Server) personPacks(r *http.Request, uid ids.ID) ([]modPackView, error) {
	packs, err := s.Store.ModPacks(r.Context(), store.ModPackFilter{OwnerID: &uid, Sort: "newest", Limit: 200, Preview: packPreview})
	if err != nil {
		return nil, err
	}
	views, _, _ := s.modPackViews(r, packs)
	return views, nil
}
