package com.yourbrowser.adblock

import android.util.Log
import com.example.BuildConfig

object AdblockLogger {
    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, message)
        }
    }
}
