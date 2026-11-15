package com.dsharnessmobile.shell

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.webkit.PermissionRequest
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.URI

/**
 * issue #0（本仓首个麦克风需求）：页面 `getUserMedia({audio:true})` 的壳侧授权闸。
 *
 * 缺陷形态：全壳没有任何 `onPermissionRequest` 实现，`WebChromeClient` 的默认实现对任何
 * 权限请求一律 `deny()`；AndroidManifest 里也没有 `RECORD_AUDIO`，系统设置页因此**不显示**
 * 麦克风开关，用户既看不到也点不到。两条加在一起，页面侧官方语音输入按钮与唤醒词插件
 * （都走 `getUserMedia` + `MediaRecorder`）拿到的永远是 `NotAllowedError`。
 *
 * 为什么单一入口：两个 WebView 宿主（`MainActivity` 主页面、`BrowserHost` 隔离浏览器）都改用
 * [MicChromeClient]，闸只在那个类里接一次，避免两处实现各自漂移。
 *
 * 三条必须守住的：
 *   ① 只放行**纯麦克风**请求：摄像头、混合资源、空资源一律 `deny()`（本应用不对页面开放摄像头）；
 *   ② 只放行**与顶层页面同源且为环回地址**的请求：跨源 iframe、外部站点、端口不同的页面都拒绝，
 *      否则一个第三方页面就能借本应用的身份打开麦克风；
 *   ③ `PermissionRequest` 只能**结算一次**，且页面可能在系统授权框还开着时导航离开——
 *      结算必须包 try/catch，失败不得升级成崩溃源（与 `JsConfirmChromeClient` 的 `JsResult` 同款约束）。
 */
internal object WebViewMicrophoneGate {

  /** 系统授权框的请求码。取值只要求非 0 且不与壳内其它请求码相撞。 */
  const val REQUEST_CODE = 4701

  private var pending: PermissionRequest? = null

  /**
   * `onPermissionRequest` 的结算入口。
   *
   * [pageUrl] 是顶层文档的 URL（主页面为 `trustedTopFrameUrl` 镜像，浏览器为当前标签页 URL）；
   * 它只用来判同源，**不**作为「这个页面可信」的证据——可信面仍由各自的 `isEngineSource` 守。
   */
  fun onPermissionRequest(activity: Activity, pageUrl: String?, request: PermissionRequest) {
    val allowed = isAllowedRequest(
      pageUrl = pageUrl,
      resources = request.resources?.toList() ?: emptyList(),
      origin = request.origin?.toString(),
    )
    if (!allowed) {
      settle(request, grant = false)
      return
    }
    if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      settle(request, grant = true)
      return
    }
    if (activity.isFinishing || activity.isDestroyed) {
      settle(request, grant = false)
      return
    }
    // 同一时刻只留一个待决请求：上一个还没结算就先拒绝它，避免结果回调把授权发给错误的页面。
    pending?.let { settle(it, grant = false) }
    pending = request
    try {
      ActivityCompat.requestPermissions(
        activity,
        arrayOf(Manifest.permission.RECORD_AUDIO),
        REQUEST_CODE,
      )
    } catch (t: Throwable) {
      // 申请本身抛错（Activity 已不可见等）：结算为拒绝，并把待决状态清干净。
      pending = null
      settle(request, grant = false)
    }
  }

  /**
   * `Activity.onRequestPermissionsResult` 的转发点。返回 true 表示本闸消费了这个结果。
   *
   * 只认自己的 [REQUEST_CODE]，其它请求码原样交回给 `super`，不改变壳内既有权限行为。
   */
  fun onRequestPermissionsResult(activity: Activity, requestCode: Int, grantResults: IntArray): Boolean {
    if (requestCode != REQUEST_CODE) return false
    val request = pending
    pending = null
    // 迟到/重复的结果：没有待决请求时什么都不做，不得凭空放行。
    if (request == null) return true
    val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
    settle(request, grant = granted)
    if (!granted) {
      // 拒绝后页面再点时，系统可能不再弹框（「不再询问」）：这里只提示，不自动跳设置页。
      Toast.makeText(
        activity,
        UiText.t(
          activity,
          "麦克风权限被拒绝，可在系统设置的应用权限里重新允许",
          "Microphone access denied. Allow it for this app in system settings.",
        ),
        Toast.LENGTH_LONG,
      ).show()
    }
    return true
  }

  /**
   * 纯函数：只按「资源面 + 同源面」判定，不碰 Android 类型，因此可在 JVM 单测里双向断言。
   *
   * 同源判据用 scheme、host、实际端口 三元组，端口缺省按 scheme 补（http 80 / https 443）——
   * 引擎监听的是动态端点，只比 host 会让任意端口的本机服务冒用身份。
   */
  internal fun isAllowedRequest(pageUrl: String?, resources: List<String>, origin: String?): Boolean {
    // ① 只接受纯麦克风。空资源、摄像头、混合请求一律拒绝。
    if (resources.isEmpty() || resources.any { it != PermissionRequest.RESOURCE_AUDIO_CAPTURE }) return false
    // ② 环回地址 + 与顶层页面同源。
    val page = parseLoopback(pageUrl) ?: return false
    val requesting = parseLoopback(origin) ?: return false
    if (page.scheme != requesting.scheme) return false
    if (page.host != requesting.host) return false
    return effectivePort(page) == effectivePort(requesting)
  }

  /** 解析并确认是环回地址；不是（或解析不了）就返回 null。 */
  private fun parseLoopback(raw: String?): URI? {
    if (raw.isNullOrBlank()) return null
    val uri = try {
      URI(raw)
    } catch (_: Exception) {
      return null
    }
    val host = uri.host?.lowercase() ?: return null
    return if (isLoopbackHost(host)) uri else null
  }

  /** `localhost` / `127.0.0.0/8` / `::1`。只认这三种形态，不做 DNS 解析。 */
  internal fun isLoopbackHost(host: String): Boolean {
    val h = host.trim().lowercase().trim('[', ']')
    if (h == "localhost" || h == "::1") return true
    val parts = h.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    return octets.all { it in 0..255 } && octets[0] == 127
  }

  private fun effectivePort(uri: URI): Int = when {
    uri.port >= 0 -> uri.port
    uri.scheme.equals("https", ignoreCase = true) -> 443
    uri.scheme.equals("http", ignoreCase = true) -> 80
    else -> -1
  }

  private fun settle(request: PermissionRequest, grant: Boolean) {
    try {
      if (grant) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else request.deny()
    } catch (t: Throwable) {
      // 页面已导航/销毁时结算会抛；这是页面的时序问题，不是壳的故障源。
    }
  }
}
