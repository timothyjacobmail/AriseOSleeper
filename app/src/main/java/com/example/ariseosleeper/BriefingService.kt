package com.example.ariseosleeper

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors

class BriefingService : Service(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var active = false
    private var speaking = false
    private var generation = 0
    private var text: String? = null
    private var cutoff = 0L
    private val timeout = Runnable { finishBriefing("Briefing timed out. You can try again in settings.") }
    private var focus: AudioFocusRequest? = null
    private var wake: PowerManager.WakeLock? = null
    private val monitor = object : Runnable {
        override fun run() {
            if (VerseAlarmStore.read(this@BriefingService).challenge != null) {
                finishBriefing("Briefing stopped for the wake-up alarm.")
            } else handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Morning briefing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, BriefingService::class.java).setAction("STOP"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, BriefingActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Morning briefing").setContentText("Preparing or reading your briefing")
            .setContentIntent(open).setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP" || intent == null) {
            finishBriefing("Briefing stopped.")
            return START_NOT_STICKY
        }
        if (active) return START_NOT_STICKY
        if (!MorningBriefing.storageAvailable(this) || VerseAlarmStore.read(this).challenge != null) {
            finishBriefing("Unlock the phone after completing the wake-up routine to hear the briefing.")
            return START_NOT_STICKY
        }
        active = true
        val request = ++generation
        cutoff = System.currentTimeMillis()
        MorningBriefing.prefs(this).edit().putString("playback_status", "Preparing your briefing…").apply()
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "AriseOSleeper:morningBriefing").apply { acquire(660_000) }
        handler.post(monitor)
        handler.postDelayed(timeout, 45_000)
        tts = TextToSpeech(this, this)
        if (intent.action == "REPLAY") {
            cutoff = 0
            text = MorningBriefing.prefs(this).getString("last_summary", null)
            if (text.isNullOrBlank()) finishBriefing("No saved briefing to replay.") else speakWhenReady()
        } else worker.execute {
            val summary = runCatching { BriefingSummary.build(this, cutoff) }
                .getOrDefault("Your morning briefing could not be prepared. Please try again in settings.")
            handler.post {
                if (active && request == generation) {
                    text = summary
                    MorningBriefing.prefs(this).edit().putString("last_summary", summary)
                        .putLong("summary_at", cutoff).apply()
                    speakWhenReady()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        if (!active) return
        if (status != TextToSpeech.SUCCESS) { finishBriefing("Text-to-speech could not start. Check your phone's speech engine."); return }
        val language = tts?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
        if (language < TextToSpeech.LANG_AVAILABLE && tts?.setLanguage(Locale.US)!! < TextToSpeech.LANG_AVAILABLE) {
            finishBriefing("Install a speech voice in Android text-to-speech settings."); return
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { if (id != "briefing") return; handler.post {
                if (!active) return@post
                if (cutoff > 0) MorningBriefing.prefs(this@BriefingService).edit()
                    .putLong("notifications_through", cutoff).apply()
                finishBriefing("Briefing complete.")
            } }
            @Deprecated("Android legacy callback") override fun onError(id: String?) {
                handler.post { finishBriefing("Speech playback failed. Try Replay briefing.") }
            }
        })
        ready = true
        speakWhenReady()
    }

    private fun speakWhenReady() {
        val summary = text ?: return
        if (!ready || !active || speaking) return
        speaking = true
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, (summary.length * 120L + 30_000).coerceIn(90_000L, 600_000L))
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener { change ->
                if (change < 0) finishBriefing("Briefing stopped because audio focus changed.")
            }.build()
        if (getSystemService(AudioManager::class.java).requestAudioFocus(focus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            finishBriefing("Audio is busy. Tap Replay briefing when ready."); return
        }
        tts?.setAudioAttributes(attrs)
        MorningBriefing.prefs(this).edit().putString("playback_status", "Reading your briefing…").apply()
        // Keep each queued segment below the Android engine input limit.
        val chunks = summary.chunked(3000)
        chunks.forEachIndexed { index, chunk ->
            val last = index == chunks.lastIndex
            // Progress listener handles only the final chunk.
            if (tts?.speak(chunk, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null, if (last) "briefing" else "part-$index") == TextToSpeech.ERROR) {
                finishBriefing("Speech playback failed."); return
            }
        }
    }

    private fun finishBriefing(status: String) {
        active = false
        generation++
        MorningBriefing.prefs(this).edit().putString("playback_status", status).apply()
        tts?.stop()
        stopSelf()
    }
    override fun onDestroy() {
        active = false
        generation++
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        tts?.stop()
        tts?.shutdown()
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        if (wake?.isHeld == true) wake?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?) = null
    companion object { private const val CHANNEL = "morning_briefing"; private const val ID = 4710 }
}
