package com.bear.ytclassic

import android.view.View
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Home card ⋮ menu: normal video actions + ไม่สนใจ / ไม่แนะนำช่องนี้ with an undo snackbar. */
class HomeHide(private val fragment: Fragment, private val adapter: () -> VideoAdapter) {
    fun menu(anchor: View, v: VideoItem) = anchor.showVideoMenu(v, listOf(
        "ไม่สนใจ" to { notInterested(v) },
        "ไม่แนะนำช่องนี้" to { hideChannel(v) },
    ))

    private fun notInterested(v: VideoItem) {
        val gone = adapter().removeWhere { it.url == v.url }
        io {
            HiddenRecs.hideVideo(v.url, Recommender.channelKey(v))
        }
        undoBar("ซ่อนวิดีโอนี้แล้ว", gone) { HiddenRecs.undo(v.url, HiddenRecs.VIDEO) }
    }

    private fun hideChannel(v: VideoItem) {
        val key = Recommender.channelKey(v)
        val gone = adapter().removeWhere { Recommender.channelKey(it) == key }
        io { HiddenRecs.hideChannel(key) }
        val name = v.channel.ifBlank { "ช่องนี้" }
        undoBar("จะไม่แนะนำ $name อีก", gone) { HiddenRecs.undo(key, HiddenRecs.CHANNEL) }
    }

    private fun undoBar(text: String, gone: List<Pair<Int, VideoItem>>, undoDb: () -> Unit) {
        val root = fragment.view ?: return
        val bar = Snackbar.make(root, text, Snackbar.LENGTH_LONG).setAction("เลิกทำ") {
            adapter().restore(gone)
            io(undoDb)
        }
        fragment.activity?.findViewById<View>(R.id.bottomNav)?.let { bar.anchorView = it }
        bar.show()
    }

    /** App-wide scope so a quick DB write still lands if the user leaves Home right away. */
    private fun io(block: () -> Unit) {
        scope.launch { runCatching(block).onFailure { android.util.Log.w("HomeHide", "save failed", it) } }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)) // in order: hide then undo
    }
}
