package com.wellington.flappybird

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Flappy Bird desenhado inteiramente com Canvas (sem imagens externas).
 * Todas as medidas são em "unidades lógicas" (tela de referência com 800 de altura)
 * multiplicadas por [s], para ficar igual em qualquer resolução.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {

    private enum class State { READY, PLAYING, GAME_OVER }

    private data class Pipe(var x: Float, val gapCenter: Float, var scored: Boolean = false)

    // ---- Constantes de física (unidades lógicas) ----
    private companion object {
        const val GRAVITY = 1800f
        const val FLAP_VELOCITY = -520f
        const val MAX_FALL = 900f
        const val SPEED = 200f
        const val PIPE_WIDTH = 80f
        const val PIPE_GAP = 175f
        const val PIPE_INTERVAL = 1.5f
        const val BIRD_RADIUS = 17f
        const val GROUND_HEIGHT = 110f
        const val RESTART_DELAY_MS = 600L
    }

    private val lock = Any()
    private var thread: Thread? = null
    @Volatile private var running = false
    private var surfaceReady = false

    // ---- Dimensões ----
    private var w = 0f
    private var h = 0f
    private var s = 1f
    private var groundY = 0f

    // ---- Estado do jogo ----
    private var state = State.READY
    private var birdX = 0f
    private var birdY = 0f
    private var velocity = 0f
    private var time = 0f
    private val pipes = ArrayList<Pipe>()
    private var spawnTimer = 0f
    private var groundOffset = 0f
    private var cloudOffset = 0f
    private var score = 0
    private var gameOverAt = 0L
    private var flashAlpha = 0f

    private val prefs = context.getSharedPreferences("flappy", Context.MODE_PRIVATE)
    private var best = prefs.getInt("best", 0)

    // ---- Pincéis ----
    private val skyPaint = Paint()
    private val cloudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 255, 255, 255) }
    private val pipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(115, 191, 46) }
    private val pipeLight = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(155, 227, 89) }
    private val pipeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 56, 71); style = Paint.Style.STROKE
    }
    private val edgeFill = Paint().apply { color = Color.rgb(84, 56, 71) }
    private val groundPaint = Paint().apply { color = Color.rgb(222, 216, 149) }
    private val grassPaint = Paint().apply { color = Color.rgb(94, 226, 112) }
    private val grassDark = Paint().apply { color = Color.rgb(84, 184, 76) }
    private val birdBody = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 208, 48) }
    private val birdWing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 160, 32) }
    private val birdOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 56, 71); style = Paint.Style.STROKE
    }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val beakPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 112, 48) }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 56, 71); textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(222, 216, 149) }
    private val panelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(84, 56, 71); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val flashPaint = Paint().apply { color = Color.WHITE }

    private val rect = RectF()
    private val beakPath = Path()

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    // ================= Ciclo de vida =================

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        synchronized(lock) {
            w = width.toFloat()
            h = height.toFloat()
            s = h / 800f
            groundY = h - GROUND_HEIGHT * s
            skyPaint.shader = LinearGradient(
                0f, 0f, 0f, groundY,
                Color.rgb(78, 192, 202), Color.rgb(170, 230, 235), Shader.TileMode.CLAMP
            )
            pipeEdge.strokeWidth = 3f * s
            birdOutline.strokeWidth = 2.5f * s
            textStroke.strokeWidth = 8f * s
            reset()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        pause()
    }

    fun resume() {
        if (running || !surfaceReady) return
        running = true
        thread = Thread(this, "GameLoop").also { it.start() }
    }

    fun pause() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
        }
        thread = null
    }

    // ================= Loop =================

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = min((now - last) / 1_000_000_000f, 0.033f)
            last = now

            val canvas: Canvas? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas()
                else holder.lockCanvas()
            } catch (_: Exception) {
                null
            }
            if (canvas == null) {
                SystemClock.sleep(8)
                continue
            }
            try {
                synchronized(lock) {
                    if (w > 0f && h > 0f) {
                        update(dt)
                        render(canvas)
                    }
                }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    // ================= Lógica =================

    private fun reset() {
        state = State.READY
        birdX = w * 0.3f
        birdY = h * 0.45f
        velocity = 0f
        pipes.clear()
        spawnTimer = 0f
        score = 0
        flashAlpha = 0f
    }

    private fun flap() {
        velocity = FLAP_VELOCITY * s
    }

    private fun update(dt: Float) {
        time += dt
        if (flashAlpha > 0f) flashAlpha = max(0f, flashAlpha - dt * 3f)

        when (state) {
            State.READY -> {
                birdY = h * 0.45f + sin(time * 5f) * 8f * s
                scrollWorld(dt, movePipes = false)
            }

            State.PLAYING -> {
                applyGravity(dt)
                scrollWorld(dt, movePipes = true)

                spawnTimer -= dt
                if (spawnTimer <= 0f) {
                    spawnPipe()
                    spawnTimer = PIPE_INTERVAL
                }

                val r = BIRD_RADIUS * s
                if (birdY - r < 0f) {
                    birdY = r
                    velocity = 0f
                }
                if (birdY + r >= groundY || hitsPipe()) die()

                for (p in pipes) {
                    if (!p.scored && p.x + PIPE_WIDTH * s < birdX) {
                        p.scored = true
                        score++
                    }
                }
            }

            State.GAME_OVER -> {
                val r = BIRD_RADIUS * s
                if (birdY + r < groundY) {
                    applyGravity(dt)
                    if (birdY + r > groundY) birdY = groundY - r
                }
            }
        }
    }

    private fun applyGravity(dt: Float) {
        velocity = min(velocity + GRAVITY * s * dt, MAX_FALL * s)
        birdY += velocity * dt
    }

    private fun scrollWorld(dt: Float, movePipes: Boolean) {
        val dx = SPEED * s * dt
        groundOffset = (groundOffset + dx) % (24f * s)
        cloudOffset = (cloudOffset + dx * 0.2f) % w
        if (movePipes) {
            for (p in pipes) p.x -= dx
            pipes.removeAll { it.x + PIPE_WIDTH * s * 1.2f < 0f }
        }
    }

    private fun spawnPipe() {
        val margin = 70f * s
        val half = PIPE_GAP * s / 2f
        val minC = margin + half
        val maxC = groundY - margin - half
        val center = minC + Random.nextFloat() * (maxC - minC)
        pipes.add(Pipe(w + 10f * s, center))
    }

    private fun hitsPipe(): Boolean {
        val r = BIRD_RADIUS * s * 0.9f // pequena folga para ser mais justo
        val pw = PIPE_WIDTH * s
        val half = PIPE_GAP * s / 2f
        for (p in pipes) {
            if (circleHitsRect(birdX, birdY, r, p.x, 0f, p.x + pw, p.gapCenter - half)) return true
            if (circleHitsRect(birdX, birdY, r, p.x, p.gapCenter + half, p.x + pw, groundY)) return true
        }
        return false
    }

    private fun circleHitsRect(cx: Float, cy: Float, r: Float, l: Float, t: Float, rr: Float, b: Float): Boolean {
        val nx = cx.coerceIn(l, rr)
        val ny = cy.coerceIn(t, b)
        val dx = cx - nx
        val dy = cy - ny
        return dx * dx + dy * dy < r * r
    }

    private fun die() {
        state = State.GAME_OVER
        gameOverAt = SystemClock.uptimeMillis()
        flashAlpha = 1f
        if (score > best) {
            best = score
            prefs.edit().putInt("best", best).apply()
        }
    }

    // ================= Toque =================

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            synchronized(lock) {
                when (state) {
                    State.READY -> {
                        state = State.PLAYING
                        spawnTimer = 0.6f
                        flap()
                    }
                    State.PLAYING -> flap()
                    State.GAME_OVER -> {
                        if (SystemClock.uptimeMillis() - gameOverAt > RESTART_DELAY_MS) reset()
                    }
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    // ================= Desenho =================

    private fun render(c: Canvas) {
        c.drawRect(0f, 0f, w, groundY, skyPaint)
        drawClouds(c)
        for (p in pipes) drawPipe(c, p)
        drawGround(c)
        drawBird(c)
        drawHud(c)
        if (flashAlpha > 0f) {
            flashPaint.alpha = (flashAlpha * 200).toInt()
            c.drawRect(0f, 0f, w, h, flashPaint)
        }
    }

    private fun drawClouds(c: Canvas) {
        val baseY = groundY - 60f * s
        var x = -cloudOffset
        while (x < w + 200f * s) {
            c.drawCircle(x + 40f * s, baseY, 40f * s, cloudPaint)
            c.drawCircle(x + 90f * s, baseY - 20f * s, 50f * s, cloudPaint)
            c.drawCircle(x + 145f * s, baseY, 42f * s, cloudPaint)
            c.drawRect(x + 40f * s, baseY, x + 145f * s, groundY, cloudPaint)
            x += 190f * s
        }
    }

    private fun drawPipe(c: Canvas, p: Pipe) {
        val pw = PIPE_WIDTH * s
        val half = PIPE_GAP * s / 2f
        val capH = 30f * s
        val capOver = 6f * s
        val topEnd = p.gapCenter - half
        val botStart = p.gapCenter + half

        // corpo de cima
        drawPipeRect(c, p.x, -10f, p.x + pw, topEnd - capH)
        // tampa de cima
        drawPipeRect(c, p.x - capOver, topEnd - capH, p.x + pw + capOver, topEnd)
        // corpo de baixo
        drawPipeRect(c, p.x, botStart + capH, p.x + pw, groundY + 10f)
        // tampa de baixo
        drawPipeRect(c, p.x - capOver, botStart, p.x + pw + capOver, botStart + capH)
    }

    private fun drawPipeRect(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        c.drawRect(l, t, r, b, pipePaint)
        c.drawRect(l + (r - l) * 0.12f, t, l + (r - l) * 0.3f, b, pipeLight)
        c.drawRect(l, t, r, b, pipeEdge)
    }

    private fun drawGround(c: Canvas) {
        c.drawRect(0f, groundY, w, h, groundPaint)
        val grassH = 18f * s
        c.drawRect(0f, groundY, w, groundY + grassH, grassPaint)
        val stripe = 24f * s
        var x = -groundOffset
        while (x < w) {
            c.drawRect(x, groundY + grassH * 0.4f, x + stripe / 2f, groundY + grassH, grassDark)
            x += stripe
        }
        c.drawRect(0f, groundY, w, groundY + 3f * s, edgeFill)
    }

    private fun drawBird(c: Canvas) {
        val r = BIRD_RADIUS * s
        val angle = when (state) {
            State.READY -> 0f
            else -> (velocity / (MAX_FALL * s) * 90f).coerceIn(-25f, 90f)
        }
        c.save()
        c.translate(birdX, birdY)
        c.rotate(angle)

        // corpo
        rect.set(-r * 1.15f, -r, r * 1.15f, r)
        c.drawOval(rect, birdBody)
        c.drawOval(rect, birdOutline)

        // asa (bate enquanto não está caído)
        val flapping = state != State.GAME_OVER
        val wingLift = if (flapping) sin(time * 22f) * 5f * s else 0f
        rect.set(-r * 1.0f, -r * 0.1f + wingLift, -r * 0.05f, r * 0.55f + wingLift)
        c.drawOval(rect, birdWing)
        c.drawOval(rect, birdOutline)

        // olho
        c.drawCircle(r * 0.45f, -r * 0.35f, r * 0.38f, white)
        c.drawCircle(r * 0.45f, -r * 0.35f, r * 0.38f, birdOutline)
        c.drawCircle(r * 0.58f, -r * 0.33f, r * 0.15f, black)

        // bico
        beakPath.reset()
        beakPath.moveTo(r * 0.7f, r * 0.05f)
        beakPath.lineTo(r * 1.55f, r * 0.15f)
        beakPath.lineTo(r * 1.35f, r * 0.5f)
        beakPath.lineTo(r * 0.7f, r * 0.45f)
        beakPath.close()
        c.drawPath(beakPath, beakPaint)
        c.drawPath(beakPath, birdOutline)

        c.restore()
    }

    private fun drawOutlined(c: Canvas, text: String, x: Float, y: Float, size: Float) {
        textStroke.textSize = size
        textFill.textSize = size
        c.drawText(text, x, y, textStroke)
        c.drawText(text, x, y, textFill)
    }

    private fun drawHud(c: Canvas) {
        val cx = w / 2f
        when (state) {
            State.READY -> {
                drawOutlined(c, "FLAPPY BIRD", cx, h * 0.22f, 56f * s)
                drawOutlined(c, "Toque para começar", cx, h * 0.62f, 30f * s)
                if (best > 0) drawOutlined(c, "Recorde: $best", cx, h * 0.69f, 24f * s)
            }

            State.PLAYING -> drawOutlined(c, score.toString(), cx, h * 0.14f, 64f * s)

            State.GAME_OVER -> {
                drawOutlined(c, "FIM DE JOGO", cx, h * 0.26f, 50f * s)

                val pw = min(w * 0.8f, 320f * s)
                rect.set(cx - pw / 2f, h * 0.32f, cx + pw / 2f, h * 0.32f + 170f * s)
                c.drawRoundRect(rect, 16f * s, 16f * s, panelPaint)
                pipeEdge.strokeWidth = 4f * s
                c.drawRoundRect(rect, 16f * s, 16f * s, pipeEdge)
                pipeEdge.strokeWidth = 3f * s

                panelText.textSize = 22f * s
                c.drawText("PONTUAÇÃO", cx, rect.top + 36f * s, panelText)
                drawOutlined(c, score.toString(), cx, rect.top + 80f * s, 40f * s)
                c.drawText("RECORDE", cx, rect.top + 116f * s, panelText)
                drawOutlined(c, best.toString(), cx, rect.top + 156f * s, 34f * s)

                if (SystemClock.uptimeMillis() - gameOverAt > RESTART_DELAY_MS) {
                    drawOutlined(c, "Toque para reiniciar", cx, h * 0.62f, 28f * s)
                }
            }
        }
    }
}
