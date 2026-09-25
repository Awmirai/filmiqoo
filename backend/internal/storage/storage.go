package storage

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

func OpenPostgres(
	ctx context.Context,
	dsn string,
	maxConns int,
	minConns int,
) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil { return nil, err }
	if maxConns<=0 { maxConns=40 }
	if minConns<0 { minConns=0 }
	if minConns>maxConns { minConns=maxConns }
	cfg.MaxConns = int32(maxConns)
	cfg.MinConns = int32(minConns)
	cfg.MaxConnLifetime = 30 * time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil { return nil, err }
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil { pool.Close(); return nil, err }
	return pool, nil
}

func OpenRedis(addr,password string,poolSize int) *redis.Client {
	if poolSize<=0 { poolSize=60 }
	minIdle:=poolSize/10
	if minIdle<3 { minIdle=3 }
	return redis.NewClient(&redis.Options{
		Addr:addr,
		Password:password,
		DB:0,
		PoolSize:poolSize,
		MinIdleConns:minIdle,
	})
}
