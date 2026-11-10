package com.sleepysoong.logent.android

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `android_device`와 `android_notifications`의 읽기 전용 구현이에요. */
internal suspend fun AndroidTools.device(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "info" -> deviceInfo(context)
        "battery" -> batteryStatus(context)
        "storage" -> storageStatus()
        "network" -> networkStatus(context)
        "sensors" -> sensorList(context)
        "volume_get" -> volumeStatus(context)
        "dnd_status" -> dndStatus(context)
        null, "" -> toolError(
            "action이 필요해요. info, battery, storage, network, sensors, volume_get, dnd_status 중 하나를 쓰세요.",
        )
        else -> toolError(
            "지원하지 않는 action이에요: $action. " +
                "info, battery, storage, network, sensors, volume_get, dnd_status 중 하나를 쓰세요.",
        )
    }
}

private fun AndroidTools.deviceInfo(context: Context): ToolResult {
    val metrics = context.resources.displayMetrics
    val widthDp = if (metrics.densityDpi > 0) {
        (metrics.widthPixels * 160 / metrics.densityDpi)
    } else {
        0
    }
    val heightDp = if (metrics.densityDpi > 0) {
        (metrics.heightPixels * 160 / metrics.densityDpi)
    } else {
        0
    }
    val payload = buildJsonObject {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        put("securityPatch", Build.VERSION.SECURITY_PATCH)
        put("uptimeMs", SystemClock.elapsedRealtime())
        put("uptime", formatDuration(SystemClock.elapsedRealtime()))
        put("locale", Locale.getDefault().toLanguageTag())
        put("timezone", TimeZone.getDefault().id)
        put(
            "screen",
            buildJsonObject {
                put("widthPx", metrics.widthPixels)
                put("heightPx", metrics.heightPixels)
                put("widthDp", widthDp)
                put("heightDp", heightDp)
                put("densityDpi", metrics.densityDpi)
                put("density", metrics.density.toDouble())
            },
        )
    }
    return ToolResult(payload.toString())
}

private fun AndroidTools.batteryStatus(context: Context): ToolResult {
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return toolError("배터리 상태 브로드캐스트를 읽지 못했어요. 기기 정책을 확인해 주세요.")
    val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) {
        return toolError("배터리 잔량을 읽지 못했어요. 기기에서 배터리 정보를 제공하지 않을 수 있어요.")
    }
    val percent = (level * 100) / scale
    val state = status.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    val temperature = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    val voltage = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
    val health = status.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
    val technology = status.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)
    val charging = state == BatteryManager.BATTERY_STATUS_CHARGING ||
        state == BatteryManager.BATTERY_STATUS_FULL || plugged != 0

    val payload = buildJsonObject {
        put("percent", percent)
        put("charging", charging)
        put("status", batteryStatusLabel(state))
        put("plugged", pluggedLabel(plugged))
        put("temperatureC", if (temperature == Int.MIN_VALUE) null else temperature / 10.0)
        put("voltageMv", if (voltage < 0) null else voltage)
        put("health", batteryHealthLabel(health))
        if (technology != null) put("technology", technology)
    }
    return ToolResult(payload.toString())
}

private fun batteryStatusLabel(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "충전 중"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "방전 중"
    BatteryManager.BATTERY_STATUS_FULL -> "충전 완료"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "충전하지 않음"
    else -> "알 수 없음"
}

private fun pluggedLabel(plugged: Int): String = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "AC 충전기"
    BatteryManager.BATTERY_PLUGGED_USB -> "USB"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "무선"
    else -> "연결되지 않음"
}

private fun batteryHealthLabel(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "좋음"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "과열"
    BatteryManager.BATTERY_HEALTH_COLD -> "너무 추움"
    BatteryManager.BATTERY_HEALTH_DEAD -> "교체 필요"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "전압 과다"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "확인 필요"
    else -> "알 수 없음"
}

@Suppress("DEPRECATION")
private fun AndroidTools.storageStatus(): ToolResult {
    val internalPath = Environment.getDataDirectory().absolutePath
    val internal = runCatching { storageEntry(internalPath) }.getOrNull()
    val externalPath = Environment.getExternalStorageDirectory()?.absolutePath
    val external = externalPath?.let { runCatching { storageEntry(it) }.getOrNull() }
    if (internal == null && external == null) {
        return toolError("저장소 통계를 읽지 못했어요. 기기 정책이나 마운트 상태를 확인해 주세요.")
    }
    val payload = buildJsonObject {
        if (internal != null) put("internal", internal) else put("internal", null as String?)
        if (external != null) put("external", external) else put("external", null as String?)
    }
    return ToolResult(payload.toString())
}

private fun storageEntry(path: String): JsonObject {
    val stat = StatFs(path)
    val total = stat.totalBytes
    val available = stat.availableBytes
    return buildJsonObject {
        put("path", path)
        put("totalBytes", total)
        put("availableBytes", available)
        put("total", formatBytes(total))
        put("available", formatBytes(available))
    }
}

private fun AndroidTools.networkStatus(context: Context): ToolResult {
    val manager = context.getSystemService(ConnectivityManager::class.java)
        ?: return toolError("연결 관리 서비스를 사용할 수 없어요.")
    val network = manager.activeNetwork
        ?: return ToolResult(
            buildJsonObject {
                put("connected", false)
                put("transports", buildJsonArray { })
                put("message", "활성 네트워크가 없어요.")
            }.toString(),
        )
    val capabilities = manager.getNetworkCapabilities(network)
        ?: return ToolResult(
            buildJsonObject {
                put("connected", false)
                put("transports", buildJsonArray { })
                put("message", "활성 네트워크의 기능 정보를 읽지 못했어요.")
            }.toString(),
        )
    val transports = buildJsonArray {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
    }
    val payload = buildJsonObject {
        put("connected", true)
        put("transports", transports)
        put("validated", capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        put("metered", !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
        put("vpn", capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        put("downstreamKbps", capabilities.linkDownstreamBandwidthKbps)
        put("upstreamKbps", capabilities.linkUpstreamBandwidthKbps)
    }
    return ToolResult(payload.toString())
}

private fun AndroidTools.sensorList(context: Context): ToolResult {
    val manager = context.getSystemService(SensorManager::class.java)
        ?: return toolError("센서 서비스를 사용할 수 없어요.")
    val sensors = manager.getSensorList(Sensor.TYPE_ALL)
    val payload = buildJsonArray {
        sensors.take(100).forEach { sensor ->
            add(
                buildJsonObject {
                    put("name", sensor.name)
                    put("vendor", sensor.vendor)
                    put("type", sensor.type)
                    put("typeName", sensorTypeLabel(sensor.type))
                    put("version", sensor.version)
                    put("powerMa", sensor.power.toDouble())
                    put("maxRange", sensor.maximumRange.toDouble())
                    put("resolution", sensor.resolution.toDouble())
                    put("minDelayUs", sensor.minDelay)
                },
            )
        }
    }
    return ToolResult(
        buildJsonObject {
            put("count", sensors.size)
            put("sensors", payload)
        }.toString(),
    )
}

private fun AndroidTools.volumeStatus(context: Context): ToolResult {
    val audio = context.getSystemService(AudioManager::class.java)
        ?: return toolError("오디오 서비스를 사용할 수 없어요.")
    val payload = buildJsonObject {
        streamEntry(audio, "music", AudioManager.STREAM_MUSIC)?.let { put("music", it) }
        streamEntry(audio, "alarm", AudioManager.STREAM_ALARM)?.let { put("alarm", it) }
        streamEntry(audio, "notification", AudioManager.STREAM_NOTIFICATION)
            ?.let { put("notification", it) }
        streamEntry(audio, "call", AudioManager.STREAM_VOICE_CALL)?.let { put("call", it) }
    }
    return ToolResult(payload.toString())
}

private fun streamEntry(audio: AudioManager, label: String, stream: Int): JsonObject? {
    val max = audio.getStreamMaxVolume(stream)
    if (max <= 0) return null
    val current = audio.getStreamVolume(stream)
    return buildJsonObject {
        put("stream", label)
        put("current", current)
        put("max", max)
        put("percent", current * 100 / max)
    }
}

internal fun AndroidTools.dndStatus(context: Context): ToolResult {
    val manager = context.getSystemService(NotificationManager::class.java)
        ?: return toolError("알림 서비스를 사용할 수 없어요.")
    val access = manager.isNotificationPolicyAccessGranted
    val payload = buildJsonObject {
        put("accessGranted", access)
        if (access) {
            put("filter", interruptionFilterLabel(manager.currentInterruptionFilter))
            put("filterCode", manager.currentInterruptionFilter)
        } else {
            put("filter", null as String?)
            put(
                "guidance",
                "방해 금지 모드 상태를 읽으려면 설정 → 앱 → 특수 권한 → 방해 금지 모드 접근에서 " +
                    "Logent를 허용해 주세요.",
            )
        }
    }
    return ToolResult(payload.toString())
}

private fun interruptionFilterLabel(filter: Int): String = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_ALL -> "모든 알림 허용"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "우선순위 알림만"
    NotificationManager.INTERRUPTION_FILTER_NONE -> "방해 금지"
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "알람만"
    else -> "알 수 없음"
}

/** `android_notifications`의 읽기 전용 구현이에요. */
internal suspend fun AndroidTools.notifications(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "status" -> dndStatus(context)
        "open_settings" -> {
            val target = arguments.text("target")?.trim()?.lowercase() ?: "app"
            val plan = notificationSettingsPlan(target, context.packageName)
                ?: return@safe toolError(
                    "지원하지 않는 설정 대상이에요: $target. app 또는 dnd를 쓰세요.",
                )
            startOrResult(plan, "알림 설정 화면을 열었어요.")
        }
        null, "" -> toolError("action이 필요해요. status 또는 open_settings를 쓰세요.")
        else -> toolError(
            "지원하지 않는 action이에요: $action. status 또는 open_settings를 쓰세요.",
        )
    }
}


/** SMS·일정·미디어 시각을 사람이 읽는 형식으로 바꿔요. */
internal fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMs))
