package com.project_by_adr.airgestures

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class GuideActivity : AppCompatActivity() {

    private data class GuideItem(val emoji: String, val title: String, val desc: String)

    private val gestures = listOf(
        GuideItem("✋", "Open palm", "Does nothing by default, because it is the pose your hand makes when you raise it. You can give it an action in Settings"),
        GuideItem("✊", "Fist", "Hold for half a second: go back (like the Back button)"),
        GuideItem("👍", "Thumbs up", "Flashlight on"),
        GuideItem("👎", "Thumbs down", "Flashlight off"),
        GuideItem("✌️", "Victory", "Starts listening for a voice command"),
        GuideItem("🤟", "I love you", "Play / pause your music or video"),
        GuideItem("👌", "Pinch", "Touch thumb and index fingertips, keep the other three fingers up and hold still: mute / unmute"),
        GuideItem("🎚", "Pinch + drag = volume", "Pinch, then move your whole hand up for volume up or down for volume down. Move further for a bigger change"),
        GuideItem("3️⃣", "Three fingers", "Index, middle and ring finger up: double tap the screen (likes a reel on Instagram)"),
        GuideItem("🔫", "L sign (gun)", "Index up, thumb out, other fingers curled: next reel. Hold to keep scrolling"),
        GuideItem("🔫", "L sign flipped", "Same shape with your fingertip pointing down: previous reel"),
        GuideItem("⬅️", "L sign pointing left", "Index pointing to your left: swipes the screen left (next photo, next story, next page). Hold to repeat"),
        GuideItem("➡️", "L sign pointing right", "Index pointing to your right: swipes the screen right (previous photo, previous page)"),
        GuideItem("👆", "Point + flick sideways", "Point up, then flick right for next track, left for previous"),
        GuideItem("⬆️", "Point + flick up", "The phone tells you the time and battery out loud"),
        GuideItem("⬇️", "Point + big flick down", "Go to the home screen"),
        GuideItem("🎛", "Make it yours", "Open ⚙ Settings to change what each gesture does")
    )

    private val voiceCommands = listOf(
        GuideItem("🚀", "\"Open Spotify\"", "Say open + any app name. It searches your installed apps"),
        GuideItem("🔊", "\"Volume up\" / \"Volume down\"", "Also: louder, quieter, mute"),
        GuideItem("⏯", "\"Pause\" / \"Next song\"", "Controls whatever music or video is playing"),
        GuideItem("🔦", "\"Flashlight on\" / \"off\"", "Torch control"),
        GuideItem("🕐", "\"What time is it\" / \"Battery\"", "Speaks the time and battery level"),
        GuideItem("😄", "\"Tell me a joke\"", "The phone tells a joke out loud"),
        GuideItem("🪙", "\"Flip a coin\" / \"Roll a dice\"", "Random result, spoken"),
        GuideItem("🎉", "\"Party mode\"", "The flashlight strobes for a few seconds"),
        GuideItem("🏠", "\"Go home\"", "Jumps to your home screen"),
        GuideItem("🔒", "\"Lock phone\"", "Needs the lock button enabled once in the app"),
        GuideItem("🤖", "\"Message Mom saying I'm late\"", "Opens WhatsApp, finds Mom, types it. Say \"send\" to send"),
        GuideItem("🔎", "\"Search lofi on YouTube\"", "Works for Instagram, X, Spotify and more"),
        GuideItem("👆", "\"Click Comment\" / \"Type nice\"", "Taps any button by its word and types into text boxes"),
        GuideItem("↔️", "\"Swipe left\" / \"Swipe right\"", "Also: next page, previous photo, next story. Swipes the screen sideways"),
        GuideItem("📨", "\"Send\" / \"Scroll down\" / \"Go back\"", "Finishing moves for autopilot jobs")
    )

    private val extras = listOf(
        GuideItem("🎮", "Gesture Challenge", "Tap 🎮 for a 30-second game. Make the gesture shown, score points, beat your best"),
        GuideItem("⏯", "Pause in the background", "In the notification, tap Pause / Resume to stop gestures without closing the app"),
        GuideItem("📊", "Stats", "Settings shows your total actions, best score and the latest actions")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)

        findViewById<Button>(R.id.backButton).setOnClickListener { finish() }

        var order = 0
        order = fillCards(findViewById<LinearLayout>(R.id.gestureList), gestures, order)
        order = fillCards(findViewById<LinearLayout>(R.id.voiceList), voiceCommands, order)
        fillCards(findViewById<LinearLayout>(R.id.extrasList), extras, order)

        findViewById<TextView>(R.id.howItWorks).text =
            "1.  The front camera streams live frames (CameraX)\n" +
                    "2.  MediaPipe finds 21 hand landmarks in every frame, fully on-device\n" +
                    "3.  A neural network names the gesture and gives a confidence score\n" +
                    "4.  Our own engine checks which fingers are straight, so a pinch is never mistaken for a fist\n" +
                    "5.  A stabiliser makes you hold a pose for a moment (the ring) so flickers and accidents are ignored\n" +
                    "6.  A mapping table turns the pose into an action you can change in Settings\n" +
                    "7.  The action layer does it: volume, torch, media keys, apps, speech\n\n" +
                    "Camera frames are processed on your phone and never uploaded."

        findViewById<TextView>(R.id.tipsText).text =
            "•  Good light on your hand matters more than anything else\n" +
                    "•  Keep the whole hand in frame, one hand at a time\n" +
                    "•  Hold a gesture until the ring fills (change the time in Settings)\n" +
                    "•  Lower your hand between one-shot gestures to re-arm them\n" +
                    "•  For the pinch, keep the other three fingers up like an OK sign\n" +
                    "•  Pinch and slide your hand up or down to change the volume smoothly\n" +
                    "•  For the flick: point up first, then flick quickly\n" +
                    "•  For the sideways swipe: make the L sign and point your index finger left or right, keeping it level\n" +
                    "•  Tap the small grey line under the gesture pill to see what the AI sees (practice mode)\n" +
                    "•  Tap the background to show or hide the camera picture"
    }

    private fun fillCards(container: LinearLayout, items: List<GuideItem>, startIndex: Int): Int {
        var index = startIndex
        for (item in items) {
            val card = LayoutInflater.from(this).inflate(R.layout.item_guide_card, container, false)
            card.findViewById<TextView>(R.id.cardEmoji).text = item.emoji
            card.findViewById<TextView>(R.id.cardTitle).text = item.title
            card.findViewById<TextView>(R.id.cardDesc).text = item.desc

            // start invisible and slightly low, then slide up one after another
            card.alpha = 0f
            card.translationY = 40f
            container.addView(card)
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(350)
                .setStartDelay(minOf(index, 12) * 60L)
                .start()
            index++
        }
        return index
    }
}