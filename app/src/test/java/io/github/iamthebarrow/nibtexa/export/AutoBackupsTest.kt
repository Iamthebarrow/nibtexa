package io.github.iamthebarrow.nibtexa.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate

class AutoBackupsTest {
    private class Memory(names: List<String> = emptyList()) : BackupFolder {
        val files = names.associateWithTo(sortedMapOf()) { "old" }

        override fun names() = files.keys.toList()

        override fun create(name: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                files[name] = toString()
            }
        }.also { files[name] = "" }

        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private val today = LocalDate.of(2026, 10, 31)
    private fun name(day: String) = "${AutoBackups.PREFIX}$day.nibtexa.zip"

    @Test
    fun itWritesTodaysAndKeepsTheThreeNewest() {
        val folder = Memory(listOf(name("2026-10-20"), name("2026-10-25"), name("2026-10-30"), "my own file.zip"))
        val written = AutoBackups.run(folder, today) { it.write("new".toByteArray()) }
        assertEquals(name("2026-10-31"), written)
        assertEquals("new", folder.files[written])
        assertEquals(setOf(name("2026-10-25"), name("2026-10-30"), name("2026-10-31"), "my own file.zip"), folder.files.keys)
    }

    @Test
    fun aSecondOneInADayReplacesTheFirst() {
        val folder = Memory(listOf(name("2026-10-31")))
        AutoBackups.run(folder, today) { it.write("again".toByteArray()) }
        assertEquals("again", folder.files[name("2026-10-31")])
        assertEquals(1, folder.files.size)
    }

    @Test
    fun aFailedBackupLeavesNoPartFileAndTheOlderOnes() {
        val folder = Memory(listOf(name("2026-10-30")))
        assertThrows(IOException::class.java) { AutoBackups.run(folder, today) { throw IOException("disk full") } }
        assertEquals(setOf(name("2026-10-30")), folder.files.keys)
    }

    @Test
    fun itIsDueOnceADay() {
        val hour = 60L * 60 * 1000
        assertTrue(AutoBackups.isDue(1000 * hour, 0))
        assertTrue(AutoBackups.isDue(1000 * hour, 976 * hour))
        assertFalse(AutoBackups.isDue(1000 * hour, 990 * hour))
    }
}
