package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	authpkg "github.com/Awmirai/filmiqoo/backend/internal/auth"
)

func (s *Server) privacyExport(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	var email,phone,username,displayName,bio,avatar,cover,status string
	var verified,privateAccount bool
	var createdAt time.Time
	err:=s.db.QueryRow(r.Context(),`
		SELECT COALESCE(u.email::text,''),COALESCE(u.phone,''),
		       p.username::text,p.display_name,p.bio,p.avatar_url,p.cover_url,
		       u.status,p.verified,p.private_account,u.created_at
		  FROM users u
		  JOIN profiles p ON p.user_id=u.id
		 WHERE u.id=$1
	`,userID).Scan(
		&email,&phone,&username,&displayName,&bio,&avatar,&cover,
		&status,&verified,&privateAccount,&createdAt,
	)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"account not found"})
		return
	}

	export:=map[string]any{
		"generatedAt":time.Now().UTC(),
		"account":map[string]any{
			"id":userID,
			"email":email,
			"phone":phone,
			"status":status,
			"createdAt":createdAt,
		},
		"profile":map[string]any{
			"username":username,
			"displayName":displayName,
			"bio":bio,
			"avatarUrl":avatar,
			"coverUrl":cover,
			"verified":verified,
			"private":privateAccount,
		},
	}

	var preferences map[string]any
	var prefRaw []byte
	if s.db.QueryRow(r.Context(),`
		SELECT to_jsonb(p)
		  FROM user_preferences p
		 WHERE p.user_id=$1
	`,userID).Scan(&prefRaw)==nil {
		_ = json.Unmarshal(prefRaw,&preferences)
		delete(preferences,"user_id")
	}
	if preferences==nil { preferences=map[string]any{} }
	export["preferences"]=preferences

	export["watchlist"]=s.exportMediaList(r,userID,`
		SELECT mt.id::text,mt.kind,mt.title,mt.year,w.created_at
		  FROM watchlist w
		  JOIN media_titles mt ON mt.id=w.media_title_id
		 WHERE w.user_id=$1
		 ORDER BY w.created_at DESC
	`)

	export["favorites"]=s.exportMediaList(r,userID,`
		SELECT mt.id::text,mt.kind,mt.title,mt.year,f.created_at
		  FROM favorites f
		  JOIN media_titles mt ON mt.id=f.media_title_id
		 WHERE f.user_id=$1
		 ORDER BY f.created_at DESC
	`)

	progress:=make([]map[string]any,0)
	rows,err:=s.db.Query(r.Context(),`
		SELECT mv.id::text,
		       COALESCE(mt.id::text,''),
		       COALESCE(mt.title,''),
		       wp.position_ms,wp.duration_ms,wp.completed,wp.updated_at
		  FROM watch_progress wp
		  JOIN media_versions mv ON mv.id=wp.media_version_id
		  LEFT JOIN media_titles mt ON mt.id=COALESCE(
		    mv.media_title_id,
		    (SELECT s.media_title_id
		       FROM episodes e
		       JOIN seasons s ON s.id=e.season_id
		      WHERE e.id=mv.episode_id)
		  )
		 WHERE wp.user_id=$1
		 ORDER BY wp.updated_at DESC
	`,userID)
	if err==nil {
		defer rows.Close()
		for rows.Next() {
			var versionID,mediaID,title string
			var position,duration int64
			var completed bool
			var updated time.Time
			if rows.Scan(&versionID,&mediaID,&title,&position,&duration,&completed,&updated)==nil {
				progress=append(progress,map[string]any{
					"mediaVersionId":versionID,
					"mediaId":mediaID,
					"title":title,
					"positionMs":position,
					"durationMs":duration,
					"completed":completed,
					"updatedAt":updated,
				})
			}
		}
	}
	export["watchProgress"]=progress

	collections:=make([]map[string]any,0)
	collectionRows,err:=s.db.Query(r.Context(),`
		SELECT id::text,name,description,emoji,visibility,item_count,created_at,updated_at
		  FROM collections
		 WHERE owner_user_id=$1
		 ORDER BY updated_at DESC
	`,userID)
	if err==nil {
		defer collectionRows.Close()
		for collectionRows.Next() {
			var id,name,description,emoji,visibility string
			var count int
			var created,updated time.Time
			if collectionRows.Scan(
				&id,&name,&description,&emoji,&visibility,&count,&created,&updated,
			)==nil {
				collections=append(collections,map[string]any{
					"id":id,"name":name,"description":description,"emoji":emoji,
					"visibility":visibility,"itemCount":count,
					"createdAt":created,"updatedAt":updated,
				})
			}
		}
	}
	export["collections"]=collections

	export["security"]=map[string]any{
		"activeSessions":s.countForUser(r,"auth_sessions","user_id",userID,"revoked_at IS NULL AND expires_at>now()"),
		"pushDevices":s.countForUser(r,"push_devices","user_id",userID,"enabled=true"),
	}
	export["social"]=map[string]any{
		"posts":s.countForUser(r,"posts","author_user_id",userID,"true"),
		"reels":s.countForUser(r,"reels","creator_user_id",userID,"true"),
		"comments":s.countForUser(r,"comments","author_user_id",userID,"true"),
		"messages":s.countForUser(r,"messages","author_user_id",userID,"true"),
		"reportsSubmitted":s.countForUser(r,"reports","reporter_user_id",userID,"true"),
	}

	w.Header().Set(
		"Content-Disposition",
		"attachment; filename=filmiqoo-account-export-"+time.Now().UTC().Format("20060102")+".json",
	)
	writeJSON(w,http.StatusOK,export)
}

func (s *Server) exportMediaList(
	r *http.Request,
	userID string,
	query string,
) []map[string]any {
	items:=make([]map[string]any,0)
	rows,err:=s.db.Query(r.Context(),query,userID)
	if err!=nil { return items }
	defer rows.Close()

	for rows.Next() {
		var id,kind,title string
		var year int
		var created time.Time
		if rows.Scan(&id,&kind,&title,&year,&created)==nil {
			items=append(items,map[string]any{
				"id":id,
				"kind":kind,
				"title":title,
				"year":year,
				"createdAt":created,
			})
		}
	}
	return items
}

func (s *Server) countForUser(
	r *http.Request,
	table string,
	column string,
	userID string,
	extra string,
) int64 {
	allowed:=map[string]bool{
		"auth_sessions":true,
		"push_devices":true,
		"posts":true,
		"reels":true,
		"comments":true,
		"messages":true,
		"reports":true,
	}
	if !allowed[table] { return 0 }

	query:="SELECT COUNT(*) FROM "+table+" WHERE "+column+"=$1"
	if strings.TrimSpace(extra)!="" {
		query+=" AND ("+extra+")"
	}
	var count int64
	if s.db.QueryRow(r.Context(),query,userID).Scan(&count)!=nil {
		return 0
	}
	return count
}

func (s *Server) deleteAccount(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	var body struct {
		Password string `json:"password"`
		Confirmation string `json:"confirmation"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	if body.Confirmation!="DELETE" {
		writeJSON(w,http.StatusBadRequest,map[string]string{
			"error":"confirmation must equal DELETE",
		})
		return
	}
	if body.Password=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"password is required"})
		return
	}

	var passwordHash,status string
	err:=s.db.QueryRow(r.Context(),`
		SELECT COALESCE(password_hash,''),status
		  FROM users
		 WHERE id=$1
	`,userID).Scan(&passwordHash,&status)
	if err!=nil || status!="active" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"account cannot be deleted"})
		return
	}
	if passwordHash=="" || !authpkg.VerifyPassword(body.Password,passwordHash) {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid password"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	defer tx.Rollback(r.Context())

	suffix:=strings.ReplaceAll(userID,"-","")
	if len(suffix)>12 { suffix=suffix[:12] }
	deletedUsername:="deleted_"+suffix

	_,err=tx.Exec(r.Context(),`
		UPDATE users
		   SET email=NULL,
		       phone=NULL,
		       password_hash=NULL,
		       status='deleted',
		       deleted_at=now(),
		       updated_at=now()
		 WHERE id=$1
	`,userID)
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE profiles
			   SET username=$2,
			       display_name='Deleted User',
			       bio='',
			       avatar_url='',
			       cover_url='',
			       verified=false,
			       private_account=true,
			       follower_count=0,
			       following_count=0,
			       updated_at=now()
			 WHERE user_id=$1
		`,userID,deletedUsername)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE auth_sessions
			   SET revoked_at=COALESCE(revoked_at,now())
			 WHERE user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM push_devices WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM user_follows
			 WHERE follower_user_id=$1 OR followed_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM follow_requests
			 WHERE requester_user_id=$1 OR target_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM blocks
			 WHERE blocker_user_id=$1 OR blocked_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM user_mutes
			 WHERE muter_user_id=$1 OR muted_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM close_friends
			 WHERE owner_user_id=$1 OR friend_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM favorites WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM watch_progress WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM downloads WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM watchlist WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM collections WHERE owner_user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM user_preferences WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM notifications WHERE user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE posts
			   SET body='',status='removed',updated_at=now()
			 WHERE author_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE comments SET body=''
			 WHERE author_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE reels
			   SET caption='',source_url='',playback_url='',cover_url='',
			       audio_name='',status='removed'
			 WHERE creator_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),"DELETE FROM stories WHERE author_user_id=$1",userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE messages
			   SET body='',attachment='{}'::jsonb,deleted_at=COALESCE(deleted_at,now())
			 WHERE author_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE channels
			   SET visibility='private',bio='',avatar_url='',cover_url='',updated_at=now()
			 WHERE owner_user_id=$1
		`,userID)
	}
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			UPDATE watch_parties
			   SET state=CASE
			     WHEN state IN ('scheduled','live') THEN 'cancelled'
			     ELSE state
			   END
			 WHERE host_user_id=$1
		`,userID)
	}

	if err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}
	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	if s.redis!=nil {
		ttl:=time.Duration(s.cfg.AuthAccessTTLMinutes+5)*time.Minute
		if ttl<20*time.Minute { ttl=20*time.Minute }
		_ = s.redis.Set(
			r.Context(),
			"auth:blocked-user:"+userID,
			"deleted",
			ttl,
		).Err()
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"deleted":true,
		"deletedAt":time.Now().UTC(),
	})
}
