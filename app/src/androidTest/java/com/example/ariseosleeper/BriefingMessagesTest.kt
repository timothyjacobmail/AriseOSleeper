package com.example.ariseosleeper

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

class BriefingMessagesTest {
    @Test fun repostsAreDeduplicatedAndOnlyTheRequestedWindowIsRead() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        BriefingMessages(context).use { store ->
            store.clear()
            try {
                val now = System.currentTimeMillis()
                store.add("WhatsApp", "Test sender", "Test message", now - 5000)
                store.add("WhatsApp", "Test sender", "Test message", now - 4000)
                store.add("Gmail", "Test sender", "Test subject", now - 1000)
                assertEquals(2, store.read(now - 10_000, now).size)
                assertEquals("Gmail", store.read(now - 2000, now).single().app)
                assertTrue(store.read(now + 1, now + 1000).isEmpty())
                store.clear()
                assertTrue(store.read(0, now).isEmpty())
            } finally { store.clear() }
        }
    }
}
