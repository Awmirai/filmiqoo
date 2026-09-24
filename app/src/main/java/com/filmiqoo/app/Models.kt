package com.filmiqoo.app

enum class MediaType { MOVIE, TV }

data class MediaItem(
    val id: Int,
    val type: MediaType,
    val title: String,
    val originalTitle: String = "",
    val overview: String = "",
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val vote: Double = 0.0,
    val date: String = "",
    val popularity: Double = 0.0,
    val backendId: String? = null,
    val mediaVersionId: String? = null,
    val streamReady: Boolean = false,
    val quality: String = ""
) {
    val year: String get() = date.take(4)
    val key: String get() = backendId ?: (type.name + "_" + id)
}

data class CastMember(
    val id: Int,
    val name: String,
    val character: String,
    val profilePath: String?
)

data class SeasonInfo(
    val number: Int,
    val name: String,
    val episodes: Int,
    val posterPath: String?,
    val airDate: String
)

data class FranchiseInfo(
    val id:Int,
    val name:String,
    val posterPath:String?,
    val backdropPath:String?,
    val parts:List<MediaItem>
)

data class MediaDetail(
    val media: MediaItem,
    val tagline: String,
    val genres: List<String>,
    val runtime: Int,
    val status: String,
    val cast: List<CastMember>,
    val trailerKey: String?,
    val recommendations: List<MediaItem>,
    val seasons: List<SeasonInfo>,
    val directors: List<CastMember> = emptyList(),
    val franchise: FranchiseInfo? = null
)

data class HomeBundle(
    val trending: List<MediaItem> = emptyList(),
    val popularMovies: List<MediaItem> = emptyList(),
    val popularTv: List<MediaItem> = emptyList(),
    val iranian: List<MediaItem> = emptyList(),
    val korean: List<MediaItem> = emptyList(),
    val bollywood: List<MediaItem> = emptyList(),
    val anime: List<MediaItem> = emptyList()
)

data class ChatMessage(
    val id: Long,
    val author: String,
    val text: String,
    val mine: Boolean = false,
    val spoiler: Boolean = false,
    val time: String = "اکنون"
)

data class Creator(
    val name: String,
    val handle: String,
    val followers: String,
    val bio: String,
    val verified: Boolean = true,
    val id: String = "",
    val entityType: String = "user",
    val avatarUrl: String = "",
    val coverUrl: String = ""
)

sealed interface OverlayRoute {
    data class Detail(val media: MediaItem) : OverlayRoute
    data class Story(val media: MediaItem, val index: Int = 0) : OverlayRoute
    data class SocialStories(val stories: List<SocialStory>, val index: Int = 0) : OverlayRoute
    data class Chat(val title: String, val media: MediaItem? = null) : OverlayRoute
    data class CreatorPage(val creator: Creator) : OverlayRoute
    data class PersonPage(val personId:Int,val name:String) : OverlayRoute
    data class WatchParty(val media: MediaItem? = null, val partyId: String? = null) : OverlayRoute
    data class Player(val target: PlaybackTarget) : OverlayRoute
    data class Room(val roomId: String, val title: String) : OverlayRoute
    data object Downloads : OverlayRoute
    data object Library : OverlayRoute
    data object History : OverlayRoute
    data object Inbox : OverlayRoute
    data object Settings : OverlayRoute
    data object ViewerProfiles : OverlayRoute
    data object ParentalGate : OverlayRoute
    data object ParentalControls : OverlayRoute
    data object Security : OverlayRoute
    data object Safety : OverlayRoute
    data object FollowRequests : OverlayRoute
    data object EditProfile : OverlayRoute
    data object Releases : OverlayRoute
    data class ChannelManage(val channelId:String,val name:String) : OverlayRoute
    data object CreatorStudio : OverlayRoute
    data object Auth : OverlayRoute
    data object Create : OverlayRoute
    data object Notifications : OverlayRoute
}
