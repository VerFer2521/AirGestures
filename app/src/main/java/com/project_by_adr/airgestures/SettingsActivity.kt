package com.project_by_adr.airgestures

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

class SettingsActivity : AppCompatActivity() {

    private val holdMin = 200      // slider 0..700   => 200..900 ms
    private val repeatMin = 500    // slider 0..1500  => 500..2000 ms

    private lateinit var holdSeek: SeekBar
    private lateinit var repeatSeek: SeekBar
    private lateinit var holdValue: TextView
    private lateinit var repeatValue: TextView

    // internal pose name, emoji, title
    private val mappable = listOf(
        Triple("Open_Palm", "✋", "Open palm"),
        Triple("Closed_Fist", "✊", "Fist"),
        Triple("Thumb_Up", "👍", "Thumbs up"),
        Triple("Thumb_Down", "👎", "Thumbs down"),
        Triple("Victory", "✌️", "Victory"),
        Triple("ILoveYou", "🤟", "I love you"),
        Triple("Pinch", "👌", "Pinch"),
        Triple("Three", "3️⃣", "Three fingers"),
        Triple("L_Up", "🔫", "L sign (up)"),
        Triple("L_Down", "🔫", "L sign (flipped down)"),
        Triple("L_Left", "⬅️", "L sign (pointing left)"),
        Triple("L_Right", "➡️", "L sign (pointing right)")
    )

    private var scrollStatus: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        // ---- sliders ----
        holdSeek = findViewById(R.id.holdSeek)
        repeatSeek = findViewById(R.id.repeatSeek)
        holdValue = findViewById(R.id.holdValue)
        repeatValue = findViewById(R.id.repeatValue)

        holdSeek.progress = GesturePrefs.holdMs(this) - holdMin
        repeatSeek.progress = GesturePrefs.repeatMs(this) - repeatMin
        holdValue.text = "${GesturePrefs.holdMs(this)} ms"
        repeatValue.text = "${GesturePrefs.repeatMs(this)} ms"

        wire(holdSeek, holdValue, holdMin) { GesturePrefs.setHoldMs(this, it) }
        wire(repeatSeek, repeatValue, repeatMin) { GesturePrefs.setRepeatMs(this, it) }

        findViewById<Button>(R.id.resetButton).setOnClickListener {
            GesturePrefs.setHoldMs(this, GesturePrefs.DEFAULT_HOLD_MS)
            GesturePrefs.setRepeatMs(this, GesturePrefs.DEFAULT_REPEAT_MS)
            holdSeek.progress = GesturePrefs.DEFAULT_HOLD_MS - holdMin
            repeatSeek.progress = GesturePrefs.DEFAULT_REPEAT_MS - repeatMin
            holdValue.text = "${GesturePrefs.DEFAULT_HOLD_MS} ms"
            repeatValue.text = "${GesturePrefs.DEFAULT_REPEAT_MS} ms"
        }

        // ---- gesture mapping ----
        buildMappingRows()
        findViewById<Button>(R.id.resetMapButton).setOnClickListener {
            GesturePrefs.resetMapping(this)
            recreate()   // rebuilds the screen with the defaults showing
        }

        // ---- stats ----
        showStats()
        findViewById<Button>(R.id.resetStatsButton).setOnClickListener {
            ActionStats.reset(this)
            showStats()
        }
    }

    private fun showStats() {
        findViewById<TextView>(R.id.statsTotal).text = "${ActionStats.total(this)} actions performed"
        findViewById<TextView>(R.id.statsBest).text = "🏆 Best game score: ${GesturePrefs.bestScore(this)}"
        val recent = ActionStats.recent()
        findViewById<TextView>(R.id.statsRecent).text =
            if (recent.isEmpty()) "Nothing yet this session" else recent.joinToString("\n")
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildMappingRows() {
        val container = findViewById<LinearLayout>(R.id.mappingList)
        val actions = GestureEngine.Action.values().filter { it.shown }.toTypedArray()
        val names = actions.map { it.label }

        for ((pose, emoji, title) in mappable) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            val label = TextView(this).apply {
                text = "$emoji  $title"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            // white text so it is readable on the dark background
            val adapter = object : ArrayAdapter<String>(
                this, android.R.layout.simple_spinner_item, names
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getView(position, convertView, parent) as TextView
                    v.setTextColor(Color.WHITE)
                    return v
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val v = super.getDropDownView(position, convertView, parent) as TextView
                    v.setTextColor(Color.WHITE)
                    v.setBackgroundColor(Color.parseColor("#1B2430"))
                    v.setPadding(dp(14), dp(12), dp(14), dp(12))
                    return v
                }
            }

            val spinner = Spinner(this).apply {
                this.adapter = adapter
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f)
                setSelection(actions.indexOf(GesturePrefs.actionFor(this@SettingsActivity, pose)))
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        GesturePrefs.setAction(this@SettingsActivity, pose, actions[position])
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }

            row.addView(label)
            row.addView(spinner)
            container.addView(row)
        }
        addScrollSetup(container)
        addExtras(container)
    }

    /** Sound switch and a button to replay the intro tour. */
    private fun addExtras(container: LinearLayout) {
        val sound = SwitchCompat(this).apply {
            text = "🔔  Sound when an action happens"
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_primary))
            textSize = 14f
            isChecked = GesturePrefs.soundOn(this@SettingsActivity)
            setPadding(0, dp(16), 0, dp(4))
            setOnCheckedChangeListener { _, checked ->
                GesturePrefs.setSoundOn(this@SettingsActivity, checked)
            }
        }

        val tour = Button(this).apply {
            text = "Replay intro tour"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.neon_cyan))
            setBackgroundResource(R.drawable.bg_card)
            stateListAnimator = null
            setOnClickListener {
                startActivity(
                    Intent(this@SettingsActivity, OnboardingActivity::class.java)
                        .putExtra("replay", true)
                )
            }
        }

        container.addView(sound)
        container.addView(
            tour,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
    }

    /** Button + status line for turning on the reel-scrolling service. */
    private fun addScrollSetup(container: LinearLayout) {
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.text_muted))
            setPadding(0, dp(12), 0, dp(4))
        }
        scrollStatus = status

        val button = Button(this).apply {
            text = "Turn on reel scrolling"
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@SettingsActivity, R.color.neon_cyan))
            setBackgroundResource(R.drawable.bg_card)
            stateListAnimator = null
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        container.addView(status)
        container.addView(button)
        refreshScrollStatus()
    }

    private fun refreshScrollStatus() {
        scrollStatus?.text =
            if (AirGestureAccessibilityService.isRunning()) "✅  Reel scrolling is ON"
            else "Reel scrolling is OFF. Tap the button, open Installed apps, choose Air Gestures and switch it on."
    }

    override fun onResume() {
        super.onResume()
        refreshScrollStatus()
    }

    private fun wire(bar: SeekBar, label: TextView, min: Int, save: (Int) -> Unit) {
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "${min + progress} ms"
                if (fromUser) save(min + progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }
}