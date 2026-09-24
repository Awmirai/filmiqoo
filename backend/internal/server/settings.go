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
	DownloadRequiresCharging *bool `json:"downloadRequiresCharging"`
	DownloadBatteryNotLow *bool `json:"downloadBatteryNotLow"`
	DownloadAvoidRoaming *bool `json:"downloadAvoidRoaming"`
	DataSaver *bool `json:"dataSaver"`
	SpoilerShield *bool `json:"spoilerShield"`
	SkipIntro *bool `json:"skipIntro"`
	SkipRecap *bool `json:"skipRecap"`
	SkipCredits *bool `json:"skipCredits"`
	SubtitleScale *float64 `json:"subtitleScale"`
	SubtitleBottomPadding *float64 `json:"subtitleBottomPadding"`
	SubtitleTextColor *string `json:"subtitleTextColor"`
	SubtitleBackgroundOpacity *float64 `json:"subtitleBackgroundOpacity"`
	SubtitleEdgeStyle *string `json:"subtitleEdgeStyle"`
	PlayerResizeMode *string `json:"playerResizeMode"`
	SmartDownloads *bool `json:"smartDownloads"`
	DownloadStorageLimitMB *int64 `json:"downloadStorageLimitMb"`
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

	var autoplayNext,autoplayPreviews,wifiOnly,downloadRequiresCharging,downloadBatteryNotLow,downloadAvoidRoaming,dataSaver,spoilerShield,skipIntro,skipRecap,skipCredits bool
	var speed,subtitleScale,subtitleBottomPadding,subtitleBackgroundOpacity float64
	var downloadStorageLimitMB int64
	var smartDownloads bool
	var audioLang,subtitleLang,playerResizeMode,subtitleTextColor,subtitleEdgeStyle string
	var subtitlesEnabled,notifySocial,notifyMessages,notifyReleases,privateAccount bool

	err:=s.db.QueryRow(r.Context(),`
		SELECT up.autoplay_next,up.autoplay_previews,up.wifi_only_downloads,
		       up.download_requires_charging,up.download_battery_not_low,up.download_avoid_roaming,
		       up.data_saver,
		       up.spoiler_shield,up.skip_intro,up.skip_recap,up.skip_credits,
		       up.subtitle_scale,up.subtitle_bottom_padding,
		       up.subtitle_text_color,up.subtitle_background_opacity,up.subtitle_edge_style,
		       up.player_resize_mode,
		       up.smart_downloads,up.download_storage_limit_mb,
		       up.default_playback_speed,
		       up.default_audio_language,up.default_subtitle_language,up.subtitles_enabled,
		       up.notifications_social,up.notifications_messages,up.notifications_releases,
		       p.private_account
		  FROM user_preferences up
		  JOIN profiles p ON p.user_id=up.user_id
		 WHERE up.user_id=$1
	`,userID).Scan(
		&autoplayNext,&autoplayPreviews,&wifiOnly,
		&downloadRequiresCharging,&downloadBatteryNotLow,&downloadAvoidRoaming,
		&dataSaver,&spoilerShield,&skipIntro,&skipRecap,&skipCredits,
		&subtitleScale,&subtitleBottomPadding,
		&subtitleTextColor,&subtitleBackgroundOpacity,&subtitleEdgeStyle,
		&playerResizeMode,&smartDownloads,&downloadStorageLimitMB,&speed,
		&audioLang,&subtitleLang,&subtitlesEnabled,
		&notifySocial,&notifyMessages,&notifyReleases,&privateAccount,
	)
	if err!=nil { writeError(w,http.StatusInternalServerError,err); return }

	writeJSON(w,http.StatusOK,map[string]any{
		"autoplayNext":autoplayNext,
		"autoplayPreviews":autoplayPreviews,
		"wifiOnlyDownloads":wifiOnly,
		"downloadRequiresCharging":downloadRequiresCharging,
		"downloadBatteryNotLow":downloadBatteryNotLow,
		"downloadAvoidRoaming":downloadAvoidRoaming,
		"dataSaver":dataSaver,
		"spoilerShield":spoilerShield,
		"skipIntro":skipIntro,
		"skipRecap":skipRecap,
		"skipCredits":skipCredits,
		"subtitleScale":subtitleScale,
		"subtitleBottomPadding":subtitleBottomPadding,
		"subtitleTextColor":subtitleTextColor,
		"subtitleBackgroundOpacity":subtitleBackgroundOpacity,
		"subtitleEdgeStyle":subtitleEdgeStyle,
		"playerResizeMode":playerResizeMode,
		"smartDownloads":smartDownloads,
		"downloadStorageLimitMb":downloadStorageLimitMB,
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
	if body.SubtitleScale!=nil && (*body.SubtitleScale<0.7 || *body.SubtitleScale>1.6) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid subtitle scale"})
		return
	}
	if body.SubtitleBottomPadding!=nil &&
		(*body.SubtitleBottomPadding<0.02 || *body.SubtitleBottomPadding>0.28) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid subtitle bottom padding"})
		return
	}
	if body.SubtitleBackgroundOpacity!=nil &&
		(*body.SubtitleBackgroundOpacity<0.0 || *body.SubtitleBackgroundOpacity>1.0) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid subtitle background opacity"})
		return
	}
	if body.SubtitleTextColor!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.SubtitleTextColor))
		if v!="white" && v!="yellow" && v!="cyan" {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid subtitle text color"})
			return
		}
		body.SubtitleTextColor=&v
	}
	if body.SubtitleEdgeStyle!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.SubtitleEdgeStyle))
		if v!="none" && v!="outline" && v!="shadow" {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid subtitle edge style"})
			return
		}
		body.SubtitleEdgeStyle=&v
	}
	if body.PlayerResizeMode!=nil {
		v:=strings.ToLower(strings.TrimSpace(*body.PlayerResizeMode))
		if v!="fit" && v!="fill" && v!="zoom" {
			writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid player resize mode"})
			return
		}
		body.PlayerResizeMode=&v
	}
	if body.DownloadStorageLimitMB!=nil &&
		(*body.DownloadStorageLimitMB!=0 &&
		 (*body.DownloadStorageLimitMB<1024 || *body.DownloadStorageLimitMB>204800)) {
		writeJSON(w,http.StatusBadRequest,map[string]string{"error":"invalid download storage limit"})
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
		  download_requires_charging=COALESCE($5,download_requires_charging),
		  download_battery_not_low=COALESCE($6,download_battery_not_low),
		  download_avoid_roaming=COALESCE($7,download_avoid_roaming),
		  data_saver=COALESCE($8,data_saver),
		  spoiler_shield=COALESCE($9,spoiler_shield),
		  skip_intro=COALESCE($10,skip_intro),
		  skip_recap=COALESCE($11,skip_recap),
		  skip_credits=COALESCE($12,skip_credits),
		  subtitle_scale=COALESCE($13,subtitle_scale),
		  subtitle_bottom_padding=COALESCE($14,subtitle_bottom_padding),
		  subtitle_text_color=COALESCE($15,subtitle_text_color),
		  subtitle_background_opacity=COALESCE($16,subtitle_background_opacity),
		  subtitle_edge_style=COALESCE($17,subtitle_edge_style),
		  player_resize_mode=COALESCE($18,player_resize_mode),
		  smart_downloads=COALESCE($19,smart_downloads),
		  download_storage_limit_mb=COALESCE($20,download_storage_limit_mb),
		  default_playback_speed=COALESCE($21,default_playback_speed),
		  default_audio_language=COALESCE($22,default_audio_language),
		  default_subtitle_language=COALESCE($23,default_subtitle_language),
		  subtitles_enabled=COALESCE($24,subtitles_enabled),
		  notifications_social=COALESCE($25,notifications_social),
		  notifications_messages=COALESCE($26,notifications_messages),
		  notifications_releases=COALESCE($27,notifications_releases),
		  updated_at=now()
		WHERE user_id=$1
	`,
		userID,
		body.AutoplayNext,body.AutoplayPreviews,body.WifiOnlyDownloads,
		body.DownloadRequiresCharging,body.DownloadBatteryNotLow,body.DownloadAvoidRoaming,
		body.DataSaver,body.SpoilerShield,body.SkipIntro,body.SkipRecap,body.SkipCredits,
		body.SubtitleScale,body.SubtitleBottomPadding,
		body.SubtitleTextColor,body.SubtitleBackgroundOpacity,body.SubtitleEdgeStyle,
		body.PlayerResizeMode,body.SmartDownloads,body.DownloadStorageLimitMB,body.DefaultPlaybackSpeed,
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
