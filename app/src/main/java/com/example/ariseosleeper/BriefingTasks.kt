package com.example.ariseosleeper

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import org.json.JSONArray
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal data class BriefingTask(val title: String, val due: LocalDate?)
internal data class BriefingTaskList(val id: String, val title: String)
internal data class TaskBriefing(val items: List<BriefingTask> = emptyList(), val status: String = "", val listTitle: String = "")

internal object BriefingTasks {
    const val ALL_LISTS = "*"
    const val SELECTED_IDS = "task_list_ids"
    fun updatedCutoff(now: Instant, days: Int): Instant? =
        if (days <= 0) null else now.minusSeconds(days.coerceAtMost(365) * 86_400L)

    fun updatedSince(updated: String, cutoff: Instant?): Boolean = cutoff == null ||
        runCatching { !Instant.parse(updated).isBefore(cutoff) }.getOrDefault(false)

    val request: AuthorizationRequest
        get() = AuthorizationRequest.builder().setRequestedScopes(
            listOf(Scope("https://www.googleapis.com/auth/tasks.readonly"))).build()

    fun selectedIds(prefs: SharedPreferences): Set<String> =
        prefs.getStringSet(SELECTED_IDS, null)
            ?: setOfNotNull(prefs.getString("task_list_id", null)?.takeIf { it.isNotEmpty() })

    fun selectLists(lists: List<BriefingTaskList>, ids: Set<String>): List<BriefingTaskList> = when {
        ALL_LISTS in ids -> lists
        ids.isEmpty() -> lists.filter { it.title.trim().equals("immediate tasks", ignoreCase = true) }
        else -> lists.filter { it.id in ids }
    }

    fun selectionTitle(selected: List<BriefingTaskList>, ids: Set<String>): String = when {
        ALL_LISTS in ids -> "All task lists"
        selected.size > 3 -> "${selected.size} task lists"
        else -> selected.joinToString(", ") { it.title }
    }

    fun cachedLists(c: Context): List<BriefingTaskList> {
        val array = JSONArray(MorningBriefing.prefs(c).getString("task_lists", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it).let { o -> BriefingTaskList(o.getString("id"), o.getString("title")) } }
    }

    fun fetch(c: Context): TaskBriefing {
        if (!MorningBriefing.prefs(c).getBoolean("tasks_connected", false))
            return TaskBriefing(status = "Google Tasks is not connected.")
        return try {
            val auth = Tasks.await(Identity.getAuthorizationClient(c).authorize(request), 10, TimeUnit.SECONDS)
            if (auth.hasResolution() || auth.accessToken.isNullOrBlank())
                TaskBriefing(status = "Google Tasks needs reconnection in Morning briefing settings.")
            else download(c, auth.accessToken!!).also {
                MorningBriefing.prefs(c).edit().putString("tasks_status",
                    it.status.ifEmpty { "Connected. Checked " + Instant.now().toString() }).apply()
            }
        } catch (_: Exception) {
            TaskBriefing(status = "Google Tasks could not be checked. Try Connect Google Tasks in settings.")
        }
    }

    fun download(c: Context, token: String, today: LocalDate = LocalDate.now()): TaskBriefing {
        val prefs = MorningBriefing.prefs(c)
        val dueOnly = prefs.getBoolean("tasks_due_only", false)
        val cutoff = updatedCutoff(Instant.now(), prefs.getInt("tasks_updated_days", 0))
        val result = mutableListOf<BriefingTask>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        var requests = 0
        fun get(path: String): JSONObject {
            check(++requests <= 100 && System.nanoTime() < deadline) { "Tasks request limit reached. Try again." }
            val con = URL("https://tasks.googleapis.com/tasks/v1/$path").openConnection() as HttpsURLConnection
            try {
                con.connectTimeout = 4000
                con.readTimeout = 4000
                con.instanceFollowRedirects = false
                con.setRequestProperty("Authorization", "Bearer $token")
                check(con.responseCode == 200) { "Google Tasks returned HTTP ${con.responseCode}. Check Tasks API and account access." }
                return con.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } finally { con.disconnect() }
        }
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        var page = ""
        val lists = mutableListOf<BriefingTaskList>()
        do {
            val response = get("users/@me/lists?maxResults=100&pageToken=" + enc(page))
            val entries = response.optJSONArray("items")
            for (i in 0 until (entries?.length() ?: 0)) entries!!.getJSONObject(i).let {
                lists.add(BriefingTaskList(it.getString("id"), it.optString("title", "Untitled list")))
            }
            page = response.optString("nextPageToken")
        } while (page.isNotEmpty())
        prefs.edit().putString("task_lists", JSONArray().apply {
            lists.forEach { put(JSONObject().put("id", it.id).put("title", it.title)) }
        }.toString()).apply()
        val ids = selectedIds(prefs)
        val selected = selectLists(lists, ids)
        if (selected.isEmpty()) return TaskBriefing(status = "No matching task lists were found. Choose task lists in Morning briefing settings.")
        if (ids.isEmpty() && selected.size > 1)
            return TaskBriefing(status = "More than one list is named immediate tasks. Choose a task list in settings.")
        val listTitle = if (ALL_LISTS in ids) "all task lists" else selectionTitle(selected, ids)
        // Pin the automatic default across renames. An explicit multi-selection always takes precedence.
        if (ids.isEmpty()) prefs.edit().putString("task_list_id", selected.single().id).apply()
        for (list in selected) {
            page = ""
            do {
                val dueFilter = if (dueOnly) "&dueMin=${today}T00:00:00Z&dueMax=${today.plusDays(2)}T00:00:00Z" else ""
                val updatedFilter = cutoff?.let { "&updatedMin=" + enc(it.toString()) }.orEmpty()
                val response = get("lists/${enc(list.id)}/tasks?maxResults=100&showCompleted=false&showDeleted=false&showAssigned=true" +
                    dueFilter + updatedFilter + "&pageToken=" + enc(page))
                val items = response.optJSONArray("items")
                for (j in 0 until (items?.length() ?: 0)) {
                    val item = items!!.getJSONObject(j)
                    val due = runCatching { LocalDate.parse(item.optString("due").take(10)) }.getOrNull()
                    if (item.optString("status") != "completed" && !item.optBoolean("deleted") &&
                        updatedSince(item.optString("updated"), cutoff) &&
                        (!dueOnly || due == today || due == today.plusDays(1))) {
                        result.add(BriefingTask(item.optString("title", "Untitled task").take(180), due))
                    }
                }
                page = response.optString("nextPageToken")
            } while (page.isNotEmpty())
        }
        return TaskBriefing(result.sortedWith(compareBy<BriefingTask> { it.due == null }.thenBy { it.due }), listTitle = listTitle)
    }

    fun summary(tasks: TaskBriefing, today: LocalDate): String {
        if (tasks.status.isNotEmpty()) return tasks.status
        val intro = "From ${tasks.listTitle}, ${tasks.items.size} incomplete ${if (tasks.items.size == 1) "task" else "tasks"}. "
        if (tasks.items.isEmpty()) return intro.trim()
        return intro + tasks.items.take(10).joinToString(". ") {
            val date = when {
                it.due == null -> ""
                it.due < today -> "overdue"
                it.due == today -> "due today"
                it.due == today.plusDays(1) -> "due tomorrow"
                else -> "due ${it.due}"
            }
            if (date.isEmpty()) it.title else "${it.title}, $date"
        } + if (tasks.items.size > 10) ". And ${tasks.items.size - 10} more." else "."
    }
}
