package unfurl

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"image"
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	_ "golang.org/x/image/webp"
	"golang.org/x/net/html"
)

const (
	// defaultUserAgent names Nexa and where to find it. "like Twitterbot" is
	// there for the sites that only write their preview tags for the crawlers
	// they know (fxtwitter, Reddit, IMDb, TikTok, the New York Times): without
	// it they answer with an empty page or a refusal, and the card says
	// nothing. "Mozilla/5.0 (compatible; …)" is the form those sites read a
	// crawler's name from. Inside the brackets, "like Twitterbot" sent YouTube
	// to its phone site, whose tags are not in its head.
	defaultUserAgent = "Mozilla/5.0 (compatible; Nexa-Unfurl/1.0; +https://nexa.fish) like Twitterbot"
	defaultTimeout   = 6 * time.Second
	defaultMaxBytes  = 2 << 20
	mediaHeadBytes   = 64 << 10
	maxRedirects     = 3
	maxTitleRunes    = 256
	maxDescRunes     = 2048
)

// MaxMediaBytes is the largest body the media proxy passes through.
const MaxMediaBytes = 25 << 20

// ProxyImageTypes are the image types the media proxy passes through. An image
// link of any other type (HEIC, TIFF) would only show as a broken picture,
// so it gets no image preview.
var ProxyImageTypes = map[string]bool{
	"image/png": true, "image/jpeg": true, "image/gif": true, "image/webp": true,
	"image/svg+xml": true, "image/avif": true, "image/bmp": true, "image/x-icon": true, "image/vnd.microsoft.icon": true,
}

// imageAliases are the names servers give common image (and audio) types besides the
// standard one. Browsers read them all, so the proxy passes them under the
// standard name.
var imageAliases = map[string]string{
	"image/jpg": "image/jpeg", "image/pjpeg": "image/jpeg", "image/jfif": "image/jpeg", "image/pjp": "image/jpeg",
	"image/x-png": "image/png", "image/apng": "image/png", "image/vnd.mozilla.apng": "image/png",
	"image/x-ms-bmp": "image/bmp", "image/x-bmp": "image/bmp", "image/ico": "image/x-icon", "image/icon": "image/x-icon",
	"image/svg": "image/svg+xml",
	// audio the same way: Apple labels its AAC previews as protected (m4p) though they are not
	"audio/x-m4a": "audio/mp4", "audio/m4a": "audio/mp4", "audio/x-m4p": "audio/mp4", "audio/mp4a-latm": "audio/mp4", "audio/mp3": "audio/mpeg",
}

// ImageAlias is the standard name of an image type a server named another way.
func ImageAlias(ct string) string {
	if std, ok := imageAliases[ct]; ok {
		return std
	}
	return ct
}

// NeedsSniff reports whether a declared type says too little to go by: none,
// a generic one, or an image or plain-text type the proxy does not pass as
// named (file hosts label pictures text/plain or image/unknown). What such a
// file is comes from its first bytes instead.
func NeedsSniff(ct string) bool {
	switch ct {
	case "", "application/octet-stream", "binary/octet-stream", "application/binary", "application/unknown", "text/plain":
		return true
	}
	return strings.HasPrefix(ct, "image/") && !ProxyImageTypes[ct]
}

// SniffMedia is the type a file's first bytes show it to be, when that is a
// type the proxy passes, else "". SVG is never sniffed: it is only passed when
// a server says so.
func SniffMedia(peek []byte) string {
	if len(peek) >= 12 && string(peek[4:8]) == "ftyp" && (string(peek[8:12]) == "avif" || string(peek[8:12]) == "avis") {
		return "image/avif"
	}
	detected, _, _ := mime.ParseMediaType(http.DetectContentType(peek))
	if (ProxyImageTypes[detected] && detected != "image/svg+xml") || PlayableMediaType(detected) {
		return detected
	}
	return ""
}

// Result is a link preview.
type Result struct {
	URL         string `json:"url"`
	Type        string `json:"type"` // link | image | gif | video | audio | youtube | invite | profile
	Title       string `json:"title,omitempty"`
	Description string `json:"description,omitempty"`
	SiteName    string `json:"site_name,omitempty"`
	Image       string `json:"image,omitempty"`
	ImageW      int    `json:"image_w,omitempty"`
	ImageH      int    `json:"image_h,omitempty"`
	VideoID     string `json:"video_id,omitempty"`
	// VideoStart is where in a YouTube video its link asks it to start, in seconds.
	VideoStart int    `json:"video_start,omitempty"`
	Mime       string `json:"mime,omitempty"`
	Color      string `json:"color,omitempty"`
	// Author and its link, where the page names one.
	Author    string `json:"author,omitempty"`
	AuthorURL string `json:"author_url,omitempty"`
	// PublishedAt is when the page says it was published, in Unix millis.
	PublishedAt int64 `json:"published_at,omitempty"`
	// Icon is the site's own small icon, for the line above the title.
	Icon string `json:"icon,omitempty"`
	// Media is a file the client can play itself: no frame, no other site's
	// code. Kind is "video" or "audio", and MediaMime its type where known.
	Media     string `json:"media,omitempty"`
	MediaKind string `json:"media_kind,omitempty"`
	MediaMime string `json:"media_mime,omitempty"`
	MediaW    int    `json:"media_w,omitempty"`
	MediaH    int    `json:"media_h,omitempty"`
	// Player is a frame for a video, and only for a video: the one place a
	// preview runs another site's code, pinned to an address built here.
	Player     string `json:"player,omitempty"`
	PlayerSite string `json:"player_site,omitempty"`
	// InviteCode is set when the link is an invite to Nexa, so the
	// client can offer to join instead of opening a page.
	InviteCode  string `json:"invite_code,omitempty"`
	MemberCount int    `json:"member_count,omitempty"`
	OnlineCount int    `json:"online_count,omitempty"`
	// Banner is the invited server's banner, for the top of its card, or a
	// linked profile's.
	Banner string `json:"banner,omitempty"`
	// Username is set when the link is to a Nexa profile (Type "profile"),
	// so the client can draw that person's card.
	Username string `json:"username,omitempty"`
	// ImageLayout is how the page asks for its picture to be shown: "large"
	// across the card or "thumbnail" beside the words, from its twitter:card.
	// Empty leaves it to the picture's size.
	ImageLayout string `json:"image_layout,omitempty"`
	// Post is set when the link is a social post the app draws as one (see
	// post.go). The fields above still describe it, for an app that does not.
	Post *Post `json:"post,omitempty"`
	// Music is set when the link is a song, album, playlist or artist on a
	// music service (music.go), drawn as music. The fields above still
	// describe it, for an app that does not.
	Music *Music `json:"music,omitempty"`
}

// Fetcher fetches previews with SSRF protection.
type Fetcher struct {
	Timeout   time.Duration
	MaxBytes  int64
	UserAgent string
	Resolver  Resolver
	// Dial, when set, opens every connection the fetcher makes (internal/egress
	// puts them through a proxy, so the sites a person names never see this
	// server's address). It is given the address already checked to be public.
	Dial      func(ctx context.Context, network, addr string) (net.Conn, error)
	Transport http.RoundTripper
	// ParentHosts are the hosts this app is served from. Twitch's player
	// refuses to open on any other; nothing else uses them.
	ParentHosts []string
	// MeasureWithin is how long after the start of a fetch a picture of
	// unknown size may still be measured. The sender's app waits a moment
	// for a preview and then sends without it; a card with its picture's
	// size unknown beats no card at all. Zero means defaultMeasureWithin.
	MeasureWithin time.Duration
	// seen is told of every post read for a card, with its files' addresses
	// as they are today (the Service keeps them for the media proxy).
	seen func(ref *postRef, p *Post)
}

// defaultMeasureWithin leaves room inside the moment the app waits for a
// preview (1.5 s), for the round trip to this server and back.
const defaultMeasureWithin = 1200 * time.Millisecond

func (f *Fetcher) measureWithin() time.Duration {
	if f.MeasureWithin > 0 {
		return f.MeasureWithin
	}
	return defaultMeasureWithin
}

func (f *Fetcher) timeout() time.Duration {
	if f.Timeout > 0 {
		return f.Timeout
	}
	return defaultTimeout
}

func (f *Fetcher) maxBytes() int64 {
	if f.MaxBytes > 0 {
		return f.MaxBytes
	}
	return defaultMaxBytes
}

func (f *Fetcher) userAgent() string {
	if f.UserAgent != "" {
		return f.UserAgent
	}
	return defaultUserAgent
}

func (f *Fetcher) transport() http.RoundTripper {
	if f.Transport != nil {
		return f.Transport
	}
	return &http.Transport{
		Proxy:               nil,
		DialContext:         f.guardedDial,
		DisableKeepAlives:   true,
		TLSHandshakeTimeout: 5 * time.Second,
		TLSClientConfig:     &tls.Config{MinVersion: tls.VersionTLS12},
		ForceAttemptHTTP2:   true,
	}
}

// validate checks a URL syntactically and resolves its host to public addresses.
func (f *Fetcher) validate(ctx context.Context, u *url.URL) error {
	if err := checkURL(u); err != nil {
		return err
	}
	_, err := f.resolvePublic(ctx, u.Hostname())
	return err
}

// Client returns an http.Client that refuses private addresses and redirects
// away from them, for callers that need the raw bytes (the media proxy).
func (f *Fetcher) Client() *http.Client {
	return &http.Client{
		Transport: f.transport(),
		Timeout:   f.timeout(),
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > maxRedirects {
				return errors.New("too many redirects")
			}
			return checkURL(req.URL)
		},
	}
}

// StreamClient is Client without a deadline on the whole exchange, for the
// media proxy: a big GIF on a weak connection can take minutes to pass through,
// which is fine while it keeps moving. Bound its requests with Watch.
func (f *Fetcher) StreamClient() *http.Client {
	c := f.Client()
	c.Timeout = 0
	return c
}

// Watchdog cancels a request that goes the fetcher's timeout without progress:
// no response yet, or no bytes read from its body.
type Watchdog struct {
	timer *time.Timer
	idle  time.Duration
}

// Watch starts a Watchdog that calls cancel once the request stalls.
func (f *Fetcher) Watch(cancel context.CancelFunc) *Watchdog {
	d := f.timeout()
	return &Watchdog{timer: time.AfterFunc(d, cancel), idle: d}
}

// Body wraps a response body so that every read bringing bytes resets the clock.
func (w *Watchdog) Body(r io.Reader) io.Reader { return watchedReader{r: r, w: w} }

// Stop ends the watch.
func (w *Watchdog) Stop() { w.timer.Stop() }

type watchedReader struct {
	r io.Reader
	w *Watchdog
}

func (r watchedReader) Read(p []byte) (int, error) {
	n, err := r.r.Read(p)
	if n > 0 {
		r.w.timer.Reset(r.w.idle)
	}
	return n, err
}

// Validate reports whether a URL is safe to fetch (public host, http(s) only).
func (f *Fetcher) Validate(ctx context.Context, u *url.URL) error { return f.validate(ctx, u) }

// UA is the user agent the fetcher presents.
func (f *Fetcher) UA() string { return f.userAgent() }

// Fetch retrieves a preview for rawURL.
func (f *Fetcher) Fetch(ctx context.Context, rawURL string) (*Result, error) {
	u, err := url.Parse(strings.TrimSpace(rawURL))
	if err != nil {
		return nil, ErrBlocked
	}
	if err := f.validate(ctx, u); err != nil {
		return nil, err
	}
	started := time.Now()
	ctx, cancel := context.WithTimeout(ctx, f.timeout())
	defer cancel()

	// A social post has a card of its own. A post no source could read is
	// fetched like any other link, for the card its page asks for.
	if r := f.postResult(ctx, u); r != nil {
		return r, nil
	}
	// So has music. A link no service could read is fetched like any other.
	if r := f.musicResult(ctx, u); r != nil {
		return r, nil
	}

	client := &http.Client{
		Transport: f.transport(),
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > maxRedirects {
				return errors.New("unfurl: too many redirects")
			}
			return f.validate(req.Context(), req.URL)
		},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return nil, ErrBlocked
	}
	req.Header.Set("User-Agent", f.userAgent())
	req.Header.Set("Accept", "text/html,image/*,video/*,audio/*,*/*;q=0.5")
	req.Header.Set("Accept-Language", "en")
	res, err := client.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) && ue.Err != nil {
			return nil, ue.Err
		}
		return nil, err
	}
	defer res.Body.Close()

	final := res.Request.URL
	switch {
	case res.StatusCode >= 200 && res.StatusCode < 300:
	case res.StatusCode >= 500:
		return nil, fmt.Errorf("unfurl: upstream status %d", res.StatusCode)
	default:
		return nil, ErrUnsupported
	}
	ct, _, _ := mime.ParseMediaType(res.Header.Get("Content-Type"))
	ct = strings.ToLower(ct)
	var body io.Reader = res.Body
	// File hosts sometimes omit the type or give a wrong one. Judge only the
	// bounded file header, never its extension, and keep those bytes for the
	// image reader below.
	ct = ImageAlias(ct)
	if NeedsSniff(ct) {
		head := bufio.NewReaderSize(res.Body, 512)
		peek, _ := head.Peek(512)
		if detected := SniffMedia(peek); detected != "" {
			ct = detected
		}
		body = head
	}
	switch {
	case strings.HasPrefix(ct, "image/"):
		// the preview is shown through the media proxy, so only what it passes on
		if !ProxyImageTypes[ct] || res.ContentLength > MaxMediaBytes {
			return nil, ErrUnsupported
		}
		return f.mediaResult(body, final, ct, "image")
	case playableVideo[ct]:
		return &Result{URL: final.String(), Type: "video", Mime: ct}, nil
	case playableAudio[ct]:
		return &Result{URL: final.String(), Type: "audio", Mime: ct, Media: final.String(), MediaKind: "audio", MediaMime: ct}, nil
	case ct == "text/html" || ct == "application/xhtml+xml":
		return f.htmlResult(ctx, started, body, final)
	}
	return nil, ErrUnsupported
}

// mediaResult reads only the head of an image to learn its dimensions.
func (f *Fetcher) mediaResult(body io.Reader, final *url.URL, ct, typ string) (*Result, error) {
	head := make([]byte, mediaHeadBytes)
	n, _ := io.ReadFull(body, head)
	head = head[:n]
	r := &Result{URL: final.String(), Type: typ, Mime: ct, Image: final.String()}
	if ct == "image/gif" || strings.HasSuffix(strings.ToLower(final.Path), ".gif") {
		r.Type = "gif"
		r.Mime = "image/gif"
	}
	if cfg, _, err := image.DecodeConfig(bytes.NewReader(head)); err == nil {
		r.ImageW, r.ImageH = cfg.Width, cfg.Height
	}
	return r, nil
}

var headCloseRe = regexp.MustCompile(`(?i)</head\s*>`)

// htmlResult parses metadata from a bounded read of the page.
func (f *Fetcher) htmlResult(ctx context.Context, started time.Time, body io.Reader, final *url.URL) (*Result, error) {
	max := f.maxBytes()
	buf, err := io.ReadAll(io.LimitReader(body, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(buf)) > max {
		buf = buf[:max]
		if !headCloseRe.Match(buf) {
			return nil, ErrTooLarge
		}
	}
	meta := parseMeta(buf)
	if r := tenorGif(final, meta); r != nil {
		return r, nil
	}
	r := &Result{URL: final.String(), Type: "link"}
	r.Title = clip(decoded(meta.first("og:title", "twitter:title", "html:title")), maxTitleRunes)
	r.Description = clip(decoded(meta.text("og:description", "twitter:description", "description")), maxDescRunes)
	r.SiteName = clip(decoded(meta.first("og:site_name")), maxTitleRunes)
	if img := meta.first("og:image", "og:image:secure_url", "og:image:url"); img != "" {
		if abs := resolveImage(final, img); abs != "" {
			r.Image = abs
			r.ImageW, _ = strconv.Atoi(meta.first("og:image:width"))
			r.ImageH, _ = strconv.Atoi(meta.first("og:image:height"))
		}
	}
	if r.Image == "" {
		if img := meta.first("twitter:image", "twitter:image:src"); img != "" {
			r.Image = resolveImage(final, img)
		}
	}
	switch strings.ToLower(meta.first("twitter:card")) {
	case "summary_large_image", "player":
		r.ImageLayout = "large"
	case "summary", "app":
		r.ImageLayout = "thumbnail"
	}
	r.Color = themeColor(meta.first("theme-color"))
	// with no name of its own, a site goes by its address as people say it
	host := strings.TrimPrefix(strings.ToLower(final.Hostname()), "www.")
	if r.SiteName == "" {
		r.SiteName = host
	}
	r.Author = clip(decoded(meta.first("article:author", "og:article:author", "author", "twitter:creator")), maxTitleRunes)
	// An author given as a link belongs in author_url, not on the card as text.
	if strings.HasPrefix(r.Author, "http://") || strings.HasPrefix(r.Author, "https://") {
		r.AuthorURL, r.Author = r.Author, ""
	}
	// The site's own account ("@github" on GitHub) is not who wrote the page,
	// and says nothing the site's name beside it does not.
	if site := meta.first("twitter:site"); site != "" && strings.EqualFold(r.Author, site) {
		r.Author = ""
	}
	if u := meta.first("article:author:url", "og:article:author:url"); u != "" {
		r.AuthorURL = resolveImage(final, u)
	}
	r.PublishedAt = parseTime(meta.first("article:published_time", "og:article:published_time", "article:modified_time", "date", "pubdate"))
	if icon := meta.first("link:icon"); icon != "" {
		r.Icon = resolveImage(final, icon)
	}
	// A file the client can play by itself: no frame, nobody else's code.
	if v := meta.first("og:video:secure_url", "og:video:url", "og:video"); v != "" {
		if mime := strings.ToLower(meta.first("og:video:type")); playableVideo[mime] {
			if abs := resolveImage(final, v); abs != "" {
				r.Media, r.MediaKind, r.MediaMime = abs, "video", mime
				r.MediaW, _ = strconv.Atoi(meta.first("og:video:width"))
				r.MediaH, _ = strconv.Atoi(meta.first("og:video:height"))
			}
		}
	}
	if r.Media == "" {
		if a := meta.first("og:audio:secure_url", "og:audio:url", "og:audio"); a != "" {
			if mime := strings.ToLower(meta.first("og:audio:type")); playableAudio[mime] {
				if abs := resolveImage(final, a); abs != "" {
					r.Media, r.MediaKind, r.MediaMime = abs, "audio", mime
				}
			}
		}
	}
	// The one place a preview runs another site's code, and only for a video.
	if p := PlayerFor(final, f.ParentHosts...); p != nil {
		r.Player, r.PlayerSite = p.URL, p.Site
		if r.SiteName == "" || r.SiteName == host {
			r.SiteName = p.Site
		}
	}
	if id := YouTubeID(final); id != "" {
		r.Type = "youtube"
		r.VideoID = id
		if r.Image == "" {
			// the one picture every video has, at the size it always has
			r.Image = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
			r.ImageW, r.ImageH = 480, 360
		}
	}
	if r.Title == "" && r.Description == "" && r.Image == "" && r.Player == "" && r.Media == "" {
		return nil, ErrUnsupported
	}
	// A picture of unknown size is measured before the card goes out, if there
	// is time: the card is laid out before the picture arrives, and a guess
	// moved everything under it once it did. A thumbnail is cropped to a
	// square whatever its size.
	if r.Image != "" && (r.ImageW <= 0 || r.ImageH <= 0) && r.ImageLayout != "thumbnail" {
		if left := f.measureWithin() - time.Since(started); left > 0 {
			mctx, cancel := context.WithTimeout(ctx, left)
			r.ImageW, r.ImageH = f.imageSize(mctx, r.Image)
			cancel()
		}
	}
	return r, nil
}

// imageSize reads the head of a picture for its size, or gives 0, 0. It goes
// through the same guard as the page, and stops at the first 64 KiB.
func (f *Fetcher) imageSize(ctx context.Context, raw string) (int, int) {
	u, err := url.Parse(raw)
	if err != nil || f.validate(ctx, u) != nil {
		return 0, 0
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u.String(), nil)
	if err != nil {
		return 0, 0
	}
	req.Header.Set("User-Agent", f.userAgent())
	req.Header.Set("Accept", "image/*")
	req.Header.Set("Range", fmt.Sprintf("bytes=0-%d", mediaHeadBytes-1))
	res, err := f.Client().Do(req)
	if err != nil {
		return 0, 0
	}
	defer res.Body.Close()
	if res.StatusCode != http.StatusOK && res.StatusCode != http.StatusPartialContent {
		return 0, 0
	}
	cfg, _, err := image.DecodeConfig(io.LimitReader(res.Body, mediaHeadBytes))
	if err != nil || cfg.Width <= 0 || cfg.Height <= 0 {
		return 0, 0
	}
	return cfg.Width, cfg.Height
}

// Files a browser plays without help; anything else would need a plugin or a
// frame, and a preview gets neither.
var playableVideo = map[string]bool{"video/mp4": true, "video/webm": true, "video/ogg": true, "video/quicktime": true}
var playableAudio = map[string]bool{"audio/mpeg": true, "audio/mp4": true, "audio/ogg": true, "audio/wav": true, "audio/webm": true, "audio/aac": true, "audio/flac": true, "audio/x-wav": true, "audio/x-flac": true}

// PlayableMediaType is shared by preview fetching and the media proxy so a
// file offered to a player is also allowed through its signed proxy link.
func PlayableMediaType(ct string) bool { return playableVideo[ct] || playableAudio[ct] }

var timeLayouts = []string{time.RFC3339, "2006-01-02T15:04:05Z0700", "2006-01-02T15:04:05", "2006-01-02 15:04:05", "2006-01-02"}

// parseTime reads the date forms pages put in their metadata, in Unix millis.
func parseTime(raw string) int64 {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	for _, layout := range timeLayouts {
		if t, err := time.Parse(layout, raw); err == nil {
			// a year far from now is a typo or a placeholder, not a date
			if y := t.Year(); y < 1990 || y > 2100 {
				return 0
			}
			return t.UnixMilli()
		}
	}
	return 0
}

// metaSet holds each tag's value as the page wrote it.
type metaSet map[string]string

// first is the first of keys the page gives a value for, on one line.
func (m metaSet) first(keys ...string) string {
	for _, k := range keys {
		if v := collapse(m[k]); v != "" {
			return v
		}
	}
	return ""
}

// text is like first, with the value's line breaks kept (see paragraphs).
func (m metaSet) text(keys ...string) string {
	for _, k := range keys {
		if v := paragraphs(m[k]); v != "" {
			return v
		}
	}
	return ""
}

// named reports whether the tags so far say what the page is.
func (m metaSet) named() bool {
	return m.first("og:title", "twitter:title", "og:description", "twitter:description") != ""
}

// parseMeta walks the document collecting <meta> and <title> values. They
// live in <head>, so it stops where the body starts, unless the head named
// nothing: some pages (YouTube's phone site) write their tags in the body.
func parseMeta(buf []byte) metaSet {
	out := metaSet{}
	z := html.NewTokenizer(bytes.NewReader(buf))
	inTitle := false
	inBody := false
	var title strings.Builder
	for {
		tt := z.Next()
		switch tt {
		case html.ErrorToken:
			if _, ok := out["html:title"]; !ok && title.Len() > 0 {
				out["html:title"] = title.String()
			}
			return out
		case html.StartTagToken, html.SelfClosingTagToken:
			name, hasAttr := z.TagName()
			switch string(name) {
			case "title":
				// a title in the body is an icon's or a picture's, not the page's
				inTitle = tt == html.StartTagToken && !inBody
			case "meta":
				var key, content, prop, nm string
				for hasAttr {
					var k, v []byte
					k, v, hasAttr = z.TagAttr()
					switch strings.ToLower(string(k)) {
					case "property":
						prop = strings.ToLower(string(v))
					case "name":
						nm = strings.ToLower(string(v))
					case "content":
						content = string(v)
					}
				}
				if prop != "" {
					key = prop
				} else {
					key = nm
				}
				if key != "" && content != "" {
					if _, seen := out[key]; !seen {
						out[key] = content
					}
				}
			case "link":
				var rel, href, typ string
				for hasAttr {
					var k, v []byte
					k, v, hasAttr = z.TagAttr()
					switch strings.ToLower(string(k)) {
					case "rel":
						rel = strings.ToLower(string(v))
					case "href":
						href = string(v)
					case "type":
						typ = strings.ToLower(string(v))
					}
				}
				// The proxy passes no SVG, so an SVG icon only ever showed as a
				// broken picture; the PNG most such sites name beside it
				// ("alternate icon") is taken instead.
				if href == "" || strings.Contains(typ, "svg") || svgPath(href) {
					break
				}
				icon := false
				for _, t := range strings.Fields(rel) {
					icon = icon || t == "icon"
				}
				// the sharpest icon named wins; apple's is a good large one
				switch {
				case strings.Contains(rel, "apple-touch-icon"):
					out["link:icon"] = href
				case icon:
					if _, seen := out["link:icon"]; !seen {
						out["link:icon"] = href
					}
				}
			case "body":
				// metadata lives in <head>; stop once the body starts unless the title is still open
				if !inTitle {
					if _, ok := out["html:title"]; !ok && title.Len() > 0 {
						out["html:title"] = title.String()
					}
					if out.named() {
						return out
					}
					inBody = true
				}
			}
		case html.EndTagToken:
			name, _ := z.TagName()
			if string(name) == "title" && inTitle {
				inTitle = false
				if _, ok := out["html:title"]; !ok {
					out["html:title"] = title.String()
				}
			}
		case html.TextToken:
			if inTitle {
				title.Write(z.Text())
			}
		}
	}
}

// svgPath reports whether an address names an SVG file.
func svgPath(href string) bool {
	path, _, _ := strings.Cut(href, "#")
	path, _, _ = strings.Cut(path, "?")
	return strings.HasSuffix(strings.ToLower(path), ".svg")
}

var spaceRe = regexp.MustCompile(`\s+`)

func collapse(s string) string {
	return strings.TrimSpace(spaceRe.ReplaceAllString(s, " "))
}

// paragraphs keeps a description's line breaks: a post says what it says line
// by line. A break followed by indentation is only the page's own source,
// wrapped for whoever edits it, and joins the lines it splits. Spaces inside
// a line collapse, and paragraphs keep one blank line between them.
func paragraphs(s string) string {
	s = strings.ReplaceAll(strings.ReplaceAll(s, "\r\n", "\n"), "\r", "\n")
	var b strings.Builder
	blank := false
	for i, line := range strings.Split(s, "\n") {
		wrapped := i > 0 && (strings.HasPrefix(line, "  ") || strings.HasPrefix(line, "\t"))
		line = collapse(line)
		if line == "" {
			blank = b.Len() > 0
			continue
		}
		if b.Len() > 0 {
			switch {
			case blank:
				b.WriteString("\n\n")
			case wrapped:
				b.WriteByte(' ')
			default:
				b.WriteByte('\n')
			}
		}
		blank = false
		b.WriteString(line)
	}
	return b.String()
}

var entityRe = regexp.MustCompile(`&(?:[a-zA-Z][a-zA-Z0-9]{1,31}|#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6});`)

// decoded undoes escaping a page did twice. The parser has undone it once, so
// an "&quot;" still standing in the words means the page escaped them again,
// and the card would print it as it is.
func decoded(s string) string {
	if !strings.Contains(s, "&") || !entityRe.MatchString(s) {
		return s
	}
	return html.UnescapeString(s)
}

func clip(s string, max int) string {
	if utf8.RuneCountInString(s) <= max {
		return s
	}
	r := []rune(s)
	return string(r[:max])
}

func resolveImage(base *url.URL, raw string) string {
	ref, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	abs := base.ResolveReference(ref)
	if abs.Scheme != "http" && abs.Scheme != "https" || abs.Host == "" {
		return ""
	}
	return abs.String()
}

var hexColorRe = regexp.MustCompile(`^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$`)

func themeColor(raw string) string {
	raw = strings.TrimSpace(raw)
	m := hexColorRe.FindStringSubmatch(raw)
	if m == nil {
		return ""
	}
	h := strings.ToLower(m[1])
	if len(h) == 3 {
		h = string([]byte{h[0], h[0], h[1], h[1], h[2], h[2]})
	}
	return "#" + h
}
