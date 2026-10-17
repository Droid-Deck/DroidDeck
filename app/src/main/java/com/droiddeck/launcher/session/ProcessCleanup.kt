package com.droiddeck.launcher.session

/**
 * proot's --kill-on-exit takes the guest tree down only if proot gets to run it, and
 * SIGKILL never lets it. A SIGKILLed proot left its tracees alive with no tracer: every
 * seccomp-trapped syscall then failed with ENOSYS, they spun on retries at a full core
 * for over an hour, and the reaper could not even open /proc to kill them. So: SIGTERM,
 * a grace for proot's own cleanup, SIGKILL only if it will not go, then verify the exits.
 * Each pid is checked against its start time before signalling so a number reused by a
 * new process is never touched; repeated snapshots also catch children created during cleanup.
 */
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
        val targets = LinkedHashSet<Identity>()
        val terminatedAt = HashMap<Identity, Long>()
        val killed = HashSet<Identity>()
        var emptyPasses = 0
        while (true) {
            val processes = snapshot()
            targets.addAll(select(processes).filter { it.alive }.map { it.identity })
            val liveTargets = processes.filter { it.alive && it.identity in targets }
            observed.addAll(liveTargets.map { it.identity })
            val live = processes.filter { it.alive }.mapTo(HashSet()) { it.identity }
            val stopped = observed.count { it !in live }
            if (liveTargets.isEmpty()) {
                if (++emptyPasses >= 2) return Result(stopped, emptyList())
            } else {
                emptyPasses = 0
            }
            for (entry in liveTargets) {
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
            if (liveTargets.isNotEmpty() && now() >= deadline) {
                return Result(stopped, liveTargets.map { it.identity })
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
        /** How long proot gets to run its own cleanup before it is killed outright. */
        const val GRACE_MS = 1200L
        const val KILL_WAIT_MS = 1200L
        private const val POLL_MS = 50L
        private const val SIGCONT = 18
        private const val SIGTERM = 15
        private const val SIGKILL = 9

        /** [roots] and every process below them, by each process's parent in a single /proc snapshot. */
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
