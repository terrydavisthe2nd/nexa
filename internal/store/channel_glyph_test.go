package store

import (
	"context"
	"testing"
)

// A channel's icon is written by UpdateChannel and read back by every way a
// channel is loaded: on its own, and in its server's list.
func TestChannelGlyph(t *testing.T) {
	s := newStore(t)
	ctx := context.Background()
	owner := mkUser(t, s, "owner")
	srv, err := s.CreateServer(ctx, owner.ID, "Glyphs", DefaultNames{})
	if err != nil {
		t.Fatal(err)
	}
	chans, _ := s.ServerChannels(ctx, srv.ID)
	var general *Channel
	for _, c := range chans {
		if c.Name == "general" {
			general = c
		}
		if c.Glyph != "" {
			t.Errorf("a new channel has no icon, %q has %q", c.Name, c.Glyph)
		}
	}
	if general == nil {
		t.Fatal("no general")
	}
	if err := s.UpdateChannel(ctx, general.ID, ChannelPatch{Glyph: ptr("icon:megaphone")}); err != nil {
		t.Fatal(err)
	}
	got, err := s.ChannelByID(ctx, general.ID)
	if err != nil || got.Glyph != "icon:megaphone" {
		t.Fatalf("by id: %q %v", got.Glyph, err)
	}
	// a patch that says nothing about the icon leaves it alone
	if err := s.UpdateChannel(ctx, general.ID, ChannelPatch{Topic: ptr("news")}); err != nil {
		t.Fatal(err)
	}
	chans, _ = s.ServerChannels(ctx, srv.ID)
	for _, c := range chans {
		if c.ID == general.ID && (c.Glyph != "icon:megaphone" || c.Topic != "news") {
			t.Fatalf("in the list: %q %q", c.Glyph, c.Topic)
		}
	}
	if err := s.UpdateChannel(ctx, general.ID, ChannelPatch{Glyph: ptr("")}); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.ChannelByID(ctx, general.ID); got.Glyph != "" {
		t.Fatalf("cleared: %q", got.Glyph)
	}
	// a voice channel takes an emoji the same way
	c, err := s.CreateServerChannel(ctx, srv.ID, "voice", "Music", nil, "")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateChannel(ctx, c.ID, ChannelPatch{Glyph: ptr("emoji:🎵")}); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.ChannelByID(ctx, c.ID); got.Glyph != "emoji:🎵" || got.Type != "voice" {
		t.Fatalf("voice: %q %q", got.Glyph, got.Type)
	}
}
