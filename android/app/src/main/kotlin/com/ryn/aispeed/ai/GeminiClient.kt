package com.ryn.aispeed.ai

import android.content.Context
import android.util.Base64
import com.ryn.aispeed.core.security.KeystoreKeyManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ChatTurn(val fromUser: Boolean, val text: String)

class GeminiException(val httpCode: Int, message: String) : Exception(message)

/** Plain REST client for the Gemini API. The key travels in a header, never in the URL or in logs. */
class GeminiClient(private val apiKey: String, private val model: String) {

    suspend fun generate(history: List<ChatTurn>, systemPrompt: String): String = withContext(Dispatchers.IO) {
        require(MODEL_REGEX.matches(model)) { "اسم النموذج غير صالح" }
        val contents = JSONArray()
        history.takeLast(MAX_TURNS).forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.fromUser) "user" else "model")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
            )
        }
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw GeminiException(code, extractError(text))
            parseReply(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun extractError(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    } catch (e: Exception) {
        ""
    }

    private fun parseReply(body: String): String {
        val json = JSONObject(body)
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
        val sb = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        val reply = sb.toString().trim()
        if (reply.isNotEmpty()) return reply
        val blocked = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        return if (blocked.isNotEmpty()) "تم حجب هذا الطلب من الخدمة ($blocked)." else "لم يصل رد من النموذج."
    }

    companion object {
        private const val MAX_TURNS = 24
        private val MODEL_REGEX = Regex("^[A-Za-z0-9._-]{1,64}$")
        const val DEFAULT_MODEL = "gemini-flash-latest"

        /** Turns any failure into a short Arabic message the user can act on. */
        fun friendlyError(e: Throwable): String = when {
            e is GeminiException && (e.httpCode == 400 || e.httpCode == 401 || e.httpCode == 403) ->
                "مفتاح Gemini غير صالح أو ليس له صلاحية. تأكد منه من زر ⚙"
            e is GeminiException && e.httpCode == 404 -> "اسم النموذج غير موجود. غيّره من زر ⚙"
            e is GeminiException && e.httpCode == 429 -> "وصلت للحد المجاني حالياً. جرّب بعد قليل"
            e is GeminiException -> "خطأ من الخدمة (${e.httpCode})"
            e is IOException -> "تعذّر الاتصال بالإنترنت"
            e is CancellationException -> throw e
            else -> "حدث خطأ غير متوقع"
        }
    }
}

/** Small encrypted key/value store (Android Keystore AES-GCM) for secrets such as the API key. */
class SecureStringStore(context: Context, private val keys: KeystoreKeyManager) {
    private val prefs = context.applicationContext.getSharedPreferences("ryn_secure_strings", Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return try {
            String(keys.unseal(Base64.decode(encoded, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun put(name: String, value: String) {
        val sealed = Base64.encodeToString(keys.seal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(name, sealed).commit()
    }

    fun remove(name: String) {
        prefs.edit().remove(name).commit()
    }
}
