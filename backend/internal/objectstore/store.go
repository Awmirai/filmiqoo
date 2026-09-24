package objectstore

import (
	"context"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type Store struct {
	internal *minio.Client
	signer *minio.Client
	bucket string
	once sync.Once
	ensureErr error
}

func New(internalEndpoint, publicEndpoint, accessKey, secretKey, bucket string) (*Store,error) {
	internal,err:=newClient(internalEndpoint,accessKey,secretKey)
	if err!=nil { return nil,err }
	if strings.TrimSpace(publicEndpoint)=="" { publicEndpoint=internalEndpoint }
	signer,err:=newClient(publicEndpoint,accessKey,secretKey)
	if err!=nil { return nil,err }
	if strings.TrimSpace(bucket)=="" { return nil,fmt.Errorf("object storage bucket is required") }
	return &Store{internal:internal,signer:signer,bucket:bucket},nil
}

func newClient(endpoint,accessKey,secretKey string) (*minio.Client,error) {
	raw:=strings.TrimSpace(endpoint)
	if !strings.Contains(raw,"://") { raw="http://"+raw }
	u,err:=url.Parse(raw)
	if err!=nil { return nil,err }
	return minio.New(u.Host,&minio.Options{
		Creds:credentials.NewStaticV4(accessKey,secretKey,""),
		Secure:u.Scheme=="https",
	})
}

func (s *Store) ensure(ctx context.Context) error {
	s.once.Do(func(){
		exists,err:=s.internal.BucketExists(ctx,s.bucket)
		if err!=nil { s.ensureErr=err; return }
		if !exists {
			s.ensureErr=s.internal.MakeBucket(ctx,s.bucket,minio.MakeBucketOptions{})
		}
	})
	return s.ensureErr
}

func (s *Store) PresignPut(ctx context.Context,key string,expiry time.Duration) (*url.URL,error) {
	if err:=s.ensure(ctx); err!=nil { return nil,err }
	return s.signer.PresignedPutObject(ctx,s.bucket,key,expiry)
}

func (s *Store) PresignGet(ctx context.Context,key string,expiry time.Duration) (*url.URL,error) {
	if err:=s.ensure(ctx); err!=nil { return nil,err }
	return s.signer.PresignedGetObject(ctx,s.bucket,key,expiry,nil)
}


type ObjectInfo struct {
	Size int64
	ContentType string
}

func (s *Store) Stat(ctx context.Context,key string) (ObjectInfo,error) {
	if err:=s.ensure(ctx); err!=nil { return ObjectInfo{},err }
	info,err:=s.internal.StatObject(ctx,s.bucket,key,minio.StatObjectOptions{})
	if err!=nil { return ObjectInfo{},err }
	return ObjectInfo{
		Size:info.Size,
		ContentType:strings.ToLower(strings.TrimSpace(info.ContentType)),
	},nil
}

func (s *Store) Delete(ctx context.Context,key string) error {
	if err:=s.ensure(ctx); err!=nil { return err }
	return s.internal.RemoveObject(ctx,s.bucket,key,minio.RemoveObjectOptions{})
}
