package catalogindex

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Awmirai/filmiqoo/backend/internal/ingest"
	"github.com/Awmirai/filmiqoo/backend/internal/tmdb"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
)

type DB interface {
	Exec(context.Context, string, ...any) (pgconn.CommandTag, error)
	Query(context.Context, string, ...any) (pgx.Rows, error)
	QueryRow(context.Context, string, ...any) pgx.Row
}

// DetectVersion reads only stored metadata; it never downloads video or contacts Telegram.
func DetectVersion(ctx context.Context, db DB, id string, apply bool) (ingest.PresentationDetection, bool, error) {
	var filename, caption, source string
	var convention ingest.PresentationConvention
	err := db.QueryRow(ctx, `SELECT v.file_name,COALESCE(i.caption,''),v.detection_source,
 COALESCE(c.generic_dub_is_persian,false),COALESCE(c.generic_sub_is_persian,false)
 FROM media_versions v LEFT JOIN telegram_ingest_items i
 ON i.telegram_chat_id=v.telegram_chat_id AND i.telegram_message_id=v.telegram_message_id
 LEFT JOIN telegram_source_conventions c ON c.telegram_chat_id=v.telegram_chat_id WHERE v.id=$1`, id).Scan(&filename, &caption, &source, &convention.GenericDubIsPersian, &convention.GenericSubIsPersian)
	if err != nil {
		return ingest.PresentationDetection{}, false, err
	}
	if source == "manual" {
		return ingest.PresentationDetection{}, false, nil
	}
	detection := ingest.DetectPresentation(filename, caption, convention)
	if apply {
		evidence, _ := json.Marshal(detection.Evidence)
		_, err = db.Exec(ctx, `UPDATE media_versions SET is_dubbed=$2,is_persian_dubbed=$3,has_persian_subtitle=$4,
   detection_source=$5,detection_confidence=$6,detection_evidence=$7,detection_version=$8
   WHERE id=$1 AND detection_source<>'manual'`, id, detection.IsDubbed, detection.IsPersianDubbed, detection.HasPersianSubtitle, detection.Source, detection.Confidence, evidence, ingest.PresentationDetectionVersion)
	}
	return detection, true, err
}

// Source facets remain unknown when metadata does not supply them.
func StoreMetadata(ctx context.Context, db DB, id string, meta tmdb.TitleMetadata) error {
	return StoreMetadataWithRefresh(ctx, db, id, meta, false)
}
func StoreMetadataWithRefresh(ctx context.Context, db DB, id string, meta tmdb.TitleMetadata, refresh bool) error {
	genres, _ := json.Marshal(meta.GenreIDs)
	names, _ := json.Marshal(meta.Genres)
	countries, _ := json.Marshal(meta.OriginCountries)
	_, err := db.Exec(ctx, `UPDATE media_titles SET genre_ids=$2,
  genres=CASE WHEN jsonb_array_length($3::jsonb)>0 THEN $3::jsonb ELSE genres END,
  origin_countries=$4,original_language=CASE WHEN $5<>'' THEN $5 ELSE original_language END,
  year=CASE WHEN $6>0 THEN $6 ELSE year END,rating=$7,popularity=$8,vote_count=$9,
  runtime_minutes=COALESCE($10,runtime_minutes),series_status=$11,series_type=$12,season_count=$13,episode_count=$14,
  metadata_indexed_at=now(),updated_at=now() WHERE id=$1 AND (metadata_indexed_at IS NULL OR $15)`,
		id, genres, names, countries, meta.OriginalLanguage, meta.Year, meta.Rating, meta.Popularity, meta.VoteCount, meta.RuntimeMinutes, meta.SeriesStatus, meta.SeriesType, meta.SeasonCount, meta.EpisodeCount, refresh)
	return err
}

type BackfillOptions struct {
	Apply           bool
	FetchTMDB       bool
	RefreshMetadata bool
	Limit           int
	AfterVersion    string
}
type BackfillReport struct {
	DryRun           bool   `json:"dryRun"`
	Scanned          int    `json:"scanned"`
	Changed          int    `json:"changed"`
	SkippedManual    int    `json:"skippedManual"`
	PersianDubbed    int    `json:"persianDubbed"`
	PersianSubtitled int    `json:"persianSubtitled"`
	MetadataIndexed  int    `json:"metadataIndexed"`
	MetadataFailures int    `json:"metadataFailures"`
	NextVersion      string `json:"nextVersion"`
}

func Backfill(ctx context.Context, db DB, metadata *tmdb.Client, options BackfillOptions) (BackfillReport, error) {
	result := BackfillReport{DryRun: !options.Apply}
	if options.FetchTMDB && (metadata == nil || !metadata.Enabled()) {
		return result, fmt.Errorf("fetch-tmdb requires configured TMDB token")
	}
	if options.Limit <= 0 {
		options.Limit = 500
	}
	if options.Limit > 10000 {
		return result, fmt.Errorf("limit must be <=10000")
	}
	rows, err := db.Query(ctx, `SELECT id::text FROM media_versions WHERE ($1='' OR id>NULLIF($1,'')::uuid) ORDER BY id LIMIT $2`, options.AfterVersion, options.Limit)
	if err != nil {
		return result, err
	}
	ids := []string{}
	for rows.Next() {
		var id string
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return result, err
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return result, err
	}
	for _, id := range ids {
		if err = ctx.Err(); err != nil {
			return result, err
		}
		detection, processed, err := DetectVersion(ctx, db, id, options.Apply)
		if err != nil {
			return result, err
		}
		result.Scanned++
		result.NextVersion = id
		if !processed {
			result.SkippedManual++
			continue
		}
		if options.Apply {
			result.Changed++
		}
		if detection.IsPersianDubbed {
			result.PersianDubbed++
		}
		if detection.HasPersianSubtitle {
			result.PersianSubtitled++
		}
	}
	if options.FetchTMDB {
		if metadata == nil || !metadata.Enabled() {
			return result, fmt.Errorf("fetch-tmdb requires configured TMDB token")
		}
		rows, err = db.Query(ctx, `SELECT id::text,kind,tmdb_id FROM media_titles WHERE tmdb_id IS NOT NULL AND tmdb_id>0 AND (metadata_indexed_at IS NULL OR $2) ORDER BY metadata_indexed_at NULLS FIRST,id LIMIT $1`, options.Limit, options.RefreshMetadata)
		if err != nil {
			return result, err
		}
		type target struct {
			id, kind string
			tmdbID   int64
		}
		targets := []target{}
		for rows.Next() {
			var row target
			if err = rows.Scan(&row.id, &row.kind, &row.tmdbID); err != nil {
				rows.Close()
				return result, err
			}
			targets = append(targets, row)
		}
		err = rows.Err()
		rows.Close()
		if err != nil {
			return result, err
		}
		for _, target := range targets {
			meta, fetchErr := metadata.Metadata(ctx, target.kind, target.tmdbID)
			if ctx.Err() != nil {
				return result, ctx.Err()
			}
			if fetchErr != nil {
				result.MetadataFailures++
				continue
			}
			if options.Apply {
				if err = StoreMetadataWithRefresh(ctx, db, target.id, meta, options.RefreshMetadata); err != nil {
					return result, err
				}
			}
			result.MetadataIndexed++
		}
	}
	return result, nil
}
