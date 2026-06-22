package com.stansful.sshvpnclient.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun `parses plain and v-prefixed versions`() {
        assertEquals(SemanticVersion(2, 1, 0), SemanticVersion.parse("2.1.0"))
        assertEquals(SemanticVersion(2, 1, 0), SemanticVersion.parse("v2.1.0"))
    }

    @Test
    fun `compares patch minor and major versions`() {
        assertTrue(SemanticVersion.parse("2.1.1")!! > SemanticVersion.parse("2.1.0")!!)
        assertTrue(SemanticVersion.parse("2.2.0")!! > SemanticVersion.parse("2.1.9")!!)
        assertTrue(SemanticVersion.parse("3.0.0")!! > SemanticVersion.parse("2.9.0")!!)
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(SemanticVersion.parse("2.1"))
        assertNull(SemanticVersion.parse("release-2.1.0"))
        assertNull(SemanticVersion.parse("2.01.0"))
    }
}
