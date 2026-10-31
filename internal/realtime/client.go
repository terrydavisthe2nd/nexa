// Package realtime talks to Cloudflare Realtime SFU for calls. Every device
// in a call has one SFU session; chatd publishes its tracks and decides whose
// tracks it may pull, so the app secret never leaves this server and nobody
// in a call reaches anybody else's address.
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

// DefaultBase is Cloudflare's Realtime API.
const DefaultBase = "https://rtc.live.cloudflare.com/v1"

// userAgent is sent on every call: the API turns some default agents away.
const userAgent = "evilion-chat/1 (+https://nexa.fish)"

// SessionDescription is an SDP offer or answer.
type SessionDescription struct {
	SDP  string `json:"sdp"`
	Type string `json:"type"`
}

// Track is one entry of a tracks request or response, in the API's naming.
type Track struct {
	Location         string `json:"location,omitempty"`
	Mid              string `json:"mid,omitempty"`
	SessionID        string `json:"sessionId,omitempty"`
	TrackName        string `json:"trackName,omitempty"`
	Kind             string `json:"kind,omitempty"`
	ErrorCode        string `json:"errorCode,omitempty"`
	ErrorDescription string `json:"errorDescription,omitempty"`
}

// TracksResult is what adding or closing tracks returns.
type TracksResult struct {
	Description *SessionDescription
	// Renegotiate means the SFU made an offer that must be answered.
	Renegotiate bool
	Tracks      []Track
}

// Error is a refusal from the API.
type Error struct {
	Status      int
	Code        string
	Description string
}

func (e *Error) Error() string {
	return fmt.Sprintf("realtime: %d %s: %s", e.Status, e.Code, e.Description)
}

// ErrSessionGone is returned for a session the SFU no longer knows.
var ErrSessionGone = errors.New("realtime: session not found")

// Disconnected reports the SFU's answer for a session whose PeerConnection it
// no longer reaches (410 session_error): nothing can flow to that device, so
// it costs nothing and has nothing left to cut off.
func Disconnected(err error) bool {
	var apiErr *Error
	return errors.As(err, &apiErr) && apiErr.Status == http.StatusGone
}

// CloseTimeout bounds one attempt to close a session. Cloudflare waits up to
// five seconds for a session's connection before answering about it, and a
// device that left has closed its connection, so an attempt held to five
// seconds gave up just before the answer came: every such session stayed
// charged until the next restart.
const CloseTimeout = 20 * time.Second

// Client calls the Realtime API for one app.
type Client struct {
	AppID  string
	Secret string
	Base   string
	HTTP   *http.Client
}

// New returns a client for the app, or nil when no app is configured.
func New(appID, secret string) *Client {
	if appID == "" || secret == "" {
		return nil
	}
	return &Client{AppID: appID, Secret: secret, Base: DefaultBase, HTTP: &http.Client{Timeout: 10 * time.Second}}
}

type apiResponse struct {
	ErrorCode                      string              `json:"errorCode"`
	ErrorDescription               string              `json:"errorDescription"`
	SessionID                      string              `json:"sessionId"`
	SessionDescription             *SessionDescription `json:"sessionDescription"`
	RequiresImmediateRenegotiation bool                `json:"requiresImmediateRenegotiation"`
	Tracks                         []Track             `json:"tracks"`
}

func (c *Client) call(ctx context.Context, method, path string, body any) (*apiResponse, error) {
	var reader io.Reader
	if body != nil {
		raw, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		reader = bytes.NewReader(raw)
	}
	u := c.Base + "/apps/" + url.PathEscape(c.AppID) + path
	req, err := http.NewRequestWithContext(ctx, method, u, reader)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.Secret)
	req.Header.Set("User-Agent", userAgent)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.HTTP.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	out := &apiResponse{}
	if len(bytes.TrimSpace(raw)) > 0 {
		if err := json.Unmarshal(raw, out); err != nil && res.StatusCode < 300 {
			return nil, fmt.Errorf("realtime: unreadable response: %w", err)
		}
	}
	if res.StatusCode == http.StatusNotFound {
		return nil, ErrSessionGone
	}
	if res.StatusCode >= 300 || out.ErrorCode != "" {
		return nil, &Error{Status: res.StatusCode, Code: out.ErrorCode, Description: out.ErrorDescription}
	}
	return out, nil
}

// NewSession opens a session: one PeerConnection to Cloudflare.
func (c *Client) NewSession(ctx context.Context) (string, error) {
	// the API refuses an empty JSON body here, so none is sent
	out, err := c.call(ctx, http.MethodPost, "/sessions/new", nil)
	if err != nil {
		return "", err
	}
	if out.SessionID == "" {
		return "", errors.New("realtime: no session id in response")
	}
	return out.SessionID, nil
}

// AddTracks publishes local tracks (with the client's offer) or pulls remote
// ones (the SFU then offers, and Renegotiate is set).
func (c *Client) AddTracks(ctx context.Context, session string, offer *SessionDescription, tracks []Track) (*TracksResult, error) {
	body := map[string]any{"tracks": tracks}
	if offer != nil {
		body["sessionDescription"] = offer
	}
	out, err := c.call(ctx, http.MethodPost, "/sessions/"+url.PathEscape(session)+"/tracks/new", body)
	if err != nil {
		return nil, err
	}
	return &TracksResult{Description: out.SessionDescription, Renegotiate: out.RequiresImmediateRenegotiation, Tracks: out.Tracks}, nil
}

// Renegotiate hands the SFU an answer to its offer, or an offer of the
// client's own (an ICE restart), whose answer comes back.
func (c *Client) Renegotiate(ctx context.Context, session string, d SessionDescription) (*SessionDescription, error) {
	out, err := c.call(ctx, http.MethodPut, "/sessions/"+url.PathEscape(session)+"/renegotiate", map[string]any{"sessionDescription": d})
	if err != nil {
		return nil, err
	}
	return out.SessionDescription, nil
}

// CloseTracks stops tracks by mid. With force the media simply stops flowing
// and no renegotiation happens, which is how a removed member is cut off.
func (c *Client) CloseTracks(ctx context.Context, session string, mids []string, offer *SessionDescription, force bool) (*TracksResult, error) {
	tracks := make([]map[string]string, 0, len(mids))
	for _, m := range mids {
		tracks = append(tracks, map[string]string{"mid": m})
	}
	body := map[string]any{"tracks": tracks, "force": force}
	if offer != nil {
		body["sessionDescription"] = offer
	}
	out, err := c.call(ctx, http.MethodPut, "/sessions/"+url.PathEscape(session)+"/tracks/close", body)
	if err != nil {
		return nil, err
	}
	return &TracksResult{Description: out.SessionDescription, Renegotiate: out.RequiresImmediateRenegotiation, Tracks: out.Tracks}, nil
}

// SessionTracks lists a session's tracks.
func (c *Client) SessionTracks(ctx context.Context, session string) ([]Track, error) {
	out, err := c.call(ctx, http.MethodGet, "/sessions/"+url.PathEscape(session), nil)
	if err != nil {
		return nil, err
	}
	return out.Tracks, nil
}

// CloseSession force-closes every track of a session, so a device that left
// or was removed stops sending and receiving at once. A session the SFU has
// already forgotten, or whose connection it no longer reaches, is not an
// error: nothing flows to it either way.
func (c *Client) CloseSession(ctx context.Context, session string) error {
	tracks, err := c.SessionTracks(ctx, session)
	if errors.Is(err, ErrSessionGone) || Disconnected(err) {
		return nil
	}
	if err != nil {
		return err
	}
	mids := []string{}
	for _, t := range tracks {
		if t.Mid != "" {
			mids = append(mids, t.Mid)
		}
	}
	if len(mids) == 0 {
		return nil
	}
	res, err := c.CloseTracks(ctx, session, mids, nil, true)
	if errors.Is(err, ErrSessionGone) || Disconnected(err) {
		return nil
	}
	if err != nil {
		return err
	}
	for _, track := range res.Tracks {
		if track.ErrorCode != "" {
			return fmt.Errorf("realtime: track close refused: %s", track.ErrorCode)
		}
	}
	return nil
}
