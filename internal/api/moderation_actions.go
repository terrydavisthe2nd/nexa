package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// modActionReq is what the Moderate dialog sends. Which fields matter depends
// on the action.
type modActionReq struct {
	Action string `json:"action"`
	Reason string `json:"reason"`
	// avatar_remove: which pictures to take down (the avatar alone when unset)
	Images *struct {
		Avatar bool `json:"avatar"`
		Banner bool `json:"banner"`
	} `json:"images"`
	// field_reset: username, display_name, about_me, pronouns, status,
	// nicknames, server_profiles, typing_style, name_style, widgets, server_tag
	Fields []string `json:"fields"`
	// mute and ban: how long; a ban can be permanent instead
	DurationMS int64 `json:"duration_ms"`
	Permanent  bool  `json:"permanent"`
	// delete: the account's username, typed out
	Confirm string `json:"confirm"`
}

const (
	maxModReason    = 1000
	minModReason    = 3
	minModDuration  = int64(time.Minute / time.Millisecond)
	maxMuteDuration = int64(365 * 24 * time.Hour / time.Millisecond)
	maxBanDuration  = int64(5 * 365 * 24 * time.Hour / time.Millisecond)
	// resetPrefix is what a reset profile field says in its place.
	resetPrefix = "MODERATOR_REMOVED_"
	// usernameResetPrefix is the same for a username, which is lowercase by
	// definition: sign-in lowercases what is typed and looks it up as it is,
	// so an uppercase name could never be signed in with.
	usernameResetPrefix = "moderator_removed_"
	// maxKeptOldValue bounds how much of a reset field the case keeps.
	maxKeptOldValue = 2000
)

// resetFields are the profile fields a field_reset can take back. Nicknames
// are the names the account goes by in its servers, all of them at once, and
// server profiles the bios, pronouns and widgets of its profiles there; those,
// the typing style, the name style, the widgets and the server tag worn are cleared rather
// than overwritten, as a status is.
var resetFields = []string{"username", "display_name", "about_me", "pronouns", "status", "nicknames", "server_profiles", "typing_style", "name_style", "widgets", "server_tag"}

// readReason checks a moderator's reason, which the member reads.
func readReason(w http.ResponseWriter, raw string) (string, bool) {
	reason, ok := CleanText(raw, maxModReason)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "reason_too_long", "Reasons are at most 1000 characters."))
		return "", false
	}
	if len([]rune(reason)) < minModReason {
		WriteError(w, E(http.StatusBadRequest, "reason_required", "Give a reason; the member will read it."))
		return "", false
	}
	return reason, true
}

// randomSuffix is eight random lowercase letters and digits.
func randomSuffix() string {
	const alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	for i := range b {
		b[i] = alphabet[int(b[i])%len(alphabet)]
	}
	return string(b)
}

// clipRunes keeps at most n runes of s, for what a case records of a reset field.
func clipRunes(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n]) + "…"
}

func mustJSON(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return json.RawMessage(`{}`)
	}
	return b
}

// handleModAction takes one action against an account. Each opens a case and
// a log line in the same transaction as the change itself, then tells the
// member — a direct message from Nexa and a notice on screen, both with the
// reason and the moderator's name — and the rest of the staff.
func (s *Server) handleModAction(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	me := auth.UserFrom(ctx)
	target := s.modTarget(w, r)
	if target == nil {
		return
	}
	if block := s.moderationBlock(ctx, me, target); block != "" {
		WriteError(w, blockError(block))
		return
	}
	var req modActionReq
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	reason, ok := readReason(w, req.Reason)
	if !ok {
		return
	}
	switch rank := s.rankOf(ctx, me); {
	case req.Action == store.ActionDelete && rank < rankAdmin:
		WriteError(w, staffRankError(rankAdmin))
		return
	case rank < rankAdmin && !s.modMayRestrict(w, r, target, req):
		return
	}
	c := &store.ModCase{UserID: target.ID, ModeratorID: me.ID, Action: req.Action, Reason: reason, TargetUsername: target.Username, TargetDisplayName: target.DisplayName}
	log := &store.ModLogEntry{Action: req.Action, ModeratorID: me.ID, ModeratorUsername: me.Username, TargetID: &target.ID, TargetUsername: target.Username, Reason: reason}
	switch req.Action {
	case store.ActionAvatarRemove:
		s.actRemoveImages(w, r, target, c, log, req)
	case store.ActionFieldReset:
		s.actResetFields(w, r, target, c, log, req)
	case store.ActionMute, store.ActionBan:
		s.actRestrict(w, r, target, c, log, req)
	case store.ActionDelete:
		s.actDelete(w, r, target, c, log, req)
	default:
		WriteError(w, E(http.StatusBadRequest, "unknown_action", "Unknown moderation action."))
	}
}

// modMayRestrict holds a mod to what their rank allows: taking profile parts
// down freely, and a mute or a ban of at most 30 days, never permanent, and
// never in place of one that would last longer (which would shorten it).
func (s *Server) modMayRestrict(w http.ResponseWriter, r *http.Request, target *store.User, req modActionReq) bool {
	if req.Action == store.ActionAvatarRemove || req.Action == store.ActionFieldReset {
		return true
	}
	if req.Action != store.ActionMute && req.Action != store.ActionBan {
		WriteError(w, E(http.StatusForbidden, "mod_action", "Mods can take content down, and mute and ban for at most 30 days."))
		return false
	}
	if req.Permanent || req.DurationMS > modMaxDuration {
		WriteError(w, E(http.StatusForbidden, "mod_duration", "Mods can mute and ban for at most 30 days."))
		return false
	}
	now := time.Now().UnixMilli()
	mute, ban, err := s.Store.ActiveRestrictions(r.Context(), target.ID, now)
	if err != nil {
		WriteError(w, err)
		return false
	}
	cur := mute
	if req.Action == store.ActionBan {
		cur = ban
	}
	if cur != nil && (cur.ExpiresAt == nil || *cur.ExpiresAt > now+req.DurationMS) {
		WriteError(w, E(http.StatusForbidden, "mod_shortens", "They already have a longer one. Only an admin can shorten it."))
		return false
	}
	return true
}

func (s *Server) actRemoveImages(w http.ResponseWriter, r *http.Request, target *store.User, c *store.ModCase, log *store.ModLogEntry, req modActionReq) {
	ctx := r.Context()
	which := store.ModImages{Avatar: true}
	if req.Images != nil {
		which = store.ModImages{Avatar: req.Images.Avatar, Banner: req.Images.Banner}
	}
	// a picture of theirs counts wherever it is shown: on their profile, or on
	// their profile in one of their servers
	serverAvatar, serverBanner := false, false
	if which.Avatar || which.Banner {
		all, err := s.Store.ModMemberships(ctx, target.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		for _, m := range all {
			serverAvatar = serverAvatar || m.AvatarID != nil
			serverBanner = serverBanner || m.BannerID != nil
		}
	}
	switch {
	case !which.Avatar && !which.Banner:
		WriteError(w, E(http.StatusBadRequest, "nothing_selected", "Choose the profile picture, the banner, or both."))
		return
	case which.Avatar && target.AvatarID == nil && !serverAvatar:
		WriteError(w, E(http.StatusBadRequest, "no_avatar", "They have no profile picture to remove."))
		return
	case which.Banner && target.BannerID == nil && !serverBanner:
		WriteError(w, E(http.StatusBadRequest, "no_banner", "They have no banner to remove."))
		return
	}
	c.Details = mustJSON(map[string]bool{"avatar": which.Avatar, "banner": which.Banner})
	log.Details = c.Details
	servers, err := s.Store.ModRemoveImages(ctx, c, which, log)
	if err != nil {
		WriteError(w, err)
		return
	}
	if updated, err := s.Store.UserByID(ctx, target.ID); err == nil {
		s.Bcast.UserUpdated(updated)
	}
	for _, srv := range servers {
		if m, err := s.Store.Member(ctx, srv, target.ID); err == nil {
			s.emitServer(ctx, srv, "member_update", m)
		}
	}
	s.actionDone(w, ctx, c, log)
}

func (s *Server) actResetFields(w http.ResponseWriter, r *http.Request, target *store.User, c *store.ModCase, log *store.ModLogEntry, req modActionReq) {
	ctx := r.Context()
	chosen := map[string]bool{}
	var fields []string
	for _, f := range req.Fields {
		if !OneOf(f, resetFields...) {
			WriteError(w, E(http.StatusBadRequest, "unknown_field", "Only the username, display name, about me, pronouns, custom status, server nicknames, server profiles, typing indicator, widgets and server tag can be reset."))
			return
		}
		if !chosen[f] {
			chosen[f] = true
			fields = append(fields, f)
		}
	}
	if len(fields) == 0 {
		WriteError(w, E(http.StatusBadRequest, "nothing_selected", "Choose at least one field to reset."))
		return
	}
	// the servers they go by a nickname in, which a nickname reset clears, and
	// the ones they have a bio, pronouns or widgets of their own in
	var nicknamed, profiled []store.ModMembership
	if chosen["nicknames"] || chosen["server_profiles"] {
		all, err := s.Store.ModMemberships(ctx, target.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		for _, m := range all {
			if m.Nickname != "" && chosen["nicknames"] {
				nicknamed = append(nicknamed, m)
			}
			if m.HasProfile() && chosen["server_profiles"] {
				profiled = append(profiled, m)
			}
		}
	}
	var widgets string
	if chosen["widgets"] {
		raw, err := s.Store.UserWidgets(ctx, target.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		widgets = raw
	}
	empty := map[string]bool{"about_me": target.AboutMe == "", "pronouns": target.Pronouns == "", "status": target.StatusText == "" && target.StatusEmoji == "", "nicknames": len(nicknamed) == 0, "server_profiles": len(profiled) == 0,
		"typing_style": target.TypingStyle == "", "name_style": target.NameStyle == "", "widgets": widgets == "", "server_tag": target.WornTag() == nil}
	for _, f := range fields {
		if empty[f] {
			WriteError(w, E(http.StatusBadRequest, "field_empty", "That field is already empty, so there is nothing to reset."))
			return
		}
	}
	var err error
	// A new username can collide with one taken meanwhile; drawing another is
	// all it takes, and eight random characters make the second try the last.
	for attempt := 0; attempt < 5; attempt++ {
		suffix := randomSuffix()
		var f store.ModFieldReset
		oldVals, newVals := map[string]string{}, map[string]string{}
		for _, field := range fields {
			switch field {
			case "username":
				v := usernameResetPrefix + suffix
				f.Username, f.HeldUsername = &v, target.Username
				oldVals[field], newVals[field] = target.Username, v
			case "display_name":
				v := resetPrefix + suffix
				f.DisplayName = &v
				oldVals[field], newVals[field] = target.DisplayName, v
			case "about_me":
				v := resetPrefix + suffix
				f.AboutMe = &v
				oldVals[field], newVals[field] = clipRunes(target.AboutMe, maxKeptOldValue), v
			case "pronouns":
				v := resetPrefix + suffix
				f.Pronouns = &v
				oldVals[field], newVals[field] = target.Pronouns, v
			case "status":
				// a status is there for a while and then gone, so it is cleared
				// rather than left saying MODERATOR_REMOVED for days
				f.Status = true
				oldVals[field], newVals[field] = clipRunes(target.StatusEmoji+" "+target.StatusText, maxKeptOldValue), ""
			case "nicknames":
				// cleared, so each server shows their name again
				f.Nicknames = true
				var was []string
				for _, m := range nicknamed {
					was = append(was, m.Name+": "+m.Nickname)
				}
				oldVals[field], newVals[field] = clipRunes(strings.Join(was, "\n"), maxKeptOldValue), ""
			case "server_profiles":
				// cleared, so each server shows the profile they have everywhere
				f.ServerProfiles = true
				var was []string
				for _, m := range profiled {
					var parts []string
					if m.Pronouns != "" {
						parts = append(parts, m.Pronouns)
					}
					if m.Bio != "" {
						parts = append(parts, m.Bio)
					}
					if m.Widgets != "" {
						parts = append(parts, widgetsText(m.Widgets))
					}
					was = append(was, m.Name+": "+strings.Join(parts, " / "))
				}
				oldVals[field], newVals[field] = clipRunes(strings.Join(was, "\n"), maxKeptOldValue), ""
			case "typing_style":
				f.TypingStyle = true
				oldVals[field], newVals[field] = typingStyleText(target.TypingStyle), ""
			case "name_style":
				f.NameStyle = true
				oldVals[field], newVals[field] = nameStyleText(target.NameStyle), ""
			case "widgets":
				f.Widgets = true
				oldVals[field], newVals[field] = clipRunes(widgetsText(widgets), maxKeptOldValue), ""
			case "server_tag":
				f.ServerTag = true
				oldVals[field], newVals[field] = target.TagText, ""
			}
		}
		c.Details = mustJSON(map[string]any{"fields": fields, "old": oldVals, "new": newVals})
		log.Details = c.Details
		if err = s.Store.ModResetFields(ctx, c, f, log); !errors.Is(err, store.ErrUsernameTaken) {
			break
		}
		// the rolled-back attempt's ids are free to use again, but not its number
		c.Number, log.CaseID, log.CaseNumber = 0, nil, nil
	}
	if err != nil {
		WriteError(w, err)
		return
	}
	if updated, err := s.Store.UserByID(ctx, target.ID); err == nil {
		s.Bcast.UserUpdated(updated)
	}
	told := map[ids.ID]bool{}
	for _, m := range append(nicknamed, profiled...) {
		if told[m.ServerID] {
			continue
		}
		told[m.ServerID] = true
		if mem, err := s.Store.Member(ctx, m.ServerID, target.ID); err == nil {
			s.emitServer(ctx, m.ServerID, "member_update", mem)
		}
	}
	s.actionDone(w, ctx, c, log)
}

func (s *Server) actRestrict(w http.ResponseWriter, r *http.Request, target *store.User, c *store.ModCase, log *store.ModLogEntry, req modActionReq) {
	ctx := r.Context()
	now := time.Now().UnixMilli()
	c.CreatedAt = now
	if req.Action == store.ActionBan && req.Permanent {
		c.Details = mustJSON(map[string]any{"permanent": true})
	} else {
		limit := maxMuteDuration
		if req.Action == store.ActionBan {
			limit = maxBanDuration
		}
		if req.DurationMS < minModDuration || req.DurationMS > limit {
			if req.Action == store.ActionBan {
				WriteError(w, E(http.StatusBadRequest, "bad_duration", "A ban lasts between a minute and five years, or is permanent."))
			} else {
				WriteError(w, E(http.StatusBadRequest, "bad_duration", "A mute lasts between a minute and a year."))
			}
			return
		}
		exp := now + req.DurationMS
		c.ExpiresAt = &exp
		c.Details = mustJSON(map[string]any{"duration_ms": req.DurationMS})
	}
	log.Details = c.Details
	prev, line, err := s.Store.ModRestrict(ctx, c, log)
	if err != nil {
		WriteError(w, err)
		return
	}
	// out of any call at once: a muted or banned account is in none
	s.dropVoice(ctx, target.ID, func(*store.Channel) bool { return true })
	if !prev.IsZero() {
		if pc, err := s.Store.ModCaseByID(ctx, prev); err == nil {
			s.emitCase(ctx, pc, line)
		}
	}
	s.actionDone(w, ctx, c, log)
	if req.Action == store.ActionBan {
		// the notice above is queued first, so it arrives before the door shuts
		s.Bcast.DisconnectUser(target.ID, 4003, "banned")
	}
}

// actionDone tells the member and the staff about a new case and answers the
// moderator with it.
func (s *Server) actionDone(w http.ResponseWriter, ctx context.Context, c *store.ModCase, log *store.ModLogEntry) {
	s.notifyMember(ctx, c)
	s.emitCaseStaff(ctx, c)
	s.emitLog(ctx, log)
	cs := s.caseStateFor(ctx, c.UserID)
	WriteJSON(w, http.StatusCreated, map[string]any{"case": toStaffCase(c, cs, nil), "people": s.people(ctx, casePeople(c)...)})
}

// notifyMember is the notice a new case gives its member: it lands in their
// Nexa conversation and pops up on every device they have open, and it keeps
// popping up until they dismiss it.
func (s *Server) notifyMember(ctx context.Context, c *store.ModCase) {
	u, err := s.Store.UserByID(ctx, c.UserID)
	if err != nil {
		return
	}
	st, err := s.nexaState(ctx, u)
	if err != nil {
		s.Log.Warn("nexa: state", "user", c.UserID.String(), "err", err)
		st = &nexaState{Pending: []memberCase{}}
	}
	cs := s.caseStateFor(ctx, c.UserID)
	s.Bcast.EmitUsers([]ids.ID{c.UserID}, "nexa_notice", map[string]any{"case": toMemberCase(c, cs), "people": s.people(ctx, casePeople(c)...), "state": st})
}

// emitCaseStaff tells the staff alone that a case changed.
func (s *Server) emitCaseStaff(ctx context.Context, c *store.ModCase, lines ...*store.ModMessage) {
	if lines == nil {
		lines = []*store.ModMessage{}
	}
	who := casePeople(c)
	for _, m := range lines {
		who = append(who, m.AuthorID)
	}
	var last *store.ModMessage
	if n := len(lines); n > 0 {
		last = lines[n-1]
	} else if m, err := s.Store.LastModMessages(ctx, []ids.ID{c.ID}); err == nil {
		last = m[c.ID]
	}
	counts, _ := s.Store.CountModCases(ctx, time.Now().UnixMilli())
	s.emitStaff(ctx, rankHelper, "mod_case", map[string]any{"case": toStaffCase(c, s.caseStateFor(ctx, c.UserID), last), "messages": lines, "people": s.people(ctx, who...), "counts": counts})
	// what waits on the staff, and so what each of them has not read, may have changed
	s.emitModUnreadAll(ctx)
}
