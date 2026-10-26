package com.pi.app.data

import android.os.Build

/**
 * 网关地址的默认提示值。
 *
 * 这些只是「手动配置」输入框的占位符。此前同样的字符串散落在三处且互相不一致
 * （MainActivity / GlobalSettingsScreen 用 192.168.1.100:4100，
 * SessionListScreen 用 10.0.2.2:4100），真机用户照抄模拟器地址必然连不上。
 * 这里收口成一处，并按运行环境给出该环境下真正可用的地址。
 */
object ServerDefaults {

    /** 伴侣网关的默认监听端口，与 server/gateway.js 的 GATEWAY_PORT 默认值一致。 */
    const val DEFAULT_PORT = 4100

    /** Android 模拟器访问宿主机回环地址的特殊映射。 */
    private const val EMULATOR_LOOPBACK_HOST = "10.0.2.2"

    /** 局域网内 PC 的示例地址，仅作占位提示。 */
    private const val LAN_EXAMPLE_HOST = "192.168.1.100"

    /** 手动配置输入框的占位地址。 */
    val gatewayUrlHint: String
        get() = "http://${if (isEmulator()) EMULATOR_LOOPBACK_HOST else LAN_EXAMPLE_HOST}:$DEFAULT_PORT"

    private fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT
        if (fingerprint.startsWith("generic") || fingerprint.startsWith("unknown")) return true
        val model = Build.MODEL
        val product = Build.PRODUCT
        return model.contains("google_sdk") ||
            model.contains("Emulator") ||
            model.contains("Android SDK built for") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            product.contains("sdk_gphone") ||
            product.contains("vbox86p") ||
            product.contains("emulator")
    }
}
