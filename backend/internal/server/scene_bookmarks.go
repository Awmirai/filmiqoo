package server

import (
    "encoding/json"
    "fmt"
    "net/http"
    "strings"
    "time"

    "github.com/go-chi/chi/v5"
)

type sceneBookmarkPayload struct {
    PositionMs int64  `json:"positionMs"`
    Note       string `json:"note"`
    Tag        string `json:"tag"`
}

func (s *Server) sceneBookmarks(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    rows,err:=s.db.Query(r.Context(),`
        SELECT sb.id::text,sb.media_version_id::text,sb.position_ms,sb.note,sb.tag,
               sb.created_at,sb.updated_at,
               mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,
               mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
               e.name,sn.season_number,e.episode_number,e.still_url,
               mv.quality_label
          FROM scene_bookmarks sb
          JOIN media_versions mv ON mv.id=sb.media_version_id
          LEFT JOIN episodes e ON e.id=mv.episode_id
          LEFT JOIN seasons sn ON sn.id=e.season_id
          JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
         WHERE sb.user_id=$1
         ORDER BY sb.updated_at DESC
         LIMIT 500
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        item,err:=scanSceneBookmark(rows)
        if err==nil { items=append(items,item) }
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) playbackSceneBookmarks(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    versionID:=chi.URLParam(r,"versionID")

    rows,err:=s.db.Query(r.Context(),`
        SELECT sb.id::text,sb.media_version_id::text,sb.position_ms,sb.note,sb.tag,
               sb.created_at,sb.updated_at,
               mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,
               mt.poster_url,mt.backdrop_url,mt.year,mt.rating,
               e.name,sn.season_number,e.episode_number,e.still_url,
               mv.quality_label
          FROM scene_bookmarks sb
          JOIN media_versions mv ON mv.id=sb.media_version_id
          LEFT JOIN episodes e ON e.id=mv.episode_id
          LEFT JOIN seasons sn ON sn.id=e.season_id
          JOIN media_titles mt ON mt.id=COALESCE(mv.media_title_id,sn.media_title_id)
         WHERE sb.user_id=$1 AND sb.media_version_id=$2
         ORDER BY sb.position_ms ASC,sb.created_at ASC
         LIMIT 300
    `,userID,versionID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        item,err:=scanSceneBookmark(rows)
        if err==nil { items=append(items,item) }
    }
    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

type sceneBookmarkScanner interface {
    Scan(dest ...any) error
}

func scanSceneBookmark(row sceneBookmarkScanner) (map[string]any,error) {
    var (
        id,versionID,note,tag,mediaID,kind,title,originalTitle,poster,backdrop string
        position int64
        created,updated time.Time
        tmdbID *int64
        year int
        rating *float64
        episodeName,stillURL,quality *string
        seasonNumber,episodeNumber *int
    )

    err:=row.Scan(
        &id,&versionID,&position,&note,&tag,&created,&updated,
        &mediaID,&tmdbID,&kind,&title,&originalTitle,
        &poster,&backdrop,&year,&rating,
        &episodeName,&seasonNumber,&episodeNumber,&stillURL,
        &quality,
    )
    if err!=nil { return nil,err }

    displayTitle:=title
    subtitle:=""
    imageURL:=poster

    if episodeNumber!=nil && seasonNumber!=nil {
        if episodeName!=nil && strings.TrimSpace(*episodeName)!="" {
            displayTitle=*episodeName
        }
        subtitle=fmt.Sprintf("S%02dE%02d",*seasonNumber,*episodeNumber)
        if quality!=nil && strings.TrimSpace(*quality)!="" {
            subtitle+=" • "+strings.TrimSpace(*quality)
        }
        if stillURL!=nil && strings.TrimSpace(*stillURL)!="" {
            imageURL=strings.TrimSpace(*stillURL)
        }
    } else {
        if year>0 { subtitle=fmt.Sprintf("%d",year) }
        if quality!=nil && strings.TrimSpace(*quality)!="" {
            if subtitle!="" { subtitle+=" • " }
            subtitle+=strings.TrimSpace(*quality)
        }
    }

    return map[string]any{
        "id":id,
        "mediaVersionId":versionID,
        "positionMs":position,
        "note":note,
        "tag":tag,
        "createdAt":created,
        "updatedAt":updated,
        "title":displayTitle,
        "subtitle":subtitle,
        "posterUrl":imageURL,
        "media":map[string]any{
            "id":mediaID,
            "tmdbId":tmdbID,
            "kind":kind,
            "title":title,
            "originalTitle":originalTitle,
            "posterUrl":poster,
            "backdropUrl":backdrop,
            "year":year,
            "rating":rating,
        },
    },nil
}

func (s *Server) createSceneBookmark(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    versionID:=chi.URLParam(r,"versionID")

    var body sceneBookmarkPayload
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.Note=strings.TrimSpace(body.Note)
    body.Tag=strings.TrimSpace(body.Tag)
    if body.PositionMs<0 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"positionMs must be positive"}); return
    }
    if len([]rune(body.Note))>1200 || len([]rune(body.Tag))>48 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"bookmark content is too long"}); return
    }

    var id string
    err:=s.db.QueryRow(r.Context(),`
        INSERT INTO scene_bookmarks (
            user_id,media_version_id,position_ms,note,tag
        )
        SELECT $2,id,$3,$4,$5
          FROM media_versions
         WHERE id=$1
        ON CONFLICT (user_id,media_version_id,position_ms)
        DO UPDATE SET
          note=EXCLUDED.note,
          tag=EXCLUDED.tag,
          updated_at=now()
        RETURNING id::text
    `,versionID,userID,body.PositionMs,body.Note,body.Tag).Scan(&id)
    if err!=nil {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"media version not found"}); return
    }

    writeJSON(w,http.StatusOK,map[string]any{"id":id})
}

func (s *Server) updateSceneBookmark(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    var body struct {
        Note string `json:"note"`
        Tag string `json:"tag"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.Note=strings.TrimSpace(body.Note)
    body.Tag=strings.TrimSpace(body.Tag)
    if len([]rune(body.Note))>1200 || len([]rune(body.Tag))>48 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"bookmark content is too long"}); return
    }

    tag,err:=s.db.Exec(r.Context(),`
        UPDATE scene_bookmarks
           SET note=$3,tag=$4,updated_at=now()
         WHERE id=$1 AND user_id=$2
    `,id,userID,body.Note,body.Tag)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    if tag.RowsAffected()==0 {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"bookmark not found"}); return
    }
    writeJSON(w,http.StatusOK,map[string]any{"updated":true})
}

func (s *Server) deleteSceneBookmark(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    id:=chi.URLParam(r,"id")

    tag,err:=s.db.Exec(r.Context(),`
        DELETE FROM scene_bookmarks WHERE id=$1 AND user_id=$2
    `,id,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"deleted":tag.RowsAffected()>0})
}
