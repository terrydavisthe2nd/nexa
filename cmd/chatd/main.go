// Command chatd is the Nexa server: REST API, WebSocket gateway, media
// storage and the built web app, in one process.
package main

import (
	"context"
	"crypto/rand"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/coder/websocket"

	"evilion.xyz/chat/internal/api"
	"evilion.xyz/chat/internal/books"
	"evilion.xyz/chat/internal/cache"
	"evilion.xyz/chat/internal/config"
	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/egress"
	"evilion.xyz/chat/internal/games"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/media"
	"evilion.xyz/chat/internal/music"
	"evilion.xyz/chat/internal/objstore"
	"evilion.xyz/chat/internal/rpcapps"
	"evilion.xyz/chat/internal/screen"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/unfurl"
)

// version is set at build time: -ldflags "-X main.version=…".
var version = "dev"

func main() {
	envFile := flag.String("env", "chatd.env", "path to an optional KEY=VALUE env file")
	flag.Parse()

	fileEnv, err := config.LoadEnvFile(*envFile)
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		slog.Error("env file", "path", *envFile, "err", err)
		os.Exit(2)
	}
	getenv := func(k string) string {
		if v := os.Getenv(k); v != "" {
			return v
		}
		return fileEnv[k]
	}
	cfg, err := config.Load(getenv)
	if err != nil {
		slog.Error("config", "err", err)
		os.Exit(2)
	}
	log := setupLogging(cfg.LogLevel, cfg.LogFile)
	// Before anything can generate one: the node id is part of every id, and
	// two processes sharing a database with the same one collide.
	if err := ids.SetNode(cfg.NodeID); err != nil {
		log.Error("node id", "err", err)
		os.Exit(2)
	}
	if w := processWarning(cfg); w != "" {
		log.Warn(w, "node_id", cfg.NodeID, "redis", cfg.RedisURL != "")
	}

	if err := os.MkdirAll(cfg.DataDir, 0o750); err != nil {
		log.Error("data dir", "err", err)
		os.Exit(1)
	}
	dbAddr := cfg.DatabaseURL
	if dbAddr == "" {
		dbAddr = filepath.Join(cfg.DataDir, "chat.db")
	}
	database, err := db.OpenURL(dbAddr)
	if err != nil {
		log.Error("open database", "err", err)
		os.Exit(1)
	}
	if err := database.Migrate(context.Background()); err != nil {
		log.Error("migrate", "err", err)
		os.Exit(1)
	}
	if v, err := database.Version(context.Background()); err == nil {
		log.Info("database ready", "dialect", string(database.Dialect), "schema_version", v)
	}
	st := store.New(database)
	mediaStore, err := media.New(filepath.Join(cfg.DataDir, "media"), st)
	if err != nil {
		log.Error("media dir", "err", err)
		os.Exit(1)
	}
	// End-to-end encrypted attachments move to the bucket once sent
	// (internal/media/remote.go); without one every file stays on this disk.
	var bucket *objstore.Client
	if cfg.R2On() {
		if bucket, err = objstore.New(cfg.R2URL(), cfg.R2Bucket, cfg.R2AccessKeyID, cfg.R2SecretAccessKey); err != nil {
			log.Error("R2 bucket", "err", err)
			os.Exit(2)
		}
		mediaStore.Remote = bucket
		log.Info("attachments are kept in R2", "bucket", cfg.R2Bucket)
	}
	secret, err := loadOrCreateSecret(filepath.Join(cfg.DataDir, "server.secret"), func() (int, error) {
		return st.CountTwoFactorAccounts(context.Background())
	})
	if err != nil {
		log.Error("server secret", "err", err)
		os.Exit(1)
	}

	shared, err := cache.Open(cfg.RedisURL)
	if err != nil {
		log.Error("open redis", "err", err)
		os.Exit(1)
	}
	defer shared.Close()

	srv := api.NewServer(cfg, st, log, nil)
	srv.Version = version
	// With a Redis behind it every process counts against one allowance; with
	// none, each keeps its own, which is right while there is only one.
	if cfg.RedisURL != "" {
		srv.ShareLimiters(shared)
	}
	srv.Secret = secret
	srv.Media = mediaStore
	if bucket != nil {
		bucket.Billed = srv.StorageBilled
		mediaStore.Billed = srv.StorageBilled
	}
	srv.Unfurl = unfurl.NewService()
	// Twitch's player refuses to open unless it is told which pages may hold
	// it: every address this Nexa is at.
	for _, o := range cfg.Origins() {
		if u, err := url.Parse(o); err == nil && u.Hostname() != "" {
			srv.Unfurl.Fetcher.ParentHosts = append(srv.Unfurl.Fetcher.ParentHosts, u.Hostname())
		}
	}
	// What a person can make this server fetch goes out through the proxy.
	out, warp := setupEgress(cfg, log)
	if out != nil {
		srv.Egress = out
		srv.Unfurl.Fetcher.Dial = out.DialContext
		srv.Unfurl.Fetcher.Resolver = out.Resolver()
		srv.Apps = rpcapps.New()
		srv.Apps.Client.Transport = out.Transport()
	}
	// the games people put on their profiles, searched on IGDB and their covers kept, here for everyone
	srv.Games = games.New(cfg.IGDBClientID, cfg.IGDBClientSecret, filepath.Join(cfg.DataDir, "games"))
	srv.Games.Log = log
	if out != nil {
		srv.Games.HTTP.Transport = out.Transport()
	}
	// their shows and films, searched on TMDB and their posters kept the same way
	srv.Screen = screen.New(cfg.TMDBAPIKey, filepath.Join(cfg.DataDir, "screen"))
	srv.Screen.Log = log
	if out != nil {
		srv.Screen.HTTP.Transport = out.Transport()
	}
	// their books, searched on Open Library and their covers kept the same way
	srv.Books = books.New(filepath.Join(cfg.DataDir, "books"))
	srv.Books.Log = log
	if out != nil {
		srv.Books.HTTP.Transport = out.Transport()
	}
	// and their music, searched on Deezer and its pictures kept the same way
	srv.Music = music.New(filepath.Join(cfg.DataDir, "music"))
	srv.Music.Log = log
	if out != nil {
		srv.Music.HTTP.Transport = out.Transport()
	}
	hub := gateway.New(st, srv.Auth, log, cfg.Origins())
	srv.Bcast = hub
	srv.WireServers(hub)
	srv.WireVoice(hub)
	srv.WireActivities(hub)
	srv.WireDiscord(hub)
	// last: it wraps the ready extras the others installed
	srv.WireModeration(hub)
	srv.Extra = append(srv.Extra, func(mux *http.ServeMux) { mux.Handle("GET /gateway", hub) })

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go hub.Run(ctx)
	srv.StartLimiters(ctx)
	srv.StartGuests(ctx)
	srv.StartModeration(ctx)
	srv.StartPolls(ctx)
	srv.StartThreads(ctx)
	srv.StartDiscord(ctx)
	// whatever deleted accounts still own goes, now and every hour after
	srv.StartAccountSweep(ctx)
	srv.StartBilling(ctx)
	// TURN credentials revoked once their calls end, Cloudflare's measurement
	// of what calls used, and the clearing out of old call records
	srv.StartRealtime(ctx)
	srv.StartStorage(ctx)
	if warp != nil {
		go func() {
			if err := warp.Ensure(ctx); err != nil {
				log.Error("warp proxy is not running; link previews and the media proxy are refused until it is", "err", err)
			} else {
				log.Info("warp proxy ready", "port", warp.Port)
			}
			warp.Keep(ctx, time.Minute)
		}()
	}
	// what runs beside the requests, waited for at shutdown: the meter
	// writes its last minute of usage as it stops
	var loops sync.WaitGroup
	if srv.Meter != nil {
		loops.Go(func() { srv.Meter.Run(ctx, time.Minute, func(err error) { log.Warn("realtime usage", "err", err) }) })
	}
	loops.Go(func() { maintenance(ctx, log, st, mediaStore, cfg.SessionTTL) })

	httpSrv := &http.Server{Addr: cfg.Listen, Handler: srv.Handler(), ReadHeaderTimeout: 10 * time.Second, IdleTimeout: 120 * time.Second}
	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", cfg.Listen, "public_url", cfg.PublicURL, "version", version, "web_dist", cfg.WebDist, "captcha", cfg.TurnstileSecretKey != "" || cfg.HCaptchaSecretKey != "", "sfu", srv.Realtime != nil, "database", string(database.Dialect), "redis", cfg.RedisURL != "")
		errc <- httpSrv.ListenAndServe()
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-stop:
		log.Info("shutting down", "signal", sig.String())
	case err := <-errc:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Error("serve", "err", err)
			os.Exit(1)
		}
	}
	cancel()
	hub.CloseAll(websocket.StatusGoingAway, "server restarting")
	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelShutdown()
	if err := httpSrv.Shutdown(shutdownCtx); err != nil {
		log.Warn("http shutdown", "err", err)
	}
	// Shutdown returns as soon as the ordinary requests are done; it does not
	// wait for hijacked connections, and every gateway socket is one. Each of
	// them runs a tail of queries as it closes — voice cleanup, presence,
	// disconnect hooks — so closing the database first meant a handful of
	// "database is closed" errors on every restart and no WAL checkpoint.
	hub.Wait(shutdownCtx)
	srv.WaitBackground(shutdownCtx)
	waited := make(chan struct{})
	go func() {
		loops.Wait()
		close(waited)
	}()
	select {
	case <-waited:
	case <-shutdownCtx.Done():
	}
	if err := database.Close(); err != nil {
		log.Warn("database close", "err", err)
	}
	log.Info("stopped")
}

// processWarning is what startup says about a configuration that asks for a
// second chatd process, or "" when there is nothing to say.
//
// A node id is only ever needed by a second process, and a second process is
// not something chatd can be: events, presence and calls live in the memory of
// the process holding the sockets, and REDIS_URL shares nothing but the rate
// limits. Said where the operator will look, rather than letting two
// processes each quietly serve half of every conversation.
// setupEgress is the proxy for requests a person can point this server at, and
// the WARP client to keep running for it when that is what serves it. With
// EGRESS_PROXY=off both are nil and those requests go out directly.
func setupEgress(cfg config.Config, log *slog.Logger) (*egress.Proxy, *egress.Warp) {
	switch cfg.EgressProxy {
	case "off":
		log.Warn("EGRESS_PROXY=off: link previews and the media proxy show this server's address to the sites people link")
		return nil, nil
	case "warp":
		w := &egress.Warp{CLI: egress.FindWarpCLI(), Port: cfg.WarpProxyPort, Log: log}
		p, err := w.Proxy()
		if err != nil {
			log.Error("warp proxy", "err", err)
			os.Exit(1)
		}
		if w.CLI == "" {
			// still through the port, so nothing goes out directly while it is missing
			log.Error("warp-cli is not installed: link previews and the media proxy are refused until it is (run deploy/warp-proxy.sh)")
			return p, nil
		}
		return p, w
	default:
		p, err := egress.Parse(cfg.EgressProxy)
		if err != nil {
			log.Error("EGRESS_PROXY", "err", err)
			os.Exit(1)
		}
		return p, nil
	}
}

func processWarning(cfg config.Config) string {
	if cfg.NodeID == 0 {
		return ""
	}
	return "NODE_ID is set, but chatd runs as a single process: a second process would never see the first one's events, presence or calls, REDIS_URL or not. Run one chatd (deploy/README.md, \"Moving to Postgres\")."
}

// maintenance runs periodic cleanups: unreferenced blobs and expired sessions.
func maintenance(ctx context.Context, log *slog.Logger, st *store.Store, m *media.Store, sessionTTL time.Duration) {
	run := func() {
		if n, err := m.GC(ctx, time.Now().Add(-24*time.Hour).UnixMilli()); err != nil {
			log.Warn("blob gc", "err", err)
		} else if n > 0 {
			log.Info("blob gc", "removed", n)
		}
		if n, err := st.DeleteExpiredSessions(ctx, time.Now().Add(-sessionTTL).UnixMilli()); err != nil {
			log.Warn("session sweep", "err", err)
		} else if n > 0 {
			log.Info("session sweep", "removed", n)
		}
		if n, err := st.DeleteStaleUserIPs(ctx, time.Now().AddDate(0, 0, -store.UserIPRetentionDays).UnixMilli()); err != nil {
			log.Warn("address sweep", "err", err)
		} else if n > 0 {
			log.Info("address sweep", "removed", n)
		}
		// transfers nobody came back to finish, and the bytes they left behind
		if stale, err := st.StaleUploads(ctx, time.Now().Add(-24*time.Hour).UnixMilli()); err == nil && len(stale) > 0 {
			for _, id := range stale {
				m.DiscardSpool(id)
				_ = st.DeleteUpload(ctx, id)
			}
			log.Info("upload sweep", "removed", len(stale))
		}
		if n := sweepTemp(ctx, st, m.Dir, time.Now()); n > 0 {
			log.Info("temp sweep", "removed", n)
		}
	}
	t := time.NewTicker(time.Hour)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return
	case <-time.After(time.Minute):
		run()
	}
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			run()
		}
	}
}

// sweepTemp removes the temp files that requests cut short by a restart or
// crash leave in the media directory, since only the request that made them
// would have deleted them. Files still being written keep a fresh modification
// time, so only ones idle for an hour go; a resumable spool also needs its
// upload row to be gone, and gets the day a client has to come back for it.
func sweepTemp(ctx context.Context, st *store.Store, dir string, now time.Time) int {
	removed := 0
	remove := func(path string) {
		if os.Remove(path) == nil {
			removed++
		}
	}
	idle := func(path string, d time.Duration) bool {
		info, err := os.Lstat(path)
		return err == nil && info.Mode().IsRegular() && info.ModTime().Before(now.Add(-d))
	}
	entries, _ := os.ReadDir(filepath.Join(dir, "tmp"))
	for _, e := range entries {
		path := filepath.Join(dir, "tmp", e.Name())
		switch {
		case strings.HasPrefix(e.Name(), "upload-"), strings.HasPrefix(e.Name(), "piece-"):
			// a piece is staged here while its request's body arrives; one
			// left behind by a restart mid-request has nobody coming for it
			if idle(path, time.Hour) {
				remove(path)
			}
		case strings.HasPrefix(e.Name(), "resume-"):
			id, err := ids.Parse(strings.TrimPrefix(e.Name(), "resume-"))
			if err != nil || !idle(path, 24*time.Hour) {
				continue
			}
			if _, err := st.UploadByID(ctx, id); errors.Is(err, store.ErrNotFound) {
				remove(path)
			}
		}
	}
	variants, _ := filepath.Glob(filepath.Join(dir, "variants", "*", ".variant-*"))
	for _, path := range variants {
		if idle(path, time.Hour) {
			remove(path)
		}
	}
	return removed
}

// loadOrCreateSecret returns a stable random secret persisted at path. The
// secret seals every two-factor seed, so a file that exists but cannot be read
// or is too short stops startup instead of being replaced: a new secret would
// lock every 2FA user out and destroy the one that still works.
//
// A missing file is only a first start when nothing was sealed with it yet.
// sealed counts what was — the accounts with a second factor on — and when
// there are any, the database came from somewhere this file did not (a restore
// that left it behind), so startup stops and says where to find it rather than
// minting a new one that could never open theirs.
func loadOrCreateSecret(path string, sealed func() (int, error)) ([]byte, error) {
	b, err := os.ReadFile(path)
	if err == nil {
		if len(b) < 32 {
			return nil, fmt.Errorf("%s is shorter than 32 bytes; refusing to replace it", path)
		}
		return b, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	if sealed != nil {
		n, err := sealed()
		if err != nil {
			return nil, fmt.Errorf("%s is missing and the database could not say whether anything depends on it: %w", path, err)
		}
		if n > 0 {
			return nil, fmt.Errorf("%s is missing, but %d account(s) have two-factor sign-in sealed with it: put it back from the backup the database came from (backup.sh keeps it as backup/secret-*); a new one would lock them out for good", path, n)
		}
	}
	b = make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	// Written beside the real path and renamed into place, so a crash half way
	// never leaves a truncated secret behind.
	tmp := path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
	if err != nil {
		return nil, err
	}
	if _, err := f.Write(b); err != nil {
		f.Close()
		return nil, err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		return nil, err
	}
	if err := f.Close(); err != nil {
		return nil, err
	}
	if err := os.Rename(tmp, path); err != nil {
		return nil, err
	}
	// The rename itself has to reach the disk too. Without this the file's
	// contents are safe but its name may not be, so a power cut in the
	// writeback window leaves no secret at all — and the next start would
	// generate a different one, which is exactly what the comment above says
	// this is guarding against: every TOTP seed sealed under the old secret
	// becomes undecryptable.
	if dir, err := os.Open(filepath.Dir(path)); err == nil {
		_ = dir.Sync()
		dir.Close()
	}
	return b, nil
}

// setupLogging logs to stdout — the journal, under systemd — or, when file is
// set, to that file, which chatd then keeps from growing without end.
func setupLogging(level, file string) *slog.Logger {
	var lv slog.Level
	switch level {
	case "debug":
		lv = slog.LevelDebug
	case "warn":
		lv = slog.LevelWarn
	case "error":
		lv = slog.LevelError
	default:
		lv = slog.LevelInfo
	}
	var out io.Writer = os.Stdout
	var fileErr error
	if file != "" {
		if f, err := openRotating(file, logFileMax, logFileKeep); err == nil {
			out = f
		} else {
			fileErr = err
		}
	}
	log := slog.New(slog.NewJSONHandler(out, &slog.HandlerOptions{Level: lv}))
	slog.SetDefault(log)
	if fileErr != nil {
		log.Warn("log file: writing to stdout instead", "path", file, "err", fileErr)
	}
	return log
}
