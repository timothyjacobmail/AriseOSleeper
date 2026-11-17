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
    private var taskListButton: Button? = null
    private val notificationModeButtons = mutableMapOf<String, Button>()
    private var connecting = false
    private var pageScroll: ScrollView? = null
    private var taskStatus: TextView? = null
    private var readButton: Button? = null
    private var replayButton: Button? = null
    private var stopButton: Button? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var progress: com.google.android.material.progressindicator.LinearProgressIndicator? = null
    private val sourceChecks = mutableMapOf<String, com.google.android.material.checkbox.MaterialCheckBox>()
    private val sourceControls = mutableMapOf<String, List<android.view.View>>()
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
    private fun savePreference(key: String, value: Boolean) {
        stopService(Intent(this, BriefingService::class.java))
        MorningBriefing.prefs(this).edit().putBoolean(key, value)
            .putString("playback_status", "Settings saved. Read a new briefing to hear the changes.")
            .remove("last_summary").remove("summary_at").apply()
    }

    private fun render() {
        if (!MorningBriefing.unlocked(this)) {
            UiComponents.page(this, "Morning briefing",
                listOf(label("Unlock your phone to configure or read your morning briefing.")), { finish() })
            return
        }
        val scrollY = pageScroll?.scrollY ?: 0
        val prefs = MorningBriefing.prefs(this)
        sourceChecks.clear()
        sourceControls.clear()
        notificationModeButtons.clear()
        fun source(title: String, key: String) = UiComponents.checkbox(this, title,
            prefs.getBoolean(key, true)) { savePreference(key, it) }.also { sourceChecks[key] = it }
        fun setting(title: String, value: String, action: () -> Unit) = button("$title\n$value", action).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            icon = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, R.drawable.ic_expand_more)
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
        }

        status = label("").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        progress = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            isIndeterminate = true
            visibility = android.view.View.GONE
        }
        readButton = UiStyle.button(this, "Read briefing now", primary = true) { startBriefing("BUILD") }.apply {
            tag = "briefing:read"
        }
        replayButton = button("Replay") { startBriefing("REPLAY") }.apply { contentDescription = "Replay last briefing" }
        stopButton = button("Stop") { startService(Intent(this@BriefingActivity, BriefingService::class.java).setAction("STOP")) }
            .apply { contentDescription = "Stop reading briefing" }
        val controls = UiComponents.actions(this, listOf(replayButton!!, stopButton!!))
        val playback = UiComponents.section(this, "Listen", listOf(status!!, progress!!, readButton!!, controls))
        val automatic = UiComponents.settingSwitch(this, "Morning briefing enabled",
            prefs.getBoolean("enabled", true)) { savePreference("enabled", it) }

        val notificationViews = mutableListOf<android.view.View>()
        for (app in listOf("WhatsApp", "Gmail")) {
            notificationViews.add(source(if (app == "WhatsApp") "WhatsApp and WhatsApp Business" else "Gmail", app))
            val choice = setting("$app reading", NotificationReadingMode.read(this, app).label) { chooseNotificationMode(app) }
            notificationModeButtons[app] = choice
            sourceControls[app] = listOf(choice)
            notificationViews.add(choice)
        }
        val filterButton = setting("Notification filters", "") { BriefingFilterEditor.show(this) }.apply {
            tag = "briefing:filters"
        }
        notificationViews.add(filterButton)
        sourceControls["notifications"] = listOf(filterButton)
        val notificationAccess = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        notificationViews.add(label(if (notificationAccess) "Notification access granted" else "Notification access needed"))
        if (!notificationAccess) notificationViews.add(button("Allow notification access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        notificationViews.add(button("How notification reading works") {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Notification reading")
                .setMessage("Count and sender modes use notifications currently in your notification tray. Apps can bundle several messages into one notification.\n\nPreview mode reads collected notifications since the last completed briefing, up to the previous 12 hours. It reads up to four previews per app. Filters apply to both approaches.\n\nAndroid may hide sensitive previews. Collected previews older than 24 hours are removed during collection or briefing.")
                .setPositiveButton("Got it", null).show()
        })

        val calendarViews = mutableListOf<android.view.View>(
            source("Include calendar events", "calendar"), label("Today and tomorrow, from calendars synced to this phone."))
        val calendarGranted = checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        calendarViews.add(label(if (calendarGranted) "Calendar access granted" else "Calendar access needed"))
        if (!calendarGranted) calendarViews.add(button("Allow calendar access") { calendar.launch(Manifest.permission.READ_CALENDAR) })

        taskListButton = setting("Task list", prefs.getString("task_list_title", "immediate tasks").orEmpty()) { chooseTaskList() }
        val dueOnly = UiComponents.settingSwitch(this, "Only tasks due today or tomorrow",
            prefs.getBoolean("tasks_due_only", false)) { savePreference("tasks_due_only", it) }
        taskStatus = label("").apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        connectButton = button("Connect Google Tasks") { connectTasks() }.apply { tag = "briefing:connect-tasks" }
        disconnectButton = button("Disconnect Google Tasks") {
            UiComponents.confirm(this, "Disconnect Google Tasks?", "Tasks will be unavailable in the briefing until you reconnect.",
                "Disconnect") {
                stopService(Intent(this, BriefingService::class.java))
                prefs.edit().putBoolean("tasks_connected", false).putString("tasks_status", "Google Tasks disconnected.")
                    .remove("last_summary").remove("summary_at").apply()
            }
        }
        sourceControls["tasks"] = listOf(taskListButton!!, dueOnly)
        val taskViews = listOf(source("Include Google Tasks", "tasks"), taskListButton!!, dueOnly,
            label("With the date filter off, undated and overdue tasks are included."), taskStatus!!,
            connectButton!!, disconnectButton!!)
        summary = label("").apply { setTextIsSelectable(true) }
        val saved = UiComponents.section(this, "Last briefing", listOf(summary!!))
        val data = PassageDropdown(this, "briefing-help", "Help and stored data", "Google setup and local storage",
            false, listOf(
                button("Google Tasks setup help") { showTasksHelp() },
                label("After a phone restart, unlock once before personal briefing data is available."),
                button("Clear collected previews and saved briefing") {
                    UiComponents.confirm(this, "Clear briefing data?", "Delete the locally collected notification previews and saved briefing?",
                        "Clear") {
                        stopService(Intent(this, BriefingService::class.java))
                        BriefingMessages(this).use { it.clear() }
                        prefs.edit().remove("last_summary").remove("summary_at")
                            .putLong("notifications_through", System.currentTimeMillis()).apply()
                    }
                }
            )) { }
        pageScroll = UiComponents.page(this, "Morning briefing", listOf(automatic,
            label("Reads once a day after the final wake-up check. Changes on this page save automatically."),
            playback, UiComponents.section(this, "Notifications", notificationViews),
            UiComponents.section(this, "Calendar", calendarViews),
            UiComponents.section(this, "Google Tasks", taskViews), saved, data), { finish() })
        updateStatus()
        pageScroll?.post { pageScroll?.scrollTo(0, scrollY) }
    }

    private fun updateStatus() {
        val p = MorningBriefing.prefs(this)
        val playback = p.getString("playback_status", "Ready to prepare a briefing.").orEmpty()
        status?.text = playback
        val rawTaskStatus = p.getString("tasks_status", "Google Tasks is not connected.").orEmpty()
        taskStatus?.text = if (rawTaskStatus.startsWith("Connected. Checked ")) runCatching {
            val time = java.time.Instant.parse(rawTaskStatus.removePrefix("Connected. Checked "))
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT))
            "Google Tasks checked at $time."
        }.getOrDefault(rawTaskStatus) else rawTaskStatus
        val last = p.getString("last_summary", "").orEmpty()
        summary?.text = last.ifBlank { "Your briefing text will appear here after you read it." }
        val enabled = p.getBoolean("enabled", true)
        val playing = playback.startsWith("Reading your") || playback.startsWith("Preparing your")
        readButton?.isEnabled = enabled && !playing
        replayButton?.isEnabled = enabled && !playing && last.isNotBlank()
        stopButton?.isEnabled = playing
        progress?.visibility = if (playback.startsWith("Preparing your") || connecting)
            android.view.View.VISIBLE else android.view.View.GONE
        sourceChecks.forEach { (_, view) -> view.isEnabled = enabled }
        sourceControls.forEach { (source, controls) ->
            val available = if (source == "notifications") p.getBoolean("WhatsApp", true) || p.getBoolean("Gmail", true)
                else p.getBoolean(source, true)
            controls.forEach { it.isEnabled = enabled && available }
        }
        taskListButton?.text = "Task list\n" + p.getString("task_list_title", "immediate tasks")
        connectButton?.text = if (connecting) "Connecting…" else
            if (p.getBoolean("tasks_connected", false)) "Check Google Tasks connection" else "Connect Google Tasks"
        connectButton?.isEnabled = !connecting
        disconnectButton?.visibility = if (p.getBoolean("tasks_connected", false)) android.view.View.VISIBLE else android.view.View.GONE
        notificationModeButtons.forEach { (source, button) ->
            button.text = "$source reading\n${NotificationReadingMode.read(this, source).label}"
        }
        val rules = BriefingFilters.read(this)
        sourceControls["notifications"]?.firstOrNull()?.let {
            (it as Button).text = "Notification filters\n" + if (rules.isEmpty()) "No rules" else "${rules.count { rule -> rule.enabled }} active rules"
        }
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
                val items = BriefingTasks.download(this, token)
                MorningBriefing.prefs(this).edit().putBoolean("tasks_connected", true).apply()
                items.status.ifEmpty { "Google Tasks connected. ${items.items.size} incomplete tasks in ${items.listTitle}." }
            } catch (e: Exception) {
                // Only our HTTP/status messages may be displayed, never auth tokens or responses.
                if (e is IllegalStateException) e.message.orEmpty() else "Google Tasks could not be read. Check your connection and setup help."
            }
            setTaskStatus(status)
        }
    }
    private fun chooseNotificationMode(source: String) {
        val modes = NotificationReadingMode.values().filter { source == "Gmail" || it != NotificationReadingMode.SENDERS_ONLY }
        val current = NotificationReadingMode.read(this, source)
        val labels = modes.map { mode ->
            mode.label + "\n" + when (mode) {
                NotificationReadingMode.PREVIEWS -> "Sender and message text from the briefing window"
                NotificationReadingMode.COUNT_ONLY -> "Number of notifications currently waiting"
                NotificationReadingMode.SENDERS_ONLY -> "Unique sender names, without message text"
            }
        }
        UiComponents.choiceDialog(this, "$source reading style", labels, modes.indexOf(current)) { index ->
            stopService(Intent(this, BriefingService::class.java))
            MorningBriefing.prefs(this).edit().putString("notification_mode_$source", modes[index].name)
                .putString("playback_status", "Reading style saved. Read a new briefing to apply it.")
                .remove("last_summary").remove("summary_at").apply()
        }
    }


    private fun chooseTaskList() {
        val lists = BriefingTasks.cachedLists(this)
        if (lists.isEmpty()) {
            Toast.makeText(this, "Fetching your task lists. Open Task list again when the connection check finishes.", Toast.LENGTH_LONG).show()
            connectTasks()
            return
        }
        val choices = listOf(BriefingTaskList(BriefingTasks.ALL_LISTS, "All task lists")) + lists
        val prefs = MorningBriefing.prefs(this)
        var selected = choices.indexOfFirst { it.id == prefs.getString("task_list_id", "") }
        var visible = choices.indices.toList()
        val body = UiComponents.column(this).apply {
            val pad = UiStyle.dp(context, 20)
            setPadding(pad, 0, pad, 0)
        }
        val searchBox = UiComponents.field(this, "Search lists")
        UiComponents.add(body, searchBox)
        val empty = label("No task lists match your search.")
        val listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            divider = null
        }
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_single_choice, mutableListOf()) {
            override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                return (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 16f
                    setSingleLine(false)
                    ellipsize = null
                    minHeight = UiStyle.dp(context, 56)
                    layoutParams = AbsListView.LayoutParams(-1, -2)
                }
            }
        }
        listView.adapter = adapter
        body.addView(listView, LinearLayout.LayoutParams(-1, UiStyle.dp(this,
            (resources.configuration.screenHeightDp / 3).coerceIn(72, 320))))
        body.addView(empty)
        listView.emptyView = empty
        fun refreshChoices(query: String) {
            visible = choices.indices.filter { choices[it].title.contains(query, ignoreCase = true) }
            adapter.clear()
            adapter.addAll(visible.map { choices[it].title })
            listView.clearChoices()
            val checked = visible.indexOf(selected)
            if (checked >= 0) listView.setItemChecked(checked, true)
            // Size to measured, wrapping labels, with a scrollable cap for large collections.
            val rowWidth = listView.width.takeIf { it > 0 } ?: UiStyle.dp(this, resources.configuration.screenWidthDp - 104)
            val rowsHeight = (0 until adapter.count).sumOf { index ->
                adapter.getView(index, null, listView).apply {
                    measure(android.view.View.MeasureSpec.makeMeasureSpec(rowWidth, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
                }.measuredHeight
            }
            listView.layoutParams.height = minOf(rowsHeight, UiStyle.dp(this,
                (resources.configuration.screenHeightDp / 3).coerceIn(72, 320)))
            listView.requestLayout()
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        UiComponents.add(body, button("Refresh lists") { dialog.dismiss(); connectTasks() })
        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Task list")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        listView.setOnItemClickListener { _, _, position, _ ->
            selected = visible[position]
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
        }
        searchBox.editText!!.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshChoices(s.toString())
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        refreshChoices("")
        dialog.setOnShowListener {
            val apply = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
            apply.isEnabled = selected >= 0
            apply.setOnClickListener {
                val choice = choices.getOrNull(selected) ?: return@setOnClickListener
                stopService(Intent(this, BriefingService::class.java))
                prefs.edit().putString("task_list_id", choice.id).putString("task_list_title", choice.title)
                    .putString("playback_status", "Task list saved. Read a new briefing to hear it.")
                    .remove("last_summary").remove("summary_at").apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    @Suppress("DEPRECATION")
    private fun showTasksHelp() {
        val sig = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        val sha = sig?.let { MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString(":") { b -> "%02X".format(b) } }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Connect Google Tasks")
            .setMessage("In Google Cloud, enable Google Tasks API, configure Google Auth Platform and add your account as a test user if the project is in testing. Create an Android OAuth client with these values:\n\nPackage: $packageName\nSHA-1: $sha\n\nThen return here and tap Connect Google Tasks. Google will ask for permission to view your tasks. A Maps API key cannot authorize this account access. Testing-mode authorization may need renewing.")
            .setPositiveButton("OK", null).show()
    }
}
