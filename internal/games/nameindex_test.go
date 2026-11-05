package games

import (
	"encoding/hex"
	"encoding/json"
	"os"
	"testing"
)

// The desktop app hashes names itself to look in the index (games/candidates.ts,
// desktop/test/games.test.ts reads the same file), so the two must agree exactly.
func TestNameHashVectors(t *testing.T) {
	raw, err := os.ReadFile("testdata/fold_vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var vectors []struct{ Name, Fold, Hash string }
	if err := json.Unmarshal(raw, &vectors); err != nil {
		t.Fatal(err)
	}
	for _, v := range vectors {
		if got := FoldName(v.Name); got != v.Fold {
			t.Errorf("FoldName(%q) = %q, want %q", v.Name, got, v.Fold)
		}
		if got := hex.EncodeToString(NameHash(v.Name)); got != v.Hash {
			t.Errorf("NameHash(%q) = %s, want %s", v.Name, got, v.Hash)
		}
	}
}

func TestNameIndexFormat(t *testing.T) {
	var a, b [HashBytes]byte
	copy(a[:], NameHash("Hollow Knight"))
	copy(b[:], NameHash("Some Obscure Game"))
	data := encodeNameIndex(map[[HashBytes]byte]bool{a: true}, map[[HashBytes]byte]bool{b: true})
	if !validNameIndex(data) {
		t.Fatal("a fresh index should read back as valid")
	}
	if validNameIndex(data[:len(data)-1]) {
		t.Fatal("a cut-off index must not pass, or the app would search garbage")
	}
}
