package io.github.iamthebarrow.nibtexa.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidatorTest {

    private val valid = SyncSettings(
        remoteUrl = "https://git.example.org/me/notes.git",
        authorName = "Writer",
        authorEmail = "writer@example.org",
    )

    private fun problems(settings: SyncSettings, policy: RemotePolicy = RemotePolicy.HTTPS_ONLY) = SettingsValidator.problems(settings, policy)

    @Test
    fun defaultsWithARemoteAndAuthorAreValid() {
        assertEquals(emptyList<SettingsProblem>(), problems(valid))
        assertEquals("nibtexa-export", valid.branch)
        assertEquals("nibtexa", valid.destination)
        assertFalse(valid.includeSource)
    }

    @Test
    fun onlyAllowedSchemesAreAccepted() {
        assertEquals(listOf(SettingsProblem.UnsupportedScheme("http")), problems(valid.copy(remoteUrl = "http://git.example.org/notes.git")))
        assertEquals(listOf(SettingsProblem.UnsupportedScheme("ssh")), problems(valid.copy(remoteUrl = "ssh://git@example.org/notes.git")))
        assertEquals(emptyList<SettingsProblem>(), problems(valid.copy(remoteUrl = "http://10.0.2.2:18080/notes.git"), RemotePolicy(setOf("https", "http"))))
    }

    @Test
    fun credentialsInTheUrlAreRefused() {
        assertEquals(listOf(SettingsProblem.CredentialsInUrl), problems(valid.copy(remoteUrl = "https://me:token@git.example.org/notes.git")))
        assertEquals(listOf(SettingsProblem.CredentialsInUrl), problems(valid.copy(remoteUrl = "https://token@git.example.org/notes.git")))
    }

    @Test
    fun malformedRemotesAreRefused() {
        assertEquals(listOf(SettingsProblem.MissingRemote), problems(valid.copy(remoteUrl = " ")))
        assertEquals(listOf(SettingsProblem.MalformedRemote), problems(valid.copy(remoteUrl = "git.example.org/notes.git")))
        assertEquals(listOf(SettingsProblem.MalformedRemote), problems(valid.copy(remoteUrl = "https:///notes.git")))
        assertEquals(listOf(SettingsProblem.MalformedRemote), problems(valid.copy(remoteUrl = "https://git.example.org/notes.git?x=1")))
        assertEquals(listOf(SettingsProblem.MalformedRemote), problems(valid.copy(remoteUrl = "https://git.example.org/a b")))
    }

    @Test
    fun branchNamesFollowGitsRules() {
        assertTrue(SettingsValidator.isValidBranch("notes/2026"))
        for (bad in listOf("", " x", "a..b", "a b", "x.lock", "-", "a~1", "a:b", "@{x}", "/x")) {
            assertFalse(bad, SettingsValidator.isValidBranch(bad))
        }
    }

    @Test
    fun theDestinationIsAPlainRelativeFolder() {
        assertEquals(listOf("notes", "nibtexa"), SettingsValidator.destinationSegments("notes/nibtexa/"))
        assertEquals(listOf("nibtexa"), SettingsValidator.destinationSegments("/nibtexa"))
        for (bad in listOf("", "/", "..", "a/../b", ".", "a//b", ".git", "x/.GIT", "a b", "a\\b", "C:", "...", "a/b/c/d/e/f/g/h/i")) {
            assertNull(bad, SettingsValidator.destinationSegments(bad))
        }
    }

    @Test
    fun theAuthorIsRequired() {
        assertEquals(listOf(SettingsProblem.MissingAuthor, SettingsProblem.InvalidEmail), problems(valid.copy(authorName = " ", authorEmail = "nobody")))
    }

    @Test
    fun copiesAreNamedNextToTheOriginal() {
        assertEquals("a/main.nibtexa.tex", copyPath("a/main.tex"))
        assertEquals("a/assets/p-1.nibtexa.png", copyPath("a/assets/p-1.png"))
        assertEquals("a/README.nibtexa", copyPath("a/README"))
        assertEquals("a/.hidden.nibtexa", copyPath("a/.hidden"))
    }

    @Test
    fun recordsRoundTripDeterministically() {
        val record = SyncRecord(files = mapOf("b.tex" to "2", "a.tex" to "1"), kept = mapOf("c.tex" to KeepMode.COPY))
        val text = record.encode()
        assertEquals(record, SyncRecord.decode(text))
        assertEquals(text, SyncRecord(files = mapOf("a.tex" to "1", "b.tex" to "2"), kept = mapOf("c.tex" to KeepMode.COPY)).encode())
        assertTrue(text.indexOf("a.tex") < text.indexOf("b.tex"))
        assertTrue(text.endsWith("}\n"))
    }

    @Test
    fun diffsShowTextChangesAndSkipBinaries() {
        val diff = Diffs.unified(FileChange("a/main.tex", ChangeKind.MODIFIED, "one\ntwo\n".toByteArray(), "one\n2\n".toByteArray()))!!
        assertTrue(diff, diff.contains("-two\n") && diff.contains("+2\n") && diff.contains(" one\n"))
        assertNull(Diffs.unified(FileChange("a/x.png", ChangeKind.ADDED, null, byteArrayOf(0x89.toByte(), 0x50, 0, 1))))
    }

    @Test
    fun aFolderNameBecomesOneSafeSegment() {
        assertEquals("Signals-Systems", SettingsValidator.folderSegment("Signals & Systems"))
        assertEquals("Term-1", SettingsValidator.folderSegment("  Term 1 "))
        assertEquals("a-b", SettingsValidator.folderSegment("../a/b"))
        assertEquals("x", SettingsValidator.folderSegment("..x.."))
        assertEquals(null, SettingsValidator.folderSegment("..."))
        assertEquals(null, SettingsValidator.folderSegment(".git"))
        assertEquals(null, SettingsValidator.folderSegment("日本語"))
        assertEquals(64, SettingsValidator.folderSegment("y".repeat(100))!!.length)
    }

    @Test
    fun theRedactorHidesTheToken() {
        assertEquals("auth *** failed", Redactor("tok").redact("auth tok failed"))
        assertEquals("unchanged", Redactor(null).redact("unchanged"))
    }
}
