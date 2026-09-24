package server

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"
)

type fcmClient struct {
	projectID string
	clientEmail string
	privateKey *rsa.PrivateKey
	tokenURI string
	httpClient *http.Client
	mu sync.Mutex
	accessToken string
	accessTokenExpiry time.Time
}

type firebaseServiceAccount struct {
	ProjectID string `json:"project_id"`
	ClientEmail string `json:"client_email"`
	PrivateKey string `json:"private_key"`
	TokenURI string `json:"token_uri"`
}

type fcmSendResult struct {
	Permanent bool
	InvalidToken bool
}

func (s *Server) configureFCM() error {
	if !s.cfg.FirebasePushEnabled {
		s.fcm=nil
		return nil
	}
	var account firebaseServiceAccount
	if err:=json.Unmarshal([]byte(s.cfg.FirebaseServiceAccountJSON),&account); err!=nil {
		return fmt.Errorf("firebase service account: %w",err)
	}
	key,err:=parseRSAPrivateKey(account.PrivateKey)
	if err!=nil { return fmt.Errorf("firebase private key: %w",err) }
	projectID:=strings.TrimSpace(s.cfg.FirebaseProjectID)
	if projectID=="" { projectID=strings.TrimSpace(account.ProjectID) }
	tokenURI:=strings.TrimSpace(account.TokenURI)
	if tokenURI=="" { tokenURI="https://oauth2.googleapis.com/token" }
	s.fcm=&fcmClient{
		projectID:projectID,
		clientEmail:strings.TrimSpace(account.ClientEmail),
		privateKey:key,
		tokenURI:tokenURI,
		httpClient:s.upstreamClient,
	}
	return nil
}

func parseRSAPrivateKey(value string) (*rsa.PrivateKey,error) {
	block,_:=pem.Decode([]byte(value))
	if block==nil { return nil,errors.New("invalid PEM private key") }
	if key,err:=x509.ParsePKCS8PrivateKey(block.Bytes); err==nil {
		rsaKey,ok:=key.(*rsa.PrivateKey)
		if !ok { return nil,errors.New("private key is not RSA") }
		return rsaKey,nil
	}
	if key,err:=x509.ParsePKCS1PrivateKey(block.Bytes); err==nil { return key,nil }
	return nil,errors.New("unsupported RSA private key encoding")
}

func (c *fcmClient) token(ctx context.Context) (string,error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.accessToken!="" && time.Until(c.accessTokenExpiry)>2*time.Minute {
		return c.accessToken,nil
	}
	now:=time.Now().UTC()
	headerJSON,_:=json.Marshal(map[string]any{"alg":"RS256","typ":"JWT"})
	claimsJSON,_:=json.Marshal(map[string]any{
		"iss":c.clientEmail,
		"scope":"https://www.googleapis.com/auth/firebase.messaging",
		"aud":c.tokenURI,
		"iat":now.Unix(),
		"exp":now.Add(55*time.Minute).Unix(),
	})
	unsigned:=base64.RawURLEncoding.EncodeToString(headerJSON)+"."+
		base64.RawURLEncoding.EncodeToString(claimsJSON)
	digest:=sha256.Sum256([]byte(unsigned))
	signature,err:=rsa.SignPKCS1v15(rand.Reader,c.privateKey,crypto.SHA256,digest[:])
	if err!=nil { return "",err }
	assertion:=unsigned+"."+base64.RawURLEncoding.EncodeToString(signature)

	form:=url.Values{}
	form.Set("grant_type","urn:ietf:params:oauth:grant-type:jwt-bearer")
	form.Set("assertion",assertion)
	req,err:=http.NewRequestWithContext(ctx,http.MethodPost,c.tokenURI,strings.NewReader(form.Encode()))
	if err!=nil { return "",err }
	req.Header.Set("Content-Type","application/x-www-form-urlencoded")
	res,err:=c.httpClient.Do(req)
	if err!=nil { return "",err }
	defer res.Body.Close()
	raw,_:=io.ReadAll(io.LimitReader(res.Body,1<<20))
	if res.StatusCode<200 || res.StatusCode>=300 {
		return "",fmt.Errorf("firebase oauth returned %d: %s",res.StatusCode,strings.TrimSpace(string(raw)))
	}
	var tokenResponse struct {
		AccessToken string `json:"access_token"`
		ExpiresIn int64 `json:"expires_in"`
	}
	if err:=json.Unmarshal(raw,&tokenResponse); err!=nil { return "",err }
	if strings.TrimSpace(tokenResponse.AccessToken)=="" {
		return "",errors.New("firebase oauth returned empty access token")
	}
	if tokenResponse.ExpiresIn<=0 { tokenResponse.ExpiresIn=3600 }
	c.accessToken=tokenResponse.AccessToken
	c.accessTokenExpiry=time.Now().Add(time.Duration(tokenResponse.ExpiresIn)*time.Second)
	return c.accessToken,nil
}

func (s *Server) sendFCM(
	ctx context.Context,
	token,title,body,notificationID,notificationType,entityType,entityID string,
) (fcmSendResult,error) {
	if s.fcm==nil { return fcmSendResult{},errors.New("FCM is not configured") }
	accessToken,err:=s.fcm.token(ctx)
	if err!=nil { return fcmSendResult{},err }

	payload:=map[string]any{
		"message":map[string]any{
			"token":token,
			"data":map[string]string{
				"title":title,
				"body":body,
				"notificationId":notificationID,
				"type":notificationType,
				"entityType":entityType,
				"entityId":entityID,
			},
			"android":map[string]any{
				"priority":"high",
				"ttl":"86400s",
			},
		},
	}
	raw,_:=json.Marshal(payload)
	endpoint:="https://fcm.googleapis.com/v1/projects/"+url.PathEscape(s.fcm.projectID)+"/messages:send"
	req,err:=http.NewRequestWithContext(ctx,http.MethodPost,endpoint,strings.NewReader(string(raw)))
	if err!=nil { return fcmSendResult{},err }
	req.Header.Set("Authorization","Bearer "+accessToken)
	req.Header.Set("Content-Type","application/json")
	res,err:=s.fcm.httpClient.Do(req)
	if err!=nil { return fcmSendResult{},err }
	defer res.Body.Close()
	responseRaw,_:=io.ReadAll(io.LimitReader(res.Body,1<<20))
	if res.StatusCode>=200 && res.StatusCode<300 { return fcmSendResult{},nil }

	responseText:=strings.ToUpper(string(responseRaw))
	invalidToken:=strings.Contains(responseText,"UNREGISTERED") ||
		strings.Contains(responseText,"SENDER_ID_MISMATCH") ||
		(res.StatusCode==http.StatusBadRequest && strings.Contains(responseText,"INVALID_ARGUMENT"))
	permanent:=invalidToken || res.StatusCode==http.StatusBadRequest ||
		res.StatusCode==http.StatusNotFound || res.StatusCode==http.StatusForbidden
	return fcmSendResult{Permanent:permanent,InvalidToken:invalidToken},
		fmt.Errorf("FCM returned %d: %s",res.StatusCode,strings.TrimSpace(string(responseRaw)))
}

type pushOutboxItem struct {
	id string
	notificationID string
	userID string
	attempt int
}

func (s *Server) processPushOutbox(ctx context.Context) error {
	if s.fcm==nil { return nil }
	rows,err:=s.db.Query(ctx,`
		UPDATE push_outbox
		   SET status='sending',
		       attempt_count=attempt_count+1,
		       last_attempt_at=now(),
		       updated_at=now()
		 WHERE id IN (
		   SELECT id
		     FROM push_outbox
		    WHERE (
		      status IN ('pending','failed')
		      OR (status='sending' AND updated_at<now()-interval '2 minutes')
		    )
		      AND next_attempt_at<=now()
		      AND attempt_count<$1
		    ORDER BY next_attempt_at ASC,created_at ASC
		    FOR UPDATE SKIP LOCKED
		    LIMIT 50
		 )
		 RETURNING id::text,notification_id::text,user_id::text,attempt_count
	`,s.cfg.PushMaxAttempts)
	if err!=nil { return err }
	defer rows.Close()
	items:=make([]pushOutboxItem,0)
	for rows.Next() {
		var item pushOutboxItem
		if rows.Scan(&item.id,&item.notificationID,&item.userID,&item.attempt)==nil {
			items=append(items,item)
		}
	}
	if err:=rows.Err(); err!=nil { return err }

	for _,item:=range items {
		if err:=s.deliverPushOutboxItem(ctx,item); err!=nil {
			delay:=pushRetryDelay(item.attempt)
			status:="failed"
			if item.attempt>=s.cfg.PushMaxAttempts { status="dead" }
			_,_=s.db.Exec(ctx,`
				UPDATE push_outbox
				   SET status=$2,
				       next_attempt_at=now()+($3::text || ' seconds')::interval,
				       last_error=$4,
				       updated_at=now()
				 WHERE id=$1
			`,item.id,status,strconv.FormatInt(int64(delay/time.Second),10),truncateWorkerError(err.Error()))
		}
	}
	return nil
}

func (s *Server) deliverPushOutboxItem(ctx context.Context,item pushOutboxItem) error {
	var title,body,notificationType,entityType string
	var entityID *string
	err:=s.db.QueryRow(ctx,`
		SELECT title,body,notification_type,entity_type,entity_id::text
		  FROM notifications
		 WHERE id=$1 AND user_id=$2
	`,item.notificationID,item.userID).Scan(
		&title,&body,&notificationType,&entityType,&entityID,
	)
	if err!=nil { return err }

	rows,err:=s.db.Query(ctx,`
		SELECT id::text,push_token
		  FROM push_devices
		 WHERE user_id=$1
		   AND provider='fcm'
		   AND platform='android'
		   AND enabled=true
		 ORDER BY last_seen_at DESC
		 LIMIT 20
	`,item.userID)
	if err!=nil { return err }
	defer rows.Close()
	type device struct { id string; token string }
	devices:=make([]device,0)
	for rows.Next() {
		var d device
		if rows.Scan(&d.id,&d.token)==nil { devices=append(devices,d) }
	}
	if len(devices)==0 {
		_,_=s.db.Exec(ctx,"UPDATE push_outbox SET status='no_device',last_error='',updated_at=now() WHERE id=$1",item.id)
		return nil
	}

	successes:=0
	transientErrors:=make([]string,0)
	for _,device:=range devices {
		sendCtx,cancel:=context.WithTimeout(ctx,12*time.Second)
		result,sendErr:=s.sendFCM(
			sendCtx,device.token,title,body,item.notificationID,
			notificationType,entityType,valueOrEmpty(entityID),
		)
		cancel()
		if sendErr==nil {
			successes++
			continue
		}
		if result.InvalidToken {
			_,_=s.db.Exec(ctx,"UPDATE push_devices SET enabled=false,last_seen_at=now() WHERE id=$1",device.id)
		}
		if !result.Permanent { transientErrors=append(transientErrors,sendErr.Error()) }
	}
	if successes>0 {
		_,_=s.db.Exec(ctx,"UPDATE push_outbox SET status='sent',sent_at=now(),last_error='',updated_at=now() WHERE id=$1",item.id)
		return nil
	}
	if len(transientErrors)>0 { return errors.New(transientErrors[0]) }

	_,_=s.db.Exec(ctx,`
		UPDATE push_outbox
		   SET status='no_device',
		       last_error='all registered tokens were rejected',
		       updated_at=now()
		 WHERE id=$1
	`,item.id)
	return nil
}

func pushRetryDelay(attempt int) time.Duration {
	if attempt<1 { attempt=1 }
	if attempt>8 { attempt=8 }
	delay:=time.Duration(1<<(attempt-1))*15*time.Second
	if delay>30*time.Minute { return 30*time.Minute }
	return delay
}

func truncateWorkerError(value string) string {
	runes:=[]rune(strings.TrimSpace(value))
	if len(runes)>1000 { return string(runes[:1000]) }
	return string(runes)
}

func valueOrEmpty(value *string) string {
	if value==nil { return "" }
	return *value
}

func (s *Server) runPushDeliveryWorker(ctx context.Context) {
	if s.fcm==nil { return }
	ticker:=time.NewTicker(5*time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			runCtx,cancel:=context.WithTimeout(ctx,20*time.Second)
			_ = s.processPushOutbox(runCtx)
			cancel()
		}
	}
}
