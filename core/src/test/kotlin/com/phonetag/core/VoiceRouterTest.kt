package com.phonetag.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceRouterTest {
    private val router = VoiceRouter()
    private var now = 0L

    private fun at(dist: Double) = trackedPose(0.0, dist)

    private fun step(ms: Long, vararg members: VoiceRouter.Member, mode: GameMode = GameMode.FREE_FOR_ALL) {
        val end = now + ms
        while (now < end) {
            now += 200
            router.update(members.toList(), mode, now)
        }
    }

    @Test
    fun linkOpensBeyondTenMetresAndClosesInstantlyWhenClose() {
        val a = VoiceRouter.Member(1, Team.NONE, at(0.0))
        // 30 m apart: link opens after the 1 s debounce.
        step(400, a, VoiceRouter.Member(2, Team.NONE, at(30.0)))
        assertFalse(router.canHear(1, 2))
        step(1_000, a, VoiceRouter.Member(2, Team.NONE, at(30.0)))
        assertTrue(router.canHear(1, 2))
        assertTrue(router.canHear(2, 1))
        assertEquals(listOf(RadioLink(1, 2)), router.links())

        // Jitter between 9.5 and 10.5 m keeps the current state.
        step(2_000, a, VoiceRouter.Member(2, Team.NONE, at(9.8)))
        assertTrue(router.canHear(1, 2))

        // Walking up within 9.5 m mutes immediately (feedback protection).
        step(200, a, VoiceRouter.Member(2, Team.NONE, at(6.0)))
        assertFalse(router.canHear(1, 2))
        step(5_000, a, VoiceRouter.Member(2, Team.NONE, at(10.2)))
        assertFalse(router.canHear(1, 2))
    }

    @Test
    fun briefExcursionsBeyondRangeDontOpenTheLink() {
        val a = VoiceRouter.Member(1, Team.NONE, at(0.0))
        repeat(10) {
            step(400, a, VoiceRouter.Member(2, Team.NONE, at(12.0)))  // GPS spike
            step(400, a, VoiceRouter.Member(2, Team.NONE, at(5.0)))
        }
        assertFalse(router.canHear(1, 2))
    }

    @Test
    fun teamsOnlyTalkToTeammatesAndUnknownPositionsStaySilent() {
        val red1 = VoiceRouter.Member(1, Team.RED, at(0.0))
        val red2 = VoiceRouter.Member(2, Team.RED, at(40.0))
        val blue = VoiceRouter.Member(3, Team.BLUE, at(20.0))
        val lost = VoiceRouter.Member(4, Team.RED, null)
        step(2_000, red1, red2, blue, lost, mode = GameMode.TEAMS)
        assertEquals(listOf(RadioLink(1, 2)), router.links())
    }

    @Test
    fun arenaDistanceIsHorizontal() {
        val a = Pose(0.0, 0.0, 3.0, 0.0, arena = ArenaPose(1, Vec3(0.0, 1.5, 0.0), Vec3.FORWARD))
        val b = Pose(0.0, 0.0, 3.0, 0.0, arena = ArenaPose(1, Vec3(6.0, 9.5, -8.0), Vec3.FORWARD))
        assertEquals(10.0, VoiceRouter.distanceM(a, b), 1e-9) // height difference ignored, GPS ignored
    }

    @Test
    fun lobbyForwardsVoiceOnlyOverOpenLinks() {
        var t = 1_000_000L
        val lobby = Lobby("VOICE", "v") { t }
        val inbox = mutableMapOf<Int, MutableList<ServerMessage>>()
        fun join(n: String) = mutableListOf<ServerMessage>().let { box ->
            (lobby.join(n, arTracking = true) { box += it } as Lobby.JoinResult.Joined).playerId.also { inbox[it] = box }
        }
        val a = join("a")
        val far = join("far")
        val near = join("near")
        repeat(10) {
            t += 200
            lobby.handle(a, ClientMessage.PoseUpdate(trackedPose(0.0, 0.0)))
            lobby.handle(far, ClientMessage.PoseUpdate(trackedPose(0.0, 25.0)))
            lobby.handle(near, ClientMessage.PoseUpdate(trackedPose(90.0, 4.0)))
            lobby.tick()
        }
        lobby.handle(a, ClientMessage.Voice(1, "AAAA"))
        assertEquals(listOf(ServerMessage.Voice(a, 1, "AAAA")), inbox.getValue(far).filterIsInstance<ServerMessage.Voice>())
        assertTrue(inbox.getValue(near).none { it is ServerMessage.Voice })
        assertTrue(inbox.getValue(a).none { it is ServerMessage.Voice }) // never echoed back

        val snap = inbox.getValue(a).filterIsInstance<ServerMessage.Snapshot>().last()
        assertEquals(listOf(VoiceRouter.link(a, far), VoiceRouter.link(far, near)), snap.radio)

        // Oversized frames are dropped.
        lobby.handle(a, ClientMessage.Voice(2, "A".repeat(Protocol.MAX_VOICE_CHARS + 1)))
        assertEquals(1, inbox.getValue(far).count { it is ServerMessage.Voice })
    }
}
