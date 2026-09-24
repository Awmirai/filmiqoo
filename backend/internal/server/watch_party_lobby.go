package server

import (
	"encoding/json"
	"net/http"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
)

func (s *Server) watchPartyLobby(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var myRole string
	var myReady bool
	err:=s.db.QueryRow(r.Context(),`
		SELECT role,ready
		  FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID).Scan(&myRole,&myReady)
	if err!=nil {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"join the watch party first"})
		return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
		       wpm.role,wpm.ready,wpm.joined_at,wpm.last_seen_at
		  FROM watch_party_members wpm
		  JOIN profiles p ON p.user_id=wpm.user_id
		 WHERE wpm.watch_party_id=$1
		 ORDER BY
		   CASE wpm.role
		     WHEN 'host' THEN 0
		     WHEN 'cohost' THEN 1
		     WHEN 'moderator' THEN 2
		     ELSE 3
		   END,
		   wpm.joined_at ASC
		 LIMIT 200
	`,partyID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	members:=make([]map[string]any,0)
	var readyCount int64
	for rows.Next() {
		var id,username,displayName,avatar,role string
		var verified,ready bool
		var joined,lastSeen time.Time
		if err:=rows.Scan(
			&id,&username,&displayName,&avatar,&verified,
			&role,&ready,&joined,&lastSeen,
		); err!=nil { continue }
		if ready { readyCount++ }
		members=append(members,map[string]any{
			"id":id,"username":username,"displayName":displayName,"avatarUrl":avatar,
			"verified":verified,"role":role,"ready":ready,
			"joinedAt":joined,"lastSeenAt":lastSeen,
		})
	}

	requests:=make([]map[string]any,0)
	if myRole=="host" || myRole=="cohost" {
		reqRows,err:=s.db.Query(r.Context(),`
			SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
			       wpr.requested_at
			  FROM watch_party_join_requests wpr
			  JOIN profiles p ON p.user_id=wpr.user_id
			 WHERE wpr.watch_party_id=$1 AND wpr.status='pending'
			 ORDER BY wpr.requested_at ASC
			 LIMIT 100
		`,partyID)
		if err==nil {
			defer reqRows.Close()
			for reqRows.Next() {
				var id,username,displayName,avatar string
				var verified bool
				var requested time.Time
				if reqRows.Scan(&id,&username,&displayName,&avatar,&verified,&requested)==nil {
					requests=append(requests,map[string]any{
						"id":id,"username":username,"displayName":displayName,
						"avatarUrl":avatar,"verified":verified,"requestedAt":requested,
					})
				}
			}
		}
	}

	var readyCheck bool
	var state string
	var participantCount int64
	err=s.db.QueryRow(r.Context(),`
		SELECT ready_check_enabled,state,participant_count
		  FROM watch_parties
		 WHERE id=$1
	`,partyID).Scan(&readyCheck,&state,&participantCount)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party not found"})
		return
	}

	_,_=s.db.Exec(r.Context(),`
		UPDATE watch_party_members
		   SET last_seen_at=now()
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID)

	writeJSON(w,http.StatusOK,map[string]any{
		"myRole":myRole,
		"myReady":myReady,
		"readyCheckEnabled":readyCheck,
		"readyCount":readyCount,
		"participantCount":participantCount,
		"state":state,
		"members":members,
		"requests":requests,
	})
}

func (s *Server) requestWatchPartyJoin(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var visibility,state,title,hostID string
	err:=s.db.QueryRow(r.Context(),`
		SELECT visibility,state,title,host_user_id::text
		  FROM watch_parties
		 WHERE id=$1
	`,partyID).Scan(&visibility,&state,&title,&hostID)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party not found"}); return
	}
	if state=="ended" || state=="cancelled" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"watch party is closed"}); return
	}
	if visibility!="private" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"join requests are only needed for private parties"}); return
	}

	var existing bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2
		)
	`,partyID,userID).Scan(&existing)
	if existing {
		writeJSON(w,http.StatusOK,map[string]any{"status":"joined"})
		return
	}

	_,err=s.db.Exec(r.Context(),`
		INSERT INTO watch_party_join_requests (
			watch_party_id,user_id,status,requested_at,resolved_at
		) VALUES ($1,$2,'pending',now(),NULL)
		ON CONFLICT (watch_party_id,user_id)
		DO UPDATE SET status='pending',requested_at=now(),resolved_at=NULL
	`,partyID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,_=s.db.Exec(r.Context(),`
		INSERT INTO notifications (
			user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
		) VALUES (
			$1,$2,'watch_party_join_request','watch_party',$3,'درخواست ورود Watch Party',$4
		)
	`,hostID,userID,partyID,"درخواست ورود به "+title)

	writeJSON(w,http.StatusOK,map[string]any{"status":"pending"})
}

func (s *Server) resolveWatchPartyJoinRequest(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	var myRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID).Scan(&myRole); err!=nil || (myRole!="host" && myRole!="cohost") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"})
		return
	}

	var body struct {
		Accept bool `json:"accept"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID,title string
	if err:=tx.QueryRow(r.Context(),`
		SELECT room_id::text,title FROM watch_parties WHERE id=$1
	`,partyID).Scan(&roomID,&title); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"watch party not found"}); return
	}

	var pending bool
	_=tx.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_join_requests
			 WHERE watch_party_id=$1 AND user_id=$2 AND status='pending'
		)
	`,partyID,targetID).Scan(&pending)
	if !pending {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"pending request not found"})
		return
	}

	status:="declined"
	if body.Accept {
		status="approved"
		tag,err:=tx.Exec(r.Context(),`
			INSERT INTO watch_party_members (watch_party_id,user_id,role,ready,last_seen_at)
			VALUES ($1,$2,'viewer',false,now())
			ON CONFLICT DO NOTHING
		`,partyID,targetID)
		if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

		if tag.RowsAffected()>0 {
			_,err=tx.Exec(r.Context(),`
				UPDATE watch_parties
				   SET participant_count=participant_count+1
				 WHERE id=$1
			`,partyID)
			if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

			_,err=tx.Exec(r.Context(),`
				INSERT INTO room_members (room_id,user_id,role)
				VALUES ($1,$2,'member')
				ON CONFLICT DO NOTHING
			`,roomID,targetID)
			if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
		}
	}

	_,err=tx.Exec(r.Context(),`
		UPDATE watch_party_join_requests
		   SET status=$3,resolved_at=now()
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,targetID,status)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		INSERT INTO notifications (
			user_id,actor_user_id,notification_type,entity_type,entity_id,title,body
		) VALUES (
			$1,$2,$3,'watch_party',$4,$5,$6
		)
	`,
		targetID,userID,
		func() string {
			if body.Accept { return "watch_party_join_approved" }
			return "watch_party_join_declined"
		}(),
		partyID,
		func() string {
			if body.Accept { return "درخواست ورود تأیید شد" }
			return "درخواست ورود رد شد"
		}(),
		title,
	)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}

	writeJSON(w,http.StatusOK,map[string]any{"status":status})
}

func (s *Server) toggleWatchPartyReady(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var ready bool
	err:=s.db.QueryRow(r.Context(),`
		UPDATE watch_party_members
		   SET ready=NOT ready,last_seen_at=now()
		 WHERE watch_party_id=$1 AND user_id=$2
		 RETURNING ready
	`,partyID,userID).Scan(&ready)
	if err!=nil {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"join the watch party first"}); return
	}

	writeJSON(w,http.StatusOK,map[string]any{"ready":ready})
}

func (s *Server) setWatchPartyReadyCheck(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var body struct {
		Enabled bool `json:"enabled"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	var allowed bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2 AND role IN ('host','cohost')
		)
	`,partyID,userID).Scan(&allowed)
	if !allowed {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"}); return
	}

	_,err:=s.db.Exec(r.Context(),`
		UPDATE watch_parties SET ready_check_enabled=$2 WHERE id=$1
	`,partyID,body.Enabled)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if body.Enabled {
		_,_=s.db.Exec(r.Context(),`
			UPDATE watch_party_members
			   SET ready=CASE WHEN role IN ('host','cohost') THEN true ELSE false END
			 WHERE watch_party_id=$1
		`,partyID)
	}

	writeJSON(w,http.StatusOK,map[string]any{"enabled":body.Enabled})
}

func (s *Server) updateWatchPartyMemberRole(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	var myRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID).Scan(&myRole); err!=nil || myRole!="host" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"host permission required"})
		return
	}

	var body struct {
		Role string `json:"role"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Role=strings.ToLower(strings.TrimSpace(body.Role))
	switch body.Role {
	case "cohost","moderator","viewer":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid role"}); return
	}

	var targetRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,targetID).Scan(&targetRole); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"member not found"}); return
	}
	if targetRole=="host" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"host role cannot be changed"})
		return
	}

	_,err:=s.db.Exec(r.Context(),`
		UPDATE watch_party_members
		   SET role=$3,last_seen_at=now()
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,targetID,body.Role)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{"role":body.Role})
}

func (s *Server) leaveWatchParty(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var role,roomID string
	if err:=tx.QueryRow(r.Context(),`
		SELECT wpm.role,wp.room_id::text
		  FROM watch_party_members wpm
		  JOIN watch_parties wp ON wp.id=wpm.watch_party_id
		 WHERE wpm.watch_party_id=$1 AND wpm.user_id=$2
	`,partyID,userID).Scan(&role,&roomID); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"membership not found"}); return
	}
	if role=="host" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"host must end the party instead of leaving"})
		return
	}

	tag,err:=tx.Exec(r.Context(),`
		DELETE FROM watch_party_members
		 WHERE watch_party_id=$1 AND user_id=$2
	`,partyID,userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if tag.RowsAffected()>0 {
		_,err=tx.Exec(r.Context(),`
			UPDATE watch_parties
			   SET participant_count=GREATEST(participant_count-1,0)
			 WHERE id=$1
		`,partyID)
		if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

		_,err=tx.Exec(r.Context(),`
			DELETE FROM room_members WHERE room_id=$1 AND user_id=$2
		`,roomID,userID)
		if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	}

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusOK,map[string]any{"left":true})
}

func (s *Server) reactWatchParty(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	partyID:=chi.URLParam(r,"id")

	var member bool
	_=s.db.QueryRow(r.Context(),`
		SELECT EXISTS(
			SELECT 1 FROM watch_party_members
			 WHERE watch_party_id=$1 AND user_id=$2
		)
	`,partyID,userID).Scan(&member)
	if !member {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"join the watch party first"}); return
	}

	var body struct {
		Emoji string `json:"emoji"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Emoji=strings.TrimSpace(body.Emoji)
	switch body.Emoji {
	case "❤️","😂","😮","🔥","👏","😢","🤯":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"unsupported reaction"}); return
	}

	var id string
	var created time.Time
	err:=s.db.QueryRow(r.Context(),`
		INSERT INTO watch_party_reactions (watch_party_id,user_id,emoji)
		VALUES ($1,$2,$3)
		RETURNING id::text,created_at
	`,partyID,userID,body.Emoji).Scan(&id,&created)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	event:=map[string]any{
		"id":id,"watchPartyId":partyID,"userId":userID,
		"emoji":body.Emoji,"createdAt":created,
	}
	raw,_:=json.Marshal(map[string]any{
		"type":"watchparty.reaction",
		"reaction":event,
	})
	_=s.redis.Publish(r.Context(),"watchparty:"+partyID,raw).Err()

	writeJSON(w,http.StatusCreated,event)
}

func (s *Server) recentWatchPartyReactions(w http.ResponseWriter,r *http.Request) {
	partyID:=chi.URLParam(r,"id")
	rows,err:=s.db.Query(r.Context(),`
		SELECT wpr.id::text,wpr.emoji,wpr.created_at,
		       p.user_id::text,p.display_name,p.avatar_url
		  FROM watch_party_reactions wpr
		  JOIN profiles p ON p.user_id=wpr.user_id
		 WHERE wpr.watch_party_id=$1
		   AND wpr.created_at>now()-interval '30 seconds'
		 ORDER BY wpr.created_at DESC
		 LIMIT 60
	`,partyID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,emoji,userID,displayName,avatar string
		var created time.Time
		if rows.Scan(&id,&emoji,&created,&userID,&displayName,&avatar)==nil {
			items=append(items,map[string]any{
				"id":id,"emoji":emoji,"createdAt":created,
				"user":map[string]any{
					"id":userID,"displayName":displayName,"avatarUrl":avatar,
				},
			})
		}
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
