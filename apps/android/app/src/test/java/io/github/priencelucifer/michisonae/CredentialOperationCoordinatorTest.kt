package io.github.priencelucifer.michisonae

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialOperationCoordinatorTest {
    @Test
    fun deletionCannotRaceAnInFlightCredentialOperation() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val deletionAttempting = CountDownLatch(1)
        val deletionEntered = AtomicBoolean(false)
        val finished = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        try {
            executor.execute {
                CredentialOperationCoordinator.runExclusive {
                    firstEntered.countDown()
                    releaseFirst.await(2, TimeUnit.SECONDS)
                }
                finished.countDown()
            }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            executor.execute {
                deletionAttempting.countDown()
                CredentialOperationCoordinator.runExclusive {
                    deletionEntered.set(true)
                }
                finished.countDown()
            }

            assertTrue(deletionAttempting.await(2, TimeUnit.SECONDS))
            assertFalse(deletionEntered.get())
            releaseFirst.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(deletionEntered.get())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
}
