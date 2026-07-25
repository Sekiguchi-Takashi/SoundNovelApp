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
        const val MODE_ENDING = 7     // エンドカード表示
        const val MODE_COLLECTION = 8 // エンドコレクション
        const val MODE_EPISODES = 9   // 話選択（3章クリア後に解放）
        const val MODE_SAVE = 10      // セーブスロット選択
        const val MODE_LOAD = 11      // ロードスロット選択
        const val SLOT_COUNT = 3
        const val TYPE_INTERVAL = 28L
    }

    private val prefs = ctx.getSharedPreferences("novel_save", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    // ---- マニフェスト ----
    private var novelTitle = "サウンドノベル"
    private var startEpisode = "ep01"

    // ---- 章 ----
    private data class Chapter(val id: String, val title: String, val start: String,
                              val lockedByDefault: Boolean, val passcode: String,
                              val episodeIds: List<String> = emptyList(),
                              val episodeTitles: List<String> = emptyList())
    private val chapters = mutableListOf<Chapter>()
    private val chapterRects = mutableListOf<RectF>()

    // ---- 話選択 ----
    private var episodeChapter = 0                 // 話選択で見ている章
    private val episodeRects = mutableListOf<RectF>()
    private val chapterTabRects = mutableListOf<RectF>()
    private val episodeSelectBtnRect = RectF()

    // ---- セーブ/ロード ----
    private val slotRects = Array(SLOT_COUNT) { RectF() }
    private val saveBtnRect = RectF()          // 選択肢画面上部のセーブボタン
    private val autoSaveRect = RectF()         // ロード画面の自動セーブ枠
    private var slotMessage = ""               // 「セーブしました」等の一時表示
    private var slotMessageAt = 0L

    // ---- パスコード入力 ----
    private val padRects = Array(12) { RectF() }   // 0-9, ←, OK
    private var passInput = ""
    private var passError = false
    private var passTargetChapter = 1              // どの章を解錠しようとしているか
    private var fromChapterSelect = false          // 章選択のロック章タップから来たか
    private val backToTitleRect = RectF()

    // ---- エンディング ----
    private data class EndingDef(val id: String, val label: String, val name: String, val desc: String)
    // 全エンド一覧（コレクション表示用。未取得は ??? 表示）
    private val allEndings = listOf(
        EndingDef("toru1", "透エンド1", "普通の大学生活", "駅で一晩を明かし、何事もなく日常へ戻った。"),
        EndingDef("toru2", "透エンド2", "気まずい2人", "梨花を探さずに眠り、幼なじみとの距離が戻らなくなった。"),
        EndingDef("true1", "第一章 完", "十分の一", "透編を最後まで見届けた。"),
        EndingDef("ch3clear", "第三章 完", "茜の答え", "茜編を最後まで見届けた。")
    )
    private var endLabel = ""
    private var endName = ""
    private var endDesc = ""
    private var endIsNew = false

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
    // 誤タップ防止: 選択肢はカバーを下方向にスワイプすると現れる
    private var choiceRevealed = false
    private var swipeStartY = 0f
    private var swipeStartX = 0f
    private var swipeTracking = false
    private var swipeProgress = 0f          // 0..1 カバーの引き下げ量
    private val swipeCoverRect = RectF()

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
            if (mode == MODE_CHOICE && !choiceRevealed) dirty = true
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
                    val ids = mutableListOf<String>()
                    val titles = mutableListOf<String>()
                    val el = c.optJSONArray("episodeList")
                    if (el != null) {
                        for (k in 0 until el.length()) {
                            val e = el.getJSONObject(k)
                            ids.add(e.optString("id"))
                            titles.add(e.optString("title"))
                        }
                    }
                    chapters.add(Chapter(
                        c.optString("id", "ch${i + 1}"),
                        c.optString("title", "第${i + 1}章"),
                        c.optString("start", startEpisode),
                        c.optBoolean("locked", false),
                        c.optString("passcode", ""),
                        ids, titles
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

    // 第三章を最後まで読むと話選択が解放される
    private fun isEpisodeSelectUnlocked(): Boolean {
        val last = chapters.lastOrNull() ?: return false
        return prefs.getBoolean("cleared_${last.id}", false)
    }

    // 話を直接指定して開始
    private fun startEpisodeDirect(chapterIdx: Int, epId: String) {
        currentChapter = chapterIdx
        playSe("stop")
        bgName = ""; bgBitmap = null
        if (loadEpisode(epId, 0)) { mode = MODE_GAME; advanceNode() }
        invalidate()
    }

    // ================= セーブ / ロード =================

    private fun slotKey(i: Int, k: String) = "slot${i}_$k"
    private fun slotUsed(i: Int) = prefs.contains(slotKey(i, "ep"))

    // 現在の話の中で、今の選択肢が何番目か（1始まり）。選択肢以外なら0
    private fun currentChoiceNumber(): Int {
        var count = 0
        for (i in 0..nodeIndex.coerceAtMost(nodes.length() - 1)) {
            val nd = nodes.optJSONObject(i) ?: continue
            if (nd.optString("t") == "choice") count++
        }
        return count
    }

    // その話に選択肢が複数あるか
    private fun choiceCountInEpisode(): Int {
        var count = 0
        for (i in 0 until nodes.length()) {
            if (nodes.optJSONObject(i)?.optString("t") == "choice") count++
        }
        return count
    }

    private fun currentEpisodeTitle(): String {
        for (i in 0 until nodes.length()) {
            val nd = nodes.optJSONObject(i) ?: continue
            if (nd.optString("t") == "title") return nd.optString("v")
        }
        return episodeId
    }

    private fun saveToSlot(i: Int) {
        val chTitle = chapters.getOrNull(currentChapter)?.title ?: "第${currentChapter + 1}章"
        val epTitle = currentEpisodeTitle()
        val cn = currentChoiceNumber()
        val total = choiceCountInEpisode()
        val choicePart = if (total > 1 && cn > 0) "　選択$cn" else if (cn > 0) "　選択" else ""
        val time = java.text.SimpleDateFormat("MM/dd HH:mm", java.util.Locale.JAPAN)
            .format(java.util.Date())
        val label = "$chTitle　$epTitle$choicePart"
        prefs.edit()
            .putInt(slotKey(i, "ch"), currentChapter)
            .putString(slotKey(i, "ep"), episodeId)
            .putInt(slotKey(i, "idx"), nodeIndex)
            .putString(slotKey(i, "label"), label)
            .putString(slotKey(i, "time"), time)
            .apply()
        slotMessage = "スロット${i + 1}にセーブしました"
        slotMessageAt = System.currentTimeMillis()
        invalidate()
    }

    private fun loadFromSlot(i: Int) {
        if (!slotUsed(i)) return
        val ch = prefs.getInt(slotKey(i, "ch"), 0)
        val ep = prefs.getString(slotKey(i, "ep"), null) ?: return
        val idx = prefs.getInt(slotKey(i, "idx"), 0)
        currentChapter = ch
        playSe("stop")
        bgName = ""; bgBitmap = null
        // idx の手前まで巻き戻して背景・音を復元し、その位置から再生
        if (loadEpisode(ep, idx)) {
            for (k in 0 until idx.coerceAtMost(nodes.length())) {
                val nd = nodes.getJSONObject(k)
                when (nd.optString("t")) {
                    "bg" -> setBg(nd.optString("v"))
                    "se" -> playSe(nd.optString("v"))
                }
            }
            nodeIndex = idx - 1
            mode = MODE_GAME
            advanceNode()
        }
        invalidate()
    }


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
                    choiceRevealed = false
                    swipeProgress = 0f
                    swipeTracking = false
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
                "ending" -> {
                    val id = node.optString("id")
                    endLabel = node.optString("label")
                    endName = node.optString("name")
                    endDesc = node.optString("desc")
                    endIsNew = !prefs.getBoolean("ending_$id", false)
                    if (id.isNotEmpty()) prefs.edit().putBoolean("ending_$id", true).apply()
                    // この章を最後まで読んだ記録（話選択の解放判定に使う）
                    val chId = chapters.getOrNull(currentChapter)?.id
                    if (chId != null && episodeId == chapters[currentChapter].episodeIds.lastOrNull()) {
                        prefs.edit().putBoolean("cleared_$chId", true).apply()
                    }
                    prefs.edit().remove(epKey()).remove(idxKey()).apply()
                    playSe("stop")
                    mode = MODE_ENDING
                    invalidate()
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
            MODE_EPISODES -> { mode = MODE_CHAPTERS }
            MODE_SAVE -> { mode = MODE_CHOICE }
            MODE_LOAD -> { mode = MODE_TITLE }
            MODE_COLLECTION -> { mode = MODE_TITLE }
            MODE_ENDING -> { mode = MODE_COLLECTION }
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
        // 選択肢のスワイプゲート（誤タップ防止）
        if (mode == MODE_CHOICE && !choiceRevealed) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // カバーの外にあるセーブボタンは常に押せる
                    if (saveBtnRect.contains(event.x, event.y)) {
                        slotMessage = ""; mode = MODE_SAVE; invalidate(); return true
                    }
                    swipeStartY = event.y; swipeStartX = event.x
                    swipeTracking = swipeCoverRect.contains(event.x, event.y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (swipeTracking) {
                        val dy = event.y - swipeStartY
                        val need = height * 0.10f
                        swipeProgress = (dy / need).coerceIn(0f, 1f)
                        if (swipeProgress >= 1f) {
                            choiceRevealed = true
                            swipeTracking = false
                        }
                        invalidate()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!choiceRevealed) { swipeProgress = 0f; invalidate() }
                    swipeTracking = false
                    return true
                }
            }
            return true
        }
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
                else if (isEpisodeSelectUnlocked() && episodeSelectBtnRect.contains(x, y)) {
                    episodeChapter = 0; mode = MODE_EPISODES
                }
                else for (i in chapterRects.indices) {
                    if (chapterRects[i].contains(x, y)) { onChapterTap(i); break }
                }
            }
            MODE_EPISODES -> {
                if (backToTitleRect.contains(x, y)) { mode = MODE_CHAPTERS }
                else {
                    var handled = false
                    for (i in chapterTabRects.indices) {
                        if (chapterTabRects[i].contains(x, y)) {
                            episodeChapter = i; handled = true; break
                        }
                    }
                    if (!handled) {
                        val ch = chapters.getOrNull(episodeChapter)
                        if (ch != null) for (i in episodeRects.indices) {
                            if (i < ch.episodeIds.size && episodeRects[i].contains(x, y)) {
                                startEpisodeDirect(episodeChapter, ch.episodeIds[i]); break
                            }
                        }
                    }
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
                if (saveBtnRect.contains(x, y)) { slotMessage = ""; mode = MODE_SAVE }
                else if (rectA.contains(x, y)) takeBranch(gotoA)
                else if (rectB.contains(x, y)) takeBranch(gotoB)
            }
            MODE_SAVE -> {
                if (backToTitleRect.contains(x, y)) { mode = MODE_CHOICE }
                else for (i in 0 until SLOT_COUNT) {
                    if (slotRects[i].contains(x, y)) { saveToSlot(i); break }
                }
            }
            MODE_LOAD -> {
                if (backToTitleRect.contains(x, y)) { mode = MODE_TITLE }
                else if (autoSaveRect.contains(x, y)) {
                    if (hasChapterSave(0)) startChapter(0, true)
                }
                else for (i in 0 until SLOT_COUNT) {
                    if (slotRects[i].contains(x, y)) { if (slotUsed(i)) loadFromSlot(i); break }
                }
            }
            MODE_END -> { playSe("stop"); mode = MODE_TITLE }
            MODE_ENDING -> { mode = MODE_COLLECTION }
            MODE_COLLECTION -> { if (backToTitleRect.contains(x, y)) mode = MODE_TITLE }
        }
        invalidate()
        return true
    }

    // 分岐先へ進む。goto が現在の話と同じなら「その場で続行」＝正常ルート
    private fun takeBranch(target: String) {
        if (target == episodeId || target.isEmpty()) {
            mode = MODE_GAME
            advanceNode()
        } else {
            playSe("stop")
            if (loadEpisode(target, 0)) { mode = MODE_GAME; advanceNode() }
        }
    }

    // タイトル画面のボタン: 0=はじめから, 1=つづきから, 2=章を選ぶ, 3=パスコード入力, 4=エンドコレクション
    private val titleBtnRects = Array(5) { RectF() }

    private fun onTitleButton(i: Int) {
        when (i) {
            0 -> startChapter(0, false)                       // 第一章 最初から
            1 -> {                                            // つづきから
                if (anySlotUsed() || hasChapterSave(0)) { slotMessage = ""; mode = MODE_LOAD }
            }
            2 -> { mode = MODE_CHAPTERS }                     // 章選択へ
            3 -> { fromChapterSelect = false; passTargetChapter = firstLockedChapter(); passInput = ""; passError = false; mode = MODE_PASSCODE }
            4 -> { mode = MODE_COLLECTION }                   // エンドコレクション
        }
    }

    private fun anySlotUsed(): Boolean {
        for (i in 0 until SLOT_COUNT) if (slotUsed(i)) return true
        return false
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
            fromChapterSelect = true; passTargetChapter = i; passInput = ""; passError = false; mode = MODE_PASSCODE
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
        // 開発用: 9999 で話選択を解放
        if (passInput == "9999") {
            val last = chapters.lastOrNull()
            if (last != null) {
                prefs.edit().putBoolean("cleared_${last.id}", true).apply()
                for (c in chapters) if (c.lockedByDefault) prefs.edit().putBoolean("unlock_${c.id}", true).apply()
            }
            passInput = ""
            mode = MODE_EPISODES
            episodeChapter = 0
            invalidate()
            return
        }
        // 入力コードに一致するロック章を探す（トップの共通入力に対応）
        var matched = -1
        for (i in chapters.indices) {
            val c = chapters[i]
            if (c.lockedByDefault && c.passcode.isNotEmpty() && passInput == c.passcode) {
                matched = i; break
            }
        }
        // 章選択から特定章の解錠を求められた場合はその章のみ許可
        if (fromChapterSelect && matched != passTargetChapter) {
            val c = chapters.getOrNull(passTargetChapter)
            matched = if (c != null && passInput == c.passcode && c.passcode.isNotEmpty()) passTargetChapter else -1
        }
        if (matched >= 0) {
            prefs.edit().putBoolean("unlock_${chapters[matched].id}", true).apply()
            startChapter(matched, hasChapterSave(matched))
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
            MODE_ENDING -> drawEndingCard(canvas)
            MODE_COLLECTION -> drawCollection(canvas)
            MODE_EPISODES -> drawEpisodes(canvas)
            MODE_SAVE -> drawSlots(canvas, true)
            MODE_LOAD -> drawSlots(canvas, false)
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
        val bh = height * 0.066f
        val cx = width / 2f
        val ys = height * 0.46f
        val gap = height * 0.087f
        for (i in 0 until 5) titleBtnRects[i].set(cx - bw / 2, ys + gap * i, cx + bw / 2, ys + gap * i + bh)

        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        drawMenuButton(canvas, titleBtnRects[0], "はじめから", true)
        drawMenuButton(canvas, titleBtnRects[1], "つづきから", anySlotUsed() || hasChapterSave(0))
        drawMenuButton(canvas, titleBtnRects[2], "章を選ぶ", true)
        drawMenuButton(canvas, titleBtnRects[3], "人狼スマホのパスコードは？", true, accent = true)
        val got = allEndings.count { prefs.getBoolean("ending_${it.id}", false) }
        drawMenuButton(canvas, titleBtnRects[4], "エンドコレクション（$got/${allEndings.size}）", true)

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
        // 話選択（第三章クリアで解放）
        val unlockedSel = isEpisodeSelectUnlocked()
        val sw = width * 0.78f
        val sh = height * 0.068f
        episodeSelectBtnRect.set(cx - sw / 2, height * 0.735f, cx + sw / 2, height * 0.735f + sh)
        paintUi.style = Paint.Style.FILL
        paintUi.color = if (unlockedSel) Color.argb(210, 22, 26, 24) else Color.argb(130, 16, 16, 18)
        canvas.drawRoundRect(episodeSelectBtnRect, 18f, 18f, paintUi)
        paintUi.style = Paint.Style.STROKE
        paintUi.strokeWidth = 3f
        paintUi.color = if (unlockedSel) Color.rgb(120, 180, 130) else Color.argb(90, 150, 150, 150)
        canvas.drawRoundRect(episodeSelectBtnRect, 18f, 18f, paintUi)
        paintUi.style = Paint.Style.FILL
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.color = if (unlockedSel) Color.WHITE else Color.argb(120, 220, 220, 220)
        paintUi.textSize = width * 0.042f
        canvas.drawText(if (unlockedSel) "話を選ぶ" else "🔒 話を選ぶ",
            cx, episodeSelectBtnRect.centerY() + paintUi.textSize / 3f - height * 0.006f, paintUi)
        paintUi.textSize = width * 0.026f
        paintUi.color = if (unlockedSel) Color.argb(150, 220, 220, 220) else Color.rgb(200, 165, 90)
        canvas.drawText(if (unlockedSel) "どの話からでも読み返せます" else "第三章を読み終えると解放されます",
            cx, episodeSelectBtnRect.bottom - height * 0.008f, paintUi)

        paintUi.textAlign = Paint.Align.LEFT
        drawBackButton(canvas)
    }

    private fun drawEpisodes(canvas: Canvas) {
        drawBg(canvas, titleBg, 170)
        val cx = width / 2f
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(8f, 0f, 3f, Color.BLACK)
        paintUi.color = Color.rgb(232, 224, 214)
        paintUi.textSize = width * 0.055f
        canvas.drawText("話を選ぶ", cx, height * 0.085f, paintUi)
        paintUi.clearShadowLayer()
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)

        // 章タブ
        chapterTabRects.clear()
        val tabW = width * 0.29f
        val tabH = height * 0.045f
        val totalW = tabW * chapters.size + width * 0.02f * (chapters.size - 1)
        var tx = cx - totalW / 2
        val tyTab = height * 0.115f
        for (i in chapters.indices) {
            val r = RectF(tx, tyTab, tx + tabW, tyTab + tabH)
            chapterTabRects.add(r)
            val sel = (i == episodeChapter)
            paintUi.style = Paint.Style.FILL
            paintUi.color = if (sel) Color.argb(230, 60, 34, 30) else Color.argb(170, 22, 20, 24)
            canvas.drawRoundRect(r, 14f, 14f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 2.5f
            paintUi.color = if (sel) Color.rgb(210, 120, 80) else Color.argb(110, 180, 180, 180)
            canvas.drawRoundRect(r, 14f, 14f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.color = if (sel) Color.WHITE else Color.argb(170, 220, 220, 220)
            paintUi.textSize = width * 0.032f
            canvas.drawText("第${i + 1}章", r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
            tx += tabW + width * 0.02f
        }

        val ch = chapters.getOrNull(episodeChapter)
        if (ch == null) { paintUi.textAlign = Paint.Align.LEFT; drawBackButton(canvas); return }

        // 話番号グリッド（6列）
        episodeRects.clear()
        val cols = 6
        val cellW = width * 0.135f
        val cellH = height * 0.052f
        val gx = width * 0.015f
        val gy = height * 0.014f
        val gridW = cellW * cols + gx * (cols - 1)
        val startX = cx - gridW / 2
        val startY = height * 0.19f
        for (i in ch.episodeIds.indices) {
            val col = i % cols; val row = i / cols
            val r = RectF(startX + col * (cellW + gx), startY + row * (cellH + gy),
                startX + col * (cellW + gx) + cellW, startY + row * (cellH + gy) + cellH)
            episodeRects.add(r)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.argb(215, 26, 22, 28)
            canvas.drawRoundRect(r, 12f, 12f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 2.5f
            paintUi.color = Color.rgb(170, 120, 80)
            canvas.drawRoundRect(r, 12f, 12f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.WHITE
            paintUi.textSize = width * 0.038f
            canvas.drawText("${i + 1}", r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
        }
        val rows = (ch.episodeIds.size + cols - 1) / cols
        val gridBottom = startY + rows * (cellH + gy)
        paintUi.color = Color.argb(150, 225, 225, 225)
        paintUi.textSize = width * 0.028f
        canvas.drawText("全${ch.episodeIds.size}話　番号をタップでその話から再生", cx, gridBottom + height * 0.03f, paintUi)
        paintUi.color = Color.argb(120, 210, 190, 160)
        paintUi.textSize = width * 0.025f
        canvas.drawText("※ 開発・読み返し用。進行状況は保存されます", cx, gridBottom + height * 0.062f, paintUi)

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
        val subMsg = if (fromChapterSelect)
            "${chapters.getOrNull(passTargetChapter)?.title ?: ""} を解錠します"
        else
            "正しいコードを入力すると章が開きます"
        canvas.drawText(subMsg, width / 2f, height * 0.18f, paintUi)

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

        // 画面最上部のセーブボタン（カバー表示中も押せる）
        val sbw = width * 0.42f
        val sbh = height * 0.052f
        saveBtnRect.set(width / 2f - sbw / 2, height * 0.055f, width / 2f + sbw / 2, height * 0.055f + sbh)
        paintUi.style = Paint.Style.FILL
        paintUi.color = Color.argb(215, 20, 26, 30)
        canvas.drawRoundRect(saveBtnRect, 16f, 16f, paintUi)
        paintUi.style = Paint.Style.STROKE
        paintUi.strokeWidth = 3f
        paintUi.color = Color.rgb(110, 160, 190)
        canvas.drawRoundRect(saveBtnRect, 16f, 16f, paintUi)
        paintUi.style = Paint.Style.FILL
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.color = Color.rgb(215, 235, 245)
        paintUi.textSize = width * 0.036f
        canvas.drawText("この分岐をセーブ", saveBtnRect.centerX(),
            saveBtnRect.centerY() + paintUi.textSize / 3f, paintUi)

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
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.argb(190, 20, 16, 22)
            canvas.drawRoundRect(r, 22f, 22f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 4f
            paintUi.color = Color.rgb(190, 75, 55)
            canvas.drawRoundRect(r, 22f, 22f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.color = Color.WHITE
            var ts = width * 0.048f
            paintUi.textSize = ts
            while (paintUi.measureText(label) > r.width() - width * 0.06f && ts > width * 0.03f) {
                ts -= width * 0.003f; paintUi.textSize = ts
            }
            canvas.drawText(label, r.centerX(), r.centerY() + paintUi.textSize / 3f, paintUi)
        }
        paintUi.textAlign = Paint.Align.LEFT
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.clearShadowLayer()

        if (!choiceRevealed) drawSwipeCover(canvas)
    }

    // 選択肢を覆うカバー。下方向にスワイプすると剥がれて選択肢が現れる
    private fun drawSwipeCover(canvas: Canvas) {
        val top = height * 0.40f
        val bottom = height * 0.72f
        val slide = swipeProgress * (bottom - top) * 1.15f
        swipeCoverRect.set(0f, top, width.toFloat(), bottom)

        canvas.save()
        canvas.clipRect(0f, top, width.toFloat(), bottom)
        val r = RectF(width * 0.06f, top + slide, width * 0.94f, bottom + slide)
        paintUi.style = Paint.Style.FILL
        paintUi.color = Color.argb(248, 14, 12, 16)
        canvas.drawRoundRect(r, 26f, 26f, paintUi)
        paintUi.style = Paint.Style.STROKE
        paintUi.strokeWidth = 3.5f
        paintUi.color = Color.rgb(150, 120, 70)
        canvas.drawRoundRect(r, 26f, 26f, paintUi)
        paintUi.style = Paint.Style.FILL

        paintUi.textAlign = Paint.Align.CENTER
        val cx = width / 2f
        val cy = r.top + r.height() * 0.32f
        // つまみ（ハンドル）
        paintUi.color = Color.argb(200, 190, 165, 110)
        canvas.drawRoundRect(RectF(cx - width * 0.09f, r.top + height * 0.018f,
            cx + width * 0.09f, r.top + height * 0.026f), 8f, 8f, paintUi)

        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.color = Color.rgb(225, 205, 160)
        paintUi.textSize = width * 0.042f
        canvas.drawText("選択肢があります", cx, cy + height * 0.02f, paintUi)
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.color = Color.argb(200, 225, 225, 225)
        paintUi.textSize = width * 0.034f
        canvas.drawText("下にスワイプして表示", cx, cy + height * 0.065f, paintUi)

        // 下向き矢印（点滅）
        val alpha = ((Math.sin(blink.toDouble() * 1.4) * 0.4 + 0.6) * 255).toInt().coerceIn(0, 255)
        paintUi.color = Color.argb(alpha, 210, 180, 120)
        paintUi.textSize = width * 0.075f
        canvas.drawText("▼", cx, cy + height * 0.135f, paintUi)

        // 進捗バー
        val barW = width * 0.5f
        val barY = r.top + r.height() * 0.86f
        paintUi.color = Color.argb(90, 200, 200, 200)
        canvas.drawRoundRect(RectF(cx - barW / 2, barY, cx + barW / 2, barY + height * 0.008f), 6f, 6f, paintUi)
        paintUi.color = Color.rgb(200, 165, 90)
        canvas.drawRoundRect(RectF(cx - barW / 2, barY,
            cx - barW / 2 + barW * swipeProgress, barY + height * 0.008f), 6f, 6f, paintUi)

        canvas.restore()
        paintUi.textAlign = Paint.Align.LEFT
    }

    private fun drawEndingCard(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        drawBg(canvas, bgBitmap, 190)
        val cx = width / 2f
        // 額縁
        val fr = RectF(width * 0.10f, height * 0.28f, width * 0.90f, height * 0.66f)
        paintUi.style = Paint.Style.FILL
        paintUi.color = Color.argb(225, 16, 13, 18)
        canvas.drawRoundRect(fr, 24f, 24f, paintUi)
        paintUi.style = Paint.Style.STROKE
        paintUi.strokeWidth = 5f
        paintUi.color = Color.rgb(205, 165, 85)
        canvas.drawRoundRect(fr, 24f, 24f, paintUi)
        paintUi.strokeWidth = 2f
        canvas.drawRoundRect(RectF(fr.left + 12, fr.top + 12, fr.right - 12, fr.bottom - 12), 18f, 18f, paintUi)
        paintUi.style = Paint.Style.FILL

        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        if (endIsNew) {
            paintUi.color = Color.rgb(230, 190, 90)
            paintUi.textSize = width * 0.034f
            canvas.drawText("NEW ENDING", cx, fr.top + height * 0.05f, paintUi)
        }
        paintUi.color = Color.rgb(220, 195, 140)
        paintUi.textSize = width * 0.042f
        canvas.drawText(endLabel, cx, fr.top + height * 0.105f, paintUi)
        paintUi.color = Color.WHITE
        paintUi.textSize = width * 0.072f
        canvas.drawText(endName, cx, fr.top + height * 0.185f, paintUi)
        paintUi.color = Color.rgb(180, 90, 60)
        paintUi.textSize = width * 0.028f
        canvas.drawText("──────  ◆  ──────", cx, fr.top + height * 0.225f, paintUi)
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.color = Color.argb(200, 230, 230, 230)
        paintUi.textSize = width * 0.033f
        var ty = fr.top + height * 0.275f
        for (seg in wrap(endDesc, paintUi, fr.width() - width * 0.10f)) {
            canvas.drawText(seg, cx, ty, paintUi)
            ty += paintUi.textSize * 1.6f
        }
        paintUi.color = Color.argb(140, 255, 255, 255)
        paintUi.textSize = width * 0.03f
        canvas.drawText("タップしてコレクションへ", cx, height * 0.78f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
    }

    private fun drawCollection(canvas: Canvas) {
        drawBg(canvas, titleBg, 165)
        val cx = width / 2f
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(8f, 0f, 3f, Color.BLACK)
        paintUi.color = Color.rgb(232, 224, 214)
        paintUi.textSize = width * 0.058f
        canvas.drawText("エンドコレクション", cx, height * 0.12f, paintUi)
        paintUi.clearShadowLayer()
        val got = allEndings.count { prefs.getBoolean("ending_${it.id}", false) }
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.color = Color.rgb(210, 170, 90)
        paintUi.textSize = width * 0.036f
        canvas.drawText("$got / ${allEndings.size} 種類", cx, height * 0.165f, paintUi)

        val bw = width * 0.82f
        val bh = height * 0.115f
        var y = height * 0.22f
        for (e in allEndings) {
            val unlocked = prefs.getBoolean("ending_${e.id}", false)
            val r = RectF(cx - bw / 2, y, cx + bw / 2, y + bh)
            paintUi.style = Paint.Style.FILL
            paintUi.color = if (unlocked) Color.argb(215, 24, 20, 26) else Color.argb(150, 16, 15, 18)
            canvas.drawRoundRect(r, 18f, 18f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 3f
            paintUi.color = if (unlocked) Color.rgb(205, 165, 85) else Color.argb(90, 150, 150, 150)
            canvas.drawRoundRect(r, 18f, 18f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.textAlign = Paint.Align.LEFT
            val lx = r.left + width * 0.045f
            if (unlocked) {
                paintUi.color = Color.rgb(215, 180, 110)
                paintUi.textSize = width * 0.03f
                canvas.drawText(e.label, lx, r.top + height * 0.032f, paintUi)
                paintUi.color = Color.WHITE
                paintUi.textSize = width * 0.046f
                canvas.drawText(e.name, lx, r.top + height * 0.072f, paintUi)
                paintUi.color = Color.argb(150, 220, 220, 220)
                paintUi.textSize = width * 0.026f
                val d1 = wrap(e.desc, paintUi, bw - width * 0.09f)
                canvas.drawText(d1.firstOrNull() ?: "", lx, r.top + height * 0.100f, paintUi)
            } else {
                paintUi.color = Color.argb(120, 200, 200, 200)
                paintUi.textSize = width * 0.03f
                canvas.drawText(e.label, lx, r.top + height * 0.032f, paintUi)
                paintUi.color = Color.argb(150, 210, 210, 210)
                paintUi.textSize = width * 0.046f
                canvas.drawText("？？？？？", lx, r.top + height * 0.072f, paintUi)
                paintUi.color = Color.argb(110, 200, 200, 200)
                paintUi.textSize = width * 0.026f
                canvas.drawText("まだ見ていない結末", lx, r.top + height * 0.100f, paintUi)
            }
            y += bh + height * 0.022f
        }
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.color = Color.argb(130, 230, 230, 230)
        paintUi.textSize = width * 0.026f
        canvas.drawText("選択肢を変えると、別の結末にたどり着けます", cx, height * 0.855f, paintUi)
        paintUi.textAlign = Paint.Align.LEFT
        drawBackButton(canvas)
    }

    private fun drawSlots(canvas: Canvas, forSave: Boolean) {
        drawBg(canvas, if (forSave) bgBitmap else titleBg, 175)
        val cx = width / 2f
        paintUi.textAlign = Paint.Align.CENTER
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        paintUi.setShadowLayer(8f, 0f, 3f, Color.BLACK)
        paintUi.color = Color.rgb(232, 224, 214)
        paintUi.textSize = width * 0.058f
        canvas.drawText(if (forSave) "セーブ" else "つづきから", cx, height * 0.13f, paintUi)
        paintUi.clearShadowLayer()
        paintUi.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        paintUi.color = Color.argb(160, 225, 225, 225)
        paintUi.textSize = width * 0.03f
        canvas.drawText(if (forSave) "スロットを選んで保存します" else "読み込むデータを選んでください",
            cx, height * 0.175f, paintUi)

        val bw = width * 0.84f
        val bh = height * 0.115f
        var y = height * 0.24f
        for (i in 0 until SLOT_COUNT) {
            val r = RectF(cx - bw / 2, y, cx + bw / 2, y + bh)
            slotRects[i].set(r)
            val used = slotUsed(i)
            paintUi.style = Paint.Style.FILL
            paintUi.color = if (used) Color.argb(220, 24, 22, 28) else Color.argb(160, 18, 17, 20)
            canvas.drawRoundRect(r, 18f, 18f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 3f
            paintUi.color = when {
                forSave -> Color.rgb(110, 160, 190)
                used -> Color.rgb(190, 150, 80)
                else -> Color.argb(80, 150, 150, 150)
            }
            canvas.drawRoundRect(r, 18f, 18f, paintUi)
            paintUi.style = Paint.Style.FILL

            paintUi.textAlign = Paint.Align.LEFT
            val lx = r.left + width * 0.04f
            paintUi.color = Color.rgb(180, 200, 215)
            paintUi.textSize = width * 0.03f
            canvas.drawText("スロット ${i + 1}", lx, r.top + height * 0.032f, paintUi)
            if (used) {
                paintUi.color = Color.WHITE
                var ts = width * 0.036f
                paintUi.textSize = ts
                val label = prefs.getString(slotKey(i, "label"), "") ?: ""
                while (paintUi.measureText(label) > bw - width * 0.08f && ts > width * 0.024f) {
                    ts -= width * 0.002f; paintUi.textSize = ts
                }
                canvas.drawText(label, lx, r.top + height * 0.072f, paintUi)
                paintUi.color = Color.argb(150, 210, 210, 210)
                paintUi.textSize = width * 0.028f
                canvas.drawText(prefs.getString(slotKey(i, "time"), "") ?: "", lx, r.top + height * 0.101f, paintUi)
            } else {
                paintUi.color = Color.argb(130, 210, 210, 210)
                paintUi.textSize = width * 0.036f
                canvas.drawText("- 空き -", lx, r.top + height * 0.075f, paintUi)
            }
            paintUi.textAlign = Paint.Align.CENTER
            y += bh + height * 0.025f
        }

        // 自動セーブからの再開（ロード時のみ・4つ目の枠）
        if (!forSave) {
            val r = RectF(cx - bw / 2, y, cx + bw / 2, y + bh * 0.72f)
            autoSaveRect.set(r)
            val hasAuto = hasChapterSave(currentChapter) || hasChapterSave(0)
            paintUi.style = Paint.Style.FILL
            paintUi.color = if (hasAuto) Color.argb(200, 22, 26, 24) else Color.argb(150, 18, 17, 20)
            canvas.drawRoundRect(r, 18f, 18f, paintUi)
            paintUi.style = Paint.Style.STROKE
            paintUi.strokeWidth = 3f
            paintUi.color = if (hasAuto) Color.rgb(120, 180, 130) else Color.argb(80, 150, 150, 150)
            canvas.drawRoundRect(r, 18f, 18f, paintUi)
            paintUi.style = Paint.Style.FILL
            paintUi.textAlign = Paint.Align.LEFT
            val lx = r.left + width * 0.04f
            paintUi.color = if (hasAuto) Color.rgb(170, 210, 180) else Color.argb(120, 200, 200, 200)
            paintUi.textSize = width * 0.03f
            canvas.drawText("自動セーブ", lx, r.top + height * 0.03f, paintUi)
            paintUi.color = if (hasAuto) Color.WHITE else Color.argb(120, 210, 210, 210)
            paintUi.textSize = width * 0.034f
            canvas.drawText(if (hasAuto) "最後に読んでいた位置から続ける" else "- なし -",
                lx, r.top + height * 0.062f, paintUi)
            paintUi.textAlign = Paint.Align.CENTER
        }

        if (slotMessage.isNotEmpty() && System.currentTimeMillis() - slotMessageAt < 2500) {
            paintUi.color = Color.rgb(140, 210, 150)
            paintUi.textSize = width * 0.034f
            canvas.drawText(slotMessage, cx, height * 0.80f, paintUi)
        }
        paintUi.textAlign = Paint.Align.LEFT
        drawBackButton(canvas)
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
