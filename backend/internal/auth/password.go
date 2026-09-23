package auth

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
	"strings"

	"golang.org/x/crypto/argon2"
)

const (
	memory uint32 = 64 * 1024
	iterations uint32 = 3
	parallelism uint8 = 2
	saltLength uint32 = 16
	keyLength uint32 = 32
)

func HashPassword(password string) (string,error) {
	salt:=make([]byte,saltLength)
	if _,err:=rand.Read(salt); err!=nil { return "",err }
	hash:=argon2.IDKey([]byte(password),salt,iterations,memory,parallelism,keyLength)
	return fmt.Sprintf("$argon2id$v=19$m=%d,t=%d,p=%d$%s$%s",
		memory,iterations,parallelism,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	),nil
}

func VerifyPassword(password, encoded string) bool {
	parts:=strings.Split(encoded,"$")
	if len(parts)!=6 || parts[1]!="argon2id" { return false }

	var m,t uint32
	var p uint8
	if _,err:=fmt.Sscanf(parts[3],"m=%d,t=%d,p=%d",&m,&t,&p); err!=nil { return false }

	salt,err:=base64.RawStdEncoding.DecodeString(parts[4])
	if err!=nil { return false }
	expected,err:=base64.RawStdEncoding.DecodeString(parts[5])
	if err!=nil { return false }

	actual:=argon2.IDKey([]byte(password),salt,t,m,p,uint32(len(expected)))
	return subtle.ConstantTimeCompare(actual,expected)==1
}
