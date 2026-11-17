package com.example.ariseosleeper

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class BriefingFilter(
    val id: String = UUID.randomUUID().toString(),
    val app: String = "Any app",
    val field: String = "Sender or conversation",
    val match: String = "Contains",
    val pattern: String,
    val include: Boolean = false,
    val enabled: Boolean = true
) {
    fun applies(message: BriefingMessage) = app == "Any app" || app == message.app
    fun matches(message: BriefingMessage): Boolean {
        val values = when (field) {
            "Sender or conversation" -> listOf(message.sender, message.conversation)
            "Message text" -> listOf(message.text)
            else -> listOf(message.sender, message.conversation, message.text)
        }
        return values.any { value ->
            when (match) {
                "Equals" -> value.equals(pattern, ignoreCase = true)
                "Starts with" -> value.startsWith(pattern, ignoreCase = true)
                else -> value.contains(pattern, ignoreCase = true)
            }
        }
    }
}

internal object BriefingFilters {
    fun allows(message: BriefingMessage, rules: List<BriefingFilter>): Boolean {
        val applicable = rules.filter { it.enabled && it.applies(message) }
        if (applicable.any { !it.include && it.matches(message) }) return false
        val includes = applicable.filter { it.include }
        return includes.isEmpty() || includes.any { it.matches(message) }
    }
    fun read(c: Context): List<BriefingFilter> = runCatching {
        val array = JSONArray(MorningBriefing.prefs(c).getString("notification_filters", "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o ->
            BriefingFilter(o.getString("id"), o.getString("app"), o.getString("field"),
                o.getString("match"), o.getString("pattern"), o.optBoolean("include"), o.optBoolean("enabled", true))
        } }
    }.getOrDefault(emptyList())

    fun save(c: Context, rules: List<BriefingFilter>) {
        val data = JSONArray().apply { rules.forEach { rule ->
            put(JSONObject().put("id", rule.id).put("app", rule.app).put("field", rule.field)
                .put("match", rule.match).put("pattern", rule.pattern).put("include", rule.include).put("enabled", rule.enabled))
        } }
        c.stopService(android.content.Intent(c, BriefingService::class.java))
        MorningBriefing.prefs(c).edit().putString("playback_status", "Filters updated. Read a new briefing to apply them.")
            .putString("notification_filters", data.toString())
            .remove("last_summary").remove("summary_at").commit()
    }
}
