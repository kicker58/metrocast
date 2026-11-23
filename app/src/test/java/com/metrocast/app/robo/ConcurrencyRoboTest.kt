package com.metrocast.app.robo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.metrocast.app.data.Repository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** The app screen, the widget and the background check all working at once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ConcurrencyRoboTest {
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("metrocast", Context.MODE_PRIVATE).edit().clear().commit()
        FakeWmata.install()
    }
    @After fun tearDown() = FakeWmata.uninstall()

    @Test fun manyRefreshersAtOnce() {
        runBlocking { Repository(ctx).refresh() }
        Repository(ctx).apply { startLiveTrip("C02", "F05"); setPlan("N06", "B03"); toggleSavedRoute("A15", "F05") }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val workers = (0 until 8).map { w ->
            thread {
                start.await()
                val repo = Repository(ctx) // each part of the app makes its own, like the real app
                repeat(40) { i ->
                    try {
                        when ((w + i) % 6) {
                            0 -> runBlocking { repo.refresh() }
                            1 -> repo.snapshot()
                            2 -> repo.lineMaps()
                            3 -> repo.liveTrip()
                            4 -> repo.autoAdvanceLive()
                            else -> repo.checkLiveNotification(ctx)
                        }
                    } catch (e: Throwable) { errors += e }
                }
            }
        }
        start.countDown()
        workers.forEach { it.join(120_000) }
        assertTrue("errors while running at once: ${errors.take(3)}", errors.isEmpty())
        // Everything still consistent afterwards.
        val repo = Repository(ctx)
        assertNotNull(repo.liveTrip())
        assertNotNull(repo.snapshot().plan)
        assertTrue(repo.snapshot().savedRoutes.isNotEmpty())
    }
}
