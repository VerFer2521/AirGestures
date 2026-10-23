package com.project_by_adr.airgestures

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class OverlayService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "AirGesturesOverlayChannel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.project_by_adr.airgestures.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.project_by_adr.airgestures.TOGGLE_PAUSE"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: HandSkeletonOverlayView
    private lateinit var cameraExecutor: ExecutorService
    private var gestureRecognizer: GestureRecognizer? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var paused = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine = GestureEngine()

    // ---- battery saver ----
    private val minFrameGapMs = 30L          // up to about 30 frames per second, the most a phone camera gives
    private var lastFrameTime = 0L
    private var boundProvider: ProcessCameraProvider? = null
    private var cameraActive = false

    // camera off while the screen is off, back on when you unlock the phone
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopCamera()
                Intent.ACTION_USER_PRESENT -> if (!cameraActive) startCamera()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GesturePrefs.applyTo(this, engine)
        startForegroundNotification()
        setupOverlayWindow()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupGestureRecognizer()
        setupSpeechRecognizer()
        startCamera()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ------------------------------------------------------------ set-up

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Air Gestures Background",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Air Gestures running")
            .setContentText("Hand tracking is active in the background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Pause / Resume", pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            // only declare the microphone type if the permission is granted,
            // otherwise newer Android versions refuse to start the service
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = HandSkeletonOverlayView(this)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val density = resources.displayMetrics.density
        val widthPx = (220 * density).toInt()
        val heightPx = (180 * density).toInt()

        val params = WindowManager.LayoutParams(
            widthPx, heightPx, overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        // centred at the bottom, a little above the edge
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.x = 0
        params.y = (40 * density).toInt()

        windowManager.addView(overlayView, params)
    }

    private fun setupGestureRecognizer() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("gesture_recognizer.task")
            .build()

        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener { result, _ -> onGestureResult(result) }
            .setErrorListener { e -> Log.e("OverlayService", "Recognizer error", e) }
            .build()

        gestureRecognizer = GestureRecognizer.createFromOptions(this, options)
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e("OverlayService", "Speech recognition not available on this device")
            return
        }
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                isListening = false
                Log.e("OverlayService", "Speech error code: $error")
                feedback("🎤  Didn't catch that")
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val spoken = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (spoken != null) {
                    feedback(PhoneActions.runVoiceCommand(this@OverlayService, spoken) ?: "🤔  $spoken")
                }
            }
        })
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, imageAnalyzer)
                boundProvider = cameraProvider
                cameraActive = true
            } catch (e: Exception) {
                Log.e("OverlayService", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        boundProvider?.unbindAll()
        cameraActive = false
        engine.reset()
        if (::overlayView.isInitialized) overlayView.clear()
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeFrame(imageProxy: ImageProxy) {
        // battery saver: skip frames that arrive faster than we need
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameTime < minFrameGapMs) {
            imageProxy.close()
            return
        }
        lastFrameTime = now

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
            Log.e("OverlayService", "Frame error", e)
        } finally {
            imageProxy.close()
        }
    }

    // ------------------------------------------------- results -> actions

    private fun feedback(text: String) {
        overlayView.showLabel(text)
    }

    private fun onGestureResult(result: GestureRecognizerResult) {
        val landmarks = result.landmarks().firstOrNull()
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val label = top?.categoryName()
        val confidence = top?.score() ?: 0f

        // the AI delivers results on its own thread; handle everything on the main thread
        mainHandler.post {
            if (landmarks != null) overlayView.updateLandmarks(landmarks) else overlayView.clear()

            // paused: keep showing the skeleton, but do nothing
            if (paused) return@post

            val out = engine.update(landmarks, label, confidence)
            overlayView.setHold(out.progress)
            val action = out.action
            if (action != null && action != GestureEngine.Action.NONE) {
                if (action == GestureEngine.Action.VOICE) {
                    startListening()
                } else {
                    PhoneActions.perform(this, action)?.let { feedback(it) }
                }
            }
        }
    }

    private fun startListening() {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            feedback("🎤  Mic permission missing")
            return
        }
        isListening = true
        feedback("🎤  Listening…")
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        speechRecognizer?.startListening(intent)
        // safety net: never get stuck in "listening" if no callback arrives
        mainHandler.postDelayed({ isListening = false }, 8000)
    }

    // ------------------------------------------------------- life-cycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_TOGGLE_PAUSE -> {
                paused = !paused
                engine.reset()
                overlayView.setPaused(paused)
                if (!paused) feedback("▶  Resumed")
            }
        }
        // never let Android restart this service on its own
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.e("OverlayService", "receiver was not registered", e)
        }
        SoundFx.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        cameraExecutor.shutdown()
        gestureRecognizer?.close()
        speechRecognizer?.destroy()
        Speaker.shutdown()
        if (::windowManager.isInitialized && ::overlayView.isInitialized) {
            val wm = windowManager
            val view = overlayView
            view.playExit {
                try {
                    wm.removeView(view)
                } catch (e: Exception) {
                    Log.e("OverlayService", "removeView failed", e)
                }
            }
        }
    }
}