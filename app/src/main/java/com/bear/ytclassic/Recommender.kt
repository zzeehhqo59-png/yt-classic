package com.bear.ytclassic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp
import kotlin.math.ln

/**
 * On-device Home recommendations: nothing leaves the phone except the normal YouTube page fetches.
 * Candidates = recent uploads of subscribed channels + videos related to recently watched ones
 * + recent uploads of channels watched often. Score = recency + how often the channel is watched
 * (+ small bonuses for subscribed channels / related-to-recent). Already-watched videos and Shorts are dropped.
 */
object Recommender {
    private var cache: Pair<Long, List<VideoItem>>? = null
    private const val TTL = 10 * 60 * 1000L

    private fun key(url: String?, name: String) = url?.let { runCatching { Subs.normalize(it) }.getOrNull() } ?: "name:" + name.lowercase()

    suspend fun recommend(c: Context, force: Boolean): List<VideoItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache?.let { if (!force && now - it.first < TTL) { val w = History.watchedUrls(); return@withContext it.second.filter { v -> v.url !in w } } }
        val hist = History.recent(300)
        val subs = Subs.all()
        if (hist.isEmpty() && subs.isEmpty()) return@withContext emptyList()
        val watched = hist.map { it.video.url }.toHashSet()
        val chCount = HashMap<String, Int>()
        hist.forEach { e -> chCount.merge(key(e.video.channelUrl, e.video.channel), e.count, Int::plus) }
        val subKeys = subs.map { it.url }.toHashSet()

        data class Cand(val v: VideoItem, var bonus: Double)
        val cands = LinkedHashMap<String, Cand>()
        fun addAll(list: List<VideoItem>, bonus: Double) = list.forEach { v ->
            if (v.kind != 0 || v.url in watched || v.upcoming) return@forEach
            val e = cands[v.url]
            if (e == null) cands[v.url] = Cand(v, bonus) else if (bonus > e.bonus) e.bonus = bonus
        }

        coroutineScope {
            val feed = async {
                if (subs.isEmpty()) emptyList()
                else Feed.cached(c)?.first ?: withTimeoutOrNull(20_000) { runCatching { Feed.refresh(c) { _, _ -> } }.getOrNull() } ?: emptyList()
            }
            val related = hist.take(5).mapIndexed { i, e ->
                async { i to (withTimeoutOrNull(12_000) { runCatching { Repo.stream(e.video.url).relatedItems.videosOnly().take(12) }.getOrNull() } ?: emptyList()) }
            }
            val topChannels = hist.filter { it.video.channelUrl != null }
                .groupBy { key(it.video.channelUrl, it.video.channel) }
                .filterKeys { it !in subKeys }
                .entries.sortedByDescending { en -> en.value.sumOf { it.count } }.take(3)
                .map { en -> en.value.first().video.channelUrl!! }
            val fromChannels = topChannels.map { u ->
                async { withTimeoutOrNull(12_000) { runCatching { Repo.channelVideos(u).next().take(8) }.getOrNull() } ?: emptyList() }
            }
            addAll(feed.await().take(150), 0.0)
            fromChannels.awaitAll().forEach { addAll(it, 0.0) }
            related.awaitAll().forEach { (i, l) -> addAll(l, 0.9 - 0.12 * i) }
        }

        val scored = cands.values.map { cd ->
            val v = cd.v
            val k = key(v.channelUrl, v.channel)
            val ageDays = if (v.uploadedMs > 0) (now - v.uploadedMs) / 86_400_000.0 else 30.0
            val s = exp(-ageDays.coerceAtLeast(0.0) / 10.0) + ln(1.0 + (chCount[k] ?: 0)) * 0.6 + (if (k in subKeys) 0.5 else 0.0) + cd.bonus
            v to s
        }.sortedByDescending { it.second }.map { it.first }

        // variety: at most 3 videos per channel among the first 24
        val out = ArrayList<VideoItem>(); val rest = ArrayList<VideoItem>(); val per = HashMap<String, Int>()
        for (v in scored) {
            val k = key(v.channelUrl, v.channel)
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
        if (recs.isEmpty()) return trending
        val seen = HashSet(recs.map { it.url })
        val watched = withContext(Dispatchers.IO) { History.watchedUrls() }
        return object : Pager {
            var first = true
            override val hasMore get() = first || trending.hasMore
            override suspend fun next(): List<VideoItem> {
                if (first) { first = false; return recs }
                return trending.next().filter { it.url !in watched && seen.add(it.url) }
            }
        }
    }
}
