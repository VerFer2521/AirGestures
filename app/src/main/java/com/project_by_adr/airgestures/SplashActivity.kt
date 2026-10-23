package com.project_by_adr.airgestures

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val hand = findViewById<SplashHandView>(R.id.splashHand)
        val title = findViewById<TextView>(R.id.splashTitle)
        val line = findViewById<View>(R.id.splashLine)
        val tagline = findViewById<TextView>(R.id.splashTagline)
        val credit = findViewById<TextView>(R.id.splashCredit)

        // 1. the neon hand draws itself (about 1.5 s)
        hand.start()

        // 2. title fades in while its letters glide together from wide spacing
        title.animate()
            .alpha(1f)
            .setStartDelay(700)
            .setDuration(900)
            .setInterpolator(DecelerateInterpolator())
            .start()
        ValueAnimator.ofFloat(0.3f, 0.12f).apply {
            startDelay = 700
            duration = 1100
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { title.letterSpacing = it.animatedValue as Float }
            start()
        }

        // 3. magenta line grows out from the middle
        line.animate()
            .scaleX(1f)
            .setStartDelay(1300)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()

        // 4. tagline fades in
        tagline.animate()
            .alpha(1f)
            .setStartDelay(1700)
            .setDuration(700)
            .start()

        // 5. credit fades in, then pulses softly
        credit.animate()
            .alpha(1f)
            .setStartDelay(2000)
            .setDuration(600)
            .withEndAction {
                ObjectAnimator.ofFloat(credit, "alpha", 1f, 0.4f, 1f).apply {
                    duration = 1600
                    repeatCount = ObjectAnimator.INFINITE
                    start()
                }
            }
            .start()

        // 6. crossfade into the app
        handler.postDelayed({
            val destination: Class<*> =
                if (GesturePrefs.onboardingDone(this)) MainActivity::class.java
                else OnboardingActivity::class.java
            startActivity(Intent(this, destination))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, 3700)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}