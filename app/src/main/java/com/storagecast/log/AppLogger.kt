package com.storagecast.log

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object AppLogger {

    enum class LogLevel {
        INFO, WARNING, ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val tag: String,
        val message: String,
        val level: LogLevel = LogLevel.INFO
    ) {
        fun format(): String {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
            val levelTag = when (level) {
                LogLevel.INFO -> "I"
                LogLevel.WARNING -> "W"
                LogLevel.ERROR -> "E"
            }
            return "[$time] $levelTag/$tag: $message"
        }
    }

    private val entries = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun log(tag: String, message: String) {
        info(tag, message)
    }

    fun info(tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message, LogLevel.INFO)
        entries.add(entry)
        Log.i(tag, message)
        listeners.forEach { it() }
    }

    fun warn(tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message, LogLevel.WARNING)
        entries.add(entry)
        Log.w(tag, message)
        listeners.forEach { it() }
    }

    fun error(tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message, LogLevel.ERROR)
        entries.add(entry)
        Log.e(tag, message)
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
