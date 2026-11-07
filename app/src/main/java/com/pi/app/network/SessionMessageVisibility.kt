package com.pi.app.network

/**
 * 会话快照里「哪条消息该渲染」的判据（纯 JVM，可单测）。
 *
 * 唯一目的：把 pi-web 的两条过滤规则从解析代码里抽出来，1:1 落到一个可验证的位置。
 * 这两条规则一旦漏掉，界面上会出现**用户根本没说过的话**：
 *  - 压缩摘要（compaction）被下发成 `role:"custom"`，摘要正文以 `## Goal` 开头，
 *    漏判就会在每一轮压缩后印一张标题只剩 "Goal" 的卡片；
 *  - 扩展的内部续跑指令（`auto-continue-after-compaction`）是 `display:false` 的
 *    custom 消息，漏判就会在每次压缩后印一张 "Continue the current task from the co…"。
 *
 * 权威依据（@agegr/pi-web）：
 * 1) 服务端构造 `context.messages` 时（`.next/server` 里的 buildContextEntries 映射）：
 *    - `case "message"` 里 `role === "system"` 直接 `return null`，系统提示不进消息流；
 *    - `case "compaction"` 下发成
 *      `{role:"custom", customType:"compaction", content:<summary>, display:true, details:{tokensBefore, firstKeptEntryId}}`；
 *    - `case "custom_message"` 原样透传 `display`。
 * 2) 客户端 MessageView：`rz(e){return "custom"===e.role && !e.display}`，命中即 `return null`；
 *    消息分发里没有 `system` 分支（`system` 一律渲染为 null）。
 *
 * 因此判据是「`display` 必须**显式为 true**」，而不是「没有显式为 false」——缺字段（undefined）
 * 在 JS 里是 falsy，pi-web 会隐藏；按 `!= false` 判断会把这类消息重新放出来。
 */
object SessionMessageVisibility {

    /**
     * 这条会话消息是否参与渲染。
     *
     * @param role 消息角色（快照里的 `message.role`）
     * @param display custom 消息的展示开关；非 custom 角色忽略
     */
    fun isVisible(role: String, display: Boolean?): Boolean {
        // pi-web 服务端不会把 system 消息放进 context.messages，客户端也没有 system 分支。
        if (role == "system") return false
        // pi-web MessageView 的 rz()：custom 且 display 非真 → 渲染 null。
        if (role == "custom" && display != true) return false
        return true
    }

    /**
     * 是否是 pi-web 服务端下发的压缩摘要视图模型（`role:"custom"` + `customType:"compaction"`）。
     *
     * 命中后应当走「压缩摘要卡片」（📦 标题 + 摘要正文），而不是通用注入消息卡片：
     * 后者的标题取正文首行，而压缩摘要首行恒为 Markdown 标题 `## Goal`。
     */
    fun isCompactionView(role: String, customType: String?): Boolean =
        role == "custom" && customType == "compaction"
}
