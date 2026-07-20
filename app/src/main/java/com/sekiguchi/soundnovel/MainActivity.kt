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
        const val TYPE_INTERVAL = 28L
    }

    private val prefs = ctx.getSharedPreferences("novel_save", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // ---- マニフェスト ----
    private var novelTitle = "サウンドノベル"
    private var startEpisode = "ep01"

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
        } catch (_: Exception) {}
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

    private fun startGame(fromSave: Boolean) {
        if (fromSave && prefs.contains("ep")) {
            val ep = prefs.getString("ep", startEpisode) ?: startEpisode
            val idx = prefs.getInt("idx", 0)
            if (loadEpisode(ep, idx)) {
                // 再開位置までの背景・環境音を復元
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
            if (loadEpisode(startEpisode, 0)) { mode = MODE_GAME; advanceNode() }
        }
        invalidate()
    }

    private fun saveProgress() {
        prefs.edit().putString("ep", episodeId).putInt("idx", nodeIndex).apply()
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
        if (!keepSave) prefs.edit().clear().apply()
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
        if (mode != MODE_TITLE) {
            playSe("stop")
            mode = MODE_TITLE
            invalidate()
            return true
        }
        return false
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
                val hasSave = prefs.contains("ep")
                val yStart = height * 0.62f
                if (y in yStart..(yStart + height * 0.075f)) {
                    startGame(false)
                } else if (hasSave && y in (yStart + height * 0.10f)..(yStart + height * 0.175f)) {
                    startGame(true)
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

    // ================= 描画 =================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        when (mode) {
            MODE_TITLE -> drawTitle(canvas)
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

    private fun drawTitle(canvas: Canvas) {
        drawBg(canvas, titleBg, 100)
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(10f, 0f, 4f, Color.BLACK)
        paintUi.color = Color.rgb(235, 225, 215)
        paintUi.textSize = width * 0.035f
        canvas.drawText("サウンドノベル", width / 2f, height * 0.30f, paintUi)
        paintUi.textSize = width * 0.095f
        canvas.drawText(novelTitle, width / 2f, height * 0.38f, paintUi)
        paintUi.color = Color.rgb(200, 70, 50)
        paintUi.textSize = width * 0.03f
        canvas.drawText("──────  ◆  ──────", width / 2f, height * 0.44f, paintUi)

        val hasSave = prefs.contains("ep")
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.textSize = width * 0.052f
        val yStart = height * 0.62f
        paintUi.color = Color.WHITE
        canvas.drawText("はじめから", width / 2f, yStart + height * 0.05f, paintUi)
        paintUi.color = if (hasSave) Color.WHITE else Color.argb(90, 255, 255, 255)
        canvas.drawText("つづきから", width / 2f, yStart + height * 0.15f, paintUi)

        paintUi.color = Color.argb(140, 255, 255, 255)
        paintUi.textSize = width * 0.028f
        canvas.drawText("画面タップで物語が進みます", width / 2f, height * 0.92f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
        paintUi.clearShadowLayer()
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
