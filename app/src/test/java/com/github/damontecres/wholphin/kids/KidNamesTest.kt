package com.github.damontecres.wholphin.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Contract section 2, as the TV form applies it. */
class KidNamesTest {
    @Test
    fun `kid names follow the website's rule`() {
        assertEquals("Emma", KidNames.sanitize("  Emma  "))
        assertEquals("Mary Jo", KidNames.sanitize("Mary   Jo"))
        assertEquals("Zoë", KidNames.sanitize("Zoë"))
        assertEquals("Bob", KidNames.sanitize("B<o>b!"))
        assertEquals("O'Neil-Smith Jr.", KidNames.sanitize("O'Neil-Smith Jr."))
        assertNull(KidNames.validOrNull("   "))
        assertNull(KidNames.validOrNull("!!!"))
        assertNull(KidNames.validOrNull("a".repeat(21)))
        assertEquals("a".repeat(20), KidNames.validOrNull("a".repeat(20)))
    }
}
