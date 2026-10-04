package server

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/Awmirai/filmiqoo/backend/internal/ingest"
	"path/filepath"
	"strings"
	"time"
)

func (s *Server) resolveTelegramIngest(ctx context.Context, ingestID string) error {
	if s.tmdb == nil || !s.tmdb.Enabled() {
		return nil
	}

	var chatID, messageID, fileNumericID, fileSize int64
	var fileName, mimeType, streamHash, kind, title, quality, source, codec, caption string
	var season, episode, year *int

	err := s.db.QueryRow(ctx, `
		SELECT telegram_chat_id,telegram_message_id,telegram_file_numeric_id,file_size_bytes,
		       file_name,mime_type,stream_hash,parsed_kind,parsed_title,parsed_quality,
		       parsed_source,parsed_codec,parsed_season,parsed_episode,parsed_year,caption
		  FROM telegram_ingest_items
		 WHERE id=$1
	`, ingestID).Scan(
		&chatID, &messageID, &fileNumericID, &fileSize, &fileName, &mimeType, &streamHash,
		&kind, &title, &quality, &source, &codec, &season, &episode, &year, &caption,
	)
	if err != nil {
		return err
	}

	_, _ = s.db.Exec(ctx, "UPDATE telegram_ingest_items SET status='resolving',error_text='',updated_at=now() WHERE id=$1", ingestID)

	// Obtain the authoritative MTProto file ID and stream hash from FSB.
	streamMeta, err := s.tgfsbFileMetadata(ctx, messageID)
	if err != nil {
		return fmt.Errorf("resolve telegram stream metadata: %w", err)
	}

	fileNumericID = streamMeta.FileID
	fileName = streamMeta.FileName
	fileSize = streamMeta.FileSize
	mimeType = streamMeta.MimeType
	streamHash = streamMeta.Hash

	_, err = s.db.Exec(ctx, `
		UPDATE telegram_ingest_items
		   SET telegram_file_numeric_id=$2,
		       file_name=$3,
		       file_size_bytes=$4,
		       mime_type=$5,
		       stream_hash=$6,
		       updated_at=now()
		 WHERE id=$1
	`, ingestID, fileNumericID, fileName, fileSize, mimeType, streamHash)
	if err != nil {
		return fmt.Errorf("persist telegram stream metadata: %w", err)
	}

	parsed, candidates := ingest.ParseTelegramMedia(fileName, caption)
	if len(candidates) == 0 {
		return fmt.Errorf("no usable title in filename or caption")
	}
	kind, title, quality, source, codec = parsed.Kind, parsed.Title, parsed.Quality, parsed.Source, parsed.Codec
	season, episode, year = parsed.Season, parsed.Episode, parsed.Year
	_, err = s.db.Exec(ctx, `UPDATE telegram_ingest_items SET parsed_kind=$2,parsed_title=$3,
		parsed_quality=$4,parsed_source=$5,parsed_codec=$6,parsed_season=$7,parsed_episode=$8,parsed_year=$9 WHERE id=$1`,
		ingestID, kind, title, quality, source, codec, season, episode, year)
	if err != nil {
		return err
	}
	match, err := s.tmdb.Search(ctx, kind, title, year)
	for _, candidate := range candidates[1:] {
		if err == nil || ctx.Err() != nil {
			break
		}
		match, err = s.tmdb.Search(ctx, kind, candidate, year)
	}
	if err != nil {
		_, _ = s.db.Exec(ctx, "UPDATE telegram_ingest_items SET status='failed',error_text=$2,updated_at=now() WHERE id=$1", ingestID, err.Error())
		return err
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)

	genresJSON, _ := json.Marshal(match.Genres)
	var mediaTitleID string
	err = tx.QueryRow(ctx, `
		INSERT INTO media_titles (
			tmdb_id,kind,title,original_title,overview,year,poster_url,backdrop_url,rating,
			original_language,genres,audience_level,visibility,updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,'public',now())
		ON CONFLICT (tmdb_id,kind) DO UPDATE SET
			title=EXCLUDED.title,
			original_title=EXCLUDED.original_title,
			overview=CASE WHEN EXCLUDED.overview<>'' THEN EXCLUDED.overview ELSE media_titles.overview END,
			year=CASE WHEN EXCLUDED.year<>0 THEN EXCLUDED.year ELSE media_titles.year END,
			poster_url=CASE WHEN EXCLUDED.poster_url<>'' THEN EXCLUDED.poster_url ELSE media_titles.poster_url END,
			backdrop_url=CASE WHEN EXCLUDED.backdrop_url<>'' THEN EXCLUDED.backdrop_url ELSE media_titles.backdrop_url END,
			rating=EXCLUDED.rating,
			original_language=EXCLUDED.original_language,
			genres=CASE
				WHEN jsonb_array_length(EXCLUDED.genres)>0 THEN EXCLUDED.genres
				ELSE media_titles.genres
			END,
			audience_level=CASE
				WHEN EXCLUDED.audience_level<>'unrated' THEN EXCLUDED.audience_level
				ELSE media_titles.audience_level
			END,
			updated_at=now()
		RETURNING id::text
	`, match.TMDBID, match.Kind, match.Title, match.OriginalTitle, match.Overview, match.Year,
		match.PosterURL, match.BackdropURL, match.Rating, match.OriginalLanguage, genresJSON, match.AudienceLevel).Scan(&mediaTitleID)
	if err != nil {
		return err
	}

	// Every title gets a first-class community room automatically.
	_, _ = tx.Exec(ctx, `
		INSERT INTO rooms (media_title_id,name,topic,room_type,visibility)
		VALUES ($1,$2,$3,'community','public')
		ON CONFLICT DO NOTHING
	`, mediaTitleID, match.Title+" • Community", "گفت‌وگوی رسمی "+match.Title)

	var episodeID *string
	if kind == "series" || kind == "anime" {
		if season == nil || episode == nil {
			return fmt.Errorf("series ingest missing season or episode")
		}

		var seasonID string
		err = tx.QueryRow(ctx, `
			INSERT INTO seasons (media_title_id,season_number,name)
			VALUES ($1,$2,$3)
			ON CONFLICT (media_title_id,season_number)
			DO UPDATE SET name=CASE WHEN seasons.name='' THEN EXCLUDED.name ELSE seasons.name END
			RETURNING id::text
		`, mediaTitleID, *season, fmt.Sprintf("فصل %d", *season)).Scan(&seasonID)
		if err != nil {
			return err
		}

		epMeta, epErr := s.tmdb.Episode(ctx, match.TMDBID, *season, *episode)
		if epErr != nil {
			epMeta.Name = fmt.Sprintf("قسمت %d", *episode)
		}

		var epID string
		var airDate any
		if epMeta.AirDate != "" {
			airDate = epMeta.AirDate
		}
		err = tx.QueryRow(ctx, `
			INSERT INTO episodes (
				season_id,episode_number,name,overview,still_url,runtime_minutes,air_date,tmdb_episode_id
			) VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
			ON CONFLICT (season_id,episode_number) DO UPDATE SET
				name=CASE WHEN EXCLUDED.name<>'' THEN EXCLUDED.name ELSE episodes.name END,
				overview=CASE WHEN EXCLUDED.overview<>'' THEN EXCLUDED.overview ELSE episodes.overview END,
				still_url=CASE WHEN EXCLUDED.still_url<>'' THEN EXCLUDED.still_url ELSE episodes.still_url END,
				runtime_minutes=CASE WHEN EXCLUDED.runtime_minutes<>0 THEN EXCLUDED.runtime_minutes ELSE episodes.runtime_minutes END,
				air_date=COALESCE(EXCLUDED.air_date,episodes.air_date),
				tmdb_episode_id=COALESCE(EXCLUDED.tmdb_episode_id,episodes.tmdb_episode_id)
			RETURNING id::text
		`, seasonID, *episode, epMeta.Name, epMeta.Overview, epMeta.StillURL, epMeta.RuntimeMinutes, airDate,
			nullableInt64(epMeta.TMDBEpisodeID)).Scan(&epID)
		if err != nil {
			return err
		}
		episodeID = &epID

		// Episode rooms make spoiler-safe discussion possible at episode granularity.
		_, _ = tx.Exec(ctx, `
			INSERT INTO rooms (media_title_id,episode_id,name,topic,room_type,visibility)
			VALUES ($1,$2,$3,$4,'episode','public')
			ON CONFLICT DO NOTHING
		`, mediaTitleID, epID, match.Title+" • "+fmt.Sprintf("S%02dE%02d", *season, *episode),
			"بحث قسمت "+fmt.Sprintf("%d", *episode))
	}

	container := strings.TrimPrefix(strings.ToLower(filepath.Ext(fileName)), ".")
	sourceRef := fmt.Sprintf("telegram:%d:%d", chatID, messageID)
	streamReady := streamHash != ""

	// Reposts of the exact same Telegram document share the authoritative MTProto
	// file ID. Reuse that media version instead of creating duplicate choices in
	// the client. source_ref remains message-specific so playback keeps a valid
	// message reference for the original version.
	var mediaVersionID string
	duplicateFile := false
	if fileNumericID != 0 {
		_ = tx.QueryRow(ctx, `
			SELECT id::text
			  FROM media_versions
			 WHERE source_kind='telegram'
			   AND telegram_file_id=$1
			 ORDER BY created_at ASC
			 LIMIT 1
		`, fmt.Sprintf("%d", fileNumericID)).Scan(&mediaVersionID)
		duplicateFile = mediaVersionID != ""
	}

	if mediaVersionID == "" {
		if episodeID != nil {
			err = tx.QueryRow(ctx, `
				INSERT INTO media_versions (
					episode_id,source_kind,source_ref,telegram_chat_id,telegram_message_id,
					telegram_file_id,file_name,file_size_bytes,container,quality_label,video_codec,
					stream_hash,stream_ready,preferred
				) VALUES ($1,'telegram',$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,true)
				ON CONFLICT DO NOTHING
				RETURNING id::text
			`, *episodeID, sourceRef, chatID, messageID, fmt.Sprintf("%d", fileNumericID), fileName, fileSize,
				container, quality, codec, streamHash, streamReady).Scan(&mediaVersionID)
		} else {
			err = tx.QueryRow(ctx, `
				INSERT INTO media_versions (
					media_title_id,source_kind,source_ref,telegram_chat_id,telegram_message_id,
					telegram_file_id,file_name,file_size_bytes,container,quality_label,video_codec,
					stream_hash,stream_ready,preferred
				) VALUES ($1,'telegram',$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,true)
				ON CONFLICT DO NOTHING
				RETURNING id::text
			`, mediaTitleID, sourceRef, chatID, messageID, fmt.Sprintf("%d", fileNumericID), fileName, fileSize,
				container, quality, codec, streamHash, streamReady).Scan(&mediaVersionID)
		}
		if err != nil {
			// A repeated webhook may already have promoted this exact source. Reuse it.
			_ = tx.QueryRow(ctx, `
				SELECT id::text
				  FROM media_versions
				 WHERE source_kind='telegram'
				   AND (source_ref=$1 OR ($2<>'' AND telegram_file_id=$2))
				 ORDER BY (source_ref=$1) DESC, created_at ASC
				 LIMIT 1
			`, sourceRef, fmt.Sprintf("%d", fileNumericID)).Scan(&mediaVersionID)
			if mediaVersionID == "" {
				return err
			}
			duplicateFile = true
		}
	} else {
		// Keep the canonical version's original message reference. Telegram stream
		// hashes are derived from file metadata, so it remains playable.
		_, err = tx.Exec(ctx, `
			UPDATE media_versions
			   SET file_name=$2,
			       file_size_bytes=$3,
			       container=$4,
			       quality_label=$5,
			       video_codec=$6,
			       stream_hash=$7,
			       stream_ready=$8,
			       preferred=true
			 WHERE id=$1
		`, mediaVersionID, fileName, fileSize, container, quality, codec, streamHash, streamReady)
		if err != nil {
			return fmt.Errorf("refresh duplicate telegram media version: %w", err)
		}
	}

	if mediaVersionID != "" {
		// For newly-created/message-specific rows, persist all authoritative metadata.
		// A duplicate keeps the canonical row's original message reference.
		_, err = tx.Exec(ctx, `
			UPDATE media_versions
			   SET telegram_chat_id=CASE WHEN $5 THEN telegram_chat_id ELSE $2 END,
			       telegram_message_id=CASE WHEN $5 THEN telegram_message_id ELSE $3 END,
			       telegram_file_id=$4,
			       stream_hash=$6,
			       stream_ready=$7
			 WHERE id=$1
		`, mediaVersionID, chatID, messageID, fmt.Sprintf("%d", fileNumericID), duplicateFile, streamHash, streamReady)
		if err != nil {
			return fmt.Errorf("refresh telegram media version: %w", err)
		}
	}

	_, err = tx.Exec(ctx, `
		UPDATE telegram_ingest_items
		   SET status='ready',
		       resolved_media_title_id=$2,
		       resolved_episode_id=$3,
		       resolved_media_version_id=$4,
		       error_text='',
		       updated_at=now()
		 WHERE id=$1
	`, ingestID, mediaTitleID, episodeID, mediaVersionID)
	if err != nil {
		return err
	}

	if err := tx.Commit(ctx); err != nil {
		return err
	}
	return nil
}

func nullableInt64(v int64) any {
	if v == 0 {
		return nil
	}
	return v
}

func (s *Server) retryTelegramResolve(ctx context.Context, ingestID string) error {
	ctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	return s.resolveTelegramIngest(ctx, ingestID)
}
