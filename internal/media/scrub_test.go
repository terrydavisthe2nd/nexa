package media

import (
	"bytes"
	"compress/zlib"
	"context"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"flag"
	"hash/crc32"
	"image"
	"image/color"
	"image/gif"
	"image/jpeg"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/image/webp"

	"evilion.xyz/chat/internal/ids"
)

// The files in testdata/scrub are shared with web/src/lib/scrubMetadata.test.ts:
// each <name>.<ext> must come out of both scrubbers as <name>.clean.<ext>, or
// unchanged when there is no such file. They are made by the builders below:
//
//	go test ./internal/media -run TestScrubFixtures -update-scrub
var updateScrub = flag.Bool("update-scrub", false, "rewrite testdata/scrub from the builders in scrub_test.go")

const scrubDir = "testdata/scrub"

// scrubBytes is scrubFile for bytes in memory.
func scrubBytes(data []byte) []byte {
	plan := planScrub(&scrubSource{r: bytes.NewReader(data), size: int64(len(data))})
	if plan == nil {
		return data
	}
	var out []byte
	for _, p := range plan {
		switch {
		case p.lit != nil:
			out = append(out, p.lit...)
		case p.zero:
			out = append(out, make([]byte, p.n)...)
		default:
			out = append(out, data[p.off:p.off+p.n]...)
		}
	}
	return out
}

// What the made-up camera wrote, none of which may be left.
var secrets = []string{"SpyCam", "Phone X1", "EditPro", "SN-4815162342", "2026:09:01", "Hidden Lane", "Secret Place", "GPSLatitude", "+51.5074", "Nora at home"}

func noSecrets(t *testing.T, name string, b []byte) {
	t.Helper()
	for _, s := range secrets {
		if bytes.Contains(b, []byte(s)) {
			t.Errorf("%s: %q is still there", name, s)
		}
	}
}

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(scrubDir, name))
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// cleanName is where a fixture's cleaned form is kept: x.jpg → x.clean.jpg.
func cleanName(name string) string {
	ext := filepath.Ext(name)
	return strings.TrimSuffix(name, ext) + ".clean" + ext
}

func TestScrubFixtures(t *testing.T) {
	if *updateScrub {
		if err := os.RemoveAll(scrubDir); err != nil {
			t.Fatal(err)
		}
		if err := os.MkdirAll(scrubDir, 0o755); err != nil {
			t.Fatal(err)
		}
		for name, in := range buildScrubFixtures(t) {
			if err := os.WriteFile(filepath.Join(scrubDir, name), in, 0o644); err != nil {
				t.Fatal(err)
			}
			if out := scrubBytes(in); !bytes.Equal(out, in) {
				if err := os.WriteFile(filepath.Join(scrubDir, cleanName(name)), out, 0o644); err != nil {
					t.Fatal(err)
				}
			}
		}
	}
	entries, err := os.ReadDir(scrubDir)
	if err != nil {
		t.Fatal(err)
	}
	n := 0
	for _, e := range entries {
		name := e.Name()
		if strings.Contains(name, ".clean.") {
			continue
		}
		n++
		in := fixture(t, name)
		want := in
		if b, err := os.ReadFile(filepath.Join(scrubDir, cleanName(name))); err == nil {
			want = b
		}
		if got := scrubBytes(in); !bytes.Equal(got, want) {
			t.Errorf("%s: scrubbed to %d bytes, want the %d of %s", name, len(got), len(want), cleanName(name))
		}
	}
	if n < 12 {
		t.Fatalf("only %d fixtures in %s", n, scrubDir)
	}
}

// jpegSegments lists a JPEG's segments before its picture data, fill bytes
// skipped: the marker, then the payload.
func jpegSegments(t *testing.T, b []byte) [][]byte {
	t.Helper()
	var segs [][]byte
	for p := 2; ; {
		for p+1 < len(b) && b[p] == 0xff && b[p+1] == 0xff {
			p++
		}
		if p+4 > len(b) || b[p] != 0xff {
			t.Fatalf("no marker at %d", p)
		}
		if b[p+1] == 0xda {
			return segs
		}
		n := int(binary.BigEndian.Uint16(b[p+2:]))
		segs = append(segs, append([]byte{b[p+1]}, b[p+4:p+2+n]...))
		p += 2 + n
	}
}

func decodeRGBA(t *testing.T, b []byte) *image.RGBA {
	t.Helper()
	img, _, err := image.Decode(bytes.NewReader(b))
	if err != nil {
		t.Fatal(err)
	}
	out := image.NewRGBA(img.Bounds())
	for y := img.Bounds().Min.Y; y < img.Bounds().Max.Y; y++ {
		for x := img.Bounds().Min.X; x < img.Bounds().Max.X; x++ {
			out.Set(x, y, img.At(x, y))
		}
	}
	return out
}

func samePicture(t *testing.T, name string, a, b []byte) {
	t.Helper()
	pa, pb := decodeRGBA(t, a), decodeRGBA(t, b)
	if pa.Bounds() != pb.Bounds() || !bytes.Equal(pa.Pix, pb.Pix) {
		t.Errorf("%s: the picture changed: %v → %v", name, pa.Bounds(), pb.Bounds())
	}
}

func TestScrubJPEG(t *testing.T) {
	for _, c := range []struct {
		name   string
		orient int
		order  string
	}{{"jpeg-exif-be.jpg", 6, "MM"}, {"jpeg-exif-le.jpg", 8, "II"}, {"jpeg-upright.jpg", 0, ""}} {
		in := fixture(t, c.name)
		out := scrubBytes(in)
		noSecrets(t, c.name, out)
		samePicture(t, c.name, in, out)
		// everything from the picture data on is as it was
		sos := bytes.Index(in, []byte{0xff, 0xda})
		if !bytes.HasSuffix(out, in[sos:]) {
			t.Errorf("%s: the picture data changed", c.name)
		}
		var kinds []string
		exif := 0
		for _, s := range jpegSegments(t, out) {
			switch {
			case s[0] == 0xe0 && bytes.HasPrefix(s[1:], []byte("JFIF\x00")):
				kinds = append(kinds, "JFIF")
			case s[0] == 0xe1:
				exif++
				kinds = append(kinds, "Exif")
				checkOrientationOnly(t, c.name, s[1:], c.orient, c.order)
			case s[0] == 0xe2 && bytes.HasPrefix(s[1:], []byte("ICC_PROFILE\x00")):
				kinds = append(kinds, "ICC")
			case s[0] == 0xee && bytes.HasPrefix(s[1:], []byte("Adobe")):
				kinds = append(kinds, "Adobe")
			case s[0] >= 0xe0 && s[0] <= 0xef || s[0] == 0xfe:
				t.Errorf("%s: segment %#x left in", c.name, s[0])
			}
		}
		if want := map[bool]int{true: 1, false: 0}[c.orient > 1]; exif != want {
			t.Errorf("%s: %d Exif segments, want %d", c.name, exif, want)
		}
		t.Logf("%s: %v", c.name, kinds)
		if c.orient > 1 && (kinds[0] != "JFIF" || kinds[1] != "Exif") {
			t.Errorf("%s: the orientation is not right after JFIF: %v", c.name, kinds)
		}
		if again := scrubBytes(out); !bytes.Equal(again, out) {
			t.Errorf("%s: a second pass changed it again", c.name)
		}
	}
	// the colour segments stay
	out := scrubBytes(fixture(t, "jpeg-exif-be.jpg"))
	if !bytes.Contains(out, []byte("ICC_PROFILE\x00")) || !bytes.Contains(out, []byte("Adobe")) {
		t.Error("the ICC profile or the Adobe marker went")
	}
	// a JPEG with nothing to take out is left alone
	plain := fixture(t, "jpeg-plain.jpg")
	if out := scrubBytes(plain); &out[0] != &plain[0] {
		t.Error("a plain JPEG was rewritten")
	}
}

// checkOrientationOnly checks an Exif segment's payload holds a valid TIFF
// with one directory and in it the orientation alone.
func checkOrientationOnly(t *testing.T, name string, p []byte, orient int, order string) {
	t.Helper()
	if !bytes.HasPrefix(p, []byte("Exif\x00\x00")) || len(p) != 32 {
		t.Fatalf("%s: Exif segment of %d bytes: %x", name, len(p), p)
	}
	tf := p[6:]
	var bo binary.ByteOrder = binary.BigEndian
	if order == "II" {
		bo = binary.LittleEndian
	}
	if string(tf[:2]) != order || bo.Uint16(tf[2:]) != 42 || bo.Uint32(tf[4:]) != 8 {
		t.Fatalf("%s: TIFF header %x", name, tf[:8])
	}
	if n := bo.Uint16(tf[8:]); n != 1 {
		t.Fatalf("%s: %d entries", name, n)
	}
	e := tf[10:22]
	if bo.Uint16(e) != 0x0112 || bo.Uint16(e[2:]) != 3 || bo.Uint32(e[4:]) != 1 || int(bo.Uint16(e[8:])) != orient {
		t.Errorf("%s: entry %x, want orientation %d", name, e, orient)
	}
	if next := bo.Uint32(tf[22:]); next != 0 {
		t.Errorf("%s: a next directory at %d", name, next)
	}
	if got, gotOrder := exifOrientation(tf); got != orient || gotOrder != order {
		t.Errorf("%s: reads back as %d %s", name, got, gotOrder)
	}
}

func TestExifOrientation(t *testing.T) {
	for _, bo := range []byteOrder{binary.BigEndian, binary.LittleEndian} {
		for v := uint16(0); v <= 9; v++ {
			want := int(v)
			if v < 1 || v > 8 {
				want = 0
			}
			if got, _ := exifOrientation(exifTIFF(bo, v)); got != want {
				t.Errorf("%v %d: got %d", bo, v, got)
			}
		}
		// a LONG, or two values, is not an orientation browsers use
		long := buildTIFF(bo, []ifdEntry{{0x0112, 4, 1, bo.AppendUint32(nil, 6)}}, nil, nil)
		if got, _ := exifOrientation(long); got != 0 {
			t.Errorf("%v: a LONG orientation read as %d", bo, got)
		}
	}
	for _, bad := range [][]byte{nil, []byte("MM"), []byte("XX\x00\x2a\x00\x00\x00\x08"), []byte("MM\x00\x2a\xff\xff\xff\xff"), []byte("MM\x00\x2a\x00\x00\x00\x08\x00\x09")} {
		if got, _ := exifOrientation(bad); got != 0 {
			t.Errorf("%x: %d", bad, got)
		}
	}
}

func pngChunkTypes(t *testing.T, b []byte) []string {
	t.Helper()
	var types []string
	for p := 8; p < len(b); {
		n := int(binary.BigEndian.Uint32(b[p:]))
		if crc32.ChecksumIEEE(b[p+4:p+8+n]) != binary.BigEndian.Uint32(b[p+8+n:]) {
			t.Errorf("chunk %s: bad checksum", b[p+4:p+8])
		}
		types = append(types, string(b[p+4:p+8]))
		p += 12 + n
	}
	return types
}

func TestScrubPNG(t *testing.T) {
	for name, want := range map[string]string{
		"png-text.png":  "IHDR gAMA cHRM sRGB pHYs IDAT IEND",
		"apng-text.png": "IHDR acTL iCCP fcTL IDAT fcTL fdAT IEND",
	} {
		in := fixture(t, name)
		out := scrubBytes(in)
		noSecrets(t, name, out)
		samePicture(t, name, in, out)
		if got := strings.Join(pngChunkTypes(t, out), " "); got != want {
			t.Errorf("%s: chunks %s, want %s", name, got, want)
		}
		if again := scrubBytes(out); !bytes.Equal(again, out) {
			t.Errorf("%s: a second pass changed it again", name)
		}
	}
	// an unknown critical chunk stays, an unknown ancillary one goes
	base := pngBytes(4, 4)
	in := append(append(append([]byte{}, base[:33]...), pngChunk("QRST", []byte("keep"))...), append(pngChunk("qrst", []byte("drop")), base[33:]...)...)
	if got := strings.Join(pngChunkTypes(t, scrubBytes(in)), " "); !strings.HasPrefix(got, "IHDR QRST IDAT") {
		t.Errorf("unknown chunks: %s", got)
	}
	if out := scrubBytes(base); &out[0] != &base[0] {
		t.Error("a plain PNG was rewritten")
	}
}

func riffChunkIDs(t *testing.T, b []byte) []string {
	t.Helper()
	if int(binary.LittleEndian.Uint32(b[4:]))+8 != len(b) {
		t.Errorf("RIFF size %d for %d bytes", binary.LittleEndian.Uint32(b[4:]), len(b))
	}
	var ids []string
	for p := 12; p < len(b); {
		n := int(binary.LittleEndian.Uint32(b[p+4:]))
		ids = append(ids, string(b[p:p+4]))
		p += 8 + n + n&1
	}
	return ids
}

func TestScrubWebP(t *testing.T) {
	still := scrubBytes(fixture(t, "webp-still.webp"))
	noSecrets(t, "still", still)
	if got := strings.Join(riffChunkIDs(t, still), " "); got != "VP8X ICCP VP8L" {
		t.Errorf("still: chunks %s", got)
	}
	if still[20] != 0x20 { // the ICC profile's flag and nothing else
		t.Errorf("still: flags %#x", still[20])
	}
	if img, err := webp.Decode(bytes.NewReader(still)); err != nil || img.Bounds().Dx() != 16 || img.Bounds().Dy() != 8 {
		t.Errorf("still: %v", err)
	}

	in := fixture(t, "webp-anim.webp")
	anim := scrubBytes(in)
	noSecrets(t, "animated", anim)
	// what is left is the animation as libwebp made it
	if !bytes.Equal(anim, mustB64(t, animWebPLossless)) {
		t.Error("animated: not the original animation")
	}
	if got := strings.Join(riffChunkIDs(t, anim), " "); got != "VP8X ANIM ANMF ANMF" {
		t.Errorf("animated: chunks %s", got)
	}
	if mime, animated, _ := Sniff(anim); mime != "image/webp" || !animated {
		t.Error("animated: no longer animated")
	}
	if _, err := firstWebPFrame(anim); err != nil {
		t.Errorf("animated: %v", err)
	}
	if again := scrubBytes(anim); !bytes.Equal(again, anim) {
		t.Error("animated: a second pass changed it again")
	}
}

func TestScrubGIF(t *testing.T) {
	in := fixture(t, "gif-comment.gif")
	out := scrubBytes(in)
	noSecrets(t, "gif", out)
	if bytes.Contains(out, []byte("XMP DataXMP")) || !bytes.Contains(out, []byte("NETSCAPE2.0")) || !bytes.Contains(out, []byte("ICCRGBG1012")) {
		t.Error("the wrong extensions went")
	}
	a, err := gif.DecodeAll(bytes.NewReader(in))
	if err != nil {
		t.Fatal(err)
	}
	b, err := gif.DecodeAll(bytes.NewReader(out))
	if err != nil {
		t.Fatal(err)
	}
	if len(b.Image) != 3 || len(a.Image) != 3 || b.LoopCount != a.LoopCount {
		t.Fatalf("frames %d → %d, loop %d → %d", len(a.Image), len(b.Image), a.LoopCount, b.LoopCount)
	}
	for i := range a.Image {
		if !bytes.Equal(a.Image[i].Pix, b.Image[i].Pix) || a.Delay[i] != b.Delay[i] {
			t.Errorf("frame %d changed", i)
		}
	}
	if out[len(out)-1] != 0x3b {
		t.Error("something is left after the trailer")
	}
	plain := gifBytes(3)
	if out := scrubBytes(plain); &out[0] != &plain[0] {
		t.Error("a plain GIF was rewritten")
	}
}

// mp4Boxes lists the boxes of [start, end) of an MP4 in b as type@offset,
// going into the containers the scrubber goes into.
func mp4Boxes(b []byte, start, end int, into map[string]bool) []string {
	var out []string
	for p := start; p+8 <= end; {
		size, hdr := int(binary.BigEndian.Uint32(b[p:])), 8
		if size == 1 {
			size, hdr = int(binary.BigEndian.Uint64(b[p+8:])), 16
		} else if size == 0 {
			size = end - p
		}
		typ := string(b[p+4 : p+8])
		out = append(out, typ)
		if into[typ] {
			for _, c := range mp4Boxes(b, p+hdr, p+size, into) {
				out = append(out, typ+"/"+c)
			}
		}
		p += size
	}
	return out
}

func TestScrubMP4(t *testing.T) {
	into := map[string]bool{"moov": true, "trak": true, "mdia": true}
	for name, want := range map[string]string{
		"mp4-meta.mp4": "ftyp moov moov/mvhd moov/trak moov/trak/tkhd moov/trak/mdia moov/trak/mdia/mdhd moov/trak/mdia/hdlr moov/trak/mdia/minf moov/trak/free moov/trak/free moov/free moov/free moov/free mdat free free uuid free",
		"mov-meta.mov": "ftyp wide moov moov/mvhd moov/trak moov/trak/tkhd moov/trak/mdia moov/trak/mdia/mdhd moov/trak/mdia/hdlr moov/free moov/free mdat",
	} {
		in := fixture(t, name)
		out := scrubBytes(in)
		noSecrets(t, name, out)
		if len(out) != len(in) {
			t.Fatalf("%s: %d bytes → %d", name, len(in), len(out))
		}
		if got := strings.Join(mp4Boxes(out, 0, len(out), into), " "); got != want {
			t.Errorf("%s: boxes\n%s\nwant\n%s", name, got, want)
		}
		// Every byte that changed is in a box that became free, or is one of
		// the header times; the rest (the index, the media) is untouched.
		allowed := make([]bool, len(in))
		var mark func(start, end int)
		mark = func(start, end int) {
			for p := start; p+8 <= end; {
				size, hdr := int(binary.BigEndian.Uint32(in[p:])), 8
				if size == 1 {
					size, hdr = int(binary.BigEndian.Uint64(in[p+8:])), 16
				} else if size == 0 {
					size = end - p
				}
				switch typ := string(in[p+4 : p+8]); {
				case into[typ]:
					mark(p+hdr, p+size)
				case string(out[p+4:p+8]) == "free" && typ != "free":
					if !allZero(out[p+hdr : p+size]) {
						t.Errorf("%s: a blanked %s still holds something", name, typ)
					}
					for i := p + 4; i < p+size; i++ {
						allowed[i] = true
					}
				case typ == "mvhd" || typ == "tkhd" || typ == "mdhd":
					n := 8
					if in[p+hdr] == 1 {
						n = 16
					}
					if !allZero(out[p+hdr+4 : p+hdr+4+n]) {
						t.Errorf("%s: the times in %s are still there", name, typ)
					}
					for i := p + hdr + 4; i < p+hdr+4+n; i++ {
						allowed[i] = true
					}
				}
				p += size
			}
		}
		mark(0, len(in))
		for i := range in {
			if in[i] != out[i] && !allowed[i] {
				t.Fatalf("%s: byte %d changed", name, i)
			}
		}
		if again := scrubBytes(out); !bytes.Equal(again, out) {
			t.Errorf("%s: a second pass changed it again", name)
		}
	}
	// the chunk offset still points at the frame data
	out := scrubBytes(fixture(t, "mp4-meta.mp4"))
	stco := bytes.Index(out, []byte("stco"))
	off := binary.BigEndian.Uint32(out[stco+12:])
	if !bytes.HasPrefix(out[off:], []byte("FRAME-DATA")) {
		t.Errorf("stco points at %q", out[off:off+10])
	}
}

func TestScrubLeavesOthersAlone(t *testing.T) {
	heif := fixture(t, "heic-exif.heic")
	cases := map[string][]byte{
		"heic":      heif,
		"avif":      bytes.Replace(heif, []byte("heic"), []byte("avif"), -1),
		"m4a":       mp4File("M4A ", mp4Moov(udta())),
		"cr3":       mp4File("crx ", mp4Moov(udta(), mp4Box("uuid", []byte(xmpUUID), []byte(xmpPacket)))),
		"no moov":   mp4File("isom", mp4Box("meta", []byte("SpyCam"))),
		"text":      []byte("hello, world"),
		"empty":     nil,
		"webm":      []byte("\x1a\x45\xdf\xa3\x9f\x42\x86\x81\x01webm"),
		"svg":       []byte("<svg xmlns='http://www.w3.org/2000/svg'/>"),
		"jpeg head": {0xff, 0xd8, 0xff},
	}
	// a PNG built to cost memory: something to take out between every chunk
	base := pngBytes(4, 4)
	costly := append([]byte{}, base[:33]...)
	for i := 0; i <= maxScrubParts; i++ {
		costly = append(append(costly, pngChunk("tEXt", nil)...), pngChunk("gAMA", []byte{0, 0, 0xb1, 0x8f})...)
	}
	cases["costly"] = append(costly, base[33:]...)
	for name, in := range cases {
		if out := scrubBytes(in); !bytes.Equal(out, in) {
			t.Errorf("%s: changed", name)
		}
	}
}

// A file cut short is left as it is, or cleaned as far as it goes when all
// the scrubber has to read is there (a JPEG's head, a video's boxes up to the
// cut), and never turned into anything else.
func TestScrubTruncated(t *testing.T) {
	entries, _ := os.ReadDir(scrubDir)
	for _, e := range entries {
		name := e.Name()
		if strings.Contains(name, ".clean.") {
			continue
		}
		in := fixture(t, name)
		clean := scrubBytes(in)
		sos := -1
		if strings.HasSuffix(name, ".jpg") {
			sos = bytes.Index(in, []byte{0xff, 0xda})
		}
		for n := 0; n < len(in); n++ {
			got := scrubBytes(in[:n])
			if sos >= 0 && n < sos+2 && !bytes.Equal(got, in[:n]) {
				t.Fatalf("%s cut at %d, before the picture data: changed", name, n)
			}
			// as it was, cleaned as far as it goes, or (cut after its end) all of it cleaned
			cut := clean[:max(0, len(clean)-(len(in)-n))]
			if !bytes.Equal(got, in[:n]) && !bytes.Equal(got, cut) && !bytes.Equal(got, clean) {
				t.Fatalf("%s cut at %d: %d bytes of something else", name, n, len(got))
			}
		}
	}
}

func FuzzScrub(f *testing.F) {
	entries, _ := os.ReadDir(scrubDir)
	for _, e := range entries {
		if b, err := os.ReadFile(filepath.Join(scrubDir, e.Name())); err == nil {
			f.Add(b)
		}
	}
	f.Fuzz(func(t *testing.T, in []byte) {
		out := scrubBytes(in)
		if len(out) > len(in)+36 {
			t.Fatalf("grew from %d to %d", len(in), len(out))
		}
		if again := scrubBytes(out); !bytes.Equal(again, out) {
			t.Fatal("a second pass changed it again")
		}
	})
}

func TestScrubFile(t *testing.T) {
	dir := t.TempDir()
	for _, name := range []string{"jpeg-exif-be.jpg", "mp4-meta.mp4", "jpeg-plain.jpg", "heic-exif.heic"} {
		path := filepath.Join(dir, name)
		in := fixture(t, name)
		if err := os.WriteFile(path, in, 0o600); err != nil {
			t.Fatal(err)
		}
		before, _ := os.Stat(path)
		changed, err := scrubFile(path)
		if err != nil {
			t.Fatal(err)
		}
		got, _ := os.ReadFile(path)
		want := scrubBytes(in)
		if !bytes.Equal(got, want) || changed != !bytes.Equal(in, want) {
			t.Errorf("%s: changed=%v, %d bytes, want %d", name, changed, len(got), len(want))
		}
		// a video is written over where it is, not copied
		after, _ := os.Stat(path)
		if strings.HasSuffix(name, ".mp4") && !os.SameFile(before, after) {
			t.Errorf("%s: copied rather than written in place", name)
		}
	}
	if left, _ := filepath.Glob(filepath.Join(dir, "clean-*")); len(left) != 0 {
		t.Errorf("left behind: %v", left)
	}
	if _, err := scrubFile(filepath.Join(dir, "missing")); err == nil {
		t.Error("no error for a missing file")
	}
}

func sha256Hex(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

func storedBytes(t *testing.T, m *Store, id ids.ID) []byte {
	t.Helper()
	f, _, err := m.Open(context.Background(), id)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	b, err := io.ReadAll(f)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestStoredMediaHasNoMetadata(t *testing.T) {
	m, _ := newMedia(t)
	ctx := context.Background()
	owner := ids.New()
	photo := fixture(t, "jpeg-exif-be.jpg")
	clean := scrubBytes(photo)

	// a profile picture
	lim := Limits{MaxBytes: 10 << 20, MaxW: 64, MaxH: 64}
	b, err := m.PutPublic(ctx, "avatar", bytes.NewReader(photo), lim, owner)
	if err != nil {
		t.Fatal(err)
	}
	got := storedBytes(t, m, b.ID)
	noSecrets(t, "avatar", got)
	if !bytes.Equal(got, clean) || b.SHA256 != sha256Hex(got) || b.Size != int64(len(got)) || *b.Width != 16 || *b.Height != 8 {
		t.Errorf("avatar: %d bytes stored, blob %+v", len(got), b)
	}
	// the same photo again, or already clean, is the same file
	for _, again := range [][]byte{photo, clean} {
		b2, err := m.PutPublic(ctx, "avatar", bytes.NewReader(again), lim, owner)
		if err != nil || b2.ID != b.ID {
			t.Errorf("dedupe: %v %+v", err, b2)
		}
	}
	if left, _ := os.ReadDir(filepath.Join(m.Dir, "tmp")); len(left) != 0 {
		t.Errorf("left in tmp: %d", len(left))
	}

	// a webhook's video and picture
	video := fixture(t, "mp4-meta.mp4")
	v, err := m.PutPlain(ctx, bytes.NewReader(video), 10<<20, owner, "video/mp4")
	if err != nil {
		t.Fatal(err)
	}
	got = storedBytes(t, m, v.ID)
	noSecrets(t, "webhook video", got)
	if !bytes.Equal(got, scrubBytes(video)) || v.Size != int64(len(video)) || shaOf(v) != sha256Hex(got) || v.Mime != "video/mp4" {
		t.Errorf("webhook video: %+v", v)
	}
	pic := fixture(t, "png-text.png")
	p, err := m.PutPlain(ctx, bytes.NewReader(pic), 10<<20, owner, "image/png")
	if err != nil {
		t.Fatal(err)
	}
	got = storedBytes(t, m, p.ID)
	noSecrets(t, "webhook picture", got)
	if !bytes.Equal(got, scrubBytes(pic)) || p.Size != int64(len(got)) || p.Mime != "image/png" || *p.Width != 16 {
		t.Errorf("webhook picture: %+v", p)
	}
	// anything else a webhook sends is kept as it came
	doc := []byte("SpyCam notes")
	d, err := m.PutPlain(ctx, bytes.NewReader(doc), 10<<20, owner, "text/plain")
	if err != nil || !bytes.Equal(storedBytes(t, m, d.ID), doc) {
		t.Errorf("text: %v", err)
	}

	// a bug report's screenshot and recording
	plim := PrivateLimits{ImageBytes: 10 << 20, VideoBytes: 10 << 20, MaxSide: 4096, MaxPixels: 1 << 24}
	shot, err := m.PutPrivate(ctx, "bug", bytes.NewReader(fixture(t, "gif-comment.gif")), plim, owner)
	if err != nil {
		t.Fatal(err)
	}
	noSecrets(t, "bug screenshot", storedBytes(t, m, shot.ID))
	rec, err := m.PutPrivate(ctx, "bug", bytes.NewReader(fixture(t, "mov-meta.mov")), plim, owner)
	if err != nil {
		t.Fatal(err)
	}
	noSecrets(t, "bug recording", storedBytes(t, m, rec.ID))
	if rec.Mime != "video/quicktime" {
		t.Errorf("recording: %+v", rec)
	}
}

// ---- The builders of testdata/scrub. ----

func buildScrubFixtures(t *testing.T) map[string][]byte {
	base := jpegPicture()
	jfif := jpegSeg(0xe0, []byte("JFIF\x00\x01\x02\x00\x00\x01\x00\x01\x00\x00"))
	xmp := jpegSeg(0xe1, []byte("http://ns.adobe.com/xap/1.0/\x00"), []byte(xmpPacket))
	more := "<exif:GPSLatitude>51,30N</exif:GPSLatitude>"
	extXMP := jpegSeg(0xe1, []byte("http://ns.adobe.com/xmp/extension/\x000123456789ABCDEF0123456789ABCDEF"), binary.BigEndian.AppendUint32(nil, uint32(len(more))), []byte{0, 0, 0, 0}, []byte(more))
	icc := jpegSeg(0xe2, []byte("ICC_PROFILE\x00\x01\x01"), fakeICC())
	mpf := jpegSeg(0xe2, []byte("MPF\x00MM\x00\x2a\x00\x00\x00\x08\x00\x00\x00\x00\x00\x00"))
	iptc := jpegSeg(0xed, []byte("Photoshop 3.0\x008BIM\x04\x04\x00\x00\x00\x00\x00\x11\x1c\x02\x5a\x00\x0cSecret Place\x00"))
	ducky := jpegSeg(0xec, []byte("Ducky\x00\x01\x00\x04\x00\x00\x00\x50\x00\x00"))
	app3 := jpegSeg(0xe3, []byte("META\x00\x00SpyCam maker notes"))
	adobe := jpegSeg(0xee, []byte("Adobe\x00\x64\x00\x00\x00\x00\x01"))
	com := jpegSeg(0xfe, []byte("Taken at 12 Hidden Lane"))
	app15 := jpegSeg(0xef, []byte("SpyCam private"))
	exifBE := jpegSeg(0xe1, []byte("Exif\x00\x00"), exifTIFF(binary.BigEndian, 6))
	exifLE := jpegSeg(0xe1, []byte("Exif\x00\x00"), exifTIFF(binary.LittleEndian, 8))
	exifUp := jpegSeg(0xe1, []byte("Exif\x00\x00"), exifTIFF(binary.BigEndian, 1))
	join := func(parts ...[]byte) []byte { return bytes.Join(parts, nil) }
	soi := []byte{0xff, 0xd8}

	pic := pngBytes(16, 8)
	ihdr, idat, iend := pic[8:33], pic[33:len(pic)-12], pic[len(pic)-12:]
	// the animation's second frame: the same picture again, as frame data
	fdat := pngChunk("fdAT", append([]byte{0, 0, 0, 2}, pic[41:len(pic)-16]...))

	stillFrame := webpFirstFrameChunks(t, mustB64(t, animWebPLossless))
	still := webpFile(
		webpVP8X(0x20|0x08|0x04, 16, 8),
		riffChunk("ICCP", fakeICC()),
		stillFrame,
		riffChunk("EXIF", append(exifTIFF(binary.LittleEndian, 3), 0)), // odd, so padded
		riffChunk("XMP ", []byte(xmpPacket)),
	)
	anim := mustB64(t, animWebPLossless)
	anim = webpFile(append(append(append([]byte{}, anim[12:]...), riffChunk("EXIF", exifTIFF(binary.BigEndian, 1))...), riffChunk("XMP ", []byte(xmpPacket))...))
	anim[20] |= 0x0c

	return map[string][]byte{
		"jpeg-exif-be.jpg": join(soi, jfif, exifBE, xmp, extXMP, icc, mpf, iptc, ducky, app3, adobe, com, app15, base[2:]),
		// a camera's order (Exif first), and a fill byte before a marker
		"jpeg-exif-le.jpg": join(soi, exifLE, []byte{0xff}, jfif, xmp, icc, com, base[2:]),
		"jpeg-upright.jpg": join(soi, exifUp, base[2:]),
		"jpeg-plain.jpg":   join(soi, jfif, base[2:]),
		"png-text.png": join(pic[:8], ihdr,
			pngChunk("tIME", []byte{0x07, 0xea, 9, 1, 12, 34, 56}),
			pngChunk("gAMA", []byte{0, 0, 0xb1, 0x8f}),
			pngChunk("cHRM", make([]byte, 32)),
			pngChunk("sRGB", []byte{0}),
			pngChunk("pHYs", []byte{0, 0, 0x0b, 0x13, 0, 0, 0x0b, 0x13, 1}),
			pngChunk("tEXt", []byte("Comment\x00Taken at 12 Hidden Lane")),
			pngChunk("zTXt", append([]byte("Author\x00\x00"), deflate("Nora at home")...)),
			pngChunk("iTXt", append([]byte("XML:com.adobe.xmp\x00\x00\x00\x00\x00"), xmpPacket...)),
			pngChunk("eXIf", exifTIFF(binary.BigEndian, 1)),
			pngChunk("prVt", []byte("SpyCam private")),
			idat,
			pngChunk("tEXt", []byte("Software\x00EditPro 9")),
			iend,
			// a cropped screenshot's leftovers
			[]byte("SpyCam leftovers of the uncropped picture")),
		"apng-text.png": join(pic[:8], ihdr,
			pngChunk("acTL", []byte{0, 0, 0, 2, 0, 0, 0, 0}),
			pngChunk("iCCP", append([]byte("ICC\x00\x00"), deflate("not really a profile")...)),
			pngChunk("fcTL", fcTL(0)),
			idat,
			pngChunk("tEXt", []byte("Comment\x00Taken at 12 Hidden Lane")),
			pngChunk("fcTL", fcTL(1)),
			fdat,
			iend),
		"webp-still.webp": still,
		"webp-anim.webp":  anim,
		"gif-comment.gif": gifWithComments(t),
		"mp4-meta.mp4":    mp4Fixture(),
		"mov-meta.mov":    movFixture(),
		"heic-exif.heic": mp4File("heic",
			mp4FullBox("meta", 0, 0, mp4FullBox("hdlr", 0, 0, []byte("\x00\x00\x00\x00pict\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00")), mp4FullBox("iinf", 0, 0, []byte("\x00\x01"), mp4FullBox("infe", 2, 0, []byte("\x00\x01\x00\x00Exif\x00")))),
			mp4Box("mdat", []byte("Exif\x00\x00"), exifTIFF(binary.BigEndian, 6))),
	}
}

const xmpPacket = `<?xpacket begin="" id="W5M0MpCehiHzreSzNTczkc9d"?><x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description xmlns:exif="http://ns.adobe.com/exif/1.0/" xmlns:tiff="http://ns.adobe.com/tiff/1.0/" exif:GPSLatitude="51,30.44N" exif:GPSLongitude="0,7.66W" tiff:Make="SpyCam" tiff:Model="Phone X1"/></rdf:RDF></x:xmpmeta><?xpacket end="w"?>`

// jpegPicture is a 16×8 JPEG with something to see in it, as Go writes one:
// no application segments at all.
func jpegPicture() []byte {
	img := image.NewRGBA(image.Rect(0, 0, 16, 8))
	for y := 0; y < 8; y++ {
		for x := 0; x < 16; x++ {
			img.Set(x, y, color.RGBA{uint8(x * 16), uint8(y * 32), 128, 255})
		}
	}
	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 90})
	return buf.Bytes()
}

func jpegSeg(marker byte, payload ...[]byte) []byte {
	body := bytes.Join(payload, nil)
	out := []byte{0xff, marker, 0, 0}
	binary.BigEndian.PutUint16(out[2:], uint16(len(body)+2))
	return append(out, body...)
}

// fakeICC stands in for a colour profile: nothing here reads one.
func fakeICC() []byte {
	b := make([]byte, 132)
	binary.BigEndian.PutUint32(b, 132)
	copy(b[36:], "acsp")
	return b
}

// byteOrder is binary.BigEndian or binary.LittleEndian.
type byteOrder interface {
	binary.ByteOrder
	binary.AppendByteOrder
}

type ifdEntry struct {
	tag, typ uint16
	count    uint32
	value    []byte // in the file's byte order
}

func ifdASCII(tag uint16, s string) ifdEntry {
	return ifdEntry{tag, 2, uint32(len(s) + 1), append([]byte(s), 0)}
}

// buildTIFF writes IFD0, then the Exif and GPS directories it points to.
func buildTIFF(bo byteOrder, ifd0, exif, gps []ifdEntry) []byte {
	dirs := [][]ifdEntry{append([]ifdEntry{}, ifd0...)}
	if exif != nil {
		dirs[0] = append(dirs[0], ifdEntry{0x8769, 4, 1, nil})
		dirs = append(dirs, exif)
	}
	if gps != nil {
		dirs[0] = append(dirs[0], ifdEntry{0x8825, 4, 1, nil})
		dirs = append(dirs, gps)
	}
	offs, at := make([]int, len(dirs)), 8
	for i, d := range dirs {
		offs[i] = at
		at += 2 + 12*len(d) + 4
	}
	for i := 1; i < len(dirs); i++ {
		dirs[0][len(dirs[0])-len(dirs)+i].value = bo.AppendUint32(nil, uint32(offs[i]))
	}
	out := make([]byte, at)
	if bo == binary.LittleEndian {
		copy(out, "II")
	} else {
		copy(out, "MM")
	}
	bo.PutUint16(out[2:], 42)
	bo.PutUint32(out[4:], 8)
	for i, d := range dirs {
		bo.PutUint16(out[offs[i]:], uint16(len(d)))
		for j, e := range d {
			q := out[offs[i]+2+12*j:]
			bo.PutUint16(q, e.tag)
			bo.PutUint16(q[2:], e.typ)
			bo.PutUint32(q[4:], e.count)
			if len(e.value) <= 4 {
				copy(q[8:], e.value)
				continue
			}
			bo.PutUint32(q[8:], uint32(len(out)))
			out = append(out, e.value...)
			if len(out)%2 == 1 {
				out = append(out, 0)
			}
		}
	}
	return out
}

// exifTIFF is what a phone writes: who made it, when, where, and with what.
func exifTIFF(bo byteOrder, orientation uint16) []byte {
	rationals := func(tag uint16, v ...uint32) ifdEntry {
		var b []byte
		for _, x := range v {
			b = bo.AppendUint32(b, x)
		}
		return ifdEntry{tag, 5, uint32(len(v) / 2), b}
	}
	return buildTIFF(bo,
		[]ifdEntry{ifdASCII(0x010f, "SpyCam"), ifdASCII(0x0110, "Phone X1"), {0x0112, 3, 1, bo.AppendUint16(nil, orientation)}, ifdASCII(0x0131, "EditPro 9"), ifdASCII(0x0132, "2026:09:01 12:34:56")},
		[]ifdEntry{ifdASCII(0x9003, "2026:09:01 12:34:56"), ifdASCII(0xa431, "SN-4815162342")},
		[]ifdEntry{{0x0000, 1, 4, []byte{2, 3, 0, 0}}, ifdASCII(0x0001, "N"), rationals(0x0002, 51, 1, 30, 1, 2644, 100), ifdASCII(0x0003, "W"), rationals(0x0004, 0, 1, 7, 1, 3962, 100)},
	)
}

func pngChunk(typ string, data []byte) []byte {
	out := binary.BigEndian.AppendUint32(nil, uint32(len(data)))
	out = append(append(out, typ...), data...)
	return binary.BigEndian.AppendUint32(out, crc32.ChecksumIEEE(out[4:]))
}

func deflate(s string) []byte {
	var buf bytes.Buffer
	w := zlib.NewWriter(&buf)
	_, _ = w.Write([]byte(s))
	_ = w.Close()
	return buf.Bytes()
}

// fcTL is the control chunk of frame i of a 16×8 animation (sequence number
// 0 for the first, then 1: the second frame's data is number 2).
func fcTL(i int) []byte {
	b := make([]byte, 26)
	binary.BigEndian.PutUint32(b, uint32(i))
	binary.BigEndian.PutUint32(b[4:], 16)
	binary.BigEndian.PutUint32(b[8:], 8)
	binary.BigEndian.PutUint16(b[20:], 1)
	binary.BigEndian.PutUint16(b[22:], 10)
	return b
}

func riffChunk(id string, data []byte) []byte {
	out := binary.LittleEndian.AppendUint32([]byte(id), uint32(len(data)))
	out = append(out, data...)
	if len(data)%2 == 1 {
		out = append(out, 0)
	}
	return out
}

func webpFile(chunks ...[]byte) []byte {
	body := append([]byte("WEBP"), bytes.Join(chunks, nil)...)
	return append(binary.LittleEndian.AppendUint32([]byte("RIFF"), uint32(len(body))), body...)
}

func webpVP8X(flags byte, w, h int) []byte {
	b := make([]byte, 10)
	b[0] = flags
	putUint24(b[4:], w-1)
	putUint24(b[7:], h-1)
	return riffChunk("VP8X", b)
}

// webpFirstFrameChunks is the chunks of an animation's first frame, which
// make a still picture of their own.
func webpFirstFrameChunks(t *testing.T, anim []byte) []byte {
	for p := 12; p+8 <= len(anim); {
		n := int(binary.LittleEndian.Uint32(anim[p+4:]))
		if string(anim[p:p+4]) == "ANMF" {
			return anim[p+8+16 : p+8+n]
		}
		p += 8 + n + n&1
	}
	t.Fatal("no frame")
	return nil
}

// gifWithComments is a looping three-frame GIF with comments before and
// between its frames, XMP (with the trailer that lets a GIF reader skip it)
// and an ICC profile at the end, and something after its trailer.
func gifWithComments(t *testing.T) []byte {
	g := gifBytes(3)
	blocks := gifSplit(t, g)
	comment := func(s string) []byte {
		return append(append([]byte{0x21, 0xfe, byte(len(s))}, s...), 0)
	}
	magic := []byte{1}
	for v := 255; v >= 0; v-- {
		magic = append(magic, byte(v))
	}
	xmpExt := append(append([]byte("\x21\xff\x0bXMP DataXMP"), xmpPacket...), append(magic, 0)...)
	iccExt := append([]byte("\x21\xff\x0bICCRGBG1012\x08"), append(fakeICC()[:8], 0)...)
	var out []byte
	for i, b := range blocks {
		switch {
		case i == 1:
			out = append(out, comment("Taken at 12 Hidden Lane")...)
		case i == len(blocks)-1:
			out = append(append(out, xmpExt...), iccExt...)
		case b[0] == 0x21 && b[1] == 0xf9 && i > 2: // before a later frame's control extension
			out = append(out, comment("SpyCam")...)
		}
		out = append(out, b...)
	}
	return append(out, "SpyCam leftovers"...)
}

// gifSplit cuts a GIF into its header (with any global colour table) and its
// blocks, the trailer last.
func gifSplit(t *testing.T, b []byte) [][]byte {
	p := 13
	if b[10]&0x80 != 0 {
		p += 3 << (b[10]&7 + 1)
	}
	out := [][]byte{b[:p]}
	subBlocks := func(q int) int {
		for b[q] != 0 {
			q += 1 + int(b[q])
		}
		return q + 1
	}
	for {
		start := p
		switch b[p] {
		case 0x21:
			p = subBlocks(p + 2)
		case 0x2c:
			q := p + 10
			if b[p+9]&0x80 != 0 {
				q += 3 << (b[p+9]&7 + 1)
			}
			p = subBlocks(q + 1)
		case 0x3b:
			return append(out, b[p:p+1])
		default:
			t.Fatalf("GIF block %#x", b[p])
		}
		out = append(out, b[start:p])
	}
}

func mp4Box(typ string, body ...[]byte) []byte {
	b := bytes.Join(body, nil)
	return append(binary.BigEndian.AppendUint32(nil, uint32(len(b)+8)), append([]byte(typ), b...)...)
}

func mp4FullBox(typ string, version byte, flags uint32, body ...[]byte) []byte {
	vf := binary.BigEndian.AppendUint32(nil, flags)
	vf[0] = version
	return mp4Box(typ, append([][]byte{vf}, body...)...)
}

func mp4File(brand string, boxes ...[]byte) []byte {
	ftyp := mp4Box("ftyp", []byte(brand), []byte{0, 0, 2, 0}, []byte(brand), []byte("isom"))
	return bytes.Join(append([][]byte{ftyp}, boxes...), nil)
}

// A capture time of 2026-09-01 in seconds since 1904, as a movie writes it.
const movieTime = 3_839_567_696

func mvhd() []byte {
	b := binary.BigEndian.AppendUint32(nil, movieTime)
	b = binary.BigEndian.AppendUint32(b, movieTime+1)
	b = binary.BigEndian.AppendUint32(b, 1000) // timescale
	b = binary.BigEndian.AppendUint32(b, 2000) // duration
	b = append(b, 0, 1, 0, 0, 1, 0)            // rate, volume
	b = append(b, make([]byte, 10+36+24)...)   // reserved, matrix, pre-defined
	b = binary.BigEndian.AppendUint32(b, 2)    // next track
	return mp4FullBox("mvhd", 0, 0, b)
}

func tkhd() []byte {
	b := binary.BigEndian.AppendUint32(nil, movieTime)
	b = binary.BigEndian.AppendUint32(b, movieTime+1)
	b = binary.BigEndian.AppendUint32(b, 1) // track
	b = append(b, make([]byte, 4+4+8+8+36)...)
	b = binary.BigEndian.AppendUint32(b, 16<<16)
	b = binary.BigEndian.AppendUint32(b, 8<<16)
	return mp4FullBox("tkhd", 0, 3, b)
}

func mdhd(version byte) []byte {
	var b []byte
	if version == 1 {
		b = binary.BigEndian.AppendUint64(nil, movieTime)
		b = binary.BigEndian.AppendUint64(b, movieTime+1)
		b = binary.BigEndian.AppendUint32(b, 1000)
		b = binary.BigEndian.AppendUint64(b, 2000)
	} else {
		b = binary.BigEndian.AppendUint32(nil, movieTime)
		b = binary.BigEndian.AppendUint32(b, movieTime+1)
		b = binary.BigEndian.AppendUint32(b, 1000)
		b = binary.BigEndian.AppendUint32(b, 2000)
	}
	return mp4FullBox("mdhd", version, 0, append(b, 0x55, 0xc4, 0, 0))
}

func hdlr(kind string) []byte {
	return mp4FullBox("hdlr", 0, 0, []byte("\x00\x00\x00\x00"+kind+"\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00\x00Handler\x00"))
}

// appleMeta is the metadata box an iPhone writes: keys, then their values.
func appleMeta() []byte {
	key := func(k string) []byte { return mp4Box("mdta", []byte(k)) }
	item := func(i uint32, v string) []byte {
		return append(binary.BigEndian.AppendUint32(nil, uint32(8+16+len(v))), append(binary.BigEndian.AppendUint32(nil, i), mp4Box("data", []byte{0, 0, 0, 1, 0, 0, 0, 0}, []byte(v))...)...)
	}
	return mp4Box("meta", hdlr("mdta"),
		mp4FullBox("keys", 0, 0, []byte{0, 0, 0, 3}, key("com.apple.quicktime.location.ISO6709"), key("com.apple.quicktime.make"), key("com.apple.quicktime.model")),
		mp4Box("ilst", item(1, "+51.5074-000.1278+012.000/"), item(2, "SpyCam"), item(3, "Phone X1")))
}

func udta() []byte {
	text := func(typ, s string) []byte {
		return mp4Box(typ, binary.BigEndian.AppendUint16(nil, uint16(len(s))), []byte{0x15, 0xc7}, []byte(s))
	}
	return mp4Box("udta", text("\xa9xyz", "+51.5074-000.1278/"), text("\xa9mak", "SpyCam"), text("\xa9mod", "Phone X1"))
}

func mp4Moov(extra ...[]byte) []byte {
	return mp4Box("moov", append([][]byte{mvhd()}, extra...)...)
}

// mp4Fixture is an MP4 with metadata everywhere it goes: the movie's and a
// track's udta and meta, XMP in the movie and at the top, and a meta box at
// the top; its media data has a 64-bit size, and the index points into it.
func mp4Fixture() []byte {
	frame := []byte("FRAME-DATA and more of the picture")
	xmpBox := mp4Box("uuid", []byte(xmpUUID), []byte(xmpPacket))
	build := func(chunkOffset uint32) []byte {
		stbl := mp4Box("stbl",
			mp4FullBox("stsd", 0, 0, []byte{0, 0, 0, 0}),
			mp4FullBox("stco", 0, 0, []byte{0, 0, 0, 1}, binary.BigEndian.AppendUint32(nil, chunkOffset)))
		trak := mp4Box("trak", tkhd(),
			mp4Box("mdia", mdhd(1), hdlr("vide"), mp4Box("minf", stbl)),
			udta(), mp4FullBox("meta", 0, 0, hdlr("mdir"), mp4Box("ilst", []byte("\xa9too Lavf SpyCam"))))
		moov := mp4Moov(trak, udta(), appleMeta(), xmpBox)
		mdat := append([]byte{0, 0, 0, 1, 'm', 'd', 'a', 't'}, binary.BigEndian.AppendUint64(nil, uint64(16+len(frame)))...)
		return bytes.Join([][]byte{
			mp4File("isom"), moov, mdat, frame,
			mp4FullBox("meta", 0, 0, hdlr("mdir"), []byte("SpyCam Phone X1")),
			xmpBox,
			mp4Box("uuid", []byte("\x50\x52\x4f\x46\x21\xd2\x4f\xce\xbb\x88\x69\x5c\xfa\xc9\xc7\x40"), []byte("a profile box")),
			mp4Box("free", []byte("padding")),
		}, nil)
	}
	first := build(0)
	return build(uint32(bytes.Index(first, []byte("FRAME-DATA"))))
}

// movFixture is an iPhone's QuickTime movie, its media data running to the
// end of the file.
func movFixture() []byte {
	ftyp := mp4Box("ftyp", []byte("qt  "), []byte{0, 0, 2, 0}, []byte("qt  "))
	trak := mp4Box("trak", tkhd(), mp4Box("mdia", mdhd(0), hdlr("vide")))
	moov := mp4Moov(trak, appleMeta(), udta())
	return bytes.Join([][]byte{ftyp, mp4Box("wide"), moov, []byte("\x00\x00\x00\x00mdat"), []byte("FRAME-DATA to the end")}, nil)
}
