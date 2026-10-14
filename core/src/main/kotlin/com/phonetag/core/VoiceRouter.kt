package com.phonetag.core

import kotlinx.serialization.Serializable

/** Two players who can currently hear each other over the radio (ids in ascending order). */
@Serializable
data class RadioLink(val a: Int, val b: Int)

/**
 * Proximity voice chat routing: the radio connects players who are **far apart** and cuts
 * players who are close, because those can hear each other directly and their phones would
 * otherwise feed back (speaker → nearby mic → speaker...).
 *
 * - A link closes immediately when a pair comes within [CLOSE_M].
 * - It opens once the pair has been beyond [OPEN_M] for [OPEN_DELAY_MS] (GPS jitter around the
 *   threshold must not make the radio flap).
 * - Unknown or stale positions mean no link: when in doubt, stay silent.
 * - In team mode only teammates are linked; in free-for-all, everyone.
 */
class VoiceRouter {
    data class Member(val id: Int, val team: Team, val pose: Pose?)

    private val links = HashSet<RadioLink>()
    private val farSince = HashMap<RadioLink, Long>()

    @Synchronized
    fun update(members: List<Member>, mode: GameMode, nowMs: Long) {
        val seen = HashSet<RadioLink>()
        for (i in members.indices) for (j in i + 1 until members.size) {
            val m = members[i]
            val n = members[j]
            val key = link(m.id, n.id)
            seen += key
            val eligible = mode != GameMode.TEAMS || (m.team == n.team && m.team != Team.NONE)
            val pm = m.pose
            val pn = n.pose
            if (!eligible || pm == null || pn == null) {
                links -= key
                farSince -= key
                continue
            }
            val d = distanceM(pm, pn)
            when {
                d <= CLOSE_M -> {
                    links -= key
                    farSince -= key
                }
                d >= OPEN_M -> {
                    val since = farSince.getOrPut(key) { nowMs }
                    if (nowMs - since >= OPEN_DELAY_MS) links += key
                }
                // Between the thresholds: keep whatever state the link is in.
            }
        }
        links.retainAll(seen)
        farSince.keys.retainAll(seen)
    }

    @Synchronized
    fun canHear(a: Int, b: Int) = link(a, b) in links

    @Synchronized
    fun links(): List<RadioLink> = links.sortedWith(compareBy({ it.a }, { it.b }))

    companion object {
        const val RANGE_M = 10.0
        const val OPEN_M = RANGE_M + 0.5
        const val CLOSE_M = RANGE_M - 0.5
        const val OPEN_DELAY_MS = 1_000L

        fun link(a: Int, b: Int) = if (a < b) RadioLink(a, b) else RadioLink(b, a)

        /** Horizontal distance: shared-arena positions when both have them, GPS otherwise. */
        fun distanceM(a: Pose, b: Pose): Double {
            val aa = a.arena
            val bb = b.arena
            if (aa != null && bb != null && aa.epoch == bb.epoch && aa.epoch != 0) {
                val v = bb.position - aa.position
                return kotlin.math.sqrt(v.x * v.x + v.z * v.z)
            }
            return Geo.distanceM(a.lat, a.lon, b.lat, b.lon)
        }
    }
}
