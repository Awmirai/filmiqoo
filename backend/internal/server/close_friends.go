package server

import (
    "context"
    "net/http"

    "github.com/go-chi/chi/v5"
)

func (s *Server) closeFriends(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())

    rows,err:=s.db.Query(r.Context(),`
        SELECT p.user_id::text,p.username::text,p.display_name,p.avatar_url,p.verified,
               (cf.friend_user_id IS NOT NULL) AS close_friend
          FROM user_follows uf
          JOIN profiles p ON p.user_id=uf.followed_user_id
          LEFT JOIN close_friends cf
            ON cf.owner_user_id=uf.follower_user_id
           AND cf.friend_user_id=uf.followed_user_id
         WHERE uf.follower_user_id=$1
         ORDER BY close_friend DESC,p.display_name ASC
         LIMIT 500
    `,userID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
    defer rows.Close()

    items:=make([]map[string]any,0)
    for rows.Next() {
        var id,username,displayName,avatar string
        var verified,closeFriend bool
        if err:=rows.Scan(
            &id,&username,&displayName,&avatar,&verified,&closeFriend,
        ); err!=nil { continue }

        items=append(items,map[string]any{
            "id":id,
            "username":username,
            "displayName":displayName,
            "avatarUrl":avatar,
            "verified":verified,
            "closeFriend":closeFriend,
        })
    }

    writeJSON(w,http.StatusOK,map[string]any{"items":items})
}

func (s *Server) toggleCloseFriend(w http.ResponseWriter,r *http.Request) {
    userID:=userIDFromContext(r.Context())
    friendID:=chi.URLParam(r,"userID")
    if friendID==userID {
        writeJSON(w,http.StatusBadRequest,map[string]string{"error":"cannot add yourself"})
        return
    }

    var following bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM user_follows
             WHERE follower_user_id=$1 AND followed_user_id=$2
        )
    `,userID,friendID).Scan(&following); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }
    if !following {
        writeJSON(w,http.StatusConflict,map[string]string{
            "error":"you must follow this user before adding them to Close Friends",
        })
        return
    }

    var exists bool
    if err:=s.db.QueryRow(r.Context(),`
        SELECT EXISTS(
            SELECT 1 FROM close_friends
             WHERE owner_user_id=$1 AND friend_user_id=$2
        )
    `,userID,friendID).Scan(&exists); err!=nil {
        writeError(w,http.StatusInternalServerError,err); return
    }

    if exists {
        _,err:=s.db.Exec(r.Context(),`
            DELETE FROM close_friends
             WHERE owner_user_id=$1 AND friend_user_id=$2
        `,userID,friendID)
        if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
        writeJSON(w,http.StatusOK,map[string]any{"closeFriend":false})
        return
    }

    _,err=s.db.Exec(r.Context(),`
        INSERT INTO close_friends (owner_user_id,friend_user_id)
        VALUES ($1,$2)
        ON CONFLICT DO NOTHING
    `,userID,friendID)
    if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

    writeJSON(w,http.StatusOK,map[string]any{"closeFriend":true})
}


func (s *Server) storyAuthorIfAccessible(
    ctx context.Context,
    storyID string,
    viewerID string,
) (string,bool) {
    var authorID string
    err:=s.db.QueryRow(ctx,`
        SELECT st.author_user_id::text
          FROM stories st
         WHERE st.id=$1
           AND st.expires_at>now()
           AND (
             st.author_user_id=$2
             OR st.close_friends_only=false
             OR EXISTS (
               SELECT 1 FROM close_friends cf
                WHERE cf.owner_user_id=st.author_user_id
                  AND cf.friend_user_id=$2
             )
           )
    `,storyID,viewerID).Scan(&authorID)
    return authorID,err==nil
}
