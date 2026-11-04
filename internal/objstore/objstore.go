// Package objstore is a small client for S3-compatible object storage
// (Cloudflare R2): the handful of calls Nexa needs to keep files in a bucket,
// signed with AWS Signature Version 4. Standard library only.
package objstore

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// ErrNotFound is a key the bucket does not hold.
var ErrNotFound = errors.New("objstore: no such key")

// Error is a refusal from the bucket, with the S3 error code when it sent one.
type Error struct {
	Status  int
	Code    string
	Message string
}

func (e *Error) Error() string {
	if e.Code == "" {
		return fmt.Sprintf("objstore: HTTP %d", e.Status)
	}
	return fmt.Sprintf("objstore: HTTP %d %s: %s", e.Status, e.Code, e.Message)
}

// Is makes a 404 match ErrNotFound.
func (e *Error) Is(target error) bool {
	return target == ErrNotFound && e.Status == http.StatusNotFound
}

// The two classes of request R2 bills. Deletes and aborted uploads are free.
const (
	ClassA byte = 'A' // writes: PutObject, CreateMultipartUpload, UploadPart, CompleteMultipartUpload
	ClassB byte = 'B' // reads: GetObject, HeadObject
)

const (
	// SinglePutMax is the largest file sent in one request. Bigger ones go up
	// in parts, so a dropped connection costs one part rather than the file.
	SinglePutMax = 96 << 20
	// PartSize is one part of a multipart upload. R2 wants every part but
	// the last the same size.
	PartSize = 64 << 20
	// partTries is how often one part is sent before the upload gives up.
	partTries = 3
)

// Client talks to one bucket.
type Client struct {
	endpoint *url.URL
	bucket   string
	sign     signer
	http     *http.Client
	// Billed is told about each request the bucket bills, ClassA or ClassB.
	Billed func(class byte)
	now    func() time.Time
	// singleMax and partSize are SinglePutMax and PartSize, smaller in tests.
	singleMax, partSize int64
}

// New makes a client for bucket at endpoint (https://<account>.r2.cloudflarestorage.com
// for R2), signing with the access key pair.
func New(endpoint, bucket, accessKey, secretKey string) (*Client, error) {
	u, err := url.Parse(strings.TrimRight(endpoint, "/"))
	if err != nil || (u.Scheme != "https" && u.Scheme != "http") || u.Host == "" || u.RawQuery != "" {
		return nil, fmt.Errorf("objstore: bad endpoint %q", endpoint)
	}
	if !validKey(bucket) || strings.Contains(bucket, "/") {
		return nil, fmt.Errorf("objstore: bad bucket name %q", bucket)
	}
	if accessKey == "" || secretKey == "" {
		return nil, errors.New("objstore: an access key ID and secret are both needed")
	}
	return &Client{
		endpoint: u,
		bucket:   bucket,
		sign:     signer{access: accessKey, secret: secretKey, region: "auto"},
		http: &http.Client{Transport: &http.Transport{
			// the bucket is a fixed, trusted address: no proxy from the environment
			Proxy:                 nil,
			DialContext:           (&net.Dialer{Timeout: 15 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
			TLSHandshakeTimeout:   15 * time.Second,
			ResponseHeaderTimeout: 2 * time.Minute,
			IdleConnTimeout:       90 * time.Second,
			MaxIdleConnsPerHost:   4,
			ForceAttemptHTTP2:     true,
		}},
		now:       time.Now,
		singleMax: SinglePutMax,
		partSize:  PartSize,
	}, nil
}

// validKey keeps keys to characters that need no escaping, so the path that
// is signed is the path that is sent.
func validKey(key string) bool {
	if key == "" || len(key) > 1024 || key[0] == '/' || strings.Contains(key, "//") || strings.Contains(key, "..") {
		return false
	}
	for i := 0; i < len(key); i++ {
		c := key[i]
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.' || c == '/') {
			return false
		}
	}
	return true
}

// objectURL is the path-style address of key.
func (c *Client) objectURL(key string) *url.URL {
	u := *c.endpoint
	u.Path = c.endpoint.Path + "/" + c.bucket + "/" + key
	u.RawPath = ""
	u.RawQuery = ""
	return &u
}

// do sends one signed request and turns a refusal into an *Error. The
// caller closes the body of the response it gets.
func (c *Client) do(ctx context.Context, method, key string, q url.Values, body io.Reader, size int64, payload string, hdr map[string]string, class byte) (*http.Response, error) {
	if !validKey(key) {
		return nil, fmt.Errorf("objstore: bad key %q", key)
	}
	u := c.objectURL(key)
	u.RawQuery = canonicalQuery(q)
	req, err := http.NewRequestWithContext(ctx, method, u.String(), body)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.ContentLength = size
	}
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	c.sign.sign(req, payload, c.now())
	if class != 0 && c.Billed != nil {
		c.Billed(class)
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return nil, err
	}
	if resp.StatusCode >= 300 {
		defer resp.Body.Close()
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64<<10))
		return nil, parseError(resp.StatusCode, raw)
	}
	return resp, nil
}

func parseError(status int, raw []byte) error {
	var e struct {
		Code    string `xml:"Code"`
		Message string `xml:"Message"`
	}
	_ = xml.Unmarshal(raw, &e)
	return &Error{Status: status, Code: e.Code, Message: e.Message}
}

// Put stores size bytes of f under key: in one request up to SinglePutMax,
// else in parts. Every request carries the SHA-256 of what it sends, which
// the bucket checks, so bytes damaged on the way are refused, never kept.
func (c *Client) Put(ctx context.Context, key string, f io.ReaderAt, size int64, contentType string) error {
	if size <= c.singleMax {
		sum, err := hashSection(f, 0, size)
		if err != nil {
			return err
		}
		resp, err := c.do(ctx, http.MethodPut, key, nil, io.NewSectionReader(f, 0, size), size, sum, map[string]string{"Content-Type": contentType}, ClassA)
		if err != nil {
			return err
		}
		resp.Body.Close()
		return nil
	}
	return c.putParts(ctx, key, f, size, contentType)
}

type completedPart struct {
	Number int    `xml:"PartNumber"`
	ETag   string `xml:"ETag"`
}

type completeUpload struct {
	XMLName xml.Name        `xml:"CompleteMultipartUpload"`
	Parts   []completedPart `xml:"Part"`
}

func (c *Client) putParts(ctx context.Context, key string, f io.ReaderAt, size int64, contentType string) error {
	resp, err := c.do(ctx, http.MethodPost, key, url.Values{"uploads": {""}}, nil, 0, emptySHA, map[string]string{"Content-Type": contentType}, ClassA)
	if err != nil {
		return err
	}
	var start struct {
		UploadID string `xml:"UploadId"`
	}
	err = xml.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&start)
	resp.Body.Close()
	if err != nil || start.UploadID == "" {
		return fmt.Errorf("objstore: starting a multipart upload: %v", err)
	}
	// Free, and best effort: the bucket's lifecycle rule clears what a
	// failed abort leaves.
	abort := func() {
		actx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 30*time.Second)
		defer cancel()
		if r, err := c.do(actx, http.MethodDelete, key, url.Values{"uploadId": {start.UploadID}}, nil, 0, emptySHA, nil, 0); err == nil {
			r.Body.Close()
		}
	}
	var parts []completedPart
	for n, off := 1, int64(0); off < size; n, off = n+1, off+c.partSize {
		etag, err := c.putPart(ctx, key, start.UploadID, n, f, off, min(c.partSize, size-off))
		if err != nil {
			abort()
			return err
		}
		parts = append(parts, completedPart{Number: n, ETag: etag})
	}
	body, err := xml.Marshal(completeUpload{Parts: parts})
	if err != nil {
		abort()
		return err
	}
	sum := sha256.Sum256(body)
	resp, err = c.do(ctx, http.MethodPost, key, url.Values{"uploadId": {start.UploadID}}, bytes.NewReader(body), int64(len(body)), hex.EncodeToString(sum[:]), map[string]string{"Content-Type": "application/xml"}, ClassA)
	if err != nil {
		abort()
		return err
	}
	defer resp.Body.Close()
	// S3 may answer 200 and still name an error in the body
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if bytes.Contains(raw, []byte("<Error>")) {
		abort()
		return parseError(resp.StatusCode, raw)
	}
	return nil
}

// putPart sends one part, again when the connection drops or the bucket
// fails, and returns its ETag.
func (c *Client) putPart(ctx context.Context, key, uploadID string, n int, f io.ReaderAt, off, length int64) (string, error) {
	sum, err := hashSection(f, off, length)
	if err != nil {
		return "", err
	}
	q := url.Values{"partNumber": {strconv.Itoa(n)}, "uploadId": {uploadID}}
	var last error
	for try := 0; try < partTries; try++ {
		if try > 0 {
			select {
			case <-time.After(time.Duration(try) * 2 * time.Second):
			case <-ctx.Done():
				return "", ctx.Err()
			}
		}
		resp, err := c.do(ctx, http.MethodPut, key, q, io.NewSectionReader(f, off, length), length, sum, nil, ClassA)
		if err != nil {
			last = err
			var refused *Error
			if errors.As(err, &refused) && refused.Status < 500 {
				return "", err // refused, not dropped: sending it again changes nothing
			}
			continue
		}
		etag := resp.Header.Get("ETag")
		resp.Body.Close()
		if etag == "" {
			last = errors.New("objstore: a part came back without an ETag")
			continue
		}
		return etag, nil
	}
	return "", last
}

// Head returns the size of the object under key, or ErrNotFound.
func (c *Client) Head(ctx context.Context, key string) (int64, error) {
	resp, err := c.do(ctx, http.MethodHead, key, nil, nil, 0, emptySHA, nil, ClassB)
	if err != nil {
		return 0, err
	}
	resp.Body.Close()
	return resp.ContentLength, nil
}

// Get reads the object under key from byte from: n bytes, or to its end
// when n is 0.
func (c *Client) Get(ctx context.Context, key string, from, n int64) (io.ReadCloser, error) {
	var hdr map[string]string
	switch {
	case n > 0:
		hdr = map[string]string{"Range": fmt.Sprintf("bytes=%d-%d", from, from+n-1)}
	case from > 0:
		hdr = map[string]string{"Range": fmt.Sprintf("bytes=%d-", from)}
	}
	resp, err := c.do(ctx, http.MethodGet, key, nil, nil, 0, emptySHA, hdr, ClassB)
	if err != nil {
		return nil, err
	}
	if hdr != nil && resp.StatusCode != http.StatusPartialContent {
		resp.Body.Close()
		return nil, fmt.Errorf("objstore: asked for a range and got HTTP %d", resp.StatusCode)
	}
	return resp.Body, nil
}

// Delete removes the object under key. One already gone counts as deleted.
func (c *Client) Delete(ctx context.Context, key string) error {
	resp, err := c.do(ctx, http.MethodDelete, key, nil, nil, 0, emptySHA, nil, 0)
	if errors.Is(err, ErrNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	resp.Body.Close()
	return nil
}

// Presign returns a link anyone may GET the object under key with, for ttl.
// It is signed here and costs nothing until it is used.
func (c *Client) Presign(key string, ttl time.Duration) string {
	return c.sign.presign(http.MethodGet, c.objectURL(key), ttl, c.now())
}

func hashSection(f io.ReaderAt, off, n int64) (string, error) {
	h := sha256.New()
	if _, err := io.Copy(h, io.NewSectionReader(f, off, n)); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// Getter is what a Reader reads through (a *Client, or a stand-in in tests).
type Getter interface {
	Get(ctx context.Context, key string, from, n int64) (io.ReadCloser, error)
}

// Reader reads one object like a file: reads in order share one request,
// a seek starts another, and ReadAt asks for exactly the bytes it wants.
type Reader struct {
	g    Getter
	ctx  context.Context
	key  string
	size int64
	pos  int64
	body io.ReadCloser
}

// NewReader reads the size-byte object under key through g.
func NewReader(ctx context.Context, g Getter, key string, size int64) *Reader {
	return &Reader{g: g, ctx: ctx, key: key, size: size}
}

func (r *Reader) Read(p []byte) (int, error) {
	if r.pos >= r.size {
		return 0, io.EOF
	}
	if r.body == nil {
		b, err := r.g.Get(r.ctx, r.key, r.pos, 0)
		if err != nil {
			return 0, err
		}
		r.body = b
	}
	n, err := r.body.Read(p)
	r.pos += int64(n)
	if err == io.EOF && r.pos < r.size {
		err = io.ErrUnexpectedEOF
	}
	return n, err
}

func (r *Reader) Seek(offset int64, whence int) (int64, error) {
	switch whence {
	case io.SeekStart:
	case io.SeekCurrent:
		offset += r.pos
	case io.SeekEnd:
		offset += r.size
	default:
		return r.pos, errors.New("objstore: bad whence")
	}
	if offset < 0 {
		return r.pos, errors.New("objstore: seek before the start")
	}
	if offset != r.pos && r.body != nil {
		r.body.Close()
		r.body = nil
	}
	r.pos = offset
	return offset, nil
}

func (r *Reader) ReadAt(p []byte, off int64) (int, error) {
	if off < 0 {
		return 0, errors.New("objstore: negative offset")
	}
	if off >= r.size {
		return 0, io.EOF
	}
	n := min(int64(len(p)), r.size-off)
	b, err := r.g.Get(r.ctx, r.key, off, n)
	if err != nil {
		return 0, err
	}
	defer b.Close()
	got, err := io.ReadFull(b, p[:n])
	if err == nil && n < int64(len(p)) {
		err = io.EOF
	}
	return got, err
}

func (r *Reader) Close() error {
	if r.body != nil {
		err := r.body.Close()
		r.body = nil
		return err
	}
	return nil
}
