package io.legado.app.ui.main

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
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
        val first = MainRouteReadManga(bookUrl = null, openRequestId = 1L)
        val stack = mutableStateListOf<NavKey>(MainRouteHome, first)
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
                entryProvider = entryProvider {
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

    private class ReaderViewModel : ViewModel() {
        var cleared = 0
            private set

        override fun onCleared() {
            cleared++
        }
    }
}
