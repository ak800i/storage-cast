package com.storagecast.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.storagecast.R
import com.storagecast.databinding.ActivityLogBinding
import com.storagecast.log.AppLogger

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding

    private val logListener: () -> Unit = {
        runOnUiThread { refreshLogs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        val logs = AppLogger.getFormattedLogs()
        binding.logTextView.text = logs.ifEmpty { getString(R.string.no_logs) }
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
