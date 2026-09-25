package main

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

type sample struct {
	duration time.Duration
	ok bool
}

func main() {
	base:=strings.TrimRight(strings.TrimSpace(os.Getenv("FILMIQOO_LOAD_BASE_URL")),"/")
	if base=="" {
		fmt.Fprintln(os.Stderr,"FILMIQOO_LOAD_BASE_URL is required")
		os.Exit(2)
	}

	concurrency:=envInt("FILMIQOO_LOAD_CONCURRENCY",20)
	durationSeconds:=envInt("FILMIQOO_LOAD_DURATION_SECONDS",30)
	p95LimitMs:=envInt("FILMIQOO_LOAD_P95_LIMIT_MS",1500)
	maxErrorPercent:=envFloat("FILMIQOO_LOAD_MAX_ERROR_PERCENT",1.0)
	paths:=envList(
		"FILMIQOO_LOAD_PATHS",
		[]string{
			"/healthz",
			"/catalog/home",
			"/search?q=matrix",
		},
	)

	transport:=&http.Transport{
		MaxIdleConns:concurrency*4,
		MaxIdleConnsPerHost:concurrency*2,
		IdleConnTimeout:30*time.Second,
	}
	client:=&http.Client{
		Transport:transport,
		Timeout:10*time.Second,
	}

	ctx,cancel:=context.WithTimeout(
		context.Background(),
		time.Duration(durationSeconds)*time.Second,
	)
	defer cancel()

	var cursor atomic.Uint64
	var total atomic.Int64
	var errorsCount atomic.Int64
	var mu sync.Mutex
	samples:=make([]sample,0,concurrency*durationSeconds*4)

	var wg sync.WaitGroup
	for worker:=0; worker<concurrency; worker++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				default:
				}

				index:=cursor.Add(1)-1
				path:=paths[int(index%uint64(len(paths)))]
				started:=time.Now()
				req,err:=http.NewRequestWithContext(
					ctx,
					http.MethodGet,
					base+path,
					nil,
				)
				ok:=false
				if err==nil {
					res,doErr:=client.Do(req)
					if doErr==nil {
						_,_=io.Copy(io.Discard,res.Body)
						res.Body.Close()
						ok=res.StatusCode>=200 && res.StatusCode<500
					}
				}
				elapsed:=time.Since(started)
				total.Add(1)
				if !ok { errorsCount.Add(1) }

				mu.Lock()
				samples=append(samples,sample{
					duration:elapsed,
					ok:ok,
				})
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	mu.Lock()
	latencies:=make([]time.Duration,0,len(samples))
	for _,s:=range samples {
		latencies=append(latencies,s.duration)
	}
	mu.Unlock()

	if len(latencies)==0 {
		fmt.Fprintln(os.Stderr,"no load samples were collected")
		os.Exit(1)
	}
	sort.Slice(latencies,func(i,j int) bool {
		return latencies[i]<latencies[j]
	})

	requests:=total.Load()
	failed:=errorsCount.Load()
	errorPercent:=float64(failed)*100/float64(requests)
	p50:=percentile(latencies,0.50)
	p95:=percentile(latencies,0.95)
	p99:=percentile(latencies,0.99)

	fmt.Printf(
		"requests=%d errors=%d error_rate=%.2f%% p50=%s p95=%s p99=%s concurrency=%d duration=%ds\n",
		requests,
		failed,
		errorPercent,
		p50,
		p95,
		p99,
		concurrency,
		durationSeconds,
	)

	failedGate:=false
	if errorPercent>maxErrorPercent {
		fmt.Fprintf(
			os.Stderr,
			"error rate %.2f%% exceeded %.2f%%\n",
			errorPercent,
			maxErrorPercent,
		)
		failedGate=true
	}
	if p95>time.Duration(p95LimitMs)*time.Millisecond {
		fmt.Fprintf(
			os.Stderr,
			"p95 %s exceeded %dms\n",
			p95,
			p95LimitMs,
		)
		failedGate=true
	}
	if failedGate { os.Exit(1) }
}

func percentile(values []time.Duration,p float64) time.Duration {
	if len(values)==0 { return 0 }
	if p<=0 { return values[0] }
	if p>=1 { return values[len(values)-1] }
	index:=int(float64(len(values)-1)*p)
	return values[index]
}

func envInt(key string,fallback int) int {
	raw:=strings.TrimSpace(os.Getenv(key))
	if raw=="" { return fallback }
	value,err:=strconv.Atoi(raw)
	if err!=nil || value<=0 { return fallback }
	return value
}

func envFloat(key string,fallback float64) float64 {
	raw:=strings.TrimSpace(os.Getenv(key))
	if raw=="" { return fallback }
	value,err:=strconv.ParseFloat(raw,64)
	if err!=nil || value<0 { return fallback }
	return value
}

func envList(key string,fallback []string) []string {
	raw:=strings.TrimSpace(os.Getenv(key))
	if raw=="" { return fallback }
	result:=make([]string,0)
	for _,part:=range strings.Split(raw,",") {
		value:=strings.TrimSpace(part)
		if value!="" { result=append(result,value) }
	}
	if len(result)==0 { return fallback }
	return result
}
