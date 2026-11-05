package api

import (
	"cmp"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode/utf8"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

var reDiscordTemplateCode = regexp.MustCompile(`^[A-Za-z0-9_-]{2,32}$`)

var (
	errInvalidTemplateLink = E(http.StatusBadRequest, "invalid_template_link", "Paste a Discord server template link, like https://discord.new/2TffvPucqHkN.")
	errInviteNotTemplate   = E(http.StatusBadRequest, "invite_not_template", "That is a Discord invite link, not a server template link. Templates look like https://discord.new/2TffvPucqHkN.")
	errTemplateNotFound    = E(http.StatusNotFound, "template_not_found", "Discord has no server template with that link or code.")
	errDiscordRateLimited  = E(http.StatusTooManyRequests, "discord_rate_limited", "Discord asked to slow down. Try again in a moment.")
	errDiscordTemplateDown = E(http.StatusBadGateway, "discord_unavailable", "Discord could not be reached right now.")
	errEmptyTemplate       = E(http.StatusBadRequest, "empty_template", "That template has no channels.")
)

// ParseDiscordTemplateCode extracts a template code from a discord.new link, a
// discord.com/template link, or a bare template code.
func ParseDiscordTemplateCode(input string) (string, *Error) {
	s := strings.TrimSpace(input)
	if s == "" {
		return "", errInvalidTemplateLink
	}
	if !strings.Contains(s, "://") && !strings.ContainsAny(s, "/.") {
		if !reDiscordTemplateCode.MatchString(s) {
			return "", errInvalidTemplateLink
		}
		return s, nil
	}
	if !strings.Contains(s, "://") {
		s = "https://" + s
	}
	u, err := url.Parse(s)
	if err != nil || u.Host == "" {
		return "", errInvalidTemplateLink
	}
	host := strings.ToLower(u.Hostname())
	for _, prefix := range []string{"www.", "ptb.", "canary."} {
		host = strings.TrimPrefix(host, prefix)
	}
	parts := strings.FieldsFunc(strings.Trim(u.EscapedPath(), "/"), func(r rune) bool { return r == '/' })
	switch host {
	case "discord.gg":
		return "", errInviteNotTemplate
	case "discord.new":
		if len(parts) != 1 || !reDiscordTemplateCode.MatchString(parts[0]) {
			return "", errInvalidTemplateLink
		}
		return parts[0], nil
	case "discord.com", "discordapp.com":
		if len(parts) >= 1 && strings.EqualFold(parts[0], "invite") {
			return "", errInviteNotTemplate
		}
		// /template/{code} or /templates/{code}
		if len(parts) == 2 && (strings.EqualFold(parts[0], "template") || strings.EqualFold(parts[0], "templates")) && reDiscordTemplateCode.MatchString(parts[1]) {
			return parts[1], nil
		}
		// /api/guilds/templates/{code} or /api/v10/guilds/templates/{code}
		if len(parts) == 4 && strings.EqualFold(parts[0], "api") && strings.EqualFold(parts[1], "guilds") && strings.EqualFold(parts[2], "templates") && reDiscordTemplateCode.MatchString(parts[3]) {
			return parts[3], nil
		}
		if len(parts) == 5 && strings.EqualFold(parts[0], "api") && strings.EqualFold(parts[2], "guilds") && strings.EqualFold(parts[3], "templates") && reDiscordTemplateCode.MatchString(parts[4]) {
			return parts[4], nil
		}
		return "", errInvalidTemplateLink
	default:
		return "", errInvalidTemplateLink
	}
}

// templatePlaceholderID unmarshals a placeholder integer or string id from a
// Discord template's serialized_source_guild.
type templatePlaceholderID string

func (id *templatePlaceholderID) UnmarshalJSON(b []byte) error {
	s := strings.TrimSpace(string(b))
	if s == "" || s == "null" {
		*id = ""
		return nil
	}
	if s[0] == '"' {
		var str string
		if err := json.Unmarshal(b, &str); err != nil {
			return err
		}
		*id = templatePlaceholderID(strings.TrimSpace(str))
		return nil
	}
	var n int64
	if err := json.Unmarshal(b, &n); err != nil {
		return err
	}
	*id = templatePlaceholderID(strconv.FormatInt(n, 10))
	return nil
}

type discordGuildTemplate struct {
	Code                  string               `json:"code"`
	Name                  string               `json:"name"`
	Description           *string              `json:"description"`
	UsageCount            int                  `json:"usage_count"`
	SerializedSourceGuild discordTemplateGuild `json:"serialized_source_guild"`
}

type discordTemplateGuild struct {
	Name                        string                   `json:"name"`
	Description                 *string                  `json:"description"`
	DefaultMessageNotifications int                      `json:"default_message_notifications"`
	SystemChannelID             templatePlaceholderID    `json:"system_channel_id"`
	SystemChannelFlags          int                      `json:"system_channel_flags"`
	Roles                       []discordTemplateRole    `json:"roles"`
	Channels                    []discordTemplateChannel `json:"channels"`
}

type discordTemplateRole struct {
	ID          templatePlaceholderID `json:"id"`
	Name        string                `json:"name"`
	Permissions perms.Perm            `json:"permissions"`
	Color       int                   `json:"color"`
	Colors      *struct {
		PrimaryColor   *int `json:"primary_color"`
		SecondaryColor *int `json:"secondary_color"`
		TertiaryColor  *int `json:"tertiary_color"`
	} `json:"colors"`
	Hoist        bool    `json:"hoist"`
	Mentionable  bool    `json:"mentionable"`
	UnicodeEmoji *string `json:"unicode_emoji"`
}

type discordTemplateOverwrite struct {
	ID    templatePlaceholderID `json:"id"`
	Type  templatePlaceholderID `json:"type"`
	Allow perms.Perm            `json:"allow"`
	Deny  perms.Perm            `json:"deny"`
}

type discordTemplateChannel struct {
	ID                         templatePlaceholderID      `json:"id"`
	Type                       int                        `json:"type"`
	Name                       string                     `json:"name"`
	Position                   int                        `json:"position"`
	Topic                      *string                    `json:"topic"`
	UserLimit                  int                        `json:"user_limit"`
	NSFW                       bool                       `json:"nsfw"`
	RateLimitPerUser           int                        `json:"rate_limit_per_user"`
	ParentID                   templatePlaceholderID      `json:"parent_id"`
	DefaultAutoArchiveDuration *int                       `json:"default_auto_archive_duration"`
	PermissionOverwrites       []discordTemplateOverwrite `json:"permission_overwrites"`
	AvailableTags              []struct {
		Name      string  `json:"name"`
		Moderated bool    `json:"moderated"`
		EmojiName *string `json:"emoji_name"`
	} `json:"available_tags"`
	DefaultReactionEmoji *struct {
		EmojiName *string `json:"emoji_name"`
	} `json:"default_reaction_emoji"`
	DefaultSortOrder   *int `json:"default_sort_order"`
	DefaultForumLayout *int `json:"default_forum_layout"`
}

type discordTemplatePreview struct {
	Code              string                          `json:"code"`
	Name              string                          `json:"name"`
	Description       string                          `json:"description"`
	UsageCount        int                             `json:"usage_count"`
	ServerName        string                          `json:"server_name"`
	ServerDescription string                          `json:"server_description"`
	Channels          []discordTemplatePreviewChannel `json:"channels"`
	Roles             []discordTemplatePreviewRole    `json:"roles"`
}

type discordTemplatePreviewChannel struct {
	ID          int    `json:"id"`
	Type        string `json:"type"`
	Name        string `json:"name"`
	Topic       string `json:"topic,omitempty"`
	ParentIndex int    `json:"parent_index"`
	Private     bool   `json:"private,omitempty"`
}

type discordTemplatePreviewRole struct {
	Name  string `json:"name"`
	Color int    `json:"color"`
}

var discordTemplateCache = struct {
	sync.Mutex
	m map[string]struct {
		at  time.Time
		tpl *discordGuildTemplate
	}
}{m: map[string]struct {
	at  time.Time
	tpl *discordGuildTemplate
}{}}

var (
	discordTemplateClientMu sync.Mutex
	discordTemplateClients  = map[string]*http.Client{}
)

func (s *Server) discordTemplateHTTPClient() *http.Client {
	if s.Apps != nil && s.Apps.Client != nil {
		return s.Apps.Client
	}
	addr := ""
	if s.Egress != nil {
		addr = s.Egress.Addr
	}
	discordTemplateClientMu.Lock()
	defer discordTemplateClientMu.Unlock()
	if c, ok := discordTemplateClients[addr]; ok {
		return c
	}
	c := &http.Client{Timeout: 10 * time.Second}
	if s.Egress != nil {
		c.Transport = s.Egress.Transport()
	}
	discordTemplateClients[addr] = c
	return c
}

func (s *Server) discordTemplateBase() string {
	return strings.TrimRight(cmp.Or(s.discordAPI, s.Cfg.DiscordAPIURL, "https://discord.com/api/v10"), "/")
}

func (s *Server) fetchDiscordTemplate(ctx context.Context, code string) (*discordGuildTemplate, *Error) {
	base := s.discordTemplateBase()
	cacheKey := base + "|" + code
	discordTemplateCache.Lock()
	if hit, ok := discordTemplateCache.m[cacheKey]; ok && time.Since(hit.at) < 5*time.Minute {
		discordTemplateCache.Unlock()
		return hit.tpl, nil
	}
	discordTemplateCache.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/guilds/templates/"+url.PathEscape(code), nil)
	if err != nil {
		return nil, errDiscordTemplateDown
	}
	req.Header.Set("User-Agent", "Nexa (https://nexa.fish, server templates)")
	req.Header.Set("Accept", "application/json")
	res, err := s.discordTemplateHTTPClient().Do(req)
	if err != nil {
		return nil, errDiscordTemplateDown
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, 2<<20))
	if err != nil {
		return nil, errDiscordTemplateDown
	}
	switch res.StatusCode {
	case http.StatusOK:
	case http.StatusNotFound, http.StatusBadRequest:
		return nil, errTemplateNotFound
	case http.StatusTooManyRequests:
		return nil, errDiscordRateLimited
	default:
		return nil, errDiscordTemplateDown
	}
	var tpl discordGuildTemplate
	if err := json.Unmarshal(body, &tpl); err != nil {
		return nil, errDiscordTemplateDown
	}
	if tpl.Code == "" {
		tpl.Code = code
	}
	discordTemplateCache.Lock()
	if len(discordTemplateCache.m) > 200 {
		clear(discordTemplateCache.m)
	}
	discordTemplateCache.m[cacheKey] = struct {
		at  time.Time
		tpl *discordGuildTemplate
	}{time.Now(), &tpl}
	discordTemplateCache.Unlock()
	return &tpl, nil
}

func truncateCleanText(s string, maxRunes int) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	if utf8.RuneCountInString(s) > maxRunes {
		runes := []rune(s)
		s = string(runes[:maxRunes])
	}
	clean, ok := CleanText(s, maxRunes)
	if !ok {
		return ""
	}
	return clean
}

func discordChannelType(t int) string {
	switch t {
	case 0:
		return "text"
	case 2, 13:
		return "voice"
	case 4:
		return "category"
	case 5:
		return store.ChannelAnnouncement
	case 15:
		return store.ChannelForum
	case 16:
		return store.ChannelMedia
	default:
		return ""
	}
}

func buildTemplateSpec(tpl *discordGuildTemplate, customName string) (store.TemplateSpec, discordTemplatePreview, *Error) {
	guild := tpl.SerializedSourceGuild
	serverName := ""
	if strings.TrimSpace(customName) != "" {
		clean, ok := cleanServerName(customName)
		if !ok {
			return store.TemplateSpec{}, discordTemplatePreview{}, E(http.StatusBadRequest, "invalid_name", "Server names are 1–100 characters.")
		}
		serverName = clean
	} else {
		if clean, ok := cleanServerName(guild.Name); ok {
			serverName = clean
		} else if clean, ok := cleanServerName(tpl.Name); ok {
			serverName = clean
		} else {
			serverName = "Server"
		}
	}
	tplName, ok := cleanServerName(tpl.Name)
	if !ok {
		tplName = serverName
	}
	var tplDesc, srvDesc string
	if tpl.Description != nil {
		tplDesc = truncateCleanText(*tpl.Description, 1000)
	}
	if guild.Description != nil {
		srvDesc = truncateCleanText(*guild.Description, 1000)
	}
	if srvDesc == "" {
		srvDesc = tplDesc
	}
	defaultNotifs := 0
	if guild.DefaultMessageNotifications == 1 {
		defaultNotifs = 1
	}

	// Roles: @everyone (id "0") plus up to maxServerRoles-1 custom roles in template order.
	everyonePerms := perms.DefaultEveryone
	roleMap := map[templatePlaceholderID]int{}
	var roles []store.TemplateRole
	var previewRoles []discordTemplatePreviewRole
	for i, r := range guild.Roles {
		if r.ID == "0" || (i == 0 && r.Name == "@everyone") {
			p := r.Permissions & perms.All
			// Older Discord templates were saved before threads, stickers,
			// soundboards or polls had permission bits of their own: grant the
			// newer defaults whenever @everyone could already talk in channels.
			if p != 0 {
				// Discord has no such bit, so no template can have meant to take it away
				p |= perms.UseStereoVoice
				if p.Has(perms.SendMessages) && r.Permissions < (1<<32) {
					p |= perms.CreatePublicThreads | perms.CreatePrivateThreads | perms.UseExternalStickers | perms.SendMessagesInThreads
				}
				if p.Has(perms.SendMessages) && r.Permissions < (1<<49) {
					p |= perms.CreatePolls | perms.UseSoundboard
				}
				everyonePerms = p
			}
			continue
		}
		if len(roles) >= maxServerRoles-1 {
			break
		}
		rName, ok := cleanServerName(r.Name)
		if !ok {
			rName = "new role"
		}
		color := 0
		if r.Color >= 0 && r.Color <= 0xFFFFFF {
			color = r.Color
		}
		var color2, color3 *int
		if r.Colors != nil {
			if r.Colors.PrimaryColor != nil && *r.Colors.PrimaryColor >= 0 && *r.Colors.PrimaryColor <= 0xFFFFFF {
				color = *r.Colors.PrimaryColor
			}
			if r.Colors.SecondaryColor != nil && *r.Colors.SecondaryColor >= 0 && *r.Colors.SecondaryColor <= 0xFFFFFF {
				c2 := *r.Colors.SecondaryColor
				color2 = &c2
			}
			if r.Colors.TertiaryColor != nil && *r.Colors.TertiaryColor >= 0 && *r.Colors.TertiaryColor <= 0xFFFFFF {
				c3 := *r.Colors.TertiaryColor
				color3 = &c3
			}
		}
		iconEmoji := ""
		if r.UnicodeEmoji != nil {
			em := strings.TrimSpace(*r.UnicodeEmoji)
			if em != "" && len(em) <= maxEmojiLen && isEmojiSequence(em) {
				iconEmoji = em
			}
		}
		idx := len(roles)
		if r.ID != "" {
			roleMap[r.ID] = idx
		}
		roles = append(roles, store.TemplateRole{
			Name:        rName,
			Color:       color,
			Color2:      color2,
			Color3:      color3,
			Hoist:       r.Hoist,
			Mentionable: r.Mentionable,
			Permissions: r.Permissions & perms.All,
			IconEmoji:   iconEmoji,
		})
		previewRoles = append(previewRoles, discordTemplatePreviewRole{Name: rName, Color: color})
	}
	if previewRoles == nil {
		previewRoles = []discordTemplatePreviewRole{}
	}

	convertOverwrites := func(raw []discordTemplateOverwrite) []store.TemplateOverwrite {
		out := []store.TemplateOverwrite{}
		seen := map[int]bool{}
		for _, ow := range raw {
			if ow.Type != "0" && !strings.EqualFold(string(ow.Type), "role") {
				continue
			}
			roleIdx := -2
			if ow.ID == "0" {
				roleIdx = -1
			} else if idx, ok := roleMap[ow.ID]; ok {
				roleIdx = idx
			}
			if roleIdx < -1 || seen[roleIdx] {
				continue
			}
			allow := (ow.Allow & perms.All) &^ (ow.Deny & perms.All)
			deny := ow.Deny & perms.All
			if allow == 0 && deny == 0 {
				continue
			}
			seen[roleIdx] = true
			out = append(out, store.TemplateOverwrite{RoleIndex: roleIdx, Allow: allow, Deny: deny})
		}
		return out
	}

	type indexedChan struct {
		order int
		raw   discordTemplateChannel
		typ   string
	}
	var rawCats, rawChans []indexedChan
	for i, ch := range guild.Channels {
		typ := discordChannelType(ch.Type)
		if typ == "" {
			continue
		}
		if typ == "category" {
			rawCats = append(rawCats, indexedChan{order: i, raw: ch, typ: typ})
		} else {
			rawChans = append(rawChans, indexedChan{order: i, raw: ch, typ: typ})
		}
	}
	slices.SortFunc(rawCats, func(a, b indexedChan) int {
		if a.raw.Position != b.raw.Position {
			return cmp.Compare(a.raw.Position, b.raw.Position)
		}
		return cmp.Compare(a.order, b.order)
	})

	var channels []store.TemplateChannel
	catIndexByID := map[templatePlaceholderID]int{}
	catOverwritesByIndex := map[int][]discordTemplateOverwrite{}
	for _, c := range rawCats {
		if len(channels) >= maxServerChans {
			break
		}
		name, ok := cleanChannelName(c.raw.Name, "category")
		if !ok {
			name = "Category"
		}
		idx := len(channels)
		if c.raw.ID != "" {
			catIndexByID[c.raw.ID] = idx
		}
		catOverwritesByIndex[idx] = c.raw.PermissionOverwrites
		channels = append(channels, store.TemplateChannel{
			Type:        "category",
			Name:        name,
			Position:    idx,
			ParentIndex: -1,
			Overwrites:  convertOverwrites(c.raw.PermissionOverwrites),
		})
	}

	// Group non-category channels by their resolved parent category so each
	// category's text/announcement/forum channels keep their order above its
	// voice channels, numbered 0, 1, 2... within that parent.
	slices.SortFunc(rawChans, func(a, b indexedChan) int {
		pa, pb := -1, -1
		if idx, ok := catIndexByID[a.raw.ParentID]; ok {
			pa = idx
		}
		if idx, ok := catIndexByID[b.raw.ParentID]; ok {
			pb = idx
		}
		if pa != pb {
			return cmp.Compare(pa, pb)
		}
		va, vb := a.typ == "voice", b.typ == "voice"
		if va != vb {
			if va {
				return 1
			}
			return -1
		}
		if a.raw.Position != b.raw.Position {
			return cmp.Compare(a.raw.Position, b.raw.Position)
		}
		return cmp.Compare(a.order, b.order)
	})

	posInParent := map[int]int{}
	chanIndexByID := map[templatePlaceholderID]int{}
	for _, c := range rawChans {
		if len(channels) >= maxServerChans {
			break
		}
		parentIdx := -1
		if c.raw.ParentID != "" {
			if idx, ok := catIndexByID[c.raw.ParentID]; ok {
				parentIdx = idx
			}
		}
		name, ok := cleanChannelName(c.raw.Name, c.typ)
		if !ok {
			if c.typ == "voice" {
				name = "General"
			} else {
				name = "general"
			}
		}
		topicLimit := maxTopic
		if store.IsForum(c.typ) {
			topicLimit = maxForumTopic
		}
		topic := ""
		if c.raw.Topic != nil {
			topic = truncateCleanText(*c.raw.Topic, topicLimit)
		}
		slowmode := max(0, min(21600, c.raw.RateLimitPerUser))
		userLimit := 0
		if c.typ == "voice" {
			userLimit = max(0, min(99, c.raw.UserLimit))
		}
		autoArchive := 0
		if store.HoldsThreads(c.typ) && c.raw.DefaultAutoArchiveDuration != nil && validAutoArchive(*c.raw.DefaultAutoArchiveDuration) {
			autoArchive = *c.raw.DefaultAutoArchiveDuration
		}
		var forum *store.ForumSettings
		if store.IsForum(c.typ) {
			f := &store.ForumSettings{Sort: "activity", Layout: "list", Tags: []store.ForumTag{}}
			if c.raw.DefaultSortOrder != nil && *c.raw.DefaultSortOrder == 1 {
				f.Sort = "created"
			}
			if c.raw.DefaultForumLayout != nil && *c.raw.DefaultForumLayout == 2 {
				f.Layout = "gallery"
			}
			if c.raw.DefaultReactionEmoji != nil && c.raw.DefaultReactionEmoji.EmojiName != nil {
				if em, ok := parseEmoji(*c.raw.DefaultReactionEmoji.EmojiName); ok {
					f.DefaultReaction = em
				}
			}
			for _, t := range c.raw.AvailableTags {
				if len(f.Tags) >= maxForumTags {
					break
				}
				tName, ok := cleanServerName(t.Name)
				if !ok || utf8.RuneCountInString(tName) > 50 {
					continue
				}
				tEmoji := ""
				if t.EmojiName != nil {
					if em, ok := parseEmoji(*t.EmojiName); ok {
						tEmoji = em
					}
				}
				f.Tags = append(f.Tags, store.ForumTag{Name: tName, Emoji: tEmoji, Moderated: t.Moderated})
			}
			forum = f
		}
		rawOWs := c.raw.PermissionOverwrites
		if len(rawOWs) == 0 && parentIdx >= 0 {
			rawOWs = catOverwritesByIndex[parentIdx]
		}
		pos := posInParent[parentIdx]
		posInParent[parentIdx] = pos + 1
		idx := len(channels)
		if c.raw.ID != "" {
			chanIndexByID[c.raw.ID] = idx
		}
		channels = append(channels, store.TemplateChannel{
			Type:               c.typ,
			Name:               name,
			Topic:              topic,
			Position:           pos,
			ParentIndex:        parentIdx,
			NSFW:               c.raw.NSFW,
			SlowmodeSeconds:    slowmode,
			UserLimit:          userLimit,
			DefaultAutoArchive: autoArchive,
			Forum:              forum,
			Overwrites:         convertOverwrites(rawOWs),
		})
	}
	if len(channels) == 0 {
		return store.TemplateSpec{}, discordTemplatePreview{}, errEmptyTemplate
	}

	sysIdx := -1
	if guild.SystemChannelID != "" && guild.SystemChannelFlags&1 == 0 {
		if idx, ok := chanIndexByID[guild.SystemChannelID]; ok && channels[idx].Type == "text" {
			sysIdx = idx
		}
	}

	// Build preview channels with privacy flags computed the same way as the store.
	const fakeEveryoneID = ids.ID(1)
	permRoles := make([]perms.Role, 0, len(roles)+1)
	permRoles = append(permRoles, perms.Role{ID: fakeEveryoneID, Position: 0, Permissions: everyonePerms})
	for i, r := range roles {
		permRoles = append(permRoles, perms.Role{ID: ids.ID(i + 2), Position: i + 1, Permissions: r.Permissions})
	}
	previewChans := make([]discordTemplatePreviewChannel, len(channels))
	for i, ch := range channels {
		priv := false
		if ch.Type != "category" {
			pows := make([]perms.Overwrite, len(ch.Overwrites))
			for j, ow := range ch.Overwrites {
				target := fakeEveryoneID
				if ow.RoleIndex >= 0 {
					target = ids.ID(ow.RoleIndex + 2)
				}
				pows[j] = perms.Overwrite{TargetID: target, Kind: "role", Allow: ow.Allow, Deny: ow.Deny}
			}
			priv = !perms.Compute(fakeEveryoneID, permRoles, perms.Member{}, false, pows).Has(perms.ViewChannel)
		}
		previewChans[i] = discordTemplatePreviewChannel{
			ID:          i,
			Type:        ch.Type,
			Name:        ch.Name,
			Topic:       ch.Topic,
			ParentIndex: ch.ParentIndex,
			Private:     priv,
		}
	}

	spec := store.TemplateSpec{
		Name:                 serverName,
		Description:          srvDesc,
		DefaultNotifications: defaultNotifs,
		EveryonePerms:        everyonePerms,
		Roles:                roles,
		Channels:             channels,
		SystemChannelIndex:   sysIdx,
	}
	preview := discordTemplatePreview{
		Code:              tpl.Code,
		Name:              tplName,
		Description:       tplDesc,
		UsageCount:        tpl.UsageCount,
		ServerName:        serverName,
		ServerDescription: srvDesc,
		Channels:          previewChans,
		Roles:             previewRoles,
	}
	return spec, preview, nil
}

func (s *Server) handleDiscordTemplatePreview(w http.ResponseWriter, r *http.Request) {
	if mutedFor(w, r) {
		return
	}
	code, apiErr := ParseDiscordTemplateCode(r.PathValue("code"))
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	tpl, apiErr := s.fetchDiscordTemplate(r.Context(), code)
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	_, preview, apiErr := buildTemplateSpec(tpl, "")
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	WriteJSON(w, http.StatusOK, preview)
}

func (s *Server) handleDiscordTemplateImport(w http.ResponseWriter, r *http.Request) {
	if !s.requireCaptcha(w, r, "create_server") {
		return
	}
	me := auth.UserFrom(r.Context())
	var req struct {
		URL  string `json:"url"`
		Name string `json:"name"`
	}
	if err := ReadJSON(w, r, &req, 4<<10); err != nil {
		WriteError(w, err)
		return
	}
	code, apiErr := ParseDiscordTemplateCode(req.URL)
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	tpl, apiErr := s.fetchDiscordTemplate(r.Context(), code)
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	spec, _, apiErr := buildTemplateSpec(tpl, req.Name)
	if apiErr != nil {
		WriteError(w, apiErr)
		return
	}
	srv, err := s.Store.CreateServerFromTemplate(r.Context(), me.ID, spec)
	if err != nil {
		WriteError(w, err)
		return
	}
	view, users, err := s.serverView(r.Context(), srv, me.ID)
	if err != nil {
		WriteError(w, err)
		return
	}
	s.Bcast.EmitUsers([]ids.ID{me.ID}, "server_create", serverCreateEvent{view, users})
	WriteJSON(w, http.StatusCreated, view)
}
