package com.sleepysoong.logent.android

/**
 * Android Intent를 만들기 전 단계의 순수 값이에요.
 * 프레임워크 없이 액션·데이터·extra 구성을 테스트하려고 따로 두어요.
 */
internal data class IntentPlan(
    val action: String,
    val data: String? = null,
    val type: String? = null,
    val packageName: String? = null,
    val categories: List<String> = emptyList(),
    val extras: List<IntentExtra> = emptyList(),
)

internal sealed interface IntentExtra {
    val key: String

    data class Text(override val key: String, val value: String) : IntentExtra
    data class Number(override val key: String, val value: Long) : IntentExtra
    data class Bool(override val key: String, val value: Boolean) : IntentExtra
}

internal object AndroidIntents {
    const val ACTION_INSERT = "android.intent.action.INSERT"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_DELETE = "android.intent.action.DELETE"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"
    const val ACTION_MAIN = "android.intent.action.MAIN"

    const val CATEGORY_APP_MESSAGING = "android.intent.category.APP_MESSAGING"

    const val ACTION_APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val ACTION_APP_NOTIFICATION_SETTINGS = "android.settings.APP_NOTIFICATION_SETTINGS"
    const val ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS =
        "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS"
    const val ACTION_MANAGE_WRITE_SETTINGS = "android.settings.action.MANAGE_WRITE_SETTINGS"

    const val ACTION_SOUND_SETTINGS = "android.settings.SOUND_SETTINGS"
    const val ACTION_DISPLAY_SETTINGS = "android.settings.DISPLAY_SETTINGS"
    const val ACTION_BATTERY_SAVER_SETTINGS = "android.settings.BATTERY_SAVER_SETTINGS"
    const val ACTION_SECURITY_SETTINGS = "android.settings.SECURITY_SETTINGS"
    const val ACTION_WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val ACTION_BLUETOOTH_SETTINGS = "android.settings.BLUETOOTH_SETTINGS"
    const val ACTION_APPLICATION_SETTINGS = "android.settings.APPLICATION_SETTINGS"
    const val ACTION_ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"
    const val ACTION_APPLICATION_DEVELOPMENT_SETTINGS =
        "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"
    const val ACTION_LOCATION_SOURCE_SETTINGS = "android.settings.LOCATION_SOURCE_SETTINGS"
    const val ACTION_INTERNAL_STORAGE_SETTINGS = "android.settings.INTERNAL_STORAGE_SETTINGS"

    const val ACTION_STILL_IMAGE_CAMERA = "android.media.action.STILL_IMAGE_CAMERA"

    const val CONTACTS_CONTENT_URI = "content://com.android.contacts/contacts"
    const val CONTACTS_CONTENT_TYPE = "vnd.android.cursor.dir/contact"
    const val EVENTS_CONTENT_URI = "content://com.android.calendar/events"
    const val EVENTS_CONTENT_TYPE = "vnd.android.cursor.dir/event"
    const val CALENDAR_CONTENT_URI = "content://com.android.calendar/time"
    const val IMAGES_CONTENT_URI = "content://media/external/images/media"

    const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"
    const val EXTRA_SMS_BODY = "sms_body"
    const val EXTRA_CONTACT_NAME = "name"
    const val EXTRA_CONTACT_PHONE = "phone"
    const val EXTRA_CONTACT_EMAIL = "email"
    const val EXTRA_EVENT_TITLE = "title"
    const val EXTRA_EVENT_BEGIN_TIME = "beginTime"
    const val EXTRA_EVENT_END_TIME = "endTime"
    const val EXTRA_EVENT_DESCRIPTION = "description"

    const val MARKET_DETAILS_PREFIX = "market://details?id="
    const val MARKET_SEARCH_PREFIX = "market://search?q="
}

/** 문자 작성 화면을 주소·본문과 함께 열어요. 전송은 사용자가 직접 확인해요. */
internal fun smsComposePlan(address: String?, body: String?): IntentPlan {
    val target = normalizePhoneAddress(address)
    return IntentPlan(
        action = AndroidIntents.ACTION_SENDTO,
        data = "sms:" + (target ?: ""),
        extras = buildList {
            if (!body.isNullOrEmpty()) add(IntentExtra.Text(AndroidIntents.EXTRA_SMS_BODY, body))
        },
    )
}

/** 연락처 편집 화면을 열어요. 저장은 사용자가 확인해야 해요. */
internal fun contactInsertPlan(name: String, phone: String?, email: String?): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_INSERT,
        data = AndroidIntents.CONTACTS_CONTENT_URI,
        type = AndroidIntents.CONTACTS_CONTENT_TYPE,
        extras = buildList {
            add(IntentExtra.Text(AndroidIntents.EXTRA_CONTACT_NAME, name))
            if (!phone.isNullOrBlank()) {
                add(IntentExtra.Text(AndroidIntents.EXTRA_CONTACT_PHONE, phone))
            }
            if (!email.isNullOrBlank()) {
                add(IntentExtra.Text(AndroidIntents.EXTRA_CONTACT_EMAIL, email))
            }
        },
    )

/** 캘린더 편집 화면을 열어요. 저장은 사용자가 확인해야 해요. */
internal fun calendarInsertPlan(
    title: String,
    start: Long,
    end: Long,
    description: String?,
): IntentPlan = IntentPlan(
    action = AndroidIntents.ACTION_INSERT,
    data = AndroidIntents.EVENTS_CONTENT_URI,
    type = AndroidIntents.EVENTS_CONTENT_TYPE,
    extras = buildList {
        add(IntentExtra.Text(AndroidIntents.EXTRA_EVENT_TITLE, title))
        add(IntentExtra.Number(AndroidIntents.EXTRA_EVENT_BEGIN_TIME, start))
        add(IntentExtra.Number(AndroidIntents.EXTRA_EVENT_END_TIME, end))
        if (!description.isNullOrBlank()) {
            add(IntentExtra.Text(AndroidIntents.EXTRA_EVENT_DESCRIPTION, description))
        }
    },
)

internal fun appDetailsPlan(packageName: String): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_APPLICATION_DETAILS_SETTINGS,
        data = "package:$packageName",
    )

internal fun uninstallPlan(packageName: String): IntentPlan =
    IntentPlan(action = AndroidIntents.ACTION_DELETE, data = "package:$packageName")

internal fun marketDetailsPlan(packageName: String): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_VIEW,
        data = AndroidIntents.MARKET_DETAILS_PREFIX + urlEncode(packageName),
    )

internal fun marketSearchPlan(query: String): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_VIEW,
        data = AndroidIntents.MARKET_SEARCH_PREFIX + urlEncode(query),
    )

/** content:// 미디어만 재생해요. file://은 FileProvider 없이 노출할 수 없어요. */
internal fun isPlayableMediaUri(uri: String?): Boolean {
    val value = uri?.trim() ?: return false
    if (!value.startsWith("content://")) return false
    return value.length > "content://".length
}

internal fun mediaViewPlan(uri: String?): IntentPlan? {
    if (!isPlayableMediaUri(uri)) return null
    return IntentPlan(action = AndroidIntents.ACTION_VIEW, data = uri!!.trim())
}

internal fun galleryPlan(): IntentPlan =
    IntentPlan(action = AndroidIntents.ACTION_VIEW, data = AndroidIntents.IMAGES_CONTENT_URI)

internal fun cameraPlan(): IntentPlan =
    IntentPlan(action = AndroidIntents.ACTION_STILL_IMAGE_CAMERA)

internal fun contactsOpenPlan(): IntentPlan =
    IntentPlan(action = AndroidIntents.ACTION_VIEW, data = AndroidIntents.CONTACTS_CONTENT_URI)

/** 캘린더 앱을 오늘 날짜로 열어요. */
internal fun calendarOpenPlan(now: Long): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_VIEW,
        data = AndroidIntents.CALENDAR_CONTENT_URI + "/" + now,
    )

/** 기본 문자 앱을 열어요. 특정 대화로 들어가지 않아요. */
internal fun messagingAppPlan(): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_MAIN,
        categories = listOf(AndroidIntents.CATEGORY_APP_MESSAGING),
    )

internal fun writeSettingsPlan(appPackage: String): IntentPlan =
    IntentPlan(
        action = AndroidIntents.ACTION_MANAGE_WRITE_SETTINGS,
        data = "package:$appPackage",
    )

/** 알림 설정은 우리 앱의 알림 화면이나 방해 금지 접근 설정으로만 열어요. */
internal fun notificationSettingsPlan(target: String, appPackage: String): IntentPlan? =
    when (target.lowercase()) {
        "app" -> IntentPlan(
            action = AndroidIntents.ACTION_APP_NOTIFICATION_SETTINGS,
            extras = listOf(IntentExtra.Text(AndroidIntents.EXTRA_APP_PACKAGE, appPackage)),
        )
        "dnd" -> IntentPlan(action = AndroidIntents.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        else -> null
    }

/** `android_control`의 open_settings 대상을 시스템 설정 액션으로 바꿔요. */
internal fun settingsPlan(key: String, appPackage: String): IntentPlan? = when (key.lowercase()) {
    "sound" -> IntentPlan(action = AndroidIntents.ACTION_SOUND_SETTINGS)
    "display" -> IntentPlan(action = AndroidIntents.ACTION_DISPLAY_SETTINGS)
    "battery" -> IntentPlan(action = AndroidIntents.ACTION_BATTERY_SAVER_SETTINGS)
    "security" -> IntentPlan(action = AndroidIntents.ACTION_SECURITY_SETTINGS)
    "notifications" -> IntentPlan(
        action = AndroidIntents.ACTION_APP_NOTIFICATION_SETTINGS,
        extras = listOf(IntentExtra.Text(AndroidIntents.EXTRA_APP_PACKAGE, appPackage)),
    )
    "location" -> IntentPlan(action = AndroidIntents.ACTION_LOCATION_SOURCE_SETTINGS)
    "wifi" -> IntentPlan(action = AndroidIntents.ACTION_WIFI_SETTINGS)
    "bluetooth" -> IntentPlan(action = AndroidIntents.ACTION_BLUETOOTH_SETTINGS)
    "apps" -> IntentPlan(action = AndroidIntents.ACTION_APPLICATION_SETTINGS)
    "dnd" -> IntentPlan(action = AndroidIntents.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    "accessibility" -> IntentPlan(action = AndroidIntents.ACTION_ACCESSIBILITY_SETTINGS)
    "developer" -> IntentPlan(action = AndroidIntents.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    "storage" -> IntentPlan(action = AndroidIntents.ACTION_INTERNAL_STORAGE_SETTINGS)
    else -> null
}

internal val SETTINGS_KEYS = listOf(
    "sound", "display", "battery", "security", "notifications", "location", "wifi",
    "bluetooth", "apps", "dnd", "accessibility", "developer", "storage",
)
