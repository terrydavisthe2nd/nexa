package api

import (
	"context"
	"net/http"
	"time"

	"evilion.xyz/chat/internal/auth"
	"evilion.xyz/chat/internal/store"
)

// Nexa's staff come in five ranks, each holding everything the ranks below it
// hold (reworked 2026-11-04 so each rank's powers fit its job):
//
//   - Helper: support and triage. The report queue (claiming, notes, and
//     dismissing, never Action Taken), bug reports, and reading people, cases
//     and the log. No action on any account or content.
//   - Mod: a moderator. Takes reported content down (messages, profile
//     parts, stickers, emoji, packs), mutes and bans for at most 30 days
//     (modMaxDuration; never permanent, never shortening a longer one),
//     replies in cases, and lifts, closes and reopens the cases they opened.
//     Browses servers and packs.
//   - Admin: everything else in the panel: restrictions of any length,
//     anyone's cases, server and Discover takedowns, alt accounts, call
//     usage, apps, announcements and badges, disabling sign-in and deleting
//     accounts of people who are not staff.
//   - Developer: the instance itself: its settings, stats and crash reports.
//     A Developer has no rights in other people's servers or group chats.
//   - Owner: the oldest Developer. Only the owner gives and takes ranks, sees
//     the legal report, and disables or deletes staff.
//
// A rank is a public flag bit, so every app can draw it. Staff of any rank
// cannot be moderated until the owner takes their rank away. The apps hold
// the same table (web lib/staffRank.ts, Android data/StaffRanks.kt).
type staffRank int

const (
	rankNone staffRank = iota
	rankHelper
	rankMod
	rankAdmin
	rankDeveloper
	rankOwner
)

// modMaxDuration is the longest mute or ban a mod can give.
const modMaxDuration = int64(30 * 24 * time.Hour / time.Millisecond)

// rankNames are the ranks as the API spells them; "" is no rank.
var rankNames = map[staffRank]string{rankNone: "", rankHelper: "helper", rankMod: "mod", rankAdmin: "admin", rankDeveloper: "developer", rankOwner: "owner"}

// rankBits is the flag bit each grantable rank is stored as. The owner is
// not granted: it is the oldest Developer.
var rankBits = map[staffRank]int64{rankHelper: store.FlagStaffHelper, rankMod: store.FlagStaffMod, rankAdmin: store.FlagStaffAdmin, rankDeveloper: store.FlagAdmin}

// rankFromFlags reads a rank from an account's flags, given whether it is the
// owner's account.
func rankFromFlags(flags int64, owner bool) staffRank {
	switch {
	case flags&store.FlagAdmin != 0 && owner:
		return rankOwner
	case flags&store.FlagAdmin != 0:
		return rankDeveloper
	case flags&store.FlagStaffAdmin != 0:
		return rankAdmin
	case flags&store.FlagStaffMod != 0:
		return rankMod
	case flags&store.FlagStaffHelper != 0:
		return rankHelper
	}
	return rankNone
}

// rankOf is u's rank. Only a Developer needs the owner looked up; a lookup
// that fails leaves them a Developer, never the owner.
func (s *Server) rankOf(ctx context.Context, u *store.User) staffRank {
	owner := false
	if u.Flags&store.FlagAdmin != 0 {
		id, err := s.Store.InstanceOwnerID(ctx)
		owner = err == nil && id == u.ID
	}
	return rankFromFlags(u.Flags, owner)
}

// staffRankError is the refusal for someone below the rank a thing needs.
func staffRankError(need staffRank) *Error {
	switch need {
	case rankOwner:
		return E(http.StatusForbidden, "owner_only", "Only the owner of Nexa can do that.")
	case rankDeveloper:
		return E(http.StatusForbidden, "developer_only", "Only Nexa developers and the owner can do that.")
	case rankAdmin:
		return E(http.StatusForbidden, "staff_admin_only", "Only Nexa admins and above can do that.")
	case rankMod:
		return E(http.StatusForbidden, "staff_mod_only", "Only Nexa mods and above can do that.")
	}
	return E(http.StatusForbidden, "admin_only", "Only Nexa staff can do that.")
}

// staff gates a handler behind a staff rank.
func (s *Server) staff(need staffRank, h http.HandlerFunc) http.Handler {
	return s.authed(func(w http.ResponseWriter, r *http.Request) {
		// someone with no rank at all is told what they always were
		if rank := s.rankOf(r.Context(), auth.UserFrom(r.Context())); rank < need {
			required := need
			if rank == rankNone {
				required = rankNone
			}
			WriteError(w, staffRankError(required))
			return
		}
		h(w, r)
	})
}

// staffBits are the flags of the staff who are at least need (Owner counts
// as Developer here, as they share the bit).
func staffBits(need staffRank) int64 {
	var bits int64
	for r, b := range rankBits {
		if r >= need {
			bits |= b
		}
	}
	return bits
}
