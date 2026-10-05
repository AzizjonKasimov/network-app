package com.azizjon.network.checkin

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** One contact saved in the phone's address book, as the check-in needs it: who, nothing else. */
data class PhoneContact(val lookupKey: String, val name: String)

/**
 * Reads the names in the phone's address book.
 *
 * Only the display name and Android's lookup key are read: no numbers, emails,
 * or anything else. The list is compared with the network on the phone and
 * never sent anywhere.
 */
class ContactsScanner(private val context: Context) {
    val permitted: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun read(): List<PhoneContact> {
        if (!permitted) return emptyList()
        val projection = arrayOf(ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
        val contacts = mutableListOf<PhoneContact>()
        context.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val keyColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.LOOKUP_KEY)
            val nameColumn = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            while (cursor.moveToNext()) {
                val key = cursor.getString(keyColumn)?.takeIf(String::isNotBlank) ?: continue
                val name = cursor.getString(nameColumn)?.trim().orEmpty()
                // A contact saved as just a number is not something to ask about by name.
                if (NameKey.of(name).none(Char::isLetter)) continue
                contacts += PhoneContact(key, name)
            }
        }
        return contacts
    }
}
