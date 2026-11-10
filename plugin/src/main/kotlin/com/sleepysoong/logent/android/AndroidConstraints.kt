package com.sleepysoong.logent.android

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** epoch 밀리초는 Int 범위를 넘으므로 별도로 읽어요. */
internal fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

/** 플러그인이 코어 매니페스트에 기대는 런타임 권한 이름이에요. */
internal object AndroidPermissions {
    const val READ_CONTACTS = "android.permission.READ_CONTACTS"
    const val READ_CALENDAR = "android.permission.READ_CALENDAR"
    const val READ_SMS = "android.permission.READ_SMS"
    const val SEND_SMS = "android.permission.SEND_SMS"
    const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
    const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
    const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
    const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
    const val ACCESS_FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    const val ACCESS_COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
    const val WRITE_SETTINGS = "android.permission.WRITE_SETTINGS"
    const val SET_WALLPAPER = "android.permission.SET_WALLPAPER"
    const val VIBRATE = "android.permission.VIBRATE"
    const val FLASHLIGHT = "android.permission.FLASHLIGHT"
}

/** 사용자에게 보여 줄 권한 이름이에요. */
internal fun permissionLabel(permission: String): String = when (permission) {
    AndroidPermissions.READ_CONTACTS -> "연락처 읽기"
    AndroidPermissions.READ_CALENDAR -> "캘린더 읽기"
    AndroidPermissions.READ_SMS -> "문자 읽기"
    AndroidPermissions.SEND_SMS -> "문자 보내기"
    AndroidPermissions.READ_EXTERNAL_STORAGE -> "저장소 읽기"
    AndroidPermissions.READ_MEDIA_IMAGES -> "사진 읽기"
    AndroidPermissions.READ_MEDIA_VIDEO -> "동영상 읽기"
    AndroidPermissions.READ_MEDIA_AUDIO -> "오디오 읽기"
    AndroidPermissions.ACCESS_FINE_LOCATION -> "정확한 위치"
    AndroidPermissions.ACCESS_COARSE_LOCATION -> "대략적인 위치"
    AndroidPermissions.WRITE_SETTINGS -> "시스템 설정 변경"
    AndroidPermissions.SET_WALLPAPER -> "배경화면 설정"
    AndroidPermissions.VIBRATE -> "진동"
    else -> permission
}

internal enum class MediaKind { IMAGES, VIDEOS, AUDIO }

/**
 * Android 13(API 33)부터 MediaStore 읽기 권한이 유형별로 나뉘어요.
 * 12 이하에서는 예전 저장소 읽기 권한을 써요.
 */
internal fun mediaReadPermissions(sdkInt: Int, kind: MediaKind): List<String> = when {
    sdkInt >= 33 -> listOf(
        when (kind) {
            MediaKind.IMAGES -> AndroidPermissions.READ_MEDIA_IMAGES
            MediaKind.VIDEOS -> AndroidPermissions.READ_MEDIA_VIDEO
            MediaKind.AUDIO -> AndroidPermissions.READ_MEDIA_AUDIO
        },
    )
    else -> listOf(AndroidPermissions.READ_EXTERNAL_STORAGE)
}

internal const val MAX_SMS_BODY_LENGTH = 1000
internal const val SMS_LIST_BODY_LIMIT = 300
internal const val SMS_LIST_MAX = 50
internal const val MAX_RANGE_MS = 366L * 24 * 60 * 60 * 1000

internal sealed interface SmsRequest {
    data class Valid(val address: String, val body: String) : SmsRequest
    data class Invalid(val message: String) : SmsRequest
}

/**
 * 전화번호를 숫자와 앞자리 `+`만 남겨 정리해요. 국가 코드·구분 기호·공백은 허용하되
 * 문자로 보낼 수 없는 형식은 null을 돌려줘요.
 */
internal fun normalizePhoneAddress(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val hasPlus = trimmed.startsWith("+")
    val digits = trimmed.drop(if (hasPlus) 1 else 0)
        .filterNot { it.isWhitespace() || it == '-' || it == '(' || it == ')' || it == '.' }
    if (digits.isEmpty() || digits.length > 20) return null
    if (digits.any { !it.isDigit() }) return null
    if (digits.length < 3) return null
    return (if (hasPlus) "+" else "") + digits
}

/** 문자 전송 전에 번호·본문을 검증해요. 빈 전송이나 잘못된 번호를 그대로 보내지 않아요. */
internal fun validateSms(to: String?, body: String?): SmsRequest {
    if (to.isNullOrBlank()) return SmsRequest.Invalid("받는 번호가 필요해요.")
    val address = normalizePhoneAddress(to)
        ?: return SmsRequest.Invalid("전화번호 형식이 올바르지 않아요. 숫자와 앞자리 +만 쓸 수 있어요.")
    if (body.isNullOrBlank()) return SmsRequest.Invalid("문자 본문이 필요해요.")
    if (body.length > MAX_SMS_BODY_LENGTH) {
        return SmsRequest.Invalid("문자 본문은 ${MAX_SMS_BODY_LENGTH}자 이하여야 해요.")
    }
    return SmsRequest.Valid(address, body)
}

/**
 * `#RGB`, `#RRGGBB`, `#AARRGGBB` 색상을 ARGB 정수로 바꿔요.
 * 형식이 다르면 null을 돌려주고 임의의 색으로 대체하지 않아요.
 */
internal fun parseColorHex(raw: String?): Int? {
    val text = raw?.trim() ?: return null
    if (!text.startsWith("#")) return null
    val hex = text.substring(1)
    if (hex.isEmpty() || hex.any { !it.isHexDigit() }) return null
    return when (hex.length) {
        3 -> {
            val red = hex[0].digitToInt(16) * 17
            val green = hex[1].digitToInt(16) * 17
            val blue = hex[2].digitToInt(16) * 17
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
        6 -> (0xFF000000L or hex.toLong(16)).toInt()
        8 -> hex.toLong(16).toInt()
        else -> null
    }
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

internal data class EpochRange(val from: Long, val to: Long)

internal sealed interface RangeResult {
    data class Valid(val range: EpochRange) : RangeResult
    data class Invalid(val message: String) : RangeResult
}

/** 캘린더 목록 조회 범위를 검증해요. 기본값은 지금부터 7일이에요. */
internal fun resolveListRange(
    from: Long?,
    to: Long?,
    now: Long,
    defaultSpanMs: Long = 7L * 24 * 60 * 60 * 1000,
): RangeResult {
    if (from != null && from < 0) return RangeResult.Invalid("from은 0 이상의 epoch 밀리초여야 해요.")
    if (to != null && to < 0) return RangeResult.Invalid("to는 0 이상의 epoch 밀리초여야 해요.")
    val start = from ?: now
    val end = to ?: (start + defaultSpanMs)
    if (end <= start) return RangeResult.Invalid("to는 from보다 커야 해요.")
    val span = end - start
    if (span <= 0 || span > MAX_RANGE_MS) {
        return RangeResult.Invalid("조회 기간은 366일 이하여야 해요.")
    }
    return RangeResult.Valid(EpochRange(start, end))
}

/** 일정 추가 범위를 검증해요. 기본값은 지금부터 1시간이에요. */
internal fun resolveEventRange(
    start: Long?,
    end: Long?,
    now: Long,
    defaultDurationMs: Long = 60L * 60 * 1000,
): RangeResult {
    if (start != null && start < 0) return RangeResult.Invalid("start는 0 이상의 epoch 밀리초여야 해요.")
    if (end != null && end < 0) return RangeResult.Invalid("end는 0 이상의 epoch 밀리초여야 해요.")
    val begin = start ?: now
    val finish = end ?: (begin + defaultDurationMs)
    if (finish <= begin) return RangeResult.Invalid("end는 start보다 뒤여야 해요.")
    val span = finish - begin
    if (span <= 0 || span > MAX_RANGE_MS) {
        return RangeResult.Invalid("일정 기간은 366일 이하여야 해요.")
    }
    return RangeResult.Valid(EpochRange(begin, finish))
}

/** 문자 목록 본문을 정해진 길이에서 줄여요. */
internal fun truncateBody(text: String, max: Int = SMS_LIST_BODY_LIMIT): String =
    if (text.length <= max) text else text.take(max).trimEnd() + "…"

internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "$bytes B"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB", "PiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0분"
    var seconds = ms / 1000
    val days = seconds / 86_400
    seconds %= 86_400
    val hours = seconds / 3_600
    seconds %= 3_600
    val minutes = seconds / 60
    val parts = buildList {
        if (days > 0) add("${days}일")
        if (hours > 0) add("${hours}시간")
        if (minutes > 0) add("${minutes}분")
    }
    return if (parts.isEmpty()) "1분 미만" else parts.joinToString(" ")
}

/** Sensor.TYPE_* 값을 읽기 쉬운 이름으로 바꿔요. 모르는 값은 숫자를 그대로 남겨요. */
internal fun sensorTypeLabel(type: Int): String = when (type) {
    1 -> "accelerometer"
    2 -> "magnetic_field"
    3 -> "orientation"
    4 -> "gyroscope"
    5 -> "light"
    6 -> "pressure"
    7 -> "temperature"
    8 -> "proximity"
    9 -> "gravity"
    10 -> "linear_acceleration"
    11 -> "rotation_vector"
    12 -> "relative_humidity"
    13 -> "ambient_temperature"
    14 -> "magnetic_field_uncalibrated"
    15 -> "game_rotation_vector"
    16 -> "gyroscope_uncalibrated"
    17 -> "significant_motion"
    18 -> "step_detector"
    19 -> "step_counter"
    20 -> "geomagnetic_rotation_vector"
    21 -> "heart_rate"
    22 -> "pose_6dof"
    23 -> "stationary_detect"
    24 -> "motion_detect"
    25 -> "heart_beat"
    26 -> "low_latency_offbody_detect"
    27 -> "accelerated_heart_rate"
    28 -> "wrist_tilt"
    29 -> "head_tracker"
    30 -> "hinge_angle"
    31 -> "head_tracking"
    32 -> "acceleration"
    33 -> "angular_velocity"
    34 -> "device_orientation"
    else -> "type_$type"
}

private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

internal fun isValidPackageName(value: String?): Boolean =
    value != null && value.length <= 255 && PACKAGE_NAME_PATTERN.matches(value)

internal fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name())

internal fun playStoreDetailsUrl(packageName: String): String =
    "https://play.google.com/store/apps/details?id=" + urlEncode(packageName)

internal fun playStoreSearchUrl(query: String): String =
    "https://play.google.com/store/search?q=" + urlEncode(query) + "&c=apps"
