package storage

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/redis/go-redis/v9"
)

func OpenPostgres(ctx context.Context, dsn string) (*pgxpool.Pool, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil { return nil, err }
	cfg.MaxConns = 20
	cfg.MinConns = 2
	cfg.MaxConnLifetime = 30 * time.Minute
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil { return nil, err }
	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := pool.Ping(pingCtx); err != nil { pool.Close(); return nil, err }
	return pool, nil
}

func OpenRedis(addr, password string) *redis.Client {
	return redis.NewClient(&redis.Options{Addr: addr, Password: password, DB: 0, PoolSize: 30, MinIdleConns: 3})
}
