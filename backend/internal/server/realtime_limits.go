package server

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"
)

var acquireRealtimeSlotScript=redis.NewScript(`
local key=KEYS[1]
local now=tonumber(ARGV[1])
local expires=tonumber(ARGV[2])
local limit=tonumber(ARGV[3])
local member=ARGV[4]

redis.call('ZREMRANGEBYSCORE',key,'-inf',now)
local count=redis.call('ZCARD',key)
if count>=limit then
  return 0
end
redis.call('ZADD',key,expires,member)
redis.call('EXPIRE',key,300)
return 1
`)

func (s *Server) acquireRealtimeSlot(
	ctx context.Context,
	userID string,
) (string,bool,error) {
	if s.redis==nil { return "",true,nil }
	member,err:=randomHex(12)
	if err!=nil { return "",false,err }

	now:=time.Now().Unix()
	expires:=now+120
	key:="realtime:user:"+userID
	result,err:=acquireRealtimeSlotScript.Run(
		ctx,
		s.redis,
		[]string{key},
		now,
		expires,
		s.cfg.RealtimeConnectionsPerUser,
		member,
	).Int()
	if err!=nil { return "",false,err }
	return member,result==1,nil
}

func (s *Server) refreshRealtimeSlot(
	ctx context.Context,
	userID string,
	member string,
) {
	if s.redis==nil || member=="" { return }
	key:="realtime:user:"+userID
	expires:=float64(time.Now().Add(2*time.Minute).Unix())
	_ = s.redis.ZAddArgs(ctx,key,redis.ZAddArgs{
		XX:true,
		Members:[]redis.Z{{
			Score:expires,
			Member:member,
		}},
	}).Err()
	_ = s.redis.Expire(ctx,key,5*time.Minute).Err()
}

func (s *Server) releaseRealtimeSlot(
	ctx context.Context,
	userID string,
	member string,
) {
	if s.redis==nil || member=="" { return }
	_ = s.redis.ZRem(ctx,"realtime:user:"+userID,member).Err()
}

func (s *Server) realtimeLimitMessage() string {
	return fmt.Sprintf(
		"too many realtime connections; maximum is %s",
		strconv.Itoa(s.cfg.RealtimeConnectionsPerUser),
	)
}
