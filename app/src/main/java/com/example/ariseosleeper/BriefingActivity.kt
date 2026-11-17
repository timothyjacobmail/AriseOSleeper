package com.example.ariseosleeper

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import java.util.concurrent.Executors
import java.security.MessageDigest

class BriefingActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var status: TextView? = null
    private var summary: TextView? = null
    private var connecting = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> runOnUiThread { updateStatus() } }
    private val calendar = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }
    private val tasksResolution = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        connecting = false
        if (result.resultCode != RESULT_OK || result.data == null) {
            setTaskStatus("Google Tasks connection cancelled.")
        } else try {
            accept(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data))
        } catch (e: ApiException) { authError(e) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        MorningBriefing.prefs(this).registerOnSharedPreferenceChangeListener(listener)
    }
    override fun onResume() { super.onResume(); render() }
    override fun onDestroy() {
        MorningBriefing.prefs(this).unregisterOnSharedPreferenceChangeListener(listener)
        worker.shutdownNow()
        super.onDestroy()
    }
    private fun label(value: String, size: Float = 16f) = UiStyle.label(this, value, size)
    private fun button(value: String, action: () -> Unit) = UiStyle.button(this, value, onClick = action)
    private fun render() {
        if (!MorningBriefing.unlocked(this)) {
            setContentView(label("Unlock your phone to configure or read your morning briefing."))
            return
        }
        val prefs = MorningBriefing.prefs(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = UiStyle.dp(this@BriefingActivity, 20)
            setPadding(pad, pad * 2, pad, pad * 2)
        }
        fun add(view: android.view.View) {
            column.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })
        }
        fun toggle(text: String, key: String) {
            add(CheckBox(this).apply {
                this.text = text
                isChecked = prefs.getBoolean(key, true)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean(key, checked).apply()
                    if (key == "enabled" && !checked) stopService(Intent(this@BriefingActivity, BriefingService::class.java))
                }
            })
        }
        add(label("Morning briefing", 26f))
        add(label("After the final wake-up check, read one briefing each day, including while the screen is locked. After restarting the phone, unlock it once so Android can make your personal data available."))
        toggle("Automatically read the morning briefing", "enabled")
        toggle("WhatsApp and WhatsApp Business", "WhatsApp")
        toggle("Gmail notification previews", "Gmail")
        toggle("Calendar events today and tomorrow", "calendar")
        toggle("Google Tasks due today and tomorrow", "tasks")
        add(label("Notification previews are stored locally. Previews older than 24 hours are cleared during collection or briefing. Each briefing covers new previews since the last completed briefing, up to the past 12 hours. It reads up to four recent previews per app. Android may hide sensitive previews."))
        val access = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        add(button(if (access) "Notification access enabled" else "Allow WhatsApp and Gmail notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        val calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        add(button(if (calendarGranted) "Calendar access enabled" else "Allow calendar access") {
            calendar.launch(Manifest.permission.READ_CALENDAR)
        })
        add(label("Calendar uses calendars synced to this phone. Google Tasks uses read-only account access and is checked when a briefing is prepared."))
        add(button("Connect Google Tasks") { connectTasks() })
        add(button("Google Tasks setup help") { showTasksHelp() })
        if (prefs.getBoolean("tasks_connected", false)) add(button("Disconnect Google Tasks") {
            prefs.edit().putBoolean("tasks_connected", false).putString("tasks_status", "Google Tasks disconnected.").apply()
        })
        status = label("")
        add(status!!)
        add(button("Read briefing now") { startBriefing("BUILD") })
        add(button("Replay last briefing") { startBriefing("REPLAY") })
        add(button("Stop reading") { startService(Intent(this, BriefingService::class.java).setAction("STOP")) })
        summary = label("")
        add(summary!!)
        add(button("Clear collected previews and saved briefing") {
            androidx.appcompat.app.AlertDialog.Builder(this).setMessage("Delete locally collected notification previews and the saved briefing?")
                .setPositiveButton("Clear") { _, _ ->
                    BriefingMessages(this).use { it.clear() }
                    prefs.edit().remove("last_summary").remove("summary_at").putLong("notifications_through", System.currentTimeMillis()).apply()
                }.setNegativeButton("Cancel", null).show()
        })
        add(button("Back to alarm") { finish() })
        setContentView(ScrollView(this).apply { addView(column) })
        updateStatus()
    }
    private fun updateStatus() {
        val p = MorningBriefing.prefs(this)
        status?.text = listOf(p.getString("tasks_status", "Google Tasks is not connected."),
            p.getString("playback_status", "Ready to prepare a briefing.")).joinToString("\n")
        summary?.text = p.getString("last_summary", "").orEmpty()
    }
    private fun startBriefing(action: String) {
        if (!MorningBriefing.unlocked(this)) return
        if (VerseAlarmStore.read(this).challenge != null) {
            Toast.makeText(this, "Complete the current wake-up routine first.", Toast.LENGTH_LONG).show()
            return
        }
        startForegroundService(Intent(this, BriefingService::class.java).setAction(action))
    }
    private fun setTaskStatus(value: String) {
        MorningBriefing.prefs(this).edit().putString("tasks_status", value).apply()
    }
    private fun authError(error: ApiException) {
        connecting = false
        setTaskStatus("Google Tasks authorization failed (code ${error.statusCode}). Check Google Tasks setup help.")
    }
    private fun connectTasks() {
        if (connecting) return
        connecting = true
        setTaskStatus("Connecting to Google Tasks…")
        Identity.getAuthorizationClient(this).authorize(BriefingTasks.request)
            .addOnSuccessListener(this) { result ->
                connecting = false
                if (result.hasResolution()) {
                    result.pendingIntent?.let { tasksResolution.launch(IntentSenderRequest.Builder(it).build()) }
                } else accept(result)
            }.addOnFailureListener(this) {
                connecting = false
                if (it is ApiException) authError(it) else setTaskStatus("Google Tasks connection failed. Check your connection and setup help.")
            }
    }
    private fun accept(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) { setTaskStatus("Google Tasks access was not granted."); return }
        setTaskStatus("Checking Google Tasks…")
        worker.execute {
            val status = try {
                val items = BriefingTasks.download(token)
                MorningBriefing.prefs(this).edit().putBoolean("tasks_connected", true).apply()
                "Google Tasks connected. ${items.items.size} incomplete tasks due today or tomorrow."
            } catch (e: Exception) {
                // Only our HTTP/status messages may be displayed, never auth tokens or responses.
                if (e is IllegalStateException) e.message.orEmpty() else "Google Tasks could not be read. Check your connection and setup help."
            }
            setTaskStatus(status)
        }
    }
    @Suppress("DEPRECATION")
    private fun showTasksHelp() {
        val sig = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        val sha = sig?.let { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString(":") { b -> "%02X".format(b) } }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Connect Google Tasks")
            .setMessage("In Google Cloud, enable Google Tasks API, configure Google Auth Platform and add your account as a test user if the project is in testing. Create an Android OAuth client with these values:\n\nPackage: $packageName\nSHA-1: $sha\n\nThen return here and tap Connect Google Tasks. Google will ask for permission to view your tasks. A Maps API key cannot authorize this account access. Testing-mode authorization may need renewing.")
            .setPositiveButton("OK", null).show()
    }
}
