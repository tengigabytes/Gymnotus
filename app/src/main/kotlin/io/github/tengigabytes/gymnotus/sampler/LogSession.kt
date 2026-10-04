package io.github.tengigabytes.gymnotus.sampler

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.CsvExport
import io.github.tengigabytes.gymnotus.power.ExportMeta
import io.github.tengigabytes.gymnotus.power.ExportStats
import io.github.tengigabytes.gymnotus.power.MonitorInfo
import io.github.tengigabytes.gymnotus.power.PollRecord
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Streams polls to a document chosen by the user. Rows are written as they arrive and flushed every
 * [FLUSH_INTERVAL_MS], so a log that is cut short (process killed) is still a readable CSV without its trailer.
 *
 * @param onError called on the main thread when the file cannot be opened or written
 */
class LogSession(
    context: Context,
    scope: CoroutineScope,
    uri: Uri,
    meta: ExportMeta,
    monitors: List<MonitorInfo>,
    onError: (String) -> Unit,
) {
    private val records = Channel<PollRecord>(Channel.UNLIMITED)
    private val finalStats = CompletableDeferred<ExportStats>()

    init {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri, "wt") ?: error("Cannot open $uri for writing")
                    stream.bufferedWriter().use { out ->
                        CsvExport.writeHeader(out, meta, monitors)
                        var lastFlushMs = SystemClock.elapsedRealtime()
                        for (record in records) {
                            CsvExport.writePoll(out, monitors, record)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                                out.flush()
                                lastFlushMs = now
                            }
                        }
                        CsvExport.writeStats(out, monitors, finalStats.await())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(context.getString(R.string.msg_log_write_failed, e.toString()))
            }
        }
    }

    fun offer(record: PollRecord) {
        records.trySend(record)
    }

    /** Writes the statistics trailer and closes the file. */
    fun close(stats: ExportStats) {
        finalStats.complete(stats)
        records.close()
    }

    private companion object {
        const val FLUSH_INTERVAL_MS = 5_000L
    }
}
