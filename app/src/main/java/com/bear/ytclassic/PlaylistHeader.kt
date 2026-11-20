package com.bear.ytclassic

import android.graphics.Bitmap
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.scale
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation

/** Top of a playlist page: blurred cover behind the cover image, title, owner, "N วิดีโอ" and "เล่นทั้งหมด". */
class PlaylistHeaderAdapter(private val onPlayAll: () -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    data class Info(val title: String, val owner: String?, val count: Int, val cover: String?, val canPlay: Boolean)

    private var info: Info? = null

    fun set(i: Info) { val had = info != null; info = i; if (had) notifyItemChanged(0) else notifyItemInserted(0) }

    override fun getItemCount() = if (info == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist_header, parent, false)
        val cover = v.findViewById<ImageView>(R.id.cover)
        val d = v.resources.displayMetrics.density
        cover.outlineProvider = RoundOutline(12 * d); cover.clipToOutline = true
        // 16:9 cover inside 20dp padding + 24dp margins on each side
        val w = (if (parent.width > 0) parent.width else v.resources.displayMetrics.widthPixels) - (88 * d).toInt()
        cover.layoutParams = cover.layoutParams.apply { height = w * 9 / 16 }
        return object : RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val i = info ?: return
        val v = holder.itemView
        val cover = v.findViewById<ImageView>(R.id.cover)
        if (cover.tag != i.cover) {
            cover.tag = i.cover
            cover.load(i.cover) { crossfade(true) }
            val blur = v.findViewById<ImageView>(R.id.blur)
            if (Build.VERSION.SDK_INT >= 31) {
                blur.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(80f, 80f, android.graphics.Shader.TileMode.CLAMP))
                blur.load(i.cover)
            } else blur.load(i.cover) { transformations(BlurTransformation()) }
        }
        v.findViewById<TextView>(R.id.plTitle).text = i.title
        v.findViewById<TextView>(R.id.plOwner).apply { text = i.owner; visibility = if (i.owner.isNullOrBlank()) View.GONE else View.VISIBLE }
        v.findViewById<TextView>(R.id.plCount).text = if (i.count >= 0) "${i.count} วิดีโอ" else ""
        v.findViewById<View>(R.id.btnPlayAll).apply {
            alpha = if (i.canPlay) 1f else 0.5f
            setOnClickListener { onPlayAll() }
        }
    }
}

/** Cheap blur for Android < 12: shrink to a few pixels, then scale back up with filtering. */
class BlurTransformation : Transformation() {
    override val cacheKey = "blur-small-24"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = 24
        val h = (input.height * w / input.width.coerceAtLeast(1)).coerceAtLeast(1)
        val small = input.scale(w, h, filter = true)
        val mid = small.scale(w * 4, h * 4, filter = true)
        return mid.scale(w * 16, h * 16, filter = true)
    }
}
