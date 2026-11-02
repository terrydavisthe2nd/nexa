package unfurl

import (
	"context"
	"encoding/json"
	"os"
	"testing"
)

// TestMusicLive reads real music links (UNFURL_LIVE=1).
func TestMusicLive(t *testing.T) {
	if os.Getenv("UNFURL_LIVE") == "" {
		t.Skip("UNFURL_LIVE not set")
	}
	f := NewService().Fetcher
	for _, link := range []string{
		"https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
		"https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv",
		"https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M",
		"https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt",
		"https://music.apple.com/us/album/better-together/1440857781?i=1440857786",
		"https://music.apple.com/us/album/in-between-dreams-bonus-track-version/1440857781",
		"https://music.apple.com/us/artist/jack-johnson/909253",
		"https://www.deezer.com/en/track/3135556",
		"https://www.deezer.com/album/302127",
		"https://www.deezer.com/artist/27",
		"https://www.deezer.com/playlist/908622995",
		"https://soundcloud.com/forss/flickermood",
		"https://soundcloud.com/forss/sets/soulhack",
	} {
		r, err := f.Fetch(context.Background(), link)
		if err != nil || r == nil || r.Music == nil {
			t.Errorf("%s: %v %+v", link, err, r)
			continue
		}
		b, _ := json.Marshal(r.Music)
		t.Logf("%s\n  %s", link, b)
	}
}
