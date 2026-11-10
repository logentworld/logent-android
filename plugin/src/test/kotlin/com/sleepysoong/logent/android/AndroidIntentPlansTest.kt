package com.sleepysoong.logent.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidIntentPlansTest {
    private fun IntentPlan.textExtra(key: String): String? =
        (extras.firstOrNull { it.key == key } as? IntentExtra.Text)?.value

    private fun IntentPlan.numberExtra(key: String): Long? =
        (extras.firstOrNull { it.key == key } as? IntentExtra.Number)?.value

    @Test
    fun `문자 작성 화면은 정리된 주소와 본문을 담는다`() {
        val plan = smsComposePlan("+82 10-1234-5678", "안녕하세요")
        assertEquals(AndroidIntents.ACTION_SENDTO, plan.action)
        assertEquals("sms:+821012345678", plan.data)
        assertEquals("안녕하세요", plan.textExtra(AndroidIntents.EXTRA_SMS_BODY))
    }

    @Test
    fun `문자 작성 화면은 주소와 본문이 없어도 열 수 있다`() {
        val plan = smsComposePlan(null, null)
        assertEquals("sms:", plan.data)
        assertTrue(plan.extras.isEmpty())
    }

    @Test
    fun `연락처 추가는 편집 URI와 extra를 구성한다`() {
        val plan = contactInsertPlan("홍길동", "+821012345678", "a@example.com")
        assertEquals(AndroidIntents.ACTION_INSERT, plan.action)
        assertEquals(AndroidIntents.CONTACTS_CONTENT_URI, plan.data)
        assertEquals(AndroidIntents.CONTACTS_CONTENT_TYPE, plan.type)
        assertEquals("홍길동", plan.textExtra(AndroidIntents.EXTRA_CONTACT_NAME))
        assertEquals("+821012345678", plan.textExtra(AndroidIntents.EXTRA_CONTACT_PHONE))
        assertEquals("a@example.com", plan.textExtra(AndroidIntents.EXTRA_CONTACT_EMAIL))
    }

    @Test
    fun `연락처 추가는 빈 전화번호와 이메일을 넣지 않는다`() {
        val plan = contactInsertPlan("홍길동", null, " ")
        assertNull(plan.textExtra(AndroidIntents.EXTRA_CONTACT_PHONE))
        assertNull(plan.textExtra(AndroidIntents.EXTRA_CONTACT_EMAIL))
    }

    @Test
    fun `일정 추가는 이벤트 URI와 epoch millis를 담는다`() {
        val plan = calendarInsertPlan("회의", 1_000L, 2_000L, "준비물")
        assertEquals(AndroidIntents.ACTION_INSERT, plan.action)
        assertEquals(AndroidIntents.EVENTS_CONTENT_URI, plan.data)
        assertEquals(AndroidIntents.EVENTS_CONTENT_TYPE, plan.type)
        assertEquals("회의", plan.textExtra(AndroidIntents.EXTRA_EVENT_TITLE))
        assertEquals(1_000L, plan.numberExtra(AndroidIntents.EXTRA_EVENT_BEGIN_TIME))
        assertEquals(2_000L, plan.numberExtra(AndroidIntents.EXTRA_EVENT_END_TIME))
        assertEquals("준비물", plan.textExtra(AndroidIntents.EXTRA_EVENT_DESCRIPTION))
    }

    @Test
    fun `일정 추가는 빈 설명을 넣지 않는다`() {
        val plan = calendarInsertPlan("회의", 1_000L, 2_000L, "  ")
        assertNull(plan.textExtra(AndroidIntents.EXTRA_EVENT_DESCRIPTION))
    }

    @Test
    fun `시스템 설정 키가 올바른 액션으로 바뀐다`() {
        assertEquals(
            AndroidIntents.ACTION_SOUND_SETTINGS,
            settingsPlan("sound", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_DISPLAY_SETTINGS,
            settingsPlan("display", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_BATTERY_SAVER_SETTINGS,
            settingsPlan("battery", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_SECURITY_SETTINGS,
            settingsPlan("security", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_LOCATION_SOURCE_SETTINGS,
            settingsPlan("location", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_WIFI_SETTINGS,
            settingsPlan("wifi", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_BLUETOOTH_SETTINGS,
            settingsPlan("bluetooth", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_APPLICATION_SETTINGS,
            settingsPlan("apps", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_ACCESSIBILITY_SETTINGS,
            settingsPlan("accessibility", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            settingsPlan("developer", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_INTERNAL_STORAGE_SETTINGS,
            settingsPlan("storage", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            settingsPlan("dnd", "com.test")?.action,
        )
        assertNull(settingsPlan("unknown", "com.test"))
        assertNull(settingsPlan("", "com.test"))
    }

    @Test
    fun `알림 설정은 우리 앱 패키지를 extra로 넘긴다`() {
        val plan = settingsPlan("notifications", "com.test")
        assertEquals(AndroidIntents.ACTION_APP_NOTIFICATION_SETTINGS, plan?.action)
        assertEquals("com.test", plan?.textExtra(AndroidIntents.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `알림 도구 설정 대상은 app과 dnd만 허용한다`() {
        assertEquals(
            AndroidIntents.ACTION_APP_NOTIFICATION_SETTINGS,
            notificationSettingsPlan("app", "com.test")?.action,
        )
        assertEquals(
            AndroidIntents.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS,
            notificationSettingsPlan("dnd", "com.test")?.action,
        )
        assertNull(notificationSettingsPlan("other", "com.test"))
    }

    @Test
    fun `시스템 설정 변경 안내는 패키지 데이터를 담는다`() {
        val plan = writeSettingsPlan("com.test")
        assertEquals(AndroidIntents.ACTION_MANAGE_WRITE_SETTINGS, plan.action)
        assertEquals("package:com.test", plan.data)
    }

    @Test
    fun `앱 상세 제거 스토어 인텐트 데이터가 맞다`() {
        assertEquals(
            "package:com.test",
            appDetailsPlan("com.test").data,
        )
        assertEquals(
            AndroidIntents.ACTION_APPLICATION_DETAILS_SETTINGS,
            appDetailsPlan("com.test").action,
        )
        assertEquals("package:com.test", uninstallPlan("com.test").data)
        assertEquals(AndroidIntents.ACTION_DELETE, uninstallPlan("com.test").action)
        assertEquals(
            "market://details?id=com.test",
            marketDetailsPlan("com.test").data,
        )
        assertEquals(
            "market://search?q=a+b%26c",
            marketSearchPlan("a b&c").data,
        )
    }

    @Test
    fun `재생은 content URI만 허용한다`() {
        assertTrue(isPlayableMediaUri("content://media/external/images/media/1"))
        assertFalse(isPlayableMediaUri("content://"))
        assertFalse(isPlayableMediaUri("file:///sdcard/a.jpg"))
        assertFalse(isPlayableMediaUri("https://example.com/a.mp4"))
        assertFalse(isPlayableMediaUri(null))
        assertNull(mediaViewPlan("file:///sdcard/a.jpg"))
        assertEquals(
            "content://media/external/images/media/1",
            mediaViewPlan(" content://media/external/images/media/1 ")?.data,
        )
    }

    @Test
    fun `갤러리 카메라 문자 캘린더 인텐트를 구성한다`() {
        assertEquals(AndroidIntents.IMAGES_CONTENT_URI, galleryPlan().data)
        assertEquals(AndroidIntents.ACTION_VIEW, galleryPlan().action)
        assertEquals(AndroidIntents.ACTION_STILL_IMAGE_CAMERA, cameraPlan().action)
        assertEquals(AndroidIntents.ACTION_SENDTO, smsComposePlan("01012345678", null).action)
        assertEquals(AndroidIntents.ACTION_MAIN, messagingAppPlan().action)
        assertEquals(
            listOf(AndroidIntents.CATEGORY_APP_MESSAGING),
            messagingAppPlan().categories,
        )
        assertTrue(calendarOpenPlan(123L).data?.startsWith("content://com.android.calendar/time/") == true)
        assertTrue(calendarOpenPlan(123L).data?.endsWith("/123") == true)
        assertEquals(AndroidIntents.CONTACTS_CONTENT_URI, contactsOpenPlan().data)
    }
}
