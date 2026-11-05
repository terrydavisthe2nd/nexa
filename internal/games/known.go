package games

import (
	_ "embed"
	"encoding/json"
)

// KnownGame is a game recognised by its program's file name, for games no
// store's files on the computer name. Arg must also be on the command line,
// for games run by something else (java).
type KnownGame struct {
	Name   string   `json:"name"`
	Exes   []string `json:"exes"`
	Arg    []string `json:"arg,omitempty"`
	GameID string   `json:"game_id,omitempty"`
}

//go:embed known_games.json
var knownGamesJSON []byte

// BaseKnownGames is the list written by hand, which the desktop app also
// bundles for a first run with no connection.
func BaseKnownGames() ([]KnownGame, error) {
	var list []KnownGame
	if err := json.Unmarshal(knownGamesJSON, &list); err != nil {
		return nil, err
	}
	return list, nil
}
