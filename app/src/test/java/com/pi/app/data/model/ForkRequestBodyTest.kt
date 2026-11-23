package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `POST /api/sessions/[id]/fork` 的请求体形状（对齐 pi-web 0.18.0）。
 *
 * 守两件事：
 * 1. 不带目标目录时仍然是 `{}` —— 服务端据此走「在当前目录分叉分支」；
 * 2. 带目标目录时**必须有** `cwd` 键：服务端是 `Object.hasOwn(body, "cwd")` 判定，
 *    塞 `null` 或空串会被走成 fork-into-cwd 再以 `cwd-invalid` 拒掉。
 */
class ForkRequestBodyTest {

    @Test
    fun `no target cwd keeps the branch fork request`() {
        assertEquals("{}", forkRequestBody(null))
        assertEquals("{}", forkRequestBody("   "))
    }

    @Test
    fun `a target cwd is trimmed and sent as its own key`() {
        assertEquals("""{"cwd":"D:\\work\\app"}""", forkRequestBody("  D:\\work\\app  "))
    }
}
