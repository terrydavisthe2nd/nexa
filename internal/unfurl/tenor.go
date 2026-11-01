package unfurl

import (
	"net/url"
	"strconv"
	"strings"
)

// tenorGif turns a Tenor page (tenor.com/view/..., with or without a language
// before "view") into the GIF it shows, so the message draws that GIF like
// one from the picker instead of a card about Tenor. The page names the GIF
// in og:image (type image/gif); its own size is the player's, since
// og:image:width describes a smaller rendition than the file named.
func tenorGif(final *url.URL, meta metaSet) *Result {
	host := strings.TrimPrefix(strings.ToLower(final.Hostname()), "www.")
	if host != "tenor.com" || !isTenorView(final.Path) {
		return nil
	}
	img := resolveImage(final, meta.first("og:image", "og:image:secure_url", "og:image:url", "twitter:image"))
	if img == "" {
		return nil
	}
	iu, err := url.Parse(img)
	if err != nil {
		return nil
	}
	ih := strings.ToLower(iu.Hostname())
	if !(ih == "tenor.com" || strings.HasSuffix(ih, ".tenor.com")) {
		return nil
	}
	switch t := strings.ToLower(meta.first("og:image:type")); {
	case t == "" && !strings.HasSuffix(strings.ToLower(iu.Path), ".gif"), t != "" && t != "image/gif":
		return nil
	}
	r := &Result{URL: final.String(), Type: "gif", Mime: "image/gif", Image: img, SiteName: "Tenor"}
	w, _ := strconv.Atoi(meta.first("twitter:player:width"))
	h, _ := strconv.Atoi(meta.first("twitter:player:height"))
	if w <= 0 || h <= 0 {
		w, _ = strconv.Atoi(meta.first("og:image:width"))
		h, _ = strconv.Atoi(meta.first("og:image:height"))
	}
	if w > 0 && h > 0 {
		r.ImageW, r.ImageH = w, h
	}
	return r
}

// isTenorView reports whether a path is one GIF's page: /view/<slug> or
// /<language>/view/<slug>.
func isTenorView(path string) bool {
	parts := strings.Split(strings.Trim(path, "/"), "/")
	switch {
	case len(parts) == 2 && parts[0] == "view":
		return parts[1] != ""
	case len(parts) == 3 && parts[1] == "view" && len(parts[0]) <= 8:
		return parts[2] != ""
	}
	return false
}
