package com.github.damontecres.wholphin.jellytip.me

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsRatingsTest {
    @Test
    fun ratingsToAge() {
        assertEquals(13, KidsRatings.age("PG-13")) // a rating, not a country prefix
        assertEquals(13, KidsRatings.age("NZ-M"))
        assertEquals(16, KidsRatings.age("nz-r16"))
        assertEquals(16, KidsRatings.age("GB-15"))
        assertEquals(0, KidsRatings.age("G"))
        assertEquals(7, KidsRatings.age("TV-PG"))
        assertEquals(18, KidsRatings.age(null))
        assertEquals(18, KidsRatings.age("Unrated"))
    }

    @Test
    fun allowedUpToTheLimit() {
        assertTrue(KidsRatings.allowed("PG", 7))
        assertFalse(KidsRatings.allowed("PG-13", 10))
        assertFalse(KidsRatings.allowed(null, 16))
    }
}
