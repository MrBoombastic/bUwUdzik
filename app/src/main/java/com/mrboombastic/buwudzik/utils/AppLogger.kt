package com.mrboombastic.buwudzik.utils

import android.util.Log
import com.mrboombastic.buwudzik.BuildConfig

object AppLogger {
    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, "${getCallerInfo()}$msg")
        }
    }

    fun d(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.d(tag, "${getCallerInfo()}$msg", tr)
        }
    }

    fun v(tag: String, msg: String) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, "${getCallerInfo()}$msg")
        }
    }

    fun v(tag: String, msg: String, tr: Throwable) {
        if (BuildConfig.DEBUG) {
            Log.v(tag, "${getCallerInfo()}$msg", tr)
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

    private fun getCallerInfo(): String {
        // Optimize: throwable stack trace is usually faster than thread stack trace on Android
        val stackTrace = Throwable().stackTrace
        // Find the first frame that is NOT AppLogger and NOT internal VM calls
        val caller = stackTrace.firstOrNull { element ->
            val className = element.className
            className != AppLogger::class.java.name &&
                    className != "java.lang.Thread" &&
                    className != "dalvik.system.VMStack"
        }
        return if (caller != null) "* (${caller.fileName}:${caller.lineNumber}) " else ""
    }
}
