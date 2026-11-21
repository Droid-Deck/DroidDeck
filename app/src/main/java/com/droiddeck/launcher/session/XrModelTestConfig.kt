package com.droiddeck.launcher.session

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import java.io.File
import java.net.URI
import org.json.JSONObject

internal data class XrModelTestConfig(val baseUrl: String, val request: String) {
    companion object {
        fun read(context: Context): XrModelTestConfig? {
            if (!BuildConfig.XR_MODEL_TESTING || BuildConfig.CI_BUILD) return null
            val directory = context.getExternalFilesDir(null) ?: return null
            val file = File(directory, "xr-model-test.json")
            if (!file.isFile) return null
            return runCatching {
                check(file.length() <= 4096)
                parse(file.readText())
            }.onFailure { Log.w("DroidDeckXR", "Invalid model test configuration", it) }.getOrNull()
        }

        internal fun parse(text: String): XrModelTestConfig {
            val json = JSONObject(text)
            val request = json.getString("request")
            require(request.matches(Regex("[A-Za-z0-9_-]{1,64}")))
            val base = URI(json.getString("baseUrl"))
            require(base.scheme == "http" && base.host == "127.0.0.1" && base.port in 1..65535 &&
                base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null &&
                base.rawPath.orEmpty() in listOf("", "/"))
            return XrModelTestConfig("http://127.0.0.1:${base.port}", request)
        }
    }
}
