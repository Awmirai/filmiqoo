// countrydata prepares the Iran-only subset of DB-IP Lite at image build time.
// CC BY 4.0 attribution is retained in the output and deployment documentation.
package main

import (
    "bytes"
    "compress/gzip"
    "crypto/sha256"
    "encoding/csv"
    "encoding/hex"
    "encoding/json"
    "flag"
    "fmt"
    "io"
    "log"
    "net/http"
    "os"
    "path/filepath"
    "time"

    "github.com/Awmirai/filmiqoo/backend/internal/geoaccess"
)

func main() {
    month := flag.String("month", "", "DB-IP Lite publication month YYYY-MM; defaults to current UTC month")
    output := flag.String("out", "/out/iran-country.json", "output file")
    flag.Parse()
    if *month == "" { *month = time.Now().UTC().Format("2006-01") }
    published, err := time.Parse("2006-01", *month)
    if err != nil { log.Fatal("invalid publication month") }
    source := "https://download.db-ip.com/free/dbip-country-lite-" + *month + ".csv.gz"
    client := &http.Client{Timeout: 90*time.Second, CheckRedirect: func(req *http.Request, via []*http.Request) error {
        if len(via) > 2 || req.URL.Scheme != "https" || req.URL.Hostname() != "download.db-ip.com" { return fmt.Errorf("untrusted country dataset redirect") }
        return nil
    }}
    response, err := client.Get(source)
    if err != nil { log.Fatal(err) }
    defer response.Body.Close()
    if response.StatusCode != http.StatusOK { log.Fatalf("country dataset download: HTTP %d", response.StatusCode) }
    compressed, err := io.ReadAll(io.LimitReader(response.Body, 32*1024*1024+1))
    if err != nil || len(compressed) > 32*1024*1024 { log.Fatal("country dataset size limit") }
    sum := sha256.Sum256(compressed)
    gz, err := gzip.NewReader(bytes.NewReader(compressed))
    if err != nil { log.Fatal(err) }
    defer gz.Close()
    expanded, err := io.ReadAll(io.LimitReader(gz, 128*1024*1024+1))
    if err != nil || len(expanded) > 128*1024*1024 { log.Fatal("country dataset decompression failed or exceeded size limit") }
    reader := csv.NewReader(bytes.NewReader(expanded))
    reader.FieldsPerRecord = 3
    dataset := geoaccess.Dataset{Country: "IR", Published: published.Format("2006-01-02"), Source: source, SourceSHA256: hex.EncodeToString(sum[:])}
    rows := 0
    for {
        record, err := reader.Read()
        if err == io.EOF { break }
        if err != nil { log.Fatal(err) }
        rows++
        if rows > 2000000 { log.Fatal("country dataset record limit") }
        if record[2] == "IR" { dataset.Ranges = append(dataset.Ranges, geoaccess.Range{Start: record[0], End: record[1]}) }
    }
    if rows < 100000 || len(dataset.Ranges) < 100 { log.Fatal("country data is incomplete") }
    raw, err := json.Marshal(dataset)
    if err != nil { log.Fatal(err) }
    if _, err := geoaccess.Load(bytes.NewReader(raw), time.Now().UTC()); err != nil { log.Fatal(err) }
    if err := os.MkdirAll(filepath.Dir(*output), 0755); err != nil { log.Fatal(err) }
    if err := os.WriteFile(*output, raw, 0644); err != nil { log.Fatal(err) }
    fmt.Printf("DB-IP Lite %s: %d source rows, %d Iran ranges, sha256:%x\n", *month, rows, len(dataset.Ranges), sum)
}
