package server

import (
	"net/http/httptest"
	"testing"
)

func TestSocialPageParamsDefaults(t *testing.T) {
	req:=httptest.NewRequest("GET","/v1/social/feed",nil)
	limit,offset:=socialPageParams(req,30,50)
	if limit!=30 || offset!=0 {
		t.Fatalf("got limit=%d offset=%d",limit,offset)
	}
}

func TestSocialPageParamsBoundsAndCursor(t *testing.T) {
	req:=httptest.NewRequest(
		"GET",
		"/v1/social/feed?limit=999&cursor=60",
		nil,
	)
	limit,offset:=socialPageParams(req,30,50)
	if limit!=50 || offset!=60 {
		t.Fatalf("got limit=%d offset=%d",limit,offset)
	}
}

func TestSocialPageParamsRejectsInvalidCursor(t *testing.T) {
	req:=httptest.NewRequest(
		"GET",
		"/v1/social/feed?limit=-3&cursor=nope",
		nil,
	)
	limit,offset:=socialPageParams(req,30,50)
	if limit!=1 || offset!=0 {
		t.Fatalf("got limit=%d offset=%d",limit,offset)
	}
}

func TestNextSocialCursor(t *testing.T) {
	if got:=nextSocialCursor(30,30,30); got!="60" {
		t.Fatalf("expected 60, got %#v",got)
	}
	if got:=nextSocialCursor(30,12,30); got!=nil {
		t.Fatalf("expected nil, got %#v",got)
	}
}
