package server

import (
	"net/http"

	"github.com/go-chi/chi/v5"
)

func (s *Server) channelManageRooms(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	channelID:=chi.URLParam(r,"id")

	role,err:=s.channelRole(r.Context(),channelID,userID)
	if err!=nil || !canManageChannel(role) {
		writeJSON(w,http.StatusForbidden,map[string]string{"error":"channel management permission required"})
		return
	}

	rows,err:=s.db.Query(r.Context(),`
		SELECT id::text,name,topic,room_type,visibility,member_count,slow_mode_seconds
		  FROM rooms
		 WHERE channel_id=$1
		 ORDER BY created_at DESC
		 LIMIT 100
	`,channelID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,name,topic,roomType,visibility string
		var members int64
		var slowMode int
		if err:=rows.Scan(
			&id,&name,&topic,&roomType,&visibility,&members,&slowMode,
		); err!=nil { continue }
		items=append(items,map[string]any{
			"id":id,"name":name,"topic":topic,"type":roomType,
			"visibility":visibility,"members":members,"slowModeSeconds":slowMode,
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items})
}
