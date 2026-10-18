package io.legado.app.ui.main

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MainMangaReaderEntryTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `reopening and changing books retain the reader store until its slot is removed`() {
        assertReaderStoreRetained(panesEnabled = false)
    }

    @Test
    fun `wide navigation retains the reader store across explicit reopen and book changes`() {
        assertReaderStoreRetained(panesEnabled = true)
    }

    private fun assertReaderStoreRetained(panesEnabled: Boolean) {
        val first = MainRouteReadManga(bookUrl = null, openRequestId = 1L)
        val stack = mutableStateListOf<NavKey>(MainRouteHome, first)
        val tracker = MainNavRouteTracker()
        val created = mutableListOf<ReaderViewModel>()
        val opened = mutableListOf<MainRouteReadManga>()
        var currentViewModel: ReaderViewModel? = null
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ReaderViewModel().also(created::add) as T
        }
        compose.setContent {
            NavDisplay(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                onBack = { stack.removeLastOrNull() },
                entryProvider = mainPaneEntryProvider(stack, panesEnabled, tracker, {}) {
                    entry<MainRouteHome> { BasicText("Home") }
                    entry<MainRouteBookInfo> { BasicText("Details") }
                    // Use the app's registration so changing its content key or VM key fails here.
                    mangaReaderEntry { route, viewModelKey ->
                        val reader = viewModel<ReaderViewModel>(key = viewModelKey, factory = factory)
                        SideEffect { currentViewModel = reader }
                        LaunchedEffect(route.openRequestId) { opened += route }
                        BasicText("Reader ${route.bookUrl}")
                    }
                },
            )
        }
        val original = compose.runOnIdle {
            assertEquals(listOf(first), opened)
            assertEquals(1, created.size)
            requireNotNull(currentViewModel)
        }

        fun reopenFromDetails(request: MainRouteReadManga) {
            compose.runOnIdle {
                stack += MainRouteBookInfo("Comic", "Author", request.bookUrl.orEmpty())
            }
            compose.waitForIdle()
            compose.runOnIdle {
                MainNavigator.navigateToRoute(stack, request)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(listOf(MainRouteHome, request), stack.toList())
                assertSame(original, currentViewModel)
                assertEquals(1, created.size)
                assertEquals(0, original.cleared)
                assertEquals(request, opened.last())
            }
        }

        // A last-read route can become a concrete URL when its details reopen the same comic.
        val second = first.copy(bookUrl = "book-a", chapterChanged = true, openRequestId = 2L)
        reopenFromDetails(second)
        reopenFromDetails(second.copy(openRequestId = 3L))
        // Changing source or book also reuses the one session, which already owns book switching.
        reopenFromDetails(second.copy(bookUrl = "book-b", openRequestId = 4L))

        compose.runOnIdle { stack.removeLast() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(MainRouteHome), stack.toList())
            assertEquals(1, original.cleared)
            assertEquals(4, opened.size)
        }
    }

    @Test
    fun `wide reader callbacks open the actual book toc and reject retired entry callbacks`() {
        val first = MainRouteReadManga(bookUrl = "comic-a", openRequestId = 1L)
        val replacement = MainRouteReadManga(bookUrl = "comic-b", openRequestId = 2L)
        val stack = mutableStateListOf<NavKey>(MainRouteHome, first)
        val tracker = MainNavRouteTracker()
        val requested = mutableListOf<NavKey>()
        var currentOpenToc: (() -> Unit)? = null
        compose.setContent {
            // MainActivity also synchronizes stack changes outside the navigator's normal path.
            SideEffect { tracker.onBackStackChanged(stack) }
            NavDisplay(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
                onBack = { stack.removeLastOrNull() },
                entryProvider = mainPaneEntryProvider(stack, true, tracker, { destination ->
                    requested += destination
                    MainNavigator.navigateToRoute(stack, destination, tracker)
                }) {
                    entry<MainRouteHome> { BasicText("Home") }
                    entry<MainRouteToc> { route -> BasicText("TOC ${route.bookUrl}") }
                    mangaReaderEntry { route, _ ->
                        val openToc = {
                            onNavigateToRoute(MainRouteToc(requireNotNull(route.bookUrl)))
                        }
                        SideEffect { currentOpenToc = openToc }
                        BasicText(
                            "Open TOC",
                            Modifier.testTag("manga-open-toc").clickable(onClick = openToc),
                        )
                    }
                },
            )
        }
        val oldOpenToc = compose.runOnIdle { requireNotNull(currentOpenToc) }
        compose.onNodeWithTag("manga-open-toc").performClick()
        compose.runOnIdle {
            val toc = MainRouteToc("comic-a")
            assertEquals(listOf(toc), requested)
            assertEquals(listOf(MainRouteHome, first, toc), stack.toList())
            assertEquals(stack.toList(), tracker.backStack.value)
            MainNavigator.navigateToRoute(stack, replacement, tracker)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(MainRouteHome, replacement), stack.toList())
            oldOpenToc()
            assertEquals(listOf(MainRouteToc("comic-a")), requested)
            assertEquals(listOf(MainRouteHome, replacement), stack.toList())
        }
        compose.onNodeWithTag("manga-open-toc").performClick()
        compose.runOnIdle {
            val toc = MainRouteToc("comic-b")
            assertEquals(listOf(MainRouteToc("comic-a"), toc), requested)
            assertEquals(listOf(MainRouteHome, replacement, toc), stack.toList())
            assertEquals(stack.toList(), tracker.backStack.value)
        }
    }

    private class ReaderViewModel : ViewModel() {
        var cleared = 0
            private set

        override fun onCleared() {
            cleared++
        }
    }
}
