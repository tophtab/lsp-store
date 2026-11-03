package io.github.tophtab.lspstore

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tophtab.lspstore.data.install.SystemInstallSerializer

/**
 * A late broadcast for the previous install must not open the gate for the next
 * one; a cancelled installer must be able to release a gate the next row waits on.
 */
class SystemInstallSerializerTest {

    @Test
    fun onlyTheHolderReleasesTheSlot() {
        val serializer = SystemInstallSerializer()
        runBlocking {
            serializer.awaitFreeAndMarkPending("com.example.a")
            serializer.markCompleted("com.example.b")
            assertTrue(serializer.isHeldBy("com.example.a"))

            serializer.markCompleted("com.example.a")
            assertFalse(serializer.isHeldBy("com.example.a"))
        }
    }

    @Test
    fun theNextHolderWaitsForTheCurrentOne() {
        val serializer = SystemInstallSerializer()
        runBlocking {
            serializer.awaitFreeAndMarkPending("com.example.a")
            launch {
                serializer.awaitFreeAndMarkPending("com.example.b")
            }
            delay(50)
            // Still owned by a: b is parked in awaitFreeAndMarkPending.
            assertTrue(serializer.isHeldBy("com.example.a"))

            serializer.markCompleted("com.example.a")
            withTimeout(2_000) {
                while (!serializer.isHeldBy("com.example.b")) delay(10)
            }
        }
    }
}
