package io.legado.app.data.repository

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import io.legado.app.domain.model.NasUploadTask

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class NasUploadTaskObservationTest {
    private lateinit var repository: NasUploadTaskRepository
    private lateinit var workManager: WorkManager

    @Before fun setUp() {
        val application: Application = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManager = WorkManager.getInstance(application)
        repository = NasUploadTaskRepository(application)
    }

    @After fun tearDown() {
        workManager.cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    @Test fun canObserveEmptyTasksFromIoThread() = runBlocking {
        val tasks = withTimeout(5_000) {
            withContext(Dispatchers.IO) { repository.observe().first() }
        }
        assertTrue(tasks.isEmpty())
    }

    @Test fun backgroundObserverReceivesNewAndCancelledTasks() = runBlocking {
        val updates = Channel<List<NasUploadTask>>(Channel.UNLIMITED)
        val job = launch(Dispatchers.IO) { repository.observe().collect { updates.send(it) } }
        try {
            assertTrue(withTimeout(5_000) { updates.receive() }.isEmpty())
            val request = OneTimeWorkRequestBuilder<ObservationWorker>()
                .addTag(NasUploadTaskRepository.TAG)
                .addTag(NasUploadTaskRepository.TITLE_TAG_PREFIX + "comic")
                .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
                .build()
            withContext(Dispatchers.IO) { workManager.enqueue(request).result.get() }
            val queued = withTimeout(5_000) {
                var tasks = updates.receive()
                while (tasks.isEmpty()) tasks = updates.receive()
                tasks.single()
            }
            assertEquals(request.id.toString(), queued.id)
            assertEquals("comic", queued.title)
            assertEquals("ENQUEUED", queued.state)
            withContext(Dispatchers.IO) { workManager.cancelWorkById(request.id).result.get() }
            val cancelled = withTimeout(5_000) {
                var tasks = updates.receive()
                while (tasks.none { it.state == "CANCELLED" }) tasks = updates.receive()
                tasks.single()
            }
            assertTrue(cancelled.isFinished)
        } finally {
            job.cancelAndJoin()
            updates.close()
        }
    }

    @Test fun backgroundCollectionCanBeCancelledAndStartedAgain() = runBlocking {
        repeat(2) {
            val received = Channel<Unit>(1)
            val job = launch(Dispatchers.IO) {
                repository.observe().collect { received.trySend(Unit) }
            }
            try {
                withTimeout(5_000) { received.receive() }
            } finally {
                withTimeout(5_000) { job.cancelAndJoin() }
                received.close()
            }
        }
    }

    class ObservationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
        override fun doWork() = Result.success()
    }
}
