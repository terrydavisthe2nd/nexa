package api

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAndroidReleasesAreServed(t *testing.T) {
	srv, ts := newTestServer(t)
	dir := t.TempDir()
	srv.Cfg.AndroidDir = dir

	// nothing published: an empty list rather than an error, so the page simply leaves Android out
	res, body := do(t, ts, "GET", "/api/v1/android/latest", "", nil)
	if res.StatusCode != 200 || body["version"] != "" || len(body["files"].([]any)) != 0 || res.Header.Get("Cache-Control") != "no-cache" {
		t.Fatalf("before publishing: %d %v %v", res.StatusCode, body, res.Header)
	}
	if res, _ := do(t, ts, "GET", "/.well-known/assetlinks.json", "", nil); res.StatusCode != 404 {
		t.Errorf("app links before publishing: %d", res.StatusCode)
	}

	apks := map[string]string{
		"nexa-android-arm64-v8a.apk":   "sixty-four bit build",
		"nexa-android-armeabi-v7a.apk": "thirty-two",
		"nexa-android-universal.apk":   "every phone there is",
	}
	hash := map[string]string{}
	for name, content := range apks {
		sum := sha256.Sum256([]byte(content))
		hash[name] = hex.EncodeToString(sum[:])
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	entry := func(abi, name string) map[string]any {
		return map[string]any{"abi": abi, "name": name, "size": len(apks[name]), "sha256": hash[name]}
	}
	cert := strings.TrimSuffix(strings.Repeat("AB:", 32), ":")
	manifest := map[string]any{
		"version": "1.0.0", "version_code": 1, "released_at": 1800000000000, "min_sdk": 26, "min_android": "8.0",
		"package": "fish.nexa", "signing_sha256": cert, "junk": "dropped",
		"files": []any{
			entry("arm64-v8a", "nexa-android-arm64-v8a.apk"),
			entry("armeabi-v7a", "nexa-android-armeabi-v7a.apk"),
			entry("universal", "nexa-android-universal.apk"),
			// named but never committed: not offered
			map[string]any{"abi": "x86_64", "name": "nexa-android-x86_64.apk", "size": 3, "sha256": strings.Repeat("0", 64)},
			// a name that would leave the folder
			map[string]any{"abi": "universal", "name": "../chat.db", "size": 1, "sha256": strings.Repeat("0", 64)},
		},
	}
	raw, _ := json.Marshal(manifest)
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), raw, 0o644); err != nil {
		t.Fatal(err)
	}

	res, body = do(t, ts, "GET", "/api/v1/android/latest", "", nil)
	files, _ := body["files"].([]any)
	if res.StatusCode != 200 || body["version"] != "1.0.0" || body["min_android"] != "8.0" || body["min_sdk"] != float64(26) || len(files) != 3 || body["junk"] != nil {
		t.Fatalf("latest: %d %v", res.StatusCode, body)
	}
	if f := files[0].(map[string]any); f["abi"] != "arm64-v8a" || f["name"] != "nexa-android-arm64-v8a.apk" || f["size"] != float64(len(apks["nexa-android-arm64-v8a.apk"])) || f["sha256"] != hash["nexa-android-arm64-v8a.apk"] {
		t.Errorf("file entry: %v", f)
	}

	get := func(path string, headers map[string]string) (*http.Response, string) {
		t.Helper()
		req, _ := http.NewRequest("GET", ts.URL+path, nil)
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		r, err := http.DefaultTransport.RoundTrip(req)
		if err != nil {
			t.Fatal(err)
		}
		defer r.Body.Close()
		b, _ := io.ReadAll(r.Body)
		return r, string(b)
	}
	name := "nexa-android-arm64-v8a.apk"
	r, b := get("/android/"+name, nil)
	if r.StatusCode != 200 || b != apks[name] || r.Header.Get("Content-Type") != "application/vnd.android.package-archive" || !strings.Contains(r.Header.Get("Content-Disposition"), `attachment; filename="`+name+`"`) {
		t.Fatalf("apk: %d %q %v", r.StatusCode, b, r.Header)
	}
	// the name stays the same from version to version, so a bare link is always checked
	if r.Header.Get("Cache-Control") != "no-cache" || r.Header.Get("ETag") != `"`+hash[name]+`"` {
		t.Errorf("bare link caching: %v", r.Header)
	}
	// the page's links carry the hash, and those may be kept for good
	if r, _ := get("/android/"+name+"?v="+hash[name][:12], nil); !strings.Contains(r.Header.Get("Cache-Control"), "immutable") {
		t.Errorf("hashed link caching: %v", r.Header)
	}
	// an old link's hash no longer matches: the current build, checked every time
	if r, b := get("/android/"+name+"?v=0123456789ab", nil); r.StatusCode != 200 || b != apks[name] || r.Header.Get("Cache-Control") != "no-cache" {
		t.Errorf("stale hashed link: %d %v", r.StatusCode, r.Header)
	}
	// resumable, and answered from the browser's copy when it is still current
	if r, b := get("/android/"+name, map[string]string{"Range": "bytes=6-"}); r.StatusCode != 206 || b != apks[name][6:] {
		t.Errorf("range: %d %q", r.StatusCode, b)
	}
	if r, _ := get("/android/"+name, map[string]string{"If-None-Match": `"` + hash[name] + `"`}); r.StatusCode != 304 {
		t.Errorf("revalidation: %d", r.StatusCode)
	}
	if r, _ := get("/android/"+name, map[string]string{"Range": "bytes=6-", "If-Range": `"` + strings.Repeat("f", 64) + `"`}); r.StatusCode != 200 {
		t.Errorf("a resume of another build starts over: %d", r.StatusCode)
	}
	for _, bad := range []string{"/android/nexa-android-x86_64.apk", "/android/..%2Fmanifest.json", "/android/manifest.json", "/android/.hidden.apk", "/android/", "/android/a/b.apk"} {
		if r, _ := get(bad, nil); r.StatusCode != 404 {
			t.Errorf("%s: %d", bad, r.StatusCode)
		}
	}
	if r, _ := get("/android", nil); r.StatusCode != http.StatusFound || r.Header.Get("Location") != "/download" {
		t.Errorf("/android: %d %v", r.StatusCode, r.Header)
	}

	// app links name the package and the certificate the builds are signed with
	res, body = do(t, ts, "GET", "/.well-known/assetlinks.json", "", nil)
	list, _ := body["_list"].([]any)
	if res.StatusCode != 200 || len(list) != 1 {
		t.Fatalf("assetlinks: %d %v", res.StatusCode, body)
	}
	target := list[0].(map[string]any)["target"].(map[string]any)
	if target["package_name"] != "fish.nexa" || target["sha256_cert_fingerprints"].([]any)[0] != cert {
		t.Errorf("assetlinks target: %v", target)
	}

	// a build replaced without its manifest no longer matches it, and is not offered until the manifest catches up
	if err := os.WriteFile(filepath.Join(dir, name), []byte("a different build"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, body := do(t, ts, "GET", "/api/v1/android/latest", "", nil); len(body["files"].([]any)) != 2 {
		t.Errorf("mismatched build still listed: %v", body["files"])
	}
	if r, _ := get("/android/"+name, nil); r.StatusCode != 404 {
		t.Errorf("mismatched build still served: %d", r.StatusCode)
	}

	// a manifest that cannot be read is the same as none
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), []byte("{not json"), 0o644); err != nil {
		t.Fatal(err)
	}
	if res, body := do(t, ts, "GET", "/api/v1/android/latest", "", nil); res.StatusCode != 200 || len(body["files"].([]any)) != 0 {
		t.Errorf("broken manifest: %d %v", res.StatusCode, body)
	}
}

func TestAndroidOTAPackIsServedOnlyForThePublishedAPK(t *testing.T) {
	srv, ts := newTestServer(t)
	dir := t.TempDir()
	srv.Cfg.AndroidDir = dir
	apk := "a build"
	apkSum := sha256.Sum256([]byte(apk))
	os.WriteFile(filepath.Join(dir, "nexa-android-universal.apk"), []byte(apk), 0o644)
	man, _ := json.Marshal(map[string]any{"version": "1.2.3", "version_code": 7, "files": []any{
		map[string]any{"abi": "universal", "name": "nexa-android-universal.apk", "size": len(apk), "sha256": hex.EncodeToString(apkSum[:])},
	}})
	os.WriteFile(filepath.Join(dir, "manifest.json"), man, 0o644)

	if _, body := do(t, ts, "GET", "/api/v1/android/ota", "", nil); body["id"] != nil {
		t.Fatalf("no pack yet: %v", body)
	}
	zip := "PK pretend zip"
	sum := sha256.Sum256([]byte(zip))
	h := hex.EncodeToString(sum[:])
	id := h[:16]
	name := "ota-" + id + ".zip"
	os.WriteFile(filepath.Join(dir, name), []byte(zip), 0o644)
	write := func(code int) {
		raw, _ := json.Marshal(map[string]any{"id": id, "version_code": code, "name": name, "size": len(zip), "sha256": h})
		os.WriteFile(filepath.Join(dir, "ota.json"), raw, 0o644)
	}
	write(6) // made for an older APK: not offered
	if _, body := do(t, ts, "GET", "/api/v1/android/ota", "", nil); body["id"] != nil {
		t.Fatalf("stale pack offered: %v", body)
	}
	if res, _ := do(t, ts, "GET", "/android/"+name, "", nil); res.StatusCode != 404 {
		t.Fatalf("stale pack served: %d", res.StatusCode)
	}
	write(7)
	if _, body := do(t, ts, "GET", "/api/v1/android/ota", "", nil); body["id"] != id || body["sha256"] != h {
		t.Fatalf("pack: %v", body)
	}
	res, err := http.Get(ts.URL + "/android/" + name)
	if err != nil {
		t.Fatal(err)
	}
	got, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || string(got) != zip {
		t.Fatalf("download: %d %q", res.StatusCode, got)
	}
	if res, _ := do(t, ts, "GET", "/android/ota-0000000000000000.zip", "", nil); res.StatusCode != 404 {
		t.Fatalf("unknown pack: %d", res.StatusCode)
	}
}
