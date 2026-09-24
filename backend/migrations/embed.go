package migrations

import (
	"embed"
	"sort"
	"strings"
)

// Files contains every versioned SQL migration compiled into the backend binary.
//
//go:embed *.sql
var Files embed.FS

func Names() ([]string,error) {
	entries,err:=Files.ReadDir(".")
	if err!=nil { return nil,err }
	names:=make([]string,0,len(entries))
	for _,entry:=range entries {
		if entry.IsDir() { continue }
		name:=entry.Name()
		if strings.HasSuffix(strings.ToLower(name),".sql") {
			names=append(names,name)
		}
	}
	sort.Strings(names)
	return names,nil
}

func Read(name string) ([]byte,error) {
	return Files.ReadFile(name)
}
