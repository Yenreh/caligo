package com.yenreh.caligo.data.datasources

import java.io.File
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The JSON cache's files, and the sweep of those nobody uses */
class FileJsonCacheTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val today = 1_790_000_000_000L
    private var now = today

    private val folder by lazy { temporary.newFolder("cache") }

    /** A cache as a new run opens it: nothing in memory */
    private fun run() = FileJsonCache(folder, now = { now }, io = Dispatchers.Unconfined)

    private fun file(key: String) = File(folder, "$key.json")

    @Test
    fun aSweepKeepsWhatWasReadLatelyHoweverLongAgoItWasWritten() = runTest {
        val written = today - 50.days.inWholeMilliseconds
        now = written
        run().write("read", JsonPrimitive(1))
        run().write("unread", JsonPrimitive(2))
        file("read").setLastModified(written)
        file("unread").setLastModified(written)

        now = today
        assertEquals(JsonPrimitive(1), run().readEntry("read")!!.value)
        run().prune(45.days)

        // The Flutter app swept by the last write: a stop's lines read every
        // day went all the same
        assertTrue(file("read").exists())
        assertFalse(file("unread").exists())
        // Read lately, still as old as when it was saved
        assertEquals(written, run().readEntry("read")!!.savedAt)
        assertNull(run().read("read", maxAge = 1.days))
    }
}
