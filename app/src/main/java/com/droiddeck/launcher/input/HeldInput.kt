package com.droiddeck.launcher.input

class HeldInput(private val emit: (Int, Boolean) -> Unit) {
    private val owners = IntArray(768)
    private val ordered = arrayOfNulls<(Int, Boolean) -> Unit>(768)

    @Synchronized
    fun set(source: Int, code: Int, down: Boolean, orderedEmit: ((Int, Boolean) -> Unit)? = null) {
        if (code !in owners.indices) return
        val before = owners[code]
        val after = if (down) before or source else before and source.inv()
        owners[code] = after
        if ((before == 0) != (after == 0)) {
            if (after != 0) {
                ordered[code] = orderedEmit
                orderedEmit?.invoke(code, true) ?: emit(code, true)
            } else {
                val fn = ordered[code]
                ordered[code] = null
                fn?.invoke(code, false) ?: emit(code, false)
            }
        }
    }

    @Synchronized
    fun release(source: Int) {
        for (code in owners.indices) if (owners[code] and source != 0) set(source, code, false)
    }

    @Synchronized
    fun clear() {
        for (code in owners.indices) if (owners[code] != 0) {
            owners[code] = 0
            val fn = ordered[code]
            ordered[code] = null
            fn?.invoke(code, false) ?: emit(code, false)
        }
    }
}
