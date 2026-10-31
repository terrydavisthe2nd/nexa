// Package api serves the REST API and wires the middleware stack.
package api

import (
	"context"
	"crypto/rand"
	"log/slog"
	"net/http"
	"sync"
	"time"

	"golang.org/x/sync/singleflight"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/config"
	"evilion.xyz/chat/internal/discordsync"
	"evilion.xyz/chat/internal/egress"
	"evilion.xyz/chat/internal/games"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/music"
	"evilion.xyz/chat/internal/ratelimit"
	"evilion.xyz/chat/internal/realtime"
	"evilion.xyz/chat/internal/rpcapps"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/unfurl"
	"evilion.xyz/chat/internal/voice"
)

// Broadcaster is how handlers push realtime events; the gateway implements it.
type Broadcaster interface {
	UserUpdated(u *store.User)
	EmitUsers(userIDs []ids.ID, event string, data any)
	EmitChannel(channelID ids.ID, event string, data any)
	KeysMaybeNeeded(convID ids.ID)
	// PresenceChanged re-sends a user's presence to whoever should now see it.
	PresenceChanged(uid ids.ID)
	Typing(ctx context.Context, uid, channelID ids.ID)
	// CloseSession and CloseUserSessions disconnect the sockets of sessions
	// that were just deleted; except 0 closes all of a user's.
	CloseSession(sessionID ids.ID)
	CloseUserSessions(uid, except ids.ID)
	// DisconnectUser closes a user's sockets with a close code of its own,
	// once the frames already queued for them (the reason why) are out.
	DisconnectUser(uid ids.ID, code int, reason string)
}

// NoopBroadcaster drops everything (tests, or before the gateway is wired).
type NoopBroadcaster struct{}

func (NoopBroadcaster) UserUpdated(*store.User)                {}
func (NoopBroadcaster) EmitUsers([]ids.ID, string, any)        {}
func (NoopBroadcaster) EmitChannel(ids.ID, string, any)        {}
func (NoopBroadcaster) KeysMaybeNeeded(ids.ID)                 {}
func (NoopBroadcaster) PresenceChanged(ids.ID)                 {}
func (NoopBroadcaster) Typing(context.Context, ids.ID, ids.ID) {}
func (NoopBroadcaster) CloseSession(ids.ID)                    {}
func (NoopBroadcaster) CloseUserSessions(ids.ID, ids.ID)       {}
func (NoopBroadcaster) DisconnectUser(ids.ID, int, string)     {}

// Server holds the dependencies of every handler.
type Server struct {
	Cfg     config.Config
	Store   *store.Store
	Log     *slog.Logger
	Bcast   Broadcaster
	Auth    *auth.Authenticator
	Version string
	Extra   []func(mux *http.ServeMux) // routes registered by other packages (gateway, media, static)
	Secret  []byte                     // server secret for fake KDF salts (never leaves the box)
	Media   *media.Store
	Profile ProfileExtras
	// InviteCheck reports whether an invite code allows registration (set by the server layer).
	InviteCheck func(ctx context.Context, code string) bool

	// QR sign-ins waiting for a phone, and passkey challenges already answered
	qr           qrLogins
	spentPasskey spentChallenges
	// Uploads admitted but not yet represented in the database, and staged
	// chunks not yet applied to their resumable transfer.
	uploads uploadReservations
	// the plans' prices from Stripe, kept for a while (billing.go)
	priceCache priceCache

	limGeneric *ratelimit.Limiter
	limAuthIP  *ratelimit.Limiter
	limAuthUsr *ratelimit.Limiter
	limReg     *ratelimit.Limiter
	limSignup  *ratelimit.Limiter // accounts made, two a day from one address
	limMsg     *ratelimit.Limiter
	limUpload  *ratelimit.Limiter
	limUnfurl  *ratelimit.Limiter
	limProxy   *ratelimit.Limiter
	limYouTube *ratelimit.Limiter
	limRefresh *ratelimit.Limiter
	limVoice   *ratelimit.Limiter
	limTurn    *ratelimit.Limiter
	limGuest   *ratelimit.Limiter
	limRotate  *ratelimit.Limiter
	limPass    *ratelimit.Limiter
	limProfile *ratelimit.Limiter
	limExpr    *ratelimit.Limiter
	limImport  *ratelimit.Limiter
	limCrash   *ratelimit.Limiter
	limReport  *ratelimit.Limiter
	limBug     *ratelimit.Limiter
	// notes: making them, saving them, inviting people to them, opening links to them
	limNote         *ratelimit.Limiter
	limNoteSave     *ratelimit.Limiter
	limNoteUpdate   *ratelimit.Limiter
	limNoteInvite   *ratelimit.Limiter
	limNoteLink     *ratelimit.Limiter
	limNotePresence *ratelimit.Limiter
	limBugFile      *ratelimit.Limiter
	// limKeyAsk paces a device asking the holders of a conversation's keys
	// for them again, per account and conversation
	limKeyAsk  *ratelimit.Limiter
	limHook    *ratelimit.Limiter
	limHookCh  *ratelimit.Limiter
	limHookMgr *ratelimit.Limiter
	limVote    *ratelimit.Limiter
	limDiscord *ratelimit.Limiter
	// limTag paces changes to a server's tag, which reach everyone wearing it
	limTag *ratelimit.Limiter
	// limMusic paces someone's music searches, which share what Deezer allows this server
	limMusic *ratelimit.Limiter
	// limPreview paces the song previews an address plays, each a lookup on Deezer and half a megabyte through WARP
	limPreview *ratelimit.Limiter
	// limAutomod paces the AutoMod reports an account's app sends
	limAutomod *ratelimit.Limiter
	// limPublish paces how often an announcement channel publishes, each one a copy into every channel following it
	limPublish *ratelimit.Limiter
	// limThread paces the threads and forum posts an account starts
	limThread *ratelimit.Limiter
	// limDiscover paces Discovery searches, each a query over every listed server
	limDiscover *ratelimit.Limiter
	// limApps paces making, changing and deleting applications in the
	// developer portal, per account
	limApps *ratelimit.Limiter
	// botLim is the bot API's rate limits: one per bucket and a global one
	// (bot_auth.go)
	botLim *botLimiters
	// botKeys holds what has been opened for bots: their identities and the
	// roots granted to them (bot_crypto.go)
	botKeys *botKeys
	// bots runs the bot gateway's sessions and the interactions waiting on
	// an answer (bot_gateway.go, bot_interactions.go)
	bots *botRuntime
	// limInteract paces someone using applications: commands, buttons, forms
	limInteract *ratelimit.Limiter

	// volume a bot would turn on other people or on paid services: reactions
	// and messages across every channel (both fan out to whole channels),
	// searches (people, GIFs, games), public lookups by IP (invite previews,
	// profile links, KDF parameters) and building a server's structure
	limReact  *ratelimit.Limiter
	limMsgAll *ratelimit.Limiter
	// limForward: messages forwarded, per account, on top of the message limits
	limForward *ratelimit.Limiter
	limSearch  *ratelimit.Limiter
	limLookup  *ratelimit.Limiter
	limBuild   *ratelimit.Limiter
	stepLookup *stepUp // limLookup, extended by a captcha
	stepBuild  *stepUp // limBuild, extended by a captcha
	// botHTTP, when set, is the client interactions go to applications'
	// endpoints with (tests); otherwise endpointClient builds a guarded one
	botHTTP *http.Client

	// Unfurl produces link previews for senders (nil disables the route body).
	Unfurl *unfurl.Service
	// Egress is the proxy that requests a person can point this server at go
	// out through (internal/egress); nil sends them out directly.
	Egress *egress.Proxy
	// Apps looks up Discord Rich Presence applications for activities.
	Apps *rpcapps.Cache
	// Games searches the games people put on their profile widgets, and keeps
	// their covers (internal/games); off without IGDB credentials.
	Games *games.Client
	// Music searches the artists, albums and songs people put on their
	// profile widgets, and keeps their pictures (internal/music); nil turns
	// the search off.
	Music *music.Client
	// activityHub resolves activity previews the way the gateway shows them.
	activityHub *gateway.Hub
	// Discord shows linked Discord accounts' activity (nil when not configured; see WireDiscord).
	Discord *discordsync.Service
	// discordAPI points the Discord service at a fake Discord, in tests.
	discordAPI string
	// Voice tracks who is in which voice channel (set by WireVoice).
	Voice      *voice.Registry
	sfuLocks   [64]sync.Mutex // serialize provider mutations per call without a growing lock map
	sfuClosing sync.Map       // coalesce concurrent attempts to retire the same provider session
	// hub is the gateway, for whether a guest is still connected (set by WireVoice).
	hub *gateway.Hub
	// modHub is the gateway, for the presence the moderation panel shows (set by WireModeration).
	modHub *gateway.Hub
	// guestAway notes when each guest was first seen with no connection.
	guestAway guestAwayTimes
	// Realtime sends calls through Cloudflare Realtime SFU when configured, and
	// Meter keeps them inside its free allowance (both nil otherwise).
	Realtime *realtime.Client
	Meter    *realtime.Meter
	// turnCreds holds the relay credential each account is handed again,
	// turnLive every credential not yet revoked or expired, and turnBlocked
	// the accounts past their relay for the day (turn.go).
	turnMu      sync.Mutex
	turnCreds   map[ids.ID]turnCred
	turnLive    map[string]*turnGrant
	turnBlocked map[ids.ID]time.Time
	turnLoaded  bool
	turnMint    singleflight.Group
	// turnBase replaces the TURN API's address, for tests.
	turnBase string
	// Analytics reads what Cloudflare measured of the account's Realtime
	// egress, for the meter and the staff's Calls page (nil without
	// REALTIME_ACCOUNT_ID and REALTIME_ANALYTICS_TOKEN).
	Analytics *realtime.Analytics
	// serverCounts holds the member and online counts the public invite pages
	// show, so replaying a shared code cannot drive the work behind them.
	countsMu     sync.Mutex
	serverCounts map[ids.ID]serverCounts

	// system holds the Nexa account once it has been looked up or made.
	system systemAccount

	// background is the loops the Start functions run, so shutdown can wait
	// for them to put down what they were writing before the database closes.
	background sync.WaitGroup

	// ChannelAccess decides server-channel access (Phase 2). DM access is built in.
	ChannelAccess func(ctx context.Context, u *store.User, ch *store.Channel, need string) (bool, error)
	// ConvForChannel maps a server channel to the conversation that holds its keys (Phase 2).
	ConvForChannel func(ctx context.Context, ch *store.Channel) (ids.ID, error)
	// ServerChannelPatch handles PATCH for server channels (Phase 2).
	ServerChannelPatch func(w http.ResponseWriter, r *http.Request, ch *store.Channel, me *store.User)
	// CanRotate restricts who may create key epochs for a conversation (Phase 2).
	CanRotate func(ctx context.Context, u *store.User, conv ids.ID) (bool, error)
}

// WaitBackground waits, until ctx ends, for the loops the Start functions
// began to return after their context was cancelled.
func (s *Server) WaitBackground(ctx context.Context) {
	done := make(chan struct{})
	go func() {
		s.background.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
}

// NewServer builds a Server with rate limiters and the authenticator.
func NewServer(cfg config.Config, st *store.Store, log *slog.Logger, bc Broadcaster) *Server {
	if bc == nil {
		bc = NoopBroadcaster{}
	}
	if log == nil {
		log = slog.Default()
	}
	secret := make([]byte, 32)
	_, _ = rand.Read(secret)
	srv := &Server{
		Cfg: cfg, Store: st, Log: log, Bcast: bc, Version: "dev", Secret: secret,
		Auth:       &auth.Authenticator{Store: st, TTL: cfg.SessionTTL},
		limGeneric: ratelimit.New(ratelimit.Every(600, time.Minute), 300, 10*time.Minute),
		limAuthIP:  ratelimit.New(ratelimit.Every(20, time.Minute), 20, 10*time.Minute),
		limAuthUsr: ratelimit.New(ratelimit.Every(5, time.Minute), 5, 10*time.Minute),
		limReg:     ratelimit.New(ratelimit.Every(10, time.Hour), 10, 2*time.Hour),
		limSignup:  ratelimit.New(ratelimit.Every(2, 24*time.Hour), 2, 25*time.Hour),
		limMsg:     ratelimit.New(ratelimit.Every(5, 5*time.Second), 5, 5*time.Minute),
		limUpload:  ratelimit.New(ratelimit.Every(120, time.Minute), 120, time.Hour),
		limUnfurl:  ratelimit.New(ratelimit.Every(30, time.Minute), 30, 10*time.Minute),
		// The media proxy fetches from somewhere else on the caller's behalf,
		// so an unmetered one is both a way to exhaust Nexa's
		// bandwidth and a way to aim Nexa at someone else's.
		limProxy: ratelimit.New(ratelimit.Every(120, time.Minute), 60, 10*time.Minute),
		// YouTube's player through /yt/, per address: a video asks for its
		// page and scripts at once and then a piece every few seconds, and a
		// household may watch a few at a time
		limYouTube: ratelimit.New(ratelimit.Every(1200, time.Minute), 400, 10*time.Minute),
		// re-signing old media links, per account: a conversation scrolled
		// back through asks once per screenful, up to a hundred links a time
		limRefresh: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// a call renegotiates for every camera, share and person joining
		limVoice: ratelimit.New(ratelimit.Every(240, time.Minute), 120, 10*time.Minute),
		// relay credentials: a call takes one or two an hour, reused in between
		limTurn: ratelimit.New(ratelimit.Every(12, time.Hour), 6, 2*time.Hour),
		// joining a call as a guest, per address: a household or a meeting room, not a crowd of bots
		limGuest: ratelimit.New(ratelimit.Every(30, time.Hour), 10, 2*time.Hour),
		// New key epochs, per conversation. Every one of them costs every
		// online key-holder a re-wrap for every entitled member, so the rate
		// is held to well above real membership churn and far below what one
		// account posting in a loop would manage.
		limRotate: ratelimit.New(ratelimit.Every(60, time.Hour), 30, 2*time.Hour),
		// Password checks by a signed-in account — changing it, a new
		// recovery key, deleting the account, two-factor changes, a locked
		// subchat — each cost an Argon2 hash, the most expensive thing the
		// server does. A person mistyping gets several goes a minute.
		limPass: ratelimit.New(ratelimit.Every(10, time.Minute), 10, 10*time.Minute),
		// Profile and status changes, per account: each is sent to everyone
		// who shares a server with the person.
		limProfile: ratelimit.New(ratelimit.Every(20, time.Minute), 20, 10*time.Minute),
		// New emoji and stickers, per account: a pack is filled by dropping a
		// folder of images in at once, so the burst is generous; a script
		// filling the disk is not.
		limExpr: ratelimit.New(ratelimit.Every(120, time.Minute), 200, 10*time.Minute),
		// Telegram imports, per account: each pulls up to 200 files through
		// Nexa's one bot token.
		limImport: ratelimit.New(ratelimit.Every(10, time.Hour), 6, 2*time.Hour),
		// crash reports, per account: a broken page reports a burst, not a flood
		limCrash: ratelimit.New(ratelimit.Every(10, time.Minute), 10, 10*time.Minute),
		// new notes, per account: a burst while importing or tidying, then a steady few
		limNote: ratelimit.New(ratelimit.Every(60, time.Hour), 30, 2*time.Hour),
		// saves, per account: a note saves itself a fraction of a second after
		// each pause in typing, so that others writing in it see the words
		// arrive; steady typing is a few saves a second at the very most
		limNoteSave: ratelimit.New(ratelimit.Every(240, time.Minute), 120, 10*time.Minute),
		// a change goes up a moment after each burst of typing: several a
		// second from someone writing fast, sustained, in every note they have open
		limNoteUpdate: ratelimit.New(ratelimit.Every(1200, time.Minute), 400, 10*time.Minute),
		// saying a note is open, per account: once on opening, then every
		// twenty seconds, for each note open on each device
		limNotePresence: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// invitations to notes, per account: each one reaches another person
		limNoteInvite: ratelimit.New(ratelimit.Every(30, time.Hour), 10, 2*time.Hour),
		// opening share links, per account: a link's token is a secret, and this
		// is how fast one account may try them
		limNoteLink: ratelimit.New(ratelimit.Every(120, time.Hour), 40, 2*time.Hour),
		// bug reports, per account: someone who hit a few bugs in one sitting
		// can send them all, and the team is not flooded by one account
		limReport: ratelimit.New(ratelimit.Every(10, time.Hour), 10, 2*time.Hour),
		limBug:    ratelimit.New(ratelimit.Every(4, time.Hour), 4, 2*time.Hour),
		// files for bug reports, per account: five to a report, a few reports,
		// and room to add one again after taking it out
		limBugFile: ratelimit.New(ratelimit.Every(30, time.Hour), 15, 2*time.Hour),
		// asking for a conversation's keys again: a few right away, then one
		// every twenty seconds, while a device waits for someone holding them
		limKeyAsk: ratelimit.New(ratelimit.Every(3, time.Minute), 3, 30*time.Minute),
		// Each webhook: five requests every two seconds, and each channel
		// thirty webhook messages a minute, whichever webhooks send them:
		// Discord's limits, which programs written for its webhooks pace
		// themselves by.
		limHook:   ratelimit.New(ratelimit.Every(5, 2*time.Second), 5, 10*time.Minute),
		limHookCh: ratelimit.New(ratelimit.Every(30, time.Minute), 30, 10*time.Minute),
		// making, changing and deleting webhooks, per account
		limHookMgr: ratelimit.New(ratelimit.Every(30, time.Minute), 30, 10*time.Minute),
		// votes on polls, per account: a change of mind is a click
		limVote: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// linking Discord and rejoining its activity server, per account: each calls Discord
		limDiscord: ratelimit.New(ratelimit.Every(10, time.Minute), 10, 10*time.Minute),
		// each change can repaint every wearer's profile everywhere it is shown
		limTag: ratelimit.New(ratelimit.Every(20, time.Hour), 10, 2*time.Hour),
		// a search as someone types, a few for each thing they add
		limMusic: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// a play is a few requests (Safari asks for two bytes first), a song every few seconds at most
		limPreview: ratelimit.New(ratelimit.Every(240, time.Minute), 60, 10*time.Minute),
		// AutoMod reports, per account: someone's own blocked messages, and a
		// moderator's app clearing what slipped past, a burst at a time
		limAutomod: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// publishing, per announcement channel: ten an hour, as Discord allows
		limPublish: ratelimit.New(ratelimit.Every(10, time.Hour), 10, 2*time.Hour),
		// new threads and forum posts, per account
		limThread: ratelimit.New(ratelimit.Every(30, 10*time.Minute), 15, time.Hour),
		// Discovery searches, per account: a few for each word typed
		limDiscover: ratelimit.New(ratelimit.Every(120, time.Minute), 60, 10*time.Minute),
		// applications in the developer portal, per account: making one, its
		// settings, a new token
		limApps: ratelimit.New(ratelimit.Every(30, time.Minute), 20, 10*time.Minute),
		botLim:  newBotLimiters(),
		botKeys: newBotKeys(),
		bots:    newBotRuntime(),
		// using applications, per account: a command, a click, a form
		limInteract: ratelimit.New(ratelimit.Every(60, time.Minute), 20, 10*time.Minute),
		// adding or taking back a reaction, per account
		limReact: ratelimit.New(ratelimit.Every(90, time.Minute), 30, 10*time.Minute),
		// messages and edits per account across every channel, on top of
		// limMsg's five every five seconds in one channel
		limMsgAll: ratelimit.New(ratelimit.Every(120, time.Minute), 30, 10*time.Minute),
		// passing things on is for now and then, not for spreading something everywhere at once
		limForward: ratelimit.New(ratelimit.Every(20, time.Minute), 5, 10*time.Minute),
		// searching people, GIFs and games, per account
		limSearch: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// what anyone can look up without signing in, per address, before a
		// captcha is asked for (stepLookup)
		limLookup: ratelimit.New(ratelimit.Every(60, time.Minute), 30, 10*time.Minute),
		// channels, roles, invites and sticker packs made, per account,
		// before a captcha is asked for (stepBuild)
		limBuild: ratelimit.New(ratelimit.Every(60, time.Hour), 30, 2*time.Hour),
	}
	// past the free allowance a captcha buys a batch more: 100 lookups for
	// ten minutes, ten captchas an hour from one address; 30 more made in
	// an hour, ten captchas a day from one account
	srv.stepLookup = newStepUp("lookup", srv.limLookup, ratelimit.New(ratelimit.Every(10, time.Hour), 10, 2*time.Hour), 100, 10*time.Minute)
	srv.stepBuild = newStepUp("build", srv.limBuild, ratelimit.New(ratelimit.Every(10, 24*time.Hour), 10, 25*time.Hour), 30, time.Hour)
	return srv
}

// limiters is every rate limiter this server keeps.
// limiters names every rate limit, for sweeping and sharing them. One missing
// here is neither swept (every key it ever saw stays in memory) nor shared.
func (s *Server) limiters() map[string]*ratelimit.Limiter {
	out := map[string]*ratelimit.Limiter{
		"generic": s.limGeneric, "auth-ip": s.limAuthIP, "auth-user": s.limAuthUsr, "register": s.limReg, "signup": s.limSignup, "message": s.limMsg,
		"upload": s.limUpload, "unfurl": s.limUnfurl, "proxy": s.limProxy, "youtube": s.limYouTube, "refresh": s.limRefresh, "voice": s.limVoice, "turn": s.limTurn,
		"guest": s.limGuest, "rotate": s.limRotate, "password": s.limPass, "profile": s.limProfile, "expressions": s.limExpr,
		"import": s.limImport, "crash": s.limCrash, "bug": s.limBug, "report": s.limReport, "bug-file": s.limBugFile, "key-ask": s.limKeyAsk,
		"webhook": s.limHook, "webhook-channel": s.limHookCh, "webhook-manage": s.limHookMgr, "poll-vote": s.limVote, "discord": s.limDiscord, "server-tag": s.limTag, "music-search": s.limMusic, "music-preview": s.limPreview,
		"automod": s.limAutomod, "publish": s.limPublish, "thread": s.limThread, "discover": s.limDiscover,
		"note": s.limNote, "note-save": s.limNoteSave, "note-update": s.limNoteUpdate, "note-invite": s.limNoteInvite, "note-link": s.limNoteLink, "note-presence": s.limNotePresence,
		"apps": s.limApps, "interact": s.limInteract,
		"react": s.limReact, "message-all": s.limMsgAll, "forward": s.limForward, "search": s.limSearch, "lookup": s.limLookup, "build": s.limBuild,
		"lookup-captcha": s.stepLookup.solves, "build-captcha": s.stepBuild.solves,
	}
	for name, l := range s.botLim.all() {
		out[name] = l
	}
	return out
}

// StartLimiters runs the rate limiters' idle-key sweepers until ctx is done.
// Without them every IP and every username ever tried stays in memory for the
// life of the process.
func (s *Server) StartLimiters(ctx context.Context) {
	for _, l := range s.limiters() {
		l.Start(ctx)
	}
}

// ShareLimiters makes every rate limit count across chatd processes rather
// than once per process, which is what Nexa behind more than one of
// them needs: otherwise two processes let twice as much through.
func (s *Server) ShareLimiters(shared ratelimit.Shared) {
	for name, l := range s.limiters() {
		l.Share(shared, name)
	}
}

// Handler returns the fully assembled HTTP handler.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/health", func(w http.ResponseWriter, r *http.Request) {
		WriteJSON(w, http.StatusOK, map[string]any{"ok": true, "version": s.Version})
	})
	s.routes(mux)
	for _, extra := range s.Extra {
		extra(mux)
	}
	mux.HandleFunc("/api/", func(w http.ResponseWriter, r *http.Request) {
		WriteError(w, E(http.StatusNotFound, "not_found", "No such API route."))
	})
	return Chain(mux, RequestID, Recover, SecurityHeaders, Logging(s.Log), BodyReadDeadline)
}

// routes registers the versioned API. Domain files add their groups here.
func (s *Server) routes(mux *http.ServeMux) {
	for _, reg := range s.routeGroups() {
		reg(mux)
	}
}

// authed wraps h with the bearer-token middleware, the generic per-user
// limiter, and the account's mute or ban (see moderation_gate.go).
func (s *Server) authed(h http.HandlerFunc) http.Handler {
	return s.Auth.Middleware(RateLimit(s.limGeneric, func(r *http.Request) string {
		if u := auth.UserFrom(r.Context()); u != nil {
			return "u:" + u.ID.String()
		}
		return "ip:" + auth.RateKey(r)
	})(s.restricted(h)))
}

// callAuthed is authed for the routes a call runs on, which guests use too.
func (s *Server) callAuthed(h http.HandlerFunc) http.Handler {
	return s.Auth.CallMiddleware(RateLimit(s.limGeneric, func(r *http.Request) string {
		if u := auth.UserFrom(r.Context()); u != nil {
			return "u:" + u.ID.String()
		}
		return "ip:" + auth.RateKey(r)
	})(s.restricted(h)))
}

// guestAuthed lets in guests only.
func (s *Server) guestAuthed(h http.HandlerFunc) http.Handler {
	return s.Auth.GuestMiddleware(RateLimit(s.limGeneric, func(r *http.Request) string {
		return "u:" + auth.UserFrom(r.Context()).ID.String()
	})(h))
}

// ipLimited wraps a public handler with a per-IP limiter.
func ipLimited(l *ratelimit.Limiter, h http.HandlerFunc) http.Handler {
	return RateLimit(l, func(r *http.Request) string { return auth.RateKey(r) })(h)
}

// userLimited counts against the signed-in account, for routes where guessing
// is the risk rather than volume: typing a subchat's password, say.
func (s *Server) userLimited(l *ratelimit.Limiter, h http.HandlerFunc) http.Handler {
	return s.authed(RateLimit(l, func(r *http.Request) string {
		if u := auth.UserFrom(r.Context()); u != nil {
			return u.ID.String()
		}
		return auth.RateKey(r)
	})(h).ServeHTTP)
}
