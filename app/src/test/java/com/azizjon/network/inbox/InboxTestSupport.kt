package com.azizjon.network.inbox

import com.azizjon.network.backup.GitHubBackupConfig
import com.azizjon.network.backup.GitHubBackupException
import com.azizjon.network.backup.GitHubFile
import com.azizjon.network.backup.GitHubFiles
import com.azizjon.network.backup.GitHubText
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A configuration the inbox accepts; no network is ever reached in these tests. */
internal val testConfig = GitHubBackupConfig(token = "test-token", passphrase = "synthetic passphrase")

internal fun draftId(n: Int): String = n.toString(16).padStart(32, '0')

/** Seals a draft the way `scripts/queue-draft.ps1` does, for tests that need many. */
internal fun seal(
    text: String,
    recipientPublicKey: String,
    id: String = draftId(SecureRandom().nextInt(1_000_000) + 1),
    queuedAt: Long = 1_761_900_000_000,
): String {
    val recipient = Base64.getDecoder().decode(recipientPublicKey)
    val ephemeral = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()
    val agreement = KeyAgreement.getInstance("ECDH")
    agreement.init(ephemeral.private)
    agreement.doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(recipient)), true)
    val label = "network-app-inbox-draft:1".toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(label)
    digest.update(agreement.generateSecret())
    digest.update(ephemeral.public.encoded)
    digest.update(recipient)
    val iv = ByteArray(12).also(SecureRandom()::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.digest(), "AES"), GCMParameterSpec(128, iv))
    cipher.updateAAD(label)
    val plaintext = JSONObject().put("id", id).put("text", text).put("queuedAt", queuedAt).toString()
    val base64 = Base64.getEncoder()
    return JSONObject()
        .put("format", "network-app-inbox-draft")
        .put("version", 1)
        .put("keyId", InboxCodec.keyId(recipientPublicKey))
        .put("ephemeralKey", base64.encodeToString(ephemeral.public.encoded))
        .put("iv", base64.encodeToString(iv))
        .put("ciphertext", base64.encodeToString(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))))
        .toString()
}

/** The data repository in memory, refusing a stale sha the way GitHub does. */
internal class FakeFiles : GitHubFiles {
    val files = linkedMapOf<String, GitHubText>()
    var private = true
    var writes = 0
    var beforeDelete: (String) -> Unit = {}
    private var nextSha = 1

    /** A file written by someone else, such as the PC script. */
    fun upload(path: String, text: String) {
        files[path] = GitHubText("sha${nextSha++}", text)
    }

    override fun requirePrivateRepository(config: GitHubBackupConfig) {
        if (!private) throw GitHubBackupException("Backup repository must be private")
    }

    override fun read(config: GitHubBackupConfig, path: String): GitHubText? = files[path]

    override fun list(config: GitHubBackupConfig, folder: String): List<GitHubFile> =
        files.filterKeys { it.startsWith("$folder/") && '/' !in it.removePrefix("$folder/") }
            .map { (path, file) -> GitHubFile(path.substringAfterLast('/'), path, file.sha, file.text.length.toLong()) }

    override fun write(config: GitHubBackupConfig, path: String, text: String, sha: String?, message: String): String {
        if (files[path]?.sha != sha) throw GitHubBackupException("GitHub HTTP 409")
        writes++
        return "sha${nextSha++}".also { files[path] = GitHubText(it, text) }
    }

    override fun delete(config: GitHubBackupConfig, path: String, sha: String, message: String) {
        beforeDelete(path)
        val existing = files[path] ?: return
        if (existing.sha != sha) throw GitHubBackupException("GitHub HTTP 409")
        files.remove(path)
    }
}

internal class MemoryStorage : InboxStorage {
    var saved = InboxState()

    override fun load(): InboxState = saved

    override fun save(state: InboxState) {
        // Through JSON, as on the phone, so a field the store forgets fails here.
        saved = InboxState.fromJson(state.toJson())
    }
}
