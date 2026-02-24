package com.openclaw.assistant.speech

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "TTSManager"

/**
 * Text-to-Speech (TTS) Manager with provider selection.
 */
class TTSManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingSpeak: (() -> Unit)? = null
    private val settings = SettingsRepository.getInstance(context)
    private val httpClient = OkHttpClient()
    private var externalPlayer: MediaPlayer? = null

    companion object {
        @Volatile private var openAiCooldownUntilMs: Long = 0L
        @Volatile private var openAiBackoffAttempt: Int = 0
        private const val OPENAI_BACKOFF_BASE_MS = 60_000L // 1 min
        private const val OPENAI_BACKOFF_MAX_MS = 30 * 60_000L // 30 min
    }

    init {
        initializeWithBruteForce()
    }

    private fun initializeWithBruteForce() {
        Log.e(TAG, "Force-starting TTS sequence...")

        val preferredEngine = settings.ttsEngine

        if (preferredEngine.isNotEmpty()) {
            Log.e(TAG, "Attempting to initialize with preferred engine: $preferredEngine")
            tts = TextToSpeech(context.applicationContext, { status ->
                if (status == TextToSpeech.SUCCESS) {
                    Log.e(TAG, "Initialized with preferred engine: $preferredEngine")
                    onInitSuccess()
                } else {
                    Log.e(TAG, "Preferred engine failed, falling back to Google/Default")
                    tryGoogleOrFallback()
                }
            }, preferredEngine)
        } else {
            tryGoogleOrFallback()
        }
    }

    private fun tryGoogleOrFallback() {
        tts = TextToSpeech(context.applicationContext, { status ->
            if (status == TextToSpeech.SUCCESS) {
                Log.e(TAG, "Initialized with Google engine")
                onInitSuccess()
            } else {
                Log.e(TAG, "Google TTS failed, falling back to system default")
                tts = TextToSpeech(context.applicationContext) { status2 ->
                    if (status2 == TextToSpeech.SUCCESS) {
                        Log.e(TAG, "Initialized with System Default engine")
                        onInitSuccess()
                    } else {
                        Log.e(TAG, "FATAL: All TTS initialization attempts failed")
                        showError("Android TTS initialization failed")
                    }
                }
            }
        }, TTSUtils.GOOGLE_TTS_PACKAGE)
    }

    private fun onInitSuccess() {
        isInitialized = true
        TTSUtils.setupVoice(tts, settings.ttsSpeed, settings.speechLanguage.ifEmpty { null })
        pendingSpeak?.invoke()
        pendingSpeak = null
    }

    fun reinitialize() {
        isInitialized = false
        tts?.shutdown()
        initializeWithBruteForce()
    }

    suspend fun speak(text: String): Boolean {
        return when (settings.ttsProvider) {
            SettingsRepository.TTS_PROVIDER_OPENAI -> speakWithOpenAi(text)
            SettingsRepository.TTS_PROVIDER_ELEVENLABS -> speakWithElevenLabs(text)
            else -> speakWithAndroidNative(text)
        }
    }

    private suspend fun speakWithAndroidNative(text: String): Boolean {
        val maxLen = TTSUtils.getMaxInputLength(tts)
        val chunks = TTSUtils.splitTextForTTS(text, maxLen)
        Log.d(TAG, "TTS splitting text (${text.length} chars) into ${chunks.size} chunks (maxLen=$maxLen)")

        for ((index, chunk) in chunks.withIndex()) {
            val success = speakSingleChunk(chunk, index == 0)
            if (!success) {
                Log.e(TAG, "TTS chunk $index failed, aborting remaining chunks")
                return false
            }
        }
        return true
    }

    private suspend fun speakWithOpenAi(text: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isEmpty()) {
            showError("OpenAI TTS selected but API key is empty")
            return@withContext false
        }

        val now = System.currentTimeMillis()
        if (now < openAiCooldownUntilMs) {
            val remainingSec = ((openAiCooldownUntilMs - now) / 1000L).coerceAtLeast(1L)
            val msg = "OpenAI TTS is temporarily paused after quota/rate-limit errors. Try again in ${remainingSec}s."
            if (settings.openAiFallbackToAndroidOn429) {
                showError("$msg Using Android TTS fallback.")
                return@withContext speakWithAndroidNative(text)
            }
            showError(msg)
            return@withContext false
        }

        val model = settings.openAiModel.ifBlank { "gpt-4o-mini-tts" }
        val voice = settings.openAiVoice.ifBlank { "alloy" }
        val payload = JSONObject().apply {
            put("model", model)
            put("voice", voice)
            put("input", text)
            put("response_format", "mp3")
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val isQuotaOrRateLimit = response.code == 429 || body.contains("insufficient_quota", ignoreCase = true)
                    if (isQuotaOrRateLimit) {
                        val multiplier = 1L shl openAiBackoffAttempt.coerceAtMost(5)
                        val backoffMs = (OPENAI_BACKOFF_BASE_MS * multiplier).coerceAtMost(OPENAI_BACKOFF_MAX_MS)
                        openAiBackoffAttempt = (openAiBackoffAttempt + 1).coerceAtMost(10)
                        openAiCooldownUntilMs = System.currentTimeMillis() + backoffMs
                        val backoffSec = backoffMs / 1000L
                        val quotaMsg = "OpenAI TTS quota/rate-limit hit (429). Please check OpenAI billing/quota. Retrying is paused for ${backoffSec}s."
                        if (settings.openAiFallbackToAndroidOn429) {
                            showError("$quotaMsg Using Android TTS fallback.")
                            return@withContext speakWithAndroidNative(text)
                        }
                        showError(quotaMsg)
                        Log.e(TAG, "OpenAI TTS failed (429) body: ${body.take(300)}")
                        return@withContext false
                    }

                    showError("OpenAI TTS failed (${response.code}): ${body.take(180)}")
                    return@withContext false
                }

                openAiBackoffAttempt = 0
                openAiCooldownUntilMs = 0L

                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    showError("OpenAI TTS returned empty audio")
                    return@withContext false
                }
                return@withContext playAudioBytes(audioBytes, "openai")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI TTS request failed", e)
            showError("OpenAI TTS error: ${e.message}")
            false
        }
    }

    private suspend fun speakWithElevenLabs(text: String): Boolean = withContext(Dispatchers.IO) {
        val apiKey = settings.elevenLabsApiKey.trim()
        if (apiKey.isEmpty()) {
            showError("ElevenLabs TTS selected but API key is empty")
            return@withContext false
        }

        val voiceId = settings.getElevenLabsVoiceIdForAgent(settings.defaultAgentId).ifBlank {
            settings.elevenLabsDefaultVoiceId
        }
        if (voiceId.isBlank()) {
            showError("ElevenLabs TTS selected but voice ID is empty")
            return@withContext false
        }

        val modelId = settings.elevenLabsModelId.ifBlank { "eleven_turbo_v2_5" }
        val payload = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
        }

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    showError("ElevenLabs TTS failed (${response.code}): ${body.take(180)}")
                    return@withContext false
                }
                val audioBytes = response.body?.bytes()
                if (audioBytes == null || audioBytes.isEmpty()) {
                    showError("ElevenLabs TTS returned empty audio")
                    return@withContext false
                }
                return@withContext playAudioBytes(audioBytes, "elevenlabs")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs TTS request failed", e)
            showError("ElevenLabs TTS error: ${e.message}")
            false
        }
    }

    private suspend fun playAudioBytes(audioBytes: ByteArray, source: String): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                try {
                    stop()
                    val tempFile = File.createTempFile("tts_$source", ".mp3", context.cacheDir)
                    tempFile.writeBytes(audioBytes)

                    val player = MediaPlayer().apply {
                        setDataSource(tempFile.absolutePath)
                        setOnCompletionListener {
                            tempFile.delete()
                            release()
                            if (externalPlayer === this) externalPlayer = null
                            if (continuation.isActive) continuation.resume(true)
                        }
                        setOnErrorListener { _, what, extra ->
                            tempFile.delete()
                            release()
                            if (externalPlayer === this) externalPlayer = null
                            showError("$source playback error ($what/$extra)")
                            if (continuation.isActive) continuation.resume(false)
                            true
                        }
                        prepare()
                        start()
                    }
                    externalPlayer = player

                    continuation.invokeOnCancellation {
                        runCatching {
                            player.stop()
                            player.release()
                            tempFile.delete()
                            if (externalPlayer === player) externalPlayer = null
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio playback failed", e)
                    showError("TTS playback failed: ${e.message}")
                    if (continuation.isActive) continuation.resume(false)
                }
            }
        }

    private fun showError(message: String) {
        Log.e(TAG, message)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun speakSingleChunk(text: String, isFirst: Boolean): Boolean {
        val timeoutMs = (30_000L + (text.length * 15L)).coerceAtMost(120_000L)
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val utteranceId = UUID.randomUUID().toString()
                val started = java.util.concurrent.atomic.AtomicBoolean(false)

                val listener = object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        started.set(true)
                    }

                    override fun onDone(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        if (continuation.isActive) continuation.resume(false)
                    }

                    override fun onError(utteranceId: String?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                if (isInitialized) {
                    TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                    tts?.setOnUtteranceProgressListener(listener)
                    val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    val speakResult = tts?.speak(text, queueMode, null, utteranceId)
                    if (speakResult != TextToSpeech.SUCCESS) {
                        Log.e(TAG, "TTS speak failed immediately: $speakResult")
                        if (continuation.isActive) continuation.resume(false)
                    } else {
                        CoroutineScope(Dispatchers.Main).launch {
                            var waitedMs = 0L
                            while (!started.get() && continuation.isActive && waitedMs < 10_000L) {
                                delay(200)
                                waitedMs += 200
                            }
                            if (!started.get() || !continuation.isActive) return@launch
                            delay(1000)
                            while (continuation.isActive) {
                                val speaking = tts?.isSpeaking ?: false
                                if (!speaking) {
                                    Log.w(TAG, "TTS poll detected speech finished (callback missed)")
                                    if (continuation.isActive) continuation.resume(true)
                                    break
                                }
                                delay(500)
                            }
                        }
                    }

                    continuation.invokeOnCancellation { tts?.stop() }
                } else {
                    val existingPending = pendingSpeak
                    pendingSpeak = {
                        existingPending?.invoke()
                        TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                        tts?.setOnUtteranceProgressListener(listener)
                        val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                        tts?.speak(text, queueMode, null, utteranceId)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (continuation.isActive && !isInitialized) {
                            continuation.resume(false)
                        }
                    }, 5000)
                }
            }
        }

        if (result == null) {
            Log.w(TAG, "TTS chunk timed out, forcing stop")
            tts?.stop()
            return false
        }
        return result
    }

    fun speakWithProgress(text: String): Flow<TTSState> = callbackFlow {
        val utteranceId = UUID.randomUUID().toString()
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                trySend(TTSState.Speaking)
            }

            override fun onDone(utteranceId: String?) {
                trySend(TTSState.Done)
                close()
            }

            override fun onError(utteranceId: String?) {
                trySend(TTSState.Error("Error"))
                close()
            }
        }

        if (isInitialized) {
            TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
            tts?.setOnUtteranceProgressListener(listener)
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            trySend(TTSState.Preparing)
        } else {
            trySend(TTSState.Preparing)
            pendingSpeak = {
                TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
                tts?.setOnUtteranceProgressListener(listener)
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            }
        }
        awaitClose { stop() }
    }

    fun speakQueued(text: String) {
        if (isInitialized) {
            TTSUtils.applyUserConfig(tts, settings.ttsSpeed)
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        }
    }

    fun stop() {
        tts?.stop()
        externalPlayer?.let {
            runCatching {
                if (it.isPlaying) it.stop()
            }
            runCatching { it.release() }
            externalPlayer = null
        }
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        isInitialized = false
    }

    fun isReady(): Boolean = isInitialized
}

sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}
