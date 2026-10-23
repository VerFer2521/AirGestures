package com.project_by_adr.airgestures

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

object PhoneActions {

    private const val TAG = "PhoneActions"

    private var torchOn = false

    private val jokes = listOf(
        "Why do programmers prefer dark mode? Because light attracts bugs.",
        "There are only 10 kinds of people: those who understand binary, and those who don't.",
        "A SQL query walks into a bar, walks up to two tables and asks: can I join you?",
        "I would tell you a UDP joke, but you might not get it.",
        "Why was the phone wearing glasses? It lost its contacts.",
        "My code works, and I have no idea why. My code doesn't work, and I have no idea why."
    )

    /**
     * Runs the phone action for a gesture and returns the text to show on screen.
     * Returns null when there is nothing to show. VOICE is handled by the caller
     * (the app and the background service listen in different ways).
     */
    fun perform(context: Context, action: GestureEngine.Action): String? {
        val message: String? = when (action) {
            GestureEngine.Action.VOLUME_UP -> {
                adjustVolume(context, true)
                "🔊  Volume up"
            }
            GestureEngine.Action.VOLUME_DOWN -> {
                adjustVolume(context, false)
                "🔉  Volume down"
            }
            GestureEngine.Action.FLASH_ON -> {
                setFlashlight(context, true)
                "🔦  Flashlight on"
            }
            GestureEngine.Action.FLASH_OFF -> {
                setFlashlight(context, false)
                "🌑  Flashlight off"
            }
            GestureEngine.Action.TORCH_TOGGLE -> {
                setFlashlight(context, !torchOn)
                if (torchOn) "🔦  Flashlight on" else "🌑  Flashlight off"
            }
            GestureEngine.Action.PLAY_PAUSE -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "⏯  Play / pause"
            }
            GestureEngine.Action.MUTE -> {
                toggleMute(context)
                "🔇  Mute / unmute"
            }
            GestureEngine.Action.NEXT -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                "⏭  Next track"
            }
            GestureEngine.Action.PREVIOUS -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "⏮  Previous track"
            }
            GestureEngine.Action.CAMERA ->
                if (openCamera(context)) "📷  Opening camera" else "📷  No camera app found"
            GestureEngine.Action.INFO -> {
                Speaker.speak(context, spokenStatus(context))
                shortStatus(context)
            }
            GestureEngine.Action.HOME -> {
                goHome(context)
                "🏠  Going home"
            }
            GestureEngine.Action.SCROLL_NEXT -> scrollReels(true)
            GestureEngine.Action.SCROLL_PREV -> scrollReels(false)
            GestureEngine.Action.DOUBLE_TAP,
            GestureEngine.Action.SCREENSHOT,
            GestureEngine.Action.BACK,
            GestureEngine.Action.RECENTS,
            GestureEngine.Action.NOTIFICATIONS -> accessibilityAction(action)
            GestureEngine.Action.VOICE -> null
            GestureEngine.Action.NONE -> null
        }
        if (message != null) {
            ActionStats.record(context, message)
            SoundFx.tick(context)
        }
        return message
    }

    fun adjustVolume(context: Context, raise: Boolean) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun toggleMute(context: Context) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_TOGGLE_MUTE,
            AudioManager.FLAG_SHOW_UI
        )
    }

    fun setFlashlight(context: Context, on: Boolean) {
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            // pick the back camera that actually has a flash
            val id = cm.cameraIdList.firstOrNull { cameraId ->
                val c = cm.getCameraCharacteristics(cameraId)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return
            cm.setTorchMode(id, on)
            torchOn = on
        } catch (e: Exception) {
            Log.e(TAG, "Flashlight error", e)
        }
    }

    /** Fun: the flashlight strobes for about 3 seconds. */
    fun partyMode(context: Context) {
        val handler = Handler(Looper.getMainLooper())
        for (i in 0 until 12) {
            handler.postDelayed({ setFlashlight(context, i % 2 == 0) }, i * 250L)
        }
        handler.postDelayed({ setFlashlight(context, false) }, 12 * 250L)
    }

    fun sendMediaKey(context: Context, keyCode: Int) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0))
    }

    fun goHome(context: Context) {
        val home = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(home)
    }

    fun openCamera(context: Context): Boolean {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera launch error", e)
            false
        }
    }

    /** Double tap, screenshot, back, recents, notifications (need the accessibility service). */
    fun accessibilityAction(action: GestureEngine.Action): String {
        val worked = when (action) {
            GestureEngine.Action.DOUBLE_TAP -> AirGestureAccessibilityService.doubleTap()
            GestureEngine.Action.SCREENSHOT -> AirGestureAccessibilityService.screenshot()
            GestureEngine.Action.BACK -> AirGestureAccessibilityService.back()
            GestureEngine.Action.RECENTS -> AirGestureAccessibilityService.recents()
            GestureEngine.Action.NOTIFICATIONS -> AirGestureAccessibilityService.notifications()
            else -> false
        }
        if (!worked) return "⚠️  Turn on scrolling"
        return when (action) {
            GestureEngine.Action.DOUBLE_TAP -> "❤️  Double tap"
            GestureEngine.Action.SCREENSHOT -> "📸  Screenshot"
            GestureEngine.Action.BACK -> "◀️  Back"
            GestureEngine.Action.RECENTS -> "🗂  Recent apps"
            GestureEngine.Action.NOTIFICATIONS -> "🔔  Notifications"
            else -> ""
        }
    }
    fun scrollReels(next: Boolean): String {
        val worked = AirGestureAccessibilityService.swipe(next)
        return when {
            !worked -> "⚠️  Turn on scrolling"
            next -> "⬆️  Next reel"
            else -> "⬇️  Previous reel"
        }
    }

    /** Returns false if the Lock feature (device admin) hasn't been enabled yet. */
    fun lockDevice(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(admin)) return false
        dpm.lockNow()
        return true
    }

    // ---------------------------------------------------- time + battery

    private fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun timeText(): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

    /** Short enough for the small floating box, e.g. "10:42 PM · 78%" */
    fun shortStatus(context: Context): String = "${timeText()} · ${batteryPercent(context)}%"

    fun spokenStatus(context: Context): String =
        "It is ${timeText()}. Battery is at ${batteryPercent(context)} percent."

    // ------------------------------------------------------- open apps

    private fun normalize(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Finds an installed app by its name ("spotify", "you tube", "camera")
     * and launches it. Returns the app's real name, or null if not found.
     */
    fun openAppByName(context: Context, spokenName: String): String? {
        val target = normalize(spokenName)
        if (target.length < 2) return null

        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)

        val match = apps.firstOrNull { normalize(it.loadLabel(pm).toString()) == target }
            ?: apps.firstOrNull { normalize(it.loadLabel(pm).toString()).contains(target) }
            ?: return null

        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return match.loadLabel(pm).toString()
    }

    /**
     * Runs a spoken command. Returns the message to show the user,
     * or null if the command wasn't understood.
     */
    fun runVoiceCommand(context: Context, spoken: String): String? {
        val result = handleVoice(context, spoken)
        if (result != null) ActionStats.record(context, result)
        return result
    }

    private fun handleVoice(context: Context, spoken: String): String? {
        val text = spoken.lowercase().trim()

        // multi-step voice jobs (message, search, click, type...)
        Autopilot.handle(context, spoken)?.let { return it }

        if (text.startsWith("open ") || text.startsWith("launch ")) {
            val name = text.substringAfter(" ")
            val opened = openAppByName(context, name)
            return if (opened != null) "🚀  Opening $opened" else "🤷  Couldn't find $name"
        }

        return when {
            text.contains("lock") &&
                    (text.contains("phone") || text.contains("device") || text.contains("screen")) ->
                if (lockDevice(context)) "🔒  Locking…" else "🔒  Tap the lock button once to enable"

            text.contains("battery") || text.contains("what time") || text.contains("the time") -> {
                Speaker.speak(context, spokenStatus(context))
                shortStatus(context)
            }

            text.contains("joke") -> {
                Speaker.speak(context, jokes.random())
                "😄  Joke time"
            }

            text.contains("coin") -> {
                val heads = Random.nextBoolean()
                Speaker.speak(context, if (heads) "Heads" else "Tails")
                if (heads) "🪙  Heads" else "🪙  Tails"
            }

            text.contains("dice") || text.contains("roll") -> {
                val n = Random.nextInt(1, 7)
                Speaker.speak(context, "You rolled a $n")
                "🎲  You rolled a $n"
            }

            text.contains("party") || text.contains("disco") -> {
                partyMode(context)
                "🎉  Party mode!"
            }

            text.contains("home") -> {
                goHome(context)
                "🏠  Going home"
            }

            text.contains("flashlight") || text.contains("torch") -> {
                val on = !text.contains("off")
                setFlashlight(context, on)
                if (on) "🔦  Flashlight on" else "🌑  Flashlight off"
            }

            text.contains("mute") -> {
                toggleMute(context)
                "🔇  Mute toggled"
            }

            text.contains("louder") || (text.contains("volume") && text.contains("up")) -> {
                adjustVolume(context, true)
                "🔊  Volume up"
            }

            text.contains("quieter") ||
                    (text.contains("volume") && (text.contains("down") || text.contains("lower"))) -> {
                adjustVolume(context, false)
                "🔉  Volume down"
            }

            text.contains("next") -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
                "⏭  Next track"
            }

            text.contains("previous") -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                "⏮  Previous track"
            }

            text.contains("pause") || text.contains("play") || text.contains("resume") -> {
                sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                "⏯  Play / pause"
            }

            else -> null
        }
    }
}