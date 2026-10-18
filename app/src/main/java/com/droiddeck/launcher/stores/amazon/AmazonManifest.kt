package com.droiddeck.launcher.stores.amazon

import java.io.IOException

class ParsedManifest(@JvmField val totalInstallSize: Long, @JvmField val fileCount: Int)

object AmazonManifest {
    @JvmStatic
    @Throws(IOException::class)
    fun parse(data: ByteArray): ParsedManifest {
        val answer = AmazonNative.inspect(data) ?: throw IOException(AmazonNative.ENGINE_MISSING)
        if (answer.has("error")) throw IOException(answer.optString("error"))
        return ParsedManifest(answer.optLong("totalBytes", 0L), answer.optInt("files", 0))
    }
}
