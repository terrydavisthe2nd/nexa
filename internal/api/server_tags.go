package api

import (
	"bytes"
	"context"
	"errors"
	"evilion.xyz/chat/internal/textutil"
	"image"
	"io"
	"net/http"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// Server tags. A server's admins pick a few letters, a badge and a colour,
// and its members can wear them next to their name everywhere. Anyone who
// sees a tag can open it to see the server it is from, and join it when the
// admins let them. What someone wears is copied onto their own profile row,
// so every place a name is drawn has it without asking the server, and this
// file keeps the copies in step: a new badge or colour reaches every wearer,
// while new letters, leaving the server, losing the role it needs or the tag
// going away take it off them.

const (
	// a tag's badge picture is shown small and kept small
	tagBadgeSide = 96
	maxTagRoles  = 25
	// a tag's letters: short enough to sit beside a name on a phone
	maxTagLetters = 4
)

// The badges every server can use without putting up a picture of its own,
// by the names the app draws them with (web/src/lib/serverTags.ts).
var tagIcons = map[string]bool{
	"leaf": true, "flame": true, "heart": true, "star": true, "sparkles": true, "zap": true,
	"moon": true, "sun": true, "snowflake": true, "droplet": true, "cloud": true, "rainbow": true,
	"skull": true, "ghost": true, "crown": true, "gem": true, "swords": true, "shield": true,
	"cat": true, "dog": true, "bird": true, "fish": true, "rabbit": true, "paw-print": true,
	"rocket": true, "music": true, "gamepad-2": true, "trophy": true, "coffee": true, "cherry": true,
}

func (s *Server) serverTagRoutes(mux *http.ServeMux) {
	mux.Handle("PUT /api/v1/servers/{id}/tag", s.authed(s.handleServerTagPut))
	mux.Handle("DELETE /api/v1/servers/{id}/tag", s.authed(s.handleServerTagDelete))
	mux.Handle("POST /api/v1/servers/{id}/tag/badge", s.userLimited(s.limUpload, s.handleServerTagBadge))
	mux.Handle("GET /api/v1/server-tags/{id}", s.authed(s.handleServerTagPreview))
	mux.Handle("PUT /api/v1/users/@me/server-tag", s.authed(s.handleWearTag))
	mux.Handle("DELETE /api/v1/users/@me/server-tag", s.authed(s.handleTakeOffTag))
}

// cleanTagText checks a tag's letters: one to maxTagLetters of them, letters
// and digits of any script (a letter's marks count with it, three at most, so
// no zalgo), and a few symbols, with no spaces.
func cleanTagText(raw string) (string, bool) {
	text := strings.TrimSpace(raw)
	if !utf8.ValidString(text) || utf8.RuneCountInString(text) > maxTagLetters*4 {
		return "", false
	}
	n, marks := 0, 0
	for _, r := range text {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			n++
			marks = 0
		case unicode.Is(unicode.Mn, r) || unicode.Is(unicode.Mc, r):
			// a mark belongs to the letter before it and is not one of its own
			marks++
			if n == 0 || (textutil.ZalgoMark(r) && marks > textutil.MaxStackedMarks) {
				return "", false
			}
		case strings.ContainsRune("!?.-_+&#@$%*~^=:'", r):
			n++
			marks = 0
		default:
			return "", false
		}
	}
	return text, n >= 1 && n <= maxTagLetters
}

// canWearTag says whether uid may wear srv's tag: a member, and when the tag
// is kept to some roles, one with one of them or who runs the server.
func (s *Server) canWearTag(ctx context.Context, srv *store.Server, uid ids.ID) (bool, error) {
	if srv.Tag == nil || srv.NSFW {
		return false, nil
	}
	m, err := s.Store.Member(ctx, srv.ID, uid)
	if errors.Is(err, store.ErrNotFound) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !srv.Tag.Limited {
		return true, nil
	}
	if p, err := s.Store.ServerPerms(ctx, srv.ID, uid); err == nil && p.Has(perms.ManageServer) {
		return true, nil
	}
	for _, r := range m.RoleIDs {
		for _, want := range srv.Tag.Roles {
			if r == want {
				return true, nil
			}
		}
	}
	return false, nil
}

// tagChanged tells everyone who sees these people that their tag changed.
func (s *Server) tagChanged(ctx context.Context, uids []ids.ID) {
	for _, id := range uids {
		if u, err := s.Store.UserByID(ctx, id); err == nil {
			s.Bcast.UserUpdated(u)
		}
	}
}

// RecheckTagWearers takes srv's tag off the members who may no longer wear
// it: after their roles changed, or the roles it is kept to did.
func (s *Server) RecheckTagWearers(ctx context.Context, serverID ids.ID, only []ids.ID) {
	srv, err := s.Store.ServerByID(ctx, serverID)
	if err != nil || srv.Tag == nil || !srv.Tag.Limited {
		return
	}
	wearers := only
	if wearers == nil {
		if wearers, err = s.Store.TagWearers(ctx, serverID); err != nil {
			return
		}
	}
	var off []ids.ID
	for _, uid := range wearers {
		if ok, err := s.canWearTag(ctx, srv, uid); err == nil && !ok {
			off = append(off, uid)
		}
	}
	if len(off) == 0 {
		return
	}
	if taken, err := s.Store.TakeOffTags(ctx, serverID, off); err == nil {
		s.tagChanged(ctx, taken)
	}
}

// tagRoleGone drops a deleted role from the roles srv's tag is kept to. The
// tag stays kept to the rest, or to nobody but those who run the server, and
// whoever wore it through that role alone takes it off.
func (s *Server) tagRoleGone(ctx context.Context, srv *store.Server, roleID ids.ID) {
	if srv.Tag == nil || !srv.Tag.Limited {
		return
	}
	kept := []ids.ID{}
	for _, id := range srv.Tag.Roles {
		if id != roleID {
			kept = append(kept, id)
		}
	}
	if len(kept) == len(srv.Tag.Roles) {
		return
	}
	next := *srv.Tag
	next.Roles = kept
	if err := s.Store.SetServerTag(ctx, srv.ID, &next); err != nil {
		return
	}
	s.RecheckTagWearers(ctx, srv.ID, nil)
	if updated, err := s.Store.ServerByID(ctx, srv.ID); err == nil {
		s.emitServer(ctx, srv.ID, "server_update", updated)
	}
}

// takeOffLeaver takes srv's tag off someone who left it or was put out of it.
func (s *Server) takeOffLeaver(ctx context.Context, serverID, uid ids.ID) {
	if taken, err := s.Store.TakeOffTags(ctx, serverID, []ids.ID{uid}); err == nil {
		s.tagChanged(ctx, taken)
	}
}

// landingChannel is where a server's own links (its tag's invite, its vanity
// link) put people who join: its system channel when that is a text channel,
// else its first text channel, else nil.
func (s *Server) landingChannel(ctx context.Context, srv *store.Server) (*ids.ID, error) {
	chans, err := s.Store.ServerChannels(ctx, srv.ID)
	if err != nil {
		return nil, err
	}
	var target *ids.ID
	for _, c := range chans {
		if c.Type != "text" {
			continue
		}
		if srv.SystemChannelID != nil && c.ID == *srv.SystemChannelID {
			id := c.ID
			return &id, nil
		}
		if target == nil {
			id := c.ID
			target = &id
		}
	}
	return target, nil
}

// tagInvite is the invite kept for joining from srv's tag: the one it has
// when that is still good, or a new one that never runs out, to its system
// channel or else its first text channel.
func (s *Server) tagInvite(ctx context.Context, srv *store.Server, by ids.ID) (string, error) {
	if srv.Tag != nil && srv.Tag.Invite != "" {
		if in, err := s.Store.InviteByCode(ctx, srv.Tag.Invite); err == nil && in.ServerID != nil && *in.ServerID == srv.ID {
			return in.Code, nil
		}
	}
	target, err := s.landingChannel(ctx, srv)
	if err != nil {
		return "", err
	}
	if target == nil {
		return "", E(http.StatusBadRequest, "no_channel", "Add a text channel first: people who join from the tag land in one.")
	}
	in, err := s.Store.CreateInvite(ctx, &srv.ID, target, by, 0, 0)
	if err != nil {
		return "", err
	}
	return in.Code, nil
}

func (s *Server) handleServerTagPut(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	if srv.NSFW {
		WriteError(w, E(http.StatusForbidden, "nsfw_tag_blocked", "Servers marked as NSFW cannot use server tags."))
		return
	}

	if ok, retry := s.limTag.Allow("tag:" + srv.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		Text    string   `json:"text"`
		Badge   string   `json:"badge"`
		Color   *int     `json:"color"`
		Limited bool     `json:"limited"`
		Roles   []string `json:"roles"`
		Join    bool     `json:"join"`
	}
	if err := ReadJSON(w, r, &req, 8<<10); err != nil {
		WriteError(w, err)
		return
	}
	bad := func(code, msg string) { WriteError(w, E(http.StatusBadRequest, code, msg)) }
	text, ok := cleanTagText(req.Text)
	if !ok {
		bad("invalid_tag", "A tag is one to eight letters, numbers or symbols, with no spaces.")
		return
	}
	if req.Color != nil && (*req.Color < 0 || *req.Color > 0xFFFFFF) {
		bad("invalid_color", "color must be 0–16777215 or null.")
		return
	}
	old := srv.Tag
	badge := strings.TrimSpace(req.Badge)
	if name, isIcon := strings.CutPrefix(badge, "icon:"); isIcon {
		if !tagIcons[name] {
			bad("invalid_badge", "That isn't one of the badges on offer.")
			return
		}
	} else if img, isImage := store.TagImage(badge); isImage {
		b, err := s.Store.BlobByID(r.Context(), img)
		if err != nil || b.Kind != "tag" || b.Encrypted {
			bad("invalid_badge", "That badge picture has gone. Put it up again.")
			return
		}
	} else {
		bad("invalid_badge", "Choose a badge for the tag.")
		return
	}
	roles := []ids.ID{}
	if req.Limited {
		if len(req.Roles) > maxTagRoles {
			bad("invalid_roles", "A tag can be kept to at most 25 roles.")
			return
		}
		known, err := s.Store.Roles(r.Context(), srv.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		for _, raw := range req.Roles {
			id, err := ids.Parse(raw)
			found := false
			for _, role := range known {
				if err == nil && role.ID == id && role.ID != srv.ID {
					found = true
				}
			}
			if !found {
				bad("invalid_roles", "One of those roles isn't in this server.")
				return
			}
			roles = append(roles, id)
		}
	}
	next := &store.ServerTag{Text: text, Badge: badge, Color: req.Color, Limited: req.Limited, Roles: roles}
	if req.Join {
		code, err := s.tagInvite(r.Context(), srv, me.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		next.Invite, next.Join = code, true
	}
	// a new picture is held for as long as the tag names it; the one it named before is let go
	oldBadge := ""
	if old != nil {
		oldBadge = old.Badge
	}
	if badge != oldBadge {
		if img, isImage := store.TagImage(badge); isImage {
			if err := s.Store.AddBlobRef(r.Context(), img, 1); err != nil {
				bad("invalid_badge", "That badge picture has gone. Put it up again.")
				return
			}
		}
	}
	if err := s.Store.SetServerTag(r.Context(), srv.ID, next); err != nil {
		if img, isImage := store.TagImage(badge); isImage && badge != oldBadge {
			s.releaseBlob(r.Context(), img)
		}
		WriteError(w, err)
		return
	}
	if img, isImage := store.TagImage(oldBadge); isImage && badge != oldBadge {
		s.releaseBlob(r.Context(), img)
	}
	if old != nil && old.Invite != "" && old.Invite != next.Invite {
		_ = s.Store.DeleteInvite(r.Context(), old.Invite)
	}
	// What its wearers have on. New letters take it off them, so nobody ends
	// up wearing words they never chose; a new badge or colour reaches them.
	switch {
	case old != nil && old.Text != next.Text:
		if taken, err := s.Store.TakeOffTags(r.Context(), srv.ID, nil); err == nil {
			s.tagChanged(r.Context(), taken)
		}
	case old != nil && (old.Badge != next.Badge || !sameColor(old.Color, next.Color)):
		if painted, err := s.Store.RepaintTags(r.Context(), srv.ID, next.Badge, next.Color); err == nil {
			s.tagChanged(r.Context(), painted)
		}
	}
	updated, err := s.Store.ServerByID(r.Context(), srv.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if next.Limited {
		s.RecheckTagWearers(r.Context(), srv.ID, nil)
	}
	s.audit(r, srv.ID, me.ID, "server_tag_update", "server", nil, map[string]any{"tag": next.Text, "tag_badge": next.Badge, "tag_color": next.Color, "tag_limited": next.Limited, "tag_join": next.Join}, "")
	s.emitServer(r.Context(), srv.ID, "server_update", updated)
	WriteJSON(w, http.StatusOK, updated)
}

func sameColor(a, b *int) bool {
	return (a == nil && b == nil) || (a != nil && b != nil && *a == *b)
}

func (s *Server) handleServerTagDelete(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	if srv.Tag == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if err := s.removeServerTag(r.Context(), srv); err != nil {
		WriteError(w, err)
		return
	}
	s.audit(r, srv.ID, me.ID, "server_tag_delete", "server", nil, map[string]any{"tag": srv.Tag.Text}, "")
	w.WriteHeader(http.StatusNoContent)
}

// removeServerTag takes a server's tag away: off everyone wearing it, with
// its picture and its invite let go of. The staff use it too.
func (s *Server) removeServerTag(ctx context.Context, srv *store.Server) error {
	if err := s.Store.SetServerTag(ctx, srv.ID, nil); err != nil {
		return err
	}
	if img, ok := store.TagImage(srv.Tag.Badge); ok {
		s.releaseBlob(ctx, img)
	}
	if srv.Tag.Invite != "" {
		_ = s.Store.DeleteInvite(ctx, srv.Tag.Invite)
	}
	if taken, err := s.Store.TakeOffTags(ctx, srv.ID, nil); err == nil {
		s.tagChanged(ctx, taken)
	}
	if updated, err := s.Store.ServerByID(ctx, srv.ID); err == nil {
		s.emitServer(ctx, srv.ID, "server_update", updated)
	}
	return nil
}

// handleServerTagBadge takes a picture for a tag's badge, with the part of it
// that was framed (x, y, w and h in its own pixels; the middle when left
// out), cuts it here and keeps it small. A moving picture stays moving. It is
// held for a day: saving the tag with it keeps it for as long as the tag
// names it.
func (s *Server) handleServerTagBadge(w http.ResponseWriter, r *http.Request) {
	srv, me, _ := s.serverCtx(w, r, perms.ManageServer)
	if srv == nil {
		return
	}
	if srv.NSFW {
		WriteError(w, E(http.StatusForbidden, "nsfw_tag_blocked", "Servers marked as NSFW cannot use server tags."))
		return
	}

	lim := s.limitsFor("tag")
	data, err := io.ReadAll(http.MaxBytesReader(w, r.Body, lim.MaxBytes+1))
	if err != nil || int64(len(data)) > lim.MaxBytes {
		WriteError(w, media.ErrTooLarge)
		return
	}
	if width, height, err := media.ImageDims(bytes.NewReader(data)); err != nil {
		WriteError(w, media.ErrUnsupported)
		return
	} else if err := lim.Check(width, height); err != nil {
		WriteError(w, err)
		return
	}
	out, _, err := media.Crop(data, media.CropSpec{Rect: cropFromQuery(r), MaxW: tagBadgeSide, MaxH: tagBadgeSide})
	if err != nil {
		WriteError(w, cropError(err))
		return
	}
	b, err := s.Media.PutPublic(r.Context(), "tag", bytes.NewReader(out), media.Limits{MaxBytes: int64(len(out)) + 1, MaxW: tagBadgeSide, MaxH: tagBadgeSide}, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	// nothing names it yet: if the tag is never saved with it, it is collected
	s.releaseBlob(r.Context(), b.ID)
	WriteJSON(w, http.StatusOK, map[string]any{"badge": "image:" + b.ID.String(), "id": b.ID, "animated": b.Animated})
}

// cropFromQuery is the part of a picture the app framed, from ?x=&y=&w=&h=.
func cropFromQuery(r *http.Request) image.Rectangle {
	q := r.URL.Query()
	num := func(k string) int {
		n, err := strconv.Atoi(q.Get(k))
		if err != nil || n < 0 || n > 1<<16 {
			return -1
		}
		return n
	}
	x, y, cw, ch := num("x"), num("y"), num("w"), num("h")
	if x < 0 || y < 0 || cw <= 0 || ch <= 0 {
		return image.Rectangle{}
	}
	return image.Rect(x, y, x+cw, y+ch)
}

// cropError says what was wrong with a picture that could not be cut.
func cropError(err error) error {
	switch {
	case errors.Is(err, media.ErrTooManyFrames):
		return E(http.StatusBadRequest, "animation_too_long", "That animation is too long. Use one of a few seconds.")
	case errors.Is(err, media.ErrBadDimensions):
		return E(http.StatusBadRequest, "bad_crop", "That part of the picture is empty. Frame some of it.")
	}
	return media.ErrUnsupported
}

// handleServerTagPreview is what a tag opens: the server it is from, as an
// invite shows it, and whether the one asking is in it, may wear it, or can
// join it from here.
func (s *Server) handleServerTagPreview(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	id, err := ids.Parse(r.PathValue("id"))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), id)
	if err != nil || srv.Tag == nil || srv.NSFW {
		WriteError(w, E(http.StatusNotFound, "no_tag", "That server has no tag any more."))
		return
	}
	member, _ := s.Store.IsMember(r.Context(), srv.ID, me.ID)
	count, online := s.counts(r.Context(), srv.ID)
	resp := map[string]any{
		"server": map[string]any{"id": srv.ID, "name": srv.Name, "icon_id": srv.IconID, "banner_id": srv.BannerID, "description": srv.Description, "member_count": count, "online_count": online},
		"tag":    map[string]any{"text": srv.Tag.Text, "badge": srv.Tag.Badge, "color": srv.Tag.Color},
		"member": member,
	}
	if member {
		can, _ := s.canWearTag(r.Context(), srv, me.ID)
		resp["can_wear"] = can
	} else if srv.Tag.Join && srv.Tag.Invite != "" {
		if in, err := s.Store.InviteByCode(r.Context(), srv.Tag.Invite); err == nil && in.ServerID != nil && *in.ServerID == srv.ID {
			resp["invite"] = in.Code
		}
	}
	WriteJSON(w, http.StatusOK, resp)
}

// handleWearTag puts a server's tag on the account: one it is in, whose tag
// it may wear. Changing the tag shows everyone, so it counts as a profile change.
func (s *Server) handleWearTag(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if ok, retry := s.limProfile.Allow("profile:" + me.ID.String()); !ok {
		w.Header().Set("Retry-After", retryAfter(retry))
		WriteError(w, ErrRateLimited)
		return
	}
	var req struct {
		ServerID string `json:"server_id"`
	}
	if err := ReadJSON(w, r, &req, 1<<10); err != nil {
		WriteError(w, err)
		return
	}
	id, err := ids.Parse(req.ServerID)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	srv, err := s.Store.ServerByID(r.Context(), id)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	can, err := s.canWearTag(r.Context(), srv, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if !can {
		if srv.Tag == nil {
			WriteError(w, E(http.StatusBadRequest, "no_tag", "That server has no tag to wear."))
		} else {
			WriteError(w, E(http.StatusForbidden, "tag_not_yours", "This server keeps its tag to some of its roles, and you don't have one of them."))
		}
		return
	}
	if err := s.Store.WearTag(r.Context(), me.ID, &srv.ID, srv.Tag); err != nil {
		WriteError(w, err)
		return
	}
	updated, err := s.Store.UserByID(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.UserUpdated(updated)
	WriteJSON(w, http.StatusOK, s.meView(r.Context(), updated))
}

// handleTakeOffTag takes the account's tag off. Open to a muted account: it
// only takes something down.
func (s *Server) handleTakeOffTag(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	if me.TagServerID != nil {
		if err := s.Store.WearTag(r.Context(), me.ID, nil, nil); err != nil {
			WriteError(w, err)
			return
		}
	}
	updated, err := s.Store.UserByID(r.Context(), me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	if me.TagServerID != nil {
		s.Bcast.UserUpdated(updated)
	}
	WriteJSON(w, http.StatusOK, s.meView(r.Context(), updated))
}
