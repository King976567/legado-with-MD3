package io.legado.app.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestChapterTaskSchedulerTest {

    @Test
    fun `same chapter keeps running task and only latest pending task`() = runTest {
        val scheduler = LatestChapterTaskScheduler<String>(backgroundScope, StandardTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        val runningStarted = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        scheduler.submit("chapter") {
            events += "running-start"
            runningStarted.complete(Unit)
            gate.await()
            events += "running-end"
        }
        runCurrent()
        assertTrue(runningStarted.isCompleted)

        val replaced = scheduler.submit("chapter") { events += "replaced" }
        val latest = scheduler.submit("chapter") { events += "latest" }
        assertTrue(replaced.isCancelled)
        assertEquals(
            LatestChapterTaskScheduler.TaskState(running = true, pending = true),
            scheduler.stateOf("chapter"),
        )

        gate.complete(Unit)
        // result completion wakes awaiters before the worker's finally clears its entry.
        // Drain that cleanup deterministically instead of racing Dispatchers.IO.
        runCurrent()
        latest.await()

        assertEquals(listOf("running-start", "running-end", "latest"), events)
        assertEquals(LatestChapterTaskScheduler.TaskState(), scheduler.stateOf("chapter"))
    }

    @Test
    fun `different chapters can run independently`() = runTest {
        val scheduler = LatestChapterTaskScheduler<String>(backgroundScope, StandardTestDispatcher(testScheduler))
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        scheduler.submit("first") {
            firstStarted.complete(Unit)
            awaitCancellation()
        }
        scheduler.submit("second") {
            secondStarted.complete(Unit)
            awaitCancellation()
        }
        runCurrent()

        assertTrue(firstStarted.isCompleted)
        assertTrue(secondStarted.isCompleted)
        assertTrue(scheduler.stateOf("first").running)
        assertTrue(scheduler.stateOf("second").running)

        scheduler.cancelAll()
        runCurrent()
        assertFalse(scheduler.stateOf("first").running)
        assertFalse(scheduler.stateOf("second").running)
    }
}
