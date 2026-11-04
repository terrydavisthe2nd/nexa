package objstore

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Cloudflare measures what each bucket holds and every request made to it,
// the numbers R2 bills from, in its GraphQL Analytics API
// (r2StorageAdaptiveGroups, r2OperationsAdaptiveGroups). They include what
// this server cannot count itself, such as a signed link an app fetches more
// than once. Reading them takes an API token with Account Analytics: Read.

// Usage is what Cloudflare measured for one bucket.
type Usage struct {
	// ClassA and ClassB are the billed requests in the span asked about.
	ClassA, ClassB int64
	// StoredBytes is the payload and metadata held at StoredAt, the latest
	// hour measured; StoredAt is zero when no hour is measured yet.
	StoredBytes int64
	StoredAt    time.Time
}

// Analytics reads measured usage for one account.
type Analytics struct {
	Base      string // https://api.cloudflare.com/client/v4, or a stand-in
	AccountID string
	Token     string
	HTTP      *http.Client
}

// NewAnalytics reads the account's numbers with token.
func NewAnalytics(accountID, token string) *Analytics {
	return &Analytics{Base: "https://api.cloudflare.com/client/v4", AccountID: accountID, Token: token, HTTP: &http.Client{Timeout: 30 * time.Second}}
}

const usageQuery = `query ($account: string!, $since: Time, $until: Time, $bucket: string) {
  viewer { accounts(filter: { accountTag: $account }) {
    ops: r2OperationsAdaptiveGroups(limit: 10000, filter: { datetime_geq: $since, datetime_leq: $until, bucketName: $bucket }) {
      sum { requests }
      dimensions { actionType }
    }
    storage: r2StorageAdaptiveGroups(limit: 1, filter: { datetime_geq: $since, datetime_leq: $until, bucketName: $bucket }, orderBy: [datetime_DESC]) {
      max { payloadSize metadataSize }
      dimensions { datetime }
    }
  } }
}`

// Usage returns the requests made to bucket from since to until, by billing
// class, and what it held at the latest hour measured in that span. Failed
// requests count too: R2 does not bill those refused for their credentials,
// but the measurement cannot tell them from the ones it does bill, so this
// errs high.
func (a *Analytics) Usage(ctx context.Context, bucket string, since, until time.Time) (Usage, error) {
	body, _ := json.Marshal(map[string]any{"query": usageQuery, "variables": map[string]any{
		"account": a.AccountID, "bucket": bucket,
		"since": since.UTC().Format(time.RFC3339), "until": until.UTC().Format(time.RFC3339),
	}})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, a.Base+"/graphql", bytes.NewReader(body))
	if err != nil {
		return Usage{}, err
	}
	req.Header.Set("Authorization", "Bearer "+a.Token)
	req.Header.Set("Content-Type", "application/json")
	res, err := a.HTTP.Do(req)
	if err != nil {
		return Usage{}, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	var parsed struct {
		Data *struct {
			Viewer struct {
				Accounts []struct {
					Ops []struct {
						Sum struct {
							Requests int64 `json:"requests"`
						} `json:"sum"`
						Dimensions struct {
							ActionType string `json:"actionType"`
						} `json:"dimensions"`
					} `json:"ops"`
					Storage []struct {
						Max struct {
							PayloadSize  int64 `json:"payloadSize"`
							MetadataSize int64 `json:"metadataSize"`
						} `json:"max"`
						Dimensions struct {
							Datetime string `json:"datetime"`
						} `json:"dimensions"`
					} `json:"storage"`
				} `json:"accounts"`
			} `json:"viewer"`
		} `json:"data"`
		Errors []struct {
			Message string `json:"message"`
		} `json:"errors"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return Usage{}, fmt.Errorf("r2 analytics: unreadable answer (HTTP %d): %w", res.StatusCode, err)
	}
	if len(parsed.Errors) > 0 {
		return Usage{}, fmt.Errorf("r2 analytics: %s", parsed.Errors[0].Message)
	}
	if res.StatusCode != http.StatusOK {
		return Usage{}, fmt.Errorf("r2 analytics: HTTP %d", res.StatusCode)
	}
	if parsed.Data == nil || len(parsed.Data.Viewer.Accounts) == 0 {
		return Usage{}, errors.New("r2 analytics: the token cannot read this account")
	}
	acct := parsed.Data.Viewer.Accounts[0]
	var u Usage
	for _, op := range acct.Ops {
		switch classOf(op.Dimensions.ActionType) {
		case ClassA:
			u.ClassA += op.Sum.Requests
		case ClassB:
			u.ClassB += op.Sum.Requests
		}
	}
	if len(acct.Storage) > 0 {
		s := acct.Storage[0]
		if at, err := time.Parse(time.RFC3339, s.Dimensions.Datetime); err == nil {
			u.StoredBytes, u.StoredAt = s.Max.PayloadSize+s.Max.MetadataSize, at
		}
	}
	return u, nil
}

// The operations as R2's pricing lists them (developers.cloudflare.com/r2/pricing,
// checked 2026-11-04).
var (
	classA = map[string]bool{"ListBuckets": true, "PutBucket": true, "ListObjects": true, "PutObject": true, "CopyObject": true,
		"CompleteMultipartUpload": true, "CreateMultipartUpload": true, "LifecycleStorageTierTransition": true, "ListMultipartUploads": true,
		"UploadPart": true, "UploadPartCopy": true, "ListParts": true, "PutBucketEncryption": true, "PutBucketCors": true,
		"PutBucketLifecycleConfiguration": true}
	classB = map[string]bool{"HeadBucket": true, "HeadObject": true, "GetObject": true, "UsageSummary": true, "GetBucketEncryption": true,
		"GetBucketLocation": true, "GetBucketCors": true, "GetBucketLifecycleConfiguration": true}
	freeOps = map[string]bool{"DeleteObject": true, "DeleteBucket": true, "AbortMultipartUpload": true}
)

// classOf is the class an operation is billed in, 0 for a free one. One the
// list does not name counts as a read when it only reads (Get, Head) and as a
// write otherwise, which errs high.
func classOf(action string) byte {
	switch {
	case classA[action]:
		return ClassA
	case classB[action]:
		return ClassB
	case freeOps[action]:
		return 0
	case strings.HasPrefix(action, "Get") || strings.HasPrefix(action, "Head"):
		return ClassB
	}
	return ClassA
}
