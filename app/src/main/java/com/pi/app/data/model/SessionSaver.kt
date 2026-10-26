package com.pi.app.data.model

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * [Session] 的 rememberSaveable Saver。
 *
 * 没有它的时候，`currentSession` 只是一个普通的 `remember { mutableStateOf(null) }`：
 * 切深色模式、调整系统字号、被系统回收进程后 Activity 重建，它回到 null，
 * 正在看的会话立刻被打回列表页，用户得重新点进去。
 *
 * Session 是纯扁平字段，用 listSaver 足够。数字一律按字符串存取，
 * 避免 Bundle 中转时的类型擦除（Int/Long/Double 装箱后强转容易踩坑）。
 */
private val sessionFieldsSaver: Saver<Session, Any> = listSaver(
    save = { session ->
        listOf(
            session.id,
            session.name ?: "",
            session.title ?: "",
            session.firstMessage ?: "",
            session.cwd ?: "",
            session.project ?: "",
            session.projectRoot ?: "",
            session.projectKey ?: "",
            session.branch ?: "",
            session.created ?: "",
            session.modified ?: "",
            session.messageCount.toString(),
            session.tokenUsage.toString(),
            session.cost.toString(),
            session.active.toString(),
            session.awaitingInput.toString()
        )
    },
    restore = { values ->
        fun text(index: Int): String = values.getOrNull(index) as? String ?: ""
        fun optional(index: Int): String? = text(index).ifEmpty { null }
        Session(
            id = text(0),
            name = optional(1),
            title = optional(2),
            firstMessage = optional(3),
            cwd = optional(4),
            project = optional(5),
            projectRoot = optional(6),
            projectKey = optional(7),
            branch = optional(8),
            created = optional(9),
            modified = optional(10),
            messageCount = text(11).toIntOrNull() ?: 0,
            tokenUsage = text(12).toLongOrNull() ?: 0L,
            cost = text(13).toDoubleOrNull() ?: 0.0,
            active = text(14).toBooleanStrictOrNull() ?: false,
            awaitingInput = text(15).toBooleanStrictOrNull() ?: false
        )
    }
)

/** 允许 null 的包装：空列表表示「当前没有打开任何会话」。 */
val SessionSaver: Saver<Session?, Any> = Saver(
    save = { session ->
        if (session == null) emptyList<Any>() else sessionFieldsSaver.save(session)
    },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val values = saved as? List<Any?> ?: return@Saver null
        if (values.isEmpty()) null else sessionFieldsSaver.restore(values.filterNotNull())
    }
)
