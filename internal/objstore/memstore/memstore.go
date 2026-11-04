// Package memstore is a bucket in memory for tests: what Nexa's media store
// asks of R2, multipart uploads and streamed bodies included, refusing a
// stream that ends early as R2 does (objstore's live tests check it).
package memstore

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"sync"
	"time"

	"evilion.xyz/chat/internal/objstore"
)

// Bucket is safe for concurrent use. The zero value is empty and ready.
type Bucket struct {
	mu      sync.Mutex
	objs    map[string][]byte
	uploads map[string]*upload
	seq     int
}

type upload struct {
	key   string
	parts map[int][]byte
}

func (b *Bucket) Put(_ context.Context, key string, f io.ReaderAt, size int64, _ string) error {
	data, err := io.ReadAll(io.NewSectionReader(f, 0, size))
	if err != nil {
		return err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.objs == nil {
		b.objs = map[string][]byte{}
	}
	b.objs[key] = data
	return nil
}

func (b *Bucket) Head(_ context.Context, key string) (int64, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if d, ok := b.objs[key]; ok {
		return int64(len(d)), nil
	}
	return 0, objstore.ErrNotFound
}

// Has says whether key holds an object.
func (b *Bucket) Has(key string) bool {
	_, err := b.Head(context.Background(), key)
	return err == nil
}

func (b *Bucket) Delete(_ context.Context, key string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.objs, key)
	return nil
}

func (b *Bucket) Get(_ context.Context, key string, from, n int64) (io.ReadCloser, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	d, ok := b.objs[key]
	if !ok {
		return nil, objstore.ErrNotFound
	}
	d = d[from:]
	if n > 0 {
		d = d[:n]
	}
	return io.NopCloser(bytes.NewReader(d)), nil
}

func (b *Bucket) Presign(key string, _ time.Duration) string {
	return "https://bucket.test/" + key + "?X-Amz-Signature=x"
}

func (b *Bucket) CreateMultipart(_ context.Context, key, _ string) (string, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.uploads == nil {
		b.uploads = map[string]*upload{}
	}
	b.seq++
	id := "up" + strconv.Itoa(b.seq)
	b.uploads[id] = &upload{key: key, parts: map[int][]byte{}}
	return id, nil
}

// ErrShort is a stream that ended before its declared size.
var ErrShort = errors.New("memstore: the body ended early")

func readExactly(r io.Reader, size int64) ([]byte, error) {
	data, err := io.ReadAll(io.LimitReader(r, size))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) != size {
		return nil, ErrShort
	}
	return data, nil
}

func (b *Bucket) PutStream(_ context.Context, key string, r io.Reader, size int64, _ string) error {
	data, err := readExactly(r, size)
	if err != nil {
		return err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.objs == nil {
		b.objs = map[string][]byte{}
	}
	b.objs[key] = data
	return nil
}

func (b *Bucket) PutPartStream(_ context.Context, key, uploadID string, n int, r io.Reader, size int64) (objstore.Part, error) {
	data, err := readExactly(r, size)
	if err != nil {
		return objstore.Part{}, err
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	up := b.uploads[uploadID]
	if up == nil || up.key != key {
		return objstore.Part{}, &objstore.Error{Status: 404, Code: "NoSuchUpload"}
	}
	up.parts[n] = data
	return objstore.Part{Number: n, ETag: fmt.Sprintf(`"%d-%d"`, n, len(data)), Size: size}, nil
}

func (b *Bucket) ListParts(_ context.Context, key, uploadID string) ([]objstore.Part, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	up := b.uploads[uploadID]
	if up == nil || up.key != key {
		return nil, &objstore.Error{Status: 404, Code: "NoSuchUpload"}
	}
	var out []objstore.Part
	for n, d := range up.parts {
		out = append(out, objstore.Part{Number: n, ETag: fmt.Sprintf(`"%d-%d"`, n, len(d)), Size: int64(len(d))})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Number < out[j].Number })
	return out, nil
}

func (b *Bucket) CompleteMultipart(_ context.Context, key, uploadID string, parts []objstore.Part) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	up := b.uploads[uploadID]
	if up == nil || up.key != key {
		return &objstore.Error{Status: 404, Code: "NoSuchUpload"}
	}
	var all []byte
	for i, p := range parts {
		d, ok := up.parts[p.Number]
		if !ok {
			return &objstore.Error{Status: 400, Code: "InvalidPart"}
		}
		if i < len(parts)-1 && len(d) != len(up.parts[parts[0].Number]) {
			return &objstore.Error{Status: 400, Code: "InvalidPart", Message: "All non-trailing parts must have the same length."}
		}
		all = append(all, d...)
	}
	if b.objs == nil {
		b.objs = map[string][]byte{}
	}
	b.objs[key] = all
	delete(b.uploads, uploadID)
	return nil
}

func (b *Bucket) AbortMultipart(_ context.Context, _, uploadID string) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	delete(b.uploads, uploadID)
	return nil
}

// Open says how many multipart uploads are unfinished.
func (b *Bucket) Open() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.uploads)
}
