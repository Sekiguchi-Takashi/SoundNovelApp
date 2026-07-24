package com.sekiguchi.soundnovel

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var novelView: NovelView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        novelView = NovelView(this)
        setContentView(novelView)
    }

    override fun onPause() { super.onPause(); novelView.onAppPause() }
    override fun onResume() { super.onResume(); novelView.onAppResume() }
    override fun onDestroy() { super.onDestroy(); novelView.release() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!novelView.handleBack()) super.onBackPressed()
    }
}

class NovelView(ctx: Context) : View(ctx) {

    companion object {
        const val MODE_TITLE = 0
        const val MODE_GAME = 1
        const val MODE_CHOICE = 2
        const val MODE_TITLECARD = 3
        const val MODE_END = 4
        const val MODE_CHAPTERS = 5   // 章選択
        const val MODE_PASSCODE = 6   // パスコード入力
        const val TYPE_INTERVAL = 28L
    }

    private val prefs = ctx.getSharedPreferences("novel_save", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // ---- マニフェスト ----
    private var novelTitle = "サウンドノベル"
    private var startEpisode = "ep01"

    // ---- 章 ----
    private data class Chapter(val id: String, val title: String, val start: String,
                              val lockedByDefault: Boolean, val passcode: String)
    private val chapters = mutableListOf<Chapter>()
    private val chapterRects = mutableListOf<RectF>()

    // ---- パスコード入力 ----
    private val padRects = Array(12) { RectF() }   // 0-9, ←, OK
    private var passInput = ""
    private var passError = false
    private var passTargetChapter = 1              // どの章を解錠しようとしているか
    private val backToTitleRect = RectF()

    // ---- 状態 ----
    private var mode = MODE_TITLE
    private var episodeId = ""
    private var nodes: JSONArray = JSONArray()
    private var nodeIndex = -1

    // ---- テキスト ----
    private val pageLines = mutableListOf<String>()
    private var fullLine = ""
    private var shownChars = 0
    private var typing = false

    // ---- 画像・音 ----
    private var bgBitmap: Bitmap? = null
    private var bgName = ""
    private var titleBg: Bitmap? = null
    private var player: MediaPlayer? = null
    private var currentSe = ""

    // ---- 選択肢 ----
    private var choiceQ = ""
    private var choiceA = ""
    private var choiceB = ""
    private var gotoA = ""
    private var gotoB = ""
    private val rectA = RectF()
    private val rectB = RectF()

    // ---- タイトルカード / エンド ----
    private var cardText = ""
    private var endLines = listOf<String>()

    // ---- 描画 ----
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        setShadowLayer(6f, 0f, 3f, Color.BLACK)
    }
    private val paintVoice = Paint(paintText).apply { color = Color.rgb(180, 220, 235) }
    private val paintUi = Paint(Paint.ANTI_ALIAS_FLAG)
    private var blink = 0f

    private val ticker = object : Runnable {
        override fun run() {
            var dirty = false
            if (typing) {
                shownChars++
                if (shownChars >= fullLine.length) { shownChars = fullLine.length; typing = false }
                dirty = true
            }
            blink += 0.09f
            if (mode == MODE_GAME && !typing) dirty = true
            if (dirty) invalidate()
            handler.postDelayed(this, TYPE_INTERVAL)
        }
    }

    init {
        loadManifest()
        titleBg = loadBitmap("cottage_ext")
        handler.postDelayed(ticker, TYPE_INTERVAL)
    }

    // ================= 読み込み =================

    private fun loadManifest() {
        try {
            val j = JSONObject(context.assets.open("scenario/manifest.json")
                .bufferedReader().use { it.readText() })
            novelTitle = j.optString("novelTitle", novelTitle)
            startEpisode = j.optString("start", startEpisode)
            chapters.clear()
            val arr = j.optJSONArray("chapters")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    chapters.add(Chapter(
                        c.optString("id", "ch${i + 1}"),
                        c.optString("title", "第${i + 1}章"),
                        c.optString("start", startEpisode),
                        c.optBoolean("locked", false),
                        c.optString("passcode", "")
                    ))
                }
            }
        } catch (_: Exception) {}
    }

    private fun isChapterUnlocked(idx: Int): Boolean {
        if (idx < 0 || idx >= chapters.size) return false
        if (!chapters[idx].lockedByDefault) return true
        return prefs.getBoolean("unlock_${chapters[idx].id}", false)
    }

    private fun loadBitmap(name: String): Bitmap? = try {
        context.assets.open("bg/$name.jpg").use { BitmapFactory.decodeStream(it) }
    } catch (_: Exception) { null }

    private fun loadEpisode(id: String, startIndex: Int): Boolean {
        return try {
            val txt = context.assets.open("scenario/$id.json")
                .bufferedReader().use { it.readText() }
            nodes = JSONObject(txt).getJSONArray("nodes")
            episodeId = id
            nodeIndex = startIndex - 1
            pageLines.clear()
            typing = false
            fullLine = ""
            true
        } catch (_: Exception) {
            // 未収録エピソード → 「つづく」
            showEnd(listOf("── 続く ──", "", "次の一節は、まだ届いていない。", "更新をお待ちください。"), keepSave = true)
            false
        }
    }

    // ================= 進行 =================

    // 現在プレイ中の章index
    private var currentChapter = 0

    private fun epKey() = "ep_ch$currentChapter"
    private fun idxKey() = "idx_ch$currentChapter"

    // 章を指定して開始（続きがあれば再開）
    private fun startChapter(chapterIdx: Int, fromSave: Boolean) {
        currentChapter = chapterIdx
        val chStart = if (chapters.isNotEmpty()) chapters[chapterIdx].start else startEpisode
        if (fromSave && prefs.contains(epKey())) {
            val ep = prefs.getString(epKey(), chStart) ?: chStart
            val idx = prefs.getInt(idxKey(), 0)
            if (loadEpisode(ep, idx)) {
                for (i in 0 until idx.coerceAtMost(nodes.length())) {
                    val nd = nodes.getJSONObject(i)
                    when (nd.optString("t")) {
                        "bg" -> setBg(nd.optString("v"))
                        "se" -> playSe(nd.optString("v"))
                    }
                }
                mode = MODE_GAME; advanceNode()
            }
        } else {
            if (loadEpisode(chStart, 0)) { mode = MODE_GAME; advanceNode() }
        }
        invalidate()
    }

    private fun hasChapterSave(chapterIdx: Int) = prefs.contains("ep_ch$chapterIdx")

    private fun saveProgress() {
        prefs.edit().putString(epKey(), episodeId).putInt(idxKey(), nodeIndex).apply()
    }

    private fun advanceNode() {
        while (true) {
            nodeIndex++
            if (nodeIndex >= nodes.length()) { showEnd(listOf("── 続く ──"), keepSave = true); return }
            val node = nodes.getJSONObject(nodeIndex)
            when (node.optString("t")) {
                "bg" -> setBg(node.optString("v"))
                "se" -> playSe(node.optString("v"))
                "p" -> pageLines.clear()
                "l" -> {
                    val text = node.optString("v")
                    ensureCapacity(text)
                    fullLine = text
                    shownChars = 0
                    typing = true
                    mode = MODE_GAME
                    saveProgress()
                    invalidate()
                    return
                }
                "title" -> {
                    cardText = node.optString("v")
                    pageLines.clear()
                    mode = MODE_TITLECARD
                    saveProgress()
                    invalidate()
                    return
                }
                "choice" -> {
                    choiceQ = node.optString("q")
                    val a = node.getJSONObject("a")
                    val b = node.getJSONObject("b")
                    choiceA = a.optString("v"); gotoA = a.optString("goto")
                    choiceB = b.optString("v"); gotoB = b.optString("goto")
                    mode = MODE_CHOICE
                    saveProgress()
                    invalidate()
                    return
                }
                "goto" -> {
                    if (loadEpisode(node.optString("v"), 0)) continue else return
                }
                "end" -> {
                    showEnd(listOf("── 了 ──", "", "もう一つの結末を、あなたは見た。"), keepSave = false)
                    return
                }
            }
        }
    }

    private fun showEnd(lines: List<String>, keepSave: Boolean) {
        endLines = lines
        mode = MODE_END
        playSe("stop")
        if (!keepSave) prefs.edit().remove(epKey()).remove(idxKey()).apply()
        invalidate()
    }

    private fun setBg(name: String) {
        if (name == bgName) return
        bgName = name
        bgBitmap = loadBitmap(name)
    }

    // ================= 音 =================

    private fun playSe(name: String) {
        if (name == currentSe) return
        currentSe = name
        player?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        player = null
        if (name == "stop" || name.isEmpty()) return
        try {
            val afd = context.assets.openFd("se/se_$name.wav")
            player = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                isLooping = true
                setVolume(0.6f, 0.6f)
                prepare()
                start()
            }
            afd.close()
        } catch (_: Exception) { player = null }
    }

    fun onAppPause() { try { player?.pause() } catch (_: Exception) {} }
    fun onAppResume() { try { player?.start() } catch (_: Exception) {} }
    fun release() {
        handler.removeCallbacks(ticker)
        player?.let { try { it.stop(); it.release() } catch (_: Exception) {} }
        player = null
    }

    fun handleBack(): Boolean {
        when (mode) {
            MODE_TITLE -> return false
            MODE_PASSCODE -> { mode = MODE_CHAPTERS; passInput = ""; passError = false }
            MODE_CHAPTERS -> { mode = MODE_TITLE }
            else -> { playSe("stop"); mode = MODE_TITLE }
        }
        invalidate()
        return true
    }

    // ================= テキスト整形 =================

    private fun textSizePx() = width * 0.048f
    private fun marginPx() = width * 0.09f
    private fun lineHeightPx() = textSizePx() * 1.85f
    private fun textTopPx() = height * 0.075f
    private fun textBottomPx() = height * 0.90f

    private fun wrap(s: String, paint: Paint, maxW: Float): List<String> {
        if (s.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        var start = 0
        while (start < s.length) {
            var count = paint.breakText(s, start, s.length, true, maxW, null)
            if (count <= 0) count = 1
            out.add(s.substring(start, start + count))
            start += count
        }
        return out
    }

    private fun ensureCapacity(next: String) {
        paintText.textSize = textSizePx()
        val maxW = width - marginPx() * 2
        val capacity = ((textBottomPx() - textTopPx()) / lineHeightPx()).toInt()
        val wrapped = wrap(next, paintText, maxW).size
        var used = 0
        for (l in pageLines) used += wrap(l, paintText, maxW).size
        if (used + wrapped > capacity) pageLines.clear()
    }

    // ================= 入力 =================

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_DOWN) return true
        val x = event.x; val y = event.y
        when (mode) {
            MODE_TITLE -> {
                for (i in titleBtnRects.indices) {
                    if (titleBtnRects[i].contains(x, y)) { onTitleButton(i); break }
                }
            }
            MODE_CHAPTERS -> {
                if (backToTitleRect.contains(x, y)) { mode = MODE_TITLE }
                else for (i in chapterRects.indices) {
                    if (chapterRects[i].contains(x, y)) { onChapterTap(i); break }
                }
            }
            MODE_PASSCODE -> {
                if (backToTitleRect.contains(x, y)) { mode = MODE_CHAPTERS; passInput = ""; passError = false }
                else for (i in padRects.indices) {
                    if (padRects[i].contains(x, y)) { onPadTap(i); break }
                }
            }
            MODE_TITLECARD -> { mode = MODE_GAME; advanceNode() }
            MODE_GAME -> {
                if (typing) { shownChars = fullLine.length; typing = false }
                else {
                    if (fullLine.isNotEmpty()) pageLines.add(fullLine)
                    fullLine = ""
                    advanceNode()
                }
            }
            MODE_CHOICE -> {
                if (rectA.contains(x, y)) { playSe("stop"); if (loadEpisode(gotoA, 0)) { mode = MODE_GAME; advanceNode() } }
                else if (rectB.contains(x, y)) { playSe("stop"); if (loadEpisode(gotoB, 0)) { mode = MODE_GAME; advanceNode() } }
            }
            MODE_END -> { playSe("stop"); mode = MODE_TITLE }
        }
        invalidate()
        return true
    }

    // タイトル画面のボタン: 0=はじめから, 1=つづきから, 2=章を選ぶ, 3=パスコード入力
    private val titleBtnRects = Array(4) { RectF() }

    private fun onTitleButton(i: Int) {
        when (i) {
            0 -> startChapter(0, false)                       // 第一章 最初から
            1 -> if (hasChapterSave(0)) startChapter(0, true) // 第一章 つづき
            2 -> { mode = MODE_CHAPTERS }                     // 章選択へ
            3 -> { passTargetChapter = firstLockedChapter(); passInput = ""; passError = false; mode = MODE_PASSCODE }
        }
    }

    private fun firstLockedChapter(): Int {
        for (i in chapters.indices) if (chapters[i].lockedByDefault && !isChapterUnlocked(i)) return i
        // すべて解錠済みなら最後のロック章
        for (i in chapters.indices.reversed()) if (chapters[i].lockedByDefault) return i
        return if (chapters.size > 1) 1 else 0
    }

    private fun onChapterTap(i: Int) {
        if (isChapterUnlocked(i)) {
            startChapter(i, hasChapterSave(i))
        } else {
            passTargetChapter = i; passInput = ""; passError = false; mode = MODE_PASSCODE
        }
    }

    private fun onPadTap(i: Int) {
        passError = false
        when (i) {
            in 0..9 -> if (passInput.length < 4) passInput += i.toString()
            10 -> if (passInput.isNotEmpty()) passInput = passInput.dropLast(1)  // ←
            11 -> submitPasscode()                                               // OK
        }
    }

    private fun submitPasscode() {
        val ch = chapters.getOrNull(passTargetChapter) ?: return
        if (passInput == ch.passcode && ch.passcode.isNotEmpty()) {
            prefs.edit().putBoolean("unlock_${ch.id}", true).apply()
            startChapter(passTargetChapter, hasChapterSave(passTargetChapter))
        } else {
            passError = true; passInput = ""
        }
    }

    // ================= 描画 =================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        when (mode) {
            MODE_TITLE -> drawTitle(canvas)
            MODE_CHAPTERS -> drawChapters(canvas)
            MODE_PASSCODE -> drawPasscode(canvas)
            MODE_TITLECARD -> drawTitleCard(canvas)
            MODE_GAME -> { drawBg(canvas); drawTextPage(canvas) }
            MODE_CHOICE -> { drawBg(canvas); drawChoice(canvas) }
            MODE_END -> drawEnd(canvas)
        }
    }

    private fun drawBg(canvas: Canvas, bmp: Bitmap? = bgBitmap, dim: Int = 130) {
        bmp?.let {
            val scale = maxOf(width.toFloat() / it.width, height.toFloat() / it.height)
            val m = Matrix()
            m.postScale(scale, scale)
            m.postTranslate((width - it.width * scale) / 2f, (height - it.height * scale) / 2f)
            canvas.drawBitmap(it, m, paintUi)
        }
        paintUi.color = Color.argb(dim, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintUi)
    }

    private fun drawTextPage(canvas: Canvas) {
        paintText.textSize = textSizePx()
        paintVoice.textSize = textSizePx()
        val maxW = width - marginPx() * 2
        var y = textTopPx() + textSizePx()
        val lh = lineHeightPx()
        for (line in pageLines) {
            val p = if (line.startsWith("«")) paintVoice else paintText
            for (sub in wrap(line, p, maxW)) {
                canvas.drawText(sub, marginPx(), y, p)
                y += lh
            }
        }
        if (fullLine.isNotEmpty()) {
            val p = if (fullLine.startsWith("«")) paintVoice else paintText
            val shown = fullLine.substring(0, shownChars)
            for (sub in wrap(shown, p, maxW)) {
                canvas.drawText(sub, marginPx(), y, p)
                y += lh
            }
        }
        if (!typing) {
            val alpha = ((Math.sin(blink.toDouble()) * 0.5 + 0.5) * 255).toInt()
            paintUi.color = Color.argb(alpha, 255, 255, 255)
            paintUi.textSize = textSizePx()
            paintUi.textAlign = Paint.Align.RIGHT
            canvas.drawText("▼", width - marginPx(), height * 0.945f, paintUi)
            paintUi.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawMenuButton(canvas: Canvas, r: RectF, label: String, enabled: Boolean, accent: Boolean = false) {
        paintUi.style = Paint.Style.FILL
        paintUi.color = if (enabled) Color.argb(205, 22, 18, 24) else Color.argb(120, 22, 18, 24)
        canvas.drawRoundRect(r, 20f, 20f, paintUi)
        paintUi.style = Paint.Style.STROKE
        paintUi.strokeWidth = 3.5f
        paintUi.color = when {
            !enabled -> Color.argb(70, 200, 200, 200)
            accent -> Color.rgb(210, 160, 70)
            else -> Color.rgb(190, 75, 55)
        }
        canvas.drawRoundRect(r, 20f, 20f, paintUi)
        paintUi.style = Paint.Style.FILL
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.color = if (enabled) Color.WHITE else Color.argb(110, 255, 255, 255)
        var ts = width * 0.046f
        paintUi.textSize = ts
        val maxW = r.width() - width * 0.04f
        while (paintUi.measureText(label) > maxW && ts > width * 0.028f) {
            ts -= width * 0.003f; paintUi.textSize = ts
        }
        canvas.drawText(label, r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
    }

    private fun drawTitle(canvas: Canvas) {
        drawBg(canvas, titleBg, 105)
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(10f, 0f, 4f, Color.BLACK)
        paintUi.color = Color.rgb(235, 225, 215)
        paintUi.textSize = width * 0.035f
        canvas.drawText("サウンドノベル", width / 2f, height * 0.24f, paintUi)
        paintUi.textSize = width * 0.095f
        canvas.drawText(novelTitle, width / 2f, height * 0.32f, paintUi)
        paintUi.color = Color.rgb(200, 70, 50)
        paintUi.textSize = width * 0.03f
        canvas.drawText("──────  ◆  ──────", width / 2f, height * 0.375f, paintUi)
        paintUi.clearShadowLayer()

        val bw = width * 0.66f
        val bh = height * 0.072f
        val cx = width / 2f
        val ys = height * 0.50f
        val gap = height * 0.095f
        for (i in 0 until 4) titleBtnRects[i].set(cx - bw / 2, ys + gap * i, cx + bw / 2, ys + gap * i + bh)

        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        drawMenuButton(canvas, titleBtnRects[0], "はじめから", true)
        drawMenuButton(canvas, titleBtnRects[1], "つづきから", hasChapterSave(0))
        drawMenuButton(canvas, titleBtnRects[2], "章を選ぶ", true)
        drawMenuButton(canvas, titleBtnRects[3], "人狼スマホのパスコードは？", true, accent = true)

        paintUi.color = Color.argb(140, 255, 255, 255)
        paintUi.textSize = width * 0.028f
        canvas.drawText("画面タップで物語が進みます", width / 2f, height * 0.95f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
    }

    private fun drawBackButton(canvas: Canvas) {
        val bw = width * 0.34f
        val bh = height * 0.06f
        backToTitleRect.set(width / 2f - bw / 2, height * 0.9f, width / 2f + bw / 2, height * 0.9f + bh)
        paintUi.style = Paint.Style.STROKE
        paintUi.strokeWidth = 3f
        paintUi.color = Color.argb(150, 210, 210, 210)
        canvas.drawRoundRect(backToTitleRect, 18f, 18f, paintUi)
        paintUi.style = Paint.Style.FILL
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.color = Color.argb(210, 235, 235, 235)
        paintUi.textSize = width * 0.038f
        canvas.drawText("← 戻る", backToTitleRect.centerX(),
            backToTitleRect.centerY() + paintUi.textSize / 3f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
    }

    private fun drawChapters(canvas: Canvas) {
        drawBg(canvas, titleBg, 150)
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(8f, 0f, 3f, Color.BLACK)
        paintUi.color = Color.rgb(232, 224, 214)
        paintUi.textSize = width * 0.06f
        canvas.drawText("章を選ぶ", width / 2f, height * 0.16f, paintUi)
        paintUi.clearShadowLayer()
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

        chapterRects.clear()
        val bw = width * 0.78f
        val bh = height * 0.11f
        val cx = width / 2f
        var y = height * 0.26f
        for (i in chapters.indices) {
            val r = RectF(cx - bw / 2, y, cx + bw / 2, y + bh)
            chapterRects.add(r)
            val unlocked = isChapterUnlocked(i)
            paintUi.style = Paint.Style.FILL
            paintUi.color = if (unlocked) Color.argb(205, 24, 20, 26) else Color.argb(150, 18, 16, 20)
            canvas.drawRoundRect(r, 20f, 20f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 3.5f
            paintUi.color = if (unlocked) Color.rgb(190, 90, 60) else Color.argb(120, 150, 150, 150)
            canvas.drawRoundRect(r, 20f, 20f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.textAlign = Paint.Align.CENTER
            paintUi.color = if (unlocked) Color.WHITE else Color.argb(150, 220, 220, 220)
            paintUi.textSize = width * 0.05f
            canvas.drawText(chapters[i].title, cx, r.centerY() + paintUi.textSize / 3f - height * 0.005f, paintUi)
            paintUi.textSize = width * 0.03f
            paintUi.color = if (unlocked) Color.argb(160, 220, 220, 220) else Color.rgb(210, 170, 90)
            val sub = when {
                unlocked && hasChapterSave(i) -> "つづきから再開できます"
                unlocked -> "タップして開始"
                else -> "🔒 パスコードが必要です"
            }
            canvas.drawText(sub, cx, r.bottom - height * 0.02f, paintUi)
            y += bh + height * 0.03f
        }
        paintUi.textAlign = Paint.Align.LEFT
        drawBackButton(canvas)
    }

    private fun drawPasscode(canvas: Canvas) {
        drawBg(canvas, titleBg, 165)
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(8f, 0f, 3f, Color.BLACK)
        paintUi.color = Color.rgb(232, 224, 214)
        paintUi.textSize = width * 0.055f
        canvas.drawText("人狼スマホのパスコード", width / 2f, height * 0.13f, paintUi)
        paintUi.clearShadowLayer()
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.color = Color.argb(160, 225, 225, 225)
        paintUi.textSize = width * 0.032f
        val chName = chapters.getOrNull(passTargetChapter)?.title ?: ""
        canvas.drawText("$chName を解錠します", width / 2f, height * 0.18f, paintUi)

        // 4桁ディスプレイ
        val boxW = width * 0.12f
        val gap = width * 0.04f
        val totalW = boxW * 4 + gap * 3
        var bx = width / 2f - totalW / 2
        val by = height * 0.24f
        for (i in 0 until 4) {
            val r = RectF(bx, by, bx + boxW, by + boxW * 1.25f)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.argb(210, 18, 16, 22)
            canvas.drawRoundRect(r, 12f, 12f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 3f
            paintUi.color = if (passError) Color.rgb(200, 70, 60) else Color.rgb(190, 150, 80)
            canvas.drawRoundRect(r, 12f, 12f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.WHITE
            paintUi.textSize = width * 0.075f
            val ch = if (i < passInput.length) "●" else ""
            canvas.drawText(ch, r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
            bx += boxW + gap
        }
        if (passError) {
            paintUi.color = Color.rgb(220, 90, 80)
            paintUi.textSize = width * 0.034f
            canvas.drawText("パスコードが違います", width / 2f, height * 0.375f, paintUi)
        }

        // テンキー 3x4 (1-9, ←, 0, OK)
        val padW = width * 0.20f
        val padH = height * 0.075f
        val gx = width * 0.04f
        val gy = height * 0.018f
        val startX = width / 2f - (padW * 3 + gx * 2) / 2
        val startY = height * 0.44f
        val layout = listOf(
            "1" to 1, "2" to 2, "3" to 3,
            "4" to 4, "5" to 5, "6" to 6,
            "7" to 7, "8" to 8, "9" to 9,
            "←" to 10, "0" to 0, "OK" to 11
        )
        for ((k, pair) in layout.withIndex()) {
            val (label, code) = pair
            val col = k % 3; val row = k / 3
            val rx = startX + col * (padW + gx)
            val ry = startY + row * (padH + gy)
            val r = RectF(rx, ry, rx + padW, ry + padH)
            padRects[code].set(r)
            paintUi.style = Paint.Style.FILL
            paintUi.color = when (code) {
                11 -> Color.argb(220, 60, 90, 60)
                10 -> Color.argb(220, 90, 60, 60)
                else -> Color.argb(215, 30, 28, 34)
            }
            canvas.drawRoundRect(r, 16f, 16f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 2.5f
            paintUi.color = Color.argb(150, 200, 200, 200)
            canvas.drawRoundRect(r, 16f, 16f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.WHITE
            paintUi.textSize = width * 0.055f
            canvas.drawText(label, r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
        }
        paintUi.textAlign = Paint.Align.LEFT
        drawBackButton(canvas)
    }

    private fun drawTitleCard(canvas: Canvas) {
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.color = Color.rgb(230, 222, 212)
        paintUi.textSize = width * 0.058f
        canvas.drawText(cardText, width / 2f, height * 0.48f, paintUi)
        paintUi.color = Color.rgb(150, 55, 40)
        paintUi.textSize = width * 0.03f
        canvas.drawText("──────────────", width / 2f, height * 0.53f, paintUi)
        paintUi.color = Color.argb(120, 255, 255, 255)
        paintUi.textSize = width * 0.028f
        canvas.drawText("タップで開始", width / 2f, height * 0.60f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
    }

    private fun drawChoice(canvas: Canvas) {
        paintUi.color = Color.argb(120, 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintUi)
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(8f, 0f, 3f, Color.BLACK)
        paintUi.color = Color.WHITE
        paintUi.textSize = width * 0.055f
        canvas.drawText(choiceQ, width / 2f, height * 0.33f, paintUi)

        val bw = width * 0.76f
        val bh = height * 0.085f
        rectA.set((width - bw) / 2f, height * 0.44f, (width + bw) / 2f, height * 0.44f + bh)
        rectB.set((width - bw) / 2f, height * 0.58f, (width + bw) / 2f, height * 0.58f + bh)
        for ((r, label) in listOf(rectA to choiceA, rectB to choiceB)) {
            paintUi.color = Color.argb(190, 20, 16, 22)
            canvas.drawRoundRect(r, 22f, 22f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 4f
            paintUi.color = Color.rgb(190, 75, 55)
            canvas.drawRoundRect(r, 22f, 22f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.WHITE
            paintUi.textSize = width * 0.048f
            canvas.drawText(label, r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
        }
        paintUi.textAlign = Paint.Align.LEFT
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.clearShadowLayer()
    }

    private fun drawEnd(canvas: Canvas) {
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.color = Color.rgb(225, 218, 208)
        paintUi.textSize = width * 0.05f
        var y = height * 0.42f
        for (l in endLines) {
            canvas.drawText(l, width / 2f, y, paintUi)
            y += paintUi.textSize * 2f
        }
        paintUi.color = Color.argb(120, 255, 255, 255)
        paintUi.textSize = width * 0.028f
        canvas.drawText("タップでタイトルへ", width / 2f, height * 0.85f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
    }
}
