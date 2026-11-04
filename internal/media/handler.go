package media

import (
	"net/http"
	"os"
	"strconv"
	"time"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

// PublicKinds are the blob kinds anyone may fetch by id.
var PublicKinds = map[string]bool{"avatar": true, "banner": true, "icon": true, "emoji": true, "sticker": true, "tag": true, "widget": true, "typing": true}

// ServePublic writes a public blob with immutable caching. The kind in the
// URL must match the stored kind so an attachment can never be fetched here.
func (m *Store) ServePublic(w http.ResponseWriter, r *http.Request, kind string, id ids.ID) bool {
	f, b, err := m.Open(r.Context(), id)
	// a picture the staff took down is served to nobody, wherever it is still named
	if err != nil || b.Kind != kind || b.Encrypted || b.Blocked || !PublicKinds[kind] {
		if f != nil {
			f.Close()
		}
		return false
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", b.Mime)
	h.Set("Cache-Control", "public, max-age=31536000, immutable")
	h.Set("ETag", `"`+b.SHA256+`"`)
	h.Set("Content-Security-Policy", "default-src 'none'; sandbox")
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.UnixMilli(b.CreatedAt), f)
	return true
}

// ServeEncrypted writes attachment ciphertext, or sends the app to the
// bucket for it once it has moved there; the caller has already checked access.
func (m *Store) ServeEncrypted(w http.ResponseWriter, r *http.Request, b *store.Blob) {
	f, err := os.Open(m.Path(shaOf(b)))
	if err != nil {
		if !m.sendToBucket(w, r, b) {
			http.Error(w, "not found", http.StatusNotFound)
		}
		return
	}
	defer f.Close()
	h := w.Header()
	h.Set("Content-Type", "application/octet-stream")
	h.Set("Cache-Control", "private, max-age=31536000, immutable")
	h.Set("Content-Length", strconv.FormatInt(b.Size, 10))
	h.Set("X-Content-Type-Options", "nosniff")
	http.ServeContent(w, r, "", time.UnixMilli(b.CreatedAt), f)
}
