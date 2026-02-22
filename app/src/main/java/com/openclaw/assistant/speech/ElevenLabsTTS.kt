package com.openclaw.assistant.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "ElevenLabsTTS"

class ElevenLabsTTS(private val context: Context) {
    private val client = OkHttpClient()

    suspend fun speak(
        apiKey: String,
        voiceId: String,
        text: String,
        modelId: String = "eleven_turbo_v2_5"
    ): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || voiceId.isBlank() || text.isBlank()) return@withContext false

        val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream"
        val bodyJson = JSONObject().apply {
            put("text", text)
            put("model_id", modelId)
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
            })
        }

        val req = Request.Builder()
            .url(url)
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("xi-api-key", apiKey)
            .addHeader("Accept", "audio/mpeg")
            .build()

        return@withContext try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "ElevenLabs HTTP ${resp.code}")
                    return@use false
                }
                val bytes = resp.body?.bytes() ?: return@use false
                if (bytes.isEmpty()) return@use false

                val tmp = File(context.cacheDir, "elevenlabs-${UUID.randomUUID()}.mp3")
                tmp.writeBytes(bytes)
                val played = playFile(tmp)
                tmp.delete()
                played
            }
        } catch (e: Exception) {
            Log.e(TAG, "ElevenLabs speak failed", e)
            false
        }
    }

    private suspend fun playFile(file: File): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (cont.isActive) cont.resume(true)
            }
            mp.setOnErrorListener { player, _, _ ->
                player.release()
                if (cont.isActive) cont.resume(false)
                true
            }
            mp.prepare()
            mp.start()

            cont.invokeOnCancellation {
                try {
                    mp.stop()
                } catch (_: Exception) {
                }
                mp.release()
            }
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }
}
