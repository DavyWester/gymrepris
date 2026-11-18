package se.gymrepris.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), Pipeline.Listener, SurfaceHolder.Callback {
    private val accent = Color.rgb(0xff, 0xd2, 0x3f)
    private val maxClips = 3
    private lateinit var s: Settings
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var message: TextView
    private lateinit var chip: TextView
    private lateinit var bar: LinearLayout
    private lateinit var delayText: TextView
    private lateinit var clipRow: LinearLayout
    private lateinit var viewerTop: LinearLayout
    private lateinit var viewerBar: LinearLayout
    private lateinit var viewerTitle: TextView
    private lateinit var playBtn: Button
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private val speedBtns = ArrayList<Pair<Int, Button>>()

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
        showClips()
        toast("Klipp sparat")
    }

    private fun setDelay(d: Int) {
        s.delay = (s.delay + d).coerceIn(1, 60)
        delayText.text = "Fördröjning\n${s.delay} s"
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

    private fun markSpeed() = speedBtns.forEach { (v, b) -> b.setTextColor(if (v == s.speed) accent else Color.WHITE) }

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
        else if (idle) text = "Viloläge\nBilden kommer tillbaka när något rör sig eller när du trycker på skärmen."
        else {
            val first = p.live.firstTs()
            val t = p.nowTs()
            if (first != null && t != null) {
                val wait = (first - (t - s.delay * 1_000_000L)) / 1e6
                if (wait > 0.05) text = "Reprisen börjar om\n${Math.ceil(wait).toInt()} s"
            }
        }
        if (message.text.toString() != text) message.text = text
        message.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        message.setBackgroundColor(if (idle && c == null) Color.BLACK else Color.TRANSPARENT)
        chip.visibility = if (p?.recording == true && c == null && !idle) View.VISIBLE else View.GONE
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /* ---------- Settings ---------- */
    private fun pick(title: String, options: List<Pair<String, Int>>, current: Int, restart: Boolean, set: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), options.indexOfFirst { it.second == current }) { d, which ->
                set(options[which].second)
                d.dismiss()
                if (restart) startPipeline()
                showSettings()
            }.setNegativeButton("Avbryt") { _, _ -> showSettings() }.show()
    }

    private fun showSettings() {
        val shutters = listOf("Automatisk" to 0, "1/250 s" to 250, "1/500 s" to 500, "1/1000 s" to 1000, "1/2000 s" to 2000)
        val idles = listOf("Av" to 0, "Efter 1 minut" to 1, "Efter 2 minuter" to 2, "Efter 5 minuter" to 5, "Efter 10 minuter" to 10)
        val items = listOf(
            "Upplösning: ${s.res}p" to { pick("Upplösning", listOf("720p" to 720, "1080p" to 1080), s.res, true) { s.res = it } },
            "Bilder per sekund: ${s.fps}" to { pick("Bilder per sekund", listOf("30" to 30, "60" to 60), s.fps, true) { s.fps = it } },
            "Slutartid: ${shutters.first { it.second == s.shutter }.first}" to { pick("Slutartid", shutters, s.shutter, true) { s.shutter = it } },
            "Spara klipp automatiskt vid rörelse: ${if (s.auto == 1) "På" else "Av"}" to { pick("Spara klipp automatiskt vid rörelse", listOf("På" to 1, "Av" to 0), s.auto, false) { s.auto = it } },
            "Känslighet: ${s.sens}" to { pick("Känslighet (10 är känsligast)", (1..10).map { "$it" to it }, s.sens, false) { s.sens = it } },
            "Viloläge utan rörelse: ${idles.first { it.second == s.idle }.first}" to { pick("Viloläge utan rörelse", idles, s.idle, false) { s.idle = it } },
            "Kameran ger: ${pipeline?.info ?: ""}" to { showSettings() },
        )
        AlertDialog.Builder(this).setTitle("Inställningar")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setPositiveButton("Stäng", null).show()
    }

    /* ---------- Screen ---------- */
    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 17f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply { setColor(Color.rgb(0x23, 0x2c, 0x3a)); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.rgb(0x2b, 0x35, 0x45)) }
        minimumHeight = dp(56); minHeight = dp(56)
        minimumWidth = dp(56); minWidth = dp(56)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        for (v in views) addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
    }

    private fun label(size: Float) = TextView(this).apply { setTextColor(Color.WHITE); textSize = size; gravity = Gravity.CENTER }

    private fun showClips() {
        clipRow.removeAllViews()
        for (i in 0 until maxClips) {
            val c = clips.getOrNull(i)
            val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            if (c == null) cell.addView(label(12f).apply { text = "Klipp ${i + 1}"; setTextColor(Color.GRAY) }, LinearLayout.LayoutParams(dp(112), dp(80)))
            else {
                cell.addView(ImageButton(this).apply {
                    setImageBitmap(c.thumb); scaleType = ImageView.ScaleType.FIT_XY; setPadding(0, 0, 0, 0)
                    setBackgroundColor(Color.BLACK)
                    setOnClickListener { openClip(c) }
                }, LinearLayout.LayoutParams(dp(112), dp(63)))
                cell.addView(label(12f).apply { text = "${hhmmss(c.at)} · ${c.dur / 1_000_000} s" })
            }
            clipRow.addView(cell)
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

        message = label(34f).apply { setTypeface(typeface, Typeface.BOLD); setPadding(dp(32), dp(32), dp(32), dp(32)) }
        root.addView(message, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        chip = label(16f).apply {
            text = "●  Sparar klipp"; setTextColor(Color.rgb(0xff, 0x52, 0x52)); setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply { setColor(Color.argb(0xdd, 0x16, 0x1c, 0x26)); cornerRadius = dp(20).toFloat() }
            visibility = View.GONE
        }
        root.addView(chip, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        delayText = label(17f).apply { minWidth = dp(110) }
        clipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar = row(
            button("−") { setDelay(-1) }, delayText, button("+") { setDelay(1) },
            clipRow,
            button("Spara klipp") { if (pipeline?.saveManual() != true) toast("Inget att spara ännu") },
            button("Inställningar") { showSettings() },
        )
        root.addView(bar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        setDelay(0)
        showClips()

        viewerTitle = label(18f)
        viewerTop = row(
            viewerTitle,
            button("Spara i Galleriet") {
                val c = open ?: return@button
                AlertDialog.Builder(this).setMessage("Spara klippet från ${hhmmss(c.at)} som en videofil i Galleriet?")
                    .setNegativeButton("Avbryt", null).setPositiveButton("Spara") { _, _ -> saveToGallery(c) }.show()
            },
            button("Stäng") { closeClip() },
        ).apply { visibility = View.GONE }
        root.addView(viewerTop, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))

        playBtn = button("Paus") { togglePlay() }.apply { minWidth = dp(110); setTextColor(accent) }
        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                // Dragging pauses the clip; letting go resumes it if it was playing when the drag began.
                override fun onStartTrackingTouch(sb: SeekBar) = synchronized(lock) { dragging = true; resume = playing; playing = false }
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) synchronized(lock) { pos = progress * 1000L }
                }
                override fun onStopTrackingTouch(sb: SeekBar) = synchronized(lock) { dragging = false; playing = resume; lastNano = System.nanoTime() }
            })
        }
        timeText = label(15f).apply { minWidth = dp(130) }
        viewerBar = row(button("−1 bild") { step(-1) }, playBtn, button("+1 bild") { step(1) })
        for ((name, eighths) in listOf("1×" to 8, "½" to 4, "¼" to 2, "⅛" to 1)) {
            val b = button(name) { s.speed = eighths; markSpeed() }
            speedBtns.add(eighths to b)
            viewerBar.addView(b, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        viewerBar.addView(seek, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        viewerBar.addView(timeText)
        viewerBar.visibility = View.GONE
        root.addView(viewerBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))

        setContentView(root)
    }
}
