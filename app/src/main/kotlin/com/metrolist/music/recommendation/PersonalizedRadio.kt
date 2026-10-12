/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.recommendation

import androidx.media3.common.MediaItem
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.extensions.metadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class PersonalizedRadio @Inject constructor(
    private val database: MusicDatabase,
    private val feedbackStore: ListeningFeedbackStore,
) {
    suspend fun rank(items: List<MediaItem>): List<MediaItem> = withContext(Dispatchers.IO) {
        if (items.size < 2) return@withContext items

        val nowMs = System.currentTimeMillis()
        val since = LocalDateTime.now().minusDays(HISTORY_DAYS)
        val history = try {
            database.recentEventsWithSong(since, HISTORY_LIMIT)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withContext items
        }
        val skips = try {
            feedbackStore.skipsSince(nowMs - HISTORY_DAYS * DAY_MS).map { it.songId }.toSet()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptySet()
        }
        val candidateIds = items.mapNotNull { it.metadata?.id }.distinct()
        val localCandidates = try {
            if (candidateIds.isEmpty()) emptyList() else database.getSongsByIds(candidateIds)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        val likedIds = localCandidates.filter { it.song.liked }.map { it.id }.toSet()
        val libraryIds = localCandidates.filter { it.song.inLibrary != null }.map { it.id }.toSet()
        if (history.isEmpty() && skips.isEmpty() && likedIds.isEmpty() && libraryIds.isEmpty()) return@withContext items

        val artistAffinity = HashMap<String, Double>()
        val albumAffinity = HashMap<String, Double>()
        val lastPlayed = HashMap<String, Long>()
        history.forEach { event ->
            val playedAt = event.event.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val age = (nowMs - playedAt).coerceAtLeast(0L)
            val recency = 0.5.pow(age.toDouble() / HALF_LIFE_MS)
            val durationMs = event.song.song.duration * 1000L
            val completed = durationMs > 0 && event.event.playTime >= durationMs * COMPLETION_FRACTION
            val weight = recency * if (completed) COMPLETED_WEIGHT else PLAYED_WEIGHT

            event.song.orderedArtists.forEach { artist ->
                artistAffinity.merge(artist.id, weight, Double::plus)
            }
            event.song.album?.id?.let { albumAffinity.merge(it, weight, Double::plus) }
            lastPlayed.merge(event.event.songId, playedAt) { previous, current -> maxOf(previous, current) }
        }

        val remaining = items.mapIndexed { index, item ->
            val metadata = item.metadata
            val artistIds = metadata?.artists.orEmpty().mapNotNull { it.id }
            val albumId = metadata?.album?.id
            val affinity = artistIds.maxOfOrNull { artistAffinity[it] ?: 0.0 } ?: 0.0
            val album = albumId?.let { albumAffinity[it] } ?: 0.0
            val playAge = metadata?.id?.let { lastPlayed[it]?.let { playedAt -> nowMs - playedAt } }
            val candidateId = metadata?.id
            val score =
                affinity.coerceAtMost(AFFINITY_CAP) * ARTIST_WEIGHT +
                    album.coerceAtMost(AFFINITY_CAP) * ALBUM_WEIGHT +
                    (if (metadata?.liked == true || candidateId?.let { it in likedIds } == true) LIKED_BONUS else 0.0) +
                    (if (metadata?.inLibrary != null || candidateId?.let { it in libraryIds } == true) LIBRARY_BONUS else 0.0) -
                    (if (candidateId?.let { it in skips } == true) SKIP_PENALTY else 0.0) -
                    (if (playAge != null && playAge < RECENT_PLAY_MS) RECENT_PLAY_PENALTY else 0.0)
            Scored(item, index, score, artistIds.firstOrNull(), albumId)
        }.toMutableList()

        val artistCounts = HashMap<String, Int>()
        val albumCounts = HashMap<String, Int>()
        buildList {
            while (remaining.isNotEmpty()) {
                val best = remaining.maxWithOrNull(
                    compareBy<Scored> { candidate ->
                        candidate.score -
                            (candidate.artistId?.let { artistCounts[it] } ?: 0) * ARTIST_REPEAT_PENALTY -
                            (candidate.albumId?.let { albumCounts[it] } ?: 0) * ALBUM_REPEAT_PENALTY
                    }.thenBy { -it.index },
                ) ?: break
                remaining.remove(best)
                best.artistId?.let { artistCounts.merge(it, 1, Int::plus) }
                best.albumId?.let { albumCounts.merge(it, 1, Int::plus) }
                add(best.item)
            }
        }
    }

    private data class Scored(
        val item: MediaItem,
        val index: Int,
        val score: Double,
        val artistId: String?,
        val albumId: String?,
    )

    private companion object {
        const val HISTORY_DAYS = 60L
        const val HISTORY_LIMIT = 500
        const val DAY_MS = 86_400_000L
        const val HALF_LIFE_MS = 14 * DAY_MS
        const val COMPLETION_FRACTION = 0.8
        const val COMPLETED_WEIGHT = 1.5
        const val PLAYED_WEIGHT = 0.75
        const val AFFINITY_CAP = 6.0
        const val ARTIST_WEIGHT = 0.6
        const val ALBUM_WEIGHT = 0.25
        const val LIKED_BONUS = 1.5
        const val LIBRARY_BONUS = 0.5
        const val SKIP_PENALTY = 4.0
        const val RECENT_PLAY_MS = 14 * DAY_MS
        const val RECENT_PLAY_PENALTY = 2.0
        const val ARTIST_REPEAT_PENALTY = 0.8
        const val ALBUM_REPEAT_PENALTY = 0.4
    }
}
