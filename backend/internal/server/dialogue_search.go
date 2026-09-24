package server

import (
    "encoding/json"
    "net/http"
    "net/url"
    "strconv"
    "strings"

    "github.com/go-chi/chi/v5"
)

type subtitleCuePayload struct {
    Language string `json:"language"`
    StartMS int64 `json:"startMs"`
    EndMS int64 `json:"endMs"`
    Text string `json:"text"`
}

func (s *Server) indexSubtitleCues(w http.ResponseWriter,r *http.Request) {
    if !s.validIngestSecret(r.Header.Get("X-Filmiqoo-Ingest-Secret")) {
        writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid ingest secret"})
        return
    }

    var body struct {
        MediaVersionID string `json:"mediaVersionId"`
        ReplaceLanguage bool `json:"replaceLanguage"`
        Cues []subtitleCuePayload `json:"cues"`
    }
    if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
        writeError(w,http.StatusBadRequest,err); return
    }

    body.MediaVersionID=strings.TrimSpace(body.MediaVersionID)
    if body.MediaVersionID=="" || len(body.Cues)==0 {
        writeJSON(w,http.StatusBadRequest,map[string]string{
            "error":"mediaVersionId and cues are required",
        })
        return
    }
    if len(body.Cues)>20000 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"too many cues"})
        return
    }

    var exists bool
    if err:=s.db.QueryRow(r.Context(),
        "SELECT EXISTS(SELECT 1 FROM media_versions WHERE id=$1)",
        body.MediaVersionID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if !exists {
        writeJSON(w,http.StatusNotFound,map[string]string{"error":"media version not found"})
        return
    }

    tx,err:=s.db.Begin(r.Context())
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer tx.Rollback(r.Context())

    langs:=map[string]bool{}
    for _,cue:=range body.Cues {
        lang:=strings.ToLower(strings.TrimSpace(cue.Language))
        if lang=="" { lang="und" }
        langs[lang]=true
    }

    if body.ReplaceLanguage {
        for lang:=range langs {
            if _,err:=tx.Exec(r.Context(),
                "DELETE FROM subtitle_cues WHERE media_version_id=$1 AND language=$2",
                body.MediaVersionID,lang); err!=nil {
                writeError(w,http.StatusInternalServerError,err); return
            }
        }
    }

    inserted:=0
    for _,cue:=range body.Cues {
        lang:=strings.ToLower(strings.TrimSpace(cue.Language))
        if lang=="" { lang="und" }
        textValue:=strings.TrimSpace(cue.Text)
        if textValue=="" || cue.StartMS<0 || cue.EndMS<cue.StartMS {
            continue
        }
        if len([]rune(textValue))>4000 {
            textValue=string([]rune(textValue)[:4000])
        }
        tag,err:=tx.Exec(r.Context(),`
            INSERT INTO subtitle_cues (
                media_version_id,language,start_ms,end_ms,cue_text
            ) VALUES ($1,$2,$3,$4,$5)
            ON CONFLICT DO NOTHING
        `,body.MediaVersionID,lang,cue.StartMS,cue.EndMS,textValue)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        inserted+=int(tag.RowsAffected())
    }

    if err:=tx.Commit(r.Context()); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "inserted":inserted,
        "languages":len(langs),
    })
}

func (s *Server) dialogueSearch(w http.ResponseWriter,r *http.Request) {
    versionID:=chi.URLParam(r,"versionID")
    query:=strings.TrimSpace(r.URL.Query().Get("q"))
    language:=strings.ToLower(strings.TrimSpace(r.URL.Query().Get("lang")))
    if len([]rune(query))<2 {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"query must contain at least 2 characters"})
        return
    }
    if len([]rune(query))>160 {
        query=string([]rune(query)[:160])
    }

    limit:=40
    if raw:=r.URL.Query().Get("limit"); raw!="" {
        if v,err:=strconv.Atoi(raw); err==nil {
            limit=v
        }
    }
    if limit<1 { limit=1 }
    if limit>100 { limit=100 }

    rows,err:=s.db.Query(r.Context(),`
        SELECT sc.language,sc.start_ms,sc.end_ms,sc.cue_text,
               COALESCE(
                 ts_rank(
                   to_tsvector('simple',sc.cue_text),
                   plainto_tsquery('simple',$2)
                 ),0
               ) AS rank
          FROM subtitle_cues sc
         WHERE sc.media_version_id=$1
           AND ($3='' OR sc.language=$3)
           AND (
             to_tsvector('simple',sc.cue_text) @@ plainto_tsquery('simple',$2)
             OR lower(sc.cue_text) LIKE '%'||lower($2)||'%'
           )
         ORDER BY rank DESC,sc.start_ms ASC
         LIMIT $4
    `,versionID,query,language,limit)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var lang,textValue string
        var start,end int64
        var rank float64
        if err:=rows.Scan(&lang,&start,&end,&textValue,&rank); err!=nil { continue }
        items=append(items,map[string]any{
            "language":lang,
            "startMs":start,
            "endMs":end,
            "text":textValue,
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{
        "query":query,
        "items":items,
    })
}

func playbackDeepLink(versionID string,positionMS int64) string {
    values:=url.Values{}
    if positionMS>0 { values.Set("t",strconv.FormatInt(positionMS,10)) }
    link:="filmiqoo://play/"+versionID
    if encoded:=values.Encode(); encoded!="" { link+="?"+encoded }
    return link
}
