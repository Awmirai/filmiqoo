package storage

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/Awmirai/filmiqoo/backend/migrations"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const migrationLockID int64 = 7382457325

func ApplyMigrations(ctx context.Context,pool *pgxpool.Pool) error {
	if pool==nil { return errors.New("postgres pool is nil") }

	conn,err:=pool.Acquire(ctx)
	if err!=nil { return err }
	defer conn.Release()

	if _,err=conn.Exec(ctx,"SELECT pg_advisory_lock($1)",migrationLockID); err!=nil {
		return fmt.Errorf("acquire migration lock: %w",err)
	}
	defer func() {
		unlockCtx,cancel:=context.WithTimeout(context.Background(),5*time.Second)
		defer cancel()
		_,_ = conn.Exec(unlockCtx,"SELECT pg_advisory_unlock($1)",migrationLockID)
	}()

	if _,err=conn.Exec(ctx,`
		CREATE TABLE IF NOT EXISTS schema_migrations (
			name text PRIMARY KEY,
			checksum text NOT NULL,
			applied_at timestamptz NOT NULL DEFAULT now()
		)
	`); err!=nil {
		return fmt.Errorf("create schema_migrations: %w",err)
	}

	names,err:=migrations.Names()
	if err!=nil { return err }

	for _,name:=range names {
		sqlBytes,readErr:=migrations.Read(name)
		if readErr!=nil { return fmt.Errorf("read migration %s: %w",name,readErr) }

		sum:=sha256.Sum256(sqlBytes)
		checksum:=hex.EncodeToString(sum[:])

		var existing string
		scanErr:=conn.QueryRow(
			ctx,
			"SELECT checksum FROM schema_migrations WHERE name=$1",
			name,
		).Scan(&existing)
		if scanErr==nil {
			if existing!=checksum {
				return fmt.Errorf(
					"migration %s checksum mismatch: database=%s binary=%s",
					name,existing,checksum,
				)
			}
			continue
		}
		if !errors.Is(scanErr,pgx.ErrNoRows) {
			return fmt.Errorf("read migration state %s: %w",name,scanErr)
		}

		tx,beginErr:=conn.Begin(ctx)
		if beginErr!=nil { return fmt.Errorf("begin migration %s: %w",name,beginErr) }

		results,execErr:=tx.Conn().PgConn().Exec(ctx,string(sqlBytes)).ReadAll()
		if execErr==nil {
			for _,result:=range results {
				if result.Err!=nil {
					execErr=result.Err
					break
				}
			}
		}
		if execErr!=nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("apply migration %s: %w",name,execErr)
		}

		if _,execErr=tx.Exec(
			ctx,
			"INSERT INTO schema_migrations (name,checksum) VALUES ($1,$2)",
			name,checksum,
		); execErr!=nil {
			_ = tx.Rollback(ctx)
			return fmt.Errorf("record migration %s: %w",name,execErr)
		}
		if commitErr:=tx.Commit(ctx); commitErr!=nil {
			return fmt.Errorf("commit migration %s: %w",name,commitErr)
		}
		log.Printf("database migration applied: %s",name)
	}
	return nil
}
