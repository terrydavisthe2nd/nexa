package api

import (
	"net/http"
	"strconv"
	"strings"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/store"
)

func (s *Server) mentionRoutes(mux *http.ServeMux) {
	mux.Handle("GET /api/v1/users/@me/mentions", s.authed(s.handleMentions))
}

// handleMentions lists the messages that mentioned the caller, from every
// conversation and server channel whose history they can read (or one server,
// with server_id, or only conversations, with server_id=@me, or one channel).
// kinds narrows it to me, roles and everyone; sort=oldest pages forward from
// after, otherwise newest first back from before. The messages are sealed as
// always: the apps open them.
func (s *Server) handleMentions(w http.ResponseWriter, r *http.Request) {
	me := auth.UserFrom(r.Context())
	qv := r.URL.Query()
	q := store.MentionQuery{Oldest: qv.Get("sort") == "oldest"}
	if raw := qv.Get("kinds"); raw == "" {
		q.Kinds = store.MentionKindAll
	} else {
		for _, k := range strings.Split(raw, ",") {
			switch strings.TrimSpace(k) {
			case "me":
				q.Kinds |= store.MentionKindMe
			case "roles":
				q.Kinds |= store.MentionKindRole
			case "everyone":
				q.Kinds |= store.MentionKindEveryone
			}
		}
	}
	parse := func(k string) ids.ID {
		id, _ := ids.Parse(qv.Get(k))
		return id
	}
	q.Before, q.After = parse("before"), parse("after")
	if l, err := strconv.Atoi(qv.Get("limit")); err == nil {
		q.Limit = l
	}
	server := qv.Get("server_id")
	onlyServer := parse("server_id")
	onlyChannel := parse("channel_id")
	if server != "" && server != "@me" && onlyServer.IsZero() {
		WriteJSON(w, http.StatusOK, []*store.Message{})
		return
	}
	// conversations the caller is in and may open in this session
	if onlyServer.IsZero() {
		dms, err := s.Store.DMChannelsForUser(r.Context(), me.ID)
		if err != nil {
			WriteError(w, err)
			return
		}
		for _, ch := range dms {
			if !s.unlocked(r, ch) {
				continue
			}
			q.Channels = append(q.Channels, ch.ID)
		}
	}
	if server != "@me" && s.ChannelAccess != nil {
		more, err := s.Store.MentionChannels(r.Context(), me.ID, onlyServer)
		if err != nil {
			WriteError(w, err)
			return
		}
		q.Channels = append(q.Channels, more...)
	}
	if !onlyChannel.IsZero() {
		kept := q.Channels[:0]
		for _, ch := range q.Channels {
			if ch == onlyChannel {
				kept = append(kept, ch)
			}
		}
		q.Channels = kept
	}
	list, err := s.Store.Mentions(r.Context(), me.ID, q)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.withRefs(r.Context(), me.ID, list...)
	WriteJSON(w, http.StatusOK, list)
}
