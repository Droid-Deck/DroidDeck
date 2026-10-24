package com.droiddeck.launcher.input

object DeckControls {
    const val L4 = 1
    const val R4 = 2
    const val L5 = 4
    const val R5 = 8
    private val controls = arrayOf(InputControl.L4, InputControl.R4, InputControl.L5, InputControl.R5,
        InputControl.LEFT_PAD, InputControl.RIGHT_PAD, InputControl.LEFT_CLICK, InputControl.RIGHT_CLICK)
    private val sources = ArrayList<Source>()
    private val physical = Source(InputRole.PHYSICAL)
    private var router: InputRouter? = null
    private val pads = ShortArray(4)
    private val pressure = ShortArray(2)
    private val touchOwners = arrayOfNulls<Source>(2)

    @Synchronized
    fun setRouter(next: InputRouter?) {
        if (router === next) return
        for (source in sources) source.closeRoutes()
        router = next
        for (source in sources) source.configure()
        publish()
    }

    @Synchronized
    fun source(role: InputRole) = Source(role)

    @Synchronized
    fun setGrip(bit: Int, down: Boolean) = physical.setGrip(bit, down)

    @Synchronized
    fun setPad(right: Boolean, touching: Boolean, x: Float = 0f, y: Float = 0f) = physical.setPad(right, touching, x, y)

    @Synchronized
    fun setClick(right: Boolean, down: Boolean) = physical.setClick(right, down)

    @Synchronized
    fun releaseAll() {
        for (source in sources) source.clear()
        publish()
    }

    @Synchronized
    fun suspend() {
        for (source in sources) source.suspend()
        publish()
    }

    @Synchronized
    fun refresh() {
        for (source in sources) source.route()
        publish()
    }

    class Source internal constructor(private val role: InputRole) {
        private val x = FloatArray(8)
        private val y = FloatArray(8)
        private val touched = BooleanArray(8)
        private var routes: Array<InputRouter.Channel>? = null
        internal var mask = 0
        internal val axes = ShortArray(4)

        internal fun configure() {
            routes = router?.let { r -> Array(8) { i ->
                r.channel(role, controls[i]).also { it.awaitNeutral(x[i], y[i], touched[i]) }
            } }
            mask = 0
            axes.fill(0)
        }

        private fun attach() {
            if (this !in sources) { sources.add(this); configure() }
        }

        fun setGrip(bit: Int, down: Boolean) = synchronized(DeckControls) {
            val at = when (bit) { L4 -> 0; R4 -> 1; L5 -> 2; R5 -> 3; else -> return@synchronized }
            update(at, if (down) 1f else 0f)
        }

        fun setPad(right: Boolean, touching: Boolean, x: Float = 0f, y: Float = 0f) = synchronized(DeckControls) {
            update(if (right) 5 else 4, if (touching) x else 0f, if (touching) y else 0f, touching)
        }

        fun setClick(right: Boolean, down: Boolean) = synchronized(DeckControls) {
            update(if (right) 7 else 6, if (down) 1f else 0f)
        }

        private fun update(at: Int, value: Float, second: Float = 0f, contact: Boolean = false) {
            val timing = InputTiming.begin()
            attach()
            x[at] = value; y[at] = second; touched[at] = contact
            route()
            publish()
            InputTiming.end(timing)
        }

        internal fun route() {
            mask = 0
            axes.fill(0)
            for (i in controls.indices) {
                val target = if (routes == null) controls[i] else routes!![i].update(x[i], y[i], touched[i]) ?: continue
                val at = controls.indexOf(target)
                if (at < 0) continue
                if (at == 4 || at == 5) {
                    if (touched[i]) {
                        mask = mask or (1 shl at)
                        axes[(at - 4) * 2] = axis(x[i]); axes[(at - 4) * 2 + 1] = axis(y[i])
                    }
                } else if (x[i] > 0.5f) mask = mask or (1 shl at)
            }
        }

        internal fun clear() {
            x.fill(0f); y.fill(0f); touched.fill(false); mask = 0; axes.fill(0)
            routes?.forEach { it.release() }
        }

        internal fun suspend() {
            mask = 0; axes.fill(0)
            routes?.forEach { it.suspend() }
        }

        fun release() = synchronized(DeckControls) { clear(); publish() }

        internal fun closeRoutes() { routes?.forEach { it.close() }; routes = null }

        fun close() = synchronized(DeckControls) {
            clear(); closeRoutes(); sources.remove(this); publish()
        }
    }

    private fun axis(value: Float) = (value.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()

    private fun publish() {
        var mask = 0
        pads.fill(0)
        for (source in sources) {
            mask = mask or source.mask
        }
        for (side in 0..1) {
            val contact = 16 shl side
            val owner = touchOwners[side]?.takeIf { it.mask and contact != 0 }
                ?: sources.firstOrNull { it.mask and contact != 0 }
            touchOwners[side] = owner
            if (owner == null) continue
            pads[side * 2] = owner.axes[side * 2]
            pads[side * 2 + 1] = owner.axes[side * 2 + 1]
        }
        pressure[0] = if (mask and 64 != 0) Short.MAX_VALUE else 0
        pressure[1] = if (mask and 128 != 0) Short.MAX_VALUE else 0
        FakeInputWriter.writeDeckControls(0, mask, pads, pressure)
    }
}
