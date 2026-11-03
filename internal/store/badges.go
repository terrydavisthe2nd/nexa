package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

// Profile badges. Most are earned and worked out here when a profile is
// opened, from what the account already is or did; staff give Contributor and
// the event badges they make by hand (user_badges). Each person orders their
// own and hides any but their staff rank (badge_prefs). Bots, Nexa's own
// accounts and deleted accounts have none.
const (
	BadgeStaff        = "staff"         // a staff rank; drawn from the account's flags
	BadgePlan         = "plan"          // Supporter or Pro; drawn from the account's plan
	BadgeOG           = "og"            // the account was made before OGCutoff
	BadgeYears        = "years"         // whole years on Nexa, from one
	BadgeBugHunter    = "bug_hunter"    // staff resolved one of their bug reports
	BadgeAppDeveloper = "app_developer" // one of their apps' bots is in AppDeveloperServers servers they do not own
	BadgeGiftGiver    = "gift_giver"    // they paid for a plan someone else got; Level by GiftTiers
	BadgeContributor  = "contributor"   // given by staff to people whose code Nexa merged
	EventBadgePrefix  = "event:"        // given by staff: "event:<badge_defs id>"
)

// OGCutoff is the start of 2026-11-02 in Alaska (UTC-9): accounts made
// before it, the day badges arrived, are OG.
const OGCutoff int64 = 1793610000000

// AppDeveloperServers is how many servers, not the developer's own, one of
// their bots must be in: a test app in their own server does not count.
const AppDeveloperServers = 5

// GiftTiers are the paid gifts Gift Giver's bronze, silver and gold need.
var GiftTiers = [3]int{1, 5, 20}

// defaultBadgeOrder is where a badge goes when its holder has not placed it.
var defaultBadgeOrder = []string{BadgeStaff, BadgePlan, BadgeOG, BadgeYears, BadgeContributor, BadgeBugHunter, BadgeAppDeveloper, BadgeGiftGiver}

// Badge is one badge on a profile. Staff and plan carry nothing more: the
// clients draw them from the account itself.
type Badge struct {
	ID string `json:"id"`
	// Level is Gift Giver's tier (1 bronze, 2 silver, 3 gold) or the years on Nexa.
	Level int `json:"level,omitempty"`
	// Name, Icon (a Lucide icon's name) and Color are an event badge's.
	Name  string `json:"name,omitempty"`
	Icon  string `json:"icon,omitempty"`
	Color *int   `json:"color,omitempty"`
	// Hidden is set only in a person's own list.
	Hidden bool `json:"hidden,omitempty"`
}

// BadgeDef is a badge staff made for an event.
type BadgeDef struct {
	ID        ids.ID `json:"id"`
	Name      string `json:"name"`
	Icon      string `json:"icon"`
	Color     int    `json:"color"`
	CreatedBy ids.ID `json:"created_by"`
	CreatedAt int64  `json:"created_at"`
	Holders   int    `json:"holders"`
}

// Limits on what staff make and what a person's preferences hold.
const (
	MaxBadgeName   = 32
	maxBadgePrefs  = 128
	maxBadgeIDText = 40
)

var badgeIconRE = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)

// ValidBadgeIcon reports whether an icon is shaped like a Lucide icon's name.
// Clients draw a plain award for a name they do not know.
func ValidBadgeIcon(icon string) bool { return len(icon) <= 48 && badgeIconRE.MatchString(icon) }

// IsGrantableBadge reports whether staff can give a badge id by hand.
func IsGrantableBadge(id string) bool {
	if id == BadgeContributor {
		return true
	}
	n, ok := strings.CutPrefix(id, EventBadgePrefix)
	if !ok {
		return false
	}
	_, err := strconv.ParseInt(n, 10, 64)
	return err == nil
}

// validBadgeID reports whether a preference can name a badge id at all.
func validBadgeID(id string) bool {
	if len(id) == 0 || len(id) > maxBadgeIDText {
		return false
	}
	for _, d := range defaultBadgeOrder {
		if id == d {
			return true
		}
	}
	return IsGrantableBadge(id)
}

// yearsSince counts the whole years from a time in milliseconds to now.
func yearsSince(createdMs int64, now time.Time) int {
	start := time.UnixMilli(createdMs).UTC()
	n := now.UTC().Year() - start.Year()
	if n > 0 && start.AddDate(n, 0, 0).After(now.UTC()) {
		n--
	}
	if n < 0 {
		return 0
	}
	return n
}

// earnedBadges works out every badge an account holds, in the default order.
func (s *Store) earnedBadges(ctx context.Context, u *User, now time.Time) ([]Badge, error) {
	if u == nil || u.Flags&(NotPeople|FlagBot|FlagDeleted) != 0 {
		return nil, nil
	}
	var out []Badge
	if u.Flags&StaffFlags != 0 {
		out = append(out, Badge{ID: BadgeStaff})
	}
	if u.Plan == "supporter" || u.Plan == "pro" {
		out = append(out, Badge{ID: BadgePlan})
	}
	if u.CreatedAt < OGCutoff {
		out = append(out, Badge{ID: BadgeOG})
	}
	if y := yearsSince(u.CreatedAt, now); y >= 1 {
		out = append(out, Badge{ID: BadgeYears, Level: y})
	}
	granted, err := s.grantedBadges(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	if granted[BadgeContributor] {
		out = append(out, Badge{ID: BadgeContributor})
	}
	var one int
	switch err := s.db.R.QueryRowContext(ctx, `SELECT 1 FROM bug_reports WHERE user_id = ? AND status = ? LIMIT 1`, int64(u.ID), BugResolved).Scan(&one); {
	case err == nil:
		out = append(out, Badge{ID: BadgeBugHunter})
	case !errors.Is(err, sql.ErrNoRows):
		return nil, err
	}
	var servers int
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(DISTINCT m.server_id) FROM applications a JOIN server_members m ON m.user_id = a.id JOIN servers v ON v.id = m.server_id WHERE a.owner_id = ? AND v.owner_id != ?`, int64(u.ID), int64(u.ID)).Scan(&servers); err != nil {
		return nil, err
	}
	if servers >= AppDeveloperServers {
		out = append(out, Badge{ID: BadgeAppDeveloper})
	}
	var gifts int
	if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM plan_gifts WHERE from_id = ? AND to_id != ?`, int64(u.ID), int64(u.ID)).Scan(&gifts); err != nil {
		return nil, err
	}
	if level := giftLevel(gifts); level > 0 {
		out = append(out, Badge{ID: BadgeGiftGiver, Level: level})
	}
	// event badges, oldest given first
	var events []ids.ID
	for id := range granted {
		if n, ok := strings.CutPrefix(id, EventBadgePrefix); ok {
			if v, err := strconv.ParseInt(n, 10, 64); err == nil {
				events = append(events, ids.ID(v))
			}
		}
	}
	if len(events) > 0 {
		defs, err := s.badgeDefsByID(ctx, events)
		if err != nil {
			return nil, err
		}
		sort.Slice(events, func(i, j int) bool { return events[i] < events[j] })
		for _, id := range events {
			if d := defs[id]; d != nil {
				color := d.Color
				out = append(out, Badge{ID: EventBadgePrefix + id.String(), Name: d.Name, Icon: d.Icon, Color: &color})
			}
		}
	}
	return out, nil
}

func giftLevel(gifts int) int {
	level := 0
	for i, need := range GiftTiers {
		if gifts >= need {
			level = i + 1
		}
	}
	return level
}

// grantedBadges is the set of badges staff gave an account.
func (s *Store) grantedBadges(ctx context.Context, uid ids.ID) (map[string]bool, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT badge FROM user_badges WHERE user_id = ?`, int64(uid))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var b string
		if err := rows.Scan(&b); err != nil {
			return nil, err
		}
		out[b] = true
	}
	return out, rows.Err()
}

// GrantedBadges lists the badges staff gave an account, sorted.
func (s *Store) GrantedBadges(ctx context.Context, uid ids.ID) ([]string, error) {
	set, err := s.grantedBadges(ctx, uid)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(set))
	for b := range set {
		out = append(out, b)
	}
	sort.Strings(out)
	return out, nil
}

func (s *Store) badgeDefsByID(ctx context.Context, list []ids.ID) (map[ids.ID]*BadgeDef, error) {
	out := map[ids.ID]*BadgeDef{}
	if len(list) == 0 {
		return out, nil
	}
	args := make([]any, len(list))
	for i, id := range list {
		args[i] = int64(id)
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, name, icon, color, created_by, created_at FROM badge_defs WHERE id IN (?`+strings.Repeat(",?", len(list)-1)+`)`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		d, err := scanBadgeDef(rows)
		if err != nil {
			return nil, err
		}
		out[d.ID] = d
	}
	return out, rows.Err()
}

func scanBadgeDef(sc interface{ Scan(...any) error }) (*BadgeDef, error) {
	var d BadgeDef
	var id, by int64
	if err := sc.Scan(&id, &d.Name, &d.Icon, &d.Color, &by, &d.CreatedAt); err != nil {
		return nil, err
	}
	d.ID, d.CreatedBy = ids.ID(id), ids.ID(by)
	return &d, nil
}

// BadgePrefs is the order someone put their badges in and the ones they hid.
type BadgePrefs struct {
	Order  []string `json:"order"`
	Hidden []string `json:"hidden"`
}

func (s *Store) badgePrefs(ctx context.Context, uid ids.ID) (BadgePrefs, error) {
	var shown, hidden string
	p := BadgePrefs{Order: []string{}, Hidden: []string{}}
	err := s.db.R.QueryRowContext(ctx, `SELECT shown, hidden FROM badge_prefs WHERE user_id = ?`, int64(uid)).Scan(&shown, &hidden)
	if errors.Is(err, sql.ErrNoRows) {
		return p, nil
	}
	if err != nil {
		return p, err
	}
	_ = json.Unmarshal([]byte(shown), &p.Order)
	_ = json.Unmarshal([]byte(hidden), &p.Hidden)
	return p, nil
}

// arrange puts badges in their holder's order (the ones they placed first,
// the rest after in the default order) and marks the ones they hid. A staff
// rank is never hidden.
func arrange(list []Badge, p BadgePrefs) []Badge {
	at := map[string]int{}
	for i, id := range p.Order {
		if _, seen := at[id]; !seen {
			at[id] = i
		}
	}
	hidden := map[string]bool{}
	for _, id := range p.Hidden {
		hidden[id] = id != BadgeStaff
	}
	out := make([]Badge, len(list))
	copy(out, list)
	sort.SliceStable(out, func(i, j int) bool {
		a, aok := at[out[i].ID]
		b, bok := at[out[j].ID]
		switch {
		case aok && bok:
			return a < b
		case aok != bok:
			return aok
		}
		return false
	})
	for i := range out {
		out[i].Hidden = hidden[out[i].ID]
	}
	return out
}

// ProfileBadges is what everyone sees on an account's profile: its badges in
// its order, without the ones it hid.
func (s *Store) ProfileBadges(ctx context.Context, u *User) ([]Badge, error) {
	list, err := s.MyBadges(ctx, u)
	if err != nil {
		return nil, err
	}
	out := []Badge{}
	for _, b := range list {
		if !b.Hidden {
			out = append(out, b)
		}
	}
	return out, nil
}

// MyBadges is an account's own view of its badges: every one, in its order,
// the hidden ones marked.
func (s *Store) MyBadges(ctx context.Context, u *User) ([]Badge, error) {
	list, err := s.earnedBadges(ctx, u, time.Now())
	if err != nil || len(list) == 0 {
		return []Badge{}, err
	}
	p, err := s.badgePrefs(ctx, u.ID)
	if err != nil {
		return nil, err
	}
	return arrange(list, p), nil
}

// SetBadgePrefs keeps the order and hidden set someone chose. Unknown ids are
// dropped; ids of badges they do not hold yet are kept, so a badge staff take
// away and give back keeps its place.
func (s *Store) SetBadgePrefs(ctx context.Context, uid ids.ID, p BadgePrefs) error {
	clean := func(list []string) []string {
		out, seen := []string{}, map[string]bool{}
		for _, id := range list {
			if validBadgeID(id) && !seen[id] && len(out) < maxBadgePrefs {
				seen[id] = true
				out = append(out, id)
			}
		}
		return out
	}
	order, err := json.Marshal(clean(p.Order))
	if err != nil {
		return err
	}
	hidden := []string{}
	for _, id := range clean(p.Hidden) {
		if id != BadgeStaff {
			hidden = append(hidden, id)
		}
	}
	hid, err := json.Marshal(hidden)
	if err != nil {
		return err
	}
	return s.Tx(ctx, func(tx *db.Tx) error {
		if _, err := tx.ExecContext(ctx, `DELETE FROM badge_prefs WHERE user_id = ?`, int64(uid)); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `INSERT INTO badge_prefs (user_id, shown, hidden) VALUES (?, ?, ?)`, int64(uid), string(order), string(hid))
		return err
	})
}

// --- what staff do ----------------------------------------------------------

// BadgeDefs lists the event badges, newest first, with how many hold each.
func (s *Store) BadgeDefs(ctx context.Context) ([]*BadgeDef, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id, name, icon, color, created_by, created_at FROM badge_defs ORDER BY id DESC`)
	if err != nil {
		return nil, err
	}
	out := []*BadgeDef{}
	for rows.Next() {
		d, err := scanBadgeDef(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, d)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	counts, err := s.badgeHolders(ctx)
	if err != nil {
		return nil, err
	}
	for _, d := range out {
		d.Holders = counts[EventBadgePrefix+d.ID.String()]
	}
	return out, nil
}

// badgeHolders counts the accounts holding each badge staff give.
func (s *Store) badgeHolders(ctx context.Context) (map[string]int, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT badge, count(*) FROM user_badges GROUP BY badge`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var b string
		var n int
		if err := rows.Scan(&b, &n); err != nil {
			return nil, err
		}
		out[b] = n
	}
	return out, rows.Err()
}

// ContributorCount is how many accounts hold Contributor.
func (s *Store) ContributorCount(ctx context.Context) (int, error) {
	counts, err := s.badgeHolders(ctx)
	return counts[BadgeContributor], err
}

// BadgeDef returns one event badge.
func (s *Store) BadgeDef(ctx context.Context, id ids.ID) (*BadgeDef, error) {
	d, err := scanBadgeDef(s.db.R.QueryRowContext(ctx, `SELECT id, name, icon, color, created_by, created_at FROM badge_defs WHERE id = ?`, int64(id)))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	return d, err
}

// CreateBadgeDef makes an event badge.
func (s *Store) CreateBadgeDef(ctx context.Context, d *BadgeDef) error {
	d.ID, d.CreatedAt = ids.New(), nowMs()
	_, err := s.db.W.ExecContext(ctx, `INSERT INTO badge_defs (id, name, icon, color, created_by, created_at) VALUES (?,?,?,?,?,?)`, int64(d.ID), d.Name, d.Icon, d.Color, int64(d.CreatedBy), d.CreatedAt)
	return err
}

// UpdateBadgeDef changes an event badge's name, icon and colour.
func (s *Store) UpdateBadgeDef(ctx context.Context, d *BadgeDef) error {
	res, err := s.db.W.ExecContext(ctx, `UPDATE badge_defs SET name = ?, icon = ?, color = ? WHERE id = ?`, d.Name, d.Icon, d.Color, int64(d.ID))
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteBadgeDef deletes an event badge and takes it from everyone holding it.
func (s *Store) DeleteBadgeDef(ctx context.Context, id ids.ID) error {
	return s.Tx(ctx, func(tx *db.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM badge_defs WHERE id = ?`, int64(id))
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return ErrNotFound
		}
		_, err = tx.ExecContext(ctx, `DELETE FROM user_badges WHERE badge = ?`, EventBadgePrefix+id.String())
		return err
	})
}

// SetGrantedBadges makes the badges staff gave an account exactly these,
// answering what was added and what was taken. Event badges that no longer
// exist are refused with ErrNotFound.
func (s *Store) SetGrantedBadges(ctx context.Context, uid, by ids.ID, want []string) (added, removed []string, err error) {
	err = s.Tx(ctx, func(tx *db.Tx) error {
		added, removed = nil, nil
		wanted := map[string]bool{}
		for _, b := range want {
			if n, ok := strings.CutPrefix(b, EventBadgePrefix); ok {
				v, err := strconv.ParseInt(n, 10, 64)
				if err != nil {
					return ErrNotFound
				}
				var one int
				if err := tx.QueryRowContext(ctx, `SELECT 1 FROM badge_defs WHERE id = ?`, v).Scan(&one); err != nil {
					if errors.Is(err, sql.ErrNoRows) {
						return ErrNotFound
					}
					return err
				}
			}
			wanted[b] = true
		}
		rows, err := tx.QueryContext(ctx, `SELECT badge FROM user_badges WHERE user_id = ?`, int64(uid))
		if err != nil {
			return err
		}
		have := map[string]bool{}
		for rows.Next() {
			var b string
			if err := rows.Scan(&b); err != nil {
				rows.Close()
				return err
			}
			have[b] = true
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for b := range have {
			if !wanted[b] {
				if _, err := tx.ExecContext(ctx, `DELETE FROM user_badges WHERE user_id = ? AND badge = ?`, int64(uid), b); err != nil {
					return err
				}
				removed = append(removed, b)
			}
		}
		now := nowMs()
		for b := range wanted {
			if !have[b] {
				if _, err := tx.ExecContext(ctx, `INSERT INTO user_badges (user_id, badge, granted_by, granted_at) VALUES (?,?,?,?)`, int64(uid), b, int64(by), now); err != nil {
					return err
				}
				added = append(added, b)
			}
		}
		sort.Strings(added)
		sort.Strings(removed)
		return nil
	})
	return added, removed, err
}
