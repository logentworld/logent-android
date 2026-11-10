package com.sleepysoong.logent.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import com.sleepysoong.logent.plugin.PluginHost
import com.sleepysoong.logent.plugin.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * 플러그인이 Android 프레임워크와 만나는 유일한 지점이에요.
 *
 * 도구 구현은 `AndroidTools` 확장 함수로 나누고, 여기서는 Context·권한·Intent 실행처럼
 * 모든 도구가 함께 쓰는 동작만 다뤄요. 실패는 빈 성공으로 감추지 않고 해요체 오류로 돌려줘요.
 */
internal class AndroidTools(private val hostProvider: () -> PluginHost?) {
    @Volatile
    private var torchCameraId: String? = null

    @Volatile
    private var torchEnabled: Boolean = false

    internal fun hostOrNull(): PluginHost? = hostProvider()

    internal fun contextOrNull(): Context? = hostProvider()?.androidContext as? Context

    internal fun requireContext(): Context = contextOrNull()
        ?: throw IllegalStateException("Android 호스트에 연결되지 않았어요. Logent Android 앱에서 실행해 주세요.")

    internal fun missingHost(): ToolResult = ToolResult(
        "Android 호스트에 연결되지 않았어요. Logent Android 앱에서 실행해 주세요.",
        isError = true,
    )

    internal fun isPermissionGranted(permission: String): Boolean =
        contextOrNull()?.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 런타임 권한을 실행 시점에 확인하고, 없으면 호스트 화면에서 요청해요.
     * 거부되면 기능별 설정 안내를 오류로 돌려줘요.
     */
    internal suspend fun ensurePermission(permission: String, guidance: String): ToolResult? {
        val context = contextOrNull() ?: return missingHost()
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return null
        val host = hostOrNull() ?: return missingHost()
        host.requestAndroidPermission(permission)
        if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return null
        return ToolResult(
            "${permissionLabel(permission)} 권한이 필요해요. $guidance",
            isError = true,
        )
    }

    /** WRITE_SETTINGS는 런타임 권한이 아니라 특수 권한이라 별도 안내가 필요해요. */
    internal fun canWriteSystemSettings(): Boolean {
        val context = contextOrNull() ?: return false
        return Settings.System.canWrite(context)
    }

    internal fun writeSettingsDenied(): ToolResult = ToolResult(
        "시스템 설정 변경 권한이 없어 이 값을 바꿀 수 없어요. " +
            "설정 → 앱 → 특수 권한 → 시스템 설정 변경에서 Logent를 허용해 주세요. " +
            "(android.settings.action.MANAGE_WRITE_SETTINGS)",
        isError = true,
    )

    /**
     * 계획을 실제 Intent로 만들어 실행해요. 실행에 성공하면 null, 실패하면 오류를 돌려줘요.
     * Application Context에서도 열리도록 NEW_TASK를 붙여요.
     */
    internal fun start(plan: IntentPlan): ToolResult? = startIntent(plan.toIntent(), plan.action)

    internal fun startIntent(intent: Intent, label: String): ToolResult? {
        val context = contextOrNull() ?: return missingHost()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            null
        } catch (error: ActivityNotFoundException) {
            ToolResult(
                "이 기기에서 해당 화면을 열 수 있는 앱을 찾지 못했어요. ($label)",
                isError = true,
            )
        } catch (error: SecurityException) {
            ToolResult(
                "Android 보안 정책이 이 화면 열기를 막았어요. ${error.message ?: label}",
                isError = true,
            )
        }
    }

    internal fun startOrResult(plan: IntentPlan, successMessage: String): ToolResult =
        start(plan) ?: ToolResult(successMessage)

    internal fun torchState(): Pair<String?, Boolean> = torchCameraId to torchEnabled

    internal fun rememberTorch(cameraId: String, enabled: Boolean) {
        torchCameraId = cameraId
        torchEnabled = enabled
    }

    /**
     * Android 호출에서 나온 예외를 사용자에게 보여 줄 오류로 바꿔요.
     * 취소 예외는 삼키지 않고 그대로 전파해요.
     */
    internal suspend fun safe(block: suspend () -> ToolResult): ToolResult = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            ToolResult(
                "Android 보안 정책이 이 동작을 막았어요. 권한·기기 정책을 확인해 주세요. " +
                    (error.message ?: ""),
                isError = true,
            )
        } catch (error: Exception) {
            ToolResult(
                "Android 기능을 실행하지 못했어요. " + (error.message ?: error.javaClass.simpleName),
                isError = true,
            )
        }
    }
}

internal fun toolError(message: String): ToolResult = ToolResult(message, isError = true)

private fun IntentPlan.toIntent(): Intent {
    val intent = Intent(action)
    if (data != null && type != null) {
        intent.setDataAndType(Uri.parse(data), type)
    } else {
        data?.let { intent.data = Uri.parse(it) }
        type?.let { intent.type = it }
    }
    packageName?.let { intent.setPackage(it) }
    categories.forEach { intent.addCategory(it) }
    extras.forEach { extra ->
        when (extra) {
            is IntentExtra.Text -> intent.putExtra(extra.key, extra.value)
            is IntentExtra.Number -> intent.putExtra(extra.key, extra.value)
            is IntentExtra.Bool -> intent.putExtra(extra.key, extra.value)
        }
    }
    return intent
}
