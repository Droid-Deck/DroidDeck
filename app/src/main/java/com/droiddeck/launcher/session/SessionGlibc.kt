package com.droiddeck.launcher.session

internal fun sessionGlibcTunables(systemVulkan: Boolean): String {
    val defaults = "glibc.pthread.rseq=0:glibc.malloc.top_pad=16777216"
    // libhybris-common can exhaust static TLS when loaded late by Steam/Zink.
    // glibc must reserve this space at process startup, before the ICD is loaded.
    return if (systemVulkan) "$defaults:glibc.rtld.optional_static_tls=32768" else defaults
}
