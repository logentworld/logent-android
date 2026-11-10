package com.sleepysoong.logent.android

import android.content.Context
import android.content.pm.ApplicationInfo
import com.sleepysoong.logent.plugin.ToolResult
import com.sleepysoong.logent.plugin.text
import com.sleepysoong.logent.plugin.integer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** `android_apps`의 구현이에요. 설치 앱 조회는 코어가 선언한 QUERY_ALL_PACKAGES에 기대요. */
internal suspend fun AndroidTools.apps(arguments: JsonObject): ToolResult = safe {
    val context = requireContext()
    when (val action = arguments.text("action")?.trim()?.lowercase()) {
        "list" -> listApps(context, arguments)
        "open" -> openApp(context, arguments)
        "info" -> appInfo(arguments)
        "uninstall" -> uninstallApp(context, arguments)
        "store" -> openStore(arguments)
        "search" -> searchStore(arguments)
        null, "" -> toolError(
            "action이 필요해요. list, open, info, uninstall, store, search 중 하나를 쓰세요.",
        )
        else -> toolError(
            "지원하지 않는 action이에요: $action. list, open, info, uninstall, store, search 중 하나를 쓰세요.",
        )
    }
}

private data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val system: Boolean,
)

@Suppress("DEPRECATION")
private fun AndroidTools.listApps(context: Context, arguments: JsonObject): ToolResult {
    val limit = (arguments.integer("limit") ?: DEFAULT_APP_LIMIT).coerceIn(1, MAX_APP_LIMIT)
    val manager = context.packageManager
    val entries = manager.getInstalledApplications(0).map { info ->
        val versionName = runCatching {
            manager.getPackageInfo(info.packageName, 0).versionName
        }.getOrNull()
        InstalledApp(
            packageName = info.packageName,
            label = manager.getApplicationLabel(info).toString(),
            versionName = versionName,
            system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
        )
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    val limited = entries.take(limit)
    val payload = buildJsonObject {
        put("total", entries.size)
        put("returned", limited.size)
        put("limit", limit)
        put(
            "apps",
            buildJsonArray {
                limited.forEach { app ->
                    add(
                        buildJsonObject {
                            put("package", app.packageName)
                            put("label", app.label)
                            if (app.versionName != null) put("versionName", app.versionName)
                            put("system", app.system)
                        },
                    )
                }
            },
        )
    }
    return ToolResult(payload.toString())
}

private fun AndroidTools.openApp(context: Context, arguments: JsonObject): ToolResult {
    val packageName = arguments.text("package")?.trim()
        ?: return toolError("package가 필요해요.")
    if (!isValidPackageName(packageName)) return toolError("패키지 이름 형식이 올바르지 않아요.")
    val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        ?: return toolError(
            "실행할 수 있는 앱을 찾지 못했어요. 설치되어 있지 않거나 런치 액티비티가 없는 앱이에요.",
        )
    return startIntent(intent, packageName) ?: ToolResult("앱을 열었어요: $packageName")
}

private fun AndroidTools.appInfo(arguments: JsonObject): ToolResult {
    val packageName = arguments.text("package")?.trim()
        ?: return toolError("package가 필요해요.")
    if (!isValidPackageName(packageName)) return toolError("패키지 이름 형식이 올바르지 않아요.")
    return startOrResult(appDetailsPlan(packageName), "앱 설정 화면을 열었어요: $packageName")
}

private fun AndroidTools.uninstallApp(context: Context, arguments: JsonObject): ToolResult {
    val packageName = arguments.text("package")?.trim()
        ?: return toolError("package가 필요해요.")
    if (!isValidPackageName(packageName)) return toolError("패키지 이름 형식이 올바르지 않아요.")
    val installed = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
    }.isSuccess
    if (!installed) return toolError("설치된 앱을 찾지 못했어요: $packageName")
    return startOrResult(
        uninstallPlan(packageName),
        "제거 확인 화면을 열었어요. 실제 제거는 사용자가 확인해야 해요.",
    )
}

private fun AndroidTools.openStore(arguments: JsonObject): ToolResult {
    val packageName = arguments.text("package")?.trim()
        ?: return toolError("package가 필요해요.")
    if (!isValidPackageName(packageName)) return toolError("패키지 이름 형식이 올바르지 않아요.")
    val market = start(marketDetailsPlan(packageName))
    if (market == null) return ToolResult("Play 스토어의 앱 페이지를 열었어요: $packageName")
    val web = start(
        IntentPlan(
            action = AndroidIntents.ACTION_VIEW,
            data = playStoreDetailsUrl(packageName),
        ),
    )
    return if (web == null) {
        ToolResult("Play 스토어 앱이 없어 웹 페이지를 열었어요: $packageName")
    } else {
        toolError("Play 스토어를 열지 못했어요. ${web.text}")
    }
}

private fun AndroidTools.searchStore(arguments: JsonObject): ToolResult {
    val query = arguments.text("query")?.trim()
        ?: return toolError("query가 필요해요.")
    if (query.isEmpty()) return toolError("query가 필요해요.")
    if (query.length > 200) return toolError("query는 200자 이하여야 해요.")
    val market = start(marketSearchPlan(query))
    if (market == null) return ToolResult("Play 스토어에서 \"$query\"를 검색했어요.")
    val web = start(
        IntentPlan(
            action = AndroidIntents.ACTION_VIEW,
            data = playStoreSearchUrl(query),
        ),
    )
    return if (web == null) {
        ToolResult("Play 스토어 앱이 없어 웹 검색 페이지를 열었어요: $query")
    } else {
        toolError("Play 스토어를 열지 못했어요. ${web.text}")
    }
}

private const val DEFAULT_APP_LIMIT = 50
private const val MAX_APP_LIMIT = 200
