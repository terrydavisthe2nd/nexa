package realtime

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"
)

// TURN mints and revokes Cloudflare Realtime TURN credentials with the
// account's TURN key. The key never leaves this server: people get
// credentials that expire, each tagged with their account, so Cloudflare's
// analytics can say who relayed how much, and each one can be revoked by name.
// SFU and TURN egress share one monthly allowance, and a TURN credential
// relays to any address, not only to the SFU, so they are handed out sparingly.
type TURN struct {
	KeyID string
	Token string
	Base  string
	HTTP  *http.Client
}

// NewTURN returns a client for the key, or nil when no key is configured.
func NewTURN(keyID, token string, client *http.Client) *TURN {
	if keyID == "" || token == "" {
		return nil
	}
	if client == nil {
		client = &http.Client{Timeout: 6 * time.Second}
	}
	return &TURN{KeyID: keyID, Token: token, Base: DefaultBase, HTTP: client}
}

// Credential is one minted credential: the ICE servers to hand out, and the
// username that names it for revocation.
type Credential struct {
	Servers  []map[string]any
	Username string
}

func (t *TURN) url(rest string) string {
	return t.Base + "/turn/keys/" + url.PathEscape(t.KeyID) + "/credentials/" + rest
}

// Mint asks for a credential that expires after ttl, tagged with id.
func (t *TURN) Mint(ctx context.Context, ttl time.Duration, id string) (Credential, error) {
	body, _ := json.Marshal(map[string]any{"ttl": int(ttl.Seconds()), "customIdentifier": id})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.url("generate-ice-servers"), bytes.NewReader(body))
	if err != nil {
		return Credential{}, err
	}
	req.Header.Set("Authorization", "Bearer "+t.Token)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", userAgent)
	res, err := t.HTTP.Do(req)
	if err != nil {
		return Credential{}, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 64<<10))
	if res.StatusCode != http.StatusOK && res.StatusCode != http.StatusCreated {
		return Credential{}, fmt.Errorf("realtime: TURN credentials refused (%d)", res.StatusCode)
	}
	var parsed struct {
		ICEServers json.RawMessage `json:"iceServers"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return Credential{}, fmt.Errorf("realtime: unreadable TURN response: %w", err)
	}
	// the shape is either one entry or a list, depending on the endpoint
	var list []map[string]any
	if err := json.Unmarshal(parsed.ICEServers, &list); err != nil {
		var one map[string]any
		if err := json.Unmarshal(parsed.ICEServers, &one); err != nil {
			return Credential{}, fmt.Errorf("realtime: unreadable TURN servers: %w", err)
		}
		list = []map[string]any{one}
	}
	cred := Credential{Servers: list}
	for _, s := range list {
		if name, _ := s["username"].(string); name != "" {
			cred.Username = name
			break
		}
	}
	// a credential that cannot be named cannot be revoked, so it is not handed out
	if cred.Username == "" {
		return Credential{}, errors.New("realtime: TURN response named no credential")
	}
	return cred, nil
}

// Revoke ends a credential before it expires, along with what is relayed
// through it. A credential Cloudflare no longer knows counts as revoked.
func (t *TURN) Revoke(ctx context.Context, username string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, t.url(url.PathEscape(username)+"/revoke"), nil)
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+t.Token)
	req.Header.Set("User-Agent", userAgent)
	res, err := t.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(res.Body, 64<<10))
	switch {
	case res.StatusCode == http.StatusNoContent, res.StatusCode == http.StatusOK, res.StatusCode == http.StatusNotFound:
		return nil
	default:
		return fmt.Errorf("realtime: TURN revoke refused (%d)", res.StatusCode)
	}
}
