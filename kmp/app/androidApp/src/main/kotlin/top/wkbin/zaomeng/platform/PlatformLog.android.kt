package top.wkbin.zaomeng.platform

import android.util.Log

object PlatformLog {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        RuntimeLogBuffer.append('D', tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        RuntimeLogBuffer.append('I', tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, message, throwable) else Log.w(tag, message)
        RuntimeLogBuffer.append('W', tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) Log.e(tag, message, throwable) else Log.e(tag, message)
        RuntimeLogBuffer.append('E', tag, message, throwable)
    }
}
