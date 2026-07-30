package io.github.priencelucifer.michisonae

import android.content.Context
import android.util.AtomicFile
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray

internal class OfflineObservationQueue(context: Context) {
    private val file = AtomicFile(context.filesDir.resolve("pending-road-observations.json"))
    private val lock = ReentrantLock()

    fun enqueue(observation: RoadObservationDraft) = lock.withLock {
        write(read() + observation)
    }

    fun pending(limit: Int = 100): List<RoadObservationDraft> = lock.withLock {
        require(limit in 1..100)
        read().take(limit)
    }

    fun pendingCount(): Int = lock.withLock { read().size }

    fun acknowledgeAfterDurableAcceptance(
        eventIds: Set<String>,
        outcome: UploadOutcome,
    ) = lock.withLock {
        val acknowledged = acknowledgedEventIds(outcome, eventIds)
        if (acknowledged.isNotEmpty()) {
            write(read().filterNot { it.eventId in acknowledged })
        }
    }

    private fun read(): List<RoadObservationDraft> {
        if (!file.baseFile.exists()) return emptyList()
        return file.openRead().bufferedReader().use { input ->
            val array = JSONArray(input.readText())
            List(array.length()) { index ->
                RoadObservationDraft.fromStoredJson(array.getJSONObject(index))
            }
        }
    }

    private fun write(observations: List<RoadObservationDraft>) {
        val output = file.startWrite()
        try {
            val array = JSONArray()
            observations.forEach { array.put(it.toStoredJson()) }
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}
