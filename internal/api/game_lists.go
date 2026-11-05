package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/games"
	"evilion.xyz/chat/internal/gateway"
	"evilion.xyz/chat/internal/store"
	"evilion.xyz/chat/internal/textutil"
)

// The lists a desktop app downloads to recognise games (the programs, and the
// hashed names of IGDB's games, internal/games/nameindex.go), and what people
// share with staff to add to the first.

const maxGameListName = 128

var gameProcess = regexp.MustCompile(`^[^/\\\x00-\x1f]{1,128}$`)

// commonProcesses are file names too many programs share to say what any of
// them is: one on the list would call every program of that name a game.
var commonProcesses = map[string]bool{
	"game": true, "game.exe": true, "launcher": true, "launcher.exe": true, "client": true, "client.exe": true, "main": true, "main.exe": true,
	"start": true, "start.exe": true, "run": true, "run.exe": true, "app": true, "app.exe": true, "play": true, "play.exe": true, "setup.exe": true,
	"java": true, "java.exe": true, "javaw.exe": true, "python": true, "python.exe": true, "pythonw.exe": true, "wine": true, "wine64": true, "node": true, "node.exe": true,
	"electron": true, "electron.exe": true, "steam": true, "steam.exe": true, "explorer.exe": true, "bash": true, "sh": true, "update.exe": true, "program.exe": true,
}

// cleanProcess is a program's file name as the lists keep it, or false when it
// is not one, or is too common to mean anything.
func cleanProcess(raw string) (string, bool) {
	p := strings.ToLower(strings.TrimSpace(raw))
	if !gameProcess.MatchString(p) || commonProcesses[p] {
		return "", false
	}
	return p, true
}

func cleanGameName(raw string) (string, bool) {
	name, ok := textutil.CleanName(raw, 4*maxGameListName)
	if !ok || name == "" || utf8.RuneCountInString(name) > maxGameListName {
		return "", false
	}
	return name, true
}

type knownGamesCache struct {
	mu   sync.Mutex
	body []byte
	etag string
}

func (s *Server) gameListRoutes(mux *http.ServeMux) {
	// what every desktop app downloads, signed in or not: there is nothing private in either
	mux.Handle("GET /api/v1/games/known", ipLimited(s.limGameLists, s.handleKnownGames))
	mux.Handle("GET /api/v1/games/name-index", ipLimited(s.limGameLists, s.handleGameNameIndex))
	mux.Handle("POST /api/v1/games/shared", s.userLimited(s.limGameShare, s.handleShareGame))
	mux.Handle("GET /api/v1/games/shared", s.authed(s.handleMySharedGames))
	mux.Handle("GET /api/v1/moderation/games", s.staff(rankAdmin, s.handleModGames))
	mux.Handle("GET /api/v1/moderation/games/count", s.staff(rankAdmin, s.handleModGamesCount))
	mux.Handle("POST /api/v1/moderation/games/list", s.staff(rankAdmin, s.handleModGameList))
	mux.Handle("POST /api/v1/moderation/games/reject", s.staff(rankAdmin, s.handleModGameReject))
	mux.Handle("POST /api/v1/moderation/games/forget", s.staff(rankAdmin, s.handleModGameForget))
}

// knownGamesBody is the list as apps get it: what staff listed first, so it
// wins over the list written by hand for the same file name.
func (s *Server) knownGamesBody(r *http.Request) ([]byte, string, error) {
	s.knownGames.mu.Lock()
	defer s.knownGames.mu.Unlock()
	if s.knownGames.body != nil {
		return s.knownGames.body, s.knownGames.etag, nil
	}
	base, err := games.BaseKnownGames()
	if err != nil {
		return nil, "", err
	}
	listed, err := s.Store.DecidedGames(r.Context(), store.GameListed)
	if err != nil {
		return nil, "", err
	}
	all := make([]games.KnownGame, 0, len(base)+len(listed))
	for _, g := range listed {
		all = append(all, games.KnownGame{Name: g.Name, Exes: []string{g.Process}, GameID: g.GameID})
	}
	all = append(all, base...)
	body, err := json.Marshal(map[string]any{"games": all})
	if err != nil {
		return nil, "", err
	}
	sum := sha256.Sum256(body)
	s.knownGames.body, s.knownGames.etag = body, `"`+hex.EncodeToString(sum[:8])+`"`
	return s.knownGames.body, s.knownGames.etag, nil
}

func (s *Server) handleKnownGames(w http.ResponseWriter, r *http.Request) {
	body, etag, err := s.knownGamesBody(r)
	if err != nil {
		WriteError(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "public, max-age=600")
	w.Header().Set("ETag", etag)
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(body))
}

func (s *Server) handleGameNameIndex(w http.ResponseWriter, r *http.Request) {
	if s.Games == nil {
		http.NotFound(w, r)
		return
	}
	data, etag, at, ok := s.Games.NameIndex()
	if !ok {
		http.NotFound(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Cache-Control", "public, max-age=3600")
	w.Header().Set("ETag", etag)
	http.ServeContent(w, r, "", at, bytes.NewReader(data))
}

// handleShareGame takes what someone shares about a program: its file name and
// what they call it, and which IGDB game it is if they found one. Who shared it
// is kept only so one person counts once, and goes when staff review it.
func (s *Server) handleShareGame(w http.ResponseWriter, r *http.Request) {
	var b struct {
		Process string `json:"process"`
		OS      string `json:"os"`
		Name    string `json:"name"`
		GameID  string `json:"game_id"`
	}
	if err := ReadJSON(w, r, &b, 2<<10); err != nil {
		WriteError(w, err)
		return
	}
	process, ok := cleanProcess(b.Process)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "common_process", "Too many programs use that file name, so it can't tell people what game this is."))
		return
	}
	name, ok := cleanGameName(b.Name)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "bad_name", "A game needs a name of 1 to 128 characters."))
		return
	}
	if b.GameID != "" && !games.IDPattern.MatchString(b.GameID) {
		b.GameID = ""
	}
	osName := b.OS
	if osName != "win32" && osName != "darwin" && osName != "linux" {
		osName = "unknown"
	}
	if err := s.Store.ShareGame(r.Context(), store.GameSubmission{Process: process, OS: osName, Name: name, GameID: b.GameID, UserID: auth.UserFrom(r.Context()).ID}); err != nil {
		WriteError(w, err)
		return
	}
	s.emitGamesWaiting(r)
	w.WriteHeader(http.StatusNoContent)
}

// handleMySharedGames is what the data export reads: everything Nexa holds of what this person shared.
func (s *Server) handleMySharedGames(w http.ResponseWriter, r *http.Request) {
	list, err := s.Store.MySharedGames(r.Context(), auth.UserFrom(r.Context()).ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"games": list})
}

func (s *Server) emitGamesWaiting(r *http.Request) {
	n, err := s.Store.WaitingGamesCount(r.Context())
	if err != nil {
		s.Log.Warn("games: waiting count", "err", err)
		return
	}
	s.emitStaff(r.Context(), rankAdmin, gateway.EvModGames, map[string]any{"waiting": n})
}

func (s *Server) handleModGamesCount(w http.ResponseWriter, r *http.Request) {
	n, err := s.Store.WaitingGamesCount(r.Context())
	if err != nil {
		WriteError(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"waiting": n})
}

// handleModGames is everything the panel's page shows, with the games named on
// IGDB as far as it answers: a slow IGDB leaves the ids, not an empty page.
func (s *Server) handleModGames(w http.ResponseWriter, r *http.Request) {
	waiting, err := s.Store.WaitingGames(r.Context())
	if err != nil {
		WriteError(w, err)
		return
	}
	decided, err := s.Store.DecidedGames(r.Context(), "")
	if err != nil {
		WriteError(w, err)
		return
	}
	wanted := []string{}
	for _, g := range waiting {
		if g.GameID != "" {
			wanted = append(wanted, g.GameID)
		}
	}
	for _, g := range decided {
		if g.GameID != "" {
			wanted = append(wanted, g.GameID)
		}
	}
	known := map[string]games.Game{}
	if len(wanted) > 0 && s.Games.On() {
		if found, err := s.Games.Lookup(r.Context(), wanted); err == nil {
			known = found
		} else {
			s.Log.Warn("games: panel lookup", "err", err)
		}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"waiting": waiting, "decided": decided, "games": known})
}

type modGameBody struct {
	Process string `json:"process"`
	Name    string `json:"name"`
	GameID  string `json:"game_id"`
}

func (s *Server) readModGame(w http.ResponseWriter, r *http.Request) (string, modGameBody, bool) {
	var b modGameBody
	if err := ReadJSON(w, r, &b, 2<<10); err != nil {
		WriteError(w, err)
		return "", b, false
	}
	process := strings.ToLower(strings.TrimSpace(b.Process))
	if !gameProcess.MatchString(process) {
		WriteError(w, E(http.StatusBadRequest, "bad_process", "That isn't a program's file name."))
		return "", b, false
	}
	return process, b, true
}

func (s *Server) gameLog(r *http.Request, action string, details map[string]any) *store.ModLogEntry {
	me := auth.UserFrom(r.Context())
	return &store.ModLogEntry{Action: action, ModeratorID: me.ID, ModeratorUsername: me.Username, Details: mustJSON(details)}
}

// knownGamesChanged drops the cached list, so the next app to ask gets the new one.
func (s *Server) knownGamesChanged() {
	s.knownGames.mu.Lock()
	s.knownGames.body, s.knownGames.etag = nil, ""
	s.knownGames.mu.Unlock()
}

// handleModGameList puts a program on the list every app downloads, under the
// name and IGDB game staff chose, whatever people shared.
func (s *Server) handleModGameList(w http.ResponseWriter, r *http.Request) {
	process, b, ok := s.readModGame(w, r)
	if !ok {
		return
	}
	if commonProcesses[process] {
		WriteError(w, E(http.StatusBadRequest, "common_process", "Too many programs use that file name. Listing it would call them all this game."))
		return
	}
	name, ok := cleanGameName(b.Name)
	if !ok {
		WriteError(w, E(http.StatusBadRequest, "bad_name", "A game needs a name of 1 to 128 characters."))
		return
	}
	if b.GameID != "" && !games.IDPattern.MatchString(b.GameID) {
		WriteError(w, E(http.StatusBadRequest, "bad_game", "That isn't a game from the games search."))
		return
	}
	log := s.gameLog(r, store.LogGameList, map[string]any{"process": process, "name": name, "game_id": b.GameID})
	me := auth.UserFrom(r.Context())
	if err := s.Store.DecideGame(r.Context(), store.ListedGame{Process: process, Name: name, GameID: b.GameID, Status: store.GameListed}, me.ID, log); err != nil {
		WriteError(w, err)
		return
	}
	s.gameDecided(w, r, log)
}

func (s *Server) handleModGameReject(w http.ResponseWriter, r *http.Request) {
	process, b, ok := s.readModGame(w, r)
	if !ok {
		return
	}
	name, ok := cleanGameName(b.Name)
	if !ok {
		name = process
	}
	log := s.gameLog(r, store.LogGameReject, map[string]any{"process": process, "name": name})
	me := auth.UserFrom(r.Context())
	if err := s.Store.DecideGame(r.Context(), store.ListedGame{Process: process, Name: name, Status: store.GameRejected}, me.ID, log); err != nil {
		WriteError(w, err)
		return
	}
	s.gameDecided(w, r, log)
}

// handleModGameForget takes a program off the list, or takes back turning it
// down: either way it can be shared again.
func (s *Server) handleModGameForget(w http.ResponseWriter, r *http.Request) {
	process, _, ok := s.readModGame(w, r)
	if !ok {
		return
	}
	log := s.gameLog(r, store.LogGameForget, map[string]any{"process": process})
	found, err := s.Store.ForgetGame(r.Context(), process, log)
	if err != nil {
		WriteError(w, err)
		return
	}
	if !found {
		WriteError(w, ErrNotFound)
		return
	}
	s.gameDecided(w, r, log)
}

func (s *Server) gameDecided(w http.ResponseWriter, r *http.Request, log *store.ModLogEntry) {
	s.knownGamesChanged()
	s.emitLog(r.Context(), log)
	s.emitGamesWaiting(r)
	w.WriteHeader(http.StatusNoContent)
}
