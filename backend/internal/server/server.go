package server

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
	"github.com/Awmirai/filmiqoo/backend/internal/objectstore"
	"github.com/Awmirai/filmiqoo/backend/internal/tmdb"
	"github.com/coder/websocket"
	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"github.com/golang-jwt/jwt/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

type Server struct {
	cfg   config.Config
	db    *pgxpool.Pool
	redis *redis.Client
	http  *http.Server
	upstreamClient *http.Client
	tmdb *tmdb.Client
	objects *objectstore.Store
	workersCancel context.CancelFunc
}

func New(cfg config.Config, db *pgxpool.Pool, redisClient *redis.Client) *Server {
	objects, _ := objectstore.New(
		cfg.ObjectStorageEndpoint,
		cfg.ObjectStoragePublicEndpoint,
		cfg.ObjectStorageKey,
		cfg.ObjectStorageSecret,
		cfg.ObjectStorageBucket,
	)
	s := &Server{
		cfg: cfg,
		db: db,
		redis: redisClient,
		tmdb: tmdb.New(cfg.TMDBToken),
		objects: objects,
		upstreamClient: &http.Client{
			Transport: &http.Transport{
				Proxy: http.ProxyFromEnvironment,
				MaxIdleConns: 100,
				MaxIdleConnsPerHost: 20,
				IdleConnTimeout: 90 * time.Second,
				ResponseHeaderTimeout: 20 * time.Second,
			},
		},
	}
	r := chi.NewRouter()
	r.Use(middleware.RequestID)
	r.Use(middleware.RealIP)
	r.Use(middleware.Recoverer)
	r.Use(s.securityHeaders)
	r.Use(s.limitJSONBody)
	r.Use(s.cors)

	r.Get("/healthz", s.health)
	r.Get("/readyz", s.ready)

	r.Route("/internal", func(r chi.Router) {
		r.Post("/telegram/ingest", s.telegramIngest)
		r.Get("/telegram/pending", s.pendingTelegramIngest)
		r.Post("/telegram/{id}/resolve", s.resolveTelegramIngestNow)
	})

	r.Route("/v1", func(r chi.Router) {
		r.Route("/auth", func(r chi.Router) {
			r.With(
				s.authRateLimit(
					"register",
					s.cfg.AuthRegisterRateLimit,
					time.Hour,
				),
			).Post("/register", s.register)
			r.With(
				s.authRateLimit(
					"login",
					s.cfg.AuthLoginRateLimit,
					time.Minute,
				),
			).Post("/login", s.login)
			r.With(
				s.authRateLimit(
					"refresh",
					s.cfg.AuthRefreshRateLimit,
					time.Minute,
				),
			).Post("/refresh", s.refresh)
			r.Post("/logout", s.logout)
		})

		r.Get("/catalog/home", s.catalogHome)
		r.Get("/releases", s.releaseCenter)
		r.Get("/catalog/{id}", s.catalogDetail)
		r.Get("/catalog/{id}/reviews", s.mediaReviews)
		r.Get("/search", s.universalSearch)
		r.Get("/social/reels", s.reels)
		r.Get("/social/reels/{id}", s.reelDetail)
		r.Get("/social/feed", s.socialFeed)
		r.Get("/social/stories", s.stories)
		r.Get("/social/channels", s.channels)
		r.Get("/social/collections", s.publicCollections)
		r.Get("/social/collections/{id}", s.publicCollectionDetail)
		r.Get("/social/channels/{id}", s.channelDetail)
		r.Get("/social/channels/{id}/posts", s.channelPosts)
		r.Get("/social/channels/{id}/reels", s.channelReels)
		r.Get("/social/channels/{id}/stories", s.channelStories)
		r.Get("/social/channels/{id}/members", s.channelMembers)
		r.Get("/social/channels/{id}/rooms", s.channelRooms)
			r.Get("/social/channels/{id}/manage", s.channelManageOverview)
			r.Get("/social/channels/{id}/manage/rooms", s.channelManageRooms)
		r.Get("/social/users/{id}", s.publicUserProfile)
		r.Get("/social/users/{id}/reputation", s.userReputation)
		r.Get("/social/users/{id}/posts", s.publicUserPosts)
		r.Get("/social/users/{id}/reels", s.publicUserReels)
		r.Get("/social/users/{id}/collections", s.publicUserCollections)
		r.Get("/social/posts/{id}/comments", s.postComments)
		r.Get("/social/posts/{id}/poll", s.postPoll)
		r.Get("/social/reels/{id}/comments", s.reelComments)
		r.Get("/rooms", s.roomsList)
		r.Get("/rooms/{id}/messages", s.roomMessages)
		r.Get("/watch-parties", s.watchParties)
		r.Get("/watch-parties/{id}", s.watchPartyDetail)
		r.Get("/live-events", s.liveEvents)
		r.Get("/live-events/{id}", s.liveEventDetail)
		r.Get("/realtime", s.realtime)
		r.Get("/playback/{versionID}", s.playback)
		r.Get("/media/{key}", s.mediaRedirect)

		r.Group(func(r chi.Router) {
			r.Use(s.auth)
			r.Use(
				s.authenticatedWriteRateLimit(
					s.cfg.AuthenticatedWriteRateLimit,
					time.Minute,
				),
			)
			r.Post("/social/reels", s.createReel)
			r.Post("/social/reels/{id}/like", s.toggleReelLike)
			r.Post("/social/reels/{id}/save", s.toggleReelSave)
			r.Post("/social/reels/{id}/view", s.markReelView)
			r.Post("/social/reels/{id}/playback-event", s.reelPlaybackEvent)
			r.Post("/social/reels/{id}/comments", s.addReelComment)
			r.Post("/social/posts", s.createPost)
			r.Get("/social/posts/saved", s.savedPosts)
			r.Get("/social/reels/saved", s.savedReels)
			r.Post("/social/posts/{id}/like", s.togglePostLike)
			r.Post("/social/posts/{id}/save", s.togglePostSave)
			r.Post("/social/posts/{id}/share", s.sharePost)
			r.Post("/social/posts/{id}/comments", s.addPostComment)
			r.Post("/social/posts/{id}/poll/vote", s.votePoll)
			r.Post("/social/channels", s.createChannel)
			r.Post("/social/channels/{id}/follow", s.toggleChannelFollow)
			r.Post("/social/channels/{id}/settings", s.updateChannelSettings)
			r.Post("/social/channels/{id}/members/{userID}/role", s.changeChannelMemberRole)
			r.Post("/social/channels/{id}/members/{userID}/remove", s.removeChannelMember)
			r.Post("/social/channels/{id}/rooms", s.createChannelRoom)
			r.Post("/social/channels/{id}/rooms/{roomID}/settings", s.updateChannelRoomSettings)
			r.Post("/social/stories", s.createStory)
			r.Post("/social/stories/{id}/view", s.markStoryView)
			r.Post("/social/stories/{id}/reaction", s.reactToStory)
			r.Post("/social/stories/{id}/reply", s.replyToStory)
			r.Post("/social/users/{id}/follow", s.toggleUserFollow)
			r.Get("/social/close-friends", s.closeFriends)
			r.Post("/social/close-friends/{userID}/toggle", s.toggleCloseFriend)
			r.Get("/social/collections/following", s.followedCollections)
			r.Post("/social/collections/{id}/follow", s.toggleCollectionFollow)
			r.Get("/social/follow-requests", s.incomingFollowRequests)
			r.Post("/social/follow-requests/{requesterID}/accept", s.acceptFollowRequest)
			r.Post("/social/follow-requests/{requesterID}/decline", s.declineFollowRequest)
			r.Post("/rooms", s.createRoom)
			r.Post("/rooms/{id}/messages", s.sendRoomMessage)
			r.Post("/rooms/{id}/messages/{messageID}/reaction", s.toggleMessageReaction)
			r.Post("/rooms/{id}/messages/{messageID}/edit", s.editRoomMessage)
			r.Post("/rooms/{id}/messages/{messageID}/delete", s.deleteRoomMessage)
			r.Post("/rooms/{id}/messages/{messageID}/pin", s.togglePinRoomMessage)
			r.Post("/rooms/{id}/messages/{messageID}/forward", s.forwardRoomMessage)
			r.Post("/rooms/{id}/messages/bulk-forward", s.bulkForwardRoomMessages)
			r.Post("/rooms/{id}/messages/bulk-delete", s.bulkDeleteRoomMessages)
			r.Get("/rooms/{id}/messages/search", s.searchRoomMessages)
			r.Get("/rooms/{id}/read-state", s.roomReadState)
			r.Get("/rooms/{id}/draft", s.roomDraft)
			r.Post("/rooms/{id}/draft", s.updateRoomDraft)
			r.Post("/rooms/{id}/draft/delete", s.deleteRoomDraft)
			r.Get("/rooms/{id}/scheduled", s.scheduledRoomMessages)
			r.Post("/rooms/{id}/scheduled", s.scheduleRoomMessage)
			r.Post("/rooms/{id}/scheduled/{scheduledID}/cancel", s.cancelScheduledRoomMessage)
			r.Get("/rooms/{id}/pins", s.pinnedRoomMessages)
			r.Get("/rooms/{id}/members", s.roomMembers)
			r.Get("/rooms/{id}/member-candidates", s.roomMemberCandidates)
			r.Post("/rooms/{id}/members/{userID}/add", s.addRoomMember)
			r.Post("/rooms/{id}/members/{userID}/role", s.updateRoomMemberRole)
			r.Post("/rooms/{id}/members/{userID}/remove", s.removeRoomMember)
			r.Post("/rooms/{id}/members/{userID}/transfer-owner", s.transferRoomOwnership)
			r.Get("/rooms/{id}/settings", s.roomConversationSettings)
			r.Post("/rooms/{id}/settings", s.updateRoomConversationSettings)
			r.Post("/rooms/{id}/preferences", s.updateRoomPreferences)
			r.Get("/rooms/{id}/invite", s.roomInviteInfo)
			r.Post("/rooms/{id}/invite/regenerate", s.regenerateRoomInvite)
			r.Post("/rooms/{id}/leave", s.leaveRoom)
			r.Post("/room-invites/{code}/join", s.joinRoomInvite)
			r.Post("/presence/heartbeat", s.presenceHeartbeat)
			r.Post("/watch-parties", s.createWatchParty)
			r.Post("/watch-parties/{id}/join", s.joinWatchParty)
			r.Post("/watch-parties/{id}/state", s.updateWatchPartyState)
			r.Get("/watch-parties/{id}/invite", s.watchPartyInviteInfo)
			r.Post("/watch-parties/{id}/invite/regenerate", s.regenerateWatchPartyInvite)
			r.Get("/watch-parties/{id}/reminder", s.watchPartyReminderStatus)
			r.Post("/watch-parties/{id}/reminder", s.toggleWatchPartyReminder)
			r.Get("/watch-parties/{id}/lobby", s.watchPartyLobby)
			r.Post("/watch-parties/{id}/join-request", s.requestWatchPartyJoin)
			r.Post("/watch-parties/{id}/join-requests/{userID}/resolve", s.resolveWatchPartyJoinRequest)
			r.Post("/watch-parties/{id}/ready", s.toggleWatchPartyReady)
			r.Post("/watch-parties/{id}/ready-check", s.setWatchPartyReadyCheck)
			r.Post("/watch-parties/{id}/members/{userID}/role", s.updateWatchPartyMemberRole)
			r.Post("/watch-parties/{id}/leave", s.leaveWatchParty)
			r.Get("/watch-parties/{id}/reactions", s.recentWatchPartyReactions)
			r.Post("/watch-parties/{id}/reactions", s.reactWatchParty)
			r.Get("/watch-parties/{id}/queue", s.watchPartyQueue)
			r.Post("/watch-parties/{id}/queue", s.addWatchPartyQueueItem)
			r.Post("/watch-parties/{id}/queue/{itemID}/vote", s.voteWatchPartyQueueItem)
			r.Post("/watch-parties/{id}/queue/{itemID}/play", s.playWatchPartyQueueItem)
			r.Post("/watch-parties/{id}/queue/{itemID}/remove", s.removeWatchPartyQueueItem)
			r.Get("/watch-parties/{id}/direct-invites", s.watchPartyDirectInvites)
			r.Post("/watch-parties/{id}/invite-user/{userID}", s.inviteUserToWatchParty)
			r.Post("/watch-parties/{id}/invite-response", s.respondWatchPartyInvite)
			r.Post("/live-events", s.createLiveEvent)
			r.Post("/live-events/{id}/join", s.joinLiveEvent)
			r.Post("/live-events/{id}/leave", s.leaveLiveEvent)
			r.Post("/live-events/{id}/heartbeat", s.liveHeartbeat)
			r.Post("/live-events/{id}/state", s.updateLiveEventState)
			r.Get("/inbox", s.inbox)
			r.Post("/dm/{userID}", s.ensureDM)
			r.Post("/rooms/{id}/read", s.markRoomRead)
			r.Get("/notifications", s.notifications)
			r.Get("/release-reminders", s.releaseReminders)
			r.Get("/series/calendar", s.seriesCalendar)
			r.Get("/series/{id}/subscription", s.seriesSubscriptionStatus)
			r.Post("/series/{id}/subscription", s.updateSeriesSubscription)
			r.Get("/home/personalized", s.personalizedHome)
			r.Get("/push/devices", s.pushDevices)
			r.Post("/push/devices", s.registerPushDevice)
			r.Post("/push/devices/{deviceID}/disable", s.unregisterPushDevice)
			r.Post("/release-reminders/toggle", s.toggleReleaseReminder)
			r.Post("/notifications/read-all", s.markAllNotificationsRead)
			r.Post("/notifications/{id}/read", s.markNotificationRead)
			r.Get("/realtime/rooms/{id}", s.roomRealtime)
			r.Post("/watch/progress", s.saveProgress)
			r.Post("/playback/sessions", s.startPlaybackSession)
			r.Post("/playback/sessions/{id}/heartbeat", s.heartbeatPlaybackSession)
			r.Post("/playback/sessions/{id}/end", s.endPlaybackSession)
			r.Post("/playback/devices/heartbeat", s.heartbeatPlaybackDevice)
			r.Get("/playback/devices", s.playbackDevices)
			r.Post("/playback/handoffs", s.createPlaybackHandoff)
			r.Get("/playback/handoffs/pending", s.pendingPlaybackHandoff)
			r.Post("/playback/handoffs/{id}/accept", s.acceptPlaybackHandoff)
			r.Post("/playback/handoffs/{id}/cancel", s.cancelPlaybackHandoff)
			r.Get("/watch/continue", s.continueWatching)
			r.Get("/watch/history", s.history)
			r.Get("/watch/series/{id}/progress", s.seriesProgress)
			r.Post("/watch/history/clear", s.clearHistory)
			r.Post("/watch/history/{versionID}/remove", s.removeHistoryItem)
			r.Post("/watch/episodes/{id}/status", s.setEpisodeWatchedStatus)
			r.Post("/watch/seasons/{id}/status", s.setSeasonWatchedStatus)
			r.Get("/library/favorites", s.favorites)
			r.Post("/library/favorites/{id}/toggle", s.toggleFavorite)
			r.Post("/catalog/{id}/reviews", s.upsertMediaReview)
			r.Get("/catalog/{id}/availability-alerts", s.availabilityAlertsStatus)
			r.Post("/catalog/{id}/availability-alerts", s.updateAvailabilityAlert)
			r.Post("/reviews/{id}/like", s.toggleReviewLike)
			r.Get("/library/watchlist", s.watchlist)
			r.Post("/library/watchlist/{id}/toggle", s.toggleWatchlist)
			r.Get("/library/collections", s.collections)
			r.Post("/library/collections", s.createCollection)
			r.Get("/library/collections/{id}", s.collectionDetail)
			r.Post("/library/collections/{id}/items/{mediaID}/toggle", s.toggleCollectionItem)
			r.Post("/library/collections/{id}/delete", s.deleteCollection)
			r.Get("/library/stats", s.libraryStats)
			r.Get("/library/scene-bookmarks", s.sceneBookmarks)
			r.Get("/profile/film-dna", s.filmDNA)
			r.Post("/playback/token", s.playbackToken)
			r.Get("/playback/{versionID}/context", s.playbackContext)
			r.Get("/playback/{versionID}/moments", s.playbackMoments)
			r.Get("/playback/{versionID}/bookmarks", s.playbackSceneBookmarks)
			r.Get("/playback/{versionID}/dialogue-search", s.dialogueSearch)
			r.Post("/playback/{versionID}/moments", s.createPlaybackMoment)
			r.Post("/playback/{versionID}/bookmarks", s.createSceneBookmark)
			r.Post("/playback/bookmarks/{id}", s.updateSceneBookmark)
			r.Post("/playback/bookmarks/{id}/delete", s.deleteSceneBookmark)
			r.Post("/playback/moments/{id}/like", s.togglePlaybackMomentLike)
			r.Post("/uploads/presign", s.presignUpload)
			r.Post("/uploads/{id}/complete", s.completeUpload)
			r.Get("/me", s.me)
			r.Get("/viewer-profiles", s.viewerProfiles)
			r.Post("/viewer-profiles", s.createViewerProfile)
			r.Post("/viewer-profiles/{id}", s.updateViewerProfile)
			r.Post("/viewer-profiles/{id}/pin", s.setViewerProfilePIN)
			r.Post("/viewer-profiles/{id}/unlock", s.unlockViewerProfile)
			r.Post("/viewer-profiles/{id}/delete", s.deleteViewerProfile)
			r.Get("/parental/status", s.parentalStatus)
			r.Post("/parental/pin", s.setParentalPIN)
			r.Post("/parental/verify", s.verifyParentalGate)
			r.Post("/me", s.updateProfile)
			r.Get("/creator/studio", s.creatorStudio)
			r.Get("/creator/analytics", s.creatorAnalyticsV3)
			r.Get("/creator/scheduled", s.scheduledCreatorContent)
			r.Post("/creator/scheduled/{kind}/{id}/publish-now", s.publishScheduledNow)
			r.Post("/creator/scheduled/{kind}/{id}/unschedule", s.unscheduleCreatorContent)
			r.Get("/settings", s.getSettings)
			r.Post("/settings", s.updateSettings)
			r.Get("/privacy/export", s.privacyExport)
			r.With(
				s.authRateLimit("delete-account",5,time.Hour),
			).Post("/privacy/delete-account", s.deleteAccount)
			r.Post("/security/sessions", s.securitySessions)
			r.Post("/security/sessions/revoke-others", s.revokeOtherSessions)
			r.Post("/security/sessions/{id}/revoke", s.revokeSession)
			r.Post("/moderation/report", s.submitReport)
			r.Get("/moderation/safety", s.safetyState)
			r.Post("/social/users/{id}/block", s.toggleUserBlock)
			r.Post("/social/users/{id}/mute", s.toggleUserMute)
			r.Post("/social/feedback", s.socialFeedback)
			r.Get("/social/feed/personalized", s.personalizedFeed)
			r.Get("/social/reels/personalized", s.personalizedReels)
			r.Get("/social/stories/personalized", s.personalizedStories)
			r.Get("/social/activity/following", s.followingWatchActivity)
			r.Get("/social/activity/following/feed", s.followingActivityFeed)
			r.Get("/social/following", s.followingUsers)
		})
	})

	s.http = &http.Server{
		Addr: cfg.HTTPAddr,
		Handler: r,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 60 * time.Second,
	}
	workerCtx,workerCancel:=context.WithCancel(context.Background())
	s.workersCancel=workerCancel
	go s.runRoomMessageScheduler(workerCtx)
	return s
}

func (s *Server) ListenAndServe() error {
	err := s.http.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) { return nil }
	return err
}

func (s *Server) Shutdown(ctx context.Context) error {
	if s.workersCancel!=nil { s.workersCancel() }
	return s.http.Shutdown(ctx)
}

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w,http.StatusOK,map[string]any{
		"service":"filmiqoo-api",
		"status":"ok",
		"version":s.cfg.BuildVersion,
		"commit":s.cfg.BuildCommit,
		"environment":s.cfg.Environment,
		"time":time.Now().UTC(),
	})
}

func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.db.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status":"postgres unavailable"})
		return
	}
	if err := s.redis.Ping(ctx).Err(); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status":"redis unavailable"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status":"ready"})
}

func (s *Server) catalogHome(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.overview,mt.year,
		       mt.poster_url,mt.backdrop_url,mt.rating,
		       mv.id::text,mv.quality_label,mv.stream_ready
		  FROM media_titles mt
		  LEFT JOIN LATERAL (
			SELECT id,quality_label,stream_ready
			  FROM media_versions
			 WHERE media_title_id=mt.id
			 ORDER BY preferred DESC,height DESC,file_size_bytes DESC
			 LIMIT 1
		  ) mv ON true
		 WHERE mt.visibility='public'
		 ORDER BY mt.created_at DESC
		 LIMIT 60
	`)
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	defer rows.Close()

	items := make([]map[string]any, 0)
	for rows.Next() {
		var id,kind,title,originalTitle,overview,posterURL,backdropURL string
		var tmdbID *int64
		var year int
		var rating *float64
		var versionID,quality *string
		var ready *bool
		if err := rows.Scan(&id,&tmdbID,&kind,&title,&originalTitle,&overview,&year,&posterURL,&backdropURL,&rating,
			&versionID,&quality,&ready); err != nil {
			writeError(w, http.StatusInternalServerError, err); return
		}
		items = append(items, map[string]any{
			"id":id,"tmdbId":tmdbID,"kind":kind,"title":title,"originalTitle":originalTitle,
			"overview":overview,"year":year,"posterUrl":posterURL,"backdropUrl":backdropURL,
			"rating":rating,"mediaVersionId":versionID,"quality":quality,"streamReady":ready,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items":items})
}

func (s *Server) reels(w http.ResponseWriter, r *http.Request) {
	_ = s.processScheduledContent(r.Context())
	rows, err := s.db.Query(r.Context(), `
		SELECT r.id::text,r.caption,r.playback_url,r.cover_url,r.duration_ms,
		       r.like_count,r.comment_count,r.save_count,r.share_count,r.view_count,r.spoiler,
		       p.user_id::text,p.display_name,p.username::text,p.avatar_url,p.verified,
		       mt.id::text,mt.tmdb_id,mt.kind,mt.title,mt.original_title,mt.poster_url,mt.backdrop_url,
		       mt.year,mt.rating
		  FROM reels r
		  JOIN profiles p ON p.user_id=r.creator_user_id
		  LEFT JOIN media_titles mt ON mt.id=r.media_title_id
		 WHERE r.status='published'
		 ORDER BY r.published_at DESC NULLS LAST,r.created_at DESC
		 LIMIT 50
	`)
	if err != nil { writeError(w,http.StatusInternalServerError,err); return }
	defer rows.Close()

	items:=make([]map[string]any,0)
	for rows.Next() {
		var id,caption,playbackURL,coverURL,authorID,displayName,username,avatar string
		var duration int
		var likes,comments,saves,shares,views int64
		var spoiler,verified bool
		var mediaID,kind,title,originalTitle,poster,backdrop *string
		var tmdbID *int64
		var year *int
		var rating *float64
		if err:=rows.Scan(
			&id,&caption,&playbackURL,&coverURL,&duration,
			&likes,&comments,&saves,&shares,&views,&spoiler,
			&authorID,&displayName,&username,&avatar,&verified,
			&mediaID,&tmdbID,&kind,&title,&originalTitle,&poster,&backdrop,&year,&rating,
		); err!=nil {
			continue
		}
		items=append(items,map[string]any{
			"id":id,"caption":caption,"playbackUrl":playbackURL,"coverUrl":coverURL,
			"durationMs":duration,"likes":likes,"comments":comments,"saves":saves,
			"shares":shares,"views":views,"spoiler":spoiler,
			"author":map[string]any{
				"id":authorID,"displayName":displayName,"username":username,
				"avatarUrl":avatar,"verified":verified,
			},
			"media":map[string]any{
				"id":mediaID,"tmdbId":tmdbID,"kind":kind,"title":title,
				"originalTitle":originalTitle,"posterUrl":poster,"backdropUrl":backdrop,
				"year":year,"rating":rating,
			},
		})
	}
	writeJSON(w,http.StatusOK,map[string]any{"items":items,"nextCursor":nil})
}

func (s *Server) channels(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(),
		"SELECT id::text, slug, name, bio, avatar_url, cover_url, follower_count, verified " +
		"FROM channels WHERE visibility='public' ORDER BY follower_count DESC, created_at DESC LIMIT 30")
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	defer rows.Close()

	items := make([]map[string]any, 0)
	for rows.Next() {
		var id, slug, name, bio, avatarURL, coverURL string
		var followers int64
		var verified bool
		if err := rows.Scan(&id,&slug,&name,&bio,&avatarURL,&coverURL,&followers,&verified); err != nil {
			writeError(w, http.StatusInternalServerError, err); return
		}
		items = append(items, map[string]any{
			"id":id,"slug":slug,"name":name,"bio":bio,"avatarUrl":avatarURL,
			"coverUrl":coverURL,"followers":followers,"verified":verified,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items":items})
}

func (s *Server) realtime(w http.ResponseWriter, r *http.Request) {
	conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{OriginPatterns:[]string{"*"}})
	if err != nil { return }
	defer conn.CloseNow()

	_ = conn.Write(r.Context(), websocket.MessageText, []byte("{\"type\":\"connected\",\"service\":\"filmiqoo-realtime\"}"))
	for {
		typ, data, err := conn.Read(r.Context())
		if err != nil { return }
		if typ != websocket.MessageText { continue }
		if err := conn.Write(r.Context(), websocket.MessageText, data); err != nil { return }
	}
}

func (s *Server) likeReel(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())
	reelID := chi.URLParam(r, "id")
	_, err := s.db.Exec(r.Context(),
		"INSERT INTO reel_likes (reel_id,user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING",
		reelID, userID)
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	writeJSON(w, http.StatusOK, map[string]any{"liked":true})
}

func (s *Server) followChannel(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())
	channelID := chi.URLParam(r, "id")
	_, err := s.db.Exec(r.Context(),
		"INSERT INTO channel_followers (channel_id,user_id) VALUES ($1,$2) ON CONFLICT DO NOTHING",
		channelID, userID)
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	writeJSON(w, http.StatusOK, map[string]any{"following":true})
}

func (s *Server) saveProgress(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())
	var body struct {
		MediaVersionID string
		PositionMS int64
		DurationMS int64
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, err); return
	}
	completed := body.DurationMS > 0 && float64(body.PositionMS)/float64(body.DurationMS) >= 0.95
	viewerID:=s.viewerProfileID(r,userID)
	var err error
	if viewerID!="" {
		_,err=s.db.Exec(r.Context(),`
			INSERT INTO viewer_watch_progress (
				viewer_profile_id,media_version_id,position_ms,duration_ms,completed,updated_at
			) VALUES ($1,$2,$3,$4,$5,now())
			ON CONFLICT (viewer_profile_id,media_version_id)
			DO UPDATE SET
				position_ms=EXCLUDED.position_ms,
				duration_ms=EXCLUDED.duration_ms,
				completed=EXCLUDED.completed,
				updated_at=now()
		`,viewerID,body.MediaVersionID,body.PositionMS,body.DurationMS,completed)
	} else {
		_,err=s.db.Exec(r.Context(),
			"INSERT INTO watch_progress (user_id,media_version_id,position_ms,duration_ms,completed,updated_at) " +
			"VALUES ($1,$2,$3,$4,$5,now()) ON CONFLICT (user_id,media_version_id) DO UPDATE SET " +
			"position_ms=EXCLUDED.position_ms,duration_ms=EXCLUDED.duration_ms,completed=EXCLUDED.completed,updated_at=now()",
			userID, body.MediaVersionID, body.PositionMS, body.DurationMS, completed)
	}
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	writeJSON(w, http.StatusOK, map[string]any{"saved":true,"viewerProfileId":viewerID})
}

type ctxKey string
const userKey ctxKey = "userID"

func (s *Server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		header := strings.TrimSpace(r.Header.Get("Authorization"))
		if !strings.HasPrefix(header, "Bearer ") {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"missing bearer token"}); return
		}
		tokenString := strings.TrimSpace(strings.TrimPrefix(header, "Bearer "))
		token, err := jwt.Parse(
			tokenString,
			func(t *jwt.Token) (any,error) {
				if t.Method.Alg()!=jwt.SigningMethodHS256.Alg() {
					return nil,errors.New("unexpected signing method")
				}
				return []byte(s.cfg.JWTSecret),nil
			},
			jwt.WithValidMethods([]string{jwt.SigningMethodHS256.Alg()}),
			jwt.WithIssuer("filmiqoo"),
			jwt.WithAudience("filmiqoo-android"),
		)
		if err != nil || !token.Valid {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid token"}); return
		}
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid claims"}); return
		}
		sub, err := claims.GetSubject()
		if err != nil || sub == "" {
			writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"missing subject"})
			return
		}
		if s.redis!=nil {
			blocked,redisErr:=s.redis.Exists(
				r.Context(),
				"auth:blocked-user:"+sub,
			).Result()
			if redisErr==nil && blocked>0 {
				writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"account is unavailable"})
				return
			}
		}
		next.ServeHTTP(
			w,
			r.WithContext(context.WithValue(r.Context(),userKey,sub)),
		)
	})
}

func userIDFromContext(ctx context.Context) string {
	v, _ := ctx.Value(userKey).(string)
	return v
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter,status int,err error) {
	if err==nil {
		err=errors.New(http.StatusText(status))
	}
	if status>=http.StatusInternalServerError {
		log.Printf("internal server error: %v",err)
		writeJSON(w,status,map[string]string{"error":"internal server error"})
		return
	}
	writeJSON(w,status,map[string]string{"error":err.Error()})
}
