package com.dsharnessmobile.shell

import android.app.Activity
import android.webkit.PermissionRequest

/**
 * 麦克风授权的 WebView 宿主基类：在 [JsConfirmChromeClient] 之上只补 `onPermissionRequest`。
 *
 * 为什么单独成文件、而不是把重写塞进 `JsConfirmChromeClient`：
 *   ① 那个基类被两个宿主共用（`MainActivity` 主页面、`BrowserHost` 隔离浏览器），而麦克风只对
 *      「引擎页面」有意义；把它做成**可选**的派生类，就能让「必须显式给出顶层 URL 来源」成为
 *      构造前提——漏传的宿主根本拿不到这个能力，比默认放行安全得多；
 *   ② 两个宿主文件都很大，`docs/AGENTS/EXECUTION-MAP.md` 上钉着一批 `文件:行号` 锚点。在
 *      `JsConfirmChromeClient` 里插行会让那批锚点集体漂移（`check-code-map` 实测），本文件
 *      把新增全部收在自己身上，宿主侧只改**父类名**（行数不变）。
 *
 * 策略不在本类里：是否放行、要不要申请系统权限、拒绝后怎么提示，全部交给
 * [WebViewMicrophoneGate]，这里只做转发。
 */
internal open class MicChromeClient(
  private val host: Activity,
  /** 顶层文档 URL 的只读来源；只用于同源判定，不构成「页面可信」的证据。 */
  private val pageUrl: () -> String?,
) : JsConfirmChromeClient(host) {

  override fun onPermissionRequest(request: PermissionRequest) {
    WebViewMicrophoneGate.onPermissionRequest(host, pageUrl(), request)
  }
}
