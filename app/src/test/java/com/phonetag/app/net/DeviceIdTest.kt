package com.phonetag.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdTest {
    @Test
    fun hashIsStableOpaqueAndAcceptedByTheServer() {
        val id = DeviceId.hash("9774d56d682e549c")
        assertEquals(id, DeviceId.hash("9774d56d682e549c"))
        assertNotEquals(id, DeviceId.hash("9774d56d682e549d"))
        assertTrue("raw id must not leak", "9774d56d682e549c" !in id)
        // Same shape the server's DEVICE_ID check accepts.
        assertTrue(Regex("[A-Za-z0-9_-]{16,128}").matches(id))
        assertEquals(32, id.length)
    }
}
