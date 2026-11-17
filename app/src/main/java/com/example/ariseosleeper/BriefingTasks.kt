package com.example.ariseosleeper

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

internal data class BriefingTask(val title: String, val due: LocalDate)
internal data class TaskBriefing(val items: List<BriefingTask> = emptyList(), val status: String = "")

internal object BriefingTasks {
    val request: AuthorizationRequest
        get() = AuthorizationRequest.builder().setRequestedScopes(
            listOf(Scope("https://www.googleapis.com/auth/tasks.readonly"))).build()

    fun fetch(c: Context): TaskBriefing {
        if (!MorningBriefing.prefs(c).getBoolean("tasks_connected", false))
            return TaskBriefing(status = "Google Tasks is not connected.")
        return try {
            val auth = Tasks.await(Identity.getAuthorizationClient(c).authorize(request), 10, TimeUnit.SECONDS)
            if (auth.hasResolution() || auth.accessToken.isNullOrBlank())
                TaskBriefing(status = "Google Tasks needs reconnection in Morning briefing settings.")
            else download(auth.accessToken!!).also {
                MorningBriefing.prefs(c).edit().putString("tasks_status",
                    "Connected. Checked " + Instant.now().toString()).apply()
            }
        } catch (_: Exception) {
            TaskBriefing(status = "Google Tasks could not be checked. Try Connect Google Tasks in settings.")
        }
    }

    fun download(token: String, today: LocalDate = LocalDate.now()): TaskBriefing {
        val result = mutableListOf<BriefingTask>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
        fun get(path: String): JSONObject {
            check(System.nanoTime() < deadline) { "Tasks request timed out." }
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
        var listsPage = ""
        var requests = 0
        do {
            val lists = get("users/@me/lists?maxResults=100&pageToken=" + enc(listsPage))
            val entries = lists.optJSONArray("items")
            for (i in 0 until (entries?.length() ?: 0)) {
                val id = entries!!.getJSONObject(i).getString("id")
                var page = ""
                do {
                    check(++requests <= 100) { "Too many task pages. Briefing could not check every list." }
                    val response = get("lists/${enc(id)}/tasks?maxResults=100&showCompleted=false&showDeleted=false" +
                        "&showAssigned=true&dueMin=${today}T00:00:00Z&dueMax=${today.plusDays(2)}T00:00:00Z&pageToken=" + enc(page))
                    val items = response.optJSONArray("items")
                    for (j in 0 until (items?.length() ?: 0)) {
                        val item = items!!.getJSONObject(j)
                        val due = runCatching { LocalDate.parse(item.optString("due").take(10)) }.getOrNull()
                        if (due != null && (due == today || due == today.plusDays(1)) &&
                            item.optString("status") != "completed" && !item.optBoolean("deleted")) {
                            result.add(BriefingTask(item.optString("title", "Untitled task").take(180), due))
                        }
                    }
                    page = response.optString("nextPageToken")
                } while (page.isNotEmpty())
            }
            listsPage = lists.optString("nextPageToken")
        } while (listsPage.isNotEmpty())
        return TaskBriefing(result.sortedBy { it.due })
    }
}
