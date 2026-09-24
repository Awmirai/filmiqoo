package server

import (
	"context"
	"encoding/json"
	"errors"
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
	r.Use(cors)

	r.Get("/healthz", s.health)
	r.Get("/readyz", s.ready)

	r.Route("/internal", func(r chi.Router) {
		r.Post("/telegram/ingest", s.telegramIngest)
		r.Get("/telegram/pending", s.pendingTelegramIngest)
		r.Post("/telegram/{id}/resolve", s.resolveTelegramIngestNow)
	})

	r.Route("/v1", func(r chi.Router) {
		r.Route("/auth", func(r chi.Router) {
			r.Post("/register", s.register)
			r.Post("/login", s.login)
			r.Post("/refresh", s.refresh)
			r.Post("/logout", s.logout)
		})

		r.Get("/catalog/home", s.catalogHome)
		r.Get("/releases", s.releaseCenter)
		r.Get("/catalog/{id}", s.catalogDetail)
		r.Get("/catalog/{id}/reviews", s.mediaReviews)
		r.Get("/search", s.universalSearch)
		r.Get("/social/reels", s.reels)
		r.Get("/social/feed", s.socialFeed)
		r.Get("/social/stories", s.stories)
		r.Get("/social/channels", s.channels)
		r.Get("/social/channels/{id}", s.channelDetail)
		r.Get("/social/channels/{id}/posts", s.channelPosts)
		r.Get("/social/channels/{id}/reels", s.channelReels)
		r.Get("/social/channels/{id}/stories", s.channelStories)
		r.Get("/social/channels/{id}/members", s.channelMembers)
		r.Get("/social/channels/{id}/rooms", s.channelRooms)
		r.Get("/social/users/{id}", s.publicUserProfile)
		r.Get("/social/users/{id}/posts", s.publicUserPosts)
		r.Get("/social/users/{id}/reels", s.publicUserReels)
		r.Get("/social/posts/{id}/comments", s.postComments)
		r.Get("/social/posts/{id}/poll", s.postPoll)
		r.Get("/social/reels/{id}/comments", s.reelComments)
		r.Get("/rooms", s.roomsList)
		r.Get("/rooms/{id}/messages", s.roomMessages)
		r.Get("/watch-parties", s.watchParties)
		r.Get("/watch-parties/{id}", s.watchPartyDetail)
		r.Get("/realtime", s.realtime)
		r.Get("/playback/{versionID}", s.playback)
		r.Get("/media/{key}", s.mediaRedirect)

		r.Group(func(r chi.Router) {
			r.Use(s.auth)
			r.Post("/social/reels", s.createReel)
			r.Post("/social/reels/{id}/like", s.toggleReelLike)
			r.Post("/social/reels/{id}/save", s.toggleReelSave)
			r.Post("/social/reels/{id}/view", s.markReelView)
			r.Post("/social/reels/{id}/comments", s.addReelComment)
			r.Post("/social/posts", s.createPost)
			r.Post("/social/posts/{id}/like", s.togglePostLike)
			r.Post("/social/posts/{id}/comments", s.addPostComment)
			r.Post("/social/posts/{id}/poll/vote", s.votePoll)
			r.Post("/social/channels", s.createChannel)
			r.Post("/social/channels/{id}/follow", s.toggleChannelFollow)
			r.Post("/social/stories", s.createStory)
			r.Post("/social/stories/{id}/view", s.markStoryView)
			r.Post("/social/stories/{id}/reaction", s.reactToStory)
			r.Post("/social/stories/{id}/reply", s.replyToStory)
			r.Post("/social/users/{id}/follow", s.toggleUserFollow)
			r.Post("/rooms", s.createRoom)
			r.Post("/rooms/{id}/messages", s.sendRoomMessage)
			r.Post("/rooms/{id}/messages/{messageID}/reaction", s.toggleMessageReaction)
			r.Post("/watch-parties", s.createWatchParty)
			r.Post("/watch-parties/{id}/join", s.joinWatchParty)
			r.Post("/watch-parties/{id}/state", s.updateWatchPartyState)
			r.Get("/inbox", s.inbox)
			r.Post("/dm/{userID}", s.ensureDM)
			r.Post("/rooms/{id}/read", s.markRoomRead)
			r.Get("/notifications", s.notifications)
			r.Get("/release-reminders", s.releaseReminders)
			r.Get("/home/personalized", s.personalizedHome)
			r.Get("/push/devices", s.pushDevices)
			r.Post("/push/devices", s.registerPushDevice)
			r.Post("/push/devices/{deviceID}/disable", s.unregisterPushDevice)
			r.Post("/release-reminders/toggle", s.toggleReleaseReminder)
			r.Post("/notifications/read-all", s.markAllNotificationsRead)
			r.Post("/notifications/{id}/read", s.markNotificationRead)
			r.Get("/realtime/rooms/{id}", s.roomRealtime)
			r.Post("/watch/progress", s.saveProgress)
			r.Get("/watch/continue", s.continueWatching)
			r.Get("/watch/history", s.history)
			r.Post("/watch/history/clear", s.clearHistory)
			r.Post("/watch/history/{versionID}/remove", s.removeHistoryItem)
			r.Get("/library/favorites", s.favorites)
			r.Post("/library/favorites/{id}/toggle", s.toggleFavorite)
			r.Post("/catalog/{id}/reviews", s.upsertMediaReview)
			r.Post("/reviews/{id}/like", s.toggleReviewLike)
			r.Get("/library/watchlist", s.watchlist)
			r.Post("/library/watchlist/{id}/toggle", s.toggleWatchlist)
			r.Get("/library/collections", s.collections)
			r.Post("/library/collections", s.createCollection)
			r.Get("/library/collections/{id}", s.collectionDetail)
			r.Post("/library/collections/{id}/items/{mediaID}/toggle", s.toggleCollectionItem)
			r.Post("/library/collections/{id}/delete", s.deleteCollection)
			r.Get("/library/stats", s.libraryStats)
			r.Post("/playback/token", s.playbackToken)
			r.Post("/uploads/presign", s.presignUpload)
			r.Post("/uploads/{id}/complete", s.completeUpload)
			r.Get("/me", s.me)
			r.Post("/me", s.updateProfile)
			r.Get("/creator/studio", s.creatorStudio)
			r.Get("/settings", s.getSettings)
			r.Post("/settings", s.updateSettings)
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
		})
	})

	s.http = &http.Server{
		Addr: cfg.HTTPAddr,
		Handler: r,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 60 * time.Second,
	}
	return s
}

func (s *Server) ListenAndServe() error {
	err := s.http.ListenAndServe()
	if errors.Is(err, http.ErrServerClosed) { return nil }
	return err
}

func (s *Server) Shutdown(ctx context.Context) error { return s.http.Shutdown(ctx) }

func (s *Server) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"service": "filmiqoo-api",
		"status": "ok",
		"version": "platform-core-v1",
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
	_, err := s.db.Exec(r.Context(),
		"INSERT INTO watch_progress (user_id,media_version_id,position_ms,duration_ms,completed,updated_at) " +
		"VALUES ($1,$2,$3,$4,$5,now()) ON CONFLICT (user_id,media_version_id) DO UPDATE SET " +
		"position_ms=EXCLUDED.position_ms,duration_ms=EXCLUDED.duration_ms,completed=EXCLUDED.completed,updated_at=now()",
		userID, body.MediaVersionID, body.PositionMS, body.DurationMS, completed)
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	writeJSON(w, http.StatusOK, map[string]any{"saved":true})
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
		token, err := jwt.Parse(tokenString, func(t *jwt.Token) (any,error) {
			if t.Method.Alg() != jwt.SigningMethodHS256.Alg() { return nil, errors.New("unexpected signing method") }
			return []byte(s.cfg.JWTSecret), nil
		})
		if err != nil || !token.Valid {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid token"}); return
		}
		claims, ok := token.Claims.(jwt.MapClaims)
		if !ok {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"invalid claims"}); return
		}
		sub, err := claims.GetSubject()
		if err != nil || sub == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error":"missing subject"}); return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey, sub)))
	})
}

func userIDFromContext(ctx context.Context) string {
	v, _ := ctx.Value(userKey).(string)
	return v
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Filmiqoo-Ingest-Secret")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS")
		if r.Method == http.MethodOptions { w.WriteHeader(http.StatusNoContent); return }
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error":err.Error()})
}
