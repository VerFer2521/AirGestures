package com.project_by_adr.airgestures

import android.os.SystemClock
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Turns raw AI output (21 landmarks + a label) into stable, intentional actions.
 *
 *  1. classify()   decides the pose from finger geometry, using the AI label as a hint
 *  2. trackHold()  a pose must be held steady before it fires (and can repeat)
 *  3. trackSwipe() flicks of the pointing finger, in four directions
 *
 * All distances are in "hand sizes" (wrist to middle knuckle), so it works
 * the same near or far from the camera.
 */
class GestureEngine {

    enum class Action(val label: String) {
        VOLUME_UP("🔊  Volume up"),
        VOLUME_DOWN("🔉  Volume down"),
        FLASH_ON("🔦  Flashlight on"),
        FLASH_OFF("🌑  Flashlight off"),
        TORCH_TOGGLE("💡  Flashlight toggle"),
        VOICE("🎤  Voice command"),
        PLAY_PAUSE("⏯  Play / pause"),
        MUTE("🔇  Mute / unmute"),
        NEXT("⏭  Next track"),
        PREVIOUS("⏮  Previous track"),
        CAMERA("📷  Open camera"),
        INFO("🕐  Say time + battery"),
        HOME("🏠  Go home"),
        SCROLL_NEXT("⬆️  Next reel (scroll up)"),
        SCROLL_PREV("⬇️  Previous reel (scroll down)"),
        DOUBLE_TAP("❤️  Double tap screen (like)"),
        SCREENSHOT("📸  Screenshot"),
        BACK("◀️  Back"),
        RECENTS("🗂  Recent apps"),
        NOTIFICATIONS("🔔  Pull down notifications"),
        NONE("🚫  Do nothing")
    }

    /**
     * action     = something to do now (or null)
     * progress   = 0..1 for the hold ring
     * pose       = what we see right now
     * firedPose  = the pose whose hold just completed (or null), used by the game
     */
    data class Output(
        val action: Action?,
        val progress: Float,
        val pose: String,
        val firedPose: String?
    )

    companion object {
        /** What each gesture does until you change it in Settings. */
        val DEFAULT_MAPPING: Map<String, Action> = mapOf(
            "Open_Palm" to Action.VOLUME_UP,
            "Closed_Fist" to Action.VOLUME_DOWN,
            "Thumb_Up" to Action.FLASH_ON,
            "Thumb_Down" to Action.FLASH_OFF,
            "Victory" to Action.VOICE,
            "ILoveYou" to Action.PLAY_PAUSE,
            "Pinch" to Action.MUTE,
            "Three" to Action.DOUBLE_TAP,
            "L_Up" to Action.SCROLL_NEXT,
            "L_Down" to Action.SCROLL_PREV
        )
    }

    // ---- set from the Settings screen ----
    var holdMs = 400L       // how long a pose must stay steady before it fires
    var repeatMs = 900L     // time between volume steps while you keep holding
    var mapping: Map<String, Action> = DEFAULT_MAPPING

    // ---- fixed tuning (change only if you know why) ----
    private val minScore = 0.6f          // ignore AI guesses below 60% confidence
    private val graceMs = 180L           // a pose may flicker away this long without resetting the hold
    private val extendedRatio = 1.5f     // finger counts as "straight" if tip is 1.5x further from wrist than its knuckle
    private val pinchEnter = 0.25f       // thumb-index gap (in hand sizes) to START a pinch
    private val pinchExit = 0.40f        // gap needed to END a pinch (hysteresis)
    private val swipeRatio = 1.3f        // sideways / upward flick distance, in hand sizes
    private val swipeDownRatio = 1.8f    // flick down must be bigger so lowering your hand doesn't count
    private val thumbOutRatio = 0.65f    // thumb tip this far from the index knuckle (in hand sizes) = thumb is sticking out
    private val lRise = 0.8f             // index must point clearly up (or down) for the L sign
    private val lRepeatMs = 1500L        // while you hold the L sign, scroll again every 1.5 s
    private val swipeWindowMs = 350L
    private val swipeCooldownMs = 1000L

    private val repeatable = setOf("Open_Palm", "Closed_Fist", "L_Up", "L_Down")

    // hold state
    private var candidate = ""
    private var candidateSince = 0L
    private var candidateSeen = 0L
    private var lastFire = 0L
    private var fired = false
    private var pinching = false

    // what the engine "sees", shown in practice mode
    var debugInfo: String = "no hand"
        private set
    private var dbgUp = BooleanArray(4)
    private var dbgGap = 0f
    private var dbgThumbOut = false

    // swipe state
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()
    private var lastPointingTime = 0L
    private var lastSwipeTime = 0L

    fun reset() {
        candidate = ""
        fired = false
        pinching = false
        trail.clear()
    }

    fun update(landmarks: List<NormalizedLandmark>?, label: String?, score: Float): Output {
        val now = SystemClock.elapsedRealtime()
        val valid = landmarks != null && landmarks.size >= 21

        val pose = if (valid) classify(landmarks!!, label, score) else "None"
        if (!valid) pinching = false
        debugInfo = if (valid) buildDebug(label, score) else "no hand"

        val swipe = if (valid) trackSwipe(landmarks!!, label, score, now) else {
            trail.clear()
            null
        }
        val hold = trackHold(pose, now)
        return Output(swipe ?: hold.first, hold.second, pose, hold.third)
    }

    // ------------------------------------------------------------ 1. pose

    private fun classify(lm: List<NormalizedLandmark>, label: String?, score: Float): String {
        val size = dist(lm[0], lm[9]).coerceAtLeast(0.01f)

        // is each of index, middle, ring, pinky straight?
        val tips = intArrayOf(8, 12, 16, 20)
        val knuckles = intArrayOf(5, 9, 13, 17)
        val up = BooleanArray(4) {
            dist(lm[tips[it]], lm[0]) / dist(lm[knuckles[it]], lm[0]).coerceAtLeast(0.01f) > extendedRatio
        }
        val upCount = up.count { it }
        val othersUp = (if (up[1]) 1 else 0) + (if (up[2]) 1 else 0) + (if (up[3]) 1 else 0)

        // PINCH first: thumb touches index AND at least two other fingers are up.
        // A fist has no fingers up, so it can never be a pinch.
        val gap = dist(lm[4], lm[8]) / size
        val thumbOut = dist(lm[4], lm[5]) / size > thumbOutRatio
        dbgUp = up
        dbgGap = gap
        dbgThumbOut = thumbOut
        pinching = gap < (if (pinching) pinchExit else pinchEnter) && othersUp >= 2
        if (pinching) return "Pinch"

        // THREE fingers: index + middle + ring straight, pinky curled
        if (up[0] && up[1] && up[2] && !up[3]) return "Three"

        // L SIGN / "gun": index straight, other three curled, thumb sticking out.
        // Index pointing up = L_Up, pointing down (hand flipped) = L_Down.
        if (up[0] && !up[1] && !up[2] && !up[3] && thumbOut) {
            val rise = (lm[0].y() - lm[8].y()) / size   // positive = fingertip above the wrist
            if (rise > lRise) return "L_Up"
            if (rise < -lRise) return "L_Down"
        }

        // everything else: trust the AI label, but only if the finger shape agrees
        val name = if (label != null && score >= minScore) label else "None"
        return when (name) {
            "Open_Palm" -> if (upCount >= 3) name else "None"
            "Closed_Fist" -> if (upCount <= 1) name else "None"
            else -> name
        }
    }

    /** One short line for practice mode: finger states, thumb, pinch gap, and the AI's own guess. */
    private fun buildDebug(label: String?, score: Float): String {
        fun f(up: Boolean) = if (up) "✓" else "✗"
        val fingers = "I${f(dbgUp[0])} M${f(dbgUp[1])} R${f(dbgUp[2])} P${f(dbgUp[3])}"
        val thumb = if (dbgThumbOut) "thumb out" else "thumb in"
        val gap = "gap %.2f".format(dbgGap)
        val ai = if (label == null) "AI: -" else "$label ${(score * 100).toInt()}%"
        return "$fingers · $thumb · $gap · $ai"
    }

    // ------------------------------------------------------------ 2. hold

    private fun trackHold(pose: String, now: Long): Triple<Action?, Float, String?> {
        val actionable = pose in mapping

        if (actionable && pose == candidate) {
            candidateSeen = now
        } else if (candidate.isEmpty() || now - candidateSeen > graceMs) {
            // nothing being tracked, or it was gone longer than a flicker: start fresh
            if (actionable) {
                candidate = pose
                candidateSince = now
                candidateSeen = now
                fired = false
                lastFire = 0L
            } else {
                candidate = ""
                fired = false
            }
        }
        // else: a brief flicker of some other pose, keep waiting on the current one

        if (candidate.isEmpty()) return Triple(null, 0f, null)
        val action = mapping[candidate] ?: Action.NONE

        if (!fired) {
            if (now - candidateSince >= holdMs) {
                fired = true
                lastFire = now
                return Triple(action, 1f, candidate)
            }
            return Triple(null, ((now - candidateSince).toFloat() / holdMs).coerceIn(0f, 1f), null)
        }
        if (candidate in repeatable) {
            val gap = if (candidate.startsWith("L_")) maxOf(repeatMs, lRepeatMs) else repeatMs
            if (now - lastFire >= gap) {
                lastFire = now
                return Triple(action, 1f, candidate)
            }
            return Triple(null, ((now - lastFire).toFloat() / gap).coerceIn(0f, 1f), null)
        }
        return Triple(null, 0f, null)   // one-shot already done: lower your hand to re-arm
    }

    // ----------------------------------------------------------- 3. swipes

    private fun trackSwipe(lm: List<NormalizedLandmark>, label: String?, score: Float, now: Long): Action? {
        if (label == "Pointing_Up" && score >= minScore) lastPointingTime = now
        if (now - lastPointingTime > 500) {
            trail.clear()
            return null
        }
        val size = dist(lm[0], lm[9]).coerceAtLeast(0.01f)
        val x = 1f - lm[8].x()   // mirrored so "right" means YOUR right
        val y = lm[8].y()        // grows downward
        trail.addLast(Triple(now, x, y))
        while (trail.size > 1 && now - trail.first().first > swipeWindowMs) trail.removeFirst()
        if (now - lastSwipeTime <= swipeCooldownMs) return null

        val dx = (x - trail.first().second) / size
        val dy = (y - trail.first().third) / size
        val result = when {
            abs(dx) > swipeRatio && abs(dx) > 1.5f * abs(dy) ->
                if (dx > 0) Action.NEXT else Action.PREVIOUS
            dy < -swipeRatio && abs(dy) > 1.5f * abs(dx) -> Action.INFO
            dy > swipeDownRatio && abs(dy) > 1.5f * abs(dx) -> Action.HOME
            else -> null
        }
        if (result != null) {
            lastSwipeTime = now
            trail.clear()
        }
        return result
    }

    private fun dist(a: NormalizedLandmark, b: NormalizedLandmark): Float {
        val dx = a.x() - b.x()
        val dy = a.y() - b.y()
        return sqrt(dx * dx + dy * dy)
    }
}