package com.filmiqoo.app

data class DnaAffinity(
    val label:String,
    val weight:Double,
    val strength:Float
)

data class DnaBadge(
    val id:String,
    val title:String,
    val description:String,
    val level:Int
)

data class FilmDnaStats(
    val watchMinutes:Long,
    val watchedVersions:Long,
    val distinctTitles:Long,
    val completedVersions:Long,
    val completionRate:Double,
    val favorites:Long,
    val watchlist:Long
)

data class FilmDna(
    val viewerProfileId:String,
    val profileName:String,
    val kidsMode:Boolean,
    val archetype:String,
    val confidence:Int,
    val stats:FilmDnaStats,
    val kinds:List<DnaAffinity>,
    val languages:List<DnaAffinity>,
    val genres:List<DnaAffinity>,
    val decades:List<DnaAffinity>,
    val badges:List<DnaBadge>
)

class FilmDnaRepository(
    private val backend:BackendRepository
) {
    suspend fun load():FilmDna {
        val root=backend.getJson("/v1/profile/film-dna",authorized=true)
        val stats=root.optJSONObject("stats")
        return FilmDna(
            viewerProfileId=root.optString("viewerProfileId"),
            profileName=root.optString("profileName"),
            kidsMode=root.optBoolean("kidsMode"),
            archetype=root.optString("archetype","در حال شکل‌گیری"),
            confidence=root.optInt("confidence").coerceIn(0,100),
            stats=FilmDnaStats(
                watchMinutes=stats?.optLong("watchMinutes") ?: 0L,
                watchedVersions=stats?.optLong("watchedVersions") ?: 0L,
                distinctTitles=stats?.optLong("distinctTitles") ?: 0L,
                completedVersions=stats?.optLong("completedVersions") ?: 0L,
                completionRate=stats?.optDouble("completionRate") ?: 0.0,
                favorites=stats?.optLong("favorites") ?: 0L,
                watchlist=stats?.optLong("watchlist") ?: 0L
            ),
            kinds=parseAffinities(root,"kinds"),
            languages=parseAffinities(root,"languages"),
            genres=parseAffinities(root,"genres"),
            decades=parseAffinities(root,"decades"),
            badges=parseBadges(root)
        )
    }

    private fun parseAffinities(root:org.json.JSONObject,key:String):List<DnaAffinity> {
        val arr=root.optJSONArray(key) ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    DnaAffinity(
                        label=x.optString("label"),
                        weight=x.optDouble("weight"),
                        strength=x.optDouble("strength").toFloat().coerceIn(0f,1f)
                    )
                )
            }
        }
    }

    private fun parseBadges(root:org.json.JSONObject):List<DnaBadge> {
        val arr=root.optJSONArray("badges") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    DnaBadge(
                        id=x.optString("id"),
                        title=x.optString("title"),
                        description=x.optString("description"),
                        level=x.optInt("level",1).coerceIn(1,3)
                    )
                )
            }
        }
    }
}
