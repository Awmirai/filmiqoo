package com.filmiqoo.app

import android.content.Context

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("filmiqoo_preview", Context.MODE_PRIVATE)

    fun contains(bucket: String, key: String): Boolean {
        return prefs.getStringSet(bucket, emptySet())?.contains(key) == true
    }

    fun toggle(bucket: String, key: String): Boolean {
        val set = prefs.getStringSet(bucket, emptySet())?.toMutableSet() ?: mutableSetOf()
        val active = if (set.contains(key)) {
            set.remove(key)
            false
        } else {
            set.add(key)
            true
        }
        prefs.edit().putStringSet(bucket, set).apply()
        return active
    }

    fun count(bucket: String): Int = prefs.getStringSet(bucket, emptySet())?.size ?: 0

    fun getBoolean(key:String,default:Boolean=false):Boolean =
        prefs.getBoolean(key,default)

    fun putBoolean(key:String,value:Boolean) {
        prefs.edit().putBoolean(key,value).apply()
    }
}
