package api

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// A self-hosted deployment can put desktop installers and their manifest.json
// into DesktopDir, and the download page and banner read that. Tauri builds are
// published on GitHub; the app's updater reads the signed latest.json there.
// Old Electron files in an existing directory are left for the copies already
// installed, and Electron's latest*.yml feeds are still served.

// desktopFileName allows the flat file names installers use, and nothing that
// could leave the folder or hide in it.
var desktopFileName = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9 ._+-]{0,200}$`)

// desktopRelease is manifest.json as the download page gets it.
type desktopRelease struct {
	Version    string        `json:"version"`
	ReleasedAt int64         `json:"released_at"`
	Files      []desktopFile `json:"files"`
}

type desktopFile struct {
	OS     string `json:"os"`   // windows | mac | linux
	Arch   string `json:"arch"` // x64 | arm64
	Kind   string `json:"kind"` // installer | appimage | deb | zip
	Name   string `json:"name"`
	Size   int64  `json:"size"`
	SHA256 string `json:"sha256"`
}

var desktopTypes = map[string]string{
	".exe":      "application/vnd.microsoft.portable-executable",
	".dmg":      "application/x-apple-diskimage",
	".zip":      "application/zip",
	".appimage": "application/octet-stream",
	".deb":      "application/vnd.debian.binary-package",
	".yml":      "text/yaml; charset=utf-8",
	".json":     "application/json",
	".blockmap": "application/octet-stream",
}

func (s *Server) desktopRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/v1/desktop/latest", s.handleDesktopLatest)
	mux.HandleFunc("GET /desktop/{file}", s.handleDesktopFile)
	mux.HandleFunc("HEAD /desktop/{file}", s.handleDesktopFile)
	// the folder itself, or anything nested, is not a page of the app
	mux.HandleFunc("/desktop/", func(w http.ResponseWriter, r *http.Request) { WriteError(w, ErrNotFound) })
}

func (s *Server) handleDesktopLatest(w http.ResponseWriter, r *http.Request) {
	raw, err := os.ReadFile(filepath.Join(s.Cfg.DesktopDir, "manifest.json"))
	var rel desktopRelease
	if err != nil || json.Unmarshal(raw, &rel) != nil || rel.Version == "" {
		WriteError(w, E(http.StatusNotFound, "no_release", "No desktop release has been published yet."))
		return
	}
	w.Header().Set("Cache-Control", "no-cache")
	WriteJSON(w, http.StatusOK, rel)
}

func (s *Server) handleDesktopFile(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	if !desktopFileName.MatchString(name) || strings.Contains(name, "..") {
		WriteError(w, ErrNotFound)
		return
	}
	dir, err := filepath.Abs(s.Cfg.DesktopDir)
	if err != nil {
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
	if err != nil || !st.Mode().IsRegular() {
		WriteError(w, ErrNotFound)
		return
	}
	h := w.Header()
	ext := strings.ToLower(filepath.Ext(name))
	if ct, ok := desktopTypes[ext]; ok {
		h.Set("Content-Type", ct)
	} else {
		h.Set("Content-Type", "application/octet-stream")
	}
	switch ext {
	case ".yml", ".json", ".sig":
		// what is current changes with every release
		h.Set("Cache-Control", "no-cache")
	default:
		// every installer's name carries its version
		h.Set("Cache-Control", "public, max-age=31536000, immutable")
		h.Set("Content-Disposition", `attachment; filename="`+name+`"`)
	}
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, name, st.ModTime(), f)
}
