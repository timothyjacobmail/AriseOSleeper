package com.example.ariseosleeper

import android.app.Notification
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.security.MessageDigest

internal data class BriefingMessage(val app: String, val sender: String, val text: String, val conversation: String = "")

internal class BriefingMessages(c: Context) : SQLiteOpenHelper(c, "briefing_notifications.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE messages (digest TEXT PRIMARY KEY, app TEXT NOT NULL, sender TEXT NOT NULL, body TEXT NOT NULL, received INTEGER NOT NULL, conversation TEXT NOT NULL DEFAULT '')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("ALTER TABLE messages ADD COLUMN conversation TEXT NOT NULL DEFAULT ''")
    }

    @Synchronized fun add(app: String, sender: String, body: String, at: Long, conversation: String = "") {
        val text = body.replace(Regex("\\s+"), " ").trim().take(400)
        if (text.isBlank()) return
        val from = sender.replace(Regex("\\s+"), " ").trim().take(120)
        // Content hashes collapse reposts/group expansions. No notification content is logged.
        val hash = MessageDigest.getInstance("SHA-256").digest("$app|$from|$text|${conversation.take(120)}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val values = ContentValues().apply {
            put("digest", hash); put("app", app); put("sender", from); put("body", text); put("received", at)
            put("conversation", conversation.take(120))
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete("messages", "received < ?", arrayOf((System.currentTimeMillis() - 86_400_000).toString()))
            writableDatabase.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            writableDatabase.execSQL("DELETE FROM messages WHERE digest NOT IN (SELECT digest FROM messages ORDER BY received DESC LIMIT 500)")
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }
    @Synchronized fun read(since: Long, through: Long): List<BriefingMessage> {
        writableDatabase.delete("messages", "received < ?", arrayOf((System.currentTimeMillis() - 86_400_000).toString()))
        return readableDatabase.query("messages", arrayOf("app", "sender", "body", "conversation"),
            "received > ? AND received <= ?", arrayOf(since.toString(), through.toString()),
            null, null, "received DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(BriefingMessage(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))) }
        }
    }
    fun clear() { writableDatabase.delete("messages", null, null) }
}

internal data class BriefingActiveNotification(val app: String, val messages: List<BriefingMessage>)

class BriefingNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile private var connected: BriefingNotificationListener? = null
        internal fun currentNotifications(): List<BriefingActiveNotification>? = try {
            connected?.activeNotifications?.mapNotNull { sbn ->
                val app = source(sbn.packageName) ?: return@mapNotNull null
                if (sbn.notification.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0)
                    return@mapNotNull null
                BriefingActiveNotification(app, previews(sbn, app).map { it.first })
            }
        } catch (_: RuntimeException) { null }

        private fun source(packageName: String): String? = when (packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.google.android.gm" -> "Gmail"
            else -> null
        }
        private fun previews(sbn: StatusBarNotification, source: String): List<Pair<BriefingMessage, Long>> {
            val extras = sbn.notification.extras
            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(120)
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val messages = if (bundles == null) emptyList()
                else Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            if (messages.isNotEmpty()) return messages.takeLast(30).map {
                BriefingMessage(source, it.senderPerson?.name?.toString()?.take(120) ?: sender,
                    it.text?.toString().orEmpty().take(400), sender) to (it.timestamp.takeIf { at -> at > 0 } ?: sbn.postTime)
            }
            val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            return listOf(BriefingMessage(source, sender, text?.toString().orEmpty().take(400), sender) to sbn.postTime)
        }
    }
    private val store by lazy { BriefingMessages(this) }
    override fun onListenerConnected() {
        connected = this
        activeNotifications?.forEach(::onNotificationPosted)
    }
    override fun onListenerDisconnected() {
        if (connected === this) connected = null
        super.onListenerDisconnected()
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!MorningBriefing.unlocked(this) && !getSystemService(android.os.UserManager::class.java).isUserUnlocked) return
        val source = source(sbn.packageName) ?: return
        if (!MorningBriefing.allowed(this, source)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        try {
            val rules = BriefingFilters.read(this)
            for ((message, at) in previews(sbn, source)) {
                if (BriefingFilters.allows(message, rules))
                    store.add(source, message.sender, message.text, at, message.conversation)
            }
        } catch (_: RuntimeException) {
            // Malformed or redacted notifications must never disrupt the alarm.
        }
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        store.close()
        super.onDestroy()
    }
}
