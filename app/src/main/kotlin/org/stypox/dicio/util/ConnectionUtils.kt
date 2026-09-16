package org.stypox.dicio.util

import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.stypox.dicio.di.sharedOkHttpClient
import org.stypox.dicio.util.ConnectionUtils.percentEncode

private const val SKILL_CALL_TIMEOUT_SECONDS = 45L

object ConnectionUtils {
    @Throws(IOException::class)
    fun getPage(
        url: String,
        headers: Map<String, String?>,
    ): String {
        val request = Request.Builder()
            .url(url)
            .apply {
                for ((key, value) in headers) {
                    if (value != null) header(key, value)
                }
            }
            .build()

        val call = sharedOkHttpClient.newCall(request)
        call.timeout().timeout(SKILL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 404) throw FileNotFoundException(url)
                throw IOException("HTTP ${response.code} ${response.message} while requesting $url")
            }
            response.body?.string() ?: throw IOException("Response body is missing for $url")
        }
    }

    @Throws(IOException::class)
    fun getPage(url: String): String {
        return getPage(url, emptyMap())
    }

    @Throws(IOException::class, JSONException::class)
    fun getPageJson(url: String): JSONObject {
        return JSONObject(getPage(url))
    }

    /**
     * Encodes [s] as `application/x-www-form-urlencoded` so that it can be used as a parameter in
     * URL query strings. Note: space will be encoded as `+` which makes sense for URL query strings
     * but not for the URL path, in that case use [percentEncode] instead.
     */
    fun urlEncode(s: String): String {
        return URLEncoder.encode(s, "utf8")
    }

    /**
     * Decodes [s] from `application/x-www-form-urlencoded`.
     */
    fun urlDecode(s: String): String {
        return URLDecoder.decode(s, "utf8")
    }

    /**
     * Percent-encodes [s] so that it can be used in a URL path. Note: space will be encoded as
     * `%20`.
     */
    fun percentEncode(s: String): String {
        return Uri.encode(s)
    }

    /**
     * Percent-decodes [s].
     */
    fun percentDecode(s: String): String {
        return Uri.decode(s)
    }
}
