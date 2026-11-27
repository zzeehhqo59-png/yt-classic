package com.bear.ytclassic

import kotlin.math.exp
import kotlin.math.ln

/**
 * Pure scoring rules for on-device Home recommendations (no I/O, unit-tested).
 * Channel keys are whatever [Recommender.channelKey] produced for the videos.
 */
object RecScoring {
    data class Watch(val url: String, val channelKey: String, val count: Int)
    data class Rated(val url: String, val channelKey: String, val value: Int)

    data class Signals(
        val channelWatch: Map<String, Int> = emptyMap(),
        val liked: Map<String, Int> = emptyMap(),
        val disliked: Map<String, Int> = emptyMap(),
        /** Videos marked ไม่สนใจ, counted per channel (small penalty). */
        val notInterested: Map<String, Int> = emptyMap(),
        val hiddenVideos: Set<String> = emptySet(),
        val hiddenChannels: Set<String> = emptySet(),
        val dislikedVideos: Set<String> = emptySet(),
        val subscribed: Set<String> = emptySet(),
    )

    const val LIKE_WEIGHT = 0.8
    const val DISLIKE_PENALTY = 1.2
    const val NOT_INTERESTED_PENALTY = 0.4
    const val LIKED_SEED_BONUS = 0.8

    fun signals(
        history: List<Watch>, ratings: List<Rated>, hiddenVideos: Map<String, String?>, hiddenChannels: Set<String>, subscribed: Set<String>,
    ): Signals {
        val disliked = ratings.filter { it.value == Likes.DISLIKE }
        val dislikedUrls = disliked.map { it.url }.toSet()
        val watch = HashMap<String, Int>()
        history.filter { it.url !in dislikedUrls }.forEach { watch.merge(it.channelKey, it.count, Int::plus) }
        return Signals(
            channelWatch = watch,
            liked = ratings.filter { it.value == Likes.LIKE }.groupingBy { it.channelKey }.eachCount(),
            disliked = disliked.groupingBy { it.channelKey }.eachCount(),
            notInterested = hiddenVideos.values.filterNotNull().groupingBy { it }.eachCount(),
            hiddenVideos = hiddenVideos.keys,
            hiddenChannels = hiddenChannels,
            dislikedVideos = dislikedUrls,
            subscribed = subscribed,
        )
    }

    /** Videos to fetch "related" for: recent watches (not disliked), then recent likes, each with a bonus. */
    fun relatedSeeds(recentWatchUrls: List<String>, likedUrls: List<String>, s: Signals, maxWatched: Int = 5, maxLiked: Int = 3): List<Pair<String, Double>> {
        val out = LinkedHashMap<String, Double>()
        recentWatchUrls.filter { it !in s.dislikedVideos }.take(maxWatched).forEachIndexed { i, u -> out[u] = 0.9 - 0.12 * i }
        likedUrls.filter { it !in out }.take(maxLiked).forEach { out[it] = LIKED_SEED_BONUS }
        return out.toList()
    }

    fun allowed(url: String, channelKey: String, s: Signals): Boolean =
        url !in s.hiddenVideos && url !in s.dislikedVideos && channelKey !in s.hiddenChannels

    fun score(ageDays: Double, channelKey: String, bonus: Double, s: Signals): Double =
        exp(-ageDays.coerceAtLeast(0.0) / 10.0) +
            ln(1.0 + (s.channelWatch[channelKey] ?: 0)) * 0.6 +
            (if (channelKey in s.subscribed) 0.5 else 0.0) +
            ln(1.0 + (s.liked[channelKey] ?: 0)) * LIKE_WEIGHT -
            (s.disliked[channelKey] ?: 0) * DISLIKE_PENALTY -
            (s.notInterested[channelKey] ?: 0) * NOT_INTERESTED_PENALTY +
            bonus
}
