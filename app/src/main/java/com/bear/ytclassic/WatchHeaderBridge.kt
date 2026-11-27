package com.bear.ytclassic

/**
 * Forwards watch-header taps to plain lambdas. In 6.0.3 an anonymous Callbacks object's
 * `moreSheet()` called `moreSheet()`, which resolved to itself and overflowed the stack, so
 * the ⋯ button under every video crashed the app. Lambdas cannot resolve back to the callback.
 */
class WatchHeaderBridge(
    private val onMore: () -> Unit,
    private val onComments: () -> Unit,
    private val onShare: () -> Unit,
    private val onSave: () -> Unit,
    private val currentRating: () -> Int,
    private val onRate: (Int) -> Unit,
    private val onCommentsChanged: (String?, String?, String?) -> Unit,
    private val currentChapter: () -> String? = { null },
    private val onChapters: () -> Unit = {},
    private val onSeek: (Long) -> Unit = {},
) : WatchHeaderAdapter.Callbacks {
    override fun chapterTitle(): String? = currentChapter()
    override fun openChapters() = onChapters()
    override fun seekTo(ms: Long) = onSeek(ms)
    override fun openComments() = onComments()
    override fun moreSheet() = onMore()
    override fun share() = onShare()
    override fun save() = onSave()
    override fun rating(): Int = currentRating()
    override fun rate(value: Int) = onRate(value)
    override fun onComments(title: String?, preview: String?, avatar: String?) = onCommentsChanged(title, preview, avatar)
}
