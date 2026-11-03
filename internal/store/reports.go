package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"strconv"
	"strings"

	"evilion.xyz/chat/internal/db"
	"evilion.xyz/chat/internal/ids"
)

const ReportBlobKind = "report"

var ErrReportConflict = errors.New("report changed")

type SafetyReport struct {
	RequestID   string          `json:"-"`
	ID          ids.ID          `json:"id"`
	ReporterID  ids.ID          `json:"reporter_id"`
	TargetKind  string          `json:"target_kind"`
	TargetID    ids.ID          `json:"target_id"`
	SubjectID   ids.ID          `json:"subject_id"`
	ServerID    ids.ID          `json:"server_id"`
	Part        string          `json:"part"`
	CustomPart  string          `json:"custom_part"`
	Reason      string          `json:"reason"`
	Description string          `json:"description"`
	Excerpt     string          `json:"excerpt"`
	Snapshot    json.RawMessage `json:"snapshot"`
	Status      string          `json:"status"`
	AssignedTo  ids.ID          `json:"assigned_to"`
	Version     int             `json:"version"`
	CreatedAt   int64           `json:"created_at"`
	UpdatedAt   int64           `json:"updated_at"`
	// Outcome is what staff told the reporter on closing it ("" for the outcome
	// alone); OutcomeAt when (0 while nobody has been told).
	Outcome   string `json:"outcome"`
	OutcomeAt int64  `json:"outcome_at"`
	// Duplicates is how many open or in-review reports name the same item,
	// this one included; only the staff queue fills it in.
	Duplicates int `json:"duplicates,omitempty"`
}

type SafetyReportEvent struct {
	ID         ids.ID `json:"id"`
	ReportID   ids.ID `json:"report_id"`
	ActorID    ids.ID `json:"actor_id"`
	Status     string `json:"status"`
	AssignedTo ids.ID `json:"assigned_to"`
	Note       string `json:"note"`
	CreatedAt  int64  `json:"created_at"`
}

const reportCols = `id, request_id, reporter_id, target_kind, target_id, subject_id, server_id, part, custom_part, reason, description, excerpt, snapshot, status, assigned_to, version, created_at, updated_at, outcome, outcome_at`

func scanSafetyReport(row rowScanner) (*SafetyReport, error) {
	var v SafetyReport
	var snap string
	err := row.Scan(&v.ID, &v.RequestID, &v.ReporterID, &v.TargetKind, &v.TargetID, &v.SubjectID, &v.ServerID, &v.Part, &v.CustomPart, &v.Reason, &v.Description, &v.Excerpt, &snap, &v.Status, &v.AssignedTo, &v.Version, &v.CreatedAt, &v.UpdatedAt, &v.Outcome, &v.OutcomeAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	v.Snapshot = json.RawMessage(snap)
	return &v, nil
}
func ValidReportStatus(s string) bool {
	return s == "open" || s == "in_review" || s == "actioned" || s == "dismissed"
}

func (s *Store) CreateSafetyReport(ctx context.Context, v *SafetyReport, files []BugAttachment) error {
	v.ID = ids.New()
	v.CreatedAt = nowMs()
	v.UpdatedAt = v.CreatedAt
	v.Version = 1
	v.Status = "open"
	return s.Tx(ctx, func(tx *db.Tx) error {
		if err := tx.Serialise(ctx, "report-request:"+v.ReporterID.String()); err != nil {
			return err
		}
		existing, err := scanSafetyReport(tx.QueryRowContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE reporter_id=? AND request_id=?`, int64(v.ReporterID), v.RequestID))
		if err == nil {
			*v = *existing
			return nil
		}
		if !errors.Is(err, ErrNotFound) {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO safety_reports (`+reportCols+`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`, int64(v.ID), v.RequestID, int64(v.ReporterID), v.TargetKind, int64(v.TargetID), int64(v.SubjectID), int64(v.ServerID), v.Part, v.CustomPart, v.Reason, v.Description, v.Excerpt, string(v.Snapshot), v.Status, int64(v.AssignedTo), v.Version, v.CreatedAt, v.UpdatedAt, "", int64(0)); err != nil {
			return err
		}

		var snapshot any
		if err := json.Unmarshal(v.Snapshot, &snapshot); err != nil {
			return err
		}
		var privateFiles struct {
			Files []struct {
				ID ids.ID `json:"blob_id"`
			} `json:"_message_files"`
		}
		_ = json.Unmarshal(v.Snapshot, &privateFiles)
		requiredFiles := map[ids.ID]bool{}
		for _, f := range privateFiles.Files {
			requiredFiles[f.ID] = true
		}
		blobs := map[ids.ID]bool{}
		var collect func(any)
		collect = func(value any) {
			switch x := value.(type) {
			case map[string]any:
				for key, child := range x {
					if key == "message_content" {
						continue
					}
					if key == "avatar_id" || key == "banner_id" || key == "icon_id" || key == "blob_id" || key == "cover_blob_id" {
						if raw, ok := child.(string); ok {
							if id, err := ids.Parse(raw); err == nil {
								blobs[id] = true
							}
						}
					}
					collect(child)
				}
			case []any:
				for _, child := range x {
					collect(child)
				}
			}
		}
		collect(snapshot)
		for blob := range blobs {
			res, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count=ref_count+1 WHERE id=? AND ((encrypted=0 AND kind IN ('avatar','banner','icon','emoji','sticker','tag','widget','typing')) OR (kind='attachment' AND ?='message'))`, int64(blob), v.TargetKind)
			if err != nil {
				return err
			}
			if n, _ := res.RowsAffected(); n == 0 && requiredFiles[blob] {
				return ErrBugAttachment
			}
			if n, _ := res.RowsAffected(); n == 1 {
				if _, err := tx.ExecContext(ctx, `INSERT INTO safety_report_snapshot_blobs (report_id,blob_id) VALUES (?,?)`, int64(v.ID), int64(blob)); err != nil {
					return err
				}
			}
		}
		for _, f := range files {
			res, err := tx.ExecContext(ctx, `UPDATE blobs SET ref_count=ref_count+1 WHERE id=? AND kind=? AND owner_id=? AND ref_count=0`, int64(f.BlobID), ReportBlobKind, int64(v.ReporterID))
			if err != nil {
				return err
			}
			if n, _ := res.RowsAffected(); n != 1 {
				return ErrBugAttachment
			}
			if _, err := tx.ExecContext(ctx, `INSERT INTO safety_report_files (report_id,blob_id,name) VALUES (?,?,?)`, int64(v.ID), int64(f.BlobID), f.Name); err != nil {
				return err
			}
		}
		return nil
	})
}
func (s *Store) SafetyReportByID(ctx context.Context, id ids.ID) (*SafetyReport, error) {
	return scanSafetyReport(s.db.R.QueryRowContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE id=?`, int64(id)))
}

type ReportFilter struct {
	Status, Kind, Query, Reason string
	Before                      ids.ID
	// After with Oldest pages the queue from the oldest report forward.
	After    ids.ID
	Oldest   bool
	Reporter ids.ID
	Limit    int
	Assigned ids.ID
	// Unassigned keeps only reports nobody has claimed.
	Unassigned bool
	// Duplicates counts the active reports about each listed item.
	Duplicates bool
}

// reportActive is the status condition for a report that still waits on staff.
const reportActive = `status IN ('open','in_review')`

func (s *Store) SafetyReports(ctx context.Context, f ReportFilter) ([]*SafetyReport, error) {
	where := []string{"1=1"}
	args := []any{}
	if f.Status == "active" {
		where = append(where, reportActive)
	} else if f.Status == "closed" {
		where = append(where, `status IN ('actioned','dismissed')`)
	} else if f.Status != "" {
		where = append(where, `status=?`)
		args = append(args, f.Status)
	}
	if f.Kind != "" {
		where = append(where, `target_kind=?`)
		args = append(args, f.Kind)
	}
	if f.Reason != "" {
		where = append(where, `reason=?`)
		args = append(args, f.Reason)
	}
	if f.Before != 0 {
		where = append(where, `id<?`)
		args = append(args, int64(f.Before))
	}
	if f.After != 0 {
		where = append(where, `id>?`)
		args = append(args, int64(f.After))
	}
	if f.Reporter != 0 {
		where = append(where, `reporter_id=?`)
		args = append(args, int64(f.Reporter))
	}
	if f.Assigned != 0 {
		where = append(where, `assigned_to=?`)
		args = append(args, int64(f.Assigned))
	}
	if f.Unassigned {
		where = append(where, `assigned_to=0`)
	}
	if f.Query != "" {
		// Words in the description or the reporter's own label, an exact report,
		// item or account id, or the username of the reported account or reporter.
		raw := strings.TrimPrefix(strings.TrimSpace(f.Query), "@")
		q := "%" + strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(strings.ToLower(f.Query)) + "%"
		where = append(where, `(lower(description) LIKE ? ESCAPE '\' OR lower(custom_part) LIKE ? ESCAPE '\' OR CAST(id AS TEXT)=? OR CAST(target_id AS TEXT)=? OR CAST(subject_id AS TEXT)=? OR CAST(reporter_id AS TEXT)=? OR subject_id IN (SELECT id FROM users WHERE username=?) OR reporter_id IN (SELECT id FROM users WHERE username=?))`)
		args = append(args, q, q, raw, raw, raw, raw, strings.ToLower(raw), strings.ToLower(raw))
	}
	if f.Limit <= 0 || f.Limit > 101 {
		f.Limit = 51
	}
	args = append(args, f.Limit)
	order := "DESC"
	if f.Oldest {
		order = "ASC"
	}
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE `+strings.Join(where, " AND ")+` ORDER BY id `+order+` LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*SafetyReport{}
	for rows.Next() {
		v, err := scanSafetyReport(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if f.Duplicates {
		if err := s.countDuplicates(ctx, out); err != nil {
			return nil, err
		}
	}
	return out, nil
}

// FillReportDuplicates fills in Duplicates for reports loaded one at a time.
func (s *Store) FillReportDuplicates(ctx context.Context, list []*SafetyReport) error {
	return s.countDuplicates(ctx, list)
}

// countDuplicates fills in how many active reports name each report's item.
// Reports about "something else" name no item and count only themselves.
func (s *Store) countDuplicates(ctx context.Context, list []*SafetyReport) error {
	type item struct {
		kind string
		id   ids.ID
	}
	counts := map[item]int{}
	for _, v := range list {
		if v.TargetID == 0 {
			v.Duplicates = 1
			continue
		}
		key := item{v.TargetKind, v.TargetID}
		if _, done := counts[key]; !done {
			var n int
			if err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM safety_reports WHERE target_kind=? AND target_id=? AND `+reportActive, v.TargetKind, int64(v.TargetID)).Scan(&n); err != nil {
				return err
			}
			counts[key] = n
		}
		v.Duplicates = max(counts[key], 1)
	}
	return nil
}

// RelatedSafetyReports are the other reports about the same item or the same
// responsible account, newest first: what staff weigh a report against.
func (s *Store) RelatedSafetyReports(ctx context.Context, v *SafetyReport, limit int) ([]*SafetyReport, error) {
	cond := []string{}
	args := []any{int64(v.ID)}
	if v.TargetID != 0 {
		cond = append(cond, `(target_kind=? AND target_id=?)`)
		args = append(args, v.TargetKind, int64(v.TargetID))
	}
	if v.SubjectID != 0 {
		cond = append(cond, `subject_id=?`)
		args = append(args, int64(v.SubjectID))
	}
	if len(cond) == 0 {
		return []*SafetyReport{}, nil
	}
	args = append(args, limit)
	rows, err := s.db.R.QueryContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE id<>? AND (`+strings.Join(cond, " OR ")+`) ORDER BY id DESC LIMIT ?`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []*SafetyReport{}
	for rows.Next() {
		r, err := scanSafetyReport(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// SafetyReportStatsFor counts one person's reports by status, so staff can see
// how often a reporter's reports led somewhere.
func (s *Store) SafetyReportStatsFor(ctx context.Context, reporter ids.ID) (map[string]int, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT status,count(*) FROM safety_reports WHERE reporter_id=? GROUP BY status`, int64(reporter))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{"open": 0, "in_review": 0, "actioned": 0, "dismissed": 0}
	for rows.Next() {
		var k string
		var n int
		if err := rows.Scan(&k, &n); err != nil {
			return nil, err
		}
		out[k] = n
	}
	return out, rows.Err()
}

// SafetyReportsForReporter is every report someone sent, newest first, for
// their Nexa conversation (bounded: a person sends few).
func (s *Store) SafetyReportsForReporter(ctx context.Context, reporter ids.ID) ([]*SafetyReport, error) {
	return s.SafetyReports(ctx, ReportFilter{Reporter: reporter, Limit: 101})
}

// ActiveSafetyReportBy is the reporter's own report about an item that still
// waits on staff, so the form can say it was already sent.
func (s *Store) ActiveSafetyReportBy(ctx context.Context, reporter ids.ID, kind string, target ids.ID) (*SafetyReport, error) {
	return scanSafetyReport(s.db.R.QueryRowContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE reporter_id=? AND target_kind=? AND target_id=? AND `+reportActive+` ORDER BY id DESC LIMIT 1`, int64(reporter), kind, int64(target)))
}

func (s *Store) SafetyReportCounts(ctx context.Context) (map[string]int, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT status,count(*) FROM safety_reports GROUP BY status`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{"open": 0, "in_review": 0, "actioned": 0, "dismissed": 0}
	for rows.Next() {
		var k string
		var n int
		if err := rows.Scan(&k, &n); err != nil {
			return nil, err
		}
		out[k] = n
	}
	return out, rows.Err()
}
func (s *Store) SafetyReportEvents(ctx context.Context, id ids.ID) ([]SafetyReportEvent, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT id,report_id,actor_id,status,assigned_to,note,created_at FROM safety_report_events WHERE report_id=? ORDER BY id`, int64(id))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []SafetyReportEvent{}
	for rows.Next() {
		var v SafetyReportEvent
		if err := rows.Scan(&v.ID, &v.ReportID, &v.ActorID, &v.Status, &v.AssignedTo, &v.Note, &v.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

func closingStatus(status string) bool { return status == "actioned" || status == "dismissed" }

// A compare-and-swap makes concurrent reviews fail explicitly instead of losing
// a decision. Closing a report (or changing a closed one's outcome) tells the
// reporter, with outcome as staff's message to them; reopening one takes that
// back. With related, a closing decision also closes every other active report
// about the same item, each with its own event; it returns them.
func (s *Store) ReviewSafetyReport(ctx context.Context, id, actor ids.ID, version int, status, note, outcome string, assigned ids.ID, related bool) ([]*SafetyReport, error) {
	var closed []*SafetyReport
	err := s.Tx(ctx, func(tx *db.Tx) error {
		closed = nil
		at := nowMs()
		var old string
		if err := tx.QueryRowContext(ctx, `SELECT status FROM safety_reports WHERE id=?`, int64(id)).Scan(&old); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		told := `outcome=outcome,outcome_at=outcome_at`
		args := []any{status, int64(assigned), at}
		switch {
		case closingStatus(status) && old != status:
			told = `outcome=?,outcome_at=?`
			args = append(args, outcome, at)
		case !closingStatus(status):
			told = `outcome='',outcome_at=0`
		}
		args = append(args, int64(id), version)
		res, err := tx.ExecContext(ctx, `UPDATE safety_reports SET status=?,assigned_to=?,version=version+1,updated_at=?,`+told+` WHERE id=? AND version=?`, args...)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n != 1 {
			return ErrReportConflict
		}
		if err := reportEventTx(ctx, tx, id, actor, status, assigned, note, at); err != nil {
			return err
		}
		if related && closingStatus(status) {
			if closed, err = closeRelatedReportsTx(ctx, tx, id, actor, status, note, outcome, at); err != nil {
				return err
			}
		}
		return nil
	})
	return closed, err
}

func reportEventTx(ctx context.Context, tx *db.Tx, report, actor ids.ID, status string, assigned ids.ID, note string, at int64) error {
	if _, err := tx.ExecContext(ctx, `INSERT INTO safety_report_events (id,report_id,actor_id,status,assigned_to,note,created_at) VALUES (?,?,?,?,?,?,?)`, int64(ids.New()), int64(report), int64(actor), status, int64(assigned), note, at); err != nil {
		return err
	}
	return appendModLogTx(ctx, tx, &ModLogEntry{Action: "report_review", ModeratorID: actor, Reason: note, Details: json.RawMessage(mustReportJSON(map[string]any{"report_id": report, "status": status, "assigned_to": assigned}))})
}

// closeRelatedReportsTx gives every other active report about the same item as
// report the same outcome and message to its reporter, keeping whoever had
// claimed each.
func closeRelatedReportsTx(ctx context.Context, tx *db.Tx, report, actor ids.ID, status, note, outcome string, at int64) ([]*SafetyReport, error) {
	var kind string
	var target ids.ID
	if err := tx.QueryRowContext(ctx, `SELECT target_kind,target_id FROM safety_reports WHERE id=?`, int64(report)).Scan(&kind, &target); err != nil {
		return nil, err
	}
	if target == 0 {
		return nil, nil
	}
	rows, err := tx.QueryContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE target_kind=? AND target_id=? AND id<>? AND `+reportActive, kind, int64(target), int64(report))
	if err != nil {
		return nil, err
	}
	var list []*SafetyReport
	for rows.Next() {
		v, err := scanSafetyReport(rows)
		if err != nil {
			rows.Close()
			return nil, err
		}
		list = append(list, v)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, v := range list {
		if _, err := tx.ExecContext(ctx, `UPDATE safety_reports SET status=?,version=version+1,updated_at=?,outcome=?,outcome_at=? WHERE id=?`, status, at, outcome, at, int64(v.ID)); err != nil {
			return nil, err
		}
		if err := reportEventTx(ctx, tx, v.ID, actor, status, v.AssignedTo, note, at); err != nil {
			return nil, err
		}
		v.Status, v.Version, v.UpdatedAt, v.Outcome, v.OutcomeAt = status, v.Version+1, at, outcome, at
	}
	return list, nil
}
func mustReportJSON(v any) []byte { b, _ := json.Marshal(v); return b }
func (s *Store) SafetyReportFiles(ctx context.Context, id ids.ID) ([]BugAttachment, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT b.id,f.name,b.mime,b.size,b.width,b.height FROM safety_report_files f JOIN blobs b ON b.id=f.blob_id WHERE f.report_id=? ORDER BY b.id`, int64(id))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []BugAttachment{}
	for rows.Next() {
		var f BugAttachment
		var w, h sql.NullInt64
		if err := rows.Scan(&f.BlobID, &f.Name, &f.Mime, &f.Size, &w, &h); err != nil {
			return nil, err
		}
		if w.Valid {
			x := int(w.Int64)
			f.Width = &x
		}
		if h.Valid {
			x := int(h.Int64)
			f.Height = &x
		}
		out = append(out, f)
	}
	return out, rows.Err()
}
func (s *Store) SafetyReportFileOwner(ctx context.Context, id ids.ID) (ids.ID, bool, error) {
	var owner ids.ID
	var n int
	err := s.db.R.QueryRowContext(ctx, `SELECT b.owner_id,(SELECT count(*) FROM safety_report_files f WHERE f.blob_id=b.id) FROM blobs b WHERE b.id=? AND b.kind=?`, int64(id), ReportBlobKind).Scan(&owner, &n)
	return owner, n > 0, err
}

func (s *Store) SafetyReportByRequest(ctx context.Context, reporter ids.ID, request string) (*SafetyReport, error) {
	return scanSafetyReport(s.db.R.QueryRowContext(ctx, `SELECT `+reportCols+` FROM safety_reports WHERE reporter_id=? AND request_id=?`, int64(reporter), request))
}

// RemoveReportedMessage removes exactly the message in an existing report. It
// grants no channel access and records the case, log and report event atomically,
// closing it and every other active report about the message as actioned.
func (s *Store) RemoveReportedMessage(ctx context.Context, report *SafetyReport, version int, outcome string, c *ModCase, log *ModLogEntry) (ids.ID, []*SafetyReport, error) {
	var channel ids.ID
	var closed []*SafetyReport
	err := s.Tx(ctx, func(tx *db.Tx) error {
		closed = nil
		var target, author ids.ID
		var current int
		if err := tx.QueryRowContext(ctx, `SELECT target_id,version FROM safety_reports WHERE id=? AND target_kind='message'`, int64(report.ID)).Scan(&target, &current); err != nil {
			return ErrNotFound
		}
		if current != version {
			return ErrReportConflict
		}
		if err := tx.QueryRowContext(ctx, `SELECT channel_id,author_id FROM messages WHERE id=?`, int64(target)).Scan(&channel, &author); err != nil {
			if errors.Is(err, sql.ErrNoRows) {
				return ErrNotFound
			}
			return err
		}
		if author != report.SubjectID {
			return ErrReportConflict
		}
		// Reserve this version before changing anything else. Losing the race rolls
		// the whole transaction back, including the message deletion and the case.
		res, err := tx.ExecContext(ctx, `UPDATE safety_reports SET version=version+1,updated_at=? WHERE id=? AND version=?`, nowMs(), int64(report.ID), version)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n != 1 {
			return ErrReportConflict
		}
		if err := deleteMessageTx(ctx, tx, target); err != nil {
			return err
		}
		if err := fixLastMessageTx(ctx, tx, channel); err != nil {
			return err
		}
		if err := openCaseTx(ctx, tx, c, log); err != nil {
			return err
		}
		// The message is gone, so this report and every other one about it are done.
		at := nowMs()
		note := "#" + strconv.FormatInt(c.Number, 10) + ": " + c.Reason
		if _, err := tx.ExecContext(ctx, `UPDATE safety_reports SET status='actioned',outcome=?,outcome_at=? WHERE id=?`, outcome, at, int64(report.ID)); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO safety_report_events (id,report_id,actor_id,status,assigned_to,note,created_at) VALUES (?,?,?,?,?,?,?)`, int64(ids.New()), int64(report.ID), int64(c.ModeratorID), "actioned", int64(report.AssignedTo), note, at); err != nil {
			return err
		}
		closed, err = closeRelatedReportsTx(ctx, tx, report.ID, c.ModeratorID, "actioned", note, outcome, at)
		return err
	})
	return channel, closed, err
}

func (s *Store) SafetyReportSnapshotBlob(ctx context.Context, id ids.ID) (bool, error) {
	var count int
	err := s.db.R.QueryRowContext(ctx, `SELECT count(*) FROM safety_report_snapshot_blobs WHERE blob_id=?`, int64(id)).Scan(&count)
	return count > 0, err
}

// Only attachments already validated against a reported message can have this
// retained reference. Scan the small indexed set of reports for this blob.
func (s *Store) SafetyReportAttachment(ctx context.Context, id ids.ID) (json.RawMessage, error) {
	rows, err := s.db.R.QueryContext(ctx, `SELECT r.snapshot FROM safety_report_snapshot_blobs b JOIN safety_reports r ON r.id=b.report_id WHERE b.blob_id=? AND r.target_kind='message'`, int64(id))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var snap struct {
			Files []json.RawMessage `json:"_message_files"`
		}
		if json.Unmarshal([]byte(raw), &snap) != nil {
			continue
		}
		for _, f := range snap.Files {
			var a struct {
				ID ids.ID `json:"blob_id"`
			}
			if json.Unmarshal(f, &a) == nil && a.ID == id {
				return f, nil
			}
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return nil, ErrNotFound
}
