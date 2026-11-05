package api

import (
	"encoding/json"
	"net/http"
	"strconv"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// Servers in the moderation panel: the staff see what each server shows
// everyone (its icon, banner, name and description), who owns it and how many
// are in it, and take back what breaks the rules. What is said inside a server
// is never shown here. Taking something back opens a case for the server's
// owner, who reads why in their Nexa conversation and can answer there.

func (s *Server) modServerRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/moderation/servers", s.staff(rankMod, s.handleModServers))
	mux.Handle("GET /api/v1/moderation/servers/{id}", s.staff(rankMod, s.handleModServer))
	mux.Handle("POST /api/v1/moderation/servers/{id}/actions", s.staff(rankAdmin, s.handleModServerAction))
	mux.Handle("DELETE /api/v1/moderation/servers/{id}/discovery-hold", s.staff(rankAdmin, s.handleModDiscoveryRestore))
}

// serverResetFields are what a server_reset can take back. "discovery" takes
// the server off Discovery until the staff let it back on.
var serverResetFields = []string{"icon", "banner", "name", "description", "tag", "discovery"}

// modServerView is a server in the panel, and whether this moderator may act on it.
type modServerView struct {
	*store.ModServer
	// Blocked is why the viewer cannot act on the server: its owner is out of
	// their reach ("" when they can).
	Blocked string `json:"blocked"`
}

func (s *Server) modServerView(r *http.Request, m *store.ModServer) modServerView {
	v := modServerView{ModServer: m}
	if owner, err := s.Store.UserByID(r.Context(), m.OwnerID); err == nil {
		v.Blocked = s.moderationBlock(r.Context(), auth.UserFrom(r.Context()), owner)
	}
	return v
}

func (s *Server) handleModServers(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	q := r.URL.Query()
	limit, _ := strconv.Atoi(q.Get("limit"))
	if limit <= 0 || limit > 120 {
		limit = 60
	}
	offset, _ := strconv.Atoi(q.Get("offset"))
	sort := q.Get("sort")
	if !OneOf(sort, "newest", "oldest", "members", "name") {
		sort = "newest"
	}
	filter := store.ModServerFilter{Query: q.Get("q"), Discovery: q.Get("discovery"), Sort: sort, Offset: offset, Limit: limit + 1}
	if filter.Discovery != "" && !OneOf(filter.Discovery, "all", "listed", "held") {
		WriteError(w, E(http.StatusBadRequest, "invalid_filter", "Choose all, listed or held Discovery servers."))
		return
	}
	list, err := s.Store.ModServers(ctx, filter)
	if err != nil {
		WriteError(w, err)
		return
	}
	more := len(list) > limit
	if more {
		list = list[:limit]
	}
	total, err := s.Store.CountModServers(ctx, filter)
	if err != nil {
		WriteError(w, err)
		return
	}
	out := make([]modServerView, 0, len(list))
	owners := make([]ids.ID, 0, len(list))
	for _, m := range list {
		out = append(out, s.modServerView(r, m))
		owners = append(owners, m.OwnerID)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"servers": out, "people": s.people(ctx, owners...), "total": total, "has_more": more})
}

// modServerFor loads the server a staff route names.
func (s *Server) modServerFor(w http.ResponseWriter, r *http.Request) *store.ModServer {
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	m, err := s.Store.ModServerByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return nil
	}
	return m
}

// handleModServer is one server with the cases about it, newest first.
func (s *Server) handleModServer(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	m := s.modServerFor(w, r)
	if m == nil {
		return
	}
	cases, err := s.serverCases(r, m.ID, m.OwnerID)
	if err != nil {
		WriteError(w, err)
		return
	}
	who := []ids.ID{m.OwnerID}
	for _, c := range cases {
		who = append(who, c.ModeratorID)
	}
	// its emoji and sticker packs, which the staff can take down from here too
	emojis, packs, err := s.serverExpressions(r, m.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"server": s.modServerView(r, m), "cases": cases, "people": s.people(ctx, who...), "emojis": emojis, "packs": packs})
}

// serverCases are the cases its owner has had about this server. A server's
// case belongs to whoever owned it then, so one that changed hands keeps its
// earlier cases with its earlier owner.
func (s *Server) serverCases(r *http.Request, serverID, owner ids.ID) ([]staffCase, error) {
	ctx := r.Context()
	all, err := s.Store.ModCasesForUser(ctx, owner)
	if err != nil {
		return nil, err
	}
	cs := s.caseStateFor(ctx, owner)
	out := []staffCase{}
	for _, c := range all {
		// its own parts taken back, and its emoji and packs taken down
		if (c.Action != store.ActionServerReset && c.Action != store.ActionExpressionTakedown) || serverIDOf(c) != serverID {
			continue
		}
		out = append(out, toStaffCase(c, cs, nil))
	}
	return out, nil
}

// serverIDOf is the server a server_reset case was about, or an
// expression_takedown about a server's emoji or pack.
func serverIDOf(c *store.ModCase) ids.ID {
	var d struct {
		ServerID ids.ID `json:"server_id"`
	}
	if err := json.Unmarshal(c.Details, &d); err != nil {
		return 0
	}
	return d.ServerID
}

// handleModServerAction takes back parts of what a server shows everyone. It
// opens a case for the server's owner and tells them, as every action does,
// and the server's members see the change at once.
func (s *Server) handleModServerAction(w http.ResponseWriter, r *http.Request) {
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
		Fields []string `json:"fields"`
		Reason string   `json:"reason"`
	}
	if err := ReadJSON(w, r, &req, 16<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	chosen := map[string]bool{}
	var fields []string
	for _, f := range req.Fields {
		if !OneOf(f, serverResetFields...) {
			WriteError(w, E(http.StatusBadRequest, "unknown_field", "Only a server's icon, banner, name, description, tag and place in Discovery can be taken back."))
			return
		}
		if !chosen[f] {
			chosen[f] = true
			fields = append(fields, f)
		}
	}
	switch {
	case len(fields) == 0:
		WriteError(w, E(http.StatusBadRequest, "nothing_selected", "Choose at least one part of the server."))
		return
	case chosen["icon"] && m.IconID == nil:
		WriteError(w, E(http.StatusBadRequest, "no_icon", "That server has no icon to remove."))
		return
	case chosen["banner"] && m.BannerID == nil:
		WriteError(w, E(http.StatusBadRequest, "no_banner", "That server has no banner to remove."))
		return
	case chosen["description"] && m.Description == "":
		WriteError(w, E(http.StatusBadRequest, "field_empty", "That server has no description to clear."))
		return
	case chosen["discovery"] && (!m.Discoverable || m.DiscoveryHidden):
		WriteError(w, E(http.StatusBadRequest, "not_listed", "That server is not on Discovery."))
		return
	}
	var tag *store.ServerTag
	if chosen["tag"] {
		if m.Tag == nil {
			WriteError(w, E(http.StatusBadRequest, "no_tag", "That server has no tag to remove."))
			return
		}
		tag = m.Tag
	}
	reset := store.ModServerReset{Icon: chosen["icon"], Banner: chosen["banner"], Description: chosen["description"], Tag: tag != nil, Discovery: chosen["discovery"]}
	oldVals, newVals := map[string]string{}, map[string]string{}
	if tag != nil {
		oldVals["tag"], newVals["tag"] = tag.Text, ""
	}
	if chosen["name"] {
		name := resetPrefix + randomSuffix()
		reset.Name = &name
		oldVals["name"], newVals["name"] = m.Name, name
	}
	if chosen["description"] {
		oldVals["description"], newVals["description"] = clipRunes(m.Description, maxKeptOldValue), ""
	}
	details := mustJSON(map[string]any{"server_id": m.ID, "server_name": m.Name, "fields": fields, "old": oldVals, "new": newVals})
	c := &store.ModCase{UserID: owner.ID, ModeratorID: me.ID, Action: store.ActionServerReset, Reason: reason, Details: details, TargetUsername: owner.Username, TargetDisplayName: owner.DisplayName}
	log := &store.ModLogEntry{Action: store.ActionServerReset, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &owner.ID, TargetUsername: owner.Username, Reason: reason, Details: details}
	if err := s.Store.ModResetServer(ctx, c, m.ID, reset, log); err != nil {
		WriteError(w, err)
		return
	}
	if tag != nil {
		// gone from the server, so off everyone wearing it, with its badge and the invite kept for it
		if img, ok := store.TagImage(tag.Badge); ok {
			s.releaseBlob(ctx, img)
		}
		if tag.Invite != "" {
			_ = s.Store.DeleteInvite(ctx, tag.Invite)
		}
		if taken, err := s.Store.TakeOffTags(ctx, m.ID, nil); err == nil {
			s.tagChanged(ctx, taken)
		}
	}
	if updated, err := s.Store.ServerByID(ctx, m.ID); err == nil {
		s.emitServer(ctx, m.ID, "server_update", updated)
	}
	s.actionDone(w, ctx, c, log)
}

// handleModDiscoveryRestore lets a server the staff took off Discovery back
// on, which its owner then decides about as before.
func (s *Server) handleModDiscoveryRestore(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	m := s.modServerFor(w, r)
	if m == nil {
		return
	}
	if !m.DiscoveryHidden {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	no := false
	if err := s.Store.UpdateServer(ctx, m.ID, store.ServerPatch{DiscoveryHidden: &no}); err != nil {
		WriteError(w, err)
		return
	}
	details := mustJSON(map[string]any{"server_id": m.ID, "server_name": m.Name})
	if err := s.Store.AppendModLog(ctx, &store.ModLogEntry{Action: store.LogDiscoveryRestore, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &m.OwnerID, Details: details}); err != nil {
		s.Log.Warn("mod log", "err", err)
	}
	if updated, err := s.Store.ServerByID(ctx, m.ID); err == nil {
		s.emitServer(ctx, m.ID, "server_update", updated)
	}
	w.WriteHeader(http.StatusNoContent)
}

// serverBlockError says why a server's owner is out of this moderator's reach.
func serverBlockError(code string) *Error {
	switch code {
	case blockSelf:
		return E(http.StatusBadRequest, "moderate_self", "That is your own server. Change it in its settings.")
	case blockOwner:
		return E(http.StatusForbidden, "moderate_owner", "Servers owned by the owner of Nexa cannot be moderated.")
	case blockStaff:
		return E(http.StatusForbidden, "moderate_staff", "That server belongs to staff. Take away their admin access first.")
	}
	return blockError(code)
}
