package api

import "testing"

func TestGifPreviewIsSharpButLight(t *testing.T) {
	file := func(url string, w int) klipyFile { return klipyFile{URL: url, Width: w, Height: w * 3 / 4} }
	// the renditions one real Klipy result offers
	sizes := map[string]map[string]klipyFile{
		"hd": {"gif": file("hd.gif", 498), "webp": file("hd.webp", 498)},
		"md": {"gif": file("md.gif", 640), "webp": file("md.webp", 640)},
		"sm": {"gif": file("sm.gif", 220), "webp": file("sm.webp", 220)},
		"xs": {"gif": file("xs.gif", 113), "webp": file("xs.webp", 90)},
	}
	if got := gifPreview(sizes).URL; got != "hd.webp" {
		t.Errorf("preview = %q, want the smallest WebP wide enough to stay sharp", got)
	}
	// a sharp GIF beats a blurry WebP
	mixed := map[string]map[string]klipyFile{"sm": {"webp": file("sm.webp", 220), "gif": file("sm.gif", 220)}, "hd": {"gif": file("hd.gif", 498)}}
	if got := gifPreview(mixed).URL; got != "hd.gif" {
		t.Errorf("preview = %q, want the GIF wide enough to stay sharp", got)
	}
	// nothing wide enough: the widest there is, rather than the tiniest
	small := map[string]map[string]klipyFile{"sm": {"webp": file("sm.webp", 220)}, "xs": {"webp": file("xs.webp", 90)}}
	if got := gifPreview(small).URL; got != "sm.webp" {
		t.Errorf("preview = %q, want the widest WebP", got)
	}
	// no WebP at all: the same choice among GIFs
	gifs := map[string]map[string]klipyFile{"md": {"gif": file("md.gif", 640)}, "hd": {"gif": file("hd.gif", 498)}, "xs": {"gif": file("xs.gif", 113)}}
	if got := gifPreview(gifs).URL; got != "hd.gif" {
		t.Errorf("preview = %q, want the smallest GIF wide enough", got)
	}
	if got := gifPreview(map[string]map[string]klipyFile{}).URL; got != "" {
		t.Errorf("preview from nothing = %q", got)
	}
}

func TestGifToSendFitsThroughTheProxy(t *testing.T) {
	file := func(url string, size int64) klipyFile { return klipyFile{URL: url, Width: 640, Height: 690, Size: size} }
	// "im-not-letting-you-in-brochacho-even-with-your-minichachos": its medium GIF is 51 MB
	sizes := map[string]map[string]klipyFile{
		"md": {"gif": file("md.gif", 51182378), "webp": file("md.webp", 5218024)},
		"hd": {"gif": file("hd.gif", 13591393), "webp": file("hd.webp", 5534972)},
		"sm": {"gif": file("sm.gif", 7771877)},
	}
	if got := gifToSend(sizes).URL; got != "hd.gif" {
		t.Errorf("sent %q, want the first GIF the proxy passes", got)
	}
	// every GIF too big: the animated WebP
	huge := map[string]map[string]klipyFile{"md": {"gif": file("md.gif", 60<<20), "webp": file("md.webp", 4<<20)}, "sm": {"gif": file("sm.gif", 30<<20)}}
	if got := gifToSend(huge).URL; got != "md.webp" {
		t.Errorf("sent %q, want the WebP", got)
	}
	// sizes not given: medium, as before
	unsized := map[string]map[string]klipyFile{"md": {"gif": file("md.gif", 0)}, "hd": {"gif": file("hd.gif", 0)}}
	if got := gifToSend(unsized).URL; got != "md.gif" {
		t.Errorf("sent %q, want the medium GIF", got)
	}
}
