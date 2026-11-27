package com.bear.ytclassic

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import coil3.load
import kotlinx.coroutines.launch

/** คุณ tab: profile header, history row, คลังวิดีโอ (chips + collection rows), settings. */
class LibraryFragment : Fragment() {
    private val vm: LibraryViewModel by viewModels()
    private val history = YouHistoryAdapter { requireActivity().openVideo(it) }
    private var downloads: LibraryDownloads? = null
    private var shownHistory: List<VideoItem>? = null
    private var shownRows: List<LibraryModel.Row>? = null
    private var shownChips: Triple<List<LibraryModel.Filter>, LibraryModel.Filter, LibraryModel.Sort>? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        inflater.inflate(R.layout.fragment_library, container, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        view.findViewById<RecyclerView>(R.id.historyRow).apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = history
        }
        val openHistory = View.OnClickListener { startActivity(VideoListActivity.history(requireContext())) }
        view.findViewById<View>(R.id.historyAll).setOnClickListener(openHistory)
        view.findViewById<View>(R.id.historyHeader).setOnClickListener(openHistory)
        view.findViewById<SwipeRefreshLayout>(R.id.youRefresh).setOnRefreshListener { vm.refresh(userPulled = true) }
        downloads = LibraryDownloads(requireActivity(), view.findViewById(R.id.downloads))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.ui.collect { render(it) } }
                launch { Downloads.changes.collect { if (!isHidden) vm.refresh() } }
            }
        }
    }

    override fun onDestroyView() {
        downloads = null; shownHistory = null; shownRows = null; shownChips = null
        super.onDestroyView()
    }
    override fun onHiddenChanged(hidden: Boolean) { super.onHiddenChanged(hidden); if (!hidden) vm.refresh() }
    override fun onResume() { super.onResume(); vm.refresh() }

    private fun render(st: LibraryUi) {
        val view = view ?: return
        view.findViewById<SwipeRefreshLayout>(R.id.youRefresh).isRefreshing = st.refreshing
        val snap = st.snapshot
        view.findViewById<TextView>(R.id.profileLine).text = LibraryModel.profileLine(snap)
        if (shownHistory != snap.history) { shownHistory = snap.history; history.set(snap.history) }
        val noHistory = st.loaded && snap.history.isEmpty()
        view.findViewById<View>(R.id.historyRow).visibility = if (noHistory) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.historyEmpty).visibility = if (noHistory) View.VISIBLE else View.GONE
        val chips = Triple(st.filters, st.filter, st.sort)
        if (shownChips != chips) { shownChips = chips; renderChips(view.findViewById(R.id.libraryChips), st) }
        val rows = st.rows
        if (shownRows != rows) { shownRows = rows; renderRows(view.findViewById(R.id.libraryRows), rows) }
        val shown = st.shownDownloads
        downloads?.render(shown)
        val empty = view.findViewById<TextView>(R.id.libraryEmpty)
        val isEmpty = st.loaded && rows.isEmpty() && shown.isEmpty()
        empty.text = st.error ?: LibraryModel.emptyText(st.filter)
        empty.visibility = if (isEmpty || st.error != null) View.VISIBLE else View.GONE
    }

    private fun renderChips(box: LinearLayout, st: LibraryUi) {
        box.removeAllViews()
        val ctx = requireContext()
        val d = resources.displayMetrics.density
        st.filters.forEach { f ->
            val on = f == st.filter
            box.addView(TextView(ctx).apply {
                text = LibraryModel.chipLabel(f, st.sort)
                textSize = 14f
                setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (7 * d).toInt())
                setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_capsule)
                setTextColor(ContextCompat.getColor(ctx, if (on) R.color.bg else R.color.text1))
                if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener {
                    when {
                        f == LibraryModel.Filter.ALL -> { vm.select(f); sortSheet(st.sort) }
                        on -> vm.select(LibraryModel.Filter.ALL)   // tap a selected chip again = back to all
                        else -> vm.select(f)
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt()
            })
        }
    }

    private fun renderRows(box: LinearLayout, rows: List<LibraryModel.Row>) {
        box.removeAllViews()
        rows.forEach { r ->
            val v = layoutInflater.inflate(R.layout.item_you_collection, box, false)
            v.findViewById<TextView>(R.id.title).text = r.title
            v.findViewById<TextView>(R.id.meta).text = r.meta
            val cover = v.findViewById<ImageView>(R.id.cover)
            val icon = v.findViewById<ImageView>(R.id.icon)
            if (r.cover.isNullOrBlank()) {
                cover.setImageDrawable(null)
                icon.setImageResource(iconOf(r.kind)); icon.visibility = View.VISIBLE
                icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.text1))
            } else {
                cover.load(r.cover); icon.visibility = View.GONE
            }
            v.setOnClickListener { open(r) }
            if (r.editable) v.setOnLongClickListener { playlistMenu(r); true }
            box.addView(v)
        }
    }

    /** "ล่าสุด ▾" dropdown: ล่าสุด / ก-ฮ / ฮ-ก with a check on the current choice. */
    private fun sortSheet(current: LibraryModel.Sort) {
        val ctx = requireContext()
        ctx.sheet { box, dlg ->
            box.sheetTitle("จัดเรียงตาม")
            LibraryModel.Sort.entries.forEach { sort ->
                val check = ImageView(ctx).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(ContextCompat.getColor(ctx, R.color.text1))
                    visibility = if (sort == current) View.VISIBLE else View.INVISIBLE
                }
                box.sheetRow(null, sort.label, end = check) { dlg.dismiss(); vm.setSort(sort) }
            }
        }
    }

    private fun iconOf(kind: LibraryModel.Kind) = when (kind) {
        LibraryModel.Kind.WATCH_LATER -> R.drawable.ic_watch_later
        LibraryModel.Kind.LIKED -> R.drawable.ic_thumb_up
        LibraryModel.Kind.PLAYLIST -> R.drawable.ic_playlist
        LibraryModel.Kind.DOWNLOADS -> R.drawable.ic_download
        LibraryModel.Kind.CREATE -> R.drawable.ic_add
    }

    private fun open(r: LibraryModel.Row) {
        val ctx = requireContext()
        when (r.kind) {
            LibraryModel.Kind.CREATE -> ctx.askName("เพลย์ลิสต์ใหม่", "") { vm.createPlaylist(it) }
            LibraryModel.Kind.LIKED -> startActivity(VideoListActivity.liked(ctx))
            LibraryModel.Kind.DOWNLOADS ->
                if (vm.ui.value.snapshot.downloads.isEmpty()) Toast.makeText(ctx, "ยังไม่มีวิดีโอที่ดาวน์โหลด", Toast.LENGTH_SHORT).show()
                else vm.select(LibraryModel.Filter.DOWNLOADED)
            LibraryModel.Kind.WATCH_LATER, LibraryModel.Kind.PLAYLIST -> r.playlistId?.let { startActivity(VideoListActivity.local(ctx, it)) }
        }
    }

    /** Long-press menu (instead of a ⋮ button) for the user's own playlists. */
    private fun playlistMenu(r: LibraryModel.Row) {
        val id = r.playlistId ?: return
        val ctx = requireContext()
        AlertDialog.Builder(ctx).setTitle(r.title).setItems(arrayOf("เปลี่ยนชื่อ", "ลบเพลย์ลิสต์")) { _, w ->
            if (w == 0) ctx.askName("เปลี่ยนชื่อเพลย์ลิสต์", r.title) { vm.renamePlaylist(id, it) }
            else AlertDialog.Builder(ctx).setMessage("ลบเพลย์ลิสต์ ${r.title} ใช่ไหม")
                .setPositiveButton("ลบ") { _, _ -> vm.deletePlaylist(id) }.setNegativeButton("ยกเลิก", null).show()
        }.show()
    }
}
