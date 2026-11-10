package com.sleepysoong.logent.android

import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Surface
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.integer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `android_control`의 변경 동작 구현이에요. 사용자가 직접 확인해야 하는 동작은 몰래 우회하지 않아요. */
internal suspend fun AndroidTools.control(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "flashlight" -> setFlashlight(arguments)
        "volume_set" -> setVolume(context, arguments)
        "clipboard_get" -> readClipboard(context)
        "clipboard_set" -> writeClipboard(context, arguments)
        "vibrate" -> vibrate(context, arguments)
        "wallpaper_color" -> setWallpaperColor(context, arguments)
        "brightness_set" -> setBrightness(context, arguments)
        "rotation_set" -> setRotation(context, arguments)
        "open_settings" -> openSettings(context, arguments)
        null, "" -> toolError(
            "action이 필요해요. flashlight, volume_set, clipboard_get, clipboard_set, vibrate, " +
                "wallpaper_color, brightness_set, rotation_set, open_settings 중 하나를 쓰세요.",
        )
        else -> toolError(
            "지원하지 않는 action이에요: $action. flashlight, volume_set, clipboard_get, " +
                "clipboard_set, vibrate, wallpaper_color, brightness_set, rotation_set, " +
                "open_settings 중 하나를 쓰세요.",
        )
    }
}

private fun AndroidTools.setFlashlight(arguments: JsonObject): ToolResult {
    val context = requireContext()
    val manager = context.getSystemService(CameraManager::class.java)
        ?: return toolError("카메라 서비스를 사용할 수 없어요.")
    val mode = arguments.text("mode")?.trim()?.lowercase() ?: "toggle"
    if (mode !in setOf("on", "off", "toggle")) {
        return toolError("mode는 on, off, toggle 중 하나여야 해요.")
    }
    val cameraId = selectTorchCamera(manager)
        ?: return toolError("이 기기에는 플래시가 있는 카메라를 찾지 못했어요.")
    val (rememberedId, rememberedOn) = torchState()
    val current = if (rememberedId == cameraId) rememberedOn else false
    val target = when (mode) {
        "on" -> true
        "off" -> false
        else -> !current
    }
    manager.setTorchMode(cameraId, target)
    rememberTorch(cameraId, target)
    return ToolResult(if (target) "손전등을 켰어요." else "손전등을 껐어요.")
}

private fun selectTorchCamera(manager: CameraManager): String? {
    var fallback: String? = null
    manager.cameraIdList.forEach { id ->
        val characteristics = manager.getCameraCharacteristics(id)
        if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
            return@forEach
        }
        if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
        ) {
            return id
        }
        if (fallback == null) fallback = id
    }
    return fallback
}

private fun AndroidTools.setVolume(context: Context, arguments: JsonObject): ToolResult {
    val audio = context.getSystemService(AudioManager::class.java)
        ?: return toolError("오디오 서비스를 사용할 수 없어요.")
    val streamName = arguments.text("stream")?.trim()?.lowercase() ?: "music"
    val stream = when (streamName) {
        "music" -> AudioManager.STREAM_MUSIC
        "alarm" -> AudioManager.STREAM_ALARM
        "notification" -> AudioManager.STREAM_NOTIFICATION
        "call" -> AudioManager.STREAM_VOICE_CALL
        else -> return toolError("stream은 music, alarm, notification, call 중 하나여야 해요.")
    }
    val percent = arguments.integer("percent")
        ?: return toolError("percent가 필요해요. 0~100 사이 정수로 알려 주세요.")
    if (percent !in 0..100) return toolError("percent는 0~100 사이여야 해요.")
    val max = audio.getStreamMaxVolume(stream)
    if (max <= 0) return toolError("이 기기에서 $streamName 스트림의 음량을 바꿀 수 없어요.")
    val value = (percent * max + 50) / 100
    audio.setStreamVolume(stream, value, 0)
    val current = audio.getStreamVolume(stream)
    return ToolResult(
        buildJsonObject {
            put("stream", streamName)
            put("percent", percent)
            put("current", current)
            put("max", max)
        }.toString(),
    )
}

private fun AndroidTools.readClipboard(context: Context): ToolResult {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return toolError("클립보드 서비스를 사용할 수 없어요.")
    if (!clipboard.hasPrimaryClip()) return ToolResult("클립보드가 비어 있어요.")
    val clip = clipboard.primaryClip
        ?: return ToolResult("클립보드가 비어 있어요.")
    val text = buildString {
        for (index in 0 until clip.itemCount) {
            val part = clip.getItemAt(index).coerceToText(context)?.toString().orEmpty()
            if (part.isEmpty()) continue
            if (isNotEmpty()) append('\n')
            append(part)
        }
    }
    if (text.isEmpty()) {
        return ToolResult(
            "클립보드에 읽을 수 있는 텍스트가 없어요. Android 10 이상에서는 앱이 화면에 없을 때 " +
                "다른 앱이 복사한 내용을 읽을 수 없어요.",
        )
    }
    val limited = if (text.length > MAX_CLIPBOARD_TEXT) {
        text.take(MAX_CLIPBOARD_TEXT) + "\n… (${text.length - MAX_CLIPBOARD_TEXT}자 생략)"
    } else {
        text
    }
    return ToolResult(limited)
}

private fun AndroidTools.writeClipboard(context: Context, arguments: JsonObject): ToolResult {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return toolError("클립보드 서비스를 사용할 수 없어요.")
    val text = arguments.text("text")
        ?: return toolError("text가 필요해요.")
    if (text.isEmpty()) return toolError("빈 문자열은 복사할 수 없어요.")
    if (text.length > MAX_CLIPBOARD_TEXT) {
        return toolError("클립보드에는 ${MAX_CLIPBOARD_TEXT}자까지만 복사할 수 있어요.")
    }
    clipboard.setPrimaryClip(ClipData.newPlainText("Logent", text))
    return ToolResult("클립보드에 ${text.length}자를 복사했어요.")
}

private fun AndroidTools.vibrate(context: Context, arguments: JsonObject): ToolResult {
    val milliseconds = arguments.integer("milliseconds") ?: 300
    if (milliseconds !in 1..5000) return toolError("milliseconds는 1~5000 사이여야 해요.")
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    } ?: return toolError("진동 서비스를 사용할 수 없어요.")
    if (!vibrator.hasVibrator()) return toolError("이 기기에는 진동 모터가 없어요.")
    vibrator.vibrate(VibrationEffect.createOneShot(milliseconds.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    return ToolResult("${milliseconds}ms 동안 진동했어요.")
}

private fun AndroidTools.setWallpaperColor(context: Context, arguments: JsonObject): ToolResult {
    val color = parseColorHex(arguments.text("color"))
        ?: return toolError("color는 #RGB, #RRGGBB, #AARRGGBB 형식이어야 해요.")
    val manager = WallpaperManager.getInstance(context)
    if (!manager.isWallpaperSupported) return toolError("이 기기에서는 배경화면 변경을 지원하지 않아요.")
    if (!manager.isSetWallpaperAllowed) {
        return toolError(
            "기기 정책이 배경화면 변경을 막고 있어요. 설정 → 앱 → 특수 권한에서 배경화면 변경을 " +
                "허용해 주세요.",
        )
    }
    val metrics = context.resources.displayMetrics
    val width = metrics.widthPixels.coerceIn(1, 4096)
    val height = metrics.heightPixels.coerceIn(1, 4096)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(bitmap).drawColor(color)
        manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
    } finally {
        bitmap.recycle()
    }
    return ToolResult("배경화면을 단색으로 바꿨어요.")
}

private fun AndroidTools.setBrightness(context: Context, arguments: JsonObject): ToolResult {
    if (!canWriteSystemSettings()) return writeSettingsDenied()
    val percent = arguments.integer("percent")
        ?: return toolError("percent가 필요해요. 0~100 사이 정수로 알려 주세요.")
    if (percent !in 0..100) return toolError("percent는 0~100 사이여야 해요.")
    val value = (percent * 255 + 50) / 100
    val resolver = context.contentResolver
    val manual = Settings.System.putInt(
        resolver,
        Settings.System.SCREEN_BRIGHTNESS_MODE,
        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
    )
    if (!manual) return toolError("화면 밝기 모드를 바꾸지 못했어요. 시스템 설정 변경 권한을 확인해 주세요.")
    val changed = Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
    if (!changed) return toolError("화면 밝기를 바꾸지 못했어요. 시스템 설정 변경 권한을 확인해 주세요.")
    return ToolResult("화면 밝기를 $percent%로 바꿨어요.")
}

private fun AndroidTools.setRotation(context: Context, arguments: JsonObject): ToolResult {
    if (!canWriteSystemSettings()) return writeSettingsDenied()
    val mode = arguments.text("mode")?.trim()?.lowercase() ?: "auto"
    val resolver = context.contentResolver
    val changed = when (mode) {
        "auto" -> Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            1,
        )
        "portrait" -> Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) && Settings.System.putInt(
            resolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_0,
        )
        "landscape" -> Settings.System.putInt(
            resolver,
            Settings.System.ACCELEROMETER_ROTATION,
            0,
        ) && Settings.System.putInt(
            resolver,
            Settings.System.USER_ROTATION,
            Surface.ROTATION_90,
        )
        else -> return toolError("mode는 auto, portrait, landscape 중 하나여야 해요.")
    }
    if (!changed) return toolError("화면 회전 설정을 바꾸지 못했어요. 시스템 설정 변경 권한을 확인해 주세요.")
    return ToolResult("화면 회전을 $mode 모드로 바꿨어요.")
}

private fun AndroidTools.openSettings(context: Context, arguments: JsonObject): ToolResult {
    val key = arguments.text("settings")?.trim()?.lowercase() ?: ""
    val plan = settingsPlan(key, context.packageName)
        ?: return toolError(
            "지원하지 않는 설정 항목이에요: $key. ${SETTINGS_KEYS.joinToString(", ")} 중 하나를 쓰세요.",
        )
    return startOrResult(plan, "설정 화면을 열었어요.")
}

private const val MAX_CLIPBOARD_TEXT = 20_000
