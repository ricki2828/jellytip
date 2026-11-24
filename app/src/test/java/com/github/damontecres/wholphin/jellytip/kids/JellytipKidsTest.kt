package com.github.damontecres.wholphin.jellytip.kids

import io.mockk.every
import io.mockk.mockk
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one kid rule (same as the server's iptv/kids.py KidsPolicy.from_jellyfin): MaxParentalRating set (0 counts) OR BlockedTags
 * non-empty OR BlockUnratedItems non-empty; folder restrictions alone and an unknown policy are adults.
 */
class JellytipKidsTest {
    @Test
    fun ratingZeroIsAKid() {
        // 0 = a G cap. A truthiness check (`if (rating)`) would call this an adult.
        assertTrue(JellytipKids.isKid(0, null, null))
        assertTrue(JellytipKids.isKid(0, emptyList<String>(), emptyList<String>()))
        assertTrue(JellytipKids.isKid(13, null, null))
    }

    @Test
    fun blockedTagsMakeAKid() {
        assertTrue(JellytipKids.isKid(null, listOf("scary"), null))
        assertFalse(JellytipKids.isKid(null, emptyList<String>(), null))
    }

    @Test
    fun blockUnratedItemsIsAListOfItemTypes() {
        assertTrue(JellytipKids.isKid(null, null, listOf("Movie")))
        assertTrue(JellytipKids.isKid(null, null, listOf("Movie", "Series")))
        assertFalse(JellytipKids.isKid(null, null, emptyList<String>()))
    }

    @Test
    fun folderRestrictionAloneIsAnAdult() {
        // EnableAllFolders=false is not on the rule at all: with no rating, tags or unrated block the user is an adult
        assertFalse(JellytipKids.isKid(null, null, null))
    }

    @Test
    fun unknownPolicyIsNotKidsShell() {
        assertFalse(JellytipKids.isKid(null as UserPolicy?))
    }

    @Test
    fun policyFieldsAreRead() {
        fun policy(
            rating: Int?,
            tags: List<String>?,
            unrated: List<org.jellyfin.sdk.model.api.UnratedItem>?,
        ) = mockk<UserPolicy> {
            every { maxParentalRating } returns rating
            every { blockedTags } returns tags
            every { blockUnratedItems } returns unrated
        }
        assertTrue(JellytipKids.isKid(policy(0, null, null)))
        assertTrue(JellytipKids.isKid(policy(null, listOf("x"), null)))
        assertTrue(JellytipKids.isKid(policy(null, null, listOf(org.jellyfin.sdk.model.api.UnratedItem.MOVIE))))
        assertFalse(JellytipKids.isKid(policy(null, emptyList(), emptyList())))
    }

    @Test
    fun sectionStateStartsAtHomeAndResets() {
        KidsSectionState.set(KidsSection.MOVIES)
        assertEquals(KidsSection.MOVIES, KidsSectionState.flow.value)
        KidsSectionState.reset()
        assertEquals(KidsSection.HOME, KidsSectionState.flow.value)
    }
}
