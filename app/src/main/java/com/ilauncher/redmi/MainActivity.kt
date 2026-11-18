package com.ilauncher.redmi

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.min

/**
 * i-Лаунчер — домашний экран в стиле iPhone для Redmi 7A.
 *  - все приложения лежат на страницах (без отдельного списка), страницы листаются свайпом
 *  - иконки — скруглённые квадраты, внизу док и точки страниц
 *  - свайп вниз по экрану — поиск (как Spotlight)
 *  - долгое нажатие на иконку — «О приложении» / «Удалить»
 */
class MainActivity : Activity() {

    private class AppItem(
        val label: String,
        val pkg: String,
        val cls: String,
        val icon: Drawable
    )

    private val cols = 4
    private val rows = 5

    private var apps: List<AppItem> = emptyList()
    private var results: List<AppItem> = emptyList()
    private var pageCount = 1
    private var spotOpen = false

    private lateinit var root: FrameLayout
    private lateinit var pager: Pager
    private lateinit var pagesBox: LinearLayout
    private lateinit var dots: LinearLayout
    private lateinit var dock: LinearLayout
    private lateinit var spot: LinearLayout
    private lateinit var spotSearch: EditText
    private lateinit var spotList: ListView
    private lateinit var gestures: GestureDetector
    private val spotAdapter = SpotAdapter()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- жизненный цикл

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

        root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            insets
        }

        root.addView(buildHome(), matchParent())
        spot = buildSpotlight()
        spot.visibility = View.GONE
        root.addView(spot, matchParent())
        setContentView(root)

        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null || spotOpen) return false
                val dy = e2.y - e1.y
                val dx = e2.x - e1.x
                if (dy > dp(80) && abs(dy) > abs(dx) && velocityY > 1000f) {
                    openSpotlight()
                    return true
                }
                return false
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadApps()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // кнопка «Домой»: закрыть поиск и вернуться на первую страницу
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            closeSpotlight()
            pager.goTo(0)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (spotOpen) closeSpotlight()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun matchParent() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )

    // ---------------------------------------------------------------- страницы с иконками

    /** Горизонтальный «пейджер»: листает по одной странице и примагничивается. */
    private inner class Pager(ctx: android.content.Context) : HorizontalScrollView(ctx) {
        var page = 0
        private var flung = false

        private fun nearest(): Int = if (width == 0) 0 else (scrollX + width / 2) / width

        fun goTo(p: Int) {
            page = p.coerceIn(0, pageCount - 1)
            smoothScrollTo(page * width, 0)
            updateDots()
        }

        override fun fling(velocityX: Int) {
            flung = true
            val target = when {
                velocityX > 400 -> page + 1
                velocityX < -400 -> page - 1
                else -> nearest()
            }
            goTo(target)
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.action == MotionEvent.ACTION_DOWN) flung = false
            val r = super.onTouchEvent(ev)
            if ((ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) && !flung) {
                goTo(nearest())
            }
            return r
        }
    }

    private fun buildHome(): LinearLayout {
        val home = LinearLayout(this)
        home.orientation = LinearLayout.VERTICAL

        pager = Pager(this)
        pager.isHorizontalScrollBarEnabled = false
        pager.overScrollMode = View.OVER_SCROLL_NEVER
        pagesBox = LinearLayout(this)
        pagesBox.orientation = LinearLayout.HORIZONTAL
        pager.addView(
            pagesBox,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        home.addView(pager, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        dots = LinearLayout(this)
        dots.orientation = LinearLayout.HORIZONTAL
        dots.gravity = Gravity.CENTER
        dots.setPadding(0, dp(6), 0, dp(10))
        home.addView(
            dots,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        dock = buildDock()
        val dp1 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(88))
        dp1.setMargins(dp(12), 0, dp(12), dp(10))
        home.addView(dock, dp1)
        return home
    }

    private fun squircle(drawable: Drawable, size: Int): ImageView {
        val iv = ImageView(this)
        val d = drawable.constantState?.newDrawable(resources) ?: drawable
        iv.setImageDrawable(d)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        val bg = GradientDrawable()
        bg.setColor(Color.WHITE)
        bg.cornerRadius = size * 0.225f
        iv.background = bg
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.225f)
            }
        }
        iv.clipToOutline = true
        iv.layoutParams = LinearLayout.LayoutParams(size, size)
        return iv
    }

    private fun buildPages() {
        pagesBox.removeAllViews()
        val w = resources.displayMetrics.widthPixels
        var idx = 0
        var p = 0
        do {
            val first = p == 0
            val perPage = if (first) (rows - 1) * cols else rows * cols
            val slice = apps.subList(min(idx, apps.size), min(apps.size, idx + perPage))
            pagesBox.addView(
                buildPage(slice, first),
                LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            idx += perPage
            p++
        } while (idx < apps.size)
        pageCount = p
        pager.page = pager.page.coerceIn(0, pageCount - 1)
        updateDots()
        pager.post { pager.scrollTo(pager.page * pager.width, 0) }
    }

    private fun buildPage(slice: List<AppItem>, first: Boolean): LinearLayout {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(dp(12), dp(8), dp(12), dp(4))
        var i = 0
        for (r in 0 until rows) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            if (first && r == 0) {
                row.addView(
                    buildWidget(),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            } else {
                for (c in 0 until cols) {
                    val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    if (i < slice.size) {
                        row.addView(buildCell(slice[i]), lp)
                        i++
                    } else {
                        row.addView(View(this), lp)
                    }
                }
            }
            page.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            )
        }
        return page
    }

    private fun buildCell(item: AppItem): LinearLayout {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        cell.gravity = Gravity.CENTER
        cell.addView(squircle(item.icon, dp(58)))

        val label = TextView(this)
        label.text = item.label
        label.textSize = 11f
        label.setTextColor(Color.WHITE)
        label.setShadowLayer(3f, 0f, 1f, Color.argb(120, 0, 0, 0))
        label.maxLines = 1
        label.ellipsize = TextUtils.TruncateAt.END
        label.gravity = Gravity.CENTER
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4)
        cell.addView(label, lp)

        cell.setOnClickListener { launch(item) }
        cell.setOnLongClickListener {
            showAppMenu(item)
            true
        }
        return cell
    }

    /** Широкий виджет «время и дата» на первой странице. */
    private fun buildWidget(): LinearLayout {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(20), dp(8), dp(20), dp(8))
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#40FFFFFF"))
        bg.cornerRadius = dp(24).toFloat()
        card.background = bg

        val clock = TextClock(this)
        clock.format24Hour = "HH:mm"
        clock.format12Hour = "HH:mm"
        clock.textSize = 44f
        clock.setTextColor(Color.WHITE)
        clock.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        card.addView(clock)

        val date = TextClock(this)
        date.format24Hour = "EEEE, d MMMM"
        date.format12Hour = "EEEE, d MMMM"
        date.textSize = 16f
        date.setTextColor(Color.parseColor("#E6FFFFFF"))
        card.addView(date)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        lp.setMargins(dp(4), dp(4), dp(4), dp(8))
        card.layoutParams = lp
        return card
    }

    private fun updateDots() {
        dots.removeAllViews()
        for (i in 0 until pageCount) {
            val d = View(this)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(if (i == pager.page) Color.WHITE else Color.parseColor("#66FFFFFF"))
            d.background = bg
            val lp = LinearLayout.LayoutParams(dp(7), dp(7))
            lp.setMargins(dp(4), 0, dp(4), 0)
            dots.addView(d, lp)
        }
    }

    // ---------------------------------------------------------------- док

    private fun findActivity(candidates: List<Intent>): Intent? {
        for (i in candidates) {
            val ri: ResolveInfo? = packageManager.queryIntentActivities(i, 0)
                .firstOrNull { it.activityInfo.packageName != "android" }
            if (ri != null) {
                val launch = Intent(i)
                launch.component = ComponentName(ri.activityInfo.packageName, ri.activityInfo.name)
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return launch
            }
        }
        return null
    }

    private fun buildDock(): LinearLayout {
        val d = LinearLayout(this)
        d.orientation = LinearLayout.HORIZONTAL
        d.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#40FFFFFF"))
        bg.cornerRadius = dp(30).toFloat()
        d.background = bg

        val items = listOf(
            listOf(Intent(Intent.ACTION_DIAL)),
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
            ),
            listOf(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
                Intent(Intent.ACTION_VIEW, Uri.parse("http://www.google.com"))
            ),
            listOf(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        )

        for (candidates in items) {
            val target = findActivity(candidates) ?: continue
            val comp = target.component ?: continue
            val icon = try {
                packageManager.getActivityIcon(comp)
            } catch (e: Exception) {
                continue
            }
            val cell = LinearLayout(this)
            cell.gravity = Gravity.CENTER
            cell.addView(squircle(icon, dp(60)))
            cell.setOnClickListener {
                try {
                    startActivity(target)
                } catch (e: Exception) {
                    Toast.makeText(this, "не открылось", Toast.LENGTH_SHORT).show()
                }
            }
            d.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        return d
    }

    // ---------------------------------------------------------------- поиск (Spotlight)

    private fun buildSpotlight(): LinearLayout {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(Color.parseColor("#E61B1B2F"))
        col.setPadding(dp(14), dp(16), dp(14), 0)
        col.isClickable = true

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL

        spotSearch = EditText(this)
        spotSearch.hint = "Поиск"
        spotSearch.setHintTextColor(Color.parseColor("#99FFFFFF"))
        spotSearch.setTextColor(Color.WHITE)
        spotSearch.textSize = 17f
        spotSearch.setSingleLine(true)
        spotSearch.setPadding(dp(16), dp(10), dp(16), dp(10))
        val sb = GradientDrawable()
        sb.setColor(Color.parseColor("#33FFFFFF"))
        sb.cornerRadius = dp(14).toFloat()
        spotSearch.background = sb
        spotSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterSpot()
            }
        })
        bar.addView(spotSearch, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val cancel = TextView(this)
        cancel.text = "Отмена"
        cancel.textSize = 16f
        cancel.setTextColor(Color.WHITE)
        cancel.setPadding(dp(14), dp(10), dp(4), dp(10))
        cancel.setOnClickListener { closeSpotlight() }
        bar.addView(cancel)
        col.addView(bar)

        spotList = ListView(this)
        spotList.divider = null
        spotList.isVerticalScrollBarEnabled = false
        spotList.adapter = spotAdapter
        spotList.setOnItemClickListener { _, _, position, _ -> launch(results[position]) }
        spotList.setOnItemLongClickListener { _, _, position, _ ->
            showAppMenu(results[position])
            true
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        lp.topMargin = dp(12)
        col.addView(spotList, lp)
        return col
    }

    private fun filterSpot() {
        val q = spotSearch.text.toString().trim().lowercase()
        results = if (q.isEmpty()) apps else apps.filter { it.label.lowercase().contains(q) }
        spotAdapter.notifyDataSetChanged()
    }

    private fun openSpotlight() {
        if (spotOpen) return
        spotOpen = true
        filterSpot()
        spot.alpha = 0f
        spot.visibility = View.VISIBLE
        spot.animate().alpha(1f).setDuration(180).start()
        spotSearch.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(spotSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSpotlight() {
        if (!spotOpen) return
        spotOpen = false
        spotSearch.setText("")
        spotSearch.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(spotSearch.windowToken, 0)
        spot.animate().alpha(0f).setDuration(150).withEndAction {
            if (!spotOpen) spot.visibility = View.GONE
        }.start()
    }

    private inner class SpotAdapter : BaseAdapter() {
        override fun getCount(): Int = results.size
        override fun getItem(position: Int): Any = results[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = results[position]
            val row = LinearLayout(this@MainActivity)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(4), dp(8), dp(4), dp(8))
            row.addView(squircle(item.icon, dp(44)))
            val label = TextView(this@MainActivity)
            label.text = item.label
            label.textSize = 17f
            label.setTextColor(Color.WHITE)
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            val lp = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            lp.leftMargin = dp(14)
            row.addView(label, lp)
            return row
        }
    }

    // ---------------------------------------------------------------- приложения

    private fun loadApps() {
        thread {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    AppItem(
                        it.loadLabel(pm).toString(),
                        it.activityInfo.packageName,
                        it.activityInfo.name,
                        it.loadIcon(pm)
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                apps = list
                buildPages()
                if (spotOpen) filterSpot()
            }
        }
    }

    private fun launch(item: AppItem) {
        val i = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(item.pkg, item.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "не открылось", Toast.LENGTH_SHORT).show()
        }
        closeSpotlight()
    }

    private fun showAppMenu(item: AppItem) {
        AlertDialog.Builder(this)
            .setTitle(item.label)
            .setItems(arrayOf("О приложении", "Удалить")) { _, which ->
                try {
                    val uri = Uri.parse("package:" + item.pkg)
                    if (which == 0) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
                    } else {
                        startActivity(Intent(Intent.ACTION_DELETE, uri))
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "не получилось", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
