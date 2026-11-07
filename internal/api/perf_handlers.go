package api

import (
	"net/http"
	"net/url"
	"strings"

	"evilion.xyz/chat/internal/perf"
)

// The apps' half of the profiler (internal/perf): how long things took on the
// device, summed there into the profiler's own histogram buckets and sent every
// few minutes by everyone who leaves Send performance reports on. What arrives
// is folded into the totals for everyone at once. The account is asked for only
// so the request can be metered; it is not kept, and neither is the address,
// the app's version or anything naming a conversation: a route arrives with
// its ids already taken out and is kept only as the pattern it matches.

const (
	maxPerfStages = 40
	maxPerfRoutes = 150
	// a report covers a few minutes of one device
	maxPerfSamples = 20000
	maxPerfMs      = 10 * 60 * 1000
	// slow_frame's e is every frame drawn in those minutes
	maxPerfFrames = 200000
)

// perfStages are the stages the apps time; nothing else is kept.
var perfStages = map[string]bool{
	"startup":           true, // opening the app until it is ready
	"open_conversation": true, // choosing a conversation until its messages show
	"decrypt":           true, // opening one batch of messages
	"send":              true, // pressing send until the server has the message
	"gateway_connect":   true, // the socket opening until ready
	"gateway_rtt":       true, // a heartbeat answered, as the app saw it
	"long_task":         true, // the web page's main thread blocked for 50 ms or more
	"interaction":       true, // a tap or key until the web page answered, 100 ms and over
	"slow_frame":        true, // an Android frame over 32 ms; e is frames drawn
}

var perfTiers = map[string]bool{"low": true, "mid": true, "high": true}

var perfNets = map[string]bool{"wifi": true, "cellular": true, "ethernet": true, "4g": true, "3g": true, "2g": true, "slow-2g": true}

type perfHist struct {
	Errs int64   `json:"e"`
	Sum  float64 `json:"sum"`
	Max  float64 `json:"max"`
	H    []int64 `json:"h"`
}

// clean checks a histogram an app sent and says how many samples it holds; 0
// means drop it. maxErrs bounds e.
func (p *perfHist) clean(maxErrs int64) int64 {
	if len(p.H) == 0 || len(p.H) > perf.Buckets {
		return 0
	}
	var n int64
	for _, v := range p.H {
		if v < 0 || v > maxPerfSamples {
			return 0
		}
		n += v
	}
	if n == 0 || n > maxPerfSamples {
		return 0
	}
	p.Max = min(max(p.Max, 0), maxPerfMs)
	p.Sum = min(max(p.Sum, 0), p.Max*float64(n))
	p.Errs = min(max(p.Errs, 0), maxErrs)
	return n
}

func (s *Server) perfRoutes(mux *http.ServeMux) {
	mux.Handle("POST /api/v1/perf", s.userLimited(s.limPerf, s.handlePerfReport))
}

func (s *Server) handlePerfReport(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Tier   string `json:"tier"`
		Net    string `json:"net"`
		Stages []struct {
			Name string `json:"name"`
			perfHist
		} `json:"stages"`
		API []struct {
			Method string `json:"method"`
			Path   string `json:"path"`
			perfHist
		} `json:"api"`
	}
	if err := ReadJSON(w, r, &req, 64<<10); err != nil {
		WriteError(w, err)
		return
	}
	platform := perf.Platform(r.UserAgent())
	if platform == "other" {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	tier, net := req.Tier, req.Net
	if !perfTiers[tier] {
		tier = "unknown"
	}
	if !perfNets[net] {
		net = "unknown"
	}
	for i, st := range req.Stages {
		if i >= maxPerfStages {
			break
		}
		maxErrs := int64(maxPerfSamples)
		if st.Name == "slow_frame" {
			maxErrs = maxPerfFrames
		}
		if !perfStages[st.Name] || st.clean(maxErrs) == 0 {
			continue
		}
		perf.Merge(perf.Client, st.Name+" · "+platform, st.H, st.Errs, st.Sum, st.Max)
		perf.Merge(perf.ClientClass, st.Name+" · "+platform+" · "+tier+" · "+net, st.H, st.Errs, st.Sum, st.Max)
	}
	for i, a := range req.API {
		if i >= maxPerfRoutes {
			break
		}
		pattern := s.routePattern(a.Method, a.Path)
		if pattern == "" || a.clean(maxPerfSamples) == 0 {
			continue
		}
		perf.Merge(perf.ClientAPI, platform+" "+pattern, a.H, a.Errs, a.Sum, a.Max)
	}
	w.WriteHeader(http.StatusNoContent)
}

// routePattern is the API route a method and path belong to, as the mux would
// pick it, or "" for anything that is not one of them. The apps send paths
// with ids replaced by {id}, which the mux matches as it would a real id.
func (s *Server) routePattern(method, path string) string {
	switch method {
	case http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
	default:
		return ""
	}
	if s.mux == nil || len(path) > 200 || !strings.HasPrefix(path, "/api/") {
		return ""
	}
	req := &http.Request{Method: method, URL: &url.URL{Path: path}, Host: "nexa"}
	_, pattern := s.mux.Handler(req)
	if pattern == "" || pattern == "/api/" || !strings.Contains(pattern, " /api/") {
		return ""
	}
	return pattern
}
