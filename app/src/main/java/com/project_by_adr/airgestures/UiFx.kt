package com.project_by_adr.airgestures

import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

object UiFx {

    /** Buttons squish when pressed and spring back when released. The click still works. */
    fun pressEffect(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN ->
                        view.animate()
                            .scaleX(0.86f).scaleY(0.86f)
                            .setStartDelay(0)
                            .setDuration(90)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate()
                            .scaleX(1f).scaleY(1f)
                            .setStartDelay(0)
                            .setDuration(280)
                            .setInterpolator(OvershootInterpolator(3f))
                            .start()
                }
                false   // false = let the normal click happen too
            }
        }
    }

    /** Home screen start-up: status area drops in, bar slides up, buttons pop in one by one. */
    fun entrance(hud: View, bar: View, buttons: List<View>) {
        hud.alpha = 0f
        hud.translationY = -70f
        hud.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(100)
            .setDuration(500)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        bar.alpha = 0f
        bar.translationY = 400f
        bar.animate()
            .alpha(1f).translationY(0f)
            .setStartDelay(150)
            .setDuration(550)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()

        buttons.forEachIndexed { index, button ->
            button.scaleX = 0f
            button.scaleY = 0f
            button.animate()
                .scaleX(1f).scaleY(1f)
                .setStartDelay(450L + index * 70L)
                .setDuration(380)
                .setInterpolator(OvershootInterpolator(2.4f))
                .start()
        }
    }

    /** Opens another screen with a soft crossfade. */
    fun open(activity: Activity, intent: Intent) {
        activity.startActivity(intent)
        @Suppress("DEPRECATION")
        activity.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}