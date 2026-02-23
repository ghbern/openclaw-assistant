package com.openclaw.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID
import org.json.JSONObject

/**
 * Secure settings storage
 */
class SettingsRepository(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // Webhook URL (required)
    var webhookUrl: String
        get() = prefs.getString(KEY_WEBHOOK_URL, "") ?: ""
        set(value) {
            if (value != webhookUrl) {
                prefs.edit().putString(KEY_WEBHOOK_URL, value).apply()
                isVerified = false
            }
        }

    // Auth Token (optional)
    var authToken: String
        get() = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    // Session ID (auto-generated)
    var sessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION_ID, null)
            return existing ?: generateNewSessionId().also { sessionId = it }
        }
        set(value) = prefs.edit().putString(KEY_SESSION_ID, value).apply()



    // Hotword enabled
    var hotwordEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOTWORD_ENABLED, value).apply()

    // Wake word selection (preset or custom)
    var wakeWordPreset: String
        get() = prefs.getString(KEY_WAKE_WORD_PRESET, WAKE_WORD_OPEN_CLAW) ?: WAKE_WORD_OPEN_CLAW
        set(value) = prefs.edit().putString(KEY_WAKE_WORD_PRESET, value).apply()

    // Custom wake word (when preset is "custom")
    var customWakeWord: String
        get() = prefs.getString(KEY_CUSTOM_WAKE_WORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_WAKE_WORD, value).apply()

    // Get the actual wake words list for Vosk
    fun getWakeWords(): List<String> {
        return when (wakeWordPreset) {
            WAKE_WORD_OPEN_CLAW -> listOf("open claw")
            WAKE_WORD_HEY_ASSISTANT -> listOf("hey assistant")
            WAKE_WORD_JARVIS -> listOf("jarvis")
            WAKE_WORD_COMPUTER -> listOf("computer")
            WAKE_WORD_CUSTOM -> {
                val custom = customWakeWord.trim().lowercase()
                if (custom.isNotEmpty()) listOf(custom) else listOf("open claw")
            }
            else -> listOf("open claw")
        }
    }

    // Get display name for current wake word
    fun getWakeWordDisplayName(): String {
        return when (wakeWordPreset) {
            WAKE_WORD_OPEN_CLAW -> "Open Claw"
            WAKE_WORD_HEY_ASSISTANT -> "Hey Assistant"
            WAKE_WORD_JARVIS -> "Jarvis"
            WAKE_WORD_COMPUTER -> "Computer"
            WAKE_WORD_CUSTOM -> customWakeWord.ifEmpty { "Custom" }
            else -> "Open Claw"
        }
    }

    // TTS enabled
    var ttsEnabled: Boolean
        get() = prefs.getBoolean(KEY_TTS_ENABLED, true) // Default true as per user request
        set(value) = prefs.edit().putBoolean(KEY_TTS_ENABLED, value).apply()

    // Continuous mode
    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_CONTINUOUS_MODE, value).apply()

    // Resume Latest Session
    var resumeLatestSession: Boolean
        get() = prefs.getBoolean(KEY_RESUME_LATEST_SESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_RESUME_LATEST_SESSION, value).apply()

    // TTS Speed
    var ttsSpeed: Float
        get() = prefs.getFloat(KEY_TTS_SPEED, 1.2f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEED, value).apply()

    // ElevenLabs API key (optional, when present is primary TTS)
    var elevenLabsApiKey: String
        get() = prefs.getString(KEY_ELEVENLABS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_API_KEY, value.trim()).apply()

    // Default ElevenLabs voice ID
    var elevenLabsDefaultVoiceId: String
        get() = prefs.getString(KEY_ELEVENLABS_DEFAULT_VOICE_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_DEFAULT_VOICE_ID, value.trim()).apply()

    // ElevenLabs model ID
    var elevenLabsModelId: String
        get() = prefs.getString(KEY_ELEVENLABS_MODEL_ID, "eleven_turbo_v2_5") ?: "eleven_turbo_v2_5"
        set(value) = prefs.edit().putString(KEY_ELEVENLABS_MODEL_ID, value.trim()).apply()

    // Agent -> voice mapping JSON, e.g. {"main":"voiceIdA","coder":"voiceIdB"}
    var agentVoiceMappingJson: String
        get() = prefs.getString(KEY_AGENT_VOICE_MAPPING_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AGENT_VOICE_MAPPING_JSON, value.trim()).apply()

    fun getElevenLabsVoiceIdForAgent(agentId: String?): String {
        val id = agentId?.trim().orEmpty()
        if (id.isNotEmpty()) {
            try {
                val obj = JSONObject(agentVoiceMappingJson.ifBlank { "{}" })
                val mapped = obj.optString(id, "")
                if (mapped.isNotBlank()) return mapped
            } catch (_: Exception) {
            }
        }
        return elevenLabsDefaultVoiceId
    }

    // TTS Engine
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_ENGINE, value).apply()

    // Gateway Port for WebSocket agent list connection (default 18789)

    var gatewayPort: Int
        get() = prefs.getInt(KEY_GATEWAY_PORT, 18789)
        set(value) = prefs.edit().putInt(KEY_GATEWAY_PORT, value).apply()

    // Speech recognition silence timeout in ms (default 5000ms)
    var speechSilenceTimeout: Long
        get() = prefs.getLong(KEY_SPEECH_SILENCE_TIMEOUT, 5000L)
        set(value) = prefs.edit().putLong(KEY_SPEECH_SILENCE_TIMEOUT, value).apply()

    // Speech recognition language (BCP-47 tag, empty = system default)
    var speechLanguage: String
        get() = prefs.getString(KEY_SPEECH_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPEECH_LANGUAGE, value).apply()

    // Thinking sound enabled
    var thinkingSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_THINKING_SOUND_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_THINKING_SOUND_ENABLED, value).apply()

    // Connection Verified
    var isVerified: Boolean
        get() = prefs.getBoolean(KEY_IS_VERIFIED, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_VERIFIED, value).apply()

    // Default Agent ID
    var defaultAgentId: String
        get() = prefs.getString(KEY_DEFAULT_AGENT_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_DEFAULT_AGENT_ID, value).apply()

    /**
     * Get the chat completions URL.
     * Supports both base URL and full path, but enforces HTTPS-only.
     */
    fun getChatCompletionsUrl(): String {
        val url = webhookUrl.trim().trimEnd('/')
        if (url.isBlank()) return ""
        if (!url.startsWith("https://") && !url.startsWith("http://")) return ""
        return if (url.contains("/v1/")) url
        else "$url/v1/chat/completions"
    }

    /**
     * Get the base URL (without path) for WebSocket connections.
     * Extracts base from full path URLs, or returns as-is for base URLs.
     */
    fun getBaseUrl(): String {
        val url = webhookUrl.trimEnd('/')
        if (!url.startsWith("https://") && !url.startsWith("http://")) return ""
        val idx = url.indexOf("/v1/")
        return if (idx > 0) url.substring(0, idx) else url
    }

    // Optional certificate pin map as JSON: {"host":["sha256/..."]}
    var certPinsJson: String
        get() = prefs.getString(KEY_CERT_PINS_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CERT_PINS_JSON, value.trim()).apply()

    // Check if configured
    fun isConfigured(): Boolean {
        return webhookUrl.isNotBlank() && isVerified
    }

    // Generate new session ID
    fun generateNewSessionId(): String {
        return UUID.randomUUID().toString()
    }

    // Reset session
    fun resetSession() {
        sessionId = generateNewSessionId()
    }

    companion object {
        private const val PREFS_NAME = "openclaw_secure_prefs"
        private const val KEY_WEBHOOK_URL = "webhook_url"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_HOTWORD_ENABLED = "hotword_enabled"
        private const val KEY_WAKE_WORD_PRESET = "wake_word_preset"
        private const val KEY_CUSTOM_WAKE_WORD = "custom_wake_word"
        private const val KEY_IS_VERIFIED = "is_verified"
        private const val KEY_TTS_ENABLED = "tts_enabled"
        private const val KEY_CONTINUOUS_MODE = "continuous_mode"
        private const val KEY_RESUME_LATEST_SESSION = "resume_latest_session"
        private const val KEY_TTS_SPEED = "tts_speed"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_ELEVENLABS_API_KEY = "elevenlabs_api_key"
        private const val KEY_ELEVENLABS_DEFAULT_VOICE_ID = "elevenlabs_default_voice_id"
        private const val KEY_ELEVENLABS_MODEL_ID = "elevenlabs_model_id"
        private const val KEY_AGENT_VOICE_MAPPING_JSON = "agent_voice_mapping_json"
        private const val KEY_CERT_PINS_JSON = "cert_pins_json"
        private const val KEY_GATEWAY_PORT = "gateway_port"
        private const val KEY_DEFAULT_AGENT_ID = "default_agent_id"
        private const val KEY_SPEECH_SILENCE_TIMEOUT = "speech_silence_timeout"
        private const val KEY_THINKING_SOUND_ENABLED = "thinking_sound_enabled"
        private const val KEY_SPEECH_LANGUAGE = "speech_language"

        // Wake word presets
        const val WAKE_WORD_OPEN_CLAW = "open_claw"
        const val WAKE_WORD_HEY_ASSISTANT = "hey_assistant"
        const val WAKE_WORD_JARVIS = "jarvis"
        const val WAKE_WORD_COMPUTER = "computer"
        const val WAKE_WORD_CUSTOM = "custom"
        
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"



        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
