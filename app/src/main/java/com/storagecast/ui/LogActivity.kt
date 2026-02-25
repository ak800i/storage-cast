package com.storagecast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.storagecast.R
import com.storagecast.databinding.ActivityLogBinding
import com.storagecast.log.AppLogger

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private val logListener: () -> Unit = {
        runOnUiThread { refreshLogs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.view_logs)

        binding.copyButton.setOnClickListener {
            val logs = AppLogger.getFormattedLogs()
            if (logs.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("StorageCast Logs", logs))
                Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.no_logs, Toast.LENGTH_SHORT).show()
            }
        }

        binding.clearButton.setOnClickListener {
            AppLogger.clear()
            Toast.makeText(this, R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        }

        refreshLogs()
    }

    private fun refreshLogs() {
        val entries = AppLogger.getEntries()
        if (entries.isEmpty()) {
            binding.logTextView.text = getString(R.string.no_logs)
        } else {
            val spannable = SpannableStringBuilder()
            entries.forEachIndexed { index, entry ->
                val start = spannable.length
                spannable.append(entry.format())
                val end = spannable.length
                val color = when (entry.level) {
                    AppLogger.LogLevel.WARNING -> ContextCompat.getColor(this, R.color.log_warning)
                    AppLogger.LogLevel.ERROR -> ContextCompat.getColor(this, R.color.log_error)
                    AppLogger.LogLevel.INFO -> ContextCompat.getColor(this, R.color.log_info)
                }
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    start, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (index < entries.size - 1) {
                    spannable.append("\n")
                }
            }
            binding.logTextView.text = spannable
        }
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLogger.addListener(logListener)
        refreshLogs()
    }

    override fun onPause() {
        super.onPause()
        AppLogger.removeListener(logListener)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
