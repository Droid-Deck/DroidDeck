package com.droiddeck.launcher.stores.xbox

import android.content.Context
import org.json.JSONObject

data class XboxCloudSettings(
    val resolution: String = "1080p",
    val region: String = "",
    val language: String = "",
    val codec: String = "default",
    val bitrateMbps: Int = 0,
    val preventDrops: Boolean = false,
    val statsLevel: Int = 0,
    val statsLayout: String = "horizontal",
    val statsCorner: String = "right",
    val volume: Int = 100,
    val rumble: Int = 100,
    val maxFps: Int = 0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val gamma: Float = 1f,
    val saturation: Int = 100,
    val sharpen: Int = 0,
    val sharpenMode: String = "cas",
    val deband: Int = 0,
    val fxaa: Boolean = false,
    val toon: Boolean = false,
    val crt: Boolean = false,
    val ntsc: Boolean = false,
    val playerEngine: String = "web",
    val frameGen: String = "ddfg",
    val fpsLimit: Int = 0,
    val fpsCustomOn: Boolean = false,
    val fpsCustom: Int = 60,
    val instantInput: Boolean = true,
    val smallBuffer: Boolean = false,
    val fastDecoder: Boolean = true,
    val zeroCopy: Boolean = false,
) {
    val fpsTarget get() = if (fpsCustomOn) fpsCustom else fpsLimit

    val osName get() = when (resolution) { "720p" -> "android"; "1080p_hq" -> "tizen"; else -> "windows" }

    fun toJson(): String = JSONObject()
        .put("resolution", resolution).put("region", region).put("language", language).put("codec", codec)
        .put("bitrateMbps", bitrateMbps).put("preventDrops", preventDrops).put("statsLevel", statsLevel).put("statsLayout", statsLayout).put("statsCorner", statsCorner)
        .put("volume", volume).put("rumble", rumble).put("maxFps", maxFps).put("brightness", brightness).put("contrast", contrast)
        .put("gamma", gamma.toDouble()).put("saturation", saturation).put("sharpen", sharpen).put("sharpenMode", sharpenMode)
        .put("deband", deband).put("fxaa", fxaa).put("toon", toon).put("crt", crt).put("ntsc", ntsc)
        .put("playerEngine", playerEngine).put("frameGen", frameGen)
        .put("fpsLimit", fpsLimit).put("fpsCustomOn", fpsCustomOn).put("fpsCustom", fpsCustom)
        .put("instantInput", instantInput).put("smallBuffer", smallBuffer).put("fastDecoder", fastDecoder).put("zeroCopy", zeroCopy)
        .toString()

    fun withoutEffects() = copy(brightness = 0, contrast = 0, gamma = 1f, saturation = 100, sharpen = 0, sharpenMode = "cas",
        deband = 0, fxaa = false, toon = false, crt = false, ntsc = false)

    companion object {
        private const val PREFS = "xbox_cloud"
        private const val KEY = "settings"

        fun load(context: Context): XboxCloudSettings =
            decode(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

        internal fun decode(text: String?): XboxCloudSettings = runCatching {
            val json = JSONObject(text ?: return XboxCloudSettings())
            val d = XboxCloudSettings()
            XboxCloudSettings(
                resolution = json.optString("resolution", d.resolution).takeIf { it in setOf("720p", "1080p", "1080p_hq") } ?: d.resolution,
                region = json.optString("region", d.region).take(64), language = json.optString("language", d.language).take(16),
                codec = json.optString("codec", d.codec).takeIf { it in setOf("default", "high", "normal", "low") } ?: d.codec,
                bitrateMbps = json.optInt("bitrateMbps", d.bitrateMbps).coerceIn(0, 100),
                preventDrops = json.optBoolean("preventDrops", d.preventDrops),
                statsLevel = json.optInt("statsLevel", d.statsLevel).coerceIn(0, 4),
                statsLayout = json.optString("statsLayout", d.statsLayout).takeIf { it == "vertical" } ?: "horizontal",
                statsCorner = json.optString("statsCorner", d.statsCorner).takeIf { it == "left" } ?: "right",
                volume = json.optInt("volume", d.volume).coerceIn(100, 300), rumble = json.optInt("rumble", d.rumble).coerceIn(0, 100),
                maxFps = json.optInt("maxFps", d.maxFps).coerceIn(0, 120),
                brightness = json.optInt("brightness", d.brightness).coerceIn(-50, 50), contrast = json.optInt("contrast", d.contrast).coerceIn(-100, 100),
                gamma = json.optDouble("gamma", 1.0).toFloat().coerceIn(0.5f, 3f), saturation = json.optInt("saturation", d.saturation).coerceIn(0, 200),
                sharpen = json.optInt("sharpen", d.sharpen).coerceIn(0, 10),
                sharpenMode = json.optString("sharpenMode", d.sharpenMode).takeIf { it == "usm" } ?: "cas",
                deband = json.optInt("deband", d.deband).coerceIn(0, 8),
                fxaa = json.optBoolean("fxaa"), toon = json.optBoolean("toon"), crt = json.optBoolean("crt"), ntsc = json.optBoolean("ntsc"),
                playerEngine = json.optString("playerEngine", d.playerEngine).takeIf { it == "native" } ?: "web",
                frameGen = json.optString("frameGen", d.frameGen).let { if (it == "winfg") "ddfg" else it }
                    .takeIf { it in setOf("off", "ddfg", "lsfg", "lsfg3", "lsfg4") } ?: d.frameGen,
                fpsLimit = json.optInt("fpsLimit", d.fpsLimit).takeIf { it in setOf(0, 30, 45, 60, 120, 144, 165) } ?: 0,
                fpsCustomOn = json.optBoolean("fpsCustomOn", d.fpsCustomOn),
                fpsCustom = json.optInt("fpsCustom", d.fpsCustom).coerceIn(10, 165),
                instantInput = json.optBoolean("instantInput", d.instantInput), smallBuffer = json.optBoolean("smallBuffer", d.smallBuffer),
                fastDecoder = json.optBoolean("fastDecoder", d.fastDecoder), zeroCopy = json.optBoolean("zeroCopy", d.zeroCopy),
            )
        }.getOrDefault(XboxCloudSettings())

        fun save(context: Context, settings: XboxCloudSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, settings.toJson()).apply()
        }

        val LANGUAGES = listOf("" to "Device language", "en-US" to "English (US)", "en-GB" to "English (UK)", "de-DE" to "Deutsch",
            "es-ES" to "Español (España)", "es-MX" to "Español (México)", "fr-FR" to "Français", "it-IT" to "Italiano",
            "ja-JP" to "日本語", "ko-KR" to "한국어", "nl-NL" to "Nederlands", "pl-PL" to "Polski", "pt-BR" to "Português (Brasil)",
            "pt-PT" to "Português (Portugal)", "ru-RU" to "Русский", "sv-SE" to "Svenska", "tr-TR" to "Türkçe", "zh-CN" to "中文 (简体)",
            "zh-TW" to "中文 (繁體)")
    }
}
