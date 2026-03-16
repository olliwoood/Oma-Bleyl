package com.example.voicelauncher.data

import android.content.Context
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.util.Log
import com.example.voicelauncher.data.MessageNotification

class SmsReader(private val context: Context) {

    /**
     * Holt die neuesten empfangenen SMS aus dem Posteingang (zur Übersicht).
     */
    fun getLatestSms(limit: Int = 5): List<MessageNotification> {
        val messages = mutableListOf<MessageNotification>()
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"

        try {
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

                while (cursor.moveToNext()) {
                    val address = cursor.getString(addressIndex) ?: "Unbekannt"
                    val body = cursor.getString(bodyIndex) ?: ""
                    val date = cursor.getLong(dateIndex)
                    messages.add(MessageNotification(address, body, date))
                }
            }
        } catch (e: SecurityException) {
            Log.e("SmsReader", "Keine READ_SMS Berechtigung", e)
        } catch (e: Exception) {
            Log.e("SmsReader", "Fehler beim Lesen der SMS", e)
        }

        return messages
    }

    /**
     * Holt den jüngsten SMS-Verlauf (ein- und ausgehend) mit einer bestimmten Telefonnummer.
     */
    fun getSmsHistoryWithPhone(phoneNumber: String, limit: Int = 5): List<MessageNotification> {
        val messages = mutableListOf<MessageNotification>()
        // Wir fragen die generelle SMS-URI ab, um Inbox und Sent zu bekommen
        val uri = Telephony.Sms.CONTENT_URI
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        try {
            // Hole mehr als nötig, da der Filter für die Nummer im Code meist robuster ist
            // als ein exaktes Match im SQL (wegen Ländercodes etc.)
            context.contentResolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)

                while (cursor.moveToNext() && messages.size < limit) {
                    val address = cursor.getString(addressIndex) ?: continue
                    
                    // Vergleiche Nummern tolerant (ignoriert Leerzeichen, +49 vs 0 etc.)
                    if (PhoneNumberUtils.compare(address, phoneNumber)) {
                        val body = cursor.getString(bodyIndex) ?: ""
                        val date = cursor.getLong(dateIndex)
                        val type = cursor.getInt(typeIndex)
                        
                        val senderName = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) {
                            "Ich (Omi)"
                        } else {
                            address // Wird später in der UI durch den Kontaktnamen ersetzt
                        }

                        messages.add(MessageNotification(senderName, body, date))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("SmsReader", "Keine READ_SMS Berechtigung", e)
        } catch (e: Exception) {
            Log.e("SmsReader", "Fehler beim Lesen des SMS-Verlaufs", e)
        }

        // Wir haben absteigend sortiert (neueste zuerst), für den Verlauf drehen wir es besser um
        return messages.reversed()
    }
}
