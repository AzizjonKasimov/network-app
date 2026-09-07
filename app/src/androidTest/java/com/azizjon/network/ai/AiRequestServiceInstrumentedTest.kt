package com.azizjon.network.ai

import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the foreground service on a real framework.
 *
 * Android rejects a foreground service whose declared type, runtime type, or
 * permissions disagree, and it does so by throwing inside onStartCommand - which
 * takes the whole app down. Nothing off-device catches that, so the start has to
 * happen here, in the app's own process, on the API level it ships against.
 */
@RunWith(AndroidJUnit4::class)
class AiRequestServiceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theServiceReachesForegroundAndStopsAgain() = runBlocking {
        var foregroundWhileHeld = false
        AiRequestService.holdingProcess(context) {
            // startForegroundService only queues the start, so yield the main thread.
            delay(3_000)
            foregroundWhileHeld = serviceIsForeground()
        }
        assertTrue("the service never reached the foreground", foregroundWhileHeld)

        delay(3_000)
        assertFalse("the service was left running after the request", serviceIsForeground())
    }

    @Test
    fun nestedHoldsKeepTheServiceUpUntilTheLastOneEnds() = runBlocking {
        var foregroundAfterInnerRelease = false
        AiRequestService.holdingProcess(context) {
            AiRequestService.holdingProcess(context) { delay(3_000) }
            foregroundAfterInnerRelease = serviceIsForeground()
        }
        assertTrue("an inner release stopped a service the outer hold still needed", foregroundAfterInnerRelease)

        delay(3_000)
        assertFalse("the service was left running after the request", serviceIsForeground())
    }

    private fun serviceIsForeground(): Boolean {
        val manager = context.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == AiRequestService::class.java.name && it.foreground
        }
    }
}
