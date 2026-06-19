package com.storagecast.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.storagecast.R
import com.storagecast.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "storagecast_settings"
        const val KEY_FILTER_SHORT_VIDEOS = "filter_short_videos"
        const val KEY_MIN_DURATION_MINUTES = "min_duration_minutes"
        const val DEFAULT_MIN_DURATION_MINUTES = 18
        const val KEY_REALTIME_TRANSCODE = "realtime_transcode"
        const val DEFAULT_REALTIME_TRANSCODE = false
        const val KEY_HLS_SEEKING = "hls_seeking"
        // Off by default: the live progressive-fMP4 transcode path is the proven-working
        // one on real hardware (incl. first-gen Chromecast, which plays it but failed on
        // HLS fMP4/CMAF). HLS gives native seeking instead of seek-by-restart, but it is
        // not supported on every receiver, so it stays opt-in via the video overflow menu.
        const val DEFAULT_HLS_SEEKING = false

        private const val OPENSUBTITLES_PREFS = "opensubtitles"

        fun getMinDurationMs(context: Context): Long {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_FILTER_SHORT_VIDEOS, true)) return 0L
            val minutes = prefs.getInt(KEY_MIN_DURATION_MINUTES, DEFAULT_MIN_DURATION_MINUTES)
            return minutes * 60L * 1000L
        }

        fun getRealtimeTranscode(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_REALTIME_TRANSCODE, DEFAULT_REALTIME_TRANSCODE)
        }

        fun setRealtimeTranscode(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_REALTIME_TRANSCODE, enabled)
                .apply()
        }

        fun getHlsSeeking(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_HLS_SEEKING, DEFAULT_HLS_SEEKING)
        }

        fun setHlsSeeking(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_HLS_SEEKING, enabled)
                .apply()
        }
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        binding.filterSwitch.isChecked = prefs.getBoolean(KEY_FILTER_SHORT_VIDEOS, true)
        binding.minDurationInput.setText(
            prefs.getInt(KEY_MIN_DURATION_MINUTES, DEFAULT_MIN_DURATION_MINUTES).toString()
        )
        updateMinDurationEnabled()

        val osprefs = getOpenSubtitlesPrefs()
        binding.apiKeyInput.setText(osprefs.getString("api_key", ""))
        binding.usernameInput.setText(osprefs.getString("username", ""))
        binding.passwordInput.setText(osprefs.getString("password", ""))
    }

    private fun setupListeners() {
        binding.filterSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_FILTER_SHORT_VIDEOS, isChecked)
                .apply()
            updateMinDurationEnabled()
        }

        binding.minDurationInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveMinDuration()
        }

        binding.saveCredentialsButton.setOnClickListener {
            saveOpenSubtitlesCredentials()
        }
    }

    private fun updateMinDurationEnabled() {
        val enabled = binding.filterSwitch.isChecked
        binding.minDurationInput.isEnabled = enabled
        binding.minDurationInput.alpha = if (enabled) 1.0f else 0.5f
    }

    private fun saveMinDuration() {
        val text = binding.minDurationInput.text.toString()
        val minutes = text.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_MIN_DURATION_MINUTES
        binding.minDurationInput.setText(minutes.toString())
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MIN_DURATION_MINUTES, minutes)
            .apply()
    }

    private fun saveOpenSubtitlesCredentials() {
        val key = binding.apiKeyInput.text.toString().trim()
        val user = binding.usernameInput.text.toString().trim()
        val pass = binding.passwordInput.text.toString().trim()

        if (key.isBlank() || user.isBlank() || pass.isBlank()) {
            Toast.makeText(this, R.string.opensubtitles_credentials_required, Toast.LENGTH_SHORT).show()
            return
        }

        getOpenSubtitlesPrefs().edit()
            .putString("api_key", key)
            .putString("username", user)
            .putString("password", pass)
            .apply()

        Toast.makeText(this, R.string.opensubtitles_credentials_saved, Toast.LENGTH_SHORT).show()
    }

    private fun getOpenSubtitlesPrefs(): SharedPreferences {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        return EncryptedSharedPreferences.create(
            OPENSUBTITLES_PREFS,
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun onPause() {
        super.onPause()
        saveMinDuration()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
