package com.project_by_adr.airgestures

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognizerIntent
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var handStage: HandStageView
    private var showCamera = false
    private lateinit var gestureText: TextView
    private lateinit var statsText: TextView
    private lateinit var actionPopup: TextView
    private lateinit var confidenceBar: ProgressBar
    private lateinit var holdRing: HoldRingView
    private lateinit var gamePanel: View
    private lateinit var targetEmoji: TextView
    private lateinit var targetName: TextView
    private lateinit var gameInfo: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var gestureRecognizer: GestureRecognizer? = null

    private val engine = GestureEngine()
    private var lastLabel = ""
    private var voiceActive = false
    private var practiceMode = false

    // live stats for the HUD
    private var frameCount = 0
    private var fps = 0
    private var fpsWindowStart = SystemClock.elapsedRealtime()
    private var actionCount = 0

    // ---- Gesture Challenge game ----
    // pose name, emoji, title
    private val gameTargets = listOf(
        Triple("Open_Palm", "✋", "Open palm"),
        Triple("Closed_Fist", "✊", "Fist"),
        Triple("Thumb_Up", "👍", "Thumbs up"),
        Triple("Thumb_Down", "👎", "Thumbs down"),
        Triple("Victory", "✌️", "Victory"),
        Triple("ILoveYou", "🤟", "I love you"),
        Triple("Pinch", "👌", "Pinch"),
        Triple("Three", "3️⃣", "Three fingers")
    )
    private val gameSeconds = 30
    private var gameOn = false
    private var gameScore = 0
    private var gameEndTime = 0L
    private var gameTarget = gameTargets[0]
    private val gameHandler = Handler(Looper.getMainLooper())
    private val gameTick = object : Runnable {
        override fun run() {
            if (!gameOn) return
            val left = ((gameEndTime - SystemClock.elapsedRealtime()) / 1000).toInt() + 1
            if (left <= 0) {
                endGame(true)
            } else {
                gameInfo.text = "⏱ ${left}s   ·   Score $gameScore"
                gameHandler.postDelayed(this, 250)
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else Toast.makeText(this, "Camera permission needed", Toast.LENGTH_SHORT).show()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Mic permission needed", Toast.LENGTH_SHORT).show()
    }

    // asks for several permissions in one go (used by background mode)
    private val multiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        voiceActive = false
        if (result.resultCode == RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (spoken != null) {
                showAction(PhoneActions.runVoiceCommand(this, spoken) ?: "🤔  Heard: $spoken")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        gestureText = findViewById(R.id.gestureText)
        statsText = findViewById(R.id.statsText)
        actionPopup = findViewById(R.id.actionPopup)
        confidenceBar = findViewById(R.id.confidenceBar)
        setupStage()
        // tap the small grey line under the pill to switch practice mode on and off
        statsText.setOnClickListener { practiceMode = !practiceMode }
        holdRing = findViewById(R.id.holdRing)
        gamePanel = findViewById(R.id.gamePanel)
        targetEmoji = findViewById(R.id.targetEmoji)
        targetName = findViewById(R.id.targetName)
        gameInfo = findViewById(R.id.gameInfo)

        findViewById<Button>(R.id.micButton).setOnClickListener { startVoiceCommand() }
        findViewById<Button>(R.id.gameButton).setOnClickListener {
            if (gameOn) endGame(false) else startGame()
        }
        findViewById<Button>(R.id.adminButton).setOnClickListener { requestDeviceAdmin() }
        findViewById<Button>(R.id.backgroundButton).setOnClickListener { startBackgroundMode() }
        findViewById<Button>(R.id.guideButton).setOnClickListener {
            UiFx.open(this, Intent(this, GuideActivity::class.java))
        }
        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            UiFx.open(this, Intent(this, SettingsActivity::class.java))
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        setupGestureRecognizer()

        // animations: buttons squish when pressed, home screen slides in on start
        val buttons = listOf<View>(
            findViewById(R.id.micButton),
            findViewById(R.id.gameButton),
            findViewById(R.id.adminButton),
            findViewById(R.id.backgroundButton),
            findViewById(R.id.guideButton),
            findViewById(R.id.settingsButton)
        )
        UiFx.pressEffect(*buttons.toTypedArray())
        UiFx.entrance(findViewById(R.id.hudGroup), findViewById(R.id.bottomBar), buttons)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        // pick up any change made in Settings (sliders and gesture mapping)
        GesturePrefs.applyTo(this, engine)
        engine.reset()
        // Back in the app: the app handles gestures itself, so stop the
        // background service. This prevents every action firing twice.
        stopService(Intent(this, OverlayService::class.java))
    }

    override fun onPause() {
        super.onPause()
        if (gameOn) endGame(false)
    }

    // ---------------------------------------------------------------- AI setup

    private fun setupGestureRecognizer() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ -> onGestureResult(result) }
            .setErrorListener { e -> Log.e("AirGestures", "Recognizer error", e) }
            .build()

        gestureRecognizer = GestureRecognizer.createFromOptions(this, options)
    }

    /** Sets up the hand-only stage. Tapping it shows or hides the real camera. */
    private fun setupStage() {
        handStage = findViewById(R.id.handStage)
        val prefs = getSharedPreferences("air_gestures_ui", MODE_PRIVATE)
        showCamera = prefs.getBoolean("show_camera", false)
        applyCameraMode(false)

        handStage.setOnClickListener {
            showCamera = !showCamera
            prefs.edit().putBoolean("show_camera", showCamera).apply()
            applyCameraMode(true)
            startCamera()   // rebinds the camera with or without the preview
        }
    }

    private fun applyCameraMode(animate: Boolean) {
        handStage.setCameraBehind(showCamera)
        previewView.animate().cancel()
        if (showCamera) {
            previewView.visibility = View.VISIBLE
            if (animate) {
                previewView.alpha = 0f
                previewView.animate().alpha(1f).setStartDelay(0).setDuration(350).start()
            } else {
                previewView.alpha = 1f
            }
        } else {
            if (animate) {
                previewView.animate().alpha(0f).setStartDelay(0).setDuration(250)
                    .withEndAction { if (!showCamera) previewView.visibility = View.GONE }
                    .start()
            } else {
                previewView.visibility = View.GONE
            }
        }
    }

    /** The preview is only part of the camera setup when the camera is shown. */
    private fun useCases(
        preview: Preview,
        analysis: ImageAnalysis
    ): Array<androidx.camera.core.UseCase> =
        if (showCamera) arrayOf(preview, analysis) else arrayOf(analysis)

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, *useCases(preview, imageAnalyzer)
                )
            } catch (e: Exception) {
                Log.e("AirGestures", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        try {
            var bitmap = imageProxy.toBitmap()
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            gestureRecognizer?.recognizeAsync(mpImage, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e("AirGestures", "Frame analysis error", e)
        } finally {
            imageProxy.close()
        }
    }

    // ------------------------------------------------------- results -> actions

    private fun onGestureResult(result: GestureRecognizerResult) {
        val landmarks = result.landmarks().firstOrNull()
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val label = top?.categoryName()
        val confidence = top?.score() ?: 0f

        // the AI delivers results on its own thread; do everything on the UI thread
        runOnUiThread {
            val out = engine.update(landmarks, label, confidence)
            updateHud(landmarks != null, out.pose, confidence)
            holdRing.setHold(if (showCamera) out.progress else 0f)
            if (landmarks != null) handStage.updateLandmarks(landmarks) else handStage.clear()
            handStage.setHold(out.progress)
            if (out.firedPose != null) handStage.pulse()

            if (gameOn) {
                // in the game, gestures score points instead of controlling the phone
                out.firedPose?.let { gameHit(it) }
            } else {
                val action = out.action
                if (action != null && action != GestureEngine.Action.NONE) runAction(action)
            }
        }
    }

    private fun runAction(action: GestureEngine.Action) {
        if (action == GestureEngine.Action.VOICE) {
            startVoiceCommand()
        } else {
            PhoneActions.perform(this, action)?.let { showAction(it) }
        }
    }

    // ------------------------------------------------------------------ game

    private fun startGame() {
        gameOn = true
        gameScore = 0
        gameEndTime = SystemClock.elapsedRealtime() + gameSeconds * 1000L
        engine.reset()
        nextTarget()
        gamePanel.visibility = View.VISIBLE
        gameHandler.removeCallbacks(gameTick)
        gameHandler.post(gameTick)
    }

    private fun nextTarget() {
        var next = gameTargets.random()
        while (next == gameTarget) next = gameTargets.random()
        gameTarget = next
        targetEmoji.text = next.second
        targetName.text = next.third
        pop(targetEmoji)
    }

    private fun gameHit(pose: String) {
        if (pose != gameTarget.first) return
        gameScore++
        window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        nextTarget()
    }

    private fun endGame(showResult: Boolean) {
        if (!gameOn) return
        gameOn = false
        gameHandler.removeCallbacks(gameTick)
        gamePanel.visibility = View.GONE
        engine.reset()
        if (showResult) {
            val best = GesturePrefs.bestScore(this)
            val text = if (gameScore > best) {
                GesturePrefs.setBestScore(this, gameScore)
                "🏆  New best: $gameScore!"
            } else {
                "⏱  Score $gameScore  ·  best $best"
            }
            showAction(text)
        }
    }

    // --------------------------------------------------------------- HUD + UI

    private fun friendlyName(pose: String): String = when (pose) {
        "Open_Palm" -> "Open palm ✋"
        "Closed_Fist" -> "Fist ✊"
        "Thumb_Up" -> "Thumbs up 👍"
        "Thumb_Down" -> "Thumbs down 👎"
        "Victory" -> "Victory ✌️"
        "Pointing_Up" -> "Pointing ☝️"
        "ILoveYou" -> "I love you 🤟"
        "Pinch" -> "Pinch 👌"
        "Three" -> "Three fingers 3️⃣"
        "L_Up" -> "L sign 🔫⬆️"
        "L_Down" -> "L sign 🔫⬇️"
        else -> pose
    }

    private fun updateHud(handFound: Boolean, pose: String, confidence: Float) {
        frameCount++
        val now = SystemClock.elapsedRealtime()
        if (now - fpsWindowStart >= 1000) {
            fps = frameCount
            frameCount = 0
            fpsWindowStart = now
        }

        val recognised = pose != "None"
        val label = when {
            !handFound -> "Show your hand…"
            recognised -> friendlyName(pose)
            else -> "Hand locked"
        }
        if (label != lastLabel) {
            lastLabel = label
            gestureText.text = label
            if (handFound) pop(gestureText)
        }
        gestureText.alpha = if (handFound) 1f else 0.6f

        val percent = if (handFound && recognised) (confidence * 100).toInt() else 0
        confidenceBar.setProgress(percent, true)

        statsText.text =
            if (practiceMode) engine.debugInfo
            else "ON-DEVICE AI  ·  $fps FPS  ·  $actionCount actions"
    }

    /** Small "pop" so the view reacts when something changes. */
    private fun pop(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.12f).scaleY(1.12f)
            .setStartDelay(0)
            .setDuration(90)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setStartDelay(0)
                    .setDuration(120)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
    }

    /** Big animated badge in the middle of the screen + a haptic buzz. */
    private fun showAction(text: String) {
        actionCount++
        actionPopup.text = text
        findViewById<BurstView>(R.id.burstView).play()
        actionPopup.animate().cancel()
        actionPopup.scaleX = 0.6f
        actionPopup.scaleY = 0.6f
        actionPopup.alpha = 0f
        actionPopup.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(0)
            .setDuration(220)
            .setInterpolator(OvershootInterpolator(1.6f))
            .withEndAction {
                actionPopup.animate()
                    .alpha(0f)
                    .setStartDelay(800)
                    .setDuration(350)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            .start()
        window.decorView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    // ------------------------------------------------------------------ voice

    private fun startVoiceCommand() {
        if (voiceActive) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a command…")
        }
        voiceActive = true
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            voiceActive = false
            Toast.makeText(this, "Voice input isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------- lock + background mode

    private fun requestDeviceAdmin() {
        val compName = ComponentName(this, MyDeviceAdminReceiver::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(compName)) {
            Toast.makeText(this, "Lock feature already enabled", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Needed so the voice command 'lock phone' can lock your screen"
            )
        }
        startActivity(intent)
    }

    private fun startBackgroundMode() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            Toast.makeText(this, "Allow 'Display over other apps', then tap ⏻ again", Toast.LENGTH_LONG).show()
            startActivity(intent)
            return
        }
        // background mode needs the microphone (voice) and, on Android 13+,
        // notifications (so the Stop and Pause buttons show up)
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            Toast.makeText(this, "Allow these, then tap ⏻ again", Toast.LENGTH_LONG).show()
            multiPermissionLauncher.launch(needed.toTypedArray())
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        gameHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        gestureRecognizer?.close()
        Speaker.shutdown()
    }
}