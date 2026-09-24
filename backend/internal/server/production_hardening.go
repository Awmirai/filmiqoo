package server

import (
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"
)

func (s *Server) securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
		w.Header().Set("X-Content-Type-Options","nosniff")
		w.Header().Set("X-Frame-Options","DENY")
		w.Header().Set("Referrer-Policy","no-referrer")
		w.Header().Set("Permissions-Policy","camera=(), microphone=(), geolocation=()")
		w.Header().Set("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'")
		if s.cfg.IsProduction() {
			w.Header().Set("Strict-Transport-Security","max-age=31536000; includeSubDomains")
		}
		if strings.HasPrefix(r.URL.Path,"/v1/auth/") ||
			strings.HasPrefix(r.URL.Path,"/v1/security/") ||
			strings.HasPrefix(r.URL.Path,"/v1/privacy/") {
			w.Header().Set("Cache-Control","no-store")
			w.Header().Set("Pragma","no-cache")
		}
		next.ServeHTTP(w,r)
	})
}

func (s *Server) limitJSONBody(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
		if r.Body!=nil && isJSONRequest(r) {
			max:=s.cfg.MaxJSONBodyBytes
			if max<=0 { max=2*1024*1024 }
			r.Body=http.MaxBytesReader(w,r.Body,max)
		}
		next.ServeHTTP(w,r)
	})
}

func isJSONRequest(r *http.Request) bool {
	if r.Method==http.MethodGet || r.Method==http.MethodHead || r.Method==http.MethodOptions {
		return false
	}
	contentType:=strings.ToLower(strings.TrimSpace(r.Header.Get("Content-Type")))
	return contentType=="" ||
		strings.HasPrefix(contentType,"application/json") ||
		strings.Contains(contentType,"+json")
}

func (s *Server) authRateLimit(bucket string,limit int,window time.Duration) func(http.Handler) http.Handler {
	if limit<=0 { limit=1 }
	if window<=0 { window=time.Minute }

	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
			if s.redis==nil {
				next.ServeHTTP(w,r)
				return
			}

			identity:=clientIP(r)
			if identity=="" { identity="unknown" }

			windowID:=time.Now().UTC().Unix()/int64(window.Seconds())
			key:=fmt.Sprintf("ratelimit:%s:%s:%d",bucket,identity,windowID)

			ctx:=r.Context()
			count,err:=s.redis.Incr(ctx,key).Result()
			if err!=nil {
				// Fail open if Redis is temporarily unavailable. Readiness still reports Redis.
				next.ServeHTTP(w,r)
				return
			}
			if count==1 {
				_ = s.redis.Expire(ctx,key,window+5*time.Second).Err()
			}

			remaining:=int64(limit)-count
			if remaining<0 { remaining=0 }
			w.Header().Set("X-RateLimit-Limit",strconv.Itoa(limit))
			w.Header().Set("X-RateLimit-Remaining",strconv.FormatInt(remaining,10))

			if count>int64(limit) {
				ttl,_:=s.redis.TTL(ctx,key).Result()
				retry:=int64(ttl.Seconds())
				if retry<1 { retry=1 }
				w.Header().Set("Retry-After",strconv.FormatInt(retry,10))
				writeJSON(w,http.StatusTooManyRequests,map[string]any{
					"error":"too many requests",
					"retryAfterSeconds":retry,
				})
				return
			}

			next.ServeHTTP(w,r)
		})
	}
}

func clientIP(r *http.Request) string {
	if r==nil { return "" }
	value:=strings.TrimSpace(r.RemoteAddr)
	if host,_,err:=net.SplitHostPort(value); err==nil {
		value=host
	}
	return strings.TrimSpace(value)
}

func (s *Server) cors(next http.Handler) http.Handler {
	allowed:=make(map[string]struct{},len(s.cfg.AllowedOrigins))
	for _,origin:=range s.cfg.AllowedOrigins {
		allowed[strings.TrimSpace(origin)]=struct{}{}
	}
	devWildcard:=!s.cfg.IsProduction() && len(allowed)==0

	return http.HandlerFunc(func(w http.ResponseWriter,r *http.Request) {
		origin:=strings.TrimSpace(r.Header.Get("Origin"))
		if origin!="" {
			allowOrigin:=""
			if devWildcard {
				allowOrigin="*"
			} else if _,ok:=allowed[origin]; ok {
				allowOrigin=origin
				w.Header().Add("Vary","Origin")
			}

			if allowOrigin=="" {
				if r.Method==http.MethodOptions {
					writeJSON(w,http.StatusForbidden,map[string]string{"error":"origin not allowed"})
					return
				}
			} else {
				w.Header().Set("Access-Control-Allow-Origin",allowOrigin)
			}
		}

		w.Header().Set(
			"Access-Control-Allow-Headers",
			"Authorization, Content-Type, X-Filmiqoo-Ingest-Secret, X-Filmiqoo-Viewer-Profile, X-Request-ID",
		)
		w.Header().Set("Access-Control-Allow-Methods","GET, POST, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Max-Age","600")

		if r.Method==http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w,r)
	})
}
