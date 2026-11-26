package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class InFlightGameSessionTest {
    private fun tempFile(): File =
        File(Files.createTempDirectory("inflight").toFile(), InFlightGameSession.FILE_NAME)

    @Test
    fun saveThenConsumeRoundTripsAndRemovesFile() {
        val file = tempFile()
        val snap = InFlightGameSession.Snapshot(
            gameId = "wordle",
            openLog = true,
            elapsedMs = 42_500L,
            openedAt = 1_773_540_000_000L,
            shareText = "Wordle 1234 3/6\n🟩🟩🟩🟩🟩",
            shareConfirmed = true,
            earlierJson = """[{"id":"connections","elapsedMs":15000,"openedAt":1773539000000,"share":"","confirmed":false}]"""
        )

        assertTrue(InFlightGameSession.saveToFile(file, snap))
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())

        val consumed = InFlightGameSession.consumeFromFile(file)
        assertNotNull(consumed)
        assertEquals(snap, consumed)
        assertFalse(file.exists())
        assertNull(InFlightGameSession.consumeFromFile(file))
    }

    @Test
    fun clearRemovesExistingAndTempFiles() {
        val file = tempFile()
        val tmp = File(file.parentFile, file.name + ".tmp")
        val snap = InFlightGameSession.Snapshot("w", false, 1000L, 100L, "", false, "")
        assertTrue(InFlightGameSession.saveToFile(file, snap))
        tmp.writeText("stale")
        InFlightGameSession.clearFile(file)
        assertFalse(file.exists())
        assertFalse(tmp.exists())
        assertNull(InFlightGameSession.consumeFromFile(file))
    }

    @Test
    fun corruptOrBlankSessionFileReturnsNullAndCleansUp() {
        val file = tempFile()
        file.writeText("{not-valid-json")
        assertNull(InFlightGameSession.consumeFromFile(file))
        assertFalse(file.exists())

        // Missing or blank gameId is treated as invalid and cleaned up.
        file.writeText("""{"gameId":"  ","elapsedMs":500}""")
        assertNull(InFlightGameSession.consumeFromFile(file))
        assertFalse(file.exists())

        // Blank gameId refuses to write.
        assertFalse(
            InFlightGameSession.saveToFile(
                file,
                InFlightGameSession.Snapshot("", false, 0L, 0L, "", false, "")
            )
        )
        assertFalse(file.exists())
    }
}
