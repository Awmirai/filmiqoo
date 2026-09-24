package server

import (
	"encoding/json"
	"net/http"
	"strings"
)

func (s *Server) socialFeedback(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body struct {
		TargetType string `json:"targetType"`
		TargetID string `json:"targetId"`
		Action string `json:"action"`
	}
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}

	body.TargetType=strings.ToLower(strings.TrimSpace(body.TargetType))
	body.TargetID=strings.TrimSpace(body.TargetID)
	body.Action=strings.ToLower(strings.TrimSpace(body.Action))

	switch body.TargetType {
	case "post","reel","media":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid feedback target"}); return
	}
	switch body.Action {
	case "not_interested","show_more":
	default:
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid feedback action"}); return
	}
	if body.TargetID=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"targetId is required"}); return
	}

	_,err:=s.db.Exec(r.Context(),`
		INSERT INTO feed_feedback (user_id,target_type,target_id,action)
		VALUES ($1,$2,$3,$4)
		ON CONFLICT (user_id,target_type,target_id)
		DO UPDATE SET action=EXCLUDED.action,updated_at=now()
	`,userID,body.TargetType,body.TargetID,body.Action)
	if err!=nil {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid feedback target id"}); return
	}

	writeJSON(w,http.StatusOK,map[string]any{
		"targetType":body.TargetType,
		"targetId":body.TargetID,
		"action":body.Action,
	})
}
