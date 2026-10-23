package com.droiddeck.xrdiagnostics

class NativeBridge {
    external fun create(): Long
    external fun destroy(id: Long)
    external fun begin(id: Long, provider: Int, session: Long, origin: Long)
    external fun update(id: Long, session: Long, origin: Long, sampled: Long, now: Long,
        connected: Boolean, focused: Boolean, values: FloatArray, capabilities: Int): String
    external fun fixture(id: Long, step: Int, now: Long): String
    external fun tests(directory: String): String
    external fun roundTrip(id: Long, directory: String): Boolean
    companion object { init { System.loadLibrary("xrdiagnostics") } }
}
