package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"evilion.xyz/chat/internal/auth"
)

// Captchas guard the actions a bot would abuse: creating accounts, guessing
// passwords, recovering accounts, friend request spam, a first message to a
// stranger, joining servers en masse and creating them, joining notes from a
// link and inviting people to them, guest calls, bug reports, putting a
// server on Discovery or a sticker pack in front of everyone, and Telegram
// imports, which spend a limit everyone shares. Every check of a password
// asks for one (a new password or recovery key, two-factor set up, turned off
// or given new backup codes, a locked subchat, deleting the account), and so
// does a new username, so a stolen session cannot be scripted into either.
// They use Cloudflare Turnstile, which usually shows a person nothing and
// only asks for a click when it is unsure. Nothing asks for one until a pair
// of keys is configured. A refusal names the action in X-Captcha-Action, and
// the apps solve it for that action and send the request again. A Turnstile
// token is good once, for that action only, and only when solved on one of
// this Nexa's own hostnames.
//
// hCaptcha stays as a fallback for apps from before the move back to
// Turnstile (HCAPTCHA_* keys): current apps mark their tokens with
// X-Captcha-Provider: turnstile, and a token without the mark is checked with
// hCaptcha when it is configured. hCaptcha binds no action to a token, so
// those are checked only for passing on one of Nexa's own hostnames.

// CaptchaHeader carries the Turnstile token for the one request it was made for.
const CaptchaHeader = "X-Captcha-Token"

// CaptchaProviderHeader says which service made the token ("turnstile");
// apps that send none predate Turnstile's return and use hCaptcha.
const CaptchaProviderHeader = "X-Captcha-Provider"

// CaptchaActionHeader names, on a request a captcha guards, the action a
// token has to be solved for.
const CaptchaActionHeader = "X-Captcha-Action"

var captchaClient = &http.Client{Timeout: 8 * time.Second}

// captchaVerifyURL is where tokens are checked; tests point it at a stand-in.
var captchaVerifyURL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"

// hcaptchaVerifyURL checks the fallback's tokens; tests point it at a stand-in.
var hcaptchaVerifyURL = "https://api.hcaptcha.com/siteverify"

// hcaptchaTestSecret is hCaptcha's published test secret: it passes every
// token and reports the hostname "dummy-key-pass", so with it the hostname
// is not checked. It only ever runs on a test server configured with it.
const hcaptchaTestSecret = "0x0000000000000000000000000000000000000000"

func (s *Server) turnstileEnabled() bool {
	return s.Cfg.TurnstileSiteKey != "" && s.Cfg.TurnstileSecretKey != ""
}

func (s *Server) hcaptchaEnabled() bool {
	return s.Cfg.HCaptchaSiteKey != "" && s.Cfg.HCaptchaSecretKey != ""
}

func (s *Server) captchaEnabled() bool {
	return s.turnstileEnabled() || s.hcaptchaEnabled()
}

// requireCaptcha checks the token sent with a request, made for `action`. It
// writes the error itself and reports whether the request may go on.
func (s *Server) requireCaptcha(w http.ResponseWriter, r *http.Request, action string) bool {
	if !s.captchaEnabled() {
		return true
	}
	// what to solve, for an app that sent the request without knowing it
	// would be asked: it solves the captcha for that action and sends again
	w.Header().Set(CaptchaActionHeader, action)
	token := strings.TrimSpace(r.Header.Get(CaptchaHeader))
	// Turnstile's tokens are at most 2,048 characters and hCaptcha's run to
	// several thousand; the cap only keeps a giant header from being sent on
	if token == "" || len(token) > 16<<10 {
		WriteError(w, E(http.StatusForbidden, "captcha_required", "Please complete the captcha."))
		return false
	}
	unavailable := func() bool {
		WriteError(w, E(http.StatusServiceUnavailable, "captcha_unavailable", "The captcha could not be checked just now. Try again in a moment."))
		return false
	}
	failed := func() bool {
		WriteError(w, E(http.StatusForbidden, "captcha_failed", "The captcha check failed. Please try again."))
		return false
	}
	turnstile := strings.EqualFold(strings.TrimSpace(r.Header.Get(CaptchaProviderHeader)), "turnstile") || !s.hcaptchaEnabled()
	if turnstile && !s.turnstileEnabled() {
		return failed()
	}
	verifyURL := captchaVerifyURL
	form := url.Values{"secret": {s.Cfg.TurnstileSecretKey}, "response": {token}, "remoteip": {auth.ClientIP(r)}}
	if !turnstile {
		verifyURL = hcaptchaVerifyURL
		form = url.Values{"secret": {s.Cfg.HCaptchaSecretKey}, "response": {token}, "remoteip": {auth.ClientIP(r)}, "sitekey": {s.Cfg.HCaptchaSiteKey}}
	}
	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, verifyURL, strings.NewReader(form.Encode()))
	if err != nil {
		return unavailable()
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := captchaClient.Do(req)
	if err != nil {
		return unavailable()
	}
	defer res.Body.Close()
	var out struct {
		Success  bool   `json:"success"`
		Action   string `json:"action"`
		Hostname string `json:"hostname"`
		Metadata struct {
			// Cloudflare's test secret keys pass every token, for no action on
			// example.com, and say so here. A real secret never answers this.
			Testing bool `json:"result_with_testing_key"`
		} `json:"metadata"`
	}
	if res.StatusCode != http.StatusOK || json.NewDecoder(io.LimitReader(res.Body, 64<<10)).Decode(&out) != nil {
		return unavailable()
	}
	// A token counts only for the action it was solved for, and only when it
	// was solved on this site: one made for a friend request, or on a page
	// someone else put the widget on, does not sign anybody in.
	if !turnstile {
		// hCaptcha binds no action, so only the site is checked
		testing := s.Cfg.HCaptchaSecretKey == hcaptchaTestSecret
		if !out.Success || (!testing && !s.captchaSite(out.Hostname)) {
			return failed()
		}
		return true
	}
	if !out.Success || (!out.Metadata.Testing && (out.Action != action || !s.captchaSite(out.Hostname))) {
		return failed()
	}
	return true
}

// captchaSite reports whether Cloudflare or hCaptcha saw the captcha solved on one of
// this Nexa's own hostnames (PUBLIC_URL, ALT_URLS and MOVED_FROM).
func (s *Server) captchaSite(hostname string) bool {
	for _, h := range s.ourHostnames() {
		if strings.EqualFold(hostname, h) {
			return true
		}
	}
	return false
}
