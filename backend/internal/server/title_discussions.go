package server

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

var discussionScope = regexp.MustCompile(`^(movie|series):[1-9][0-9]{0,10}$|^catalog:[a-f0-9-]{36}$`)
var discussionUUID = regexp.MustCompile(`^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$`)
var discussionStickers = map[string]bool{"popcorn": true, "masterpiece": true, "mindblown": true, "tears": true, "applause": true, "rewatch": true, "boring": true, "heart": true}

// Keyset pagination stays stable when comments are posted while a reader scrolls.
func discussionCursor(raw string) (time.Time, string, bool) {
	if raw == "" {
		return time.Now().Add(time.Hour), "ffffffff-ffff-ffff-ffff-ffffffffffff", true
	}
	b, err := base64.RawURLEncoding.DecodeString(raw)
	if err != nil {
		return time.Time{}, "", false
	}
	p := strings.Split(string(b), "|")
	if len(p) != 2 || !discussionUUID.MatchString(p[1]) {
		return time.Time{}, "", false
	}
	t, err := time.Parse(time.RFC3339Nano, p[0])
	return t, p[1], err == nil
}

func (s *Server) titleComments(w http.ResponseWriter, r *http.Request) {
	scope, viewer, parent := chi.URLParam(r, "scope"), userIDFromContext(r.Context()), r.URL.Query().Get("parent")
	before, beforeID, valid := discussionCursor(r.URL.Query().Get("cursor"))
	if !discussionScope.MatchString(scope) || !valid || (parent != "" && !discussionUUID.MatchString(parent)) {
		writeJSON(w, 400, map[string]string{"error": "invalid discussion or cursor"})
		return
	}
	rows, err := s.db.Query(r.Context(), `
	 SELECT c.id::text,c.body,c.spoiler,c.sticker,c.deleted,c.created_at,
	 p.user_id::text,p.display_name,p.avatar_url,COALESCE(u.object_key,''),
	 (SELECT count(*) FROM title_comment_likes l WHERE l.comment_id=c.id),
	 EXISTS(SELECT 1 FROM title_comment_likes l WHERE l.comment_id=c.id AND l.user_id::text=$2),
	 (SELECT count(*) FROM title_comments ch WHERE ch.parent_id=c.id AND NOT ch.deleted
	   AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_user_id::text=$2 AND b.blocked_user_id=ch.user_id) OR (b.blocked_user_id::text=$2 AND b.blocker_user_id=ch.user_id)))
	 FROM title_comments c JOIN profiles p ON p.user_id=c.user_id JOIN users a ON a.id=c.user_id
	 LEFT JOIN ugc_uploads u ON u.id=c.upload_id AND u.status='uploaded'
	 WHERE c.scope=$1 AND COALESCE(c.parent_id::text,'')=$3 AND (c.created_at,c.id)<($4,$5::uuid)
	 AND a.status='active'
	 AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_user_id::text=$2 AND b.blocked_user_id=c.user_id) OR (b.blocked_user_id::text=$2 AND b.blocker_user_id=c.user_id))
	 ORDER BY c.created_at DESC,c.id DESC LIMIT 21`, scope, viewer, parent, before, beforeID)
	if err != nil {
		writeError(w, 500, err)
		return
	}
	defer rows.Close()
	items := make([]map[string]any, 0)
	var next any
	for rows.Next() {
		var id, body, sticker, author, name, avatar, key string
		var spoiler, deleted, liked bool
		var created time.Time
		var likes, replies int64
		if err = rows.Scan(&id, &body, &spoiler, &sticker, &deleted, &created, &author, &name, &avatar, &key, &likes, &liked, &replies); err != nil {
			writeError(w, 500, err)
			return
		}
		if len(items) == 20 {
			last := items[19]
			next = base64.RawURLEncoding.EncodeToString([]byte(last["createdAt"].(time.Time).Format(time.RFC3339Nano) + "|" + last["id"].(string)))
			break
		}
		url := ""
		if key != "" {
			url = s.mediaURL(key)
		}
		if deleted {
			body = ""
			sticker = ""
			url = ""
			spoiler = false
		}
		items = append(items, map[string]any{"id": id, "body": body, "spoiler": spoiler, "sticker": sticker, "gifUrl": url, "deleted": deleted, "createdAt": created, "authorId": author, "authorName": name, "avatarUrl": avatar, "own": viewer == author, "liked": liked, "likes": likes, "replies": replies})
	}
	if err = rows.Err(); err != nil {
		writeError(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]any{"items": items, "nextCursor": next})
}

func (s *Server) addTitleComment(w http.ResponseWriter, r *http.Request) {
	scope, user := chi.URLParam(r, "scope"), userIDFromContext(r.Context())
	var b struct {
		ClientID string `json:"clientId"`
		Body     string `json:"body"`
		ParentID string `json:"parentId"`
		Spoiler  bool   `json:"spoiler"`
		Sticker  string `json:"sticker"`
		UploadID string `json:"uploadId"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 20000)).Decode(&b); err != nil {
		writeJSON(w, 400, map[string]string{"error": "invalid comment"})
		return
	}
	b.Body = strings.TrimSpace(b.Body)
	if !discussionScope.MatchString(scope) || !discussionUUID.MatchString(b.ClientID) || len([]rune(b.Body)) > 3000 ||
		(b.ParentID != "" && !discussionUUID.MatchString(b.ParentID)) || (b.UploadID != "" && !discussionUUID.MatchString(b.UploadID)) ||
		(b.Sticker != "" && !discussionStickers[b.Sticker]) || (b.Body == "" && b.Sticker == "" && b.UploadID == "") || (b.Sticker != "" && b.UploadID != "") {
		writeJSON(w, 400, map[string]string{"error": "invalid comment content"})
		return
	}
	// A duplicate retry must return the same identity, even after the upload was consumed.
	var existing, existingScope string
	if s.db.QueryRow(r.Context(), "SELECT id::text,scope FROM title_comments WHERE user_id=$1 AND client_id=$2", user, b.ClientID).Scan(&existing, &existingScope) == nil {
		if existingScope != scope {
			writeJSON(w, 409, map[string]string{"error": "client id already used"})
			return
		}
		writeJSON(w, 200, map[string]string{"id": existing})
		return
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		writeError(w, 500, err)
		return
	}
	defer tx.Rollback(r.Context())
	if b.ParentID != "" {
		var allowed bool
		err = tx.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM title_comments c WHERE c.id=$1 AND c.scope=$2 AND c.parent_id IS NULL AND NOT c.deleted
		 AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_user_id=$3 AND b.blocked_user_id=c.user_id) OR (b.blocked_user_id=$3 AND b.blocker_user_id=c.user_id)))`, b.ParentID, scope, user).Scan(&allowed)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if !allowed {
			writeJSON(w, 404, map[string]string{"error": "reply target unavailable"})
			return
		}
	}
	if b.UploadID != "" {
		var valid bool
		err = tx.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM ugc_uploads WHERE id=$1 AND user_id=$2 AND status='uploaded' AND kind='title-comment' AND mime_type='image/gif' AND size_bytes<=10485760)`, b.UploadID, user).Scan(&valid)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if !valid {
			writeJSON(w, 400, map[string]string{"error": "GIF must be an uploaded file owned by you, at most 10 MB"})
			return
		}
	}
	var id string
	err = tx.QueryRow(r.Context(), `INSERT INTO title_comments(scope,user_id,client_id,body,parent_id,spoiler,sticker,upload_id)
	 SELECT $1,$2,$3,$4,NULLIF($5,'')::uuid,$6,$7,NULLIF($8,'')::uuid WHERE EXISTS(SELECT 1 FROM users WHERE id=$2 AND status='active')
	 ON CONFLICT(user_id,client_id) DO UPDATE SET client_id=EXCLUDED.client_id WHERE title_comments.scope=EXCLUDED.scope RETURNING id::text`, scope, user, b.ClientID, b.Body, b.ParentID, b.Spoiler, b.Sticker, b.UploadID).Scan(&id)
	if err != nil {
		writeError(w, 409, err)
		return
	}
	if err = tx.Commit(r.Context()); err != nil {
		writeError(w, 500, err)
		return
	}
	writeJSON(w, 201, map[string]string{"id": id})
}

func (s *Server) titleCommentAction(w http.ResponseWriter, r *http.Request) {
	id, user, action := chi.URLParam(r, "id"), userIDFromContext(r.Context()), chi.URLParam(r, "action")
	if !discussionUUID.MatchString(id) {
		writeJSON(w, 400, map[string]string{"error": "invalid comment"})
		return
	}
	if action == "remove" {
		tag, err := s.db.Exec(r.Context(), `UPDATE title_comments SET deleted=true,body='',sticker='',upload_id=NULL WHERE id=$1 AND user_id=$2`, id, user)
		if err != nil {
			writeError(w, 500, err)
			return
		}
		if tag.RowsAffected() == 0 {
			writeJSON(w, 404, map[string]string{"error": "comment not found"})
			return
		}
		writeJSON(w, 200, map[string]bool{"deleted": true})
		return
	}
	if action != "like" {
		writeJSON(w, 404, map[string]string{"error": "unknown action"})
		return
	}
	var b struct {
		Liked bool `json:"liked"`
	}
	if json.NewDecoder(r.Body).Decode(&b) != nil {
		writeJSON(w, 400, map[string]string{"error": "invalid reaction"})
		return
	}
	var allowed bool
	err := s.db.QueryRow(r.Context(), `SELECT EXISTS(SELECT 1 FROM title_comments c WHERE id=$1 AND NOT deleted AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_user_id=$2 AND b.blocked_user_id=c.user_id) OR (b.blocked_user_id=$2 AND b.blocker_user_id=c.user_id)))`, id, user).Scan(&allowed)
	if err != nil {
		writeError(w, 500, err)
		return
	}
	if !allowed {
		writeJSON(w, 404, map[string]string{"error": "comment unavailable"})
		return
	}
	if b.Liked {
		_, err = s.db.Exec(r.Context(), `INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, id, user)
	} else {
		_, err = s.db.Exec(r.Context(), `DELETE FROM title_comment_likes WHERE comment_id=$1 AND user_id=$2`, id, user)
	}
	if err != nil {
		writeError(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"liked": b.Liked})
}
