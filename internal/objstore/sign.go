package objstore

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
)

// AWS Signature Version 4, as S3 (and R2, which speaks it) checks it:
// https://docs.aws.amazon.com/AmazonS3/latest/API/sig-v4-authenticating-requests.html

const (
	algorithm       = "AWS4-HMAC-SHA256"
	amzFormat       = "20060102T150405Z"
	emptySHA        = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	unsignedPayload = "UNSIGNED-PAYLOAD"
)

type signer struct {
	access, secret, region string
}

func hmacSHA(key []byte, data string) []byte {
	m := hmac.New(sha256.New, key)
	m.Write([]byte(data))
	return m.Sum(nil)
}

func hexSHA(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

// key derives the signing key for one day.
func (s signer) key(day string) []byte {
	k := hmacSHA([]byte("AWS4"+s.secret), day)
	k = hmacSHA(k, s.region)
	k = hmacSHA(k, "s3")
	return hmacSHA(k, "aws4_request")
}

// sign adds x-amz-date, x-amz-content-sha256 and the Authorization header.
// req.URL.RawQuery must already be canonical (canonicalQuery), which is how
// Client builds it, so what is signed is what is sent.
func (s signer) sign(req *http.Request, payloadHash string, at time.Time) {
	amz := at.UTC().Format(amzFormat)
	req.Header.Set("X-Amz-Date", amz)
	req.Header.Set("X-Amz-Content-Sha256", payloadHash)
	host := req.Host
	if host == "" {
		host = req.URL.Host
	}
	names, block := canonicalHeaders(req.Header, host)
	creq := strings.Join([]string{req.Method, uriEncode(req.URL.Path, false), req.URL.RawQuery, block, names, payloadHash}, "\n")
	scope := amz[:8] + "/" + s.region + "/s3/aws4_request"
	sts := algorithm + "\n" + amz + "\n" + scope + "\n" + hexSHA(creq)
	sig := hex.EncodeToString(hmacSHA(s.key(amz[:8]), sts))
	req.Header.Set("Authorization", algorithm+" Credential="+s.access+"/"+scope+", SignedHeaders="+names+", Signature="+sig)
}

// presign returns u with the query parameters that let anyone holding it
// make this request, for ttl.
func (s signer) presign(method string, u *url.URL, ttl time.Duration, at time.Time) string {
	amz := at.UTC().Format(amzFormat)
	scope := amz[:8] + "/" + s.region + "/s3/aws4_request"
	q := u.Query()
	q.Set("X-Amz-Algorithm", algorithm)
	q.Set("X-Amz-Credential", s.access+"/"+scope)
	q.Set("X-Amz-Date", amz)
	q.Set("X-Amz-Expires", strconv.FormatInt(int64(ttl/time.Second), 10))
	q.Set("X-Amz-SignedHeaders", "host")
	query := canonicalQuery(q)
	path := uriEncode(u.Path, false)
	creq := method + "\n" + path + "\n" + query + "\nhost:" + u.Host + "\n\nhost\n" + unsignedPayload
	sts := algorithm + "\n" + amz + "\n" + scope + "\n" + hexSHA(creq)
	sig := hex.EncodeToString(hmacSHA(s.key(amz[:8]), sts))
	return u.Scheme + "://" + u.Host + path + "?" + query + "&X-Amz-Signature=" + sig
}

// signedHeader says which headers go into the signature: host, every
// x-amz- header, and the few standard ones S3 asks to be covered.
func signedHeader(name string) bool {
	return strings.HasPrefix(name, "x-amz-") || name == "range" || name == "content-type" || name == "content-md5"
}

// canonicalHeaders returns the signed header names ("host;range;...") and
// the canonical block, one "name:value\n" line each, sorted by name.
func canonicalHeaders(h http.Header, host string) (string, string) {
	vals := map[string]string{"host": host}
	for k, v := range h {
		lk := strings.ToLower(k)
		if !signedHeader(lk) {
			continue
		}
		parts := make([]string, len(v))
		for i, s := range v {
			parts[i] = strings.Join(strings.Fields(s), " ")
		}
		vals[lk] = strings.Join(parts, ",")
	}
	names := make([]string, 0, len(vals))
	for k := range vals {
		names = append(names, k)
	}
	sort.Strings(names)
	var b strings.Builder
	for _, k := range names {
		b.WriteString(k)
		b.WriteByte(':')
		b.WriteString(vals[k])
		b.WriteByte('\n')
	}
	return strings.Join(names, ";"), b.String()
}

// canonicalQuery sorts and encodes q the way the signature needs it; an
// empty value is written "key=".
func canonicalQuery(q url.Values) string {
	if len(q) == 0 {
		return ""
	}
	keys := make([]string, 0, len(q))
	for k := range q {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var pairs []string
	for _, k := range keys {
		vs := append([]string(nil), q[k]...)
		sort.Strings(vs)
		for _, v := range vs {
			pairs = append(pairs, uriEncode(k, true)+"="+uriEncode(v, true))
		}
	}
	return strings.Join(pairs, "&")
}

// uriEncode percent-encodes every byte but the unreserved ones (and '/'
// unless slash is set), uppercase, as SigV4 defines it.
func uriEncode(s string, slash bool) string {
	const hexDigits = "0123456789ABCDEF"
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && !slash) {
			b.WriteByte(c)
			continue
		}
		b.WriteByte('%')
		b.WriteByte(hexDigits[c>>4])
		b.WriteByte(hexDigits[c&15])
	}
	return b.String()
}
