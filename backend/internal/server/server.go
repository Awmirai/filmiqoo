package server

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/Awmirai/filmiqoo/backend/internal/config"
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
}

func New(cfg config.Config, db *pgxpool.Pool, redisClient *redis.Client) *Server {
	s := &Server{
		cfg: cfg,
		db: db,
		redis: redisClient,
		tmdb: tmdb.New(cfg.TMDBToken),
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
		r.Get("/catalog/{id}", s.catalogDetail)
		r.Get("/social/reels", s.reels)
		r.Get("/social/feed", s.socialFeed)
		r.Get("/social/stories", s.stories)
		r.Get("/social/channels", s.channels)
		r.Get("/social/channels/{id}", s.channelDetail)
		r.Get("/social/channels/{id}/posts", s.channelPosts)
		r.Get("/social/posts/{id}/comments", s.postComments)
		r.Get("/social/reels/{id}/comments", s.reelComments)
		r.Get("/rooms/{id}/messages", s.roomMessages)
		r.Get("/realtime", s.realtime)
		r.Get("/playback/{versionID}", s.playback)

		r.Group(func(r chi.Router) {
			r.Use(s.auth)
			r.Post("/social/reels/{id}/like", s.toggleReelLike)
			r.Post("/social/reels/{id}/save", s.toggleReelSave)
			r.Post("/social/reels/{id}/view", s.markReelView)
			r.Post("/social/reels/{id}/comments", s.addReelComment)
			r.Post("/social/posts", s.createPost)
			r.Post("/social/posts/{id}/like", s.togglePostLike)
			r.Post("/social/posts/{id}/comments", s.addPostComment)
			r.Post("/social/channels", s.createChannel)
			r.Post("/social/channels/{id}/follow", s.toggleChannelFollow)
			r.Post("/social/stories", s.createStory)
			r.Post("/social/stories/{id}/view", s.markStoryView)
			r.Post("/social/users/{id}/follow", s.toggleUserFollow)
			r.Post("/rooms", s.createRoom)
			r.Post("/rooms/{id}/messages", s.sendRoomMessage)
			r.Get("/realtime/rooms/{id}", s.roomRealtime)
			r.Post("/watch/progress", s.saveProgress)
			r.Post("/playback/token", s.playbackToken)
			r.Get("/me", s.me)
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
	rows, err := s.db.Query(r.Context(),
		"SELECT r.id::text, r.caption, r.playback_url, r.cover_url, r.duration_ms, " +
		"p.display_name, p.username, r.like_count, r.comment_count, r.view_count " +
		"FROM reels r JOIN profiles p ON p.user_id=r.creator_user_id " +
		"WHERE r.status='published' ORDER BY r.published_at DESC NULLS LAST, r.created_at DESC LIMIT 30")
	if err != nil { writeError(w, http.StatusInternalServerError, err); return }
	defer rows.Close()

	items := make([]map[string]any, 0)
	for rows.Next() {
		var id, caption, playbackURL, coverURL, displayName, username string
		var durationMS int
		var likes, comments, views int64
		if err := rows.Scan(&id,&caption,&playbackURL,&coverURL,&durationMS,&displayName,&username,&likes,&comments,&views); err != nil {
			writeError(w, http.StatusInternalServerError, err); return
		}
		items = append(items, map[string]any{
			"id":id,"caption":caption,"playbackUrl":playbackURL,"coverUrl":coverURL,
			"durationMs":durationMS,"displayName":displayName,"username":username,
			"likes":likes,"comments":comments,"views":views,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"items":items,"nextCursor":nil})
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
	_, err := s.db.Exec(r.Context(),
		"INSERT INTO watch_progress (user_id,media_version_id,position_ms,duration_ms,updated_at) " +
		"VALUES ($1,$2,$3,$4,now()) ON CONFLICT (user_id,media_version_id) DO UPDATE SET " +
		"position_ms=EXCLUDED.position_ms,duration_ms=EXCLUDED.duration_ms,updated_at=now()",
		userID, body.MediaVersionID, body.PositionMS, body.DurationMS)
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
