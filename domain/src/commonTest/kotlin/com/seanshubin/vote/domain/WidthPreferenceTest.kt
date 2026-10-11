package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidthPreferenceTest {

    @Test
    fun `only the wide value means wide`() {
        assertTrue(WidthPreference.isWide(WidthPreference.WIDE))
        assertFalse(WidthPreference.isWide(WidthPreference.FIXED))
    }

    @Test
    fun `anything unrecognized falls back to the current behavior`() {
        // A missing key, a value left by an older build, or a localStorage
        // read that threw and produced null all have to land on fixed width.
        // Guessing wide would move a reader's page for no reason they asked.
        assertFalse(WidthPreference.isWide(null))
        assertFalse(WidthPreference.isWide(""))
        assertFalse(WidthPreference.isWide("WIDE"))
        assertFalse(WidthPreference.isWide("full"))
    }

    @Test
    fun `what is stored is what is read back`() {
        listOf(true, false).forEach { wide ->
            assertEquals(wide, WidthPreference.isWide(WidthPreference.storedValue(wide)))
        }
    }

    @Test
    fun `the storage key is namespaced because the origin is shared`() {
        // The app and the shared review page are the same origin, so this key
        // sits in one localStorage alongside everything else the app keeps.
        assertTrue(WidthPreference.STORAGE_KEY.startsWith("vote."))
    }
}
