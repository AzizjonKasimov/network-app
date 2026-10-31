package com.azizjon.network.inbox

import com.azizjon.network.backup.GitHubBackupException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PcInboxTest {
    private val files = FakeFiles()
    private val storage = MemoryStorage()
    private var now = 1_000L
    private val inbox = PcInbox(files, storage) { now }
    private val folder = "network-backup-inbox"
    private val keyPath = "$folder/key.json"

    private fun enabledKey(): InboxKey {
        inbox.enable(testConfig)
        return storage.saved.key!!
    }

    @Test
    fun enablePublishesThePublicKeyAndKeepsThePrivateHalfOnThePhone() {
        val key = enabledKey()

        val published = files.files.getValue(keyPath).text
        assertEquals(key.publicKey, InboxCodec.publishedKey(published))
        assertFalse(key.privateKey in published)
        assertEquals(folder, key.folder)
        assertEquals(files.files.getValue(keyPath).sha, key.remoteSha)
    }

    @Test
    fun enableRefusesAPublicRepositoryAndKeepsNoKey() {
        files.private = false

        assertThrows(GitHubBackupException::class.java) { inbox.enable(testConfig) }
        assertNull(storage.saved.key)
        assertTrue(files.files.isEmpty())
    }

    @Test
    fun checkTakesDraftsOldestFirstAndDeletesEachOnlyOnceSaved() {
        val key = enabledKey()
        files.upload("$folder/20261031T080000Z-bbbbbbbb.enc.json", seal("Second", key.publicKey, draftId(2)))
        files.upload("$folder/20261031T070000Z-aaaaaaaa.enc.json", seal("First", key.publicKey, draftId(1)))
        files.beforeDelete = { path ->
            val id = if ("aaaa" in path) draftId(1) else draftId(2)
            assertTrue("$path deleted before it was saved", storage.saved.pending.any { it.id == id })
        }

        val result = inbox.check(testConfig)

        assertEquals(2, result.taken)
        assertEquals(listOf("First", "Second"), storage.saved.pending.map { it.text })
        assertEquals(setOf(keyPath), files.files.keys)
        assertEquals("", storage.saved.lastError)
        assertEquals(now, storage.saved.lastCheckAt)
    }

    @Test
    fun aCopyOfATakenDraftFromGitHistoryIsNotShownAgain() {
        val key = enabledKey()
        val envelope = seal("Met Tomas Reyes", key.publicKey, draftId(5))
        files.upload("$folder/20261031T070000Z-00000005.enc.json", envelope)
        inbox.check(testConfig)
        assertNotNull(inbox.take(draftId(5)))

        files.upload("$folder/20261031T070000Z-00000005.enc.json", envelope)
        val again = inbox.check(testConfig)

        assertEquals(0, again.taken)
        assertTrue(storage.saved.pending.isEmpty())
        assertEquals(setOf(keyPath), files.files.keys)
    }

    @Test
    fun draftsThatCannotBeOpenedAreDeletedWithTheirReason() {
        val key = enabledKey()
        val earlier = InboxCodec.newKey(folder, createdAt = 1)
        files.upload("$folder/1.enc.json", seal("For the old key", earlier.publicKey))
        files.upload("$folder/2.enc.json", "not a draft")
        files.upload("$folder/3.enc.json", "x".repeat(InboxCodec.MAX_ENVELOPE_BYTES + 1))
        files.upload("$folder/4.enc.json", seal("Fine", key.publicKey, draftId(4)))

        val result = inbox.check(testConfig)

        assertEquals(1, result.taken)
        assertEquals(
            listOf("it was made for an earlier key of this phone", "it is damaged", "it is too large"),
            result.removed,
        )
        assertEquals(listOf("Fine"), storage.saved.pending.map { it.text })
        assertEquals(setOf(keyPath), files.files.keys)
    }

    @Test
    fun aSwappedKeyIsPutBackAndReported() {
        val key = enabledKey()
        val stranger = InboxCodec.newKey(folder, createdAt = 9)
        files.upload(keyPath, InboxCodec.keyDocument(stranger))

        val result = inbox.check(testConfig)

        assertTrue(result.keyRestored)
        assertEquals(key.publicKey, InboxCodec.publishedKey(files.files.getValue(keyPath).text))
        assertEquals(files.files.getValue(keyPath).sha, storage.saved.key?.remoteSha)
    }

    @Test
    fun aMissingKeyIsPutBackAndReported() {
        val key = enabledKey()
        files.files.remove(keyPath)

        assertTrue(inbox.check(testConfig).keyRestored)
        assertEquals(key.publicKey, InboxCodec.publishedKey(files.files.getValue(keyPath).text))
    }

    @Test
    fun anUnchangedKeyCostsNoWrite() {
        enabledKey()
        val writes = files.writes

        val result = inbox.check(testConfig)

        assertFalse(result.keyRestored)
        assertEquals(writes, files.writes)
    }

    @Test
    fun atMostTwentyDraftsWaitAndTheRestStayQueued() {
        val key = enabledKey()
        repeat(25) { n -> files.upload("$folder/${n.toString().padStart(2, '0')}.enc.json", seal("Draft $n", key.publicKey, draftId(n + 1))) }

        val result = inbox.check(testConfig)

        assertEquals(PcInbox.MAX_WAITING, result.taken)
        assertEquals("Draft 0", storage.saved.pending.first().text)
        assertEquals(5, files.list(testConfig, folder).count { it.name.endsWith(".enc.json") })
    }

    @Test
    fun aFailedCheckIsRecordedAndTakesNothing() {
        val key = enabledKey()
        files.upload("$folder/1.enc.json", seal("Waiting", key.publicKey))
        files.private = false
        now = 2_000

        assertThrows(GitHubBackupException::class.java) { inbox.check(testConfig) }
        assertEquals("Backup repository must be private", storage.saved.lastError)
        assertEquals(2_000, storage.saved.lastCheckAt)
        assertTrue(storage.saved.pending.isEmpty())
        assertTrue("$folder/1.enc.json" in files.files)
    }

    @Test
    fun disableDeletesTheKeyEverywhereButKeepsTakenDrafts() {
        val key = enabledKey()
        files.upload("$folder/1.enc.json", seal("Taken", key.publicKey, draftId(1)))
        inbox.check(testConfig)
        files.upload("$folder/2.enc.json", seal("Still queued", key.publicKey, draftId(2)))

        inbox.disable(testConfig)

        assertNull(storage.saved.key)
        assertTrue(files.files.isEmpty())
        assertEquals(listOf("Taken"), storage.saved.pending.map { it.text })
    }

    @Test
    fun checkDoesNothingWhileOff() {
        files.upload("$folder/1.enc.json", "anything")

        assertEquals(InboxCheck(), inbox.check(testConfig))
        assertTrue("$folder/1.enc.json" in files.files)
    }

    @Test
    fun takeRemovesOnlyThatDraft() {
        val key = enabledKey()
        files.upload("$folder/1.enc.json", seal("One", key.publicKey, draftId(1)))
        files.upload("$folder/2.enc.json", seal("Two", key.publicKey, draftId(2)))
        inbox.check(testConfig)

        assertEquals("One", inbox.take(draftId(1))?.text)
        assertNull(inbox.take(draftId(1)))
        assertEquals(listOf("Two"), inbox.state.value.pending.map { it.text })
    }
}
