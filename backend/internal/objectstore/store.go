package objectstore

import (
	"context"
	"fmt"
	"image/gif"
	"io"
	"net/url"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type Store struct {
	internal *minio.Client
	signer   *minio.Client
	bucket   string
}

func New(internalEndpoint, publicEndpoint, accessKey, secretKey, bucket string) (*Store, error) {
	internal, err := newClient(internalEndpoint, accessKey, secretKey)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(publicEndpoint) == "" {
		publicEndpoint = internalEndpoint
	}
	signer, err := newClient(publicEndpoint, accessKey, secretKey)
	if err != nil {
		return nil, err
	}
	if strings.TrimSpace(bucket) == "" {
		return nil, fmt.Errorf("object storage bucket is required")
	}
	return &Store{internal: internal, signer: signer, bucket: bucket}, nil
}

func newClient(endpoint, accessKey, secretKey string) (*minio.Client, error) {
	raw := strings.TrimSpace(endpoint)
	if !strings.Contains(raw, "://") {
		raw = "http://" + raw
	}
	u, err := url.Parse(raw)
	if err != nil {
		return nil, err
	}
	return minio.New(u.Host, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: u.Scheme == "https",
	})
}

func (s *Store) ensure(ctx context.Context) error {
	exists, err := s.internal.BucketExists(ctx, s.bucket)
	if err != nil {
		return err
	}
	if exists {
		return nil
	}
	if err := s.internal.MakeBucket(ctx, s.bucket, minio.MakeBucketOptions{}); err != nil {
		existsAgain, checkErr := s.internal.BucketExists(ctx, s.bucket)
		if checkErr == nil && existsAgain {
			return nil
		}
		return err
	}
	return nil
}

func (s *Store) Health(ctx context.Context) error {
	if s == nil {
		return fmt.Errorf("object store is nil")
	}
	return s.ensure(ctx)
}

func (s *Store) PresignPut(ctx context.Context, key string, expiry time.Duration) (*url.URL, error) {
	if err := s.ensure(ctx); err != nil {
		return nil, err
	}
	return s.signer.PresignedPutObject(ctx, s.bucket, key, expiry)
}

func (s *Store) PresignGet(ctx context.Context, key string, expiry time.Duration) (*url.URL, error) {
	if err := s.ensure(ctx); err != nil {
		return nil, err
	}
	return s.signer.PresignedGetObject(ctx, s.bucket, key, expiry, nil)
}

type ObjectInfo struct {
	Size        int64
	ContentType string
}

func (s *Store) Stat(ctx context.Context, key string) (ObjectInfo, error) {
	if err := s.ensure(ctx); err != nil {
		return ObjectInfo{}, err
	}
	info, err := s.internal.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		return ObjectInfo{}, err
	}
	return ObjectInfo{
		Size:        info.Size,
		ContentType: strings.ToLower(strings.TrimSpace(info.ContentType)),
	}, nil
}

func (s *Store) Delete(ctx context.Context, key string) error {
	if err := s.ensure(ctx); err != nil {
		return err
	}
	return s.internal.RemoveObject(ctx, s.bucket, key, minio.RemoveObjectOptions{})
}

// Inspect a bounded GIF header directly from storage; never fetch a user URL.
func (s *Store) ValidateGIF(ctx context.Context, key string) error {
	object, err := s.internal.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return err
	}
	defer object.Close()
	cfg, err := gif.DecodeConfig(io.LimitReader(object, 65536))
	if err != nil {
		return fmt.Errorf("invalid GIF header")
	}
	if cfg.Width < 1 || cfg.Height < 1 || cfg.Width > 2048 || cfg.Height > 2048 || cfg.Width*cfg.Height > 4_194_304 {
		return fmt.Errorf("GIF dimensions exceed limit")
	}
	return nil
}
