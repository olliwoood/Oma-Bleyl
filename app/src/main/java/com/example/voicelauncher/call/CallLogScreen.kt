package com.example.voicelauncher.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Data Classes ---

data class CallLogEntry(
    val name: String?,
    val number: String,
    val type: Int, // CallLog.Calls.INCOMING_TYPE etc.
    val date: Long,
    val duration: Long
)

data class FrequentContact(
    val name: String,
    val number: String
)

// --- Main Screen ---

@Composable
fun CallLogScreen() {
    val context = LocalContext.current
    var callLogEntries by remember { mutableStateOf<List<CallLogEntry>>(emptyList()) }
    var frequentContacts by remember { mutableStateOf<List<FrequentContact>>(emptyList()) }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        )
    }

    // Load data when permission is available
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            callLogEntries = readCallLog(context)
            frequentContacts = getFrequentContacts(context)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFFFAFAFA)
    ) {
        if (!hasPermission) {
            // No permission - show message
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Bitte Berechtigung fuer Anrufliste erteilen",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1E293B),
                    modifier = Modifier.padding(32.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                // --- Favorite/Frequent Contacts ---
                if (frequentContacts.isNotEmpty()) {
                    item {
                        Text(
                            text = "Kontakte",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF64748B),
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)
                        )
                    }
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(frequentContacts) { contact ->
                                ContactChip(contact = contact, context = context)
                            }
                        }
                    }
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }

                // --- Call Log Header ---
                item {
                    Text(
                        text = "Letzte Anrufe",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B),
                        modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 8.dp)
                    )
                }

                // --- Call Log Entries ---
                if (callLogEntries.isEmpty()) {
                    item {
                        Text(
                            text = "Keine Anrufe vorhanden",
                            fontSize = 24.sp,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp)
                        )
                    }
                } else {
                    items(callLogEntries) { entry ->
                        CallLogItem(entry = entry, context = context)
                        HorizontalDivider(
                            color = Color(0xFFE2E8F0),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            }
        }
    }
}

// --- Contact Chip (large, tappable) ---

@Composable
private fun ContactChip(contact: FrequentContact, context: Context) {
    Surface(
        modifier = Modifier
            .semantics {
                contentDescription = "${contact.name} anrufen"
            }
            .clickable { makeCall(context, contact.number) },
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFFE8F5E9),
        shadowElevation = 2.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .width(120.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF4CAF50), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = contact.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 24.sp
            )
        }
    }
}

// --- Single Call Log Entry (large, tappable) ---

@Composable
private fun CallLogItem(entry: CallLogEntry, context: Context) {
    val isMissed = entry.type == CallLog.Calls.MISSED_TYPE
    val displayName = entry.name ?: entry.number
    val typeDescription = when (entry.type) {
        CallLog.Calls.INCOMING_TYPE -> "Eingehend"
        CallLog.Calls.OUTGOING_TYPE -> "Ausgehend"
        CallLog.Calls.MISSED_TYPE -> "Verpasst"
        CallLog.Calls.REJECTED_TYPE -> "Abgelehnt"
        else -> ""
    }
    val dateStr = formatCallDate(entry.date)
    val durationStr = if (entry.duration > 0) formatDuration(entry.duration) else ""
    val semanticText = "$displayName, $typeDescription, $dateStr" +
            if (durationStr.isNotEmpty()) ", Dauer $durationStr" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$semanticText. Tippen zum Anrufen." }
            .clickable { makeCall(context, entry.number) }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Call type icon
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = if (isMissed) Color(0xFFFFEBEE) else Color(0xFFF1F5F9),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (entry.type) {
                    CallLog.Calls.INCOMING_TYPE -> Icons.Default.CallReceived
                    CallLog.Calls.OUTGOING_TYPE -> Icons.Default.CallMade
                    CallLog.Calls.MISSED_TYPE -> Icons.Default.CallMissed
                    else -> Icons.Default.Call
                },
                contentDescription = typeDescription,
                tint = if (isMissed) Color(0xFFD32F2F) else Color(0xFF4CAF50),
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        // Name + details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (isMissed) Color(0xFFD32F2F) else Color(0xFF1E293B),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row {
                Text(
                    text = dateStr,
                    fontSize = 18.sp,
                    color = Color(0xFF64748B)
                )
                if (durationStr.isNotEmpty()) {
                    Text(
                        text = "  ·  $durationStr",
                        fontSize = 18.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }

        // Call button
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(Color(0xFF4CAF50), CircleShape)
                .clickable { makeCall(context, entry.number) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Call,
                contentDescription = "Anrufen",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

// --- Helper Functions ---

private fun makeCall(context: Context, number: String) {
    val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
        context.startActivity(intent)
    }
}

private fun readCallLog(context: Context, limit: Int = 50): List<CallLogEntry> {
    val entries = mutableListOf<CallLogEntry>()
    try {
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        ) ?: return entries

        cursor.use {
            var count = 0
            while (it.moveToNext() && count < limit) {
                entries.add(
                    CallLogEntry(
                        name = it.getString(0),
                        number = it.getString(1) ?: "",
                        type = it.getInt(2),
                        date = it.getLong(3),
                        duration = it.getLong(4)
                    )
                )
                count++
            }
        }
    } catch (e: SecurityException) {
        // Permission not granted
    }
    return entries
}

private fun getFrequentContacts(context: Context): List<FrequentContact> {
    val contacts = mutableListOf<FrequentContact>()
    try {
        // Get starred/favorite contacts
        val cursor = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.Contacts.STARRED
            ),
            "${ContactsContract.Contacts.STARRED} = 1",
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        )

        cursor?.use {
            val seen = mutableSetOf<String>()
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val number = it.getString(1) ?: continue
                if (seen.add(name)) {
                    contacts.add(FrequentContact(name = name, number = number))
                }
            }
        }

        // If no starred contacts, use most frequently called from call log
        if (contacts.isEmpty()) {
            val callCounts = mutableMapOf<String, Pair<String, Int>>() // number -> (name, count)
            val logCursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER),
                "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}",
                null,
                "${CallLog.Calls.DATE} DESC"
            )
            logCursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)
                    val number = it.getString(1) ?: continue
                    if (name != null && number.isNotBlank()) {
                        val existing = callCounts[number]
                        callCounts[number] = Pair(name, (existing?.second ?: 0) + 1)
                    }
                }
            }
            callCounts.entries
                .sortedByDescending { it.value.second }
                .take(8)
                .forEach { (number, pair) ->
                    contacts.add(FrequentContact(name = pair.first, number = number))
                }
        }
    } catch (e: SecurityException) {
        // Permission not granted
    }
    return contacts
}

private fun formatCallDate(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val oneDay = 24 * 60 * 60 * 1000L

    return when {
        diff < oneDay -> {
            // Today - show time
            SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(timestamp))
        }
        diff < 2 * oneDay -> {
            // Yesterday
            "Gestern " + SimpleDateFormat("HH:mm", Locale.GERMANY).format(Date(timestamp))
        }
        diff < 7 * oneDay -> {
            // This week - show day name
            SimpleDateFormat("EEEE HH:mm", Locale.GERMANY).format(Date(timestamp))
        }
        else -> {
            // Older - show date
            SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY).format(Date(timestamp))
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins} Min" else "${secs} Sek"
}
