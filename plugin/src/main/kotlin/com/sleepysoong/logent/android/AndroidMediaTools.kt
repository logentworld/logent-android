package com.sleepysoong.logent.android

import android.content.ContentUris
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.MediaStore
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.integer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `android_media`의 구현이에요. MediaStore 조회는 Android 13 권한 분기를 따라요. */
internal suspend fun AndroidTools.media(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "images" -> listMedia(context, arguments, MediaKind.IMAGES)
        "videos" -> listMedia(context, arguments, MediaKind.VIDEOS)
        "audio" -> listMedia(context, arguments, MediaKind.AUDIO)
        "open_gallery" -> startOrResult(galleryPlan(), "갤러리를 열었어요.")
        "open_camera" -> startOrResult(cameraPlan(), "카메라 앱을 열었어요.")
        "play" -> playMedia(arguments)
        null, "" -> toolError(
            "action이 필요해요. images, videos, audio, open_gallery, open_camera, play 중 하나를 쓰세요.",
        )
        else -> toolError(
            "지원하지 않는 action이에요: $action. images, videos, audio, open_gallery, " +
                "open_camera, play 중 하나를 쓰세요.",
        )
    }
}

private suspend fun AndroidTools.listMedia(
    context: Context,
    arguments: JsonObject,
    kind: MediaKind,
): ToolResult {
    val permissions = mediaReadPermissions(Build.VERSION.SDK_INT, kind)
    val limit = (arguments.integer("limit") ?: DEFAULT_MEDIA_LIMIT).coerceIn(1, MAX_MEDIA_LIMIT)
    ensurePermission(
        permissions.single(),
        "설정 → 앱 → Logent → 권한에서 " +
            "${permissionLabel(permissions.single())}를 허용해 주세요.",
    )?.let { return it }
    val collection = when (kind) {
        MediaKind.IMAGES -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaKind.VIDEOS -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }
    val projection = arrayOf("_id", "_display_name", "_size", "mime_type", "date_added")
    val cursor = context.contentResolver.query(
        collection,
        projection,
        null,
        null,
        "date_added DESC",
    ) ?: return toolError("미디어 공급자에 질의하지 못했어요. 기기 정책을 확인해 주세요.")

    val results = buildJsonArray {
        cursor.use { rows ->
            val idIndex = rows.getColumnIndexOrThrow("_id")
            val nameIndex = rows.getColumnIndexOrThrow("_display_name")
            val sizeIndex = rows.getColumnIndexOrThrow("_size")
            val mimeIndex = rows.getColumnIndexOrThrow("mime_type")
            val dateIndex = rows.getColumnIndexOrThrow("date_added")
            var count = 0
            while (count < limit && rows.moveToNext()) {
                val id = rows.getLong(idIndex)
                val size = rows.getLong(sizeIndex)
                val addedSeconds = rows.getLong(dateIndex)
                val addedMs = addedSeconds * 1000
                add(
                    buildJsonObject {
                        put("name", rows.getString(nameIndex).orEmpty())
                        put("sizeBytes", size)
                        put("size", formatBytes(size))
                        put("mimeType", rows.getString(mimeIndex).orEmpty())
                        put("dateAdded", addedMs)
                        put("dateText", formatTimestamp(addedMs))
                        put("uri", ContentUris.withAppendedId(collection, id).toString())
                    },
                )
                count++
            }
        }
    }
    return ToolResult(
        buildJsonObject {
            put("kind", kind.name.lowercase())
            put("count", results.size)
            put("items", results)
        }.toString(),
    )
}

private fun AndroidTools.playMedia(arguments: JsonObject): ToolResult {
    val uri = arguments.text("uri")
    val plan = mediaViewPlan(uri)
        ?: return toolError(
            "content:// URI만 열 수 있어요. file:// 경로는 이 플러그인에 FileProvider가 없어 공유할 수 없어요.",
        )
    return startOrResult(plan, "미디어를 열었어요.")
}

/** `android_location`의 구현이에요. FusedLocationProvider 없이 마지막 위치만 읽어요. */
internal suspend fun AndroidTools.location(arguments: JsonObject): ToolResult = safe {
    requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "last" -> lastLocation()
        "open_settings" -> {
            val context = requireContext()
            startOrResult(
                settingsPlan("location", context.packageName)
                    ?: return@safe toolError("위치 설정 화면을 만들지 못했어요."),
                "위치 설정 화면을 열었어요.",
            )
        }
        null, "" -> toolError("action이 필요해요. last 또는 open_settings를 쓰세요.")
        else -> toolError("지원하지 않는 action이에요: $action. last 또는 open_settings를 쓰세요.")
    }
}

private suspend fun AndroidTools.ensureLocationPermission(): ToolResult? {
    if (isPermissionGranted(AndroidPermissions.ACCESS_FINE_LOCATION) ||
        isPermissionGranted(AndroidPermissions.ACCESS_COARSE_LOCATION)
    ) {
        return null
    }
    val host = hostOrNull() ?: return missingHost()
    host.requestAndroidPermission(AndroidPermissions.ACCESS_FINE_LOCATION)
    if (isPermissionGranted(AndroidPermissions.ACCESS_FINE_LOCATION) ||
        isPermissionGranted(AndroidPermissions.ACCESS_COARSE_LOCATION)
    ) {
        return null
    }
    host.requestAndroidPermission(AndroidPermissions.ACCESS_COARSE_LOCATION)
    if (isPermissionGranted(AndroidPermissions.ACCESS_FINE_LOCATION) ||
        isPermissionGranted(AndroidPermissions.ACCESS_COARSE_LOCATION)
    ) {
        return null
    }
    return toolError(
        "위치 권한이 필요해요. 설정 → 앱 → Logent → 권한에서 위치를 허용해 주세요.",
    )
}

private suspend fun AndroidTools.lastLocation(): ToolResult {
    ensureLocationPermission()?.let { return it }
    val context = requireContext()
    val manager = context.getSystemService(LocationManager::class.java)
        ?: return toolError("위치 서비스를 사용할 수 없어요.")
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )
    var best: Location? = null
    for (provider in providers) {
        val location = try {
            manager.getLastKnownLocation(provider)
        } catch (error: SecurityException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
        if (location == null) continue
        val previous = best
        if (previous == null || location.time > previous.time) best = location
    }
    val location = best ?: return toolError(
        "마지막으로 알려진 위치가 없어요. 위치 기록이 없거나 위치 서비스가 꺼져 있을 수 있어요. " +
            "지도 앱에서 위치를 한 번 갱신하거나 android_location action=open_settings로 위치 설정을 확인해 주세요.",
    )
    val now = System.currentTimeMillis()
    return ToolResult(
        buildJsonObject {
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("accuracyMeters", location.accuracy.toDouble())
            put("provider", location.provider.orEmpty())
            put("time", location.time)
            put("timeText", formatTimestamp(location.time))
            put("ageMs", (now - location.time).coerceAtLeast(0))
        }.toString(),
    )
}

private const val DEFAULT_MEDIA_LIMIT = 30
private const val MAX_MEDIA_LIMIT = 100
