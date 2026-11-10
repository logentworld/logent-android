package com.sleepysoong.logent.android

import com.sleepysoong.logent.plugin.LogentPlugin
import com.sleepysoong.logent.plugin.PLUGIN_API_VERSION
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.PluginTool
import com.sleepysoong.logent.plugin.ToolAccess
import com.sleepysoong.logent.plugin.integerProperty
import com.sleepysoong.logent.plugin.stringProperty
import com.sleepysoong.logent.plugin.toolSpec

/**
 * Android 공통 기능을 제공하는 플러그인이에요.
 *
 * 삼성·Google 전용 기능은 다른 플러그인이 맡고, 여기서는 Android 공개 API만 써요.
 * 위험 권한은 도구 실행 시점에 호스트에 요청하고, 거부되면 기능별 설정 안내를 오류로 돌려줘요.
 */
class AndroidPlugin : LogentPlugin {
    override val id: String = "com.sleepysoong.logent.android"
    override val name: String = "Android"
    override val version: String = BuildConfig.PLUGIN_VERSION
    override val apiVersion: Int = PLUGIN_API_VERSION

    @Volatile
    private var host: PluginHost? = null

    private val android = AndroidTools { host }

    override fun tools(): List<PluginTool> = listOf(
        PluginTool(
            spec = toolSpec(
                name = "android_device",
                description = "Android 기기의 제조사·모델·SDK·보안 패치·가동 시간·화면·배터리·저장소·" +
                    "네트워크·센서·음량·방해 금지 상태를 읽는다. 값은 기기가 제공하는 범위에서만 " +
                    "돌려주고, 권한이 없으면 추측하지 않는다. action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty(
                    "action",
                    "info: 기기·화면 정보, battery: 배터리, storage: 내부·외부 저장소, " +
                        "network: 연결 종류·검증·Metered·VPN, sensors: 센서 목록, " +
                        "volume_get: 음악·알람·알림·통화 음량, dnd_status: 방해 금지 상태",
                    listOf(
                        "info", "battery", "storage", "network", "sensors", "volume_get", "dnd_status",
                    ),
                )
            },
            access = setOf(ToolAccess.READ_ONLY),
            childVisible = true,
            label = "기기 정보",
        ) { arguments, _ -> android.device(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_control",
                description = "Android 기기를 제어한다: 손전등, 음량, 클립보드, 진동, 단색 배경화면, " +
                    "화면 밝기·회전, 시스템 설정 화면 열기. 밝기·회전은 WRITE_SETTINGS 특수 권한이 " +
                    "필요하고, 없으면 임의로 우회하지 않고 설정 안내를 돌려준다. action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty(
                    "action",
                    "flashlight, volume_set, clipboard_get, clipboard_set, vibrate, " +
                        "wallpaper_color, brightness_set, rotation_set, open_settings",
                    listOf(
                        "flashlight", "volume_set", "clipboard_get", "clipboard_set", "vibrate",
                        "wallpaper_color", "brightness_set", "rotation_set", "open_settings",
                    ),
                )
                stringProperty("mode", "손전등은 on/off/toggle, 회전은 auto/portrait/landscape")
                stringProperty(
                    "stream",
                    "volume_set 대상 스트림",
                    listOf("music", "alarm", "notification", "call"),
                )
                integerProperty("percent", "volume_set·brightness_set 값(0~100)", 0, 100)
                stringProperty("text", "clipboard_set에 복사할 문자열(최대 20,000자)")
                integerProperty("milliseconds", "vibrate 진동 시간(1~5000ms, 기본 300)", 1, 5000)
                stringProperty("color", "wallpaper_color의 #RGB·#RRGGBB·#AARRGGBB 색상")
                stringProperty(
                    "settings",
                    "open_settings 대상",
                    SETTINGS_KEYS,
                )
            },
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "기기 제어",
        ) { arguments, _ -> android.control(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_apps",
                description = "설치 앱을 나열하고 앱 실행·설정 화면·제거 확인·Play 스토어 페이지·" +
                    "스토어 검색을 연다. 제거는 확인 화면만 열고 실제 삭제는 사용자가 확인한다. " +
                    "action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty(
                    "action",
                    "list, open, info, uninstall, store, search",
                    listOf("list", "open", "info", "uninstall", "store", "search"),
                )
                stringProperty("package", "대상 앱 패키지 이름(open·info·uninstall·store)")
                stringProperty("query", "search 검색어(최대 200자)")
                integerProperty("limit", "list 결과 수(1~200, 기본 50)", 1, 200)
            },
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "앱 관리",
        ) { arguments, _ -> android.apps(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_contacts",
                description = "연락처를 검색하거나 연락처 편집 화면을 연다. 검색은 READ_CONTACTS " +
                    "권한이 필요하고, 추가는 권한 없이 편집 화면을 열어 사용자가 저장을 확인한다. " +
                    "action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty("action", "search, add, open", listOf("search", "add", "open"))
                stringProperty("name", "검색할 이름 또는 추가할 이름")
                stringProperty("phone", "검색할 전화번호 또는 추가할 전화번호")
                stringProperty("email", "추가할 이메일")
                integerProperty("limit", "검색 결과 수(1~50, 기본 20)", 1, 50)
            },
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "연락처",
        ) { arguments, _ -> android.contacts(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_calendar",
                description = "캘린더 일정을 조회하거나 일정 편집 화면을 연다. 조회는 READ_CALENDAR " +
                    "권한이 필요하고, 추가는 권한 없이 편집 화면을 열어 사용자가 저장을 확인한다. " +
                    "시각은 epoch 밀리초이고 action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty("action", "list, add, open", listOf("list", "add", "open"))
                integerProperty("from", "list 시작 epoch 밀리초(기본 지금)")
                integerProperty("to", "list 종료 epoch 밀리초(기본 시작 + 7일)")
                integerProperty("limit", "list 결과 수(1~100, 기본 20)", 1, 100)
                stringProperty("title", "add 일정 제목")
                integerProperty("start", "add 시작 epoch 밀리초(기본 지금)")
                integerProperty("end", "add 종료 epoch 밀리초(기본 시작 + 1시간)")
                stringProperty("description", "add 일정 설명")
            },
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "캘린더",
        ) { arguments, _ -> android.calendar(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_sms",
                description = "문자 메시지를 조회·전송하거나 작성 화면을 연다. list는 READ_SMS, send는 " +
                    "SEND_SMS 권한이 필요하다. send는 검증 후 실제로 전송하며 되돌릴 수 없고, compose는 " +
                    "권한 없이 작성 화면만 연다. action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty("action", "list, send, compose, open", listOf("list", "send", "compose", "open"))
                integerProperty("thread", "list 스레드 ID")
                stringProperty("address", "list 발신·수신 주소 필터")
                integerProperty("limit", "list 결과 수(1~50, 기본 20)", 1, 50)
                stringProperty("to", "send·compose 받는 번호")
                stringProperty("body", "send·compose 본문(전송은 최대 1000자)")
            },
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "문자",
        ) { arguments, _ -> android.sms(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_location",
                description = "LocationManager의 마지막으로 알려진 위치를 읽거나 위치 설정 화면을 연다. " +
                    "FusedLocationProvider가 없으므로 실시간 측위는 하지 않는다. 위치 권한은 실행 시점에 " +
                    "요청한다. action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty("action", "last 또는 open_settings", listOf("last", "open_settings"))
            },
            access = setOf(ToolAccess.READ_ONLY),
            childVisible = false,
            label = "위치",
        ) { arguments, _ -> android.location(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_media",
                description = "MediaStore에서 최근 사진·동영상·오디오를 조회하고 갤러리·카메라를 열거나 " +
                    "content:// 미디어를 연다. Android 13 이상은 READ_MEDIA_* 권한을 유형별로 요청한다. " +
                    "file:// 경로는 FileProvider가 없어 지원하지 않는다. action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty(
                    "action",
                    "images, videos, audio, open_gallery, open_camera, play",
                    listOf("images", "videos", "audio", "open_gallery", "open_camera", "play"),
                )
                integerProperty("limit", "조회 결과 수(1~100, 기본 30)", 1, 100)
                stringProperty("uri", "play할 content:// URI")
            },
            access = setOf(ToolAccess.WRITE),
            childVisible = false,
            label = "미디어",
        ) { arguments, _ -> android.media(arguments) },

        PluginTool(
            spec = toolSpec(
                name = "android_notifications",
                description = "방해 금지 접근 여부와 현재 필터를 읽거나 앱 알림·방해 금지 설정 화면을 연다. " +
                    "NotificationListener가 없으므로 다른 앱의 알림 목록은 읽지 않는다. action은 필수다.",
                required = listOf("action"),
            ) {
                stringProperty("action", "status 또는 open_settings", listOf("status", "open_settings"))
                stringProperty("target", "open_settings 대상: app(이 앱 알림) 또는 dnd", listOf("app", "dnd"))
            },
            access = setOf(ToolAccess.READ_ONLY),
            childVisible = false,
            label = "알림",
        ) { arguments, _ -> android.notifications(arguments) },
    )

    override suspend fun ensureReady(host: PluginHost): Result<Unit> {
        this.host = host
        return Result.success(Unit)
    }
}
