package server

import (
	"net/http"
	"strconv"
	"strings"
)

func socialPageParams(
	r *http.Request,
	defaultLimit int,
	maxLimit int,
) (limit int, offset int) {
	limit=defaultLimit
	if raw:=strings.TrimSpace(r.URL.Query().Get("limit")); raw!="" {
		if parsed,err:=strconv.Atoi(raw); err==nil {
			limit=parsed
		}
	}
	if limit<1 { limit=1 }
	if limit>maxLimit { limit=maxLimit }

	offset=0
	if raw:=strings.TrimSpace(r.URL.Query().Get("cursor")); raw!="" {
		if parsed,err:=strconv.Atoi(raw); err==nil && parsed>0 {
			offset=parsed
		}
	}
	return
}

func nextSocialCursor(offset int,count int,limit int) any {
	if count<limit {
		return nil
	}
	return strconv.Itoa(offset+count)
}
