package io.github.acedroidx.frp

/**
 * MeFrp / Cloudflare 启动参数解析。
 *
 * 为了让用户可以直接粘贴官方文档中的命令，这里既支持完整命令行，也支持只粘贴密钥。
 */
object LauncherArgs {

    private val MEFRP_TOKEN = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])""")
    private val CLOUDFLARE_TOKEN = Regex("""eyJ[A-Za-z0-9+/=_-]{20,}""")
    private val BASE64ISH = Regex("""[A-Za-z0-9+/=_-]{40,}""")
    private val PORT = Regex("""(?<![\w.-])(\d{1,6})(?![\w.-])""")

    // mefrpc 中的 `-p` 是快捷启动的隧道 Id（数字形式）
    data class MefrpArgs(val token: String, val port: String)

    private fun String.trimToken(): String = trim('\'', '"', '\\', ' ', '\t', '\r', '\n')

    /**
     * 解析 MeFrp 启动参数。
     *
     * 支持：
     *  - `./mefrpc -t <token> -p <port>`
     *  - `-t <token> -p <port>`
     *  - 仅粘贴 token 与 port（各占一行）
     */
    fun parseMefrp(text: String): MefrpArgs? {
        val parts = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        var token: String? = null
        var port: String? = null
        var index = 0
        while (index < parts.size) {
            when (parts[index]) {
                "-t", "--token" -> {
                    token = parts.getOrNull(index + 1)?.trimToken()
                    index += 2
                }

                "-p", "--port" -> {
                    port = parts.getOrNull(index + 1)?.trimToken()
                    index += 2
                }

                else -> index++
            }
        }

        if (token.isNullOrBlank()) {
            token = MEFRP_TOKEN.find(text)?.value
        }
        if (port.isNullOrBlank()) {
            // 去掉 token 后再寻找独立的数字，避免把 token 中的数字误判为端口
            val remaining = if (token != null) text.replace(token, " ") else text
            port = PORT.findAll(remaining)
                .map { it.value }
                .firstOrNull { it.toIntOrNull()?.let { value -> value in 1..999999 } == true }
        }
        if (token.isNullOrBlank() || port.isNullOrBlank()) {
            return null
        }
        return MefrpArgs(token, port)
    }

    /**
     * 从 `cloudflared service install <token>`、`cloudflared tunnel run --token <token>`
     * 或单独粘贴的 token 中提取 Cloudflare Tunnel token。
     */
    fun parseCloudflareToken(text: String): String? {
        val parts = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val flagIndex = parts.indexOfFirst { it == "--token" || it == "-t" }
        if (flagIndex >= 0) {
            val value = parts.getOrNull(flagIndex + 1)?.trimToken()
            if (!value.isNullOrBlank() && value.length >= 20) {
                return value
            }
        }

        CLOUDFLARE_TOKEN.find(text)?.let { return it.value }

        // 兜底：Cloudflare token 是较长的 base64 字符串
        return BASE64ISH.findAll(text)
            .map { it.value }
            .maxByOrNull { it.length }
    }
}
