package server

import (
	"context"
	"strings"
)

func (s *Server) canViewPost(
	ctx context.Context,
	viewerID string,
	postID string,
) (bool,bool,error) {
	var allowed bool
	err:=s.db.QueryRow(ctx,`
		SELECT
		  CASE
		    WHEN $2='' THEN
		      CASE
		        WHEN p.channel_id IS NULL THEN NOT pr.private_account
		        ELSE COALESCE(ch.visibility='public',false)
		      END
		    ELSE
		      NOT EXISTS(
		        SELECT 1 FROM blocks b
		         WHERE (
		           b.blocker_user_id::text=$2 AND
		           b.blocked_user_id=p.author_user_id
		         ) OR (
		           b.blocker_user_id=p.author_user_id AND
		           b.blocked_user_id::text=$2
		         )
		      )
		      AND
		      CASE
		        WHEN p.channel_id IS NULL THEN
		          p.author_user_id::text=$2
		          OR NOT pr.private_account
		          OR EXISTS(
		            SELECT 1 FROM user_follows uf
		             WHERE uf.follower_user_id::text=$2
		               AND uf.followed_user_id=p.author_user_id
		          )
		        ELSE
		          COALESCE(ch.visibility='public',false)
		          OR EXISTS(
		            SELECT 1 FROM channel_members cm
		             WHERE cm.channel_id=p.channel_id
		               AND cm.user_id::text=$2
		          )
		      END
		  END
		  FROM posts p
		  JOIN profiles pr ON pr.user_id=p.author_user_id
		  LEFT JOIN channels ch ON ch.id=p.channel_id
		 WHERE p.id=$1 AND p.status='published'
	`,postID,viewerID).Scan(&allowed)
	if err!=nil {
		if strings.Contains(strings.ToLower(err.Error()),"no rows") {
			return false,false,nil
		}
		return false,false,err
	}
	return allowed,true,nil
}

func (s *Server) canViewReel(
	ctx context.Context,
	viewerID string,
	reelID string,
) (bool,bool,error) {
	var allowed bool
	err:=s.db.QueryRow(ctx,`
		SELECT
		  CASE
		    WHEN $2='' THEN
		      CASE
		        WHEN rl.channel_id IS NULL THEN NOT pr.private_account
		        ELSE COALESCE(ch.visibility='public',false)
		      END
		    ELSE
		      NOT EXISTS(
		        SELECT 1 FROM blocks b
		         WHERE (
		           b.blocker_user_id::text=$2 AND
		           b.blocked_user_id=rl.creator_user_id
		         ) OR (
		           b.blocker_user_id=rl.creator_user_id AND
		           b.blocked_user_id::text=$2
		         )
		      )
		      AND
		      CASE
		        WHEN rl.channel_id IS NULL THEN
		          rl.creator_user_id::text=$2
		          OR NOT pr.private_account
		          OR EXISTS(
		            SELECT 1 FROM user_follows uf
		             WHERE uf.follower_user_id::text=$2
		               AND uf.followed_user_id=rl.creator_user_id
		          )
		        ELSE
		          COALESCE(ch.visibility='public',false)
		          OR EXISTS(
		            SELECT 1 FROM channel_members cm
		             WHERE cm.channel_id=rl.channel_id
		               AND cm.user_id::text=$2
		          )
		      END
		  END
		  FROM reels rl
		  JOIN profiles pr ON pr.user_id=rl.creator_user_id
		  LEFT JOIN channels ch ON ch.id=rl.channel_id
		 WHERE rl.id=$1 AND rl.status='published'
	`,reelID,viewerID).Scan(&allowed)
	if err!=nil {
		if strings.Contains(strings.ToLower(err.Error()),"no rows") {
			return false,false,nil
		}
		return false,false,err
	}
	return allowed,true,nil
}

func (s *Server) storyAccess(
	ctx context.Context,
	viewerID string,
	storyID string,
) (string,bool,bool,error) {
	var authorID string
	var allowed bool
	err:=s.db.QueryRow(ctx,`
		SELECT st.author_user_id::text,
		       CASE
		         WHEN $2='' THEN
		           st.close_friends_only=false
		           AND CASE
		             WHEN st.channel_id IS NULL THEN NOT pr.private_account
		             ELSE COALESCE(ch.visibility='public',false)
		           END
		         ELSE
		           NOT EXISTS(
		             SELECT 1 FROM blocks b
		              WHERE (
		                b.blocker_user_id::text=$2 AND
		                b.blocked_user_id=st.author_user_id
		              ) OR (
		                b.blocker_user_id=st.author_user_id AND
		                b.blocked_user_id::text=$2
		              )
		           )
		           AND (
		             st.close_friends_only=false
		             OR st.author_user_id::text=$2
		             OR EXISTS(
		               SELECT 1 FROM close_friends cf
		                WHERE cf.owner_user_id=st.author_user_id
		                  AND cf.friend_user_id::text=$2
		             )
		           )
		           AND CASE
		             WHEN st.channel_id IS NULL THEN
		               st.author_user_id::text=$2
		               OR NOT pr.private_account
		               OR EXISTS(
		                 SELECT 1 FROM user_follows uf
		                  WHERE uf.follower_user_id::text=$2
		                    AND uf.followed_user_id=st.author_user_id
		               )
		             ELSE
		               COALESCE(ch.visibility='public',false)
		               OR EXISTS(
		                 SELECT 1 FROM channel_members cm
		                  WHERE cm.channel_id=st.channel_id
		                    AND cm.user_id::text=$2
		               )
		           END
		       END
		  FROM stories st
		  JOIN profiles pr ON pr.user_id=st.author_user_id
		  LEFT JOIN channels ch ON ch.id=st.channel_id
		 WHERE st.id=$1 AND st.expires_at>now()
	`,storyID,viewerID).Scan(&authorID,&allowed)
	if err!=nil {
		if strings.Contains(strings.ToLower(err.Error()),"no rows") {
			return "",false,false,nil
		}
		return "",false,false,err
	}
	return authorID,allowed,true,nil
}
