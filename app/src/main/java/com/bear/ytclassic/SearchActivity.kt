package com.bear.ytclassic

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {
    override fun onResume() { super.onResume(); resAdapter.refreshProgress() }
    private lateinit var query: EditText
    private lateinit var suggestions: RecyclerView
    private lateinit var results: RecyclerView
    private lateinit var progress: View
    private lateinit var message: TextView
    private val sugAdapter = SuggestionAdapter({ submit(it) }, { fill(it) }, { askRemove(it) })
    private val resAdapter = VideoAdapter(false) { openItem(it) }
    private var filter = "all"
    private var lastQuery = ""
    private lateinit var filterBar: View
    private var sugJob: Job? = null
    private var searchJob: Job? = null
    private var pager: Pager? = null
    private var ignoreText = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge(lightBars = true)
        setContentView(R.layout.activity_search)
        MiniPlayerBar.attach(this)
        findViewById<View>(R.id.root).padForSystemBars()
        query = findViewById(R.id.query)
        suggestions = findViewById(R.id.suggestions)
        results = findViewById(R.id.results)
        progress = findViewById(R.id.progress)
        message = findViewById(R.id.message)
        val clear = findViewById<View>(R.id.btnClear)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        clear.setOnClickListener { query.setText(""); showSuggestions(); showKeyboard() }
        suggestions.layoutManager = LinearLayoutManager(this)
        suggestions.adapter = sugAdapter
        results.layoutManager = LinearLayoutManager(this)
        results.adapter = resAdapter
        results.onNearEnd { loadMore() }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (ignoreText) return
                showSuggestions()
                loadSuggestions(s?.toString().orEmpty())
            }
        })
        query.setOnEditorActionListener { _, actionId, ev ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || ev?.keyCode == KeyEvent.KEYCODE_ENTER) {
                submit(query.text.toString()); true
            } else false
        }
        filterBar = findViewById(R.id.filterBar)
        val chips = findViewById<com.google.android.material.chip.ChipGroup>(R.id.chips)
        val filters = Repo.searchFilters()
        if (filters.none { it.first == filter }) filter = filters.firstOrNull()?.first ?: "videos"
        filters.forEach { (id, label) ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = label; isCheckable = true; isCheckedIconVisible = false; isChecked = id == filter
                chipBackgroundColor = androidx.core.content.ContextCompat.getColorStateList(this@SearchActivity, R.color.chip_bg_state)
                setTextColor(androidx.core.content.ContextCompat.getColorStateList(this@SearchActivity, R.color.chip_text_state))
                setOnClickListener { if (filter != id) { filter = id; if (lastQuery.isNotEmpty()) submit(lastQuery) } }
            }
            chips.addView(chip)
        }
        if (filters.size < 2) chips.visibility = View.GONE
        query.setOnFocusChangeListener { _, has -> if (has) { showSuggestions(); loadSuggestions(query.text.toString()) } }
        query.requestFocus()
        loadSuggestions("")
        intent.getStringExtra("q")?.let { submit(it) }
    }

    private fun showKeyboard() {
        query.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(query, 0)
    }

    private fun showSuggestions() {
        suggestions.visibility = View.VISIBLE
        message.visibility = View.GONE
        filterBar.visibility = View.GONE
    }

    private fun fill(text: String) {
        query.setText(text); query.setSelection(text.length)
    }

    /** Recent searches matching what is typed (all of them when the box is empty), then YouTube's suggestions. */
    private fun historyFor(q: String): List<String> {
        val all = SearchHistory.all()
        return if (q.isBlank()) all else all.filter { it.contains(q.trim(), ignoreCase = true) }.take(4)
    }

    private fun loadSuggestions(q: String) {
        sugJob?.cancel()
        val hist = historyFor(q)
        sugAdapter.set(hist, hist.size)
        if (q.isBlank()) return
        sugJob = lifecycleScope.launch {
            delay(200)
            try {
                val s = Repo.suggestions(q).filterNot { x -> hist.any { it.equals(x, ignoreCase = true) } }
                sugAdapter.set(hist + s, hist.size)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { }
        }
    }

    private fun askRemove(q: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(q)
            .setMessage("นำออกจากประวัติการค้นหาใช่ไหม")
            .setPositiveButton("นำออก") { _, _ -> SearchHistory.remove(q); loadSuggestions(query.text.toString()) }
            .setNegativeButton("ยกเลิก", null).show()
    }

    private fun submit(q: String) {
        val text = q.trim()
        if (text.isEmpty()) return
        SearchHistory.add(text)
        ignoreText = true; query.setText(text); query.setSelection(text.length); ignoreText = false
        sugJob?.cancel()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
        suggestions.visibility = View.GONE
        lastQuery = text
        filterBar.visibility = View.VISIBLE
        results.visibility = View.VISIBLE
        message.visibility = View.GONE
        resAdapter.set(emptyList())
        progress.visibility = View.VISIBLE
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            try {
                val p = Repo.searchPager(text, filter)
                pager = p
                val items = p.next()
                resAdapter.set(items)
                if (items.isEmpty()) { message.setText(R.string.no_results); message.visibility = View.VISIBLE }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                message.text = Errors.list(e); message.visibility = View.VISIBLE
            } finally { progress.visibility = View.GONE }
        }
    }

    private fun loadMore() {
        val p = pager ?: return
        if (searchJob?.isActive == true || !p.hasMore) return
        resAdapter.loadingMore = true
        searchJob = lifecycleScope.launch {
            try { val more = p.next(); resAdapter.loadingMore = false; resAdapter.add(more) }
            catch (e: CancellationException) { throw e }
            catch (_: Throwable) { }
            finally { resAdapter.loadingMore = false }
        }
    }
}

class SuggestionAdapter(
    private val onPick: (String) -> Unit,
    private val onFill: (String) -> Unit,
    private val onRemove: (String) -> Unit,
) : RecyclerView.Adapter<SuggestionAdapter.VH>() {
    private val items = ArrayList<String>()
    private var historyCount = 0
    fun set(list: List<String>, history: Int = 0) { items.clear(); items.addAll(list); historyCount = history; notifyDataSetChanged() }
    override fun getItemCount() = items.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_suggestion, parent, false))
    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.text.text = s
        val hist = position < historyCount
        h.icon.setImageResource(if (hist) R.drawable.ic_history else R.drawable.ic_search)
        h.itemView.setOnClickListener { onPick(s) }
        if (hist) h.itemView.setOnLongClickListener { onRemove(s); true } else h.itemView.setOnLongClickListener(null)
        h.fill.setOnClickListener { onFill(s) }
    }
    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val text: TextView = v.findViewById(R.id.text)
        val fill: View = v.findViewById(R.id.fill)
        val icon: android.widget.ImageView = v.findViewById(R.id.icon)
    }
}
