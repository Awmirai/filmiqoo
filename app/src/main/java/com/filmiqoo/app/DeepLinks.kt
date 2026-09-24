package com.filmiqoo.app

import android.content.Context
import android.net.Uri

object FilmiqooDeepLinks {
    fun play(mediaVersionId:String,positionMs:Long=0L):String =
        Uri.Builder()
            .scheme("filmiqoo")
            .authority("play")
            .appendPath(mediaVersionId)
            .apply {
                if(positionMs>0L) appendQueryParameter("t",positionMs.toString())
            }
            .build()
            .toString()

    fun title(mediaTitleId:String):String =
        Uri.Builder().scheme("filmiqoo").authority("title")
            .appendPath(mediaTitleId).build().toString()

    fun creator(userId:String):String =
        Uri.Builder().scheme("filmiqoo").authority("creator")
            .appendPath(userId).build().toString()

    fun channel(channelId:String):String =
        Uri.Builder().scheme("filmiqoo").authority("channel")
            .appendPath(channelId).build().toString()

    fun reel(reelId:String):String =
        Uri.Builder().scheme("filmiqoo").authority("reel")
            .appendPath(reelId).build().toString()

    fun collection(collectionId:String):String =
        Uri.Builder().scheme("filmiqoo").authority("collection")
            .appendPath(collectionId).build().toString()

    fun watchParty(partyId:String,inviteCode:String?=null):String =
        Uri.Builder().scheme("filmiqoo").authority("party")
            .appendPath(partyId)
            .apply {
                if(!inviteCode.isNullOrBlank()) appendQueryParameter("invite",inviteCode)
            }
            .build()
            .toString()

    fun room(roomId:String,title:String=""):String =
        Uri.Builder().scheme("filmiqoo").authority("room")
            .appendPath(roomId)
            .apply { if(title.isNotBlank()) appendQueryParameter("title",title) }
            .build()
            .toString()

    fun roomInvite(code:String):String =
        Uri.Builder().scheme("filmiqoo").authority("room-invite")
            .appendPath(code).build().toString()

    fun share(context:Context,label:String,link:String) {
        shareText(
            context,
            buildString {
                append(label.trim())
                if(label.isNotBlank()) append("\n")
                append(link)
            }
        )
    }
}
