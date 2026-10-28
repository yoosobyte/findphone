package com.yoosobyte.findphone.cloud

import org.json.JSONObject

/**
 * 登录 OPPO 云服务后，查找手机接口需要的东西其实只有两样：
 *
 * 1. 浏览器 Cookie（`Cookie` 请求头），其中包含云端会话；
 * 2. `TOKENKEY` Cookie 的值。
 *
 * `ctoken` 不需要单独保存，它由 `TOKENKEY` 用网页端同一套 djb2 变体算出来
 * （见 [OppoCrypto.ctoken]）。
 */
data class OppoSession(
    var cookie: String,
    var tokenKey: String,
) {

    fun toStorage(): String = JSONObject()
        .put(KEY_VERSION, 1)
        .put(KEY_COOKIE, cookie)
        .put(KEY_TOKEN_KEY, tokenKey)
        .toString()

    companion object {
        private const val KEY_VERSION = "v"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_TOKEN_KEY = "tokenKey"

        fun fromStorage(raw: String?): OppoSession? {
            if (raw.isNullOrBlank()) return null
            val text = raw.trim()
            if (text.startsWith("{")) {
                return try {
                    val json = JSONObject(text)
                    val cookie = json.optString(KEY_COOKIE)
                    val tokenKey = json.optString(KEY_TOKEN_KEY)
                        .ifBlank { CookieHeader.value(cookie, "TOKENKEY").orEmpty() }
                    if (tokenKey.isBlank()) null else OppoSession(cookie, tokenKey)
                } catch (_: Exception) {
                    null
                }
            }
            // 兼容旧版本直接保存的 Cookie 字符串。
            val tokenKey = CookieHeader.value(text, "TOKENKEY").orEmpty()
            return if (tokenKey.isBlank()) null else OppoSession(text, tokenKey)
        }
    }
}

/** Cookie 字符串的读取与合并。 */
internal object CookieHeader {

    fun value(cookie: String, name: String): String? {
        if (cookie.isBlank()) return null
        for (part in cookie.split(';')) {
            val item = part.trim()
            val index = item.indexOf('=')
            if (index <= 0) continue
            if (!item.substring(0, index).trim().equals(name, ignoreCase = true)) continue
            return item.substring(index + 1)
                .trim()
                .removeSurrounding("\"")
                .let { decode(it) }
        }
        return null
    }

    /** 把多个来源的 Cookie 合并成一条，同名 Cookie 后出现的覆盖先出现的。 */
    fun mergeAll(vararg cookies: String?): String {
        val merged = LinkedHashMap<String, String>()
        for (cookie in cookies) {
            if (cookie.isNullOrBlank()) continue
            for (part in cookie.split(';')) {
                val item = part.trim()
                if (item.isEmpty()) continue
                val index = item.indexOf('=')
                if (index <= 0) continue
                val name = item.substring(0, index).trim()
                val value = item.substring(index + 1).trim()
                if (name.isEmpty() || value.isEmpty()) continue
                merged[name] = value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 把响应里的 Set-Cookie 合并进现有 Cookie。
     * 只关心 name=value 和失效（空值 / Max-Age=0），忽略 domain / path / SameSite。
     */
    fun merge(existing: String, setCookieHeaders: List<String>): String {
        if (setCookieHeaders.isEmpty()) return existing
        val jar = LinkedHashMap<String, String>()
        for (part in existing.split(';')) {
            val item = part.trim()
            val index = item.indexOf('=')
            if (index <= 0) continue
            jar[item.substring(0, index).trim()] = item.substring(index + 1).trim()
        }
        for (header in setCookieHeaders) {
            if (header.isBlank()) continue
            val first = header.substringBefore(';')
            val index = first.indexOf('=')
            if (index <= 0) continue
            val name = first.substring(0, index).trim()
            val value = first.substring(index + 1).trim()
            val expired = value.isEmpty() ||
                header.contains("Max-Age=0", ignoreCase = true) ||
                header.contains("max-age=0", ignoreCase = true)
            if (name.isEmpty()) continue
            if (expired) jar.remove(name) else jar[name] = value
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun decode(value: String): String {
        if (!value.contains('%')) return value
        return try {
            java.net.URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }
}
