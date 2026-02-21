package com.storagecast.log

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {

    data class LogEntry(
        val timestamp: Long,
        val tag: String,
        val message: String
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            return "[$time] $tag: $message"
        }
    }

    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun log(tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message)
        entries.add(entry)
        Log.d(tag, message)
        listeners.forEach { it() }
    }

    fun getEntries(): List<LogEntry> = entries.toList()

    fun clear() {
        entries.clear()
        listeners.forEach { it() }
    }

    fun getFormattedLogs(): String {
        return entries.joinToString("\n") { it.format() }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }
}
