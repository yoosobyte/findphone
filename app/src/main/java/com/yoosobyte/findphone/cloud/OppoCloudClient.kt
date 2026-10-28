package com.yoosobyte.findphone.cloud

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class CloudDevice(
    val id: String,
    val name: String,
    val type: Int,
    val online: Boolean,
)

enum class CloudErrorType {
    AUTH_EXPIRED,
    NETWORK,
    FIND_PHONE_DISABLED,
    NO_DEVICE,
    NEED_VERIFY,
    SERVER,
    UNKNOWN,
}

sealed interface CloudResult<out T> {
    data class Success<T>(val value: T) : CloudResult<T>
    data class Failure(
        val type: CloudErrorType,
        val serverMessage: String? = null,
    ) : CloudResult<Nothing>
}

/**
 * OPPO 云服务「查找手机」的新接口（find.oppo.com）。
 *
 * 请求体使用 [OppoCrypto] 的加密信封；身份由两部分组成：
 * - `Cookie` 请求头中的云端会话；
 * - 请求 JSON 中的 `TOKENKEY` 和由它派生出来的 `ctoken`。
 *
 * 会话过期（code=8012）时网页端会先调用 `/console/web/v1/login` 续期，
 * 这里也做一次同样的重试。
 */
class OppoCloudClient {

    fun getDevices(session: OppoSession): CloudResult<List<CloudDevice>> {
        if (session.tokenKey.isBlank() || session.cookie.isBlank()) {
            return CloudResult.Failure(CloudErrorType.AUTH_EXPIRED)
        }

        val payload = JSONObject()
            .put("content", "baidu")
            .put("mapType", 3)

        // 网页端进入查找页时会先调 existUserDevices，服务端可能在这里下发查找会话 Cookie。
        val exist = callWithRelogin(PATH_EXIST_DEVICES, JSONObject(), session, webTicket = false)
        if (exist is ApiOutcome.Failure && exist.type == CloudErrorType.AUTH_EXPIRED) {
            return exist.toCloudFailure()
        }

        val primary = callWithRelogin(PATH_POST_DEVICES, payload, session, webTicket = false)
        if (primary is ApiOutcome.Failure && (
                primary.type == CloudErrorType.AUTH_EXPIRED ||
                    primary.type == CloudErrorType.NETWORK ||
                    primary.type == CloudErrorType.NEED_VERIFY
                )
        ) {
            return primary.toCloudFailure()
        }
        var devices = if (primary is ApiOutcome.Success) parseDevices(primary.json) else emptyList()

        if (devices.isEmpty()) {
            // 网页端首次进入列表时用 postUserDevices，后续轮询用 getUserDevices；
            // 两个接口在部分账号上返回内容不完全一致，互为兜底。
            val fallback = callWithRelogin(PATH_GET_DEVICES, payload, session, webTicket = false)
            if (fallback is ApiOutcome.Success) {
                devices = parseDevices(fallback.json)
            } else if (primary is ApiOutcome.Success ||
                (primary is ApiOutcome.Failure && primary.type != CloudErrorType.NO_DEVICE)
            ) {
                return (fallback as ApiOutcome.Failure).toCloudFailure()
            }
        }

        return when {
            devices.isNotEmpty() -> CloudResult.Success(devices)
            primary is ApiOutcome.Failure -> primary.toCloudFailure()
            else -> CloudResult.Failure(CloudErrorType.NO_DEVICE)
        }
    }

    fun sendRing(session: OppoSession, device: CloudDevice): CloudResult<Unit> {
        if (session.tokenKey.isBlank() || session.cookie.isBlank()) {
            return CloudResult.Failure(CloudErrorType.AUTH_EXPIRED)
        }
        if (device.id.isBlank()) {
            return CloudResult.Failure(CloudErrorType.NO_DEVICE)
        }

        val payload = JSONObject()
            .put("deviceId", device.id)
            .put("notificationType", NOTIFICATION_TYPE_SOUND)
            .put("content", "")
            .put("deviceType", device.type)
            .put("sceneId", SCENE_ID)

        val outcome = callWithRelogin(
            path = PATH_POST_NOTIFICATION,
            payload = payload,
            session = session,
            webTicket = true,
        )
        return when (outcome) {
            is ApiOutcome.Success -> CloudResult.Success(Unit)
            is ApiOutcome.Failure -> outcome.toCloudFailure()
        }
    }

    private fun callWithRelogin(
        path: String,
        payload: JSONObject,
        session: OppoSession,
        webTicket: Boolean,
    ): ApiOutcome {
        var outcome = call(path, payload, session, webTicket)
        val needsRelogin = outcome is ApiOutcome.Failure &&
            (outcome.code == "8012" || outcome.code == "8002")
        if (needsRelogin && path != PATH_LOGIN) {
            Log.i(TAG, "$path -> ${(outcome as ApiOutcome.Failure).code}, trying $PATH_LOGIN")
            val login = call(PATH_LOGIN, JSONObject(), session, webTicket = false)
            if (login is ApiOutcome.Success) {
                outcome = call(path, payload, session, webTicket)
            } else if (login is ApiOutcome.Failure && login.type == CloudErrorType.AUTH_EXPIRED) {
                return login
            }
        }
        return outcome
    }

    private fun call(
        path: String,
        payload: JSONObject,
        session: OppoSession,
        webTicket: Boolean,
    ): ApiOutcome {
        payload.put("TOKENKEY", session.tokenKey)
        payload.put("ctoken", OppoCrypto.ctoken(session.tokenKey))

        val envelope = try {
            OppoCrypto.seal(payload)
        } catch (error: Exception) {
            Log.e(TAG, "seal failed: ${error.javaClass.simpleName}", error)
            return ApiOutcome.Failure(CloudErrorType.UNKNOWN, "请求加密失败")
        }

        val response = post(path, envelope.body, session, webTicket)
            ?: return ApiOutcome.Failure(CloudErrorType.NETWORK)

        if (response.code in 401..403) {
            return ApiOutcome.Failure(CloudErrorType.AUTH_EXPIRED, code = response.code.toString())
        }
        if (response.code in 301..308) {
            return ApiOutcome.Failure(CloudErrorType.AUTH_EXPIRED, code = response.code.toString())
        }
        if (response.code !in 200..299) {
            val type = if (response.code in 500..599) {
                CloudErrorType.SERVER
            } else {
                CloudErrorType.UNKNOWN
            }
            return ApiOutcome.Failure(type, code = response.code.toString())
        }

        val text = response.body.trim()
        if (text.isEmpty()) {
            return ApiOutcome.Failure(CloudErrorType.SERVER, "云端返回空响应")
        }
        val payloadText = if (text.length >= 2 && text.first() == '"' && text.last() == '"') {
            text.substring(1, text.length - 1)
        } else {
            text
        }

        val json = try {
            if (payloadText.startsWith("{")) JSONObject(payloadText) else JSONObject(
                OppoCrypto.decrypt(payloadText, envelope.key, envelope.iv),
            )
        } catch (error: Exception) {
            Log.e(TAG, "decrypt/parse failed: ${error.javaClass.simpleName}", error)
            return ApiOutcome.Failure(CloudErrorType.SERVER, "云端响应解析失败")
        }

        val code = codeOf(json)
        val message = messageOf(json)
        if (code == "200" || code == "0" || (code.isEmpty() && json.has("data"))) {
            return ApiOutcome.Success(json)
        }

        val type = when (code) {
            "8002", "8010", "8012" -> CloudErrorType.AUTH_EXPIRED
            "8005" -> CloudErrorType.NO_DEVICE
            "8007", "8000" -> CloudErrorType.NEED_VERIFY
            "8015" -> CloudErrorType.FIND_PHONE_DISABLED
            "233" -> CloudErrorType.SERVER
            else -> {
                if (code.startsWith("5") || message?.contains("频繁") == true) {
                    CloudErrorType.SERVER
                } else {
                    CloudErrorType.UNKNOWN
                }
            }
        }
        return ApiOutcome.Failure(type, message, code)
    }

    private fun post(
        path: String,
        body: String,
        session: OppoSession,
        webTicket: Boolean,
    ): HttpResponse? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                doInput = true
                doOutput = true
                setRequestProperty("Accept", "application/json, text/plain, */*")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("OCLOUD-DIGITAL-ENVELOPE", "V1")
                if (webTicket) setRequestProperty("WEB_TICKET_VERSION", "3")
                setRequestProperty("Origin", CLOUD_ORIGIN)
                setRequestProperty("Referer", "$CLOUD_ORIGIN/")
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty(
                    "Cookie",
                    session.cookie.replace("\r", "").replace("\n", ""),
                )
            }

            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            val setCookies = connection.headerFields
                .filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
                .values
                .flatten()
                .filterNotNull()
            if (setCookies.isNotEmpty()) {
                session.cookie = CookieHeader.merge(session.cookie, setCookies)
                CookieHeader.value(session.cookie, "TOKENKEY")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { session.tokenKey = it }
            }

            Log.d(TAG, "$path -> HTTP $code, ${responseBody.length} bytes")
            HttpResponse(code, responseBody)
        } catch (error: IOException) {
            Log.w(TAG, "$path network error: ${error.javaClass.simpleName}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 用现有 Cookie 调一次 `/console/web/v1/login`，让服务端建立查找手机会话。
     * 授权阶段可以先调用它，避免第一次点铃铛时多一次 8012 往返。
     */
    fun warmUp(session: OppoSession): CloudResult<Unit> {
        if (session.tokenKey.isBlank() || session.cookie.isBlank()) {
            return CloudResult.Failure(CloudErrorType.AUTH_EXPIRED)
        }
        return when (val outcome = call(PATH_LOGIN, JSONObject(), session, webTicket = false)) {
            is ApiOutcome.Success -> CloudResult.Success(Unit)
            is ApiOutcome.Failure -> outcome.toCloudFailure()
        }
    }

    private fun parseDevices(json: JSONObject): List<CloudDevice> {
        val array = deviceArray(json) ?: return emptyList()
        val result = LinkedHashMap<String, CloudDevice>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("deviceId")
                .ifBlank { item.optString("id") }
                .ifBlank { item.optString("imei") }
            if (id.isBlank()) continue
            val name = item.optString("deviceName")
                .ifBlank { item.optString("phoneName") }
                .ifBlank { item.optString("nickName") }
                .ifBlank { item.optString("name") }
                .ifBlank { item.optString("model") }
            val type = item.optInt("deviceType", 1)
            val online = when (val onlineValue = item.opt("onlineStatus")) {
                is Number -> onlineValue.toInt() == 1
                is String -> onlineValue == "1" || onlineValue.equals("online", ignoreCase = true)
                else -> item.optBoolean("online", false) ||
                    item.optString("locationStatus").equals("online", ignoreCase = true)
            }
            result[id] = CloudDevice(id = id, name = name, type = type, online = online)
        }
        return result.values.toList()
    }

    private fun deviceArray(json: JSONObject): JSONArray? {
        json.optJSONArray("data")?.let { return it }
        json.optJSONArray("devices")?.let { return it }
        val data = json.optJSONObject("data") ?: return null
        data.optJSONArray("deviceList")?.let { return it }
        data.optJSONArray("devices")?.let { return it }
        data.optJSONArray("userDevices")?.let { return it }
        data.optJSONArray("list")?.let { return it }
        data.optJSONArray("data")?.let { return it }
        return null
    }

    private fun codeOf(json: JSONObject): String {
        val value = json.opt("code")
            ?: json.opt("errCode")
            ?: json.opt("errorCode")
            ?: return ""
        return value.toString()
    }

    private fun messageOf(json: JSONObject): String? {
        for (key in listOf("codeMsg", "errMsg", "errmsg", "message", "msg")) {
            val value = json.optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        val error = json.optJSONObject("error")
        return error?.optString("message")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private sealed class ApiOutcome {
        data class Success(val json: JSONObject) : ApiOutcome()
        data class Failure(
            val type: CloudErrorType,
            val message: String? = null,
            val code: String = "",
        ) : ApiOutcome() {
            fun toCloudFailure(): CloudResult.Failure =
                CloudResult.Failure(type, message)
        }
    }

    companion object {
        private const val TAG = "OppoCloudClient"
        private const val BASE_URL = "https://find.oppo.com"
        private const val CLOUD_ORIGIN = "https://cloud.oppo.com"

        private const val PATH_LOGIN = "/console/web/v1/login"
        private const val PATH_EXIST_DEVICES = "/console/web/v2/existUserDevices"
        private const val PATH_GET_DEVICES = "/console/web/v4/getUserDevices"
        private const val PATH_POST_DEVICES = "/console/web/v4/postUserDevices"
        private const val PATH_POST_NOTIFICATION = "/console/web/v1/postNotification"

        /** 网页端 FIND_SECOND_CHECK 的 sceneId（生产环境）。 */
        private const val SCENE_ID = "QVsudpCJq6VvtFQfMCcd"
        private const val NOTIFICATION_TYPE_SOUND = 15

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
