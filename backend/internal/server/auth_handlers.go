package server

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"regexp"
	"strings"
	"time"

	authpkg "github.com/Awmirai/filmiqoo/backend/internal/auth"
	"github.com/golang-jwt/jwt/v5"
)

var usernamePattern=regexp.MustCompile(`^[A-Za-z0-9_.]{3,24}$`)

type registerRequest struct {
	Email string `json:"email"`
	Username string `json:"username"`
	DisplayName string `json:"displayName"`
	Password string `json:"password"`
	DeviceName string `json:"deviceName"`
}

type loginRequest struct {
	Login string `json:"login"`
	Password string `json:"password"`
	DeviceName string `json:"deviceName"`
}

type refreshRequest struct {
	RefreshToken string `json:"refreshToken"`
	DeviceName string `json:"deviceName"`
}

func (s *Server) register(w http.ResponseWriter,r *http.Request) {
	var body registerRequest
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	body.Email=strings.ToLower(strings.TrimSpace(body.Email))
	body.Username=strings.TrimSpace(body.Username)
	body.DisplayName=strings.TrimSpace(body.DisplayName)

	if !strings.Contains(body.Email,"@") || len(body.Email)>254 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid email"}); return
	}
	if !usernamePattern.MatchString(body.Username) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"username must be 3-24 characters using letters, numbers, _ or ."}); return
	}
	if len([]rune(body.DisplayName))<2 || len([]rune(body.DisplayName))>50 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"displayName must be 2-50 characters"}); return
	}
	if len(body.Password)<10 || len(body.Password)>128 {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"password must be 10-128 characters"}); return
	}

	passwordHash,err:=authpkg.HashPassword(body.Password)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	tx,err:=s.db.Begin(r.Context())
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	defer tx.Rollback(r.Context())

	var userID string
	err=tx.QueryRow(r.Context(),
		"INSERT INTO users (email,password_hash) VALUES ($1,$2) RETURNING id::text",
		body.Email,passwordHash).Scan(&userID)
	if err!=nil {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"email or username already exists"}); return
	}

	_,err=tx.Exec(r.Context(),
		"INSERT INTO profiles (user_id,username,display_name) VALUES ($1,$2,$3)",
		userID,body.Username,body.DisplayName)
	if err!=nil {
		writeJSON(w,http.StatusConflict,map[string]string{"error":"email or username already exists"}); return
	}

	refresh,refreshHash,err:=newRefreshToken()
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	refreshExpiry:=time.Now().Add(time.Duration(s.cfg.AuthRefreshTTLDays)*24*time.Hour)
	if s.cfg.AuthRefreshTTLDays<=0 { refreshExpiry=time.Now().Add(30*24*time.Hour) }

	_,err=tx.Exec(r.Context(),
		`INSERT INTO auth_sessions (user_id,refresh_token_hash,device_name,user_agent,ip_address,expires_at)
		  VALUES ($1,$2,$3,$4,$5,$6)`,
		userID,refreshHash,body.DeviceName,r.UserAgent(),requestIP(r),refreshExpiry)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if err:=tx.Commit(r.Context()); err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	access,expiresIn,err:=s.issueAccessToken(userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusCreated,map[string]any{
		"user":map[string]any{
			"id":userID,"email":body.Email,"username":body.Username,"displayName":body.DisplayName,
		},
		"accessToken":access,
		"refreshToken":refresh,
		"expiresIn":expiresIn,
	})
}

func (s *Server) login(w http.ResponseWriter,r *http.Request) {
	var body loginRequest
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	login:=strings.TrimSpace(body.Login)
	if login=="" || body.Password=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"login and password are required"}); return
	}

	var userID,passwordHash,email,username,displayName,status string
	err:=s.db.QueryRow(r.Context(),`
		SELECT u.id::text,u.password_hash,COALESCE(u.email::text,''),p.username::text,p.display_name,u.status
		  FROM users u JOIN profiles p ON p.user_id=u.id
		 WHERE lower(COALESCE(u.email::text,''))=lower($1) OR lower(p.username::text)=lower($1)
		 LIMIT 1
	`,login).Scan(&userID,&passwordHash,&email,&username,&displayName,&status)
	if err!=nil || status!="active" || !authpkg.VerifyPassword(body.Password,passwordHash) {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid credentials"}); return
	}

	refresh,refreshHash,err:=newRefreshToken()
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	refreshExpiry:=time.Now().Add(time.Duration(s.cfg.AuthRefreshTTLDays)*24*time.Hour)
	if s.cfg.AuthRefreshTTLDays<=0 { refreshExpiry=time.Now().Add(30*24*time.Hour) }

	_,err=s.db.Exec(r.Context(),`
		INSERT INTO auth_sessions (user_id,refresh_token_hash,device_name,user_agent,ip_address,expires_at)
		VALUES ($1,$2,$3,$4,$5,$6)
	`,userID,refreshHash,body.DeviceName,r.UserAgent(),requestIP(r),refreshExpiry)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	access,expiresIn,err:=s.issueAccessToken(userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{
		"user":map[string]any{
			"id":userID,"email":email,"username":username,"displayName":displayName,
		},
		"accessToken":access,"refreshToken":refresh,"expiresIn":expiresIn,
	})
}

func (s *Server) refresh(w http.ResponseWriter,r *http.Request) {
	var body refreshRequest
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	oldHash:=hashRefreshToken(strings.TrimSpace(body.RefreshToken))
	if oldHash=="" {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"refreshToken is required"}); return
	}

	var sessionID,userID string
	var expiresAt time.Time
	err:=s.db.QueryRow(r.Context(),`
		SELECT id::text,user_id::text,expires_at
		  FROM auth_sessions
		 WHERE refresh_token_hash=$1 AND revoked_at IS NULL
		 LIMIT 1
	`,oldHash).Scan(&sessionID,&userID,&expiresAt)
	if err!=nil || time.Now().After(expiresAt) {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"invalid or expired refresh token"}); return
	}

	newToken,newHash,err:=newRefreshToken()
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	newExpiry:=time.Now().Add(time.Duration(s.cfg.AuthRefreshTTLDays)*24*time.Hour)
	if s.cfg.AuthRefreshTTLDays<=0 { newExpiry=time.Now().Add(30*24*time.Hour) }

	tag,err:=s.db.Exec(r.Context(),`
		UPDATE auth_sessions
		   SET refresh_token_hash=$2,expires_at=$3,last_used_at=now(),
		       device_name=CASE WHEN $4<>'' THEN $4 ELSE device_name END
		 WHERE id=$1 AND refresh_token_hash=$5 AND revoked_at IS NULL
	`,sessionID,newHash,newExpiry,body.DeviceName,oldHash)
	if err!=nil || tag.RowsAffected()!=1 {
		writeJSON(w,http.StatusUnauthorized,map[string]string{"error":"refresh token rotation failed"}); return
	}

	access,expiresIn,err:=s.issueAccessToken(userID)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }
	writeJSON(w,http.StatusOK,map[string]any{
		"accessToken":access,"refreshToken":newToken,"expiresIn":expiresIn,
	})
}

func (s *Server) logout(w http.ResponseWriter,r *http.Request) {
	var body refreshRequest
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err); return
	}
	hash:=hashRefreshToken(strings.TrimSpace(body.RefreshToken))
	if hash!="" {
		_,_=s.db.Exec(r.Context(),
			"UPDATE auth_sessions SET revoked_at=now() WHERE refresh_token_hash=$1 AND revoked_at IS NULL",
			hash)
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) me(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var email,username,displayName,bio,avatarURL,coverURL string
	var verified,privateAccount bool
	var followerCount,followingCount int64

	err:=s.db.QueryRow(r.Context(),`
		SELECT COALESCE(u.email::text,''),p.username::text,p.display_name,p.bio,p.avatar_url,p.cover_url,
		       p.verified,p.private_account,p.follower_count,p.following_count
		  FROM users u JOIN profiles p ON p.user_id=u.id
		 WHERE u.id=$1
	`,userID).Scan(&email,&username,&displayName,&bio,&avatarURL,&coverURL,
		&verified,&privateAccount,&followerCount,&followingCount)
	if err!=nil { writeJSON(w,http.StatusNotFound,map[string]string{"error":"user not found"}); return }

	writeJSON(w,http.StatusOK,map[string]any{
		"id":userID,"email":email,"username":username,"displayName":displayName,"bio":bio,
		"avatarUrl":avatarURL,"coverUrl":coverURL,"verified":verified,"private":privateAccount,
		"followers":followerCount,"following":followingCount,
	})
}

func (s *Server) issueAccessToken(userID string) (string,int64,error) {
	minutes:=s.cfg.AuthAccessTTLMinutes
	if minutes<=0 { minutes=15 }
	now:=time.Now()
	exp:=now.Add(time.Duration(minutes)*time.Minute)
	claims:=jwt.RegisteredClaims{
		Subject:userID,
		Issuer:"filmiqoo",
		Audience:jwt.ClaimStrings{"filmiqoo-android"},
		IssuedAt:jwt.NewNumericDate(now),
		ExpiresAt:jwt.NewNumericDate(exp),
	}
	token:=jwt.NewWithClaims(jwt.SigningMethodHS256,claims)
	signed,err:=token.SignedString([]byte(s.cfg.JWTSecret))
	return signed,int64(time.Until(exp).Seconds()),err
}

func newRefreshToken() (plain string,hash string,err error) {
	buf:=make([]byte,48)
	if _,err=rand.Read(buf); err!=nil { return "","",err }
	plain=base64.RawURLEncoding.EncodeToString(buf)
	hash=hashRefreshToken(plain)
	return plain,hash,nil
}

func hashRefreshToken(token string) string {
	if token=="" { return "" }
	sum:=sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func requestIP(r *http.Request) any {
	host,_,err:=net.SplitHostPort(r.RemoteAddr)
	if err==nil && net.ParseIP(host)!=nil { return host }
	if net.ParseIP(r.RemoteAddr)!=nil { return r.RemoteAddr }
	return nil
}
