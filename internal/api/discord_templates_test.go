package api

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"
)

func TestParseDiscordTemplateCode(t *testing.T) {
	good := map[string]string{
		"hgM48av5Q69A":                                              "hgM48av5Q69A",
		"  2TffvPucqHkN  ":                                          "2TffvPucqHkN",
		"https://discord.new/6exdzMgjZgah":                          "6exdzMgjZgah",
		"http://discord.new/fVfBazbqjhXg?foo=bar#baz":               "fVfBazbqjhXg",
		"discord.new/fVfBazbqjhXg/":                                 "fVfBazbqjhXg",
		"https://discord.com/template/hgM48av5Q69A":                 "hgM48av5Q69A",
		"https://www.discord.com/templates/hgM48av5Q69A":            "hgM48av5Q69A",
		"https://discordapp.com/template/hgM48av5Q69A":              "hgM48av5Q69A",
		"https://ptb.discord.com/template/hgM48av5Q69A":             "hgM48av5Q69A",
		"https://canary.discord.com/template/hgM48av5Q69A":          "hgM48av5Q69A",
		"discord.com/template/hgM48av5Q69A":                         "hgM48av5Q69A",
		"https://discord.com/api/v10/guilds/templates/hgM48av5Q69A": "hgM48av5Q69A",
	}
	for in, want := range good {
		got, err := ParseDiscordTemplateCode(in)
		if err != nil || got != want {
			t.Errorf("ParseDiscordTemplateCode(%q) = %q, %v; want %q", in, got, err, want)
		}
	}

	invites := []string{
		"https://discord.gg/abc123",
		"discord.gg/abc123",
		"https://discord.com/invite/abc123",
		"https://discordapp.com/invite/abc123",
	}
	for _, in := range invites {
		_, err := ParseDiscordTemplateCode(in)
		if err == nil || err.Code != "invite_not_template" {
			t.Errorf("ParseDiscordTemplateCode(%q) = %v; want invite_not_template", in, err)
		}
	}

	bad := []string{
		"",
		"   ",
		"a",
		"https://example.com/template/hgM48av5Q69A",
		"https://discord.com/channels/123/456",
		"https://discord.new/",
	}
	for _, in := range bad {
		_, err := ParseDiscordTemplateCode(in)
		if err == nil || err.Code != "invalid_template_link" {
			t.Errorf("ParseDiscordTemplateCode(%q) = %v; want invalid_template_link", in, err)
		}
	}
}

func TestDiscordTemplatePreviewAndImport(t *testing.T) {
	fakeDiscord := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v10/guilds/templates/fVfBazbqjhXg":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"code":        "fVfBazbqjhXg",
				"name":        "Classes Template",
				"description": "A template for classes and study groups.",
				"usage_count": 36316,
				"serialized_source_guild": map[string]any{
					"name":                          "Professor Oak's Classes",
					"description":                   "Welcome to the lab!",
					"default_message_notifications": 1,
					"system_channel_id":             3,
					"system_channel_flags":          0,
					"roles": []map[string]any{
						{"id": 0, "name": "@everyone", "permissions": "104324689", "color": 0, "hoist": false, "mentionable": false},
						{"id": 2, "name": "Student", "permissions": "37080128", "color": 15844367, "hoist": true, "mentionable": false},
						{"id": 5, "name": "Professor", "permissions": "104324937", "color": 3066993, "hoist": true, "mentionable": true, "unicode_emoji": "🎓"},
					},
					"channels": []map[string]any{
						{
							"id": 1, "type": 4, "name": "Information", "position": 0, "parent_id": nil,
							"permission_overwrites": []map[string]any{
								{"id": 0, "type": 0, "allow": "0", "deny": "2048"},
								{"id": 5, "type": 0, "allow": "2048", "deny": "0"},
							},
						},
						{
							"id": 2, "type": 5, "name": "announcements", "position": 0, "parent_id": 1,
							"topic":                 "Important class updates.",
							"permission_overwrites": []map[string]any{},
						},
						{
							"id": 3, "type": 0, "name": "general-chat", "position": 1, "parent_id": 1,
							"topic": "Say hello!", "rate_limit_per_user": 10,
							"icon_emoji": map[string]any{"id": nil, "name": "👋"},
							"permission_overwrites": []map[string]any{
								{"id": 0, "type": 0, "allow": "2048", "deny": "0"},
							},
						},
						{
							"id": 4, "type": 15, "name": "questions", "position": 2, "parent_id": 1,
							"topic":                         "Ask questions here.",
							"default_auto_archive_duration": 1440,
							"default_sort_order":            1,
							"default_forum_layout":          2,
							"default_reaction_emoji":        map[string]any{"emoji_name": "👍"},
							"available_tags": []map[string]any{
								{"name": "Homework", "moderated": false, "emoji_name": "📚"},
								{"name": "Resolved", "moderated": true, "emoji_name": "✅"},
							},
							"permission_overwrites": []map[string]any{
								{"id": 0, "type": 0, "allow": "2048", "deny": "0"},
							},
						},
						{
							"id": 6, "type": 4, "name": "Staff Only", "position": 1, "parent_id": nil,
							"permission_overwrites": []map[string]any{
								{"id": 0, "type": 0, "allow": "0", "deny": "1024"},
								{"id": 5, "type": 0, "allow": "1024", "deny": "0"},
							},
						},
						{
							"id": 7, "type": 0, "name": "teacher-lounge", "position": 3, "parent_id": 6,
							"permission_overwrites": []map[string]any{},
						},
						{
							"id": 8, "type": 2, "name": "Office Hours", "position": 0, "parent_id": 1,
							"user_limit": 4,
							"permission_overwrites": []map[string]any{},
						},
					},
				},
			})
		case "/api/v10/guilds/templates/ratelimited":
			w.WriteHeader(http.StatusTooManyRequests)
		default:
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write([]byte(`{"message":"Unknown Guild Template","code":10057}`))
		}
	}))
	defer fakeDiscord.Close()

	srv, ts := newTestServer(t)
	srv.discordAPI = fakeDiscord.URL + "/api/v10"

	tokA, _ := register(t, ts, "alice")
	tokB, _ := register(t, ts, "bob")
	ga := connectGW(t, ts, tokA)

	// 1. Preview
	res, prev := do(t, ts, "GET", "/api/v1/servers/templates/discord/fVfBazbqjhXg", tokA, nil)
	if res.StatusCode != 200 {
		t.Fatalf("preview: %d %v", res.StatusCode, prev)
	}
	if prev["code"] != "fVfBazbqjhXg" || prev["name"] != "Classes Template" || prev["server_name"] != "Professor Oak's Classes" {
		t.Errorf("preview metadata: %v", prev)
	}
	prevRoles := prev["roles"].([]any)
	if len(prevRoles) != 2 || prevRoles[0].(map[string]any)["name"] != "Student" || prevRoles[1].(map[string]any)["name"] != "Professor" {
		t.Errorf("preview roles: %v", prevRoles)
	}
	prevChans := prev["channels"].([]any)
	if len(prevChans) != 7 {
		t.Fatalf("preview channels count = %d, want 7: %v", len(prevChans), prevChans)
	}

	// Unknown template -> 404
	res, errBody := do(t, ts, "GET", "/api/v1/servers/templates/discord/missing123", tokA, nil)
	if res.StatusCode != 404 || errCode(errBody) != "template_not_found" {
		t.Errorf("missing template: %d %v", res.StatusCode, errBody)
	}

	// Rate limited by Discord -> 429
	res, errBody = do(t, ts, "GET", "/api/v1/servers/templates/discord/ratelimited", tokA, nil)
	if res.StatusCode != 429 || errCode(errBody) != "discord_rate_limited" {
		t.Errorf("rate limited template: %d %v", res.StatusCode, errBody)
	}

	// 2. Import from full discord.new link with custom name
	res, view := do(t, ts, "POST", "/api/v1/servers/import/discord", tokA, map[string]any{
		"url":  "https://discord.new/fVfBazbqjhXg",
		"name": "Oak Academy",
	})
	if res.StatusCode != 201 {
		t.Fatalf("import: %d %v", res.StatusCode, view)
	}
	sid := view["id"].(string)
	if view["name"] != "Oak Academy" || view["description"] != "Welcome to the lab!" || view["default_notifications"].(float64) != 1 {
		t.Errorf("imported server fields: %v", view)
	}
	if ev := ga.next("server_create", 2*time.Second); ev == nil || ev["id"] != sid {
		t.Errorf("server_create event: %v", ev)
	}

	// Roles: @everyone (pos 0), Student (pos 1), Professor (pos 2)
	roles := view["roles"].([]any)
	if len(roles) != 3 {
		t.Fatalf("roles count = %d, want 3: %v", len(roles), roles)
	}
	var studentRoleID, profRoleID string
	for _, raw := range roles {
		r := raw.(map[string]any)
		switch r["name"] {
		case "@everyone":
			if r["id"] != sid || r["position"].(float64) != 0 {
				t.Errorf("@everyone role: %v", r)
			}
		case "Student":
			studentRoleID = r["id"].(string)
			if r["position"].(float64) != 1 || r["color"].(float64) != 15844367 || r["hoist"] != true {
				t.Errorf("Student role: %v", r)
			}
		case "Professor":
			profRoleID = r["id"].(string)
			if r["position"].(float64) != 2 || r["color"].(float64) != 3066993 || r["mentionable"] != true || r["icon_emoji"] != "🎓" {
				t.Errorf("Professor role: %v", r)
			}
		}
	}
	if studentRoleID == "" || profRoleID == "" {
		t.Fatalf("missing custom roles: student=%q prof=%q", studentRoleID, profRoleID)
	}

	// Channels: verify types, categories, overwrites, system_channel_id, and private channel key_conv_id.
	general := channelNamed(view, "general-chat")
	if view["system_channel_id"] != general["id"] || general["slowmode_seconds"].(float64) != 10 {
		t.Errorf("general-chat / system_channel_id: sys=%v general=%v", view["system_channel_id"], general)
	}
	if general["glyph"] != nil && general["glyph"] != "" {
		t.Errorf("Discord's onboarding icon_emoji must not replace the channel's # icon: %v", general["glyph"])
	}
	ann := channelNamed(view, "announcements")
	if ann["type"] != "announcement" || ann["topic"] != "Important class updates." {
		t.Errorf("announcements channel: %v", ann)
	}
	// announcements had empty overwrites in the template so it inherited Information's overwrites
	if len(ann["overwrites"].([]any)) != 2 {
		t.Errorf("announcements inherited overwrites: %v", ann["overwrites"])
	}
	questions := channelNamed(view, "questions")
	if questions["type"] != "forum" || questions["default_auto_archive"].(float64) != 1440 {
		t.Errorf("questions forum: %v", questions)
	}
	forum := questions["forum"].(map[string]any)
	if forum["sort"] != "created" || forum["layout"] != "gallery" || forum["default_reaction"] != "👍" || len(forum["tags"].([]any)) != 2 {
		t.Errorf("questions forum settings: %v", forum)
	}
	office := channelNamed(view, "Office Hours")
	if office["type"] != "voice" || office["user_limit"].(float64) != 4 {
		t.Errorf("Office Hours voice channel: %v", office)
	}
	lounge := channelNamed(view, "teacher-lounge")
	if lounge["key_conv_id"] != lounge["id"] {
		t.Errorf("private channel teacher-lounge should encrypt under its own id: %v", lounge)
	}

	// Bob joins the server: he can see general-chat, announcements, questions, Office Hours,
	// but NOT teacher-lounge (which is private to Professor).
	code := inviteFor(t, ts, tokA, general["id"].(string))
	bobServer := join(t, ts, tokB, code)
	for _, raw := range bobServer["channels"].([]any) {
		ch := raw.(map[string]any)
		if ch["id"] == lounge["id"] {
			t.Errorf("bob should not see private channel teacher-lounge: %v", ch)
		}
	}
}
