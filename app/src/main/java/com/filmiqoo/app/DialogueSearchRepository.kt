package com.filmiqoo.app

import java.net.URLEncoder

data class DialogueCue(
    val language:String,
    val startMs:Long,
    val endMs:Long,
    val text:String
)

class DialogueSearchRepository(
    private val backend:BackendRepository
) {
    suspend fun search(
        mediaVersionId:String,
        query:String,
        language:String?=null
    ):List<DialogueCue> {
        val encoded=URLEncoder.encode(query.trim(),"UTF-8")
        val lang=language?.takeIf(String::isNotBlank)
            ?.let { "&lang="+URLEncoder.encode(it,"UTF-8") }
            .orEmpty()
        val root=backend.getJson(
            "/v1/playback/"+mediaVersionId+"/dialogue-search?q="+encoded+lang+"&limit=60",
            authorized=true
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    DialogueCue(
                        language=x.optString("language"),
                        startMs=x.optLong("startMs"),
                        endMs=x.optLong("endMs"),
                        text=x.optString("text")
                    )
                )
            }
        }
    }
}
