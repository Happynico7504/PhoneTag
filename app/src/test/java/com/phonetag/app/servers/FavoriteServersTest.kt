package com.phonetag.app.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavoriteServersTest {
    @Test
    fun normalizesAddresses() {
        assertEquals("play.example.com:47474", FavoriteServers.normalize(" Play.Example.com "))
        assertEquals("10.0.0.5:9000", FavoriteServers.normalize("10.0.0.5:9000"))
        assertNull(FavoriteServers.normalize(""))
        assertNull(FavoriteServers.normalize("host:notaport"))
        assertNull(FavoriteServers.normalize("host:70000"))
        assertNull(FavoriteServers.normalize("two words"))
    }

    @Test
    fun addDedupesRenamesAndKeepsNewestFirst() {
        var list = FavoriteServers.add(emptyList(), "", "a.example.com")
        list = FavoriteServers.add(list, "Club", "b.example.com:5000")
        assertEquals(listOf("Club", "a.example.com"), list.map { it.name })
        // Same server, different spelling: renamed and moved to the top, not duplicated.
        list = FavoriteServers.add(list, "Home", "A.EXAMPLE.COM:47474")
        assertEquals(listOf(FavoriteServer("Home", "a.example.com:47474"), FavoriteServer("Club", "b.example.com:5000")), list)
        // Invalid addresses are ignored.
        assertEquals(list, FavoriteServers.add(list, "Bad", "nope:x"))
        list = FavoriteServers.remove(list, "b.example.com:5000")
        assertEquals(listOf("Home"), list.map { it.name })
    }

    @Test
    fun capsTheListAndRoundTripsThroughStorage() {
        var list = emptyList<FavoriteServer>()
        repeat(30) { list = FavoriteServers.add(list, "S$it", "s$it.example.com") }
        assertEquals(FavoriteServers.MAX, list.size)
        assertEquals("S29", list.first().name)
        assertEquals(list, FavoriteServers.decode(FavoriteServers.encode(list)))
        assertEquals(emptyList<FavoriteServer>(), FavoriteServers.decode(null))
        // Corrupt lines are skipped.
        assertEquals(listOf(FavoriteServer("ok", "ok.example.com:47474")), FavoriteServers.decode("garbage\nok\tok.example.com\n\tbad host"))
    }
}
