package com.bear.ytclassic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * On-device Home recommendations: nothing leaves the phone except the normal YouTube page fetches.
 * Candidates = recent uploads of subscribed channels + videos related to recently watched ones
 * + recent uploads of channels watched often. Score = recency + how often the channel is watched
 * (+ small bonuses for subscribed channels / related-to-recent). Already-watched videos and Shorts are dropped.
 * 6.0.7: likes boost a channel and seed "related" candidates; dislikes don't count as watches, penalize the
 * channel and never seed; Home "ไม่สนใจ" / "ไม่แนะนำช่องนี้" (HiddenRecs) are filtered out. Rules live in [RecScoring].
 */
object Recommender {
    @Volatile private var cache: Pair<Long, List<VideoItem>>? = null
    private const val TTL = 10 * 60 * 1000L

    /** Drop cached recommendations (likes, ไม่สนใจ or ไม่แนะนำช่องนี้ changed). */
    fun invalidate() { cache = null }

    fun channelKey(url: String?, name: String) = url?.let { runCatching { Subs.normalize(it) }.getOrNull() } ?: "name:" + name.lowercase()
    fun channelKey(v: VideoItem) = channelKey(v.channelUrl, v.channel)

    suspend fun recommend(c: Context, force: Boolean): List<VideoItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.let { if (!force && now - it.first < TTL) { val w = History.watchedUrls(); return@withContext it.second.filter { v -> v.url !in w } } }
        val hist = History.recent(300)
        val subs = Subs.all()
        val rated = Likes.rated()
        if (hist.isEmpty() && subs.isEmpty() && rated.isEmpty()) return@withContext emptyList()
        val hidden = HiddenRecs.snapshot()
        val sig = RecScoring.signals(
            history = hist.map { RecScoring.Watch(it.video.url, channelKey(it.video), it.count) },
            ratings = rated.map { (v, value) -> RecScoring.Rated(v.url, channelKey(v), value) },
            hiddenVideos = hidden.videos, hiddenChannels = hidden.channels, subscribed = subs.map { it.url }.toHashSet(),
        )
        val watched = hist.map { it.video.url }.toHashSet()

        data class Cand(val v: VideoItem, var bonus: Double)
        val cands = LinkedHashMap<String, Cand>()
        fun addAll(list: List<VideoItem>, bonus: Double) = list.forEach { v ->
            if (v.kind != 0 || v.url in watched || v.upcoming || !RecScoring.allowed(v.url, channelKey(v), sig)) return@forEach
            val e = cands[v.url]
            if (e == null) cands[v.url] = Cand(v, bonus) else if (bonus > e.bonus) e.bonus = bonus
        }

        coroutineScope {
            val feed = async {
                if (subs.isEmpty()) emptyList()
                else Feed.cached(c)?.first ?: withTimeoutOrNull(20_000) { runCatching { Feed.refresh(c) { _, _ -> } }.getOrNull() } ?: emptyList()
            }
            val seeds = RecScoring.relatedSeeds(hist.map { it.video.url }, rated.filter { it.second == Likes.LIKE }.map { it.first.url }, sig)
            val related = seeds.map { (url, bonus) ->
                async { bonus to (withTimeoutOrNull(12_000) { runCatching { Repo.stream(url).relatedItems.videosOnly().take(12) }.getOrNull() } ?: emptyList()) }
            }
            val topChannels = hist.mapNotNull { e -> e.video.channelUrl?.let { u -> Triple(channelKey(e.video), u, e.count) } }
                .filter { (k, _, _) -> k !in sig.subscribed && k !in sig.hiddenChannels && (sig.disliked[k] ?: 0) == 0 }
                .groupBy { it.first }
                .entries.sortedByDescending { en -> en.value.sumOf { it.third } }.take(3)
                .map { en -> en.value.first().second }
            val fromChannels = topChannels.map { u ->
                async { withTimeoutOrNull(12_000) { runCatching { Repo.channelVideos(u).next().take(8) }.getOrNull() } ?: emptyList() }
            }
            addAll(feed.await().take(150), 0.0)
            fromChannels.awaitAll().forEach { addAll(it, 0.0) }
            related.awaitAll().forEach { (bonus, l) -> addAll(l, bonus) }
        }

        val scored = cands.values.map { cd ->
            val v = cd.v
            val ageDays = if (v.uploadedMs > 0) (now - v.uploadedMs) / 86_400_000.0 else 30.0
            v to RecScoring.score(ageDays, channelKey(v), cd.bonus, sig)
        }.sortedByDescending { it.second }.map { it.first }

        // variety: at most 3 videos per channel among the first 24
        val out = ArrayList<VideoItem>(); val rest = ArrayList<VideoItem>(); val per = HashMap<String, Int>()
        for (v in scored) {
            val k = channelKey(v)
            if (out.size < 24 && (per[k] ?: 0) >= 3) { rest.add(v); continue }
            per.merge(k, 1, Int::plus); out.add(v)
        }
        val result = (out + rest).take(60)
        cache = now to result
        result
    }

    /** First page = recommendations, then trending pages (deduplicated, watched removed). Plain trending if there is nothing to go on. */
    suspend fun homePager(c: Context, force: Boolean): Pager {
        val recs = runCatching { recommend(c, force) }.getOrDefault(emptyList())
        val trending = Repo.homePager()
        val seen = HashSet(recs.map { it.url })
        // trending-only (no history yet) keeps watched videos; hidden ones are always dropped
        val watched = if (recs.isEmpty()) emptySet() else withContext(Dispatchers.IO) { History.watchedUrls() }
        return object : Pager {
            var first = recs.isNotEmpty()
            override val hasMore get() = first || trending.hasMore
            override suspend fun next(): List<VideoItem> {
                if (first) { first = false; return recs }
                val hidden = withContext(Dispatchers.IO) { HiddenRecs.snapshot() }
                return trending.next().filter {
                    it.url !in watched && it.url !in hidden.videos && channelKey(it) !in hidden.channels && seen.add(it.url)
                }
            }
        }
    }
}
