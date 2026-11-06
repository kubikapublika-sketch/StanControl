package com.example.lovimonety

import android.app.Activity
import android.os.Bundle
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.random.Random
import kotlin.math.max

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        setContentView(GameView(this))
    }
}

private data class FallingItem(var x: Float, var y: Float, val bomb: Boolean, val speed: Float, val radius: Float)

class GameView(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val items = mutableListOf<FallingItem>()
    private var basketX = 0f
    private var score = 0
    private var lives = 3
    private var best = context.getSharedPreferences("game", 0).getInt("best", 0)
    private var running = false
    private var gameOver = false
    private var lastTime = 0L
    private var spawnTimer = 0f
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (basketX == 0f) basketX = w / 2f
        canvas.drawColor(Color.rgb(12, 20, 38))
        p.color = Color.rgb(55, 75, 105)
        for (i in 0..34) {
            val x = (i * 97 % max(1, width)).toFloat()
            val y = (i * 151 % max(1, height)).toFloat()
            canvas.drawCircle(x, y, dp(1.2f), p)
        }
        p.color = Color.WHITE
        p.textSize = dp(22f)
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        canvas.drawText("ЛОВИ МОНЕТЫ", dp(20f), dp(42f), p)
        p.textSize = dp(16f)
        p.typeface = android.graphics.Typeface.DEFAULT
        canvas.drawText("Очки: $score", dp(20f), dp(72f), p)
        canvas.drawText("Рекорд: $best", dp(20f), dp(96f), p)
        p.color = Color.rgb(255, 105, 105)
        canvas.drawText("❤".repeat(lives), w - dp(90f), dp(72f), p)

        items.forEach { item ->
            p.color = if (item.bomb) Color.rgb(235, 75, 75) else Color.rgb(255, 199, 55)
            canvas.drawCircle(item.x, item.y, item.radius, p)
            p.color = if (item.bomb) Color.WHITE else Color.rgb(120, 75, 0)
            p.textAlign = Paint.Align.CENTER
            p.textSize = item.radius * 1.15f
            canvas.drawText(if (item.bomb) "!" else "$", item.x, item.y + item.radius * .38f, p)
            p.textAlign = Paint.Align.LEFT
        }
        val bw = dp(94f); val bh = dp(24f)
        p.color = Color.rgb(65, 190, 145)
        canvas.drawRoundRect(RectF(basketX - bw/2, h - dp(72f), basketX + bw/2, h - dp(72f) + bh), dp(12f), dp(12f), p)
        p.color = Color.rgb(150, 245, 205)
        canvas.drawRoundRect(RectF(basketX - bw/2 + dp(8f), h - dp(78f), basketX + bw/2 - dp(8f), h - dp(68f)), dp(5f), dp(5f), p)

        if (!running && !gameOver) drawOverlay(canvas, "Лови монеты!", "Коснись экрана, чтобы начать")
        if (gameOver) drawOverlay(canvas, "Игра окончена", "Счёт: $score   •   Нажми, чтобы снова")
        if (running) updateGame()
        if (running || !gameOver) postInvalidateOnAnimation()
    }

    private fun drawOverlay(c: Canvas, title: String, subtitle: String) {
        p.color = 0xCC07101F.toInt()
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.textAlign = Paint.Align.CENTER
        p.color = Color.WHITE
        p.textSize = dp(30f)
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        c.drawText(title, width/2f, height/2f - dp(12f), p)
        p.textSize = dp(15f)
        p.typeface = android.graphics.Typeface.DEFAULT
        c.drawText(subtitle, width/2f, height/2f + dp(24f), p)
        p.textAlign = Paint.Align.LEFT
    }

    private fun updateGame() {
        val now = System.nanoTime()
        if (lastTime == 0L) lastTime = now
        val dt = ((now - lastTime) / 1_000_000_000f).coerceAtMost(.04f)
        lastTime = now
        spawnTimer += dt
        if (spawnTimer > .72f) {
            spawnTimer = 0f
            val r = dp(17f)
            items.add(FallingItem(
                Random.nextFloat() * (width - 2*r) + r, -r,
                Random.nextFloat() < .24f,
                dp(210f + Random.nextFloat() * 130f + score.coerceAtMost(100) * 1.2f), r
            ))
        }
        val basketY = height - dp(72f)
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            item.y += item.speed * dt
            if (item.y + item.radius >= basketY && item.y - item.radius <= basketY + dp(25f)
                && kotlin.math.abs(item.x - basketX) < dp(58f)) {
                if (item.bomb) {
                    lives--
                    if (lives <= 0) finishGame()
                } else score++
                iterator.remove()
            } else if (item.y - item.radius > height) iterator.remove()
        }
    }

    private fun finishGame() {
        running = false
        gameOver = true
        if (score > best) {
            best = score
            context.getSharedPreferences("game", 0).edit().putInt("best", best).apply()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (!running && (gameOver || event.action == MotionEvent.ACTION_DOWN)) startGame()
                basketX = event.x.coerceIn(dp(50f), width - dp(50f))
                return true
            }
        }
        return true
    }

    private fun startGame() {
        score = 0
        lives = 3
        items.clear()
        spawnTimer = 0f
        lastTime = 0L
        gameOver = false
        running = true
        postInvalidateOnAnimation()
    }
}
