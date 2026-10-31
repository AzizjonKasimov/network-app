package com.azizjon.network.inbox

import com.azizjon.network.ai.AgentClient
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Why a draft could not be opened, worded to follow "could not be opened:". */
class InboxCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A message the PC queued for the Assistant, as this phone opened it. */
data class InboxDraft(val id: String, val text: String, val queuedAt: Long)

/**
 * This phone's inbox key pair, base64 encoded: the public half (X.509) is
 * published for the PC, the private half (PKCS#8) never leaves the phone.
 * [folder] is where in the data repository this key's drafts arrive.
 */
data class InboxKey(
    val publicKey: String,
    val privateKey: String,
    val folder: String,
    val createdAt: Long,
    /** The blob sha of the `key.json` this phone last wrote, to notice a change without downloading it. */
    val remoteSha: String = "",
) {
    val keyId: String get() = InboxCodec.keyId(publicKey)
    val fingerprint: String get() = InboxCodec.fingerprint(publicKey)
}

/**
 * The inbox's file formats and encryption.
 *
 * The PC (`scripts/queue-draft.ps1`) makes a one-time P-256 key per draft,
 * agrees a secret with this phone's public key, and hashes the format label,
 * that secret, and both public keys into an AES-256-GCM key. Only this phone's
 * private key can repeat the agreement, and no passphrase is involved, so the
 * PC holds no secret and the backup's protection is untouched.
 */
object InboxCodec {
    const val DRAFT_FORMAT = "network-app-inbox-draft"
    const val KEY_FORMAT = "network-app-inbox-key"
    const val VERSION = 1

    /** Far above what a 4,000-character draft seals to, and small enough to turn a flood away cheaply. */
    const val MAX_ENVELOPE_BYTES = 64 * 1024

    private const val CURVE = "secp256r1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val label = "$DRAFT_FORMAT:$VERSION".toByteArray(Charsets.UTF_8)
    private val draftId = Regex("^[0-9a-f]{32}$")

    fun newKey(folder: String, createdAt: Long, random: SecureRandom = SecureRandom()): InboxKey {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec(CURVE), random)
        val pair = generator.generateKeyPair()
        return InboxKey(
            publicKey = encode(pair.public.encoded),
            privateKey = encode(pair.private.encoded),
            folder = folder,
            createdAt = createdAt,
        )
    }

    /** The first 16 hex digits of the public key's SHA-256, which each draft names. */
    fun keyId(publicKey: String): String =
        MessageDigest.getInstance("SHA-256").digest(decode(publicKey)).toHex().take(16)

    /** [keyId] in groups of four, as Settings and the PC script both show it. */
    fun fingerprint(publicKey: String): String = keyId(publicKey).uppercase().chunked(4).joinToString(" ")

    /** The `key.json` the PC script reads. */
    fun keyDocument(key: InboxKey): String = JSONObject()
        .put("format", KEY_FORMAT)
        .put("version", VERSION)
        .put("keyId", key.keyId)
        .put("publicKey", key.publicKey)
        .put("createdAt", key.createdAt)
        .toString(2)

    /** The public key a `key.json` publishes, or null when it is not one. */
    fun publishedKey(document: String): String? = runCatching {
        val json = JSONObject(document)
        if (json.optString("format") != KEY_FORMAT || json.optInt("version") != VERSION) null else json.getString("publicKey")
    }.getOrNull()

    fun open(envelope: String, key: InboxKey): InboxDraft {
        if (envelope.length > MAX_ENVELOPE_BYTES) throw InboxCodecException("it is too large")
        val json = runCatching { JSONObject(envelope) }.getOrNull() ?: throw InboxCodecException("it is damaged")
        if (json.optString("format") != DRAFT_FORMAT || json.optInt("version") != VERSION) {
            throw InboxCodecException("it was made by a newer or unknown version of the PC script")
        }
        if (json.optString("keyId") != key.keyId) {
            throw InboxCodecException("it was made for an earlier key of this phone")
        }
        val plaintext = try {
            val recipient = decode(key.publicKey)
            val ephemeral = decode(json.getString("ephemeralKey"))
            val iv = decode(json.getString("iv"))
            if (iv.size != IV_BYTES) throw InboxCodecException("it is damaged")
            val sealed = decode(json.getString("ciphertext"))
            val factory = KeyFactory.getInstance("EC")
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(factory.generatePrivate(PKCS8EncodedKeySpec(decode(key.privateKey))))
            agreement.doPhase(factory.generatePublic(X509EncodedKeySpec(ephemeral)), true)
            val secret = agreement.generateSecret()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(label)
            digest.update(secret)
            digest.update(ephemeral)
            digest.update(recipient)
            val aesKey = digest.digest()
            secret.fill(0)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(label)
                cipher.doFinal(sealed).toString(Charsets.UTF_8)
            } finally {
                aesKey.fill(0)
            }
        } catch (error: InboxCodecException) {
            throw error
        } catch (error: GeneralSecurityException) {
            throw InboxCodecException("it is damaged or was not made for this phone", error)
        } catch (error: Exception) {
            throw InboxCodecException("it is damaged", error)
        }
        return draftFrom(plaintext)
    }

    private fun draftFrom(plaintext: String): InboxDraft {
        val json = runCatching { JSONObject(plaintext) }.getOrNull() ?: throw InboxCodecException("it is damaged")
        val id = json.optString("id")
        val text = json.optString("text")
        val queuedAt = json.optLong("queuedAt")
        if (!draftId.matches(id) || queuedAt <= 0) throw InboxCodecException("it is damaged")
        if (text.isBlank()) throw InboxCodecException("it is empty")
        if (text.length > AgentClient.MAX_INPUT_CHARACTERS) {
            throw InboxCodecException("it is longer than the ${AgentClient.MAX_INPUT_CHARACTERS} characters a message can hold")
        }
        return InboxDraft(id, text, queuedAt)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
