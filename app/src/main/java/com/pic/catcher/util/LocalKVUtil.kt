package com.pic.catcher.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.lu.magic.util.AppUtil
import com.pic.catcher.BuildConfig

class LocalKVUtil {
    companion object {
        const val defaultTableName = "app"

        @SuppressLint("WorldReadableFiles")
        @JvmStatic
        fun getTable(name: String): SharedPreferences {
            val packageName = "com.evo.piccatcher"
            if (packageName == AppUtil.getContext().packageName) {
                return try {
                    @Suppress("DEPRECATION")
                    AppUtil.getContext().getSharedPreferences(name, Context.MODE_WORLD_READABLE)
                } catch (e: Exception) {
                    AppUtil.getContext().getSharedPreferences(name, Context.MODE_PRIVATE)
                }
            }
            // xposed hook
            return LspUtil.getTable(name)
        }

        @JvmStatic
        fun getDefaultTable(): SharedPreferences {
            return getTable(defaultTableName)
        }
    }
}
