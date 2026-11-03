// Package config loads chatd's configuration from the environment (optionally
// pre-populated from a KEY=VALUE file) with validated defaults.
package config

import (
	"bufio"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/ids"
)

// Config is the complete runtime configuration of chatd.
type Config struct {
	// MaintenanceFile is an optional host-written reboot deadline, never a reboot command.
	MaintenanceFile string
	Listen          string
	// PublicURL is Nexa's own address: what its links, previews and the
	// desktop app's updates name.
	PublicURL string
	// AltURLs are other addresses the same Nexa is reached at, every one of
	// them working in full (ALT_URLS, comma-separated).
	AltURLs []string
	// MovedFrom are addresses Nexa used to be at (MOVED_FROM). They keep
	// working for whatever still calls them, the installed desktop apps and
	// webhooks among them, while a browser that opens one is sent on to the
	// same page at PublicURL, where it signs in again: a sign-in never moves
	// between addresses.
	MovedFrom []string
	DataDir   string
	WebDist   string
	// DatabaseURL points at a Postgres database (postgres://…). Empty keeps
	// the SQLite file in DataDir, which is what one machine wants.
	DatabaseURL string
	// RedisURL points at a Redis (redis://…) that the rate limits count in,
	// so a restart does not hand everyone a fresh allowance. It does not make
	// several chatd processes one Nexa: gateway events, presence and calls
	// stay inside the process that has the sockets, so run one chatd.
	RedisURL string
	// DesktopDir holds published desktop app installers and update manifests,
	// served at /desktop/. It defaults to "desktop" inside DataDir.
	DesktopDir string
	// AndroidDir holds the published Android app: its APKs and manifest.json,
	// served at /android/. Unlike the desktop builds they are committed to the
	// repository (tools/android_release.py writes them), so a git pull is all
	// it takes to offer a new version. It defaults to "downloads/android" in
	// the working directory, which is the repository chatd runs from.
	AndroidDir      string
	MaxUploadBytes  int64
	MaxAvatarBytes  int64
	MaxEmojiBytes   int64
	MaxStickerBytes int64
	Registration    string // open | invite | closed
	RegistrationKey string // when Registration == "invite": a shared key accepted at register time
	AdminUsername   string
	// StunURLs are the STUN servers handed to clients. "none" in STUN_URLS
	// turns them off, which is what a LAN-only setup wants.
	StunURLs       []string
	TurnURLs       []string
	TurnUsername   string
	TurnCredential string
	// TurnKeyID and TurnAPIToken turn on Cloudflare Realtime TURN, where this
	// server mints a short-lived credential for each person who joins a call.
	// The relay is billed by the gigabyte and is only used when two people
	// cannot reach each other directly.
	TurnKeyID    string
	TurnAPIToken string
	// KlipyAPIKey enables GIF search. Without it the picker still offers
	// whatever has been favourited.
	KlipyAPIKey string
	// IGDBClientID and IGDBClientSecret (a Twitch developer application's)
	// turn on searching games for profile widgets (internal/games). Without
	// them people add games by name with a picture of their own.
	IGDBClientID     string
	IGDBClientSecret string
	// TMDBAPIKey (a TMDB v4 Read Access Token) turns on searching shows and
	// films for profile widgets (internal/screen). Without it people add them
	// by name with a poster of their own.
	TMDBAPIKey string
	// TelegramBotToken enables Telegram sticker pack imports. The token never
	// leaves this server: the client only ever sends a pack URL.
	TelegramBotToken string
	// DiscordClientID, DiscordClientSecret and DiscordBotToken turn on showing
	// people's Discord activity (internal/discordsync): all three, or it is off.
	// DiscordGuildID names the Discord server the bot watches presence in;
	// empty, it is the bot's only server.
	DiscordClientID     string
	DiscordClientSecret string
	DiscordBotToken     string
	DiscordGuildID      string
	// DiscordAPIURL is Discord's REST API; only end-to-end tests point it at a fake.
	DiscordAPIURL string

	// Subscriptions (api/billing.go) are on once both Stripe keys are set:
	// the secret key and the webhook endpoint's signing secret. StripeAPIURL
	// is Stripe's API; only tests point it at a fake.
	StripeSecretKey     string
	StripeWebhookSecret string
	StripeAPIURL        string
	// DiscordRedirectURL names the callback Discord sends people back through
	// after linking, outright. Empty, as it is meant to be, the callback is
	// chosen from the ones listed under OAuth2, Redirects in the Developer
	// Portal (discordsync.redirectFor): the one under the address the person
	// started at, else PublicURL's, else another of this Nexa's addresses.
	DiscordRedirectURL string
	// TurnstileSiteKey and TurnstileSecretKey turn on Cloudflare Turnstile
	// captchas for the actions bots abuse. HCaptchaSiteKey and
	// HCaptchaSecretKey keep hCaptcha working beside it for apps from before
	// the move back to Turnstile. All are free to get; with neither pair
	// nothing asks for a captcha.
	TurnstileSiteKey   string
	TurnstileSecretKey string
	HCaptchaSiteKey    string
	HCaptchaSecretKey  string
	// RealtimeAppID and RealtimeAppSecret send every call through Cloudflare
	// Realtime SFU, so nobody in a call learns another person's address.
	// Without them calls go peer to peer.
	RealtimeAppID     string
	RealtimeAppSecret string
	// RealtimeMonthlyGB is the free Realtime allowance per month, which SFU
	// and TURN egress share. Video pauses near it and calls stop just short
	// of it, so nothing is ever billed.
	RealtimeMonthlyGB int64
	// RealtimeAccountID and RealtimeAnalyticsToken (an API token that can
	// read the account's analytics) let the meter use what Cloudflare
	// measured instead of its own estimate: the month's SFU and TURN egress,
	// and each account's share of it for the staff's Calls page.
	RealtimeAccountID      string
	RealtimeAnalyticsToken string
	// RealtimeTURNDailyGB is the most TURN relay one account may use in a
	// UTC day, as Cloudflare measures it, before its relay is cut off until
	// the next day.
	RealtimeTURNDailyGB int64
	// GuestLinksP2P lets guest links work on a server whose calls go peer to
	// peer. There a link's code alone admits someone who can hear the call,
	// with no key in it to protect, and guests learn members' addresses, so
	// it is off unless asked for (development and tests).
	GuestLinksP2P bool
	// EgressProxy is where the requests a person can point this server at
	// (link previews, the media proxy, the YouTube player) go out, so the
	// sites they name never learn this server's address: "warp" (Cloudflare
	// WARP's local proxy, which chatd keeps in proxy mode and connected),
	// a "socks5://host:port", or "off" to send them out directly.
	EgressProxy string
	// WarpProxyPort is the port WARP's proxy listens on, on 127.0.0.1.
	WarpProxyPort int
	LogLevel      string
	// LogFile, when set, is where chatd writes its log instead of stdout,
	// starting a new file past 10 MB and keeping five old ones. It is for a
	// service manager that appends stdout to a file and never rotates it
	// (launchd); under systemd the journal already does this.
	LogFile    string
	SessionTTL time.Duration
	// NodeID would tell processes sharing one database apart in the ids they
	// hand out, 0–1023. chatd runs as a single process, so it stays 0
	// and startup warns when it is not.
	NodeID int
}

// Load builds a Config from getenv, applying defaults and validating values.
func Load(getenv func(string) string) (Config, error) {
	c := Config{
		Listen:              "127.0.0.1:8090",
		PublicURL:           "https://nexa.fish",
		DataDir:             "./data",
		WebDist:             "./web/dist",
		MaxUploadBytes:      104857600,
		MaxAvatarBytes:      10 << 20,
		MaxEmojiBytes:       2 << 20,
		MaxStickerBytes:     5 << 20,
		Registration:        "open",
		RealtimeMonthlyGB:   1000,
		RealtimeTURNDailyGB: 10,
		EgressProxy:         "warp",
		WarpProxyPort:       40000,
		LogLevel:            "info",
		SessionTTL:          90 * 24 * time.Hour,
	}
	str := func(k string, dst *string) {
		if v := strings.TrimSpace(getenv(k)); v != "" {
			*dst = v
		}
	}
	str("MAINTENANCE_FILE", &c.MaintenanceFile)
	str("LISTEN", &c.Listen)
	str("PUBLIC_URL", &c.PublicURL)
	str("DATA_DIR", &c.DataDir)
	str("DATABASE_URL", &c.DatabaseURL)
	str("REDIS_URL", &c.RedisURL)
	str("WEB_DIST", &c.WebDist)
	str("DESKTOP_DIR", &c.DesktopDir)
	if c.DesktopDir == "" {
		c.DesktopDir = filepath.Join(c.DataDir, "desktop")
	}
	str("ANDROID_DIR", &c.AndroidDir)
	if c.AndroidDir == "" {
		c.AndroidDir = filepath.Join("downloads", "android")
	}
	str("REGISTRATION", &c.Registration)
	str("REGISTRATION_KEY", &c.RegistrationKey)
	str("ADMIN_USERNAME", &c.AdminUsername)
	str("TURN_USERNAME", &c.TurnUsername)
	str("TURN_CREDENTIAL", &c.TurnCredential)
	str("TURN_KEY_ID", &c.TurnKeyID)
	str("TURN_API_TOKEN", &c.TurnAPIToken)
	str("KLIPY_API_KEY", &c.KlipyAPIKey)
	str("IGDB_CLIENT_ID", &c.IGDBClientID)
	str("IGDB_CLIENT_SECRET", &c.IGDBClientSecret)
	str("TMDB_API_KEY", &c.TMDBAPIKey)
	str("TELEGRAM_BOT_TOKEN", &c.TelegramBotToken)
	str("DISCORD_CLIENT_ID", &c.DiscordClientID)
	str("DISCORD_CLIENT_SECRET", &c.DiscordClientSecret)
	str("DISCORD_BOT_TOKEN", &c.DiscordBotToken)
	str("DISCORD_ACTIVITY_GUILD_ID", &c.DiscordGuildID)
	str("DISCORD_API_URL", &c.DiscordAPIURL)
	str("DISCORD_REDIRECT_URL", &c.DiscordRedirectURL)
	str("STRIPE_SECRET_KEY", &c.StripeSecretKey)
	str("STRIPE_WEBHOOK_SECRET", &c.StripeWebhookSecret)
	str("STRIPE_API_URL", &c.StripeAPIURL)
	str("TURNSTILE_SITE_KEY", &c.TurnstileSiteKey)
	str("TURNSTILE_SECRET_KEY", &c.TurnstileSecretKey)
	str("HCAPTCHA_SITE_KEY", &c.HCaptchaSiteKey)
	str("HCAPTCHA_SECRET_KEY", &c.HCaptchaSecretKey)
	str("REALTIME_APP_ID", &c.RealtimeAppID)
	str("REALTIME_APP_SECRET", &c.RealtimeAppSecret)
	str("REALTIME_ACCOUNT_ID", &c.RealtimeAccountID)
	str("REALTIME_ANALYTICS_TOKEN", &c.RealtimeAnalyticsToken)
	str("EGRESS_PROXY", &c.EgressProxy)
	str("LOG_LEVEL", &c.LogLevel)
	str("LOG_FILE", &c.LogFile)
	c.GuestLinksP2P = strings.TrimSpace(getenv("GUEST_LINKS_P2P")) == "1"
	// Usernames are stored lowercase, so a name written with capitals would
	// otherwise never match the account it means.
	c.AdminUsername = strings.ToLower(c.AdminUsername)

	ints := []struct {
		k   string
		dst *int64
	}{
		{"MAX_UPLOAD_BYTES", &c.MaxUploadBytes},
		{"MAX_AVATAR_BYTES", &c.MaxAvatarBytes},
		{"MAX_EMOJI_BYTES", &c.MaxEmojiBytes},
		{"MAX_STICKER_BYTES", &c.MaxStickerBytes},
		{"REALTIME_MONTHLY_GB", &c.RealtimeMonthlyGB},
		{"REALTIME_TURN_DAILY_GB", &c.RealtimeTURNDailyGB},
	}
	for _, kv := range ints {
		v := strings.TrimSpace(getenv(kv.k))
		if v == "" {
			continue
		}
		n, err := strconv.ParseInt(v, 10, 64)
		if err != nil || n <= 0 {
			return c, fmt.Errorf("%s: expected a positive integer, got %q", kv.k, v)
		}
		*kv.dst = n
	}
	if v := strings.TrimSpace(getenv("NODE_ID")); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 0 || n > ids.MaxNode {
			return c, fmt.Errorf("NODE_ID: expected 0..%d, got %q", ids.MaxNode, v)
		}
		c.NodeID = n
	}
	if v := strings.TrimSpace(getenv("WARP_PROXY_PORT")); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 65535 {
			return c, fmt.Errorf("WARP_PROXY_PORT: expected 1..65535, got %q", v)
		}
		c.WarpProxyPort = n
	}
	switch ep := strings.ToLower(c.EgressProxy); {
	case ep == "warp", ep == "off":
		c.EgressProxy = ep
	case strings.HasPrefix(ep, "socks5://"), strings.HasPrefix(ep, "socks5h://"):
	default:
		return c, fmt.Errorf("EGRESS_PROXY: expected warp, off or socks5://host:port, got %q", c.EgressProxy)
	}
	if v := strings.TrimSpace(getenv("SESSION_TTL_DAYS")); v != "" {
		n, err := strconv.Atoi(v)
		// A hundred years is "forever"; much larger overflows the duration and
		// turns into a TTL that signs everyone out.
		if err != nil || n <= 0 || n > 36500 {
			return c, fmt.Errorf("SESSION_TTL_DAYS: expected 1..36500, got %q", v)
		}
		c.SessionTTL = time.Duration(n) * 24 * time.Hour
	}
	c.StunURLs = []string{"stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"}
	if v := strings.TrimSpace(getenv("STUN_URLS")); v != "" {
		c.StunURLs = nil
		if !strings.EqualFold(v, "none") {
			for _, u := range strings.Split(v, ",") {
				if u = strings.TrimSpace(u); u != "" {
					c.StunURLs = append(c.StunURLs, u)
				}
			}
		}
	}
	if v := getenv("TURN_URLS"); v != "" {
		for _, u := range strings.Split(v, ",") {
			if u = strings.TrimSpace(u); u != "" {
				c.TurnURLs = append(c.TurnURLs, u)
			}
		}
	}
	for _, list := range []struct {
		key string
		dst *[]string
	}{{"ALT_URLS", &c.AltURLs}, {"MOVED_FROM", &c.MovedFrom}} {
		for _, u := range strings.Split(getenv(list.key), ",") {
			if u = strings.TrimRight(strings.TrimSpace(u), "/"); u == "" {
				continue
			}
			if o, err := url.Parse(u); err != nil || (o.Scheme != "http" && o.Scheme != "https") || o.Host == "" || (o.Path != "" && o.Path != "/") {
				return c, fmt.Errorf("%s: each address is a scheme and a host, like https://example.com, got %q", list.key, u)
			}
			*list.dst = append(*list.dst, u)
		}
	}
	switch c.Registration {
	case "open", "invite", "closed":
	default:
		return c, fmt.Errorf("REGISTRATION: expected open|invite|closed, got %q", c.Registration)
	}
	switch c.LogLevel {
	case "debug", "info", "warn", "error":
	default:
		return c, fmt.Errorf("LOG_LEVEL: expected debug|info|warn|error, got %q", c.LogLevel)
	}
	// With one key and not the other captchas would be silently off.
	if (c.TurnstileSiteKey == "") != (c.TurnstileSecretKey == "") {
		return c, fmt.Errorf("TURNSTILE_SITE_KEY and TURNSTILE_SECRET_KEY: set both to turn captchas on, or neither")
	}
	if (c.HCaptchaSiteKey == "") != (c.HCaptchaSecretKey == "") {
		return c, fmt.Errorf("HCAPTCHA_SITE_KEY and HCAPTCHA_SECRET_KEY: set both to turn the hCaptcha fallback on, or neither")
	}
	// With one and not the other calls would quietly go peer to peer.
	if (c.RealtimeAppID == "") != (c.RealtimeAppSecret == "") {
		return c, fmt.Errorf("REALTIME_APP_ID and REALTIME_APP_SECRET: set both to send calls through Cloudflare Realtime, or neither")
	}
	c.PublicURL = strings.TrimRight(c.PublicURL, "/")
	if !strings.HasPrefix(c.PublicURL, "http://") && !strings.HasPrefix(c.PublicURL, "https://") {
		return c, fmt.Errorf("PUBLIC_URL: must start with http:// or https://, got %q", c.PublicURL)
	}
	if c.DiscordRedirectURL != "" && !strings.HasPrefix(c.DiscordRedirectURL, "https://") && !strings.HasPrefix(c.DiscordRedirectURL, "http://") {
		return c, fmt.Errorf("DISCORD_REDIRECT_URL: must start with http:// or https://, got %q", c.DiscordRedirectURL)
	}
	// an address in two lists would be both moved and not
	seen := map[string]string{}
	for _, l := range []struct {
		key  string
		urls []string
	}{{"PUBLIC_URL", []string{c.PublicURL}}, {"ALT_URLS", c.AltURLs}, {"MOVED_FROM", c.MovedFrom}} {
		for _, u := range l.urls {
			h := strings.ToLower(hostOf(u))
			if prev, dup := seen[h]; dup {
				return c, fmt.Errorf("%s: %s is already in %s", l.key, h, prev)
			}
			seen[h] = l.key
		}
	}
	return c, nil
}

func hostOf(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	return u.Host
}

// Origins are every address this Nexa answers at as itself: PublicURL first,
// then AltURLs, then MovedFrom.
func (c Config) Origins() []string {
	out := []string{c.PublicURL}
	out = append(out, c.AltURLs...)
	return append(out, c.MovedFrom...)
}

// LoadEnvFile parses KEY=VALUE lines. Blank lines and # comments are skipped,
// a leading "export " is ignored, and single or double quotes around a value
// are removed.
func LoadEnvFile(path string) (map[string]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	m := map[string]string{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		k, v, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		k, v = strings.TrimSpace(k), strings.TrimSpace(v)
		if len(v) >= 2 && ((v[0] == '"' && v[len(v)-1] == '"') || (v[0] == '\'' && v[len(v)-1] == '\'')) {
			v = v[1 : len(v)-1]
		}
		m[k] = v
	}
	return m, sc.Err()
}
