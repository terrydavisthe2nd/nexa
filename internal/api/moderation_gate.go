package api

import (
	"context"
	"net/http"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/store"
)

// Mutes and bans are enforced here, in front of every signed-in route, by the
// pattern the request matched. Both lists name what a restricted account may
// still do, never what it may not: a route added later is closed to a muted or
// banned account until someone decides it should be open, rather than open
// until someone remembers to close it.

// banAllowed is everything a banned account can reach: who it is, its Nexa
// cases (a ban can be appealed there), and the way out.
var banAllowed = map[string]bool{
	"GET /api/v1/users/@me":             true,
	"GET /api/v1/users/@me/settings":    true,
	"GET /api/v1/users/@me/badges":      true,
	"POST /api/v1/auth/logout":          true,
	"GET /api/v1/auth/sessions":         true,
	"DELETE /api/v1/auth/sessions/{id}": true,
	"GET /api/v1/auth/passkeys":         true,
	"DELETE /api/v1/auth/passkeys/{id}": true,
	"POST /api/v1/client-errors":        true,
	// a subscription can always be seen and cancelled
	"GET /api/v1/billing":         true,
	"POST /api/v1/billing/portal": true,
}

// muteAllowed is what a muted account can still change besides reading, which
// is always open to it: its security, its own private settings and read
// positions, the keys other people need from it, leaving and blocking, and
// taking down what it already put up. Nothing on the list lets it say
// anything to anybody. A few of these routes can also do something public;
// their handlers ask mutedFor for that part (see there).
var muteAllowed = map[string]bool{
	"POST /api/v1/reports":                                              true,
	"POST /api/v1/reports/attachments":                                  true,
	"POST /api/v1/auth/logout":                                          true,
	"POST /api/v1/billing/portal":                                       true,
	"POST /api/v1/billing/checkout":                                     true,
	"POST /api/v1/gifts/{code}/claim":                                   true,
	"DELETE /api/v1/auth/sessions/{id}":                                 true,
	"POST /api/v1/auth/password":                                        true,
	"POST /api/v1/auth/recovery":                                        true,
	"POST /api/v1/auth/mfa/totp/setup":                                  true,
	"POST /api/v1/auth/mfa/totp/enable":                                 true,
	"POST /api/v1/auth/mfa/disable":                                     true,
	"POST /api/v1/auth/mfa/backup":                                      true,
	"POST /api/v1/auth/passkeys/options":                                true,
	"POST /api/v1/auth/passkeys":                                        true,
	"PATCH /api/v1/auth/passkeys/{id}":                                  true,
	"DELETE /api/v1/auth/passkeys/{id}":                                 true,
	"POST /api/v1/auth/qr/{id}/scan":                                    true, // signing themselves in elsewhere
	"POST /api/v1/auth/qr/{id}/approve":                                 true,
	"POST /api/v1/auth/qr/{id}/deny":                                    true,
	"PUT /api/v1/users/@me/settings":                                    true,
	"PUT /api/v1/users/@me/badges":                                      true, // only the order and which they hide
	"POST /api/v1/users/@me/delete":                                     true,
	"PATCH /api/v1/users/@me":                                           true, // clearing fields and private preferences only
	"DELETE /api/v1/users/@me/avatar":                                   true,
	"DELETE /api/v1/users/@me/banner":                                   true,
	"DELETE /api/v1/users/@me/server-tag":                               true,
	"POST /api/v1/channels/{id}/messages/{mid}/ack":                     true,
	"POST /api/v1/client-errors":                                        true,
	"POST /api/v1/proxy/refresh":                                        true,
	"POST /api/v1/activities/preview":                                   true,
	"POST /api/v1/keys/grants":                                          true,
	"POST /api/v1/keys/epochs":                                          true,
	"POST /api/v1/keys/request":                                         true, // reading needs the keys
	"DELETE /api/v1/users/@me/relationships/{id}":                       true,
	"PATCH /api/v1/users/@me/relationships/{id}":                        true,
	"PUT /api/v1/users/@me/blocks/{id}":                                 true,
	"DELETE /api/v1/users/@me/blocks/{id}":                              true,
	"PUT /api/v1/stickers/packs/{id}/install":                           true,
	"DELETE /api/v1/stickers/packs/{id}/install":                        true,
	"DELETE /api/v1/stickers/recent":                                    true, // only what they sent themselves
	"POST /api/v1/channels/{id}/call/decline":                           true,
	"POST /api/v1/channels/{id}/unlock":                                 true,
	"POST /api/v1/servers/{id}/leave":                                   true,
	"DELETE /api/v1/channels/{id}":                                      true, // closing or leaving a conversation only
	"DELETE /api/v1/channels/{id}/messages/{mid}":                       true, // their own messages only
	"DELETE /api/v1/channels/{id}/messages/{mid}/reactions/{emoji}/@me": true,
	"DELETE /api/v1/channels/{id}/recipients/{uid}":                     true, // leaving only
	"DELETE /api/v1/uploads/{id}":                                       true,
	"PUT /api/v1/voice/sfu/close":                                       true,
	// putting away an app's message meant only for them, and taking back
	// what they let apps do or tearing down their own
	"DELETE /api/v1/ephemeral/{id}":                   true,
	"DELETE /api/v1/oauth2/authorized/{id}":           true,
	"POST /api/v1/applications/{id}/delete":           true,
	"POST /api/v1/applications/{id}/bot/reset":        true,
	"POST /api/v1/applications/{id}/secret/reset":     true,
	"DELETE /api/v1/applications/{id}/commands":       true,
	"DELETE /api/v1/applications/{id}/commands/{cid}": true,
	"DELETE /api/v1/servers/{id}/integrations/{app}":  true,
}

func init() {
	// The member's side of Nexa stays open under both: the case threads are
	// where a mute or a ban is appealed.
	for _, p := range nexaMemberRoutes {
		banAllowed[p] = true
		muteAllowed[p] = true
	}
}

type ctxMuteKey struct{}

// restricted enforces the account's mute or ban on a signed-in request. A
// muted request that gets through carries the mute, for the handlers that
// allow part of what they do (mutedFor).
func (s *Server) restricted(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u := auth.UserFrom(r.Context())
		if u == nil || auth.GuestFrom(r.Context()) != nil {
			h(w, r)
			return
		}
		mute, ban, err := s.Store.ActiveRestrictions(r.Context(), u.ID, time.Now().UnixMilli())
		if err != nil {
			WriteError(w, err)
			return
		}
		if ban != nil && !banAllowed[r.Pattern] {
			WriteError(w, bannedError(ban))
			return
		}
		if mute != nil {
			if r.Method != http.MethodGet && r.Method != http.MethodHead && !muteAllowed[r.Pattern] {
				WriteError(w, mutedError(mute))
				return
			}
			r = r.WithContext(context.WithValue(r.Context(), ctxMuteKey{}, mute))
		}
		h(w, r)
	}
}

// mutedFor is the mute on the request's account, for a handler whose route
// is open to muted accounts but can also do something public: it refuses
// that part itself, and answers true when it has.
func mutedFor(w http.ResponseWriter, r *http.Request) bool {
	mute, _ := r.Context().Value(ctxMuteKey{}).(*store.Restriction)
	if mute == nil {
		return false
	}
	WriteError(w, mutedError(mute))
	return true
}

// untilText is when a restriction ends, for the English sentences below; the
// app says it again in the reader's own language from the code.
func untilText(r *store.Restriction) string {
	if r.ExpiresAt == nil {
		return ""
	}
	return " until " + time.UnixMilli(*r.ExpiresAt).UTC().Format("Jan 2, 2006 15:04 UTC")
}

func mutedError(r *store.Restriction) *Error {
	return E(http.StatusForbidden, "account_muted", "Your account is muted"+untilText(r)+". You can read, but not post, call or change your profile.")
}

func bannedError(r *store.Restriction) *Error {
	if r.ExpiresAt == nil {
		return E(http.StatusForbidden, "account_banned", "Your account is banned. You can appeal from your moderation case.")
	}
	return E(http.StatusForbidden, "account_banned", "Your account is banned"+untilText(r)+". You can appeal from your moderation case.")
}
