package api

import (
	"context"

	"evilion.xyz/chat/internal/ids"
	"evilion.xyz/chat/internal/perms"
	"evilion.xyz/chat/internal/store"
)

// A channel is synced with its category when their overwrites are the same,
// as Discord has it: nothing records the link, so changing the channel's own
// overwrites is what takes it out of sync. A synced channel follows every
// change made to its category's overwrites, a channel made in a category
// starts with the category's, and a channel moved into a category takes the
// new category's when it was synced with where it came from.

// sameOverwrites reports whether two channels' overwrites say the same.
func sameOverwrites(a, b []store.Overwrite) bool {
	if len(a) != len(b) {
		return false
	}
	byTarget := make(map[ids.ID]store.Overwrite, len(a))
	for _, ow := range a {
		byTarget[ow.TargetID] = ow
	}
	for _, ow := range b {
		o, ok := byTarget[ow.TargetID]
		if !ok || o.TargetType != ow.TargetType || o.Allow != ow.Allow || o.Deny != ow.Deny {
			return false
		}
	}
	return true
}

// syncedChildren lists a category's channels whose overwrites are the same as
// the category's are now (cat as read before a change).
func (s *Server) syncedChildren(ctx context.Context, cat *store.Channel) ([]*store.Channel, error) {
	chans, err := s.Store.ServerChannels(ctx, *cat.ServerID)
	if err != nil {
		return nil, err
	}
	var out []*store.Channel
	for _, c := range chans {
		if c.ParentID != nil && *c.ParentID == cat.ID && sameOverwrites(c.Overwrites, cat.Overwrites) {
			out = append(out, c)
		}
	}
	return out, nil
}

// copyOverwrites gives a channel the overwrites in from. A channel that
// @everyone can no longer see is marked to encrypt under a key of its own,
// which the next message there makes; it answers whether that happened.
func (s *Server) copyOverwrites(ctx context.Context, ch *store.Channel, from []store.Overwrite) (bool, error) {
	list := make([]store.Overwrite, len(from))
	for i, ow := range from {
		ow.ChannelID = ch.ID
		list[i] = ow
	}
	if err := s.Store.SetOverwrites(ctx, ch.ID, list); err != nil {
		return false, err
	}
	return s.markIfPrivate(ctx, ch), nil
}

// markIfPrivate marks a channel @everyone can no longer see to encrypt under
// a key of its own, and answers whether it did.
func (s *Server) markIfPrivate(ctx context.Context, ch *store.Channel) bool {
	// a category and a link channel hold no messages, so need no key of their own
	if store.HoldsNoMessages(ch.Type) || !s.channelBecamePrivate(ctx, ch) {
		return false
	}
	tr := true
	_ = s.Store.SetKeyFlags(ctx, ch.ID, nil, &tr)
	return true
}

// hasIn reports whether someone holds a permission in a channel.
func (s *Server) hasIn(ctx context.Context, channelID, uid ids.ID, need perms.Perm) bool {
	p, _, err := s.Store.ChannelPerms(ctx, channelID, uid)
	return err == nil && p.Has(need)
}
