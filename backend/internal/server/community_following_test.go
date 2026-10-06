package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Verify the same production query used by the community, including privacy and block rules.
func TestCommunityFollowingFeedUsesRealRelationships(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated PostgreSQL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{db: db}
	users := make([]string, 0, 4)
	defer func() {
		for _, id := range users {
			db.Exec(context.Background(), "DELETE FROM users WHERE id=$1", id)
		}
	}()
	newUser := func(private bool) string {
		t.Helper()
		var id string
		if err := db.QueryRow(ctx, `INSERT INTO users(status) VALUES('active') RETURNING id::text`).Scan(&id); err != nil {
			t.Fatal(err)
		}
		users = append(users, id)
		if _, err := db.Exec(ctx, `INSERT INTO profiles(user_id,username,display_name,private_account) VALUES($1,$2,'Film fan',$3)`, id, "community-"+id, private); err != nil {
			t.Fatal(err)
		}
		return id
	}
	viewer, followed, outsider, private := newUser(false), newUser(false), newUser(false), newUser(true)
	newPost := func(author, channel string) string {
		t.Helper()
		var id string
		if err := db.QueryRow(ctx, `INSERT INTO posts(author_user_id,channel_id,body,published_at) VALUES($1,NULLIF($2,'')::uuid,'Community fixture',now()) RETURNING id::text`, author, channel).Scan(&id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	followedPost, outsiderPost, privatePost := newPost(followed, ""), newPost(outsider, ""), newPost(private, "")
	var channel string
	if err := db.QueryRow(ctx, `INSERT INTO channels(owner_user_id,slug,name,visibility) VALUES($1,$2,'Public film club','public') RETURNING id::text`, outsider, "club-"+outsider).Scan(&channel); err != nil {
		t.Fatal(err)
	}
	channelPost := newPost(outsider, channel)
	if _, err := db.Exec(ctx, `INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES($1,$2),($1,$3)`, viewer, followed, private); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(ctx, `INSERT INTO channel_followers(channel_id,user_id) VALUES($1,$2)`, channel, viewer); err != nil {
		t.Fatal(err)
	}
	feed := func(query string) map[string]bool {
		t.Helper()
		r := httptest.NewRequest("GET", "/v1/social/feed/personalized?limit=50"+query, nil)
		w := httptest.NewRecorder()
		s.personalizedFeed(w, r.WithContext(context.WithValue(ctx, userKey, viewer)))
		if w.Code != 200 {
			t.Fatalf("feed %d %s", w.Code, w.Body)
		}
		var result struct {
			Items []struct {
				ID string `json:"id"`
			} `json:"items"`
		}
		if err := json.Unmarshal(w.Body.Bytes(), &result); err != nil {
			t.Fatal(err)
		}
		ids := map[string]bool{}
		for _, item := range result.Items {
			ids[item.ID] = true
		}
		return ids
	}
	ids := feed("&scope=following")
	if !ids[followedPost] || !ids[privatePost] || !ids[channelPost] || ids[outsiderPost] {
		t.Fatal("following feed included unrelated posts or lost authorized followed content", ids)
	}
	if _, err := db.Exec(ctx, `INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES($1,$2)`, followed, viewer); err != nil {
		t.Fatal(err)
	}
	if feed("&scope=following")[followedPost] {
		t.Fatal("reverse block leaked a followed post")
	}
	if _, err := db.Exec(ctx, `DELETE FROM user_follows WHERE follower_user_id=$1 AND followed_user_id=$2`, viewer, private); err != nil {
		t.Fatal(err)
	}
	ids = feed("")
	if ids[privatePost] || ids[followedPost] || !ids[outsiderPost] {
		t.Fatal("discovery ignored privacy/block rules or lost public content", ids)
	}
	if _, err := db.Exec(ctx, `DELETE FROM channel_followers WHERE channel_id=$1 AND user_id=$2`, channel, viewer); err != nil {
		t.Fatal(err)
	}
	if feed("&scope=following")[channelPost] {
		t.Fatal("unfollowed channel remained in following feed")
	}
}

func TestCommunityRepliesRespectPrivatePostAndParentIdentity(t *testing.T) {
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires migrated PostgreSQL")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	s := &Server{db: db}
	users := []string{}
	defer func() {
		for _, id := range users {
			db.Exec(context.Background(), "DELETE FROM users WHERE id=$1", id)
		}
	}()
	user := func(private bool) string {
		var id string
		if err := db.QueryRow(ctx, `INSERT INTO users(status) VALUES('active') RETURNING id::text`).Scan(&id); err != nil {
			t.Fatal(err)
		}
		users = append(users, id)
		if _, err := db.Exec(ctx, `INSERT INTO profiles(user_id,username,display_name,private_account) VALUES($1,$2,'Reply fan',$3)`, id, "reply-"+id, private); err != nil {
			t.Fatal(err)
		}
		return id
	}
	owner, follower, outsider := user(true), user(false), user(false)
	if _, err := db.Exec(ctx, `INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES($1,$2)`, follower, owner); err != nil {
		t.Fatal(err)
	}
	post := func(author string) string {
		var id string
		if err := db.QueryRow(ctx, `INSERT INTO posts(author_user_id,body,published_at) VALUES($1,'Private post',now()) RETURNING id::text`, author).Scan(&id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	privatePost, otherPost := post(owner), post(outsider)
	comment := func(postID string) string {
		var id string
		if err := db.QueryRow(ctx, `INSERT INTO comments(post_id,author_user_id,body) VALUES($1,$2,'Private reply') RETURNING id::text`, postID, owner).Scan(&id); err != nil {
			t.Fatal(err)
		}
		return id
	}
	privateComment, foreignComment := comment(privatePost), comment(otherPost)
	call := func(handler http.HandlerFunc, who, id, body string) *httptest.ResponseRecorder {
		r := httptest.NewRequest("POST", "/test", strings.NewReader(body))
		rc := chi.NewRouteContext()
		rc.URLParams.Add("id", id)
		c := context.WithValue(ctx, chi.RouteCtxKey, rc)
		c = context.WithValue(c, userKey, who)
		w := httptest.NewRecorder()
		handler(w, r.WithContext(c))
		return w
	}
	if w := call(s.postComments, "", privatePost, ""); w.Code != 404 {
		t.Fatalf("public comments leaked private post: %d %s", w.Code, w.Body)
	}
	if w := call(s.viewerPostComments, outsider, privatePost, ""); w.Code != 404 {
		t.Fatalf("outsider saw private comments: %d %s", w.Code, w.Body)
	}
	if w := call(s.viewerPostComments, follower, privatePost, ""); w.Code != 200 || !strings.Contains(w.Body.String(), privateComment) {
		t.Fatalf("accepted follower lost comments: %d %s", w.Code, w.Body)
	}
	if w := call(s.addPostComment, outsider, privatePost, `{"body":"unauthorized"}`); w.Code != 404 {
		t.Fatalf("outsider replied: %d %s", w.Code, w.Body)
	}
	if w := call(s.addPostComment, follower, privatePost, `{"body":"wrong parent","parentCommentId":"`+foreignComment+`"}`); w.Code != 400 {
		t.Fatalf("cross-post parent accepted: %d %s", w.Code, w.Body)
	}
	if w := call(s.addPostComment, follower, privatePost, `{"body":"valid reply","parentCommentId":"`+privateComment+`"}`); w.Code != 201 {
		t.Fatalf("valid threaded reply failed: %d %s", w.Code, w.Body)
	}
	if w := call(s.toggleCommentLike, outsider, privateComment, ""); w.Code != 404 {
		t.Fatalf("outsider liked private comment: %d %s", w.Code, w.Body)
	}
	if w := call(s.toggleCommentLike, follower, privateComment, ""); w.Code != 200 {
		t.Fatalf("follower like failed: %d %s", w.Code, w.Body)
	}
	if _, err := db.Exec(ctx, `INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES($1,$2)`, owner, follower); err != nil {
		t.Fatal(err)
	}
	if w := call(s.viewerPostComments, follower, privatePost, ""); w.Code != 404 {
		t.Fatalf("reverse block leaked comments: %d %s", w.Code, w.Body)
	}
	if w := call(s.toggleCommentLike, follower, privateComment, ""); w.Code != 404 {
		t.Fatalf("reverse block allowed like: %d %s", w.Code, w.Body)
	}
	if w := call(s.addPostComment, follower, privatePost, `{"body":"blocked"}`); w.Code != 404 {
		t.Fatalf("reverse block allowed reply: %d %s", w.Code, w.Body)
	}
	if w := call(s.postComments, "", "not-a-uuid", ""); w.Code != 400 {
		t.Fatalf("invalid ID response %d", w.Code)
	}
}
