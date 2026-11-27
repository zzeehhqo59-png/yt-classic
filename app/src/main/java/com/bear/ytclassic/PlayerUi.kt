package com.bear.ytclassic

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlinx.coroutines.launch

/**
 * YouTube-style scrubber: thin red bar with a red knob; pressing enlarges the knob and thickens the bar,
 * dragging moves it (the caller shows the storyboard preview) and the seek happens on release.
 * The bar line sits [lineFromBottom] px above the view's bottom edge.
 */
class YtSeekBar @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    interface Listener {
        fun onScrubStart(pos: Long)
        fun onScrubMove(pos: Long, x: Float)
        fun onScrubStop(pos: Long, cancelled: Boolean)
    }
    private val d = resources.displayMetrics.density
    var duration = 0L; set(v) { field = v; invalidate() }
    var position = 0L; set(v) { if (!scrubbing) { field = v; invalidate() } }
    var buffered = 0L; set(v) { field = v; invalidate() }
    var showKnob = true; set(v) { field = v; invalidate() }
    var lineFromBottom = 2f * d; set(v) { field = v; invalidate() }
    /** Chapter starts (ms): the bar is drawn in pieces with a small gap before each. */
    var chapterStarts: List<Long> = emptyList(); set(v) { field = v; invalidate() }
    var listener: Listener? = null
    var scrubbing = false; private set
    private var scrubPos = 0L
    private val pBack = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
    private val pBuf = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x80FFFFFF.toInt() }
    private val pPlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0000.toInt() }
    private val r = RectF()

    private fun left() = paddingLeft.toFloat()
    private fun right() = (width - paddingRight).toFloat()
    private fun xOf(ms: Long) = left() + (right() - left()) * (if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f)
    private fun posOf(x: Float) = if (duration <= 0) 0L else (((x - left()) / (right() - left())).coerceIn(0f, 1f) * duration).toLong()
    /** Where the knob is now (for placing the preview above it). */
    fun knobX() = xOf(if (scrubbing) scrubPos else position)

    override fun onDraw(cv: Canvas) {
        val th = if (scrubbing) 5f * d else if (showKnob) 3f * d else 2f * d
        val cy = height - lineFromBottom
        val pieces = Chapters.ranges(chapterStarts, duration)
        if (pieces.size < 2) {
            r.set(left(), cy - th / 2, right(), cy + th / 2); cv.drawRect(r, pBack)
            if (duration > 0) { r.right = xOf(buffered); cv.drawRect(r, pBuf); r.right = knobX(); cv.drawRect(r, pPlay) }
        } else {
            val gap = 2f * d
            val bufX = xOf(buffered); val playX = knobX()
            pieces.forEachIndexed { i, p ->
                val x0 = xOf(p.first) + if (i > 0) gap / 2 else 0f
                val x1 = xOf(p.last + 1) - if (i < pieces.size - 1) gap / 2 else 0f
                if (x1 <= x0) return@forEachIndexed
                r.set(x0, cy - th / 2, x1, cy + th / 2); cv.drawRect(r, pBack)
                if (bufX > x0) { r.right = minOf(x1, bufX); cv.drawRect(r, pBuf) }
                if (playX > x0) { r.right = minOf(x1, playX); cv.drawRect(r, pPlay) }
            }
        }
        if (duration > 0 && (showKnob || scrubbing)) cv.drawCircle(knobX(), cy, if (scrubbing) 9f * d else 6f * d, pPlay)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || duration <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true; scrubPos = posOf(e.x); listener?.onScrubStart(scrubPos); listener?.onScrubMove(scrubPos, knobX()); invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (scrubbing) { scrubPos = posOf(e.x); listener?.onScrubMove(scrubPos, knobX()); invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (scrubbing) {
                scrubbing = false
                val cancel = e.actionMasked == MotionEvent.ACTION_CANCEL
                if (!cancel) position = scrubPos
                listener?.onScrubStop(scrubPos, cancel); invalidate()
            }
        }
        return true
    }
}

/** The small autoplay pill switch YouTube shows at the top of the player (▶ = on, ❚❚ = off). */
class AutoplaySwitch @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : View(c, a) {
    private val d = resources.displayMetrics.density
    var checked = true; set(v) { field = v; contentDescription = if (v) "เล่นอัตโนมัติเปิดอยู่" else "เล่นอัตโนมัติปิดอยู่"; invalidate() }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F0F0F.toInt() }
    private val path = Path()
    private val r = RectF()
    init { checked = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((48 * d).toInt(), (48 * d).toInt())

    override fun onDraw(cv: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val tw = 30 * d; val th = 13 * d; val tr = 10 * d
        track.color = if (checked) 0xB3FFFFFF.toInt() else 0x80FFFFFF.toInt()
        r.set(cx - tw / 2, cy - th / 2, cx + tw / 2, cy + th / 2); cv.drawRoundRect(r, th / 2, th / 2, track)
        val tx = if (checked) cx + tw / 2 - tr + 2 * d else cx - tw / 2 + tr - 2 * d
        thumb.color = if (checked) 0xFFFFFFFF.toInt() else 0xFFCFCFCF.toInt()
        cv.drawCircle(tx, cy, tr, thumb)
        if (checked) {
            path.reset(); path.moveTo(tx - 3 * d, cy - 4.5f * d); path.lineTo(tx + 4.5f * d, cy); path.lineTo(tx - 3 * d, cy + 4.5f * d); path.close(); cv.drawPath(path, glyph)
        } else {
            cv.drawRect(tx - 3.5f * d, cy - 4.5f * d, tx - 1f * d, cy + 4.5f * d, glyph)
            cv.drawRect(tx + 1f * d, cy - 4.5f * d, tx + 3.5f * d, cy + 4.5f * d, glyph)
        }
    }
}

/** Sprite-sheet storyboard (StreamInfo.previewFrames): loads sheets with OkHttp, crops the frame for a time. */
class Storyboard(private val fs: org.schabi.newpipe.extractor.stream.Frameset) {
    private val sheets = HashMap<Int, android.graphics.Bitmap>()
    private val loading = HashSet<Int>()
    private val scope = kotlinx.coroutines.MainScope()
    fun release() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    /** Frame at [ms], or null while its sheet is still loading ([ready] is called once it arrives). */
    fun frame(ms: Long, ready: () -> Unit): android.graphics.Bitmap? {
        val b = fs.getFrameBoundsAt(ms)
        val page = b[0]
        val sheet = sheets[page]
        if (sheet == null) {
            if (page !in loading && page < fs.urls.size) {
                loading.add(page)
                val url = fs.urls[page]
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val bmp = runCatching {
                        DownloaderImpl.instance.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
                            r.body.byteStream().use { android.graphics.BitmapFactory.decodeStream(it) }
                        }
                    }.getOrNull()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loading.remove(page); if (bmp != null) { sheets[page] = bmp; ready() }
                    }
                }
            }
            return null
        }
        val l = b[1].coerceIn(0, sheet.width - 1); val t = b[2].coerceIn(0, sheet.height - 1)
        val w = (b[3] - b[1]).coerceIn(1, sheet.width - l); val h = (b[4] - b[2]).coerceIn(1, sheet.height - t)
        return runCatching { android.graphics.Bitmap.createBitmap(sheet, l, t, w, h) }.getOrNull()
    }

    companion object {
        /** The sharpest storyboard level (usually 160×90). */
        fun of(info: org.schabi.newpipe.extractor.stream.StreamInfo?): Storyboard? =
            info?.previewFrames?.filter { it.urls.isNotEmpty() && it.frameWidth > 0 }?.maxByOrNull { it.frameWidth }?.let { Storyboard(it) }
    }
}

