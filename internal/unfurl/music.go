package unfurl

// Music links, drawn as music: a song, album, playlist, artist or podcast
// episode on Spotify, Apple Music, Deezer or SoundCloud, with its cover, who
// made it and how long it runs, in a card the app draws itself.
//
// How it plays depends on what each service allows:
//   - Deezer and Apple Music let anyone hear the thirty seconds they publish
//     for each song, so the app plays those in its own player. Deezer's come
//     through /media/music/preview (its links run out within hours), Apple's
//     through the media proxy.
//   - Spotify and SoundCloud ask that their audio be played only in their own
//     player, so the card opens that player, served through this server
//     (internal/api/music_players.go) so neither sees who is listening.
//
// Everything is read from the services' public endpoints: Deezer's and
// Apple's search APIs, SoundCloud's oEmbed, and the data Spotify's own embed
// page carries.

import (
	"context"
	"encoding/json"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// Music is a music link's card.
type Music struct {
	// Platform is "spotify", "apple", "deezer" or "soundcloud".
	Platform string `json:"platform"`
	// Kind is "track", "album", "playlist", "artist", "episode" or "show".
	Kind string `json:"kind"`
	// ID is the service's own, as its player is opened with: Spotify's base62
	// id, SoundCloud's number.
	ID     string `json:"id"`
	Title  string `json:"title"`
	Artist string `json:"artist,omitempty"`
	// Album is the album a track is on.
	Album string `json:"album,omitempty"`
	// Art is the cover, a proxy address once it leaves the server.
	Art string `json:"art,omitempty"`
	// Duration is in seconds.
	Duration int `json:"duration,omitempty"`
	// Count is how many tracks an album or playlist holds.
	Count int `json:"count,omitempty"`
	// Year is when it came out.
	Year     int  `json:"year,omitempty"`
	Explicit bool `json:"explicit,omitempty"`
	// Preview is thirty seconds the app plays itself, for a track.
	Preview string `json:"preview,omitempty"`
	// Tracks are the first few of an album, playlist or artist, with
	// previews where the service gives them.
	Tracks []MusicTrack `json:"tracks,omitempty"`
	// Player is set when the card opens the service's own player through
	// this server: "spotify" or "soundcloud".
	Player string `json:"player,omitempty"`
}

// MusicTrack is one line of an album's or playlist's list.
type MusicTrack struct {
	Title    string `json:"title"`
	Artist   string `json:"artist,omitempty"`
	Duration int    `json:"duration,omitempty"`
	Preview  string `json:"preview,omitempty"`
	Explicit bool   `json:"explicit,omitempty"`
}

const (
	// musicBudget is how long reading one music link may take.
	musicBudget = 4 * time.Second
	// musicTracks is how many of an album's tracks a card lists.
	musicTracks = 5
)

// musicSites are each service's name and colour, for the card's head.
var musicSites = map[string]struct{ name, color, icon, home string }{
	"spotify":    {"Spotify", "#1ed760", "https://open.spotify.com/favicon.ico", "https://open.spotify.com/"},
	"apple":      {"Apple Music", "#fa2d48", "https://music.apple.com/favicon.ico", "https://music.apple.com/"},
	"deezer":     {"Deezer", "#a238ff", "https://www.deezer.com/favicon.ico", "https://www.deezer.com/"},
	"soundcloud": {"SoundCloud", "#ff5500", "https://soundcloud.com/favicon.ico", "https://soundcloud.com/"},
}

// musicRef is what a music link points at.
type musicRef struct {
	platform, kind, id string
	// country is Apple's storefront ("us"), and track a song picked out of an album (?i=).
	country, track string
	// link is the address as given, for SoundCloud's oEmbed and short links.
	link  *url.URL
	short bool
}

var (
	SpotifyIDRe     = regexp.MustCompile(`^[A-Za-z0-9]{22}$`)
	spotifyPathRe   = regexp.MustCompile(`^(?:/intl-[a-zA-Z-]{2,7})?/(track|album|playlist|artist|episode|show)/([A-Za-z0-9]{22})/?$`)
	applePathRe     = regexp.MustCompile(`^/([a-z]{2})/(album|song|playlist|artist|music-video)/(?:[^/]+/)?((?:pl\.)?[A-Za-z0-9.-]{1,64})/?$`)
	appleIDRe       = regexp.MustCompile(`^\d{1,15}$`)
	deezerPathRe    = regexp.MustCompile(`^(?:/[a-z]{2}(?:-[a-z]{2})?)?/(track|album|playlist|artist|episode|show)/(\d{1,15})/?$`)
	soundcloudRe    = regexp.MustCompile(`^/[A-Za-z0-9_-]{1,100}/(?:sets/)?[A-Za-z0-9_-]{1,200}/?$`)
	soundcloudAPIRe = regexp.MustCompile(`api\.soundcloud\.com(?:%2F|/)(tracks|playlists)(?:%2F|/)(\d{1,15})`)
)

// musicRefOf reads a link as music, or gives nil.
func musicRefOf(u *url.URL) *musicRef {
	if u == nil || (u.Scheme != "https" && u.Scheme != "http") {
		return nil
	}
	h := strings.ToLower(u.Hostname())
	path := u.EscapedPath()
	switch h {
	case "open.spotify.com", "play.spotify.com":
		if m := spotifyPathRe.FindStringSubmatch(path); m != nil {
			return &musicRef{platform: "spotify", kind: m[1], id: m[2]}
		}
	case "spotify.link":
		if len(path) > 1 && len(path) < 40 {
			return &musicRef{platform: "spotify", link: u, short: true}
		}
	case "music.apple.com", "geo.music.apple.com", "itunes.apple.com":
		m := applePathRe.FindStringSubmatch(path)
		if m == nil {
			return nil
		}
		ref := &musicRef{platform: "apple", country: m[1], kind: m[2], id: m[3]}
		switch ref.kind {
		case "song":
			ref.kind = "track"
		case "album":
			if i := u.Query().Get("i"); appleIDRe.MatchString(i) {
				ref.kind, ref.track = "track", i
			}
		case "music-video":
			return nil
		}
		if ref.kind != "playlist" && !appleIDRe.MatchString(strings.TrimPrefix(ref.id, "id")) {
			return nil
		}
		ref.id = strings.TrimPrefix(ref.id, "id")
		return ref
	case "deezer.com", "www.deezer.com":
		if m := deezerPathRe.FindStringSubmatch(path); m != nil && m[1] != "episode" && m[1] != "show" {
			return &musicRef{platform: "deezer", kind: m[1], id: m[2]}
		}
	case "deezer.page.link", "link.deezer.com", "dzr.page.link":
		if len(path) > 1 && len(path) < 60 {
			return &musicRef{platform: "deezer", link: u, short: true}
		}
	case "soundcloud.com", "www.soundcloud.com", "m.soundcloud.com":
		if soundcloudRe.MatchString(path) && !soundcloudReserved(path) {
			own := *u
			own.Scheme, own.Host, own.RawQuery, own.Fragment = "https", "soundcloud.com", "", ""
			return &musicRef{platform: "soundcloud", link: &own}
		}
	case "on.soundcloud.com":
		if len(path) > 1 && len(path) < 40 {
			return &musicRef{platform: "soundcloud", link: u, short: true}
		}
	}
	return nil
}

// soundcloudReserved are SoundCloud's own pages with a two-part path, which are not tracks.
func soundcloudReserved(path string) bool {
	first := strings.SplitN(strings.TrimPrefix(path, "/"), "/", 2)[0]
	switch first {
	case "discover", "search", "you", "upload", "stations", "charts", "pages", "settings", "messages", "notifications", "terms-of-use", "jobs", "imprint", "popular", "tags", "people":
		return true
	}
	return false
}

// MusicHost reports whether a link's host is a music service whose links get
// a card of their own.
func MusicHost(host string) bool {
	switch strings.ToLower(host) {
	case "open.spotify.com", "spotify.link", "music.apple.com", "geo.music.apple.com", "deezer.com", "www.deezer.com", "deezer.page.link", "link.deezer.com", "soundcloud.com", "on.soundcloud.com":
		return true
	}
	return false
}

// musicResult is the preview for a music link, or nil when the link is not
// one or the service had nothing to say about it.
func (f *Fetcher) musicResult(ctx context.Context, link *url.URL) *Result {
	ref := musicRefOf(link)
	if ref == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(ctx, musicBudget)
	defer cancel()
	if ref.short {
		final, err := f.finalURL(ctx, ref.link.String(), nil)
		if err != nil {
			return nil
		}
		if ref = musicRefOf(final); ref == nil || ref.short {
			return nil
		}
	}
	var m *Music
	var err error
	switch ref.platform {
	case "spotify":
		m, err = f.spotifyMusic(ctx, ref)
	case "apple":
		m, err = f.appleMusic(ctx, ref)
	case "deezer":
		m, err = f.deezerMusic(ctx, ref)
	case "soundcloud":
		m, err = f.soundcloudMusic(ctx, ref)
	}
	if err != nil || m == nil || m.Title == "" {
		return nil
	}
	m.Platform = ref.platform
	m.Title = clip(m.Title, maxTitleRunes)
	m.Artist = clip(m.Artist, maxTitleRunes)
	m.Album = clip(m.Album, maxTitleRunes)
	if len(m.Tracks) > musicTracks {
		m.Tracks = m.Tracks[:musicTracks]
	}
	for i := range m.Tracks {
		m.Tracks[i].Title = clip(m.Tracks[i].Title, maxTitleRunes)
		m.Tracks[i].Artist = clip(m.Tracks[i].Artist, maxTitleRunes)
	}
	site := musicSites[ref.platform]
	r := &Result{
		URL: link.String(), Type: "link", Title: m.Title, SiteName: site.name, Color: site.color, Icon: site.icon,
		Author: m.Artist, Image: m.Art, ImageLayout: "thumbnail", Music: m,
	}
	if m.Album != "" && m.Album != m.Title {
		r.Description = m.Album
	}
	return r
}

// spotifyMusic reads what Spotify's own embed page carries about it: the
// page every Spotify embed loads, with its data in Next.js's JSON.
func (f *Fetcher) spotifyMusic(ctx context.Context, ref *musicRef) (*Music, error) {
	body, _, err := f.get(ctx, "https://open.spotify.com/embed/"+ref.kind+"/"+ref.id, map[string]string{"Accept": "text/html"}, 3<<20)
	if err != nil {
		return nil, err
	}
	raw, ok := nextData(body)
	if !ok {
		return nil, errNoPost
	}
	var d struct {
		Props struct {
			PageProps struct {
				State struct {
					Data struct {
						Entity spotifyEntity `json:"entity"`
					} `json:"data"`
				} `json:"state"`
			} `json:"pageProps"`
		} `json:"props"`
	}
	if err := json.Unmarshal(raw, &d); err != nil {
		return nil, err
	}
	e := d.Props.PageProps.State.Data.Entity
	if e.Name == "" && e.Title == "" {
		return nil, errNoPost
	}
	m := &Music{Kind: ref.kind, ID: ref.id, Title: firstOf(e.Name, e.Title), Explicit: e.IsExplicit, Player: "spotify"}
	if len(e.Artists) > 0 {
		names := make([]string, 0, len(e.Artists))
		for _, a := range e.Artists {
			if a.Name != "" {
				names = append(names, a.Name)
			}
		}
		m.Artist = strings.Join(names, ", ")
	} else if ref.kind != "artist" {
		m.Artist = e.Subtitle
	}
	m.Duration = e.Duration / 1000
	if e.ReleaseDate != nil && len(e.ReleaseDate.ISO) >= 4 {
		m.Year, _ = strconv.Atoi(e.ReleaseDate.ISO[:4])
	}
	m.Art = e.art()
	m.Count = len(e.TrackList)
	for _, t := range e.TrackList {
		m.Tracks = append(m.Tracks, MusicTrack{Title: t.Title, Artist: t.Subtitle, Duration: t.Duration / 1000, Explicit: t.IsExplicit})
		if len(m.Tracks) == musicTracks {
			break
		}
	}
	if ref.kind == "artist" {
		// an artist's list is their top tracks, not everything they made
		m.Count = 0
	}
	return m, nil
}

type spotifyImage struct {
	URL    string `json:"url"`
	Width  int    `json:"width"`
	MaxW   int    `json:"maxWidth"`
	Height int    `json:"height"`
}

type spotifyEntity struct {
	Name        string `json:"name"`
	Title       string `json:"title"`
	Subtitle    string `json:"subtitle"`
	Duration    int    `json:"duration"`
	IsExplicit  bool   `json:"isExplicit"`
	ReleaseDate *struct {
		ISO string `json:"isoString"`
	} `json:"releaseDate"`
	Artists []struct {
		Name string `json:"name"`
	} `json:"artists"`
	CoverArt *struct {
		Sources []spotifyImage `json:"sources"`
	} `json:"coverArt"`
	VisualIdentity *struct {
		Image []spotifyImage `json:"image"`
	} `json:"visualIdentity"`
	TrackList []struct {
		Title      string `json:"title"`
		Subtitle   string `json:"subtitle"`
		Duration   int    `json:"duration"`
		IsExplicit bool   `json:"isExplicit"`
	} `json:"trackList"`
}

// art is the cover closest to 300 pixels across, which the card shows at a
// fraction of that.
func (e *spotifyEntity) art() string {
	var all []spotifyImage
	if e.VisualIdentity != nil {
		all = append(all, e.VisualIdentity.Image...)
	}
	if e.CoverArt != nil {
		all = append(all, e.CoverArt.Sources...)
	}
	best, bestD := "", 1<<30
	for _, im := range all {
		if !webAddress(im.URL) {
			continue
		}
		w := im.MaxW
		if w == 0 {
			w = im.Width
		}
		if w == 0 {
			w = 300
		}
		d := w - 300
		if d < 0 {
			d = -d * 2
		}
		if d < bestD {
			best, bestD = im.URL, d
		}
	}
	return best
}

var nextDataRe = regexp.MustCompile(`(?s)<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>`)

func nextData(page []byte) ([]byte, bool) {
	m := nextDataRe.FindSubmatch(page)
	if m == nil {
		return nil, false
	}
	return m[1], true
}

func firstOf(v ...string) string {
	for _, s := range v {
		if s = strings.TrimSpace(s); s != "" {
			return s
		}
	}
	return ""
}

type appleItem struct {
	WrapperType     string `json:"wrapperType"`
	Kind            string `json:"kind"`
	ArtistName      string `json:"artistName"`
	CollectionName  string `json:"collectionName"`
	TrackName       string `json:"trackName"`
	ArtworkURL100   string `json:"artworkUrl100"`
	PreviewURL      string `json:"previewUrl"`
	TrackTimeMillis int    `json:"trackTimeMillis"`
	TrackCount      int    `json:"trackCount"`
	ReleaseDate     string `json:"releaseDate"`
	TrackExplicit   string `json:"trackExplicitness"`
	CollExplicit    string `json:"collectionExplicitness"`
	PrimaryGenre    string `json:"primaryGenreName"`
	TrackNumber     int    `json:"trackNumber"`
}

// appleArt is Apple's picture at 300 pixels: its addresses name their size.
func appleArt(u string) string {
	if !webAddress(u) {
		return ""
	}
	return strings.Replace(u, "100x100bb", "300x300bb", 1)
}

func appleYear(s string) int {
	if len(s) < 4 {
		return 0
	}
	y, _ := strconv.Atoi(s[:4])
	return y
}

// appleMusic reads a song, an album or an artist from Apple's search API
// (the iTunes lookup), and a playlist from its page.
func (f *Fetcher) appleMusic(ctx context.Context, ref *musicRef) (*Music, error) {
	if ref.kind == "playlist" {
		return f.applePlaylist(ctx, ref)
	}
	id := ref.id
	if ref.track != "" {
		id = ref.track
	}
	q := url.Values{"id": {id}, "country": {ref.country}}
	if ref.kind == "album" || ref.kind == "artist" {
		q.Set("entity", "song")
		q.Set("limit", strconv.Itoa(musicTracks+1))
	}
	if ref.kind == "artist" {
		q.Set("sort", "popular")
	}
	var res struct {
		Results []appleItem `json:"results"`
	}
	if err := f.getJSON(ctx, "https://itunes.apple.com/lookup?"+q.Encode(), nil, &res); err != nil {
		return nil, err
	}
	if len(res.Results) == 0 {
		return nil, errNoPost
	}
	head := res.Results[0]
	m := &Music{Kind: ref.kind, ID: id}
	switch ref.kind {
	case "track":
		if head.Kind != "song" {
			return nil, errNoPost
		}
		m.Title, m.Artist, m.Album = head.TrackName, head.ArtistName, head.CollectionName
		m.Duration = head.TrackTimeMillis / 1000
		m.Art, m.Year, m.Explicit = appleArt(head.ArtworkURL100), appleYear(head.ReleaseDate), head.TrackExplicit == "explicit"
		if webAddress(head.PreviewURL) {
			m.Preview = head.PreviewURL
		}
	case "album":
		m.Title, m.Artist, m.Count = head.CollectionName, head.ArtistName, head.TrackCount
		m.Art, m.Year, m.Explicit = appleArt(head.ArtworkURL100), appleYear(head.ReleaseDate), head.CollExplicit == "explicit"
	case "artist":
		m.Title, m.Album = head.ArtistName, head.PrimaryGenre
		m.Art = f.pageImage(ctx, "https://music.apple.com/"+ref.country+"/artist/"+ref.id)
	}
	for _, it := range res.Results[1:] {
		if it.Kind != "song" {
			continue
		}
		t := MusicTrack{Title: it.TrackName, Artist: it.ArtistName, Duration: it.TrackTimeMillis / 1000, Explicit: it.TrackExplicit == "explicit"}
		if webAddress(it.PreviewURL) {
			t.Preview = it.PreviewURL
		}
		if ref.kind == "artist" && m.Art == "" {
			m.Art = appleArt(it.ArtworkURL100)
		}
		m.Tracks = append(m.Tracks, t)
	}
	return m, nil
}

// applePlaylist reads a playlist's name, curator and cover from its page.
func (f *Fetcher) applePlaylist(ctx context.Context, ref *musicRef) (*Music, error) {
	body, _, err := f.get(ctx, "https://music.apple.com/"+ref.country+"/playlist/"+ref.id, map[string]string{"Accept": "text/html"}, 0)
	if err != nil {
		return nil, err
	}
	meta := parseMeta(body)
	title := decoded(meta.first("apple:title", "og:title"))
	if title == "" {
		return nil, errNoPost
	}
	m := &Music{Kind: "playlist", ID: ref.id, Title: strings.TrimSuffix(title, " on Apple Music")}
	if d := decoded(meta.first("og:description")); d != "" {
		m.Artist = clip(d, 80)
	}
	if img := meta.first("og:image"); webAddress(img) {
		m.Art = img
	}
	return m, nil
}

// pageImage is a page's og:image, or "".
func (f *Fetcher) pageImage(ctx context.Context, link string) string {
	body, _, err := f.get(ctx, link, map[string]string{"Accept": "text/html"}, 0)
	if err != nil {
		return ""
	}
	if img := parseMeta(body).first("og:image"); webAddress(img) {
		return img
	}
	return ""
}

type deezerTrack struct {
	ID       int64  `json:"id"`
	Title    string `json:"title"`
	Duration int    `json:"duration"`
	Explicit bool   `json:"explicit_lyrics"`
	Readable *bool  `json:"readable"`
	Preview  string `json:"preview"`
	Artist   struct {
		Name string `json:"name"`
	} `json:"artist"`
	Album struct {
		Title    string `json:"title"`
		CoverBig string `json:"cover_medium"`
	} `json:"album"`
	ReleaseDate string `json:"release_date"`
}

// DeezerPreviewPath is where the app plays a Deezer track's thirty seconds:
// through this server, since the addresses Deezer gives run out within hours.
func DeezerPreviewPath(id int64) string { return "/media/music/preview/" + strconv.FormatInt(id, 10) }

func (t deezerTrack) line() MusicTrack {
	l := MusicTrack{Title: t.Title, Artist: t.Artist.Name, Duration: t.Duration, Explicit: t.Explicit}
	if t.Preview != "" && t.ID > 0 {
		l.Preview = DeezerPreviewPath(t.ID)
	}
	return l
}

// deezerMusic reads Deezer's public API.
func (f *Fetcher) deezerMusic(ctx context.Context, ref *musicRef) (*Music, error) {
	api := "https://api.deezer.com/" + ref.kind + "/" + ref.id
	m := &Music{Kind: ref.kind, ID: ref.id}
	var failed struct {
		Error *struct {
			Message string `json:"message"`
		} `json:"error"`
	}
	switch ref.kind {
	case "track":
		var t deezerTrack
		body, _, err := f.get(ctx, api, map[string]string{"Accept": "application/json"}, 0)
		if err != nil {
			return nil, err
		}
		if json.Unmarshal(body, &failed) == nil && failed.Error != nil {
			return nil, errNoPost
		}
		if err := json.Unmarshal(body, &t); err != nil {
			return nil, err
		}
		l := t.line()
		m.Title, m.Artist, m.Album, m.Duration, m.Explicit, m.Preview = t.Title, t.Artist.Name, t.Album.Title, t.Duration, t.Explicit, l.Preview
		m.Art, m.Year = webOnly(t.Album.CoverBig), appleYear(t.ReleaseDate)
	case "album", "playlist":
		var a struct {
			Title       string `json:"title"`
			CoverBig    string `json:"cover_medium"`
			PictureBig  string `json:"picture_medium"`
			NbTracks    int    `json:"nb_tracks"`
			Duration    int    `json:"duration"`
			ReleaseDate string `json:"release_date"`
			Explicit    bool   `json:"explicit_lyrics"`
			Artist      struct {
				Name string `json:"name"`
			} `json:"artist"`
			Creator struct {
				Name string `json:"name"`
			} `json:"creator"`
			Tracks struct {
				Data []deezerTrack `json:"data"`
			} `json:"tracks"`
		}
		body, _, err := f.get(ctx, api, map[string]string{"Accept": "application/json"}, 0)
		if err != nil {
			return nil, err
		}
		if json.Unmarshal(body, &failed) == nil && failed.Error != nil {
			return nil, errNoPost
		}
		if err := json.Unmarshal(body, &a); err != nil {
			return nil, err
		}
		m.Title, m.Artist, m.Count, m.Duration, m.Explicit = a.Title, firstOf(a.Artist.Name, a.Creator.Name), a.NbTracks, a.Duration, a.Explicit
		m.Art, m.Year = webOnly(firstOf(a.CoverBig, a.PictureBig)), appleYear(a.ReleaseDate)
		for _, t := range a.Tracks.Data {
			m.Tracks = append(m.Tracks, t.line())
			if len(m.Tracks) == musicTracks {
				break
			}
		}
	case "artist":
		var a struct {
			Name       string `json:"name"`
			PictureBig string `json:"picture_medium"`
			NbFan      int    `json:"nb_fan"`
		}
		body, _, err := f.get(ctx, api, map[string]string{"Accept": "application/json"}, 0)
		if err != nil {
			return nil, err
		}
		if json.Unmarshal(body, &failed) == nil && failed.Error != nil {
			return nil, errNoPost
		}
		if err := json.Unmarshal(body, &a); err != nil {
			return nil, err
		}
		m.Title, m.Art = a.Name, webOnly(a.PictureBig)
		var top struct {
			Data []deezerTrack `json:"data"`
		}
		if f.getJSON(ctx, api+"/top?limit="+strconv.Itoa(musicTracks), nil, &top) == nil {
			for _, t := range top.Data {
				m.Tracks = append(m.Tracks, t.line())
			}
		}
	default:
		return nil, errNoPost
	}
	return m, nil
}

func webOnly(u string) string {
	if webAddress(u) {
		return u
	}
	return ""
}

// soundcloudMusic reads SoundCloud's oEmbed, whose player address names the
// track's or playlist's number.
func (f *Fetcher) soundcloudMusic(ctx context.Context, ref *musicRef) (*Music, error) {
	var o struct {
		Title      string `json:"title"`
		AuthorName string `json:"author_name"`
		Thumbnail  string `json:"thumbnail_url"`
		HTML       string `json:"html"`
	}
	if err := f.getJSON(ctx, "https://soundcloud.com/oembed?format=json&url="+url.QueryEscape(ref.link.String()), nil, &o); err != nil {
		return nil, err
	}
	sub := soundcloudAPIRe.FindStringSubmatch(o.HTML)
	if sub == nil || o.Title == "" {
		return nil, errNoPost
	}
	m := &Music{ID: sub[2], Artist: o.AuthorName, Player: "soundcloud", Art: webOnly(o.Thumbnail)}
	m.Kind = "track"
	if sub[1] == "playlists" {
		m.Kind = "playlist"
	}
	// oEmbed titles read "Title by Artist"
	m.Title = strings.TrimSuffix(o.Title, " by "+o.AuthorName)
	if m.Art != "" {
		m.Art = strings.Replace(m.Art, "-t500x500.", "-t300x300.", 1)
	}
	return m, nil
}
