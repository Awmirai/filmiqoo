package server

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// These tests exercise the migrated trigger functions and HTTP projection together.
// They never apply migrations; CI must opt in with its migrated test database.
type communityNotificationFixture struct {
	t      *testing.T
	ctx    context.Context
	db     *pgxpool.Pool
	server *Server
	users  []string
}

func newCommunityNotificationFixture(t *testing.T) *communityNotificationFixture {
	t.Helper()
	if os.Getenv("FILMIQOO_E2E") != "1" {
		t.Skip("requires FILMIQOO_E2E=1 and a migrated test PostgreSQL database")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	db, err := pgxpool.New(ctx, os.Getenv("DATABASE_URL"))
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	f := &communityNotificationFixture{t: t, ctx: ctx, db: db, server: &Server{db: db}}
	t.Cleanup(func() {
		cleanupCtx, cleanupCancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cleanupCancel()
		if _, err := db.Exec(cleanupCtx, `DELETE FROM users WHERE id::text=ANY($1::text[])`, f.users); err != nil {
			t.Error("clean fixture users:", err)
		}
		db.Close()
		cancel()
	})
	return f
}

func (f *communityNotificationFixture) exec(sql string, args ...any) {
	f.t.Helper()
	if _, err := f.db.Exec(f.ctx, sql, args...); err != nil {
		f.t.Fatalf("fixture statement failed: %v", err)
	}
}

func (f *communityNotificationFixture) id(sql string, args ...any) string {
	f.t.Helper()
	var id string
	if err := f.db.QueryRow(f.ctx, sql, args...).Scan(&id); err != nil {
		f.t.Fatal(err)
	}
	return id
}

func (f *communityNotificationFixture) user(status string) string {
	f.t.Helper()
	id := f.id(`INSERT INTO users(status) VALUES ($1) RETURNING id::text`, status)
	f.users = append(f.users, id)
	f.exec(`INSERT INTO profiles(user_id,username,display_name) VALUES ($1,$2,'Notification test fan')`, id, "community-notification-"+id)
	return id
}

func (f *communityNotificationFixture) recipients(event, entity string, expected ...string) {
	f.t.Helper()
	rows, err := f.db.Query(f.ctx, `SELECT user_id::text FROM notifications WHERE notification_type=$1 AND entity_id=$2`, event, entity)
	if err != nil {
		f.t.Fatal(err)
	}
	defer rows.Close()
	actual := map[string]int{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			f.t.Fatal(err)
		}
		actual[id]++
	}
	if err := rows.Err(); err != nil {
		f.t.Fatal(err)
	}
	want := map[string]int{}
	for _, id := range expected {
		want[id]++
	}
	if !reflect.DeepEqual(want, actual) {
		f.t.Fatalf("%s recipients: got %v, want %v", event, actual, want)
	}
}

func TestCommunityPublicationNotificationsRespectAudienceAndEmitOnce(t *testing.T) {
	f := newCommunityNotificationFixture(t)
	author := f.user("active")
	accepted, defaultPreferences := f.user("active"), f.user("active")
	muted, blocker, blocked := f.user("active"), f.user("active"), f.user("active")
	mutedAuthor := f.user("active")
	pending, declined, stranger := f.user("active"), f.user("active"), f.user("active")
	inactive, channelOnly, privateMember := f.user("disabled"), f.user("active"), f.user("active")
	f.exec(`UPDATE profiles SET private_account=true WHERE user_id=$1`, author)
	for _, follower := range []string{accepted, defaultPreferences, muted, mutedAuthor, blocker, blocked, inactive} {
		f.exec(`INSERT INTO user_follows(follower_user_id,followed_user_id) VALUES ($1,$2)`, follower, author)
		f.exec(`INSERT INTO follow_requests(requester_user_id,target_user_id,status) VALUES ($1,$2,'accepted')`, follower, author)
	}
	f.exec(`INSERT INTO follow_requests(requester_user_id,target_user_id,status) VALUES ($1,$2,'pending'),($3,$2,'declined')`, pending, author, declined)
	f.exec(`INSERT INTO user_preferences(user_id,notifications_social) VALUES ($1,true),($2,false)`, accepted, muted)
	f.exec(`INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2),($2,$3)`, blocker, author, blocked)
	f.exec(`INSERT INTO user_mutes(muter_user_id,muted_user_id) VALUES ($1,$2)`, mutedAuthor, author)
	_ = stranger // An active user with neither a follow nor a channel subscription must receive nothing.

	post := f.id(`INSERT INTO posts(author_user_id,body,spoiler,status) VALUES ($1,'SPOILER_PUBLICATION_TOKEN',true,'draft') RETURNING id::text`, author)
	f.recipients("post_published", post)
	f.exec(`UPDATE posts SET status='published',published_at=now() WHERE id=$1`, post)
	f.recipients("post_published", post, accepted, defaultPreferences)
	// Editing a published item and retrying publication do not create more events.
	f.exec(`UPDATE posts SET body='SPOILER_EDIT_TOKEN' WHERE id=$1`, post)
	f.exec(`UPDATE posts SET status='published' WHERE id=$1`, post)
	f.exec(`UPDATE posts SET status='hidden' WHERE id=$1`, post)
	f.exec(`UPDATE posts SET status='published' WHERE id=$1`, post)
	f.recipients("post_published", post, accepted, defaultPreferences)

	reel := f.id(`INSERT INTO reels(creator_user_id,caption,spoiler,status,published_at) VALUES ($1,'SPOILER_REEL_TOKEN',true,'published',now()) RETURNING id::text`, author)
	f.recipients("reel_published", reel, accepted, defaultPreferences)
	f.exec(`UPDATE reels SET caption='SPOILER_REEL_EDIT_TOKEN',status='published' WHERE id=$1`, reel)
	f.exec(`UPDATE reels SET status='draft' WHERE id=$1`, reel)
	f.exec(`UPDATE reels SET status='published' WHERE id=$1`, reel)
	f.recipients("reel_published", reel, accepted, defaultPreferences)

	publicChannel := f.id(`INSERT INTO channels(owner_user_id,slug,name,visibility) VALUES ($1,$2,'Public test channel','public') RETURNING id::text`, author, "public-events-"+author)
	privateChannel := f.id(`INSERT INTO channels(owner_user_id,slug,name,visibility) VALUES ($1,$2,'Private test channel','private') RETURNING id::text`, author, "private-events-"+author)
	f.exec(`INSERT INTO channel_followers(channel_id,user_id) VALUES ($1,$2),($3,$2),($3,$4)`, publicChannel, channelOnly, privateChannel, privateMember)
	f.exec(`INSERT INTO channel_members(channel_id,user_id) VALUES ($1,$2)`, privateChannel, privateMember)
	publicPost := f.id(`INSERT INTO posts(author_user_id,channel_id,status) VALUES ($1,$2,'published') RETURNING id::text`, author, publicChannel)
	f.recipients("post_published", publicPost, accepted, defaultPreferences, channelOnly)
	privateReel := f.id(`INSERT INTO reels(creator_user_id,channel_id,status) VALUES ($1,$2,'published') RETURNING id::text`, author, privateChannel)
	f.recipients("reel_published", privateReel, privateMember)

	var leaked, missingOutbox int
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM notifications WHERE actor_user_id=$1 AND (title LIKE '%SPOILER_%' OR body LIKE '%SPOILER_%')`, author).Scan(&leaked); err != nil || leaked != 0 {
		t.Fatalf("publication preview leaked plot text: count=%d err=%v", leaked, err)
	}
	if err := f.db.QueryRow(f.ctx, `SELECT count(*) FROM notifications n LEFT JOIN push_outbox o ON o.notification_id=n.id WHERE n.actor_user_id=$1 AND o.id IS NULL`, author).Scan(&missingOutbox); err != nil || missingOutbox != 0 {
		t.Fatalf("notification missing delivery outbox: count=%d err=%v", missingOutbox, err)
	}
}

func (f *communityNotificationFixture) comment(user, scope, parent, title, body string, deleted bool) string {
	f.t.Helper()
	return f.id(`INSERT INTO title_comments(user_id,scope,parent_id,client_id,title_label,poster_path,body,spoiler,deleted)
		VALUES ($1,$2,NULLIF($3,'')::uuid,gen_random_uuid(),$4,'/real-test-poster.jpg',$5,true,$6) RETURNING id::text`, user, scope, parent, title, body, deleted)
}

func (f *communityNotificationFixture) notificationPage(user string) struct {
	Items  []map[string]any `json:"items"`
	Unread int              `json:"unread"`
} {
	f.t.Helper()
	r := httptest.NewRequest(http.MethodGet, "/v1/notifications", nil)
	r = r.WithContext(context.WithValue(f.ctx, userKey, user))
	w := httptest.NewRecorder()
	f.server.notifications(w, r)
	if w.Code != http.StatusOK {
		f.t.Fatalf("notifications status=%d body=%s", w.Code, w.Body)
	}
	var page struct {
		Items  []map[string]any `json:"items"`
		Unread int              `json:"unread"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &page); err != nil {
		f.t.Fatal(err)
	}
	return page
}

func TestCommunityDiscussionNotificationsAreDeduplicatedSpoilerSafeAndContextual(t *testing.T) {
	f := newCommunityNotificationFixture(t)
	alice, bob, carol := f.user("active"), f.user("active"), f.user("active")
	muted, blockedByAlice, blocksAlice := f.user("active"), f.user("active"), f.user("active")
	mutedByAlice := f.user("active")
	f.exec(`INSERT INTO user_preferences(user_id,notifications_social) VALUES ($1,false)`, muted)
	f.exec(`INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2),($3,$1)`, alice, blockedByAlice, blocksAlice)
	f.exec(`INSERT INTO user_mutes(muter_user_id,muted_user_id) VALUES ($1,$2)`, alice, mutedByAlice)
	movie := f.comment(alice, "movie:987650101", "", "Metadata-only Movie", "SPOILER_PARENT_TOKEN", false)
	series := f.comment(alice, "series:987650102:s2:e3", "", "Metadata-only Series", "SPOILER_SERIES_TOKEN", false)
	deleted := f.comment(alice, "movie:987650103", "", "Deleted title", "SPOILER_DELETED_TOKEN", true)
	mutedParent := f.comment(muted, "movie:987650104", "", "Muted title", "SPOILER_MUTED_TOKEN", false)

	firstReply := f.comment(bob, "movie:987650101", movie, "Metadata-only Movie", "SPOILER_REPLY_TOKEN", false)
	// A network retry reuses the client ID; each genuine new reply gets its own event.
	f.exec(`INSERT INTO title_comments(user_id,scope,parent_id,client_id,body) SELECT user_id,scope,parent_id,client_id,body FROM title_comments WHERE id=$1 ON CONFLICT(user_id,client_id) DO NOTHING`, firstReply)
	secondReply := f.comment(bob, "movie:987650101", movie, "Metadata-only Movie", "SPOILER_REPLY_SECOND_TOKEN", false)
	f.recipients("discussion_reply", firstReply, alice)
	f.recipients("discussion_reply", secondReply, alice)
	f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, movie, bob)
	f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING`, movie, bob)
	f.exec(`DELETE FROM title_comment_likes WHERE comment_id=$1 AND user_id=$2`, movie, bob)
	f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2)`, movie, bob)
	f.recipients("discussion_like", movie, alice)
	seriesReply := f.comment(carol, "series:987650102:s2:e3", series, "Metadata-only Series", "SPOILER_EPISODE_REPLY_TOKEN", false)
	f.recipients("discussion_reply", seriesReply, alice)

	// Self-interaction, either blocking direction, muted recipients and deleted parents emit no events.
	selfReply := f.comment(alice, "movie:987650101", movie, "Metadata-only Movie", "self reply", false)
	f.recipients("discussion_reply", selfReply)
	f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2)`, movie, alice)
	for _, actor := range []string{blockedByAlice, blocksAlice, mutedByAlice} {
		blockedReply := f.comment(actor, "movie:987650101", movie, "Metadata-only Movie", "blocked or muted reply", false)
		f.recipients("discussion_reply", blockedReply)
		f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2)`, movie, actor)
	}
	deletedReply := f.comment(bob, "movie:987650103", deleted, "Deleted title", "deleted reply", false)
	f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2)`, deleted, bob)
	mutedReply := f.comment(bob, "movie:987650104", mutedParent, "Muted title", "muted reply", false)
	f.exec(`INSERT INTO title_comment_likes(comment_id,user_id) VALUES ($1,$2)`, mutedParent, bob)
	f.recipients("discussion_reply", firstReply, alice)
	f.recipients("discussion_reply", secondReply, alice)
	f.recipients("discussion_like", movie, alice)
	f.recipients("discussion_reply", deletedReply)
	f.recipients("discussion_like", deleted)
	f.recipients("discussion_reply", mutedReply)
	f.recipients("discussion_like", mutedParent)

	page := f.notificationPage(alice)
	if len(page.Items) != 4 || page.Unread != 4 {
		t.Fatalf("expected 4 unread contextual events: %#v", page)
	}
	for _, item := range page.Items {
		preview := item["title"].(string) + item["body"].(string)
		if strings.Contains(preview, "SPOILER_") {
			t.Fatal("discussion preview leaked plot text", preview)
		}
		media, ok := item["media"].(map[string]any)
		if !ok || media["id"] != nil || media["mediaVersionId"] != nil {
			t.Fatalf("metadata-only context invented a catalog/playback identity: %#v", item)
		}
		if media["posterUrl"] != "/real-test-poster.jpg" {
			t.Fatalf("discussion poster context was lost: %#v", media)
		}
		if item["entityId"] == movie || item["entityId"] == firstReply || item["entityId"] == secondReply {
			if media["tmdbId"] != float64(987650101) || media["kind"] != "movie" || media["title"] != "Metadata-only Movie" {
				t.Fatalf("movie context unresolved: %#v", media)
			}
		} else if item["entityId"] == seriesReply {
			if media["tmdbId"] != float64(987650102) || media["kind"] != "series" || media["title"] != "Metadata-only Series" {
				t.Fatalf("episode-scoped series context unresolved: %#v", media)
			}
		} else {
			t.Fatalf("unexpected event target: %#v", item)
		}
	}
	// A block created after delivery must hide existing notifications, not just stop future inserts.
	f.exec(`INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2)`, alice, bob)
	page = f.notificationPage(alice)
	if len(page.Items) != 1 || page.Unread != 1 || page.Items[0]["entityId"] != seriesReply {
		t.Fatalf("newly blocked actor remained visible: %#v", page)
	}
	f.exec(`INSERT INTO blocks(blocker_user_id,blocked_user_id) VALUES ($1,$2)`, carol, alice)
	page = f.notificationPage(alice)
	if len(page.Items) != 0 || page.Unread != 0 {
		t.Fatalf("actor-side block bypassed notification filtering: %#v", page)
	}
}
