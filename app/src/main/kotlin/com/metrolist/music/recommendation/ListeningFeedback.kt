/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommendation

import java.io.File

data class SkipFeedback(
    val songId: String,
    val timestampMs: Long,
)

interface ListeningFeedbackStore {
    fun recordSkip(songId: String, timestampMs: Long)
    fun skipsSince(sinceMs: Long): List<SkipFeedback>
}

fun isEarlySkip(positionMs: Long, durationSec: Int, historyThresholdMs: Long): Boolean {
    if (positionMs < MIN_SKIP_SIGNAL_MS || positionMs >= historyThresholdMs) return false
    return durationSec <= 0 || positionMs < (durationSec * 1000L * MAX_SKIP_FRACTION).toLong()
}

class FileListeningFeedbackStore(
    private val file: File,
    private val maxEntries: Int = 500,
) : ListeningFeedbackStore {
    private val lock = Any()
    private var entries: MutableList<SkipFeedback>? = null

    override fun recordSkip(songId: String, timestampMs: Long) {
        if (songId.isEmpty() || songId.any { it == '\t' || it == '\n' || it == '\r' }) return
        synchronized(lock) {
            val updated = load().apply {
                add(SkipFeedback(songId, timestampMs))
                while (size > maxEntries) removeAt(0)
            }
            val temp = File(file.parentFile, "${file.name}.tmp")
            runCatching {
                temp.writeText(updated.joinToString("\n") { "${it.songId}\t${it.timestampMs}" })
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
            entries = updated
        }
    }

    override fun skipsSince(sinceMs: Long): List<SkipFeedback> =
        synchronized(lock) { load().filter { it.timestampMs >= sinceMs } }

    private fun load(): MutableList<SkipFeedback> = entries ?: run {
        val saved = if (file.exists()) {
            runCatching {
                file.readLines().mapNotNull { line ->
                    val parts = line.split('\t', limit = 2)
                    val songId = parts.getOrNull(0)
                    val timestamp = parts.getOrNull(1)?.toLongOrNull()
                    if (!songId.isNullOrEmpty() && timestamp != null) SkipFeedback(songId, timestamp) else null
                }.takeLast(maxEntries)
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        saved.toMutableList().also { entries = it }
    }
}

private const val MIN_SKIP_SIGNAL_MS = 1_000L
private const val MAX_SKIP_FRACTION = 0.5
