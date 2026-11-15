package com.dsharnessmobile.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 麦克风授权闸的**纯判定层**：资源面 + 同源面。
 *
 * 为什么只测纯函数：真正的结算（`PermissionRequest.grant/deny`）与系统授权框属于 JVM 单测够不到的
 * Android 面，本仓同类接线一律靠设备层验收；这里能双向钉住的是「谁会被放行」这条策略——
 * 而这条策略错了，就是第三方页面冒用本应用身份录音的直接通路。
 *
 * 资源常量用字面量：`PermissionRequest.RESOURCE_AUDIO` 的真实值是 Android API 的稳定字符串，
 * 单测引用它会引入 android.jar 桩依赖；改成字面量后这条用例还能反向证明「资源面只认麦克风」。
 */
class WebViewMicrophoneGateTest {

  private val audio = "android.webkit.resource.AUDIO_CAPTURE"
  private val video = "android.webkit.resource.VIDEO_CAPTURE"
  private val page = "http://127.0.0.1:4145/"

  private fun allowed(origin: String?, resources: List<String> = listOf(audio), pageUrl: String? = page) =
    WebViewMicrophoneGate.isAllowedRequest(pageUrl, resources, origin)

  @Test
  fun `same origin loopback audio only is allowed`() {
    assertTrue(allowed("http://127.0.0.1:4145"))
  }

  @Test
  fun `localhost page with localhost origin is allowed`() {
    assertTrue(allowed("http://localhost:4145", pageUrl = "http://localhost:4145/"))
  }

  @Test
  fun `camera is refused`() {
    assertFalse(allowed("http://127.0.0.1:4145", resources = listOf(video)))
  }

  @Test
  fun `mixed audio and camera is refused`() {
    assertFalse(allowed("http://127.0.0.1:4145", resources = listOf(audio, video)))
  }

  @Test
  fun `empty resource list is refused`() {
    assertFalse(allowed("http://127.0.0.1:4145", resources = emptyList()))
  }

  @Test
  fun `different port is refused`() {
    assertFalse(allowed("http://127.0.0.1:8090"))
  }

  @Test
  fun `external host is refused even when the page itself is loopback`() {
    assertFalse(allowed("https://evil.example:4145"))
  }

  @Test
  fun `non loopback page is refused`() {
    assertFalse(allowed("https://example.com:443", pageUrl = "https://example.com/"))
  }

  @Test
  fun `scheme mismatch is refused`() {
    assertFalse(allowed("https://127.0.0.1:4145"))
  }

  @Test
  fun `different loopback spelling is a different origin`() {
    assertFalse(allowed("http://localhost:4145"))
  }

  @Test
  fun `missing origin is refused`() {
    assertFalse(allowed(null))
  }

  @Test
  fun `missing page url is refused`() {
    assertFalse(allowed("http://127.0.0.1:4145", pageUrl = null))
  }

  @Test
  fun `default port equals explicit port`() {
    assertTrue(allowed("http://127.0.0.1", pageUrl = "http://127.0.0.1:80/"))
  }

  @Test
  fun `ipv6 loopback is recognised`() {
    assertTrue(WebViewMicrophoneGate.isLoopbackHost("::1"))
    assertTrue(WebViewMicrophoneGate.isLoopbackHost("[::1]"))
    assertTrue(WebViewMicrophoneGate.isLoopbackHost("LOCALHOST"))
  }

  @Test
  fun `lookalike hosts are not loopback`() {
    assertFalse(WebViewMicrophoneGate.isLoopbackHost("128.0.0.1"))
    assertFalse(WebViewMicrophoneGate.isLoopbackHost("127.0.0.1.evil.example"))
    assertFalse(WebViewMicrophoneGate.isLoopbackHost("127.0.0"))
    assertFalse(WebViewMicrophoneGate.isLoopbackHost("127.0.0.256"))
    assertFalse(WebViewMicrophoneGate.isLoopbackHost("localhost.evil.example"))
  }
}
