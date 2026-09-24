package server

import (
	"context"
	"encoding/json"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
)

func (s *Server) channelRole(ctx context.Context,channelID,userID string) (string,error) {
	var role string
	err:=s.db.QueryRow(ctx,`
		SELECT role
		  FROM channel_members
		 WHERE channel_id=$1 AND user_id=$2
	`,channelID,userID).Scan(&role)
	return role,err
}

func canManageChannel(role string) bool {
	return role=="owner" || role=="admin" || role=="moderator"
}

func (s *Server) channelManageOverview(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || !canManageChannel(role) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"channel management permission required"})
		return
	}

	var ownerID,slug,name,bio,avatar,cover,visibility string
	var verified bool
	var followers,posts,reels int64
	err=s.db.QueryRow(r.Context(),`
		SELECT owner_user_id::text,slug::text,name,bio,avatar_url,cover_url,
		       visibility,verified,follower_count,post_count,reel_count
		  FROM channels
		 WHERE id=$1
	`,channelID).Scan(
		&ownerID,&slug,&name,&bio,&avatar,&cover,
		&visibility,&verified,&followers,&posts,&reels,
	)
	if err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"channel not found"})
		return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"id":channelID,
		"ownerUserId":ownerID,
		"slug":slug,
		"name":name,
		"bio":bio,
		"avatarUrl":avatar,
		"coverUrl":cover,
		"visibility":visibility,
		"verified":verified,
		"followers":followers,
		"posts":posts,
		"reels":reels,
		"myRole":role,
	})
}

func (s *Server) updateChannelSettings(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || (role!="owner" && role!="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}

	var body struct {
		Name *string `json:"name"`
		Bio *string `json:"bio"`
		Visibility *string `json:"visibility"`
		AvatarURL *string `json:"avatarUrl"`
		CoverURL *string `json:"coverUrl"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	if body.Name!=nil {
		v:=strings.TrimSpace(*body.Name)
		if len([]rune(v))<2 || len([]rune(v))>80 {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"name must be 2-80 characters"}); return
		}
		body.Name=&v
	}
	if body.Bio!=nil {
		v:=strings.TrimSpace(*body.Bio)
		if len([]rune(v))>500 {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"bio is too long"}); return
		}
		body.Bio=&v
	}
	if body.Visibility!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.Visibility))
		switch v {
		case "public","private","invite":
		default:
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
		}
		body.Visibility=&v
	}

	_,err=s.db.Exec(r.Context(),`
		UPDATE channels SET
		  name=COALESCE($2,name),
		  bio=COALESCE($3,bio),
		  visibility=COALESCE($4,visibility),
		  avatar_url=COALESCE($5,avatar_url),
		  cover_url=COALESCE($6,cover_url),
		  updated_at=now()
		 WHERE id=$1
	`,channelID,body.Name,body.Bio,body.Visibility,body.AvatarURL,body.CoverURL)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	s.channelManageOverview(w,r)
}

func (s *Server) changeChannelMemberRole(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || role!="owner" {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner permission required"})
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
	case "admin","moderator","member":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid role"}); return
	}

	var targetRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM channel_members WHERE channel_id=$1 AND user_id=$2
	`,channelID,targetID).Scan(&targetRole); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"channel member not found"}); return
	}
	if targetRole=="owner" {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"owner role cannot be changed here"}); return
	}

	_,err=s.db.Exec(r.Context(),`
		UPDATE channel_members SET role=$3
		 WHERE channel_id=$1 AND user_id=$2
	`,channelID,targetID,body.Role)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{
		"userId":targetID,
		"role":body.Role,
	})
}

func (s *Server) removeChannelMember(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")
	targetID:=chi.URLParam(r,"userID")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || (role!="owner" && role!="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"owner or admin permission required"})
		return
	}

	var targetRole string
	if err:=s.db.QueryRow(r.Context(),`
		SELECT role FROM channel_members WHERE channel_id=$1 AND user_id=$2
	`,channelID,targetID).Scan(&targetRole); err!=nil {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"channel member not found"}); return
	}
	if targetRole=="owner" || (role=="admin" && targetRole=="admin") {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"cannot remove this member"})
		return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	_,err=tx.Exec(r.Context(),`
		DELETE FROM channel_members WHERE channel_id=$1 AND user_id=$2
	`,channelID,targetID)
	if err==nil {
		_,err=tx.Exec(r.Context(),`
			DELETE FROM room_members rm
			 USING rooms r
			 WHERE r.channel_id=$1 AND rm.room_id=r.id AND rm.user_id=$2
		`,channelID,targetID)
	}
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusOK,map[string]any{"removed":true})
}

func (s *Server) createChannelRoom(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || !canManageChannel(role) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"channel management permission required"})
		return
	}

	var body struct {
		Name string `json:"name"`
		Topic string `json:"topic"`
		Visibility string `json:"visibility"`
		SlowModeSeconds int `json:"slowModeSeconds"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Name=strings.TrimSpace(body.Name)
	body.Topic=strings.TrimSpace(body.Topic)
	body.Visibility=strings.ToLower(strings.TrimSpace(body.Visibility))

	if len([]rune(body.Name))<2 || len([]rune(body.Name))>100 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"room name must be 2-100 characters"}); return
	}
	if len([]rune(body.Topic))>300 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"topic is too long"}); return
	}
	if body.Visibility=="" { body.Visibility="public" }
	switch body.Visibility {
	case "public","private","invite":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
	}
	if body.SlowModeSeconds<0 || body.SlowModeSeconds>3600 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"slowModeSeconds must be 0-3600"}); return
	}

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var roomID string
	err=tx.QueryRow(r.Context(),`
		INSERT INTO rooms (
			owner_user_id,channel_id,name,topic,room_type,visibility,member_count,slow_mode_seconds
		) VALUES ($1,$2,$3,$4,'group',$5,1,$6)
		RETURNING id::text
	`,userID,channelID,body.Name,body.Topic,body.Visibility,body.SlowModeSeconds).Scan(&roomID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	_,err=tx.Exec(r.Context(),`
		INSERT INTO room_members (room_id,user_id,role)
		VALUES ($1,$2,$3)
	`,roomID,userID,
		func() string {
			if role=="owner" { return "owner" }
			if role=="admin" { return "admin" }
			return "moderator"
		}(),
	)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil {
		writeError(w,http.StatusInternalServerError,err); return
	}
	writeJSON(w,http.StatusCreated,map[string]any{
		"id":roomID,
		"name":body.Name,
		"topic":body.Topic,
		"visibility":body.Visibility,
		"slowModeSeconds":body.SlowModeSeconds,
	})
}

func (s *Server) updateChannelRoomSettings(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")
	roomID:=chi.URLParam(r,"roomID")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || !canManageChannel(role) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"channel management permission required"})
		return
	}

	var body struct {
		Topic *string `json:"topic"`
		Visibility *string `json:"visibility"`
		SlowModeSeconds *int `json:"slowModeSeconds"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	if body.Topic!=nil {
		v:=strings.TrimSpace(*body.Topic)
		if len([]rune(v))>300 {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"topic is too long"}); return
		}
		body.Topic=&v
	}
	if body.Visibility!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.Visibility))
		switch v {
		case "public","private","invite":
		default:
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid visibility"}); return
		}
		body.Visibility=&v
	}
	if body.SlowModeSeconds!=nil && (*body.SlowModeSeconds<0 || *body.SlowModeSeconds>3600) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"slowModeSeconds must be 0-3600"}); return
	}

	tag,err:=s.db.Exec(r.Context(),`
		UPDATE rooms SET
		  topic=COALESCE($3,topic),
		  visibility=COALESCE($4,visibility),
		  slow_mode_seconds=COALESCE($5,slow_mode_seconds)
		 WHERE id=$1 AND channel_id=$2
	`,roomID,channelID,body.Topic,body.Visibility,body.SlowModeSeconds)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	if tag.RowsAffected()==0 {
		writeJSON(w,http.StatusNotFound,map[string]string{"error":"room not found"}); return
	}

	writeJSON(w,http.StatusOK,map[string]any{"updated":true})
}
