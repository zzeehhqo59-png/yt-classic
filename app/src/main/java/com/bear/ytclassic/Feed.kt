package com.bear.ytclassic

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.concurrent.atomic.AtomicInteger

/** Subscriptions feed: latest videos (Videos tab, so no Shorts) of every subscribed channel, newest first. */
object Feed {
    private class Cache(val time: Long, val urls: Set<String>, val items: ArrayList<VideoItem>) : Serializable
    const val MAX_AGE_MS = 30 * 60 * 1000L
    private const val PER_CHANNEL = 10
    private const val PARALLEL = 6

    private fun file(c: Context) = File(c.filesDir, "feed.cache")

    /** Cached feed if it was built for the current subscription list. Returns (items, ageMs). */
    suspend fun cached(c: Context): Pair<List<VideoItem>, Long>? = withContext(Dispatchers.IO) {
        runCatching {
            val cache = ObjectInputStream(file(c).inputStream().buffered()).use { it.readObject() as Cache }
            val urls = Subs.all().map { it.url }.toSet()
            if (cache.urls != urls) null else cache.items to (System.currentTimeMillis() - cache.time)
        }.getOrNull()
    }

    suspend fun refresh(c: Context, progress: (done: Int, total: Int) -> Unit): List<VideoItem> = coroutineScope {
        val subs = Subs.all()
        val sem = Semaphore(PARALLEL)
        val done = AtomicInteger()
        val lists = subs.map { ch ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val vids = runCatching { Repo.channelVideos(ch.url).next().take(PER_CHANNEL) }.getOrDefault(emptyList())
                    var avatar = ch.avatar
                    if (avatar == null) runCatching {
                        val info = Repo.channel(ch.url)
                        avatar = info.avatars.pick(88)
                        Subs.update(Channel(ch.url, info.name.ifBlank { ch.name }, avatar, info.subscriberCount))
                    }
                    progress(done.incrementAndGet(), subs.size)
                    vids.map { v ->
                        v.copy(channel = v.channel.ifBlank { ch.name }, channelUrl = v.channelUrl ?: ch.url, avatar = v.avatar ?: avatar)
                    }
                }
            }
        }.awaitAll()
        val items = ArrayList(lists.flatten().distinctBy { it.url }.sortedByDescending { it.uploadedMs }.take(300))
        withContext(Dispatchers.IO) {
            runCatching {
                ObjectOutputStream(file(c).outputStream().buffered()).use {
                    it.writeObject(Cache(System.currentTimeMillis(), subs.map { s -> s.url }.toSet(), items))
                }
            }
        }
        items
    }
}
