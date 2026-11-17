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
    private var taskRecencyButton: Button? = null
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
            val choice = setting("$app reading", NotificationReadingOptions.read(this, app).label) { chooseNotificationMode(app) }
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
                .setMessage("Choose Count, Sender names and Message previews independently for each app.\n\nWaiting notifications reads cards currently in your tray. Apps can bundle several messages into one card. The collected briefing window reads saved previews since the last completed briefing, up to the previous 12 hours.\n\nReads up to four previews or ten unique names per app. Filters apply to either source. Android may hide sensitive content. Collected previews older than 24 hours are removed during collection or briefing.")
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
        taskRecencyButton = setting("Task recency", "") { chooseTaskRecency() }
        sourceControls["tasks"] = listOf(taskListButton!!, dueOnly, taskRecencyButton!!)
        val taskViews = listOf(source("Include Google Tasks", "tasks"), taskListButton!!, dueOnly,
            label("With the date filter off, undated and overdue tasks are included."), taskRecencyButton!!, taskStatus!!,
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
        val recentDays = p.getInt("tasks_updated_days", 0)
        taskRecencyButton?.text = "Task recency\n" + if (recentDays <= 0) "All update times"
            else "Updated in the last $recentDays ${if (recentDays == 1) "day" else "days"}"
        connectButton?.text = if (connecting) "Connecting…" else
            if (p.getBoolean("tasks_connected", false)) "Check Google Tasks connection" else "Connect Google Tasks"
        connectButton?.isEnabled = !connecting
        disconnectButton?.visibility = if (p.getBoolean("tasks_connected", false)) android.view.View.VISIBLE else android.view.View.GONE
        notificationModeButtons.forEach { (source, button) ->
            button.text = "$source reading\n${NotificationReadingOptions.read(this, source).label}"
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
        val current = NotificationReadingOptions.read(this, source)
        var count = current.count
        var senders = current.senders
        var previews = current.previews
        var collected = current.collected
        val body = UiComponents.column(this).apply {
            val pad = UiStyle.dp(context, 20)
            setPadding(pad, 0, pad, pad)
        }
        val feedback = label("Select one or more items to read.").apply {
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        fun validate() {
            val valid = count || senders || previews
            feedback.text = if (valid) "Select one or more items to read." else
                "Select at least one item, or turn off $source on the briefing page."
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = valid
        }
        UiComponents.add(body, feedback)
        UiComponents.add(body, UiComponents.checkbox(this, "Notification count", count) { count = it; validate() })
        UiComponents.add(body, UiComponents.checkbox(this, "Sender names", senders) { senders = it; validate() })
        UiComponents.add(body, UiComponents.checkbox(this, "Message previews", previews) { previews = it; validate() })
        UiComponents.add(body, label("Notifications to read", 20f))
        UiComponents.add(body, UiComponents.radioChoices(this, listOf(
            "Waiting notifications", "Collected briefing window (up to 12 hours)"),
            if (collected) 1 else 0) { collected = it == 1 })
        UiComponents.add(body, label("Waiting counts notification cards currently in your tray. Collected counts saved previews since the last completed briefing. Apps may bundle several messages into one card. Reads up to four previews or ten unique sender names."))
        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("$source reading")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            validate()
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val options = NotificationReadingOptions(count, senders, previews, collected)
                if (!options.anySelected) return@setOnClickListener
                stopService(Intent(this, BriefingService::class.java))
                MorningBriefing.prefs(this).edit().putStringSet("notification_parts_$source", options.parts)
                    .putBoolean("notification_collected_$source", options.collected)
                    .putString("playback_status", "Reading options saved. Read a new briefing to apply them.")
                    .remove("last_summary").remove("summary_at").apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }


    private fun chooseTaskRecency() {
        val prefs = MorningBriefing.prefs(this)
        val saved = prefs.getInt("tasks_updated_days", 0)
        var recentOnly = saved > 0
        val body = UiComponents.column(this).apply {
            val pad = UiStyle.dp(context, 20)
            setPadding(pad, 0, pad, pad)
        }
        val daysBox = UiComponents.field(this, "Number of days", (saved.takeIf { it > 0 } ?: 2).toString(),
            "1–365 days. Each day is a rolling 24-hour period.")
        val days = daysBox.editText!!.apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        daysBox.isEnabled = recentOnly
        days.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { daysBox.error = null }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        UiComponents.add(body, label("Google provides the last update time, not the creation date. Editing an older task makes it recent again. This combines with your selected list and due-date filter."))
        UiComponents.add(body, UiComponents.radioChoices(this,
            listOf("All update times", "Only recently updated tasks"), if (recentOnly) 1 else 0) {
            recentOnly = it == 1
            daysBox.isEnabled = recentOnly
        })
        UiComponents.add(body, daysBox)
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Task recency")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Apply", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = if (recentOnly) days.text.toString().toIntOrNull() else 0
                if (value == null || (recentOnly && value !in 1..365)) {
                    daysBox.error = "Enter a number from 1 to 365"
                    days.requestFocus()
                    return@setOnClickListener
                }
                stopService(Intent(this, BriefingService::class.java))
                prefs.edit().putInt("tasks_updated_days", value)
                    .putString("playback_status", "Task recency saved. Read a new briefing to hear it.")
                    .remove("last_summary").remove("summary_at").apply()
                dialog.dismiss()
            }
        }
        dialog.show()
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
