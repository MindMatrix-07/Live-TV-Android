package com.livetv.android

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object DebugLogStore {
    private const val MAX_ENTRIES = 220
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val entries = ArrayDeque<String>()
    private val lock = Any()

    fun add(tag: String, message: String, throwable: Throwable? = null) {
        val line = buildString {
            append(timestampFormat.format(Date()))
            append(" [")
            append(tag)
            append("] ")
            append(message)
            throwable?.let {
                append(" | ")
                append(it.javaClass.simpleName)
                append(": ")
                append(it.message ?: "Unknown error")
            }
        }

        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            entries.addLast(line)
        }
    }

    fun dump(): String =
        synchronized(lock) {
            entries.joinToString(separator = "\n")
        }
}
