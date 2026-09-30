package io.legado.app.core.ui.morph

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMorphPrivacyTest {
    @Test
    fun redactedSourceNeverRegistersSensitiveCoverOrBadges() {
        val key = "book-cover:privacy-registry"
        try {
            BookCoverMorphAnchors.report(
                key = key,
                bounds = bounds,
                cornerRadiusPx = 4f,
                bookName = "private title",
                author = "private author",
                coverPath = "private-cover.jpg",
                sourceOrigin = "private-source",
                bookUrl = "private-book",
                badgeText = "123",
                showBadgeDot = true,
                leftBottomText = "private tag",
                isRedacted = true,
            )

            val anchor = requireNotNull(BookCoverMorphAnchors.get(key))
            assertTrue(anchor.isRedacted)
            assertEquals(bounds, anchor.bounds)
            assertNull(anchor.bookName)
            assertNull(anchor.author)
            assertNull(anchor.coverPath)
            assertNull(anchor.sourceOrigin)
            assertNull(anchor.bookUrl)
            assertNull(anchor.badgeText)
            assertFalse(anchor.showBadgeDot)
            assertNull(anchor.leftBottomText)
        } finally {
            BookCoverMorphAnchors.remove(key)
        }
    }

    @Test
    fun redactedAnchorUsesOnlyFadeFromFirstFrameThroughReturn() = runTest {
        val state = newState()
        state.setAnchor(privateAnchor)
        state.reportCoverEnd(Rect(50f, 100f, 250f, 380f), 4f)
        assertRedacted(state)

        for (progress in listOf(0f, 0.05f, 0.5f, 1f)) {
            state.progress.snapTo(progress)
            assertEquals(progress, state.veil, 0.001f)
            assertEquals(0f, state.coverAlpha, 0.001f)
            assertEquals(0f, state.badgeAlpha, 0.001f)
            assertNull(state.panelFrame())
            assertNull(state.coverFrame())
        }
        state.onPredictiveBackStart()
        state.progress.snapTo(0.1f)
        assertRedacted(state)
        assertEquals(0f, state.coverAlpha, 0.001f)
        assertEquals(0f, state.badgeAlpha, 0.001f)
    }

    @Test
    fun refreshToPrivateAnchorClearsExistingCoverAndCannotUnlockThisTransition() {
        val state = newState()
        val publicAnchor = privateAnchor.copy(isRedacted = false)
        state.setAnchor(publicAnchor)
        assertEquals("private-cover.jpg", state.coverPath)

        state.updateAnchor(privateAnchor)
        assertRedacted(state)
        state.updateAnchor(publicAnchor)
        assertRedacted(state)
    }

    @Test
    fun fadeOnlyPrivateTransitionLeavesMaskedSourceVisible() = runTest {
        val key = "book-cover:privacy-origin"
        val state = newState()
        state.setAnchor(privateAnchor, key)
        try {
            BookCoverMorphAnchors.setActiveMorph(key, state)
            state.progress.snapTo(0.5f)
            assertFalse(BookCoverMorphAnchors.isOriginCoverHidden(key))
            state.progress.snapTo(1f)
            assertFalse(BookCoverMorphAnchors.isOriginCoverHidden(key))
        } finally {
            BookCoverMorphAnchors.clearActiveMorph(key)
        }
    }

    private fun newState() = BookMorphState(
        Animatable(0f),
        Density(1f),
        hasTargetCover = true,
    ).apply { reportScreenBounds(Rect(0f, 0f, 1080f, 2400f)) }

    private fun assertRedacted(state: BookMorphState) {
        assertTrue(state.isRedacted)
        assertTrue(state.fadeOnlyOpening)
        assertNull(state.bookName)
        assertNull(state.author)
        assertNull(state.coverPath)
        assertNull(state.sourceOrigin)
        assertNull(state.bookUrl)
        assertNull(state.badgeText)
        assertFalse(state.showBadgeDot)
        assertNull(state.leftBottomText)
    }

    private val bounds = Rect(100f, 200f, 300f, 480f)
    private val privateAnchor = BookCoverMorphAnchor(
        bounds = bounds,
        cornerRadiusPx = 4f,
        bookName = "private title",
        author = "private author",
        coverPath = "private-cover.jpg",
        sourceOrigin = "private-source",
        bookUrl = "private-book",
        badgeText = "123",
        showBadgeDot = true,
        leftBottomText = "private tag",
        isRedacted = true,
    )
}
