package server

import (
	"encoding/json"
	"net/http"
	"strings"
)

type preferencePayload struct {
	AutoplayNext *bool `json:"autoplayNext"`
	AutoplayPreviews *bool `json:"autoplayPreviews"`
	WifiOnlyDownloads *bool `json:"wifiOnlyDownloads"`
	DataSaver *bool `json:"dataSaver"`
	SpoilerShield *bool `json:"spoilerShield"`
	SkipIntro *bool `json:"skipIntro"`
	SkipRecap *bool `json:"skipRecap"`
	DefaultPlaybackSpeed *float64 `json:"defaultPlaybackSpeed"`
	DefaultAudioLanguage *string `json:"defaultAudioLanguage"`
	DefaultSubtitleLanguage *string `json:"defaultSubtitleLanguage"`
	SubtitlesEnabled *bool `json:"subtitlesEnabled"`
	NotificationsSocial *bool `json:"notificationsSocial"`
	NotificationsMessages *bool `json:"notificationsMessages"`
	NotificationsReleases *bool `json:"notificationsReleases"`
	PrivateAccount *bool `json:"privateAccount"`
}

func (s *Server) getSettings(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	if _,err:=s.db.Exec(r.Context(),
		"INSERT INTO user_preferences (user_id) VALUES ($1) ON CONFLICT DO NOTHING",
		userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	var autoplayNext,autoplayPreviews,wifiOnly,dataSaver,spoilerShield,skipIntro,skipRecap bool
	var speed float64
	var audioLang,subtitleLang string
	var subtitlesEnabled,notifySocial,notifyMessages,notifyReleases,privateAccount bool

	err:=s.db.QueryRow(r.Context(),`
		SELECT up.autoplay_next,up.autoplay_previews,up.wifi_only_downloads,up.data_saver,
		       up.spoiler_shield,up.skip_intro,up.skip_recap,up.default_playback_speed,
		       up.default_audio_language,up.default_subtitle_language,up.subtitles_enabled,
		       up.notifications_social,up.notifications_messages,up.notifications_releases,
		       p.private_account
		  FROM user_preferences up
		  JOIN profiles p ON p.user_id=up.user_id
		 WHERE up.user_id=$1
	`,userID).Scan(
		&autoplayNext,&autoplayPreviews,&wifiOnly,&dataSaver,
		&spoilerShield,&skipIntro,&skipRecap,&speed,
		&audioLang,&subtitleLang,&subtitlesEnabled,
		&notifySocial,&notifyMessages,&notifyReleases,&privateAccount,
	)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{
		"autoplayNext":autoplayNext,
		"autoplayPreviews":autoplayPreviews,
		"wifiOnlyDownloads":wifiOnly,
		"dataSaver":dataSaver,
		"spoilerShield":spoilerShield,
		"skipIntro":skipIntro,
		"skipRecap":skipRecap,
		"defaultPlaybackSpeed":speed,
		"defaultAudioLanguage":audioLang,
		"defaultSubtitleLanguage":subtitleLang,
		"subtitlesEnabled":subtitlesEnabled,
		"notificationsSocial":notifySocial,
		"notificationsMessages":notifyMessages,
		"notificationsReleases":notifyReleases,
		"privateAccount":privateAccount,
	})
}

func (s *Server) updateSettings(w http.ResponseWriter,r *http.Request) {
	userID:=userIDFromContext(r.Context())
	var body preferencePayload
	if err:=json.NewDecoder(r.Body).Decode(&body); err!=nil {
		writeError(w,http.StatusBadRequest,err)
		return
	}
	if body.DefaultPlaybackSpeed!=nil &&
		(*body.DefaultPlaybackSpeed<0.5 || *body.DefaultPlaybackSpeed>2.0) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid playback speed"})
		return
	}

	cleanLang:=func(v *string) *string {
		if v==nil { return nil }
		x:=strings.ToLower(strings.TrimSpace(*v))
		if len(x)>12 { x=x[:12] }
		return &x
	}
	body.DefaultAudioLanguage=cleanLang(body.DefaultAudioLanguage)
	body.DefaultSubtitleLanguage=cleanLang(body.DefaultSubtitleLanguage)

	if _,err:=s.db.Exec(r.Context(),
		"INSERT INTO user_preferences (user_id) VALUES ($1) ON CONFLICT DO NOTHING",
		userID); err!=nil {
		writeError(w,http.StatusInternalServerError,err)
		return
	}

	_,err:=s.db.Exec(r.Context(),`
		UPDATE user_preferences SET
		  autoplay_next=COALESCE($2,autoplay_next),
		  autoplay_previews=COALESCE($3,autoplay_previews),
		  wifi_only_downloads=COALESCE($4,wifi_only_downloads),
		  data_saver=COALESCE($5,data_saver),
		  spoiler_shield=COALESCE($6,spoiler_shield),
		  skip_intro=COALESCE($7,skip_intro),
		  skip_recap=COALESCE($8,skip_recap),
		  default_playback_speed=COALESCE($9,default_playback_speed),
		  default_audio_language=COALESCE($10,default_audio_language),
		  default_subtitle_language=COALESCE($11,default_subtitle_language),
		  subtitles_enabled=COALESCE($12,subtitles_enabled),
		  notifications_social=COALESCE($13,notifications_social),
		  notifications_messages=COALESCE($14,notifications_messages),
		  notifications_releases=COALESCE($15,notifications_releases),
		  updated_at=now()
		WHERE user_id=$1
	`,
		userID,
		body.AutoplayNext,body.AutoplayPreviews,body.WifiOnlyDownloads,body.DataSaver,
		body.SpoilerShield,body.SkipIntro,body.SkipRecap,body.DefaultPlaybackSpeed,
		body.DefaultAudioLanguage,body.DefaultSubtitleLanguage,body.SubtitlesEnabled,
		body.NotificationsSocial,body.NotificationsMessages,body.NotificationsReleases,
	)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	if body.PrivateAccount!=nil {
		if _,err:=s.db.Exec(r.Context(),
			"UPDATE profiles SET private_account=$2,updated_at=now() WHERE user_id=$1",
			userID,*body.PrivateAccount); err!=nil {
			writeError(w,http.StatusInternalServerError,err)
			return
		}
	}

	s.getSettings(w,r)
}
