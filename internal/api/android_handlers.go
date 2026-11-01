package api

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// The Android app's APKs are committed to the repository, in AndroidDir
// (downloads/android), with manifest.json beside them; tools/android_release.py
// writes both. A git pull is all it takes to offer a new version: the manifest
// is read on every request, and nothing is cached in between.
//
// The download page, the banner on Android phones and the app's own update
// check read /api/v1/android/latest. Phones download an APK from
// /android/<name>, and /.well-known/assetlinks.json lets Android open this
// site's links in the app once it is installed.

// androidFileName allows the flat names the release script writes, and
// nothing that could leave the folder or hide in it.
var androidFileName = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._+-]{0,120}\.apk$`)

var (
	androidSHA256  = regexp.MustCompile(`^[0-9a-f]{64}$`)
	androidPackage = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)
	// a signing certificate as Android writes it for app links: 32 bytes, upper-case hex pairs
	androidCert = regexp.MustCompile(`^([0-9A-F]{2}:){31}[0-9A-F]{2}$`)
)

// androidRelease is manifest.json as the download page, the banner and the
// app's updater get it. With nothing published every field is empty and Files
// is an empty list, which is how the page knows to leave Android out.
type androidRelease struct {
	Version     string `json:"version"`
	VersionCode int64  `json:"version_code"`
	ReleasedAt  int64  `json:"released_at"`
	// MinSDK is the oldest Android the builds install on, as an API level
	// (26), and MinAndroid the same as people know it ("8.0").
	MinSDK     int    `json:"min_sdk"`
	MinAndroid string `json:"min_android"`
	// Package and SigningSHA256 are the application id and the certificate
	// the builds are signed with, which app links need and which a person can
	// check with apksigner.
	Package       string        `json:"package,omitempty"`
	SigningSHA256 string        `json:"signing_sha256,omitempty"`
	Notes         string        `json:"notes,omitempty"`
	Files         []androidFile `json:"files"`
}

// androidFile is one build: "universal" installs on any phone, the others
// carry code for one processor and are much smaller.
type androidFile struct {
	ABI    string `json:"abi"` // arm64-v8a | armeabi-v7a | x86_64 | universal
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

func (s *Server) androidRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/android/latest", s.handleAndroidLatest)
	mux.HandleFunc("GET /api/v1/android/ota", s.handleAndroidOTA)
	mux.HandleFunc("GET /android/{file}", s.handleAndroidFile)
	mux.HandleFunc("HEAD /android/{file}", s.handleAndroidFile)
	// the folder itself, or anything nested, is not a page of the app
	mux.HandleFunc("/android/", func(w http.ResponseWriter, r *http.Request) { WriteError(w, ErrNotFound) })
	// an address short enough to say out loud leads to the downloads
	mux.HandleFunc("GET /android", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, "/download", http.StatusFound)
	})
	mux.HandleFunc("GET /.well-known/assetlinks.json", s.handleAssetLinks)
}

// androidManifest reads what is published. A build the manifest names is
// only offered while its file is really there and the size it was published
// at, so a manifest that went ahead of its files (or behind them) never sends
// a phone to a download that fails or does not match its hash.
func (s *Server) androidManifest() (androidRelease, string) {
	empty := androidRelease{Files: []androidFile{}}
	dir, err := filepath.Abs(s.Cfg.AndroidDir)
	if err != nil {
		return empty, ""
	}
	raw, err := os.ReadFile(filepath.Join(dir, "manifest.json"))
	if err != nil {
		return empty, dir
	}
	var rel androidRelease
	if json.Unmarshal(raw, &rel) != nil || strings.TrimSpace(rel.Version) == "" {
		return empty, dir
	}
	files := make([]androidFile, 0, len(rel.Files))
	for _, f := range rel.Files {
		if !androidFileName.MatchString(f.Name) || strings.Contains(f.Name, "..") || !androidSHA256.MatchString(f.SHA256) || f.ABI == "" {
			continue
		}
		st, err := os.Stat(filepath.Join(dir, f.Name))
		if err != nil || !st.Mode().IsRegular() || st.Size() != f.Size {
			continue
		}
		files = append(files, f)
	}
	if len(files) == 0 {
		return empty, dir
	}
	rel.Files = files
	if !androidPackage.MatchString(rel.Package) {
		rel.Package = ""
	}
	if !androidCert.MatchString(rel.SigningSHA256) {
		rel.SigningSHA256 = ""
	}
	return rel, dir
}

func (s *Server) handleAndroidLatest(w http.ResponseWriter, r *http.Request) {
	rel, _ := s.androidManifest()
	w.Header().Set("Cache-Control", "no-cache")
	WriteJSON(w, http.StatusOK, rel)
}

func (s *Server) handleAndroidFile(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	if androidOTAName.MatchString(name) {
		s.serveAndroidOTA(w, r, name)
		return
	}
	if !androidFileName.MatchString(name) || strings.Contains(name, "..") {
		WriteError(w, ErrNotFound)
		return
	}
	rel, dir := s.androidManifest()
	var entry *androidFile
	for i := range rel.Files {
		if rel.Files[i].Name == name {
			entry = &rel.Files[i]
			break
		}
	}
	if entry == nil || dir == "" {
		WriteError(w, ErrNotFound)
		return
	}
	fp := filepath.Join(dir, name)
	if filepath.Dir(fp) != dir {
		WriteError(w, ErrNotFound)
		return
	}
	f, err := os.Open(fp)
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || !st.Mode().IsRegular() || st.Size() != entry.Size {
		WriteError(w, ErrNotFound)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "application/vnd.android.package-archive")
	h.Set("Content-Disposition", `attachment; filename="`+name+`"`)
	h.Set("X-Content-Type-Options", "nosniff")
	// The names stay the same from one version to the next, so a plain link is
	// checked with the server every time. The page links each build with its
	// hash in ?v=, and that address only ever means those bytes: it may be
	// kept for good, by the browser and by any cache in front of this.
	h.Set("ETag", `"`+entry.SHA256+`"`)
	if v := r.URL.Query().Get("v"); len(v) >= 8 && strings.HasPrefix(entry.SHA256, strings.ToLower(v)) {
		h.Set("Cache-Control", "public, max-age=31536000, immutable")
	} else {
		h.Set("Cache-Control", "no-cache")
	}
	http.ServeContent(w, r, name, st.ModTime(), f)
}

// handleAssetLinks tells Android that this site's links may open in the app.
// It is served once a build is published with its package and certificate.
func (s *Server) handleAssetLinks(w http.ResponseWriter, r *http.Request) {
	rel, _ := s.androidManifest()
	if rel.Package == "" || rel.SigningSHA256 == "" {
		WriteError(w, ErrNotFound)
		return
	}
	w.Header().Set("Cache-Control", "public, max-age=3600")
	WriteJSON(w, http.StatusOK, []map[string]any{{
		// links open in the app, and passkeys made here work in it
		"relation": []string{"delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"},
		"target": map[string]any{
			"namespace":                "android_app",
			"package_name":             rel.Package,
			"sha256_cert_fingerprints": []string{rel.SigningSHA256},
		},
	}})
}

// Over-the-air content packs (fish.nexa.data.Ota): the web files the app
// copies in (translations, What's New, legal text, sounds, seasonal art),
// zipped by tools/android_release.py when nothing else changed since the
// published APK, and described by ota.json beside manifest.json. A pack names
// the one versionCode it was made for; the app ignores any other.
var androidOTAName = regexp.MustCompile(`^ota-[0-9a-f]{16,64}\.zip$`)

type androidOTA struct {
	ID          string `json:"id"`
	VersionCode int64  `json:"version_code"`
	Name        string `json:"name"`
	Size        int64  `json:"size"`
	SHA256      string `json:"sha256"`
}

// androidOTAPack reads ota.json, offering the pack only while it was made for
// the published APK and its file is there at the published size.
func (s *Server) androidOTAPack() (androidOTA, string, bool) {
	rel, dir := s.androidManifest()
	if dir == "" || rel.VersionCode == 0 {
		return androidOTA{}, "", false
	}
	raw, err := os.ReadFile(filepath.Join(dir, "ota.json"))
	if err != nil {
		return androidOTA{}, "", false
	}
	var p androidOTA
	if json.Unmarshal(raw, &p) != nil || p.VersionCode != rel.VersionCode || !androidOTAName.MatchString(p.Name) ||
		!androidSHA256.MatchString(p.SHA256) || p.Name != "ota-"+p.ID+".zip" {
		return androidOTA{}, "", false
	}
	st, err := os.Stat(filepath.Join(dir, p.Name))
	if err != nil || !st.Mode().IsRegular() || st.Size() != p.Size {
		return androidOTA{}, "", false
	}
	return p, dir, true
}

func (s *Server) handleAndroidOTA(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-cache")
	p, _, ok := s.androidOTAPack()
	if !ok {
		WriteJSON(w, http.StatusOK, map[string]any{})
		return
	}
	WriteJSON(w, http.StatusOK, p)
}

func (s *Server) serveAndroidOTA(w http.ResponseWriter, r *http.Request, name string) {
	p, dir, ok := s.androidOTAPack()
	if !ok || p.Name != name {
		WriteError(w, ErrNotFound)
		return
	}
	f, err := os.Open(filepath.Join(dir, name))
	if err != nil {
		WriteError(w, ErrNotFound)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || st.Size() != p.Size {
		WriteError(w, ErrNotFound)
		return
	}
	h := w.Header()
	h.Set("Content-Type", "application/zip")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("ETag", `"`+p.SHA256+`"`)
	h.Set("Cache-Control", "public, max-age=31536000, immutable") // the name carries the hash
	http.ServeContent(w, r, name, st.ModTime(), f)
}
