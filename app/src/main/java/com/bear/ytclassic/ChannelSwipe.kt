package com.bear.ytclassic

/** Maps channel tabs to ViewPager2 pages so a swipe and a tab tap stay on the same tab. */
object ChannelSwipe {
    fun pageFor(tabs: List<ChannelTabSpec>, id: String): Int = tabs.indexOfFirst { it.id == id }

    fun tabFor(tabs: List<ChannelTabSpec>, page: Int): String? = tabs.getOrNull(page)?.id

    fun tabAfterSwipe(tabs: List<ChannelTabSpec>, currentId: String, delta: Int): String? {
        val i = pageFor(tabs, currentId)
        if (i < 0) return currentId
        return tabFor(tabs, (i + delta).coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))
    }
}
