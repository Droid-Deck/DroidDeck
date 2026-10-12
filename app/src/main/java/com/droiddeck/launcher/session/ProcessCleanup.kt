package com.droiddeck.launcher.session

internal class ProcessCleanup(
    private val snapshot: () -> List<Entry>,
    private val current: (Int) -> Entry?,
    private val signal: (Int, Int) -> Unit,
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
) {
    data class Identity(val pid: Int, val started: Long)

    data class Entry(
        val identity: Identity,
        val parent: Int,
        val state: Char,
        val executable: String = "",
    ) {
        val alive get() = state != 'Z' && state != 'X'
    }

    data class Result(val stopped: Int, val remaining: List<Identity>) {
        val complete get() = remaining.isEmpty()
    }

    fun terminate(select: (List<Entry>) -> Collection<Entry>): Result {
        val started = now()
        val deadline = started + GRACE_MS + KILL_WAIT_MS
        val observed = HashSet<Identity>()
        val terminatedAt = HashMap<Identity, Long>()
        val killed = HashSet<Identity>()
        var emptyPasses = 0
        while (true) {
            val processes = snapshot()
            val targets = select(processes).filter { it.alive }.distinctBy { it.identity }
            observed.addAll(targets.map { it.identity })
            val live = processes.filter { it.alive }.mapTo(HashSet()) { it.identity }
            val stopped = observed.count { it !in live }
            if (targets.isEmpty()) {
                if (++emptyPasses >= 2) return Result(stopped, emptyList())
            } else {
                emptyPasses = 0
            }
            for (entry in targets) {
                val identity = entry.identity
                val termSentAt = terminatedAt[identity]
                if (termSentAt == null) {
                    // A stopped proot needs to run its --kill-on-exit cleanup before escalation.
                    send(identity, SIGCONT)
                    if (send(identity, SIGTERM)) terminatedAt[identity] = now()
                } else if (now() - termSentAt >= GRACE_MS && now() < deadline) {
                    if (killed.add(identity)) send(identity, SIGKILL)
                }
            }
            if (targets.isNotEmpty() && now() >= deadline) {
                return Result(stopped, targets.map { it.identity })
            }
            sleep(POLL_MS)
        }
    }

    private fun send(identity: Identity, value: Int): Boolean {
        val process = current(identity.pid) ?: return false
        if (process.identity != identity || !process.alive) return false
        signal(identity.pid, value)
        return true
    }

    companion object {
        const val GRACE_MS = 1200L
        const val KILL_WAIT_MS = 1200L
        private const val POLL_MS = 50L
        private const val SIGCONT = 18
        private const val SIGTERM = 15
        private const val SIGKILL = 9

        fun descendants(processes: List<Entry>, roots: Set<Identity>): Set<Identity> {
            val found = processes.filter { it.identity in roots }.mapTo(HashSet()) { it.identity }
            val children = processes.groupBy { it.parent }
            val queue = ArrayDeque(found)
            while (queue.isNotEmpty()) {
                for (child in children[queue.removeFirst().pid].orEmpty()) {
                    if (found.add(child.identity)) queue.add(child.identity)
                }
            }
            return found
        }
    }
}
