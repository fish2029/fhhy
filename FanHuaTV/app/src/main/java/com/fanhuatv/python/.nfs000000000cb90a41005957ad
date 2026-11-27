package com.fanhuatv.python

/**
 * AI 修源：把出错的 py 脚本 + 报错信息发给 OpenAI 兼容接口，
 * 让 AI 返回修复后的完整脚本。失败返回 null，错误信息在 result.message。
 */
object AiSourceRepair {

    data class Result(val fixedScript: String?, val message: String)

    /**
     * @param url 接口地址，如 https://api.xxx.com/v1/chat/completions
     * @param key 接口密钥（Bearer）
     * @param model 模型名，空则用 gpt-3.5-turbo
     */
    fun repair(url: String, key: String, model: String, script: String, error: String): Result {
        if (url.isBlank() || key.isBlank()) return Result(null, "请先在「我的」页配置 AI 接口地址和密钥")
        val endpoint = if (url.contains("/chat/completions")) url
        else url.trimEnd('/') + "/chat/completions"
        val modelName = model.ifBlank { "gpt-3.5-turbo" }

        val systemPrompt = "你是资深的 TVBox/鱼壳 Python 源脚本开发者。" +
                "用户会给你一个有问题的 Python 源脚本和它的报错信息。" +
                "只输出修复后的完整脚本，不要任何解释、不要 markdown 代码块围栏。" +
                "保持脚本原有的接口约定（class Spider 或 def config/search/play）不变。"
        val userPrompt = "报错信息：\n${error.take(3000)}\n\n脚本内容：\n${script.take(60000)}"

        val body = com.google.gson.JsonObject().apply {
            addProperty("model", modelName)
            add("messages", com.google.gson.JsonParser.parseString(
                com.google.gson.Gson().toJson(listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userPrompt)
                ))
            ))
            addProperty("temperature", 0.2)
        }.toString()

        return runCatching {
            val conn = java.net.URL(endpoint).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val respText = if (conn.responseCode in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                val err = conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }
                return Result(null, "接口返回 ${conn.responseCode}：${err?.take(300) ?: "无详情"}")
            }
            val obj = com.google.gson.JsonParser.parseString(respText).asJsonObject
            val content = obj.getAsJsonArray("choices")?.get(0)?.asJsonObject
                ?.getAsJsonObject("message")?.get("content")?.asString
            if (content.isNullOrBlank()) return Result(null, "接口未返回修复内容")
            // 剥掉可能的 markdown 围栏
            val fixed = content.trim()
                .removePrefix("```python").removePrefix("```")
                .removeSuffix("```")
                .trim()
            if (!fixed.contains("class Spider") && !fixed.contains("def config") &&
                !fixed.contains("def search")) {
                return Result(null, "AI 返回的不是有效脚本：${fixed.take(200)}")
            }
            Result(fixed, "修复方案已生成")
        }.getOrElse { Result(null, "AI 调用失败：${it.message ?: "网络异常"}") }
    }
}
