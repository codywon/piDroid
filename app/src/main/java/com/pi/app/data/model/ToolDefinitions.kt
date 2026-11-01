package com.pi.app.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 一个工具参数的声明（取自 pi 的 `parameters.properties`）。 */
data class ToolParameter(
    val name: String,
    val type: String? = null,
    val required: Boolean = false,
    val description: String? = null
)

/**
 * 一条工具定义，对应 pi-web `POST /api/agent/{id}` 的 `{"type":"get_tools"}` 返回项
 * （上游 `rpc-manager` 的 `get_tools`：`ToolInfo` + `description` 覆写 + `active` + `declarationHidden`）。
 */
data class ToolDefinition(
    val name: String,
    val description: String = "",
    /** 本会话当前是否启用了它（`set_tools` 之后这里才是真相） */
    val active: Boolean = false,
    /**
     * 启用、可调用，但请求里不带它的声明：codemode 的 "only" 模式对内置工具就是这样，
     * 模型只能从脚本里调到它。UI 上要和「未启用」区分开，否则用户会以为设置没生效。
     */
    val declarationHidden: Boolean = false,
    /** `direct` / `deferred` / `codemode` / `hidden` */
    val exposure: String? = null,
    val parameters: List<ToolParameter> = emptyList()
) {
    val requiredParameters: List<ToolParameter> get() = parameters.filter { it.required }

    /** 参数摘要（pi-web `tools.parameterCount` / `tools.noParameters` 同口径）。 */
    val parameterSummary: String
        get() = if (parameters.isEmpty()) "无参数" else "${parameters.size} 个参数"
}

/**
 * `get_tools` 响应的解析。
 *
 * 线上形状是 `{success:true, data:[...]}`（RPC 包装）；`data` 也可能是顶层数组（老网关）。
 * 单独抽出来是因为它只能靠 CI 验证：一旦把包装层读错，面板会永远显示「没有工具」，
 * 而这看起来和「会话真的没启用工具」一模一样。
 */
object ToolDefinitionsParsing {

    /**
     * @return 解析成功返回工具列表（可能为空），响应不可解析时返回 null。
     */
    fun parse(body: String): List<ToolDefinition>? {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        val array = when {
            root.isJsonObject -> {
                val obj = root.asJsonObject
                // 服务端明确报错时不要把 error 当成空清单
                if (obj.has("error") && !obj.get("error").isJsonNull) return null
                obj.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            }
            root.isJsonArray -> root.asJsonArray
            else -> return null
        }
        return array.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.let(::toolFrom)
        }
    }

    private fun toolFrom(obj: JsonObject): ToolDefinition? {
        val name = obj.string("name") ?: return null
        val parametersObj = obj.get("parameters")?.takeIf { it.isJsonObject }?.asJsonObject
        val required = parametersObj?.get("required")
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
            ?.toSet()
            .orEmpty()
        val properties = parametersObj?.get("properties")?.takeIf { it.isJsonObject }?.asJsonObject
        val parameters = properties?.entrySet()?.map { (key, value) ->
            val spec = value.takeIf { it.isJsonObject }?.asJsonObject
            ToolParameter(
                name = key,
                type = spec?.string("type"),
                required = required.contains(key),
                description = spec?.string("description")
            )
        }.orEmpty()

        return ToolDefinition(
            name = name,
            description = obj.string("description").orEmpty(),
            active = obj.boolean("active") ?: false,
            declarationHidden = obj.boolean("declarationHidden") ?: false,
            exposure = obj.string("exposure"),
            parameters = parameters
        )
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    private fun JsonObject.boolean(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
}
