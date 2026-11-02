package unfurl

import (
	"net/url"
	"testing"
)

func TestMusicRefOf(t *testing.T) {
	cases := []struct{ link, platform, kind, id string }{
		{"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc", "spotify", "track", "4cOdK2wGLETKBW3PvgPWqT"},
		{"https://open.spotify.com/intl-de/album/4LH4d3cOWNNsVw41Gqt2kv", "spotify", "album", "4LH4d3cOWNNsVw41Gqt2kv"},
		{"https://open.spotify.com/episode/7makk4oTQel546B0PZlDM5", "spotify", "episode", "7makk4oTQel546B0PZlDM5"},
		{"https://music.apple.com/us/album/better-together/1440857781?i=1440857786", "apple", "track", "1440857781"},
		{"https://music.apple.com/gb/album/in-between-dreams/1440857781", "apple", "album", "1440857781"},
		{"https://music.apple.com/us/song/better-together/1440857786", "apple", "track", "1440857786"},
		{"https://music.apple.com/us/playlist/todays-hits/pl.f4d106fed2bd41149aaacabb233eb5eb", "apple", "playlist", "pl.f4d106fed2bd41149aaacabb233eb5eb"},
		{"https://www.deezer.com/en/track/3135556", "deezer", "track", "3135556"},
		{"https://deezer.com/album/302127", "deezer", "album", "302127"},
		{"https://soundcloud.com/forss/flickermood", "soundcloud", "", ""},
		{"https://soundcloud.com/forss/sets/soulhack", "soundcloud", "", ""},
	}
	for _, c := range cases {
		u, _ := url.Parse(c.link)
		r := musicRefOf(u)
		if r == nil || r.platform != c.platform || r.kind != c.kind || r.id != c.id {
			t.Errorf("%s: %+v", c.link, r)
		}
	}
	for _, link := range []string{
		"https://open.spotify.com/track/short",
		"https://open.spotify.com/user/someone",
		"https://music.apple.com/us/music-video/x/123",
		"https://www.deezer.com/en/show/123",
		"https://soundcloud.com/discover/sets/x",
		"https://soundcloud.com/forss",
		"javascript:alert(1)",
		"https://evil.example/track/4cOdK2wGLETKBW3PvgPWqT",
	} {
		u, _ := url.Parse(link)
		if r := musicRefOf(u); r != nil {
			t.Errorf("%s read as music: %+v", link, r)
		}
	}
	if u, _ := url.Parse("https://spotify.link/abc123"); musicRefOf(u) == nil || !musicRefOf(u).short {
		t.Error("short Spotify link not followed")
	}
}
