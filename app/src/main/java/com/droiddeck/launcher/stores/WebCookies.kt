package com.droiddeck.launcher.stores

import android.net.Uri
import android.webkit.CookieManager

internal object WebCookies {
    fun expired(header: String?, domain: String, paths: List<String>, host: String? = null): List<String> {
        val domains = listOfNotNull(domain, host?.takeIf { it.endsWith(domain) })
        return header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()
            .flatMap { name ->
                paths.flatMap { path ->
                    listOf("$name=; Max-Age=0; Path=$path; Secure") + domains.map { "$name=; Max-Age=0; Path=$path; Secure; Domain=$it" }
                }
            }
    }

    fun expire(urls: List<String>, domain: String, paths: List<String>, done: (Int) -> Unit) {
        val cookies = CookieManager.getInstance()
        val lines = urls.flatMap { url -> expired(cookies.getCookie(url), domain, paths, Uri.parse(url).host).map { url to it } }
        if (lines.isEmpty()) {
            done(0)
            return
        }
        var left = lines.size
        for ((url, line) in lines) {
            cookies.setCookie(url, line) {
                if (--left == 0) {
                    cookies.flush()
                    done(lines.size)
                }
            }
        }
    }
}
