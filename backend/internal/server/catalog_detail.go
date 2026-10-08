package server

import "encoding/json"

func decodeJSONOrEmptyArray(raw []byte) any {
	if len(raw) == 0 {
		return []any{}
	}
	var value any
 if err := json.Unmarshal(raw, &value); err != nil || value == nil {
		return []any{}
	}
	return value
}
