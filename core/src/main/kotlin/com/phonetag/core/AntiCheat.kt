package com.phonetag.core

/**
 * Things a modified client can do that the real app never does. Each carries a weight: one-off
 * glitches (an AR relocalisation jump, two shots bunched by network jitter) cost little, while
 * sustained abuse quickly adds up to a lockout.
 */
enum class Violation(val weight: Double, val description: String) {
    FIRE_RATE(0.5, "firing faster than the weapon allows"),
    SPEED(1.0, "moving impossibly fast"),
    TELEPORT(2.5, "teleporting"),
    UNTRACKED_SHOT(0.25, "firing without AR tracking"),
    FLOOD(0.05, "flooding the server"),
    MALFORMED(0.5, "sending malformed messages"),
    BAD_DATA(0.5, "sending impossible game data"),
}

/**
 * Per-connection strike counter. Strikes decay exponentially (half-life [halfLifeMs]), so only
 * repeated violations within a short time reach [threshold].
 */
class CheatMonitor(
    private val threshold: Double = 5.0,
    private val halfLifeMs: Long = 60_000,
) {
    private var score = 0.0
    private var lastMs = Long.MIN_VALUE
    private val contributions = HashMap<Violation, Double>()

    @get:Synchronized
    val currentScore get() = score

    /** Records a violation; returns true exactly when the score crosses the threshold. */
    @Synchronized
    fun report(v: Violation, nowMs: Long): Boolean {
        decayTo(nowMs)
        val wasBelow = score < threshold
        score += v.weight
        contributions[v] = (contributions[v] ?: 0.0) + v.weight
        return wasBelow && score >= threshold
    }

    /** The main reasons, for the kick message and the server log. */
    @Synchronized
    fun reasons(): String = contributions.entries.sortedByDescending { it.value }.take(2).joinToString(" and ") { it.key.description }

    private fun decayTo(nowMs: Long) {
        if (lastMs != Long.MIN_VALUE && nowMs > lastMs) {
            val factor = Math.pow(0.5, (nowMs - lastMs).toDouble() / halfLifeMs)
            score *= factor
            contributions.replaceAll { _, c -> c * factor }
        }
        lastMs = nowMs
    }
}

/** Temporary bans keyed by IP address and device ID. In memory: a server restart clears them. */
class Lockouts(private val clock: () -> Long = System::currentTimeMillis) {
    private val until = HashMap<String, Long>()

    @Synchronized
    fun lock(keys: Collection<String>, durationMs: Long) {
        val end = clock() + durationMs
        for (k in keys) until[k] = maxOf(until[k] ?: 0, end)
    }

    /** Milliseconds left on the longest lockout matching any of [keys], or 0 if none. */
    @Synchronized
    fun remainingMs(keys: Collection<String>): Long {
        val now = clock()
        until.values.removeIf { it <= now }
        return keys.maxOfOrNull { (until[it] ?: now) - now }?.coerceAtLeast(0) ?: 0
    }

    companion object {
        fun ipKey(ip: String) = "ip:$ip"
        fun deviceKey(id: String) = "dev:$id"
    }
}
