package server

import (
	"crypto/sha256"
	"encoding/hex"
	"strings"
)

func computeTGFSBHash(fileUniqueID string, length int) string {
	uniqueID := strings.TrimSpace(fileUniqueID)
	if uniqueID == "" {
		return ""
	}

	sum := sha256.Sum256([]byte(uniqueID))
	full := hex.EncodeToString(sum[:])

	if length < 6 {
		length = 6
	}
	if length > 63 {
		length = 63
	}
	return full[:length]
}
