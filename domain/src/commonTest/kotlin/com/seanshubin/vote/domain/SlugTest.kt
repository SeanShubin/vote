package com.seanshubin.vote.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SlugTest {

    @Test
    fun `real candidate names from the election this was built for`() {
        assertEquals("favorite-rippaverse-book", Slug.of("Favorite Rippaverse Book"))
        assertEquals("zalen-what-kills-must-die", Slug.of("Zalen: What Kills Must Die"))
        assertEquals("the-horseman-welcome-to-florespark", Slug.of("The Horseman: Welcome To Florespark"))
        assertEquals("norfrica-glorious-death-1", Slug.of("Norfrica: Glorious Death 1"))
        assertEquals("goodyng-the-polymath", Slug.of("Goodyng: The Polymath"))
        assertEquals("isom-3", Slug.of("Isom 3"))
        assertEquals("bad-mr-frosty", Slug.of("Bad Mr. Frosty"))
        assertEquals("sean", Slug.of("Sean"))
    }

    @Test
    fun `runs of separators collapse to one dash`() {
        assertEquals("a-b", Slug.of("a    b"))
        assertEquals("a-b", Slug.of("a :-- b"))
        assertEquals("the-great-war-of-separation-1", Slug.of("The  Great   War of Separation 1"))
    }

    @Test
    fun `leading and trailing punctuation leaves no stray dash`() {
        // A trailing dash would make the reviewer segment ambiguous to parse,
        // since the checksum is split off the final dash.
        assertEquals("sean", Slug.of("  Sean  "))
        assertEquals("sean", Slug.of("...Sean!!!"))
        assertEquals("sean", Slug.of("-Sean-"))
        assertEquals("isom-1", Slug.of("  Isom 1.  "))
    }

    @Test
    fun `case is folded`() {
        assertEquals("isom", Slug.of("ISOM"))
        assertEquals("isom", Slug.of("iSoM"))
    }

    @Test
    fun `digits are kept`() {
        assertEquals("isom-2", Slug.of("Isom 2"))
        assertEquals("2026", Slug.of("2026"))
    }

    @Test
    fun `a name that slugs to nothing gets a non-empty placeholder`() {
        // Validation permits a name of pure punctuation. An empty segment
        // would put "//" in the path and break parsing, so the segment must
        // stay non-empty even when it carries no information — the checksum
        // is what resolves such a name.
        assertEquals(Slug.EMPTY_PLACEHOLDER, Slug.of("???"))
        assertEquals(Slug.EMPTY_PLACEHOLDER, Slug.of("..."))
        assertEquals(Slug.EMPTY_PLACEHOLDER, Slug.of("   "))
        assertEquals(Slug.EMPTY_PLACEHOLDER, Slug.of(""))
        assertTrue(Slug.EMPTY_PLACEHOLDER.isNotEmpty())
    }

    @Test
    fun `non-ascii names fall back rather than emit bytes that vary by hop`() {
        // Deliberate: a script outside ASCII would need percent-encoding in
        // the path, which is the trap this whole scheme exists to avoid. Such
        // a name resolves on its checksum instead.
        assertEquals(Slug.EMPTY_PLACEHOLDER, Slug.of("日本語"))
        // "caf", not "cafe": the accented letter becomes a separator, and a
        // trailing separator is dropped. Worth stating outright, since
        // "Café" looks like it should keep four letters.
        assertEquals("caf", Slug.of("Café"), "ascii letters around it are still kept")
    }

    @Test
    fun `output is always safe in a url path`() {
        listOf(
            "Favorite Rippaverse Book",
            "Zalen: What Kills Must Die",
            "a/b?c#d&e=f%g",
            "  ",
            "???",
            "Yaira 3",
        ).forEach { name ->
            val slug = Slug.of(name)
            assertTrue(slug.isNotEmpty(), "empty slug for '$name'")
            assertTrue(
                slug.all { it in 'a'..'z' || it in '0'..'9' || it == '-' },
                "unsafe slug '$slug' for '$name'",
            )
            assertTrue(!slug.startsWith("-") && !slug.endsWith("-"), "stray dash in '$slug'")
        }
    }
}
