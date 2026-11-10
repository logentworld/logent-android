package com.sleepysoong.logent.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidConstraintsTest {
    @Test
    fun `Android 13부터 미디어 권한을 유형별로 고른다`() {
        assertEquals(
            listOf(AndroidPermissions.READ_MEDIA_IMAGES),
            mediaReadPermissions(33, MediaKind.IMAGES),
        )
        assertEquals(
            listOf(AndroidPermissions.READ_MEDIA_VIDEO),
            mediaReadPermissions(34, MediaKind.VIDEOS),
        )
        assertEquals(
            listOf(AndroidPermissions.READ_MEDIA_AUDIO),
            mediaReadPermissions(33, MediaKind.AUDIO),
        )
    }

    @Test
    fun `Android 12 이하는 저장소 읽기 권한을 쓴다`() {
        assertEquals(
            listOf(AndroidPermissions.READ_EXTERNAL_STORAGE),
            mediaReadPermissions(32, MediaKind.IMAGES),
        )
        assertEquals(
            listOf(AndroidPermissions.READ_EXTERNAL_STORAGE),
            mediaReadPermissions(26, MediaKind.AUDIO),
        )
    }

    @Test
    fun `전화번호에서 구분 기호를 지우고 앞자리 +를 보존한다`() {
        assertEquals("+821012345678", normalizePhoneAddress("+82 10-1234-5678"))
        assertEquals("01012345678", normalizePhoneAddress("010.1234.5678"))
        assertEquals("0212345678", normalizePhoneAddress("(02) 1234-5678"))
    }

    @Test
    fun `문자로 보낼 수 없는 번호는 거부한다`() {
        assertNull(normalizePhoneAddress(null))
        assertNull(normalizePhoneAddress(""))
        assertNull(normalizePhoneAddress("+"))
        assertNull(normalizePhoneAddress("12"))
        assertNull(normalizePhoneAddress("010-ABCD-1234"))
        assertNull(normalizePhoneAddress("123456789012345678901"))
    }

    @Test
    fun `문자 검증은 정리된 번호와 본문을 돌려준다`() {
        val request = validateSms("+82 10-1234-5678", "안녕하세요")
        assertTrue(request is SmsRequest.Valid)
        request as SmsRequest.Valid
        assertEquals("+821012345678", request.address)
        assertEquals("안녕하세요", request.body)
    }

    @Test
    fun `문자 검증은 빈 번호와 잘못된 번호를 거부한다`() {
        assertTrue(validateSms("  ", "본문") is SmsRequest.Invalid)
        assertTrue(validateSms("010-ABCD", "본문") is SmsRequest.Invalid)
        assertTrue(validateSms("12", "본문") is SmsRequest.Invalid)
    }

    @Test
    fun `문자 검증은 빈 본문과 너무 긴 본문을 거부한다`() {
        assertTrue(validateSms("01012345678", " ") is SmsRequest.Invalid)
        assertTrue(validateSms("01012345678", "가".repeat(MAX_SMS_BODY_LENGTH + 1)) is SmsRequest.Invalid)
        assertTrue(validateSms("01012345678", "가".repeat(MAX_SMS_BODY_LENGTH)) is SmsRequest.Valid)
    }

    @Test
    fun `색상 파싱은 ARGB 정수를 만든다`() {
        val red: Int? = 0xFFFF0000.toInt()
        val green: Int? = 0xFF00FF00.toInt()
        val translucentWhite: Int? = 0x80FFFFFF.toInt()
        val black: Int? = 0xFF000000.toInt()
        assertEquals(red, parseColorHex("#FF0000"))
        assertEquals(red, parseColorHex("#f00"))
        assertEquals(green, parseColorHex(" #0F0 "))
        assertEquals(translucentWhite, parseColorHex("#80FFFFFF"))
        assertEquals(black, parseColorHex("#000000"))
    }

    @Test
    fun `색상 파싱은 잘못된 형식을 거부한다`() {
        assertNull(parseColorHex(null))
        assertNull(parseColorHex(""))
        assertNull(parseColorHex("FF0000"))
        assertNull(parseColorHex("#12345"))
        assertNull(parseColorHex("#GGGGGG"))
        assertNull(parseColorHex("#"))
    }

    @Test
    fun `목록 범위는 기본 7일을 쓰고 역전된 범위를 거부한다`() {
        val result = resolveListRange(null, null, now = 1_000L)
        assertTrue(result is RangeResult.Valid)
        result as RangeResult.Valid
        assertEquals(1_000L, result.range.from)
        assertEquals(1_000L + 7L * 24 * 60 * 60 * 1000, result.range.to)

        assertTrue(resolveListRange(2_000L, 1_000L, now = 0L) is RangeResult.Invalid)
        assertTrue(resolveListRange(-1L, 1_000L, now = 0L) is RangeResult.Invalid)
        assertTrue(resolveListRange(0L, -5L, now = 0L) is RangeResult.Invalid)
    }

    @Test
    fun `목록 범위는 366일을 넘는 조회를 거부한다`() {
        val from = 0L
        val to = MAX_RANGE_MS + 1
        assertTrue(resolveListRange(from, to, now = 0L) is RangeResult.Invalid)
        assertTrue(resolveListRange(from, from + MAX_RANGE_MS, now = 0L) is RangeResult.Valid)
    }

    @Test
    fun `일정 범위는 기본 1시간을 쓰고 종료가 시작보다 뒤여야 한다`() {
        val result = resolveEventRange(null, null, now = 5_000L)
        assertTrue(result is RangeResult.Valid)
        result as RangeResult.Valid
        assertEquals(5_000L, result.range.from)
        assertEquals(5_000L + 60L * 60 * 1000, result.range.to)

        assertTrue(resolveEventRange(2_000L, 1_000L, now = 0L) is RangeResult.Invalid)
        assertTrue(resolveEventRange(1_000L, 1_000L, now = 0L) is RangeResult.Invalid)
        assertTrue(resolveEventRange(1_000L, 2_000L, now = 0L) is RangeResult.Valid)
    }

    @Test
    fun `문자 본문은 300자에서 자르고 말줄임표를 붙인다`() {
        val short = "가".repeat(SMS_LIST_BODY_LIMIT)
        assertEquals(short, truncateBody(short))
        val long = "나".repeat(SMS_LIST_BODY_LIMIT + 10)
        val cut = truncateBody(long)
        assertEquals(SMS_LIST_BODY_LIMIT + 1, cut.length)
        assertTrue(cut.endsWith("…"))
    }

    @Test
    fun `바이트와 기간을 읽기 쉬운 문자열로 만든다`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("1.0 MiB", formatBytes(1024L * 1024))
        assertEquals("2.5 GiB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))

        assertEquals("0분", formatDuration(0))
        assertEquals("1분", formatDuration(60_000))
        assertEquals("1시간", formatDuration(3_600_000))
        assertEquals("1일 1시간 1분", formatDuration(90_061_000))
    }

    @Test
    fun `패키지 이름 형식을 검사한다`() {
        assertTrue(isValidPackageName("com.example.app"))
        assertTrue(isValidPackageName("a.b"))
        assertFalse(isValidPackageName(null))
        assertFalse(isValidPackageName(""))
        assertFalse(isValidPackageName("com"))
        assertFalse(isValidPackageName("com..app"))
        assertFalse(isValidPackageName("com.example."))
        assertFalse(isValidPackageName("-bad.pkg"))
    }

    @Test
    fun `스토어 URL은 검색어와 패키지를 인코딩한다`() {
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.example.app",
            playStoreDetailsUrl("com.example.app"),
        )
        assertEquals(
            "https://play.google.com/store/search?q=a+b%26c&c=apps",
            playStoreSearchUrl("a b&c"),
        )
    }

    @Test
    fun `센서 타입 이름과 알 수 없는 값`() {
        assertEquals("accelerometer", sensorTypeLabel(1))
        assertEquals("proximity", sensorTypeLabel(8))
        assertEquals("type_999", sensorTypeLabel(999))
    }

    @Test
    fun `권한 이름은 해요체 라벨을 가진다`() {
        assertEquals("문자 읽기", permissionLabel(AndroidPermissions.READ_SMS))
        assertEquals("사진 읽기", permissionLabel(AndroidPermissions.READ_MEDIA_IMAGES))
        assertEquals("android.permission.UNKNOWN", permissionLabel("android.permission.UNKNOWN"))
    }
}
