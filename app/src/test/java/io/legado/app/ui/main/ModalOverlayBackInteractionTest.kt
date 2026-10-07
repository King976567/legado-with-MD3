package io.legado.app.ui.main

import android.app.Application
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ModalOverlayBackInteractionTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun readerBackThenInfoPredictiveBackReturnsToEachEntryPointWithoutReplacingInfoLifecycleOwner() {
        val activity = composeRule.activity
        val info = MainRouteBookInfo("Book", "Author", "book-url")
        // Exercise real navigation without launching reader/database prefetch in this UI fixture.
        val reader = MainRouteReadBook(bookUrl = "book-url", chapterChanged = true)
        val backStack = mutableStateListOf<NavKey>(MainRouteHome)
        var infoOwner: LifecycleOwner? = null
        val handledBacks = mutableListOf<NavKey>()
        val tappedRoutes = mutableListOf<NavKey>()
        composeRule.setContent {
            NavDisplay(
                backStack = backStack,
                modifier = Modifier.fillMaxSize(),
                sceneStrategies = listOf(
                    remember { ModalOverlaySceneStrategy() },
                    SinglePaneSceneStrategy()
                ),
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                onBack = { backStack.removeLastOrNull() },
                entryProvider = entryProvider {
                    entry<MainRouteHome> { BasicText("Home") }
                    entry<MainRouteSearch> { BasicText("Search") }
                    entry<MainRouteExploreShow> { BasicText("Explore") }
                    entry<MainRouteBookInfo>(metadata = ModalOverlaySceneStrategy.modalOverlay()) { route ->
                        val owner = LocalLifecycleOwner.current
                        SideEffect { infoOwner = owner }
                        PredictiveBackHandler(enabled = backStack.lastOrNull() == route) { events ->
                            events.collect { }
                            handledBacks.add(route)
                            backStack.removeLastOrNull()
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("info")
                                .clickable { tappedRoutes.add(route) }
                        ) {
                            BasicText("Info")
                        }
                    }
                    entry<MainRouteReadBook>(metadata = ModalOverlaySceneStrategy.modalOverlay()) { route ->
                        PredictiveBackHandler(enabled = backStack.lastOrNull() == route) { events ->
                            events.collect { }
                            handledBacks.add(route)
                            backStack.removeLastOrNull()
                        }
                        Box(
                            Modifier
                                .fillMaxSize()
                                .testTag("reader")
                                .clickable { tappedRoutes.add(route) }
                        ) {
                            BasicText("Reader")
                        }
                    }
                },
            )
        }

        fun startGesture() {
            composeRule.runOnIdle {
                activity.onBackPressedDispatcher.dispatchOnBackStarted(
                    BackEventCompat(0f, 0f, 0f, BackEventCompat.EDGE_LEFT)
                )
                activity.onBackPressedDispatcher.dispatchOnBackProgressed(
                    BackEventCompat(0f, 0f, 0.6f, BackEventCompat.EDGE_LEFT)
                )
            }
            composeRule.waitForIdle()
        }

        fun assertTopRoute(expected: NavKey) {
            composeRule.runOnIdle { tappedRoutes.clear() }
            // Inject a real pointer at the measured route bounds: a semantics-only
            // click would bypass the overlay's drawing and hit-test order.
            composeRule.onNodeWithTag(if (expected == info) "info" else "reader")
                .assertIsDisplayed()
                .performTouchInput { click(center) }
            composeRule.runOnIdle {
                assertEquals(
                    "The visible, tappable route must match the stack top",
                    expected,
                    tappedRoutes.lastOrNull()
                )
            }
        }

        fun verifyBack(parent: NavKey) {
            val parentStack =
                if (parent == MainRouteHome) listOf(parent) else listOf(MainRouteHome, parent)
            // Dispose the previous scenario before reopening the same book route.
            composeRule.runOnIdle {
                backStack.clear()
                backStack.addAll(parentStack)
                infoOwner = null
                handledBacks.clear()
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                MainNavigator.navigateToRoute(backStack, info)
                assertEquals(parentStack + info, backStack.toList())
            }
            assertTopRoute(info)
            val originalInfoOwner = composeRule.runOnIdle {
                requireNotNull(infoOwner).also {
                    MainNavigator.navigateToRoute(backStack, reader)
                    assertEquals(parentStack + info + reader, backStack.toList())
                }
            }
            composeRule.runOnIdle { assertSame(originalInfoOwner, infoOwner) }
            assertTopRoute(reader)
            startGesture()
            composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.runOnIdle {
                assertEquals(parentStack + info, backStack.toList())
                assertSame(originalInfoOwner, infoOwner)
            }
            assertTopRoute(info)
            composeRule.runOnIdle { assertEquals(listOf(reader), handledBacks) }

            startGesture()
            composeRule.runOnIdle {
                activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            composeRule.runOnIdle {
                assertEquals(parentStack + info, backStack.toList())
                assertEquals(listOf(reader), handledBacks)
            }
            startGesture()
            composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
            composeRule.runOnIdle {
                assertEquals(parentStack, backStack.toList())
                assertEquals(listOf(reader, info), handledBacks)
                assertFalse(activity.isFinishing)
            }
            if (parent != MainRouteHome) {
                // The restored Search/Explore scene must still have Home as its parent.
                startGesture()
                composeRule.runOnIdle { activity.onBackPressedDispatcher.onBackPressed() }
                composeRule.runOnIdle {
                    assertEquals(listOf(MainRouteHome), backStack.toList())
                    assertFalse(activity.isFinishing)
                }
            }
        }

        verifyBack(MainRouteHome)
        verifyBack(MainRouteSearch(key = "Book"))
        verifyBack(MainRouteExploreShow("Explore", "source-url", "explore-url"))
    }
}
