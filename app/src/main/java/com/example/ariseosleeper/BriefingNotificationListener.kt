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

class BriefingNotificationListener : NotificationListenerService() {
    private val store by lazy { BriefingMessages(this) }
    override fun onListenerConnected() {
        activeNotifications?.forEach(::onNotificationPosted)
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!MorningBriefing.unlocked(this) && !getSystemService(android.os.UserManager::class.java).isUserUnlocked) return
        val source = when (sbn.packageName) {
            "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
            "com.google.android.gm" -> "Gmail"
            else -> return
        }
        if (!MorningBriefing.allowed(this, source)) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        try {
            val rules = BriefingFilters.read(this)
            fun collect(from: String, body: String, at: Long, conversation: String) {
                if (BriefingFilters.allows(BriefingMessage(source, from, body, conversation), rules))
                    store.add(source, from, body, at, conversation)
            }
            val extras = n.extras
            val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            val messages = if (bundles == null) emptyList()
                else Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
            if (messages.isNotEmpty()) {
                messages.takeLast(30).forEach { msg ->
                    collect(msg.senderPerson?.name?.toString() ?: sender,
                        msg.text?.toString().orEmpty(), msg.timestamp.takeIf { it > 0 } ?: sbn.postTime, sender)
                }
            } else {
                val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                collect(sender, text?.toString().orEmpty(), sbn.postTime, sender)
            }
        } catch (_: RuntimeException) {
            // Malformed or redacted notifications must never disrupt the alarm.
        }
    }
    override fun onDestroy() { store.close(); super.onDestroy() }
}
