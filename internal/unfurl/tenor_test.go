package unfurl

import (
	"net/url"
	"testing"
)

const tenorPage = `<html><head>
<meta property="og:site_name" content="Tenor">
<meta property="og:title" content="Sir Cat Meme - Sir cat - Discover &amp; Share GIFs">
<meta name="twitter:card" content="player">
<meta name="twitter:player:width" content="498">
<meta name="twitter:player:height" content="335">
<meta property="og:image" content="https://media1.tenor.com/m/FNVOSJ9lj1wAAAAC/sir-cat.gif">
<meta property="og:image:type" content="image/gif">
<meta property="og:image:width" content="236">
<meta property="og:image:height" content="159">
</head></html>`

func TestTenorPageIsItsGif(t *testing.T) {
	for _, link := range []string{
		"https://tenor.com/view/sir-cat-gif-1501192124616773468",
		"https://www.tenor.com/en-GB/view/sir-cat-gif-1501192124616773468",
	} {
		u, _ := url.Parse(link)
		r := tenorGif(u, parseMeta([]byte(tenorPage)))
		if r == nil {
			t.Fatalf("%s: no GIF", link)
		}
		if r.Type != "gif" || r.Image != "https://media1.tenor.com/m/FNVOSJ9lj1wAAAAC/sir-cat.gif" || r.URL != link {
			t.Fatalf("%s: got %+v", link, r)
		}
		if r.ImageW != 498 || r.ImageH != 335 {
			t.Fatalf("%s: size %dx%d, want the player's 498x335", link, r.ImageW, r.ImageH)
		}
		if r.Title != "" || r.Description != "" {
			t.Fatalf("%s: a GIF carries no card text, got %q %q", link, r.Title, r.Description)
		}
	}
}

func TestTenorOtherPagesStayCards(t *testing.T) {
	for _, link := range []string{
		"https://tenor.com/search/cat-gifs",
		"https://tenor.com/",
		"https://nottenor.com/view/sir-cat-gif-1",
	} {
		u, _ := url.Parse(link)
		if r := tenorGif(u, parseMeta([]byte(tenorPage))); r != nil {
			t.Fatalf("%s: became a GIF", link)
		}
	}
	// a picture from somewhere else is not taken on Tenor's word
	u, _ := url.Parse("https://tenor.com/view/x-gif-1")
	page := `<meta property="og:image" content="https://evil.example/x.gif"><meta property="og:image:type" content="image/gif">`
	if r := tenorGif(u, parseMeta([]byte(page))); r != nil {
		t.Fatalf("foreign image accepted: %+v", r)
	}
	page = `<meta property="og:image" content="https://media1.tenor.com/m/a/x.png"><meta property="og:image:type" content="image/png">`
	if r := tenorGif(u, parseMeta([]byte(page))); r != nil {
		t.Fatalf("PNG accepted: %+v", r)
	}
}
