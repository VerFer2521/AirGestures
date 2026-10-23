package com.project_by_adr.airgestures

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Voice Autopilot: understands commands that need several steps inside
 * another app, e.g. "message Mom saying I'm late" on WhatsApp.
 *
 * It never sends a message by itself. It types it, then waits for you to say "send".
 */
object Autopilot {

    private val handler = Handler(Looper.getMainLooper())

    /** Bumped every time a new command starts, so an old unfinished one stops. */
    private var generation = 0

    private class Step(
        val name: String,
        val waitMs: Long,
        val tries: Int,
        val run: (Int) -> Boolean
    )

    private val messageRegex = Regex(
        "^(?:whatsapp|message|text|msg)\\s+(.+?)\\s+(?:saying|say|that says|that)\\s+(.+)$"
    )
    private val searchOnRegex = Regex(
        "^(?:search|find|look up)\\s+(?:for\\s+)?(.+?)\\s+on\\s+(.+)$"
    )
    private val searchHereRegex = Regex("^(?:search|look up)\\s+(?:for\\s+)?(.+)$")

    private val sendWords = setOf("send", "send it", "send message", "send now", "post it", "post now")

    /** Returns the message to show, or null if this is not an autopilot command. */
    fun handle(context: Context, spoken: String): String? {
        val text = spoken.lowercase().trim().removeSuffix(".")
        val app = context.applicationContext

        if (text == "cancel" || text == "stop autopilot") {
            generation++
            handler.removeCallbacksAndMessages(null)
            return "🛑  Autopilot stopped"
        }

        val needsService = needsService(text)
        if (!needsService) return null
        if (!AirGestureAccessibilityService.isRunning()) return "⚠️  Turn on scrolling in Settings first"

        if (text == "go back" || text == "back") {
            return if (AirGestureAccessibilityService.back()) "◀️  Back" else "⚠️  Couldn't go back"
        }

        if (text in sendWords) {
            val ok = AirGestureAccessibilityService.clickLabel("send", "post", "reply", "tweet")
            return if (ok) "📨  Sent" else "🤷  No send button on screen"
        }

        if (text.startsWith("scroll ")) {
            val down = !text.contains("up")
            AirGestureAccessibilityService.swipe(down)
            return if (down) "⬇️  Scrolling down" else "⬆️  Scrolling up"
        }

        if (text.startsWith("type ") || text.startsWith("write ")) {
            val words = text.substringAfter(" ").trim()
            return if (AirGestureAccessibilityService.typeText(words, true)) "⌨️  Typed"
            else "🤷  No text box found. Tap one first"
        }

        if (text.startsWith("click ") || text.startsWith("tap ") || text.startsWith("press ")) {
            val label = text.substringAfter(" ").trim()
            return if (AirGestureAccessibilityService.clickLabel(label)) "👆  Clicked $label"
            else "🤷  Can't see \"$label\""
        }

        messageRegex.find(text)?.let { match ->
            val name = match.groupValues[1].replace(" on whatsapp", "").trim()
            val message = match.groupValues[2].trim()
            return messageOnWhatsApp(app, name, message)
        }

        searchOnRegex.find(text)?.let { match ->
            return searchInApp(app, match.groupValues[2].trim(), match.groupValues[1].trim())
        }

        searchHereRegex.find(text)?.let { match ->
            val query = match.groupValues[1].trim()
            val gen = ++generation
            runSteps(app, gen, searchSteps(query), 0, "Searched for $query")
            return "🔎  Searching $query…"
        }

        return null
    }

    private fun needsService(text: String): Boolean =
        text == "go back" || text == "back" || text in sendWords ||
                text.startsWith("scroll ") || text.startsWith("type ") ||
                text.startsWith("write ") || text.startsWith("click ") ||
                text.startsWith("tap ") || text.startsWith("press ") ||
                messageRegex.containsMatchIn(text) ||
                searchOnRegex.containsMatchIn(text) || searchHereRegex.containsMatchIn(text)

    // ------------------------------------------------------------ macros

    private fun messageOnWhatsApp(context: Context, name: String, message: String): String {
        val pm = context.packageManager
        val launch = pm.getLaunchIntentForPackage("com.whatsapp")
            ?: pm.getLaunchIntentForPackage("com.whatsapp.w4b")
            ?: return "🤷  WhatsApp isn't installed"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)

        val gen = ++generation
        val steps = listOf(
            Step("find WhatsApp's search", 1500, 8) { i ->
                // if we landed inside a chat, go back once and look again
                if (i == 3) AirGestureAccessibilityService.back()
                AirGestureAccessibilityService.clickLabel("search")
            },
            Step("type the name", 700, 6) { AirGestureAccessibilityService.typeText(name, false) },
            Step("open the chat with $name", 1300, 8) { AirGestureAccessibilityService.clickLabel(name) },
            Step("type the message", 900, 8) { AirGestureAccessibilityService.typeText(message, false) }
        )
        runSteps(context, gen, steps, 0, "Typed for $name. Say send to send it.")
        return "🤖  Messaging $name…"
    }

    private fun searchInApp(context: Context, appName: String, query: String): String {
        val candidates = if (appName == "twitter" || appName == "x") listOf("twitter", "x") else listOf(appName)
        var opened: String? = null
        for (candidate in candidates) {
            opened = PhoneActions.openAppByName(context, candidate)
            if (opened != null) break
        }
        if (opened == null) return "🤷  Couldn't find $appName"

        val gen = ++generation
        runSteps(context, gen, searchSteps(query), 0, "Searched $opened for $query", firstWait = 1600)
        return "🤖  Searching $opened…"
    }

    private fun searchSteps(query: String): List<Step> = listOf(
        Step("find the search button", 400, 8) { AirGestureAccessibilityService.clickLabel("search") },
        Step("open the search box", 800, 4) {
            AirGestureAccessibilityService.hasTextBox() || AirGestureAccessibilityService.clickLabel("search")
        },
        Step("type the search", 600, 6) { AirGestureAccessibilityService.typeText(query, false) },
        Step("press search", 500, 2) { AirGestureAccessibilityService.submit() }
    )

    // ---------------------------------------------------------- the runner

    private fun runSteps(
        context: Context,
        gen: Int,
        steps: List<Step>,
        index: Int,
        doneMessage: String,
        firstWait: Long = 0
    ) {
        if (gen != generation) return
        if (index >= steps.size) {
            say(context, doneMessage)
            return
        }
        val step = steps[index]
        val wait = if (index == 0 && firstWait > 0) firstWait else step.waitMs

        handler.postDelayed({
            tryStep(gen, step, 0) { ok ->
                if (gen != generation) return@tryStep
                if (ok) runSteps(context, gen, steps, index + 1, doneMessage)
                else say(context, "Autopilot stopped. I couldn't ${step.name}.")
            }
        }, wait)
    }

    private fun tryStep(gen: Int, step: Step, attempt: Int, finished: (Boolean) -> Unit) {
        if (gen != generation) return
        if (step.run(attempt)) {
            finished(true)
        } else if (attempt + 1 >= step.tries) {
            finished(false)
        } else {
            handler.postDelayed({ tryStep(gen, step, attempt + 1, finished) }, 500)
        }
    }

    private fun say(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        Speaker.speak(context, message)
    }
}