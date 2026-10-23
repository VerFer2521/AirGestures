package com.project_by_adr.airgestures

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class OnboardingActivity : AppCompatActivity() {

    private data class Page(val emoji: String, val title: String, val desc: String)

    private val pages = listOf(
        Page("🖐", "Show your hand",
            "Hold your hand in front of the front camera. The AI runs on your phone and nothing is uploaded."),
        Page("⭕", "Hold to confirm",
            "Keep a gesture steady until the ring fills. That is how accidents and flickers are ignored."),
        Page("🎤", "Or just say it",
            "Show ✌️ or tap the mic, then say \"open Spotify\", \"what time is it\" or \"tell me a joke\"."),
        Page("⏻", "Works in any app",
            "Tap Always-on and a small glass panel floats at the bottom of the screen while you use other apps.")
    )

    private var index = 0
    private val dots = mutableListOf<View>()

    private lateinit var emoji: TextView
    private lateinit var title: TextView
    private lateinit var desc: TextView
    private lateinit var next: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        emoji = findViewById(R.id.obEmoji)
        title = findViewById(R.id.obTitle)
        desc = findViewById(R.id.obDesc)
        next = findViewById(R.id.obNext)
        val dotRow = findViewById<LinearLayout>(R.id.obDots)

        for (i in pages.indices) {
            val dot = View(this)
            dots.add(dot)
            dotRow.addView(dot)
        }

        next.setOnClickListener {
            if (index < pages.size - 1) {
                index++
                showPage(true)
            } else {
                finishTour()
            }
        }
        findViewById<Button>(R.id.skipButton).setOnClickListener { finishTour() }

        showPage(true)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun showPage(animate: Boolean) {
        val page = pages[index]
        emoji.text = page.emoji
        title.text = page.title
        desc.text = page.desc
        next.text = if (index == pages.size - 1) "Let's go  ✨" else "Next"

        // page dots: the current one is long and cyan
        for ((i, dot) in dots.withIndex()) {
            val active = i == index
            val params = LinearLayout.LayoutParams(dp(if (active) 22 else 8), dp(8))
            params.marginStart = dp(4)
            params.marginEnd = dp(4)
            dot.layoutParams = params
            dot.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(if (active) Color.parseColor("#00E5FF") else Color.parseColor("#55FFFFFF"))
            }
        }

        if (!animate) return

        // emoji pops in, title and text slide in from the right one after another
        emoji.alpha = 0f
        emoji.scaleX = 0.4f
        emoji.scaleY = 0.4f
        emoji.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(0)
            .setDuration(450)
            .setInterpolator(OvershootInterpolator(2.2f))
            .start()

        slideIn(title, 80)
        slideIn(desc, 160)
    }

    private fun slideIn(view: View, delay: Long) {
        view.alpha = 0f
        view.translationX = dp(50).toFloat()
        view.animate()
            .alpha(1f).translationX(0f)
            .setStartDelay(delay)
            .setDuration(380)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
    }

    private fun finishTour() {
        GesturePrefs.setOnboardingDone(this, true)
        // when replayed from Settings we simply go back; the first time we continue into the app
        if (!intent.getBooleanExtra("replay", false)) {
            startActivity(Intent(this, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        finish()
    }
}