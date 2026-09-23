package server

import (
	"crypto/md5"
	"encoding/hex"
	"strconv"
)

func computeTGFSBHash(fileName string,fileSize int64,mimeType string,fileID int64,length int) string {
	h:=md5.New()
	_,_=h.Write([]byte(fileName))
	_,_=h.Write([]byte(strconv.FormatInt(fileSize,10)))
	_,_=h.Write([]byte(mimeType))
	_,_=h.Write([]byte(strconv.FormatInt(fileID,10)))
	full:=hex.EncodeToString(h.Sum(nil))
	if length<5 { length=6 }
	if length>len(full) { length=len(full) }
	return full[:length]
}
