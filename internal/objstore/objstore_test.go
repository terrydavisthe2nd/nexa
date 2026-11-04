package objstore

import (
	"net/http"
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
