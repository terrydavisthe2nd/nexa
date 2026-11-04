package objstore

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// AWS's own worked examples for Signature Version 4 with S3 ("GET Object"
// with a Range header, and the presigned URL example), with the signatures
// AWS publishes for them. R2 checks signatures the same way, so a client
// that drifts from these is refused by the bucket.
func TestSignatureMatchesAWSExamples(t *testing.T) {
	at := time.Date(2013, 5, 24, 0, 0, 0, 0, time.UTC)
	s := signer{access: "AKIAIOSFODNN7EXAMPLE", secret: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", region: "us-east-1"}

	req, _ := http.NewRequest(http.MethodGet, "https://examplebucket.s3.amazonaws.com/test.txt", nil)
	req.Header.Set("Range", "bytes=0-9")
	s.sign(req, emptySHA, at)
	want := "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
	if got := req.Header.Get("Authorization"); got != want {
		t.Fatalf("GET Object:\n got %s\nwant %s", got, want)
	}

	u, _ := url.Parse("https://examplebucket.s3.amazonaws.com/test.txt")
	link := s.presign(http.MethodGet, u, 24*time.Hour, at)
	if !strings.HasSuffix(link, "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404") {
		t.Fatalf("presigned URL: %s", link)
	}
}

// Cloudflare's measured numbers, in the shape its GraphQL API answered for
// the test bucket on 2026-11-04: each operation in its billing class, free
// ones left out, one the price list does not name counted high, and the
// latest hour's bytes with their metadata.
func TestUsageFromCloudflare(t *testing.T) {
	var asked map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok" {
			http.Error(w, "no", http.StatusForbidden)
			return
		}
		var body map[string]any
		json.NewDecoder(r.Body).Decode(&body)
		asked, _ = body["variables"].(map[string]any)
		w.Write([]byte(`{"data":{"viewer":{"accounts":[{"ops":[
			{"dimensions":{"actionType":"CreateMultipartUpload"},"sum":{"requests":2}},
			{"dimensions":{"actionType":"HeadObject"},"sum":{"requests":9}},
			{"dimensions":{"actionType":"UploadPart"},"sum":{"requests":5}},
			{"dimensions":{"actionType":"PutObject"},"sum":{"requests":4}},
			{"dimensions":{"actionType":"GetObject"},"sum":{"requests":40}},
			{"dimensions":{"actionType":"DeleteObject"},"sum":{"requests":4}},
			{"dimensions":{"actionType":"GetBucketNotificationConfiguration"},"sum":{"requests":3}},
			{"dimensions":{"actionType":"SomethingNew"},"sum":{"requests":1}}],
			"storage":[{"dimensions":{"datetime":"2026-11-04T17:00:00Z"},"max":{"metadataSize":172,"objectCount":4,"payloadSize":110828213}}]}]}},"errors":null}`))
	}))
	defer srv.Close()
	a := NewAnalytics("acct", "tok")
	a.Base = srv.URL
	since := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	u, err := a.Usage(t.Context(), "nexa-media-test", since, since.Add(72*time.Hour))
	if err != nil {
		t.Fatal(err)
	}
	if u.ClassA != 2+5+4+1 || u.ClassB != 9+40+3 || u.StoredBytes != 110828213+172 || !u.StoredAt.Equal(time.Date(2026, 11, 4, 17, 0, 0, 0, time.UTC)) {
		t.Fatalf("usage: %+v", u)
	}
	if asked["bucket"] != "nexa-media-test" || asked["account"] != "acct" || asked["since"] != "2026-11-01T00:00:00Z" {
		t.Fatalf("asked for %v", asked)
	}
	a.Token = "wrong"
	if _, err := a.Usage(t.Context(), "nexa-media-test", since, since.Add(time.Hour)); err == nil {
		t.Fatal("a refused token read as no usage")
	}
}
