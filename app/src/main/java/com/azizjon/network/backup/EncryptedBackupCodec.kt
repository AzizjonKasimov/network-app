package com.azizjon.network.backup

import com.azizjon.network.data.CapabilityEntity
import com.azizjon.network.data.InteractionEntity
import com.azizjon.network.data.NeedEntity
import com.azizjon.network.data.NetworkSnapshot
import com.azizjon.network.data.PersonEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

object EncryptedBackupCodec {
    private const val FORMAT = "network-app-encrypted-backup"
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val associatedData = "$FORMAT:$VERSION".toByteArray(Charsets.UTF_8)

    fun encode(
        snapshot: NetworkSnapshot,
        passphrase: String,
        random: SecureRandom = SecureRandom(),
    ): String {
        require(passphrase.length >= GitHubBackupConfig.MIN_PASSPHRASE_LENGTH) {
            "Backup passphrase must be at least ${GitHubBackupConfig.MIN_PASSPHRASE_LENGTH} characters"
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val plaintext = snapshotToJson(snapshot).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", ITERATIONS)
            .put("salt", Base64.getEncoder().encodeToString(salt))
            .put("iv", Base64.getEncoder().encodeToString(iv))
            .put("ciphertext", Base64.getEncoder().encodeToString(ciphertext))
            .toString(2)
    }

    fun decode(envelope: String, passphrase: String): NetworkSnapshot {
        try {
            val json = JSONObject(envelope)
            if (json.optString("format") != FORMAT || json.optInt("version") != VERSION) {
                throw BackupCodecException("Unsupported backup format or version")
            }
            val iterations = json.optInt("iterations")
            if (iterations !in 100_000..1_000_000) throw BackupCodecException("Invalid backup key settings")
            val salt = Base64.getDecoder().decode(json.getString("salt"))
            val iv = Base64.getDecoder().decode(json.getString("iv"))
            val ciphertext = Base64.getDecoder().decode(json.getString("ciphertext"))
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(associatedData)
            val plaintext = cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            return snapshotFromJson(plaintext)
        } catch (e: BackupCodecException) {
            throw e
        } catch (e: GeneralSecurityException) {
            throw BackupCodecException("Backup passphrase is incorrect or the backup is damaged", e)
        } catch (e: Exception) {
            throw BackupCodecException("Backup file is invalid or damaged", e)
        }
    }

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun NetworkSnapshot.toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 2)
        .put("exportedAt", System.currentTimeMillis())
        .put("people", JSONArray().apply { people.forEach { put(it.toJson()) } })
        .put("interactions", JSONArray().apply { interactions.forEach { put(it.toJson()) } })
        .put("needs", JSONArray().apply { needs.forEach { put(it.toJson()) } })
        .put("capabilities", JSONArray().apply { capabilities.forEach { put(it.toJson()) } })

    internal fun snapshotToJson(snapshot: NetworkSnapshot): String = snapshot.toJson().toString()

    private fun PersonEntity.toJson() = JSONObject()
        .put("id", id).put("name", name).put("organization", organization).put("role", role)
        .put("location", location).put("contact", contact).put("relationship", relationship)
        .put("tags", tags).put("notes", notes).put("isSelf", isSelf).put("archived", archived)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)

    private fun InteractionEntity.toJson() = JSONObject()
        .put("id", id).put("personId", personId).put("note", note)
        .put("occurredAt", occurredAt).put("createdAt", createdAt).put("origin", origin)

    private fun NeedEntity.toJson() = JSONObject()
        .put("id", id).put("personId", personId).put("text", text).put("status", status)
        .put("lastConfirmedAt", lastConfirmedAt).put("createdAt", createdAt)
        .put("sourceInteractionId", sourceInteractionId ?: JSONObject.NULL)

    private fun CapabilityEntity.toJson() = JSONObject()
        .put("id", id).put("personId", personId).put("text", text)
        .put("lastConfirmedAt", lastConfirmedAt).put("createdAt", createdAt)
        .put("active", active)
        .put("sourceInteractionId", sourceInteractionId ?: JSONObject.NULL)

    internal fun snapshotFromJson(value: String): NetworkSnapshot = snapshotFromJson(JSONObject(value)).also(::validate)

    private fun snapshotFromJson(json: JSONObject): NetworkSnapshot {
        val schemaVersion = json.optInt("schemaVersion")
        if (schemaVersion !in 1..2) throw BackupCodecException("Unsupported data schema")
        return NetworkSnapshot(
            people = json.getJSONArray("people").mapObjects { item ->
                PersonEntity(
                    id = item.getLong("id"),
                    name = item.getString("name"),
                    organization = item.optString("organization"),
                    role = item.optString("role"),
                    location = item.optString("location"),
                    contact = item.optString("contact"),
                    relationship = item.optString("relationship"),
                    tags = item.optString("tags"),
                    notes = item.optString("notes"),
                    isSelf = item.optBoolean("isSelf"),
                    archived = item.optBoolean("archived"),
                    createdAt = item.getLong("createdAt"),
                    updatedAt = item.getLong("updatedAt"),
                )
            },
            interactions = json.getJSONArray("interactions").mapObjects { item ->
                InteractionEntity(
                    id = item.getLong("id"), personId = item.getLong("personId"),
                    note = item.getString("note"), occurredAt = item.getLong("occurredAt"),
                    createdAt = item.getLong("createdAt"),
                    origin = if (schemaVersion >= 2) item.optString("origin", InteractionEntity.ORIGIN_MANUAL) else InteractionEntity.ORIGIN_MANUAL,
                )
            },
            needs = json.getJSONArray("needs").mapObjects { item ->
                NeedEntity(
                    id = item.getLong("id"), personId = item.getLong("personId"),
                    text = item.getString("text"), status = item.optString("status", "active"),
                    lastConfirmedAt = item.getLong("lastConfirmedAt"), createdAt = item.getLong("createdAt"),
                    sourceInteractionId = if (schemaVersion >= 2) item.optionalLong("sourceInteractionId") else null,
                )
            },
            capabilities = json.getJSONArray("capabilities").mapObjects { item ->
                CapabilityEntity(
                    id = item.getLong("id"), personId = item.getLong("personId"),
                    text = item.getString("text"), lastConfirmedAt = item.getLong("lastConfirmedAt"),
                    createdAt = item.getLong("createdAt"),
                    active = if (schemaVersion >= 2) item.optBoolean("active", true) else true,
                    sourceInteractionId = if (schemaVersion >= 2) item.optionalLong("sourceInteractionId") else null,
                )
            },
        )
    }

    private fun validate(snapshot: NetworkSnapshot) {
        val personIds = snapshot.people.map { it.id }.toSet()
        val interactionIds = snapshot.interactions.map { it.id }.toSet()
        if (personIds.size != snapshot.people.size || snapshot.people.any { it.id <= 0 || it.name.isBlank() }) {
            throw BackupCodecException("Backup contains invalid people")
        }
        if (interactionIds.size != snapshot.interactions.size ||
            snapshot.interactions.any {
                it.id <= 0 || it.personId !in personIds || it.note.isBlank() ||
                    it.origin !in setOf(InteractionEntity.ORIGIN_MANUAL, InteractionEntity.ORIGIN_AI_REVIEWED)
            } ||
            snapshot.needs.any {
                it.id <= 0 || it.personId !in personIds || it.text.isBlank() ||
                    it.status !in setOf(NeedEntity.STATUS_ACTIVE, NeedEntity.STATUS_CLOSED) ||
                    (it.sourceInteractionId != null && it.sourceInteractionId !in interactionIds)
            } ||
            snapshot.capabilities.any {
                it.id <= 0 || it.personId !in personIds || it.text.isBlank() ||
                    (it.sourceInteractionId != null && it.sourceInteractionId !in interactionIds)
            }
        ) {
            throw BackupCodecException("Backup contains invalid linked records")
        }
    }

    private fun JSONObject.optionalLong(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
        buildList {
            for (index in 0 until length()) add(transform(getJSONObject(index)))
        }
}
