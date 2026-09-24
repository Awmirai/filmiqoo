package server

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) followingUsers(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())

	rows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       p.bio,p.follower_count,
		       EXISTS(
		         SELECT 1 FROM user_presence up
		          WHERE up.user_id=p.user_id
		            AND up.state='watching'
		            AND up.visible_until>now()
		       ) AS watching_now
		  FROM user_follows uf
		  JOIN profiles p ON p.user_id=uf.followed_user_id
		 WHERE uf.follower_user_id=$1
		   AND NOT EXISTS (
		     SELECT 1 FROM blocks b
		      WHERE (b.blocker_user_id=$1 AND b.blocked_user_id=uf.followed_user_id)
		         OR (b.blocker_user_id=uf.followed_user_id AND b.blocked_user_id=$1)
		   )
		 ORDER BY watching_now DESC,p.display_name ASC
		 LIMIT 300
	`,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,username,displayName,avatar,bio string
		var verified,watching bool
		var followers int64
		if err:=rows.Scan(
			&id,&username,&displayName,&avatar,&verified,
			&bio,&followers,&watching,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"username":username,"displayName":displayName,
			"avatarUrl":avatar,"verified":verified,"bio":bio,
			"followers":followers,"watchingNow":watching,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) inviteUserToWatchParty(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	if targetID==userID {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"cannot invite yourself"}); return
	}

	var role,title,state string
	err:=s.db.QueryRow(r.Context(),`
		SELECT wpm.role,wp.title,wp.state
		  FROM watch_party_members wpm
		  JOIN watch_parties wp ON wp.id=wpm.watch_party_id
		 WHERE wpm.watch_party_id=$1 AND wpm.user_id=$2
	`,partyID,userID).Scan(&role,&title,&state)
	if err!=nil || (role!="host" && role!="cohost") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"}); return
	}
	if state=="ended" || state=="cancelled" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"watch party is closed"}); return
	}

	var blocked bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM blocks
			 WHERE (blocker_user_id=$1 AND blocked_user_id=$2)
			    OR (blocker_user_id=$2 AND blocked_user_id=$1)
		)
	`,userID,targetID).Scan(&blocked)
	if blocked {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"invite unavailable because one account blocked the other"}); return
	}

	var exists bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM users WHERE id=$1
		)
	`,targetID).Scan(&exists)
	if !exists {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"}); return
	}

	_,err=s.db.Exec(r.Context(),`
		INSERT INTO watch_party_direct_invites (
			watch_party_id,invited_user_id,invited_by_user_id,status,created_at,responded_at
		) VALUES ($1,$2,$3,'pending',now(),NULL)
		ON CONFLICT (watch_party_id,invited_user_id)
		DO UPDATE SET
		  invited_by_user_id=EXCLUDED.invited_by_user_id,
		  status='pending',
		  created_at=now(),
		  responded_at=NULL
	`,partyID,targetID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=s.db.Exec(r.Context(),`
		INSERT INTO notifications (
			user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
		) VALUES (
			$1,$2,'watch_party_invite','watch_party',$3,'دعوت به Watch Party',$4
		)
	`,targetID,userID,partyID,title)

	writeJSON(w,http.StatusOK,map[string]any{"invited":true})
}

func (s *Server) watchPartyDirectInvites(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var role string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID).Scan(&role); err!=nil || (role!="host" && role!="cohost") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"}); return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       i.status,i.created_at
		  FROM watch_party_direct_invites i
		  JOIN profiles p ON p.user_id=i.invited_user_id
		 WHERE i.watch_party_id=$1
		 ORDER BY i.created_at DESC
		 LIMIT 200
	`,partyID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,username,displayName,avatar,status string
		var verified bool
		var created time.Time
		if rows.Scan(&id,&username,&displayName,&avatar,&verified,&status,&created)==nil {
			items=append(items,map[string]any{
				"id":id,"username":username,"displayName":displayName,
				"avatarUrl":avatar,"verified":verified,
				"status":status,"createdAt":created,
			})
		}
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) respondWatchPartyInvite(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var body struct {
		Accept bool `json:"accept"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	var status string
	err:=s.db.QueryRow(r.Context(),`
		SELECT status
		  FROM watch_party_direct_invites
		 WHERE watch_party_id=$1 AND invited_user_id=$2
	`,partyID,userID).Scan(&status)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"invite not found"}); return
	}

	if !body.Accept {
		_,err=s.db.Exec(r.Context(),`
			UPDATE watch_party_direct_invites
			   SET status='declined',responded_at=now()
			 WHERE watch_party_id=$1 AND invited_user_id=$2
		`,partyID,userID)
		if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
		writeJSON(w,http.StatusOK,map[string]any{"status":"declined"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID,state string
	if err:=tx.QueryRow(r.Context(),`
		SELECT room_id::text,state
		  FROM watch_parties
		 WHERE id=$1 AND state NOT IN ('ended','cancelled')
	`,partyID).Scan(&roomID,&state); err!=nil {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"watch party unavailable"}); return
	}

	tag,err:=tx.Exec(r.Context(),`
		INSERT INTO watch_party_members (watch_party_id,user_id,role,ready,last_seen_at)
		VALUES ($1,$2,'viewer',false,now())
		ON CONFLICT DO NOTHING
	`,partyID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if tag.RowsAffected()>0 {
		_,err=tx.Exec(r.Context(),`
			UPDATE watch_parties
			   SET participant_count=participant_count+1
			 WHERE id=$1
		`,partyID)
		if err==nil {
			_,err=tx.Exec(r.Context(),`
				INSERT INTO room_members (room_id,user_id,role)
				VALUES ($1,$2,'member') ON CONFLICT DO NOTHING
			`,roomID,userID)
		}
		if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	}

	_,err=tx.Exec(r.Context(),`
		UPDATE watch_party_direct_invites
		   SET status='accepted',responded_at=now()
		 WHERE watch_party_id=$1 AND invited_user_id=$2
	`,partyID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if state=="scheduled" {
		_,_=tx.Exec(r.Context(),`
			INSERT INTO watch_party_reminders (watch_party_id,user_id)
			VALUES ($1,$2) ON CONFLICT DO NOTHING
		`,partyID,userID)
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"status":"accepted","roomId":roomID,
	})
}
