package se.gymrepris.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaCodec
import android.media.MediaMuxer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

private val BG = Color.rgb(0x0d, 0x11, 0x17)
private val PANEL = Color.argb(0xee, 0x16, 0x1c, 0x26)
private val LINE = Color.rgb(0x2b, 0x35, 0x45)
private val FG = Color.rgb(0xee, 0xf2, 0xf7)
private val DIM = Color.rgb(0x9a, 0xa7, 0xb8)
private val ACCENT = Color.rgb(0xff, 0xd2, 0x3f)
private val ON_ACCENT = Color.rgb(0x1a, 0x14, 0x00)
private val REC = Color.rgb(0xff, 0x52, 0x52)
private val BTN = Color.rgb(0x23, 0x2c, 0x3a)
private val BTN_DOWN = Color.rgb(0x33, 0x40, 0x5a)

/** Shows the movement right now against the level that counts as an exercise. */
private class Meter(ctx: Context) : View(ctx) {
    var value = 0f
    var limit = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun scale(v: Float) = minOf(1f, sqrt(v / 0.3f))

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, v.height / 2f)
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(BTN)
        paint.color = if (value > limit) ACCENT else DIM
        c.drawRect(0f, 0f, width * scale(value), height.toFloat(), paint)
        paint.color = REC
        val x = width * scale(limit)
        c.drawRect(x, 0f, x + 2 * resources.displayMetrics.density, height.toFloat(), paint)
    }
}

class MainActivity : Activity(), Pipeline.Listener, SurfaceHolder.Callback {
    private val maxClips = 3
    private lateinit var s: Settings
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var message: LinearLayout
    private lateinit var messageText: TextView
    private lateinit var messageBig: TextView
    private lateinit var retry: TextView
    private lateinit var chip: View
    private lateinit var bar: LinearLayout
    private lateinit var delayValue: TextView
    private lateinit var clipRow: LinearLayout
    private lateinit var viewerTop: LinearLayout
    private lateinit var viewerBar: LinearLayout
    private lateinit var viewerTitle: TextView
    private lateinit var playBtn: TextView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var settings: LinearLayout
    private lateinit var settingsBody: LinearLayout
    private lateinit var confirm: FrameLayout
    private lateinit var confirmText: TextView
    private lateinit var toastView: TextView
    private var meter: Meter? = null
    private var infoText: TextView? = null
    private val speedBtns = ArrayList<Pair<Int, TextView>>()
    private var confirmYes: (() -> Unit)? = null
    private val hideToast = Runnable { toastView.visibility = View.GONE }

    private var pipeline: Pipeline? = null
    private var player: Player? = null
    private var surfaceReady = false
    private var liveReady = false
    private var error: String? = null
    private var asked = false
    private var idle = false
    private var lastTouch = SystemClock.elapsedRealtime()
    private val clips = ArrayList<Clip>()

    /* The open clip. The player thread reads the position, the screen changes it. */
    private val lock = Any()
    private var open: Clip? = null
    private var pos = 0L
    private var playing = false
    private var lastNano = 0L
    private var dragging = false
    private var resume = false

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
    private fun hhmmss(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(at))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        s = Settings(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildScreen()
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        ui.post(object : Runnable { override fun run() { tick(); ui.postDelayed(this, 100) } })
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startPipeline()
        else if (!asked) { asked = true; requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
        else error = "Appen får inte använda kameran. Tillåt kameran för Gymrepris i surfplattans inställningar."
    }

    override fun onPause() {
        super.onPause()
        stopPipeline()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastTouch = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            confirm.visibility == View.VISIBLE -> confirm.visibility = View.GONE
            settings.visibility == View.VISIBLE -> settings.visibility = View.GONE
            open != null -> closeClip()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /* ---------- Camera and replay ---------- */
    private fun startPipeline() {
        stopPipeline()
        error = null
        lastTouch = SystemClock.elapsedRealtime()
        pipeline = Pipeline(this, s, this).also { it.start() }
    }

    private fun stopPipeline() {
        if (open == null) stopPlayer()
        liveReady = false
        pipeline?.stop()
        pipeline = null
    }

    private fun stopPlayer() { player?.finish(); player = null }

    override fun onReady() {
        liveReady = true
        if (open == null && !idle) startLive()
    }

    override fun onError(msg: String) { error = msg }

    private fun startLive() {
        val p = pipeline ?: return
        if (!surfaceReady || !liveReady) return
        stopPlayer()
        fit(p.width, p.height, p.rotation)
        player = Player(surface.holder.surface, p.live, p.width, p.height, p.rotation) {
            (p.nowTs() ?: 0) - s.delay * 1_000_000L
        }.also { it.start() }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        surfaceReady = true
        open?.let { startClipPlayer(it) } ?: run { if (!idle) startLive() }
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = false; stopPlayer() }

    /** The picture keeps its shape and is as large as the screen allows. */
    private fun fit(w: Int, h: Int, rotation: Int) {
        val vw = if (rotation % 180 == 0) w else h
        val vh = if (rotation % 180 == 0) h else w
        if (root.width == 0 || vw == 0) return
        val k = minOf(root.width.toFloat() / vw, root.height.toFloat() / vh)
        val lp = FrameLayout.LayoutParams((vw * k).toInt(), (vh * k).toInt(), Gravity.CENTER)
        if (surface.layoutParams.width != lp.width || surface.layoutParams.height != lp.height) surface.layoutParams = lp
    }

    override fun onClip(c: Clip) {
        clips.add(0, c)
        while (clips.size > maxClips) clips.removeAt(clips.size - 1)
        showClips(true)
        toast("Klipp sparat")
    }

    private fun setDelay(d: Int) {
        s.delay = (s.delay + d).coerceIn(1, 60)
        delayValue.text = "${s.delay} s"
    }

    /* ---------- Clip viewer ---------- */
    private fun clipTarget(): Long = synchronized(lock) {
        val c = open ?: return 0
        val now = System.nanoTime()
        if (playing) {
            pos += (now - lastNano) / 1000 * s.speed / 8
            if (pos >= c.dur) { pos = c.dur; playing = false }
        }
        lastNano = now
        c.t0 + pos
    }

    private fun openClip(c: Clip) {
        stopPlayer()
        synchronized(lock) { open = c; pos = 0; playing = true; lastNano = System.nanoTime() }
        viewerTitle.text = "Klipp ${hhmmss(c.at)}"
        seek.max = (c.dur / 1000).toInt()
        bar.visibility = View.GONE
        settings.visibility = View.GONE
        viewerTop.visibility = View.VISIBLE
        viewerBar.visibility = View.VISIBLE
        markSpeed()
        if (surfaceReady) startClipPlayer(c)
    }

    private fun startClipPlayer(c: Clip) {
        stopPlayer()
        fit(c.width, c.height, c.rotation)
        player = Player(surface.holder.surface, c.source, c.width, c.height, c.rotation, ::clipTarget).also { it.start() }
    }

    private fun closeClip() {
        stopPlayer()
        synchronized(lock) { open = null }
        viewerTop.visibility = View.GONE
        viewerBar.visibility = View.GONE
        bar.visibility = View.VISIBLE
        lastTouch = SystemClock.elapsedRealtime()
        startLive()
    }

    private fun togglePlay() = synchronized(lock) {
        val c = open ?: return
        if (!playing && pos >= c.dur) pos = 0
        playing = !playing
        lastNano = System.nanoTime()
    }

    private fun step(d: Int) = synchronized(lock) {
        val c = open ?: return
        playing = false
        val i = (c.source.findLE(c.t0 + pos) + d).coerceIn(0, c.frames.size - 1)
        pos = c.frames[i].ts - c.t0
    }

    private fun markSpeed() = speedBtns.forEach { (v, b) ->
        val on = v == s.speed
        b.setTextColor(if (on) ACCENT else FG)
        b.background = buttonBg(BTN, if (on) ACCENT else LINE, 12)
    }

    private fun saveToGallery(c: Clip) = Thread {
        val name = "gymrepris-" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date(c.at)) + ".mp4"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Gymrepris")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
            contentResolver.openFileDescriptor(uri, "rw")!!.use { fd ->
                val mux = MediaMuxer(fd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                mux.setOrientationHint(c.rotation)
                val track = mux.addTrack(c.format)
                mux.start()
                val info = MediaCodec.BufferInfo()
                for (f in c.frames) {
                    info.set(0, f.data.size, f.ts - c.t0, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    mux.writeSampleData(track, ByteBuffer.wrap(f.data), info)
                }
                mux.stop()
                mux.release()
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            ui.post { toast("Sparat i Galleriet, albumet Gymrepris") }
        } catch (e: Exception) {
            Log.w("Gymrepris", "save", e)
            ui.post { toast("Klippet kunde inte sparas") }
        }
    }.start()

    /* ---------- Ten times a second ---------- */
    private fun tick() {
        val p = pipeline
        val now = SystemClock.elapsedRealtime()
        val c = open
        var text = ""
        var big = ""

        val busy = settings.visibility == View.VISIBLE || confirm.visibility == View.VISIBLE
        if (busy) lastTouch = now
        // The replay of the last movement is shown to its end before the screen goes dark.
        val active = maxOf(lastTouch, p?.lastMotionMs ?: 0)
        val rest = s.idle > 0 && c == null && p != null && liveReady && now - active > maxOf(s.idle * 60, s.delay + 10) * 1000L
        if (rest != idle) {
            idle = rest
            window.attributes = window.attributes.apply {
                screenBrightness = if (idle) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
            if (idle) stopPlayer() else if (c == null) startLive()
        }

        if (c != null) {
            val (at, isPlaying) = synchronized(lock) { pos to playing }
            playBtn.text = if (isPlaying) "Paus" else "Spela"
            if (!dragging) seek.progress = (at / 1000).toInt()
            timeText.text = String.format(Locale.ROOT, "%.2f / %.2f s", at / 1e6, c.dur / 1e6)
        } else if (error != null) text = error!!
        else if (p == null || !liveReady) text = "Startar kameran …"
        else if (idle) text = "Viloläge. Bilden kommer tillbaka när något rör sig eller när du trycker på skärmen."
        else {
            val first = p.live.firstTs()
            val t = p.nowTs()
            if (first != null && t != null) {
                val wait = (first - (t - s.delay * 1_000_000L)) / 1e6
                if (wait > 0.05) { text = "Reprisen börjar om"; big = "${Math.ceil(wait).toInt()} s" }
            }
        }
        if (messageText.text.toString() != text) messageText.text = text
        if (messageBig.text.toString() != big) messageBig.text = big
        messageBig.visibility = if (big.isEmpty()) View.GONE else View.VISIBLE
        retry.visibility = if (error != null && c == null) View.VISIBLE else View.GONE
        message.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        message.setBackgroundColor(if (idle && c == null) Color.BLACK else Color.TRANSPARENT)
        chip.visibility = if (p?.recording == true && c == null && !idle) View.VISIBLE else View.GONE

        if (settings.visibility == View.VISIBLE) {
            meter?.let { m -> m.value = p?.score ?: 0f; m.limit = p?.threshold() ?: 0f; m.invalidate() }
            val info = "Kameran ger: ${p?.info ?: ""}"
            infoText?.let { if (it.text.toString() != info) it.text = info }
        }
    }

    private fun toast(t: String) {
        toastView.text = t
        toastView.visibility = View.VISIBLE
        ui.removeCallbacks(hideToast)
        ui.postDelayed(hideToast, if (t.length > 30) 4000 else 1800)
    }

    private fun ask(text: String, yes: () -> Unit) {
        confirmText.text = text
        confirmYes = yes
        confirm.visibility = View.VISIBLE
    }

    /* ---------- Building blocks ---------- */
    private fun shape(fill: Int, stroke: Int, radius: Int, dashed: Boolean = false) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (dashed) setStroke(dp(1), stroke, dp(4).toFloat(), dp(3).toFloat()) else setStroke(dp(1), stroke)
    }

    private fun buttonBg(fill: Int, stroke: Int, radius: Int): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(if (fill == BTN) BTN_DOWN else fill, stroke, radius))
        addState(intArrayOf(), shape(fill, stroke, radius))
    }

    private fun text(t: String, size: Float, color: Int = FG, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = text(t, 16f, if (primary) ON_ACCENT else FG, primary).apply {
        gravity = Gravity.CENTER
        background = if (primary) buttonBg(ACCENT, ACCENT, 12) else buttonBg(BTN, LINE, 12)
        minimumHeight = dp(56); minimumWidth = dp(56)
        setPadding(dp(18), 0, dp(18), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun gap(v: View, end: Int = 0, bottom: Int = 0, width: Int = WRAP_CONTENT, weight: Float = 0f) =
        v.apply { layoutParams = LinearLayout.LayoutParams(width, WRAP_CONTENT, weight).apply { marginEnd = dp(end); bottomMargin = dp(bottom) } }

    private fun line(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, v.layoutParams ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { if (i < views.size - 1) marginEnd = dp(8) }) }
    }

    private fun group(vararg views: View) = line(*views).apply {
        background = shape(PANEL, Color.TRANSPARENT, 14)
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }

    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    private fun fade(top: Int, bottom: Int) = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))

    /* ---------- Settings ---------- */
    private fun heading(t: String) = text(t.uppercase(), 13f, DIM).apply { letterSpacing = 0.08f; setPadding(0, dp(10), 0, 0) }

    private fun hint(t: String) = text(t, 13f, DIM)

    private fun setting(name: String, control: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        addView(text(name, 16f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
        addView(control, control.layoutParams ?: LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
    }

    private fun select(options: List<Pair<String, Int>>, current: () -> Int, set: (Int) -> Unit): TextView {
        fun name() = (options.firstOrNull { it.second == current() } ?: options.first()).first + "   ▾"
        val v = text(name(), 16f)
        v.background = buttonBg(BTN, LINE, 10)
        v.setPadding(dp(12), dp(10), dp(12), dp(10))
        v.minimumWidth = dp(128)
        v.setOnClickListener {
            PopupMenu(this, v).apply {
                options.forEachIndexed { i, o -> menu.add(0, i, i, o.first) }
                setOnMenuItemClickListener { item -> set(options[item.itemId].second); v.text = name(); true }
                show()
            }
        }
        return v
    }

    private fun check(current: Boolean, set: (Boolean) -> Unit) = CheckBox(this).apply {
        isChecked = current
        buttonTintList = ColorStateList.valueOf(ACCENT)
        scaleX = 1.3f; scaleY = 1.3f
        setOnCheckedChangeListener { _, on -> set(on) }
    }

    private fun stepper(min: Int, max: Int, current: () -> Int, show: (Int) -> String, set: (Int) -> Unit): View {
        val value = text(show(current()), 16f).apply { gravity = Gravity.CENTER; minimumWidth = dp(64) }
        fun change(d: Int) { set((current() + d).coerceIn(min, max)); value.text = show(current()) }
        fun small(t: String, d: Int) = button(t) { change(d) }.apply {
            textSize = 20f; minimumHeight = dp(44); minimumWidth = dp(48); setPadding(0, 0, 0, 0)
            background = buttonBg(BTN, LINE, 10)
        }
        return line(small("−", -1), value, small("+", 1))
    }

    private fun halves(v: Int) = (if (v % 2 == 0) "${v / 2}" else "${v / 2},5") + " s"

    private fun fillSettings() {
        val shutters = listOf("Automatisk" to 0, "1/250 s" to 250, "1/500 s" to 500, "1/1000 s" to 1000, "1/2000 s" to 2000)
        val idles = listOf("Av" to 0, "Efter 1 minut" to 1, "Efter 2 minuter" to 2, "Efter 5 minuter" to 5, "Efter 10 minuter" to 10)
        val m = Meter(this)
        val info = text("", 13f, DIM)
        meter = m; infoText = info
        val sens = SeekBar(this).apply {
            max = 9; progress = s.sens - 1
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(36))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) { if (fromUser) s.sens = progress + 1 }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        val title = line(
            gap(text("Inställningar", 22f, FG, true), width = 0, weight = 1f),
            button("Stäng") { settings.visibility = View.GONE },
        )
        val rows = listOf(
            title,
            heading("Kamera"),
            setting("Upplösning", select(listOf("720p" to 720, "1080p" to 1080), { s.res }) { s.res = it; startPipeline() }),
            setting("Bilder per sekund", select(listOf("30" to 30, "60" to 60), { s.fps }) { s.fps = it; startPipeline() }),
            setting("Slutartid", select(shutters, { s.shutter }) { s.shutter = it; startPipeline() }),
            hint("En kort slutartid ger skarpare bildrutor i slow motion, men kräver bra ljus. I svagt ljus blir bilden mörk och brusig."),
            setting("Viloläge utan rörelse", select(idles, { s.idle }) { s.idle = it }),
            hint("I viloläge släcks reprisbilden och skärmen tonas ned för att spara batteri. Kameran fortsätter, och bilden kommer tillbaka när något rör sig eller när du trycker på skärmen."),
            heading("Klipp"),
            setting("Spara klipp automatiskt vid rörelse", check(s.auto == 1) { s.auto = if (it) 1 else 0 }),
            setting("Känslighet", sens),
            m.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(16)) },
            hint("Stapeln visar rörelsen just nu. När den går förbi det röda strecket räknas det som en övning. Ställ känsligheten så att stapeln ligger under strecket när ingen hoppar."),
            setting("Sekunder före rörelsen", stepper(0, 10, { s.pre }, ::halves) { s.pre = it }),
            setting("Sekunder efter rörelsen", stepper(1, 10, { s.post }, ::halves) { s.post = it }),
            setting("Längd på knappen Spara klipp", stepper(3, 20, { s.manual }, { "$it s" }) { s.manual = it }),
            info,
        )
        settingsBody.removeAllViews()
        for (r in rows) settingsBody.addView(r, (r.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)).apply {
            if (width == WRAP_CONTENT) width = MATCH_PARENT
            bottomMargin = dp(14)
        })
    }

    private fun showSettings() {
        fillSettings()
        settings.visibility = View.VISIBLE
    }

    /* ---------- Screen ---------- */
    private fun showClips(isNew: Boolean = false) {
        clipRow.removeAllViews()
        for (i in 0 until maxClips) {
            val c = clips.getOrNull(i)
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            if (c == null) {
                cell.background = shape(Color.argb(0x88, 0x16, 0x1c, 0x26), LINE, 12, dashed = true)
                cell.addView(text("Klipp ${i + 1}", 12f, DIM).apply { gravity = Gravity.CENTER })
            } else {
                cell.background = if (isNew && i == 0) GradientDrawable().apply { setColor(BTN); cornerRadius = dp(12).toFloat(); setStroke(dp(3), ACCENT) } else buttonBg(BTN, LINE, 12)
                cell.addView(ImageView(this).apply {
                    setImageBitmap(c.thumb); scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.BLACK)
                    clipToOutline = true
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                    }
                }, LinearLayout.LayoutParams(dp(104), dp(58)))
                cell.addView(text("${hhmmss(c.at)} · ${Math.round(c.dur / 1e6)} s", 12f).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, 0) })
                cell.setOnClickListener { openClip(c) }
            }
            clipRow.addView(cell, LinearLayout.LayoutParams(dp(112), dp(86)).apply { if (i < maxClips - 1) marginEnd = dp(8) })
        }
    }

    private fun buildScreen() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this).apply { holder.addCallback(this@MainActivity) }
        root.addView(surface, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            open?.let { fit(it.width, it.height, it.rotation) } ?: pipeline?.let { fit(it.width, it.height, it.rotation) }
        }
        surface.setOnClickListener { if (open != null) togglePlay() }

        messageText = text("", 18f, DIM).apply { gravity = Gravity.CENTER; maxWidth = dp(560); setShadowLayer(8f, 0f, 2f, Color.BLACK) }
        messageBig = text("", 96f, ACCENT, true).apply { gravity = Gravity.CENTER; setShadowLayer(12f, 0f, 2f, Color.BLACK); fontFeatureSettings = "tnum" }
        retry = button("Försök igen", primary = true) { startPipeline() }
        message = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(messageText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(messageBig, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(retry, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        }
        root.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val dot = View(this).apply { background = shape(REC, REC, 6); layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(8) } }
        chip = line(dot, text("Sparar klipp", 16f, FG, true)).apply {
            background = shape(PANEL, Color.TRANSPARENT, 999)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            visibility = View.GONE
        }
        root.addView(chip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        /* Bottom bar */
        delayValue = text("", 22f, FG, true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        val delayBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumWidth = dp(92)
            addView(text("FÖRDRÖJNING", 11f, DIM).apply { letterSpacing = 0.06f; gravity = Gravity.CENTER })
            addView(delayValue)
        }
        fun square(t: String, d: Int) = button(t) { setDelay(d) }.apply { textSize = 28f; setPadding(0, 0, 0, 0); minimumWidth = dp(60) }
        clipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = fade(Color.TRANSPARENT, Color.argb(0xcc, 0, 0, 0))
            setPadding(dp(12), dp(24), dp(12), dp(10))
            addView(group(square("−", -1), delayBox, square("+", 1)))
            addView(spacer()); addView(clipRow); addView(spacer())
            addView(group(
                button("Spara klipp") { if (pipeline?.saveManual() != true) toast("Inget att spara ännu") },
                button("Inställningar") { if (settings.visibility == View.VISIBLE) settings.visibility = View.GONE else showSettings() },
            ))
        }
        root.addView(bar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        setDelay(0)
        showClips()

        /* Clip viewer */
        viewerTitle = text("", 18f, FG, true)
        viewerTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = fade(Color.argb(0xcc, 0, 0, 0), Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(24))
            addView(viewerTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(button("Spara i Galleriet") {
                val c = open ?: return@button
                ask("Spara klippet från ${hhmmss(c.at)} som en videofil i Galleriet?") { saveToGallery(c) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(10) })
            addView(button("Stäng") { closeClip() })
            visibility = View.GONE
        }
        root.addView(viewerTop, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        playBtn = button("Paus", primary = true) { togglePlay() }.apply { minimumWidth = dp(110) }
        seek = SeekBar(this).apply {
            progressTintList = ColorStateList.valueOf(ACCENT); thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // Dragging pauses the clip; letting go resumes it if it was playing when the drag began.
                override fun onStartTrackingTouch(sb: SeekBar) = synchronized(lock) { dragging = true; resume = playing; playing = false }
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) synchronized(lock) { pos = progress * 1000L }
                }
                override fun onStopTrackingTouch(sb: SeekBar) = synchronized(lock) { dragging = false; playing = resume; lastNano = System.nanoTime() }
            })
        }
        timeText = text("", 16f).apply { minimumWidth = dp(136); gravity = Gravity.END; fontFeatureSettings = "tnum" }
        viewerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = fade(Color.TRANSPARENT, Color.argb(0xdd, 0, 0, 0))
            setPadding(dp(12), dp(28), dp(12), dp(12))
            fun add(v: View, end: Int) = addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(end) })
            add(button("−1 bild") { step(-1) }, 10)
            add(playBtn, 10)
            add(button("+1 bild") { step(1) }, 10)
            val speeds = listOf("1×" to 8, "½" to 4, "¼" to 2, "⅛" to 1)
            speeds.forEachIndexed { i, (name, eighths) ->
                val b = button(name) { s.speed = eighths; markSpeed() }
                speedBtns.add(eighths to b)
                add(b, if (i < speeds.size - 1) 6 else 10)
            }
            addView(seek, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(timeText)
            visibility = View.GONE
        }
        root.addView(viewerBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        /* Settings panel along the right edge */
        settingsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(4)) }
        settings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            setBackgroundColor(BG)
            addView(View(this@MainActivity).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(dp(1), MATCH_PARENT))
            addView(ScrollView(this@MainActivity).apply { addView(settingsBody) }, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            visibility = View.GONE
        }
        root.addView(settings, FrameLayout.LayoutParams(dp(440), MATCH_PARENT, Gravity.END))

        /* Toast and confirmation */
        toastView = text("", 16f, ON_ACCENT, true).apply {
            background = shape(ACCENT, ACCENT, 999)
            setPadding(dp(20), dp(10), dp(20), dp(10))
            visibility = View.GONE
        }
        root.addView(toastView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(70) })

        confirmText = text("", 20f)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(BG, LINE, 16)
            setPadding(dp(24), dp(24), dp(24), dp(24))
            isClickable = true
            addView(confirmText, LinearLayout.LayoutParams(dp(380), WRAP_CONTENT).apply { bottomMargin = dp(20) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                addView(button("Avbryt") { confirm.visibility = View.GONE }.apply { minimumWidth = dp(120) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(12) })
                addView(button("Spara", primary = true) { confirm.visibility = View.GONE; confirmYes?.invoke() }.apply { minimumWidth = dp(120) })
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        confirm = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(0xaa, 0, 0, 0))
            isClickable = true
            addView(card, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
            visibility = View.GONE
        }
        root.addView(confirm, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        setContentView(root)
    }
}
