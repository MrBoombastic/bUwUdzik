package com.mrboombastic.buwudzik.utils

import android.util.Log
import com.mrboombastic.buwudzik.BuildConfig

object AppLogger {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg)
        }
    }

    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, msg, tr)
        }
    }

    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, msg)
        }
    }

    fun v(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, msg, tr)
        }
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    fun i(tag: String, msg: String, tr: Throwable) {
        Log.i(tag, msg, tr)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable) {
        Log.w(tag, msg, tr)
    }

    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable) {
        Log.e(tag, msg, tr)
    }
}
