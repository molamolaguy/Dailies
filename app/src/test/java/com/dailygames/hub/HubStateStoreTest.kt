package com.dailygames.hub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class HubStateStoreTest {

    private fun tempFile(): File = File(Files.createTempDirectory("hubstate").toFile(), HubStateStore.FILE_NAME)

    @Test
    fun writeThenRead_roundTripsLargeStateWithoutCaps() {
        val file = tempFile()
        val store = HubStateStore(file)
        // ~12 MB: far beyond WebView localStorage's ~5 MB quota.
        val logs = (0 until 60_000).joinToString(",") { """{"date":"2026-01-01","gameId":"g$it","score":"🟩🟩🟨⬛⬛ result row $it"}""" }
        val json = """{"games":[],"historyLogs":[$logs]}"""
        store.write(json)
        assertEquals(json, store.read()) // read-after-write sees the queued value immediately
        store.close()

        assertEquals(json, HubStateStore(file).read())
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }

    @Test
    fun blankWritesNeverWipeExistingState() {
        val file = tempFile()
        val store = HubStateStore(file)
        store.write("""{"games":[1]}""")
        store.flush()
        store.write("")
        store.write("   ")
        store.close()
        assertEquals("""{"games":[1]}""", HubStateStore(file).read())
    }

    @Test
    fun rapidWritesCoalesceToLatest() {
        val file = tempFile()
        val store = HubStateStore(file)
        repeat(200) { store.write("""{"v":$it}""") }
        store.close()
        assertEquals("""{"v":199}""", HubStateStore(file).read())
    }

    @Test
    fun missingFileReadsAsEmpty() {
        assertEquals("", HubStateStore(tempFile()).read())
    }

    @Test
    fun fallsBackToBackupAndNeverReportsEmptyWhenDataIsUnreadable() {
        val file = tempFile()
        val store = HubStateStore(file)
        store.write("""{"v":1}""")
        store.flush()
        store.write("""{"v":2}""")
        store.flush()
        // A .bak of the previous good state exists after the second write.
        assertEquals("""{"v":1}""", File(file.parentFile, file.name + ".bak").readText())
        // Main file corrupted to an unreadable thing (a directory) -> backup is served.
        file.delete(); file.mkdirs()
        assertEquals("""{"v":1}""", HubStateStore(file).read())
        // Both unreadable -> READ_ERROR, never "" (which the hub would treat as "no data").
        File(file.parentFile, file.name + ".bak").let { it.delete(); it.mkdirs() }
        assertEquals(HubStateStore.READ_ERROR, HubStateStore(file).read())
    }

    @Test
    fun quarantineKeepsTheDataAside() {
        val file = tempFile()
        file.writeText("not json")
        val store = HubStateStore(file)
        assertTrue(store.quarantine())
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles()!!.any { it.name.contains("unreadable") && it.readText() == "not json" })
        assertEquals("", store.read())
    }

    @Test
    fun truncatedMainFileFallsBackToBackup() {
        val file = tempFile()
        val store = HubStateStore(file)
        store.write("""{"v":1}""")
        store.flush()
        store.write("""{"v":2}""")
        store.close()
        file.writeText("""{"v":""") // cut off mid-write by a crash / power loss
        assertEquals("""{"v":1}""", HubStateStore(file).read())
        // Zero-filled main file too.
        file.writeText("\u0000".repeat(16))
        assertEquals("""{"v":1}""", HubStateStore(file).read())
    }

    @Test
    fun failedWriteIsRetriedByTheNextSaveAndReported() {
        val file = tempFile()
        val results = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val store = HubStateStore(file, onWriteResult = { results.add(it) })
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.mkdirs() // the temp path is a directory: the write fails
        store.write("""{"v":1}""")
        waitFor { results.isNotEmpty() }
        assertEquals(listOf(false), results.toList())
        assertFalse(file.exists())
        tmp.delete()
        store.write("""{"v":2}""") // no flush()/close(): the save itself must schedule the retry
        waitFor { file.exists() }
        assertEquals("""{"v":2}""", file.readText())
        waitFor { results.size == 2 }
        assertEquals(listOf(false, true), results.toList())
        store.close()
    }

    private fun waitFor(cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not met in time", cond())
    }
}
