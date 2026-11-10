package com.sleepysoong.logent.android

import android.content.ContentUris
import android.content.Context
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.integer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `android_contacts`의 구현이에요. 추가·열기는 사용자가 확인하는 편집 화면을 써요. */
internal suspend fun AndroidTools.contacts(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "search" -> searchContacts(context, arguments)
        "add" -> addContact(arguments)
        "open" -> startOrResult(contactsOpenPlan(), "연락처 앱을 열었어요.")
        null, "" -> toolError("action이 필요해요. search, add, open 중 하나를 쓰세요.")
        else -> toolError("지원하지 않는 action이에요: $action. search, add, open 중 하나를 쓰세요.")
    }
}

private suspend fun AndroidTools.searchContacts(context: Context, arguments: JsonObject): ToolResult {
    val name = arguments.text("name")?.trim()?.takeIf { it.isNotEmpty() }
    val phone = arguments.text("phone")?.trim()?.takeIf { it.isNotEmpty() }
    if (name == null && phone == null) {
        return toolError("name이나 phone 중 하나는 필요해요.")
    }
    val limit = (arguments.integer("limit") ?: DEFAULT_CONTACT_LIMIT).coerceIn(1, MAX_CONTACT_LIMIT)
    ensureContactsPermission()?.let { return it }
    val clauses = mutableListOf<String>()
    val selectionArgs = mutableListOf<String>()
    if (name != null) {
        clauses += "display_name LIKE ?"
        selectionArgs += "%$name%"
    }
    if (phone != null) {
        clauses += "data1 LIKE ?"
        selectionArgs += "%${normalizePhoneAddress(phone) ?: phone}%"
    }
    val projection = arrayOf("display_name", "data1", "contact_id")
    val cursor = context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection,
        clauses.joinToString(" OR "),
        selectionArgs.toTypedArray(),
        "display_name ASC",
    ) ?: return toolError("연락처 공급자에 질의하지 못했어요. 기기 정책을 확인해 주세요.")

    val seen = mutableSetOf<String>()
    val results = buildJsonArray {
        cursor.use { rows ->
            val nameIndex = rows.getColumnIndexOrThrow("display_name")
            val numberIndex = rows.getColumnIndexOrThrow("data1")
            val idIndex = rows.getColumnIndexOrThrow("contact_id")
            var count = 0
            while (count < limit && rows.moveToNext()) {
                val contactId = rows.getLong(idIndex)
                val number = rows.getString(numberIndex).orEmpty()
                val key = "$contactId|$number"
                if (!seen.add(key)) continue
                add(
                    buildJsonObject {
                        put("contactId", contactId)
                        put("name", rows.getString(nameIndex).orEmpty())
                        put("phone", number)
                    },
                )
                count++
            }
        }
    }
    return ToolResult(
        buildJsonObject {
            put("count", seen.size)
            put("contacts", results)
        }.toString(),
    )
}

private suspend fun AndroidTools.ensureContactsPermission(): ToolResult? = ensurePermission(
    AndroidPermissions.READ_CONTACTS,
    "설정 → 앱 → Logent → 권한에서 연락처 접근을 허용해 주세요.",
)

private fun AndroidTools.addContact(arguments: JsonObject): ToolResult {
    val name = arguments.text("name")?.trim()
        ?: return toolError("name이 필요해요.")
    if (name.isEmpty()) return toolError("name이 필요해요.")
    if (name.length > 200) return toolError("name은 200자 이하여야 해요.")
    val phoneInput = arguments.text("phone")?.trim()?.takeIf { it.isNotEmpty() }
    val phone = phoneInput?.let {
        normalizePhoneAddress(it)
            ?: return toolError("전화번호 형식이 올바르지 않아요. 숫자와 앞자리 +만 쓸 수 있어요.")
    }
    val email = arguments.text("email")?.trim()?.takeIf { it.isNotEmpty() }
    if (email != null && email.length > 320) return toolError("email은 320자 이하여야 해요.")
    return startOrResult(
        contactInsertPlan(name, phone, email),
        "연락처 편집 화면을 열었어요. 저장은 사용자가 확인해야 해요.",
    )
}

/** `android_calendar`의 구현이에요. 조회는 READ_CALENDAR가 필요하고 추가는 편집 화면을 열어요. */
internal suspend fun AndroidTools.calendar(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "list" -> listEvents(context, arguments)
        "add" -> addEvent(arguments)
        "open" -> startOrResult(calendarOpenPlan(System.currentTimeMillis()), "캘린더 앱을 열었어요.")
        null, "" -> toolError("action이 필요해요. list, add, open 중 하나를 쓰세요.")
        else -> toolError("지원하지 않는 action이에요: $action. list, add, open 중 하나를 쓰세요.")
    }
}

private suspend fun AndroidTools.listEvents(context: Context, arguments: JsonObject): ToolResult {
    val range = when (
        val result = resolveListRange(
            from = arguments.long("from"),
            to = arguments.long("to"),
            now = System.currentTimeMillis(),
        )
    ) {
        is RangeResult.Invalid -> return toolError(result.message)
        is RangeResult.Valid -> result.range
    }
    val limit = (arguments.integer("limit") ?: DEFAULT_EVENT_LIMIT).coerceIn(1, MAX_EVENT_LIMIT)
    ensureCalendarPermission()?.let { return it }
    val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
    ContentUris.appendId(builder, range.from)
    ContentUris.appendId(builder, range.to)
    val projection = arrayOf("title", "begin", "end", "allDay", "eventLocation", "calendar_displayName")
    val cursor = context.contentResolver.query(
        builder.build(),
        projection,
        null,
        null,
        "begin ASC",
    ) ?: return toolError("캘린더 공급자에 질의하지 못했어요. 기기 정책을 확인해 주세요.")

    val results = buildJsonArray {
        cursor.use { rows ->
            val titleIndex = rows.getColumnIndexOrThrow("title")
            val beginIndex = rows.getColumnIndexOrThrow("begin")
            val endIndex = rows.getColumnIndexOrThrow("end")
            val allDayIndex = rows.getColumnIndexOrThrow("allDay")
            val locationIndex = rows.getColumnIndexOrThrow("eventLocation")
            val calendarIndex = rows.getColumnIndexOrThrow("calendar_displayName")
            var count = 0
            while (count < limit && rows.moveToNext()) {
                val begin = rows.getLong(beginIndex)
                val end = rows.getLong(endIndex)
                add(
                    buildJsonObject {
                        put("title", rows.getString(titleIndex).orEmpty())
                        put("start", begin)
                        put("end", end)
                        put("startText", formatTimestamp(begin))
                        put("endText", formatTimestamp(end))
                        put("allDay", rows.getInt(allDayIndex) != 0)
                        put("location", rows.getString(locationIndex).orEmpty())
                        put("calendar", rows.getString(calendarIndex).orEmpty())
                    },
                )
                count++
            }
        }
    }
    return ToolResult(
        buildJsonObject {
            put("from", range.from)
            put("to", range.to)
            put("events", results)
        }.toString(),
    )
}

private suspend fun AndroidTools.ensureCalendarPermission(): ToolResult? = ensurePermission(
    AndroidPermissions.READ_CALENDAR,
    "설정 → 앱 → Logent → 권한에서 캘린더 접근을 허용해 주세요.",
)

private fun AndroidTools.addEvent(arguments: JsonObject): ToolResult {
    val title = arguments.text("title")?.trim()
        ?: return toolError("title이 필요해요.")
    if (title.isEmpty()) return toolError("title이 필요해요.")
    if (title.length > 200) return toolError("title은 200자 이하여야 해요.")
    val description = arguments.text("description")?.trim()?.takeIf { it.isNotEmpty() }
    if (description != null && description.length > 2000) {
        return toolError("description은 2000자 이하여야 해요.")
    }
    val range = when (
        val result = resolveEventRange(
            start = arguments.long("start"),
            end = arguments.long("end"),
            now = System.currentTimeMillis(),
        )
    ) {
        is RangeResult.Invalid -> return toolError(result.message)
        is RangeResult.Valid -> result.range
    }
    return startOrResult(
        calendarInsertPlan(title, range.from, range.to, description),
        "캘린더 편집 화면을 열었어요. 저장은 사용자가 확인해야 해요.",
    )
}

/** `android_sms`의 구현이에요. 목록·전송은 권한이 필요하고 작성은 권한 없이 열어요. */
internal suspend fun AndroidTools.sms(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "list" -> listSms(context, arguments)
        "send" -> sendSms(context, arguments)
        "compose" -> composeSms(arguments)
        "open" -> startOrResult(messagingAppPlan(), "기본 문자 앱을 열었어요.")
        null, "" -> toolError("action이 필요해요. list, send, compose, open 중 하나를 쓰세요.")
        else -> toolError("지원하지 않는 action이에요: $action. list, send, compose, open 중 하나를 쓰세요.")
    }
}

private suspend fun AndroidTools.listSms(context: Context, arguments: JsonObject): ToolResult {
    val thread = arguments.long("thread")
    val address = arguments.text("address")?.trim()?.takeIf { it.isNotEmpty() }
    val limit = (arguments.integer("limit") ?: DEFAULT_SMS_LIMIT).coerceIn(1, SMS_LIST_MAX)
    val clauses = mutableListOf<String>()
    val selectionArgs = mutableListOf<String>()
    if (thread != null) {
        if (thread < 0) return toolError("thread는 0 이상의 숫자여야 해요.")
        clauses += "thread_id = ?"
        selectionArgs += thread.toString()
    }
    if (address != null) {
        val normalized = normalizePhoneAddress(address) ?: address
        clauses += "address LIKE ?"
        selectionArgs += "%$normalized%"
    }
    ensurePermission(
        AndroidPermissions.READ_SMS,
        "설정 → 앱 → Logent → 권한에서 문자 접근을 허용해 주세요. " +
            "Logent가 기본 문자 앱이 아니면 Android가 문자 읽기를 제한할 수 있어요.",
    )?.let { return it }
    val cursor = context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf("address", "date", "body", "type"),
        if (clauses.isEmpty()) null else clauses.joinToString(" AND "),
        if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
        "date DESC",
    ) ?: return toolError("문자 공급자에 질의하지 못했어요. 기기 정책을 확인해 주세요.")

    val results = buildJsonArray {
        cursor.use { rows ->
            val addressIndex = rows.getColumnIndexOrThrow("address")
            val dateIndex = rows.getColumnIndexOrThrow("date")
            val bodyIndex = rows.getColumnIndexOrThrow("body")
            val typeIndex = rows.getColumnIndexOrThrow("type")
            var count = 0
            while (count < limit && rows.moveToNext()) {
                val date = rows.getLong(dateIndex)
                val body = rows.getString(bodyIndex).orEmpty()
                add(
                    buildJsonObject {
                        put("address", rows.getString(addressIndex).orEmpty())
                        put("date", date)
                        put("dateText", formatTimestamp(date))
                        put("direction", smsDirection(rows.getInt(typeIndex)))
                        put("body", truncateBody(body))
                    },
                )
                count++
            }
        }
    }
    return ToolResult(
        buildJsonObject {
            put("count", results.size)
            put("messages", results)
        }.toString(),
    )
}

private fun smsDirection(type: Int): String = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> "received"
    Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
    Telephony.Sms.MESSAGE_TYPE_FAILED -> "failed"
    Telephony.Sms.MESSAGE_TYPE_QUEUED -> "queued"
    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
    else -> "unknown"
}

private suspend fun AndroidTools.sendSms(context: Context, arguments: JsonObject): ToolResult {
    val request = validateSms(arguments.text("to"), arguments.text("body"))
    if (request is SmsRequest.Invalid) return toolError(request.message)
    val valid = request as SmsRequest.Valid
    ensurePermission(
        AndroidPermissions.SEND_SMS,
        "설정 → 앱 → Logent → 권한에서 문자 보내기를 허용해 주세요. " +
            "Logent가 기본 문자 앱이 아니면 Android가 문자 전송을 제한할 수 있어요.",
    )?.let { return it }
    val manager = context.getSystemService(SmsManager::class.java)
        ?: @Suppress("DEPRECATION") SmsManager.getDefault()
    val parts = manager.divideMessage(valid.body)
    if (parts.size <= 1) {
        manager.sendTextMessage(valid.address, null, valid.body, null, null)
    } else {
        manager.sendMultipartTextMessage(valid.address, null, parts, null, null)
    }
    return ToolResult(
        "문자를 전송했어요. 받는 번호: ${valid.address}, ${valid.body.length}자, " +
            "${parts.size}개 메시지로 나눠 보냈어요. 이 동작은 되돌릴 수 없어요.",
    )
}

private fun AndroidTools.composeSms(arguments: JsonObject): ToolResult {
    val address = arguments.text("to")?.trim()?.takeIf { it.isNotEmpty() }
    val body = arguments.text("body")
    return startOrResult(
        smsComposePlan(address, body),
        "문자 작성 화면을 열었어요. 전송은 사용자가 직접 확인해야 해요.",
    )
}

private const val DEFAULT_CONTACT_LIMIT = 20
private const val MAX_CONTACT_LIMIT = 50
private const val DEFAULT_EVENT_LIMIT = 20
private const val MAX_EVENT_LIMIT = 100
private const val DEFAULT_SMS_LIMIT = 20
