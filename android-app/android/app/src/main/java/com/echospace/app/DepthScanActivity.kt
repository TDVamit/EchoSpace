package com.echospace.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLES11Ext
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.graphics.drawable.GradientDrawable
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.ar.core.Config
import com.google.ar.core.Anchor
import com.google.ar.core.Pose
import com.google.ar.core.Plane
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DepthScanActivity : Activity(), GLSurfaceView.Renderer {
  private var session: Session? = null
  private lateinit var glView: GLSurfaceView
  private lateinit var status: TextView
  private lateinit var audioToggle: TextView
  private lateinit var detectionOverlay: DetectionOverlay
  private lateinit var objectDetector: LiveObjectDetector
  @Volatile private var detectionEnabled = true
  @Volatile private var viewMode = 0 // 0 camera, 1 point cloud, 2 third person
  @Volatile private var recordAction = 0 // 1 start, -1 stop
  @Volatile private var recording = false
  private var demoRecorder: DemoRecorder? = null
  private var demoAudio: DemoAudioRecorder? = null
  private lateinit var recordButton: TextView
  private val thirdProjection = FloatArray(16)
  private val thirdView = FloatArray(16)
  private val thirdWorld = FloatArray(16)
  private val thirdMap = FloatArray(16)
  private val demoOverlay = DemoDetectionOverlay()
  private var markerProgram = 0
  @Volatile private var detectionSummary = "DETECTOR STARTING"
  private var lastDetectionNanos = 0L
  private var installRequested = false
  private var activityResumed = false
  private var density = 55
  private var blindMode = false
  private var previewRange = 1f
  private var heightBelow = 1.5f
  private var heightAbove = 0.6f
  private var reduceFloor = false
  @Volatile private var floorFilterActive = false
  private var lastHeading = floatArrayOf(0f, 0f, -1f)
  private var audioEnabled = true
  private var audioVoices = 128
  private var audioVolume = 0.35f
  @Volatile private var audioStarted = false
  private var nextAudioRetryMillis = 0L
  private var lastAudioUpdateNanos = 0L
  private var lastAudioSelectionNanos = 0L
  private var lastAudioDiagnosticsNanos = 0L
  private var lastCloudOverlayNanos = 0L
  private data class MapPoint(val position: FloatArray, val semantic: SemanticPointLabel = SemanticPointLabel(),
    val correction: MapCorrectionEvidence = MapCorrectionEvidence())
  private data class CloudSnapshot(val coordinates: FloatArray, val categories: IntArray, val expiresAt: LongArray)
  private var selectedAudioData = FloatArray(0)
  private var selectedAudioCategories = IntArray(0)
  private var selectedAudioExpiry = LongArray(0)
  private var selectedAudioOffsets = IntArray(0)
  @Volatile private var soundingPointCount = 0
  @Volatile private var soundingTaggedCount = 0
  private var cameraPosition = floatArrayOf(0f, 0f, 0f)
  private var mapAnchor: Anchor? = null
  private val pointLock = Any()
  private val points = LinkedHashMap<Long, MapPoint>()
  private val pointData = AtomicReference(CloudSnapshot(FloatArray(0), IntArray(0), LongArray(0)))
  @Volatile private var labeledPointCount = 0
  private var renderBuffer: FloatBuffer? = null
  private var renderColorBuffer: FloatBuffer? = null
  private var renderedSnapshot: CloudSnapshot? = null
  private var renderedColorsAt = 0L
  private var renderedPointCount = 0
  private var voxelSizeMeters = INITIAL_VOXEL_SIZE_METERS
  private var vertexShader = 0
  private var fragmentShader = 0
  private var program = 0
  private var positionHandle = 0
  private var colorHandle = 0
  private var mvpHandle = 0
  private var cameraProgram = 0
  private var cameraPositionHandle = 0
  private var cameraTexCoordHandle = 0
  private var cameraTextureHandle = 0
  private val cameraQuad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
  private val cameraTexCoords = FloatArray(8)
  private val cameraQuadBuffer = directFloatBuffer(cameraQuad)
  private val cameraTexBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
  private var cameraTextureId = 0
  private var textureSession: Session? = null
  private var viewProjection = FloatArray(16)
  private var hasMapProjection = false
  private val worldViewProjection = FloatArray(16)
  private val anchorMatrix = FloatArray(16)
  private val viewMatrix = FloatArray(16)
  private val projectionMatrix = FloatArray(16)
  private var lastDepthSampleNanos = 0L
  private var lastCorrectionNanos = 0L
  private var lastRawDepthTimestamp = 0L
  private val sampleIntervalNanos = 80_000_000L
  private val handler = Handler(Looper.getMainLooper())
  private var trackingLabel = "Starting ARCore…"
  private var depthCount = 0
  private var validDepth = false
  private var cloudAutoThinned = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    density = (intent.getIntExtra("density", 55)).coerceIn(10, 100)
    blindMode = intent.getBooleanExtra("blindMode", false)
    previewRange = intent.getIntExtra("range", 1).coerceIn(1, 20).toFloat()
    heightBelow = intent.getFloatExtra("heightBelow", 1.5f).coerceIn(0.25f, 2.5f)
    heightAbove = intent.getFloatExtra("heightAbove", 0.6f).coerceIn(0.1f, 2f)
    reduceFloor = intent.getBooleanExtra("reduceFloor", false)
    audioEnabled = intent.getBooleanExtra("audioEnabled", true)
    audioVoices = intent.getIntExtra("audioVoices", 128).coerceIn(8, 256)
    audioVolume = intent.getFloatExtra("audioVolume", 0.35f).coerceIn(0f, 1f)
    window.statusBarColor = android.graphics.Color.rgb(17, 31, 27)
    window.navigationBarColor = android.graphics.Color.rgb(17, 31, 27)
    val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.rgb(17, 31, 27)) }
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(0, bars.top, 0, bars.bottom)
      insets
    }
    glView = GLSurfaceView(this).apply {
      setEGLContextClientVersion(2)
      preserveEGLContextOnPause = true
      setRenderer(this@DepthScanActivity)
      renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    root.addView(glView, FrameLayout.LayoutParams(-1, -1))
    detectionOverlay = DetectionOverlay(this)
    if (blindMode) detectionOverlay.visibility = android.view.View.INVISIBLE
    root.addView(detectionOverlay, FrameLayout.LayoutParams(-1, -1))
    objectDetector = LiveObjectDetector(this) { result ->
      android.util.Log.i("EchoSpaceDetector", "inference_ms=${result.inferenceMs} total_ms=${result.totalMs} count=${result.detections.size} error=${result.error}")
      if (detectionEnabled && result.semantic != null) {
        glView.queueEvent {
          if (detectionEnabled && mapAnchor?.trackingState == TrackingState.TRACKING &&
            android.os.SystemClock.elapsedRealtime() - result.capturedAt <= 3000L) {
            applySemanticObservations(result.semantic, result.capturedAt)
          }
        }
      }
      handler.post {
        if (detectionEnabled && !isFinishing) {
          detectionSummary = result.error?.let { "DETECTOR ERROR: $it" }
            ?: "DETECTION ${result.detections.size} · ${result.semantic?.offsets?.size ?: 0} MATCHED"
          detectionOverlay.result = result
          detectionOverlay.invalidate()
          detectionOverlay.postDelayed({ detectionOverlay.invalidate() }, 2100)
        }
      }
    }
    val top = FrameLayout(this)
    fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    fun pill(color: Int, radius: Float = 14f) = GradientDrawable().apply { setColor(color); cornerRadius = radius * resources.displayMetrics.density }
    fun control(title: String, description: String, action: () -> Unit): TextView = TextView(this).apply {
      text = title; contentDescription = description
      setTextColor(0xffffffff.toInt()); textSize = 13f; gravity = Gravity.CENTER
      setPadding(dp(8), dp(8), dp(8), dp(8)); minHeight = dp(48); background = pill(0xe928473b.toInt())
      isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), dp(6), dp(6), dp(6)); background = pill(0xe6153028.toInt()) }
    val title = TextView(this).apply { text = "Live scan"; textSize = 16f; setTextColor(0xffffffff.toInt()); setTypeface(null, android.graphics.Typeface.BOLD) }
    header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
    val close = control("Close", "Close scan") { finish() }
    header.addView(close)
    top.addView(header, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(16), dp(12), dp(16), 0) })
    status = TextView(this).apply { text = "Getting ready…"; textSize = 12f; setTextColor(0xffd3e9da.toInt()); setPadding(dp(14), dp(9), dp(14), dp(9)); background = pill(0xd9153028.toInt()) }
    top.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(16), dp(82), dp(16), 0) })
    val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(10), dp(10), dp(10), dp(10)); background = pill(0xec142e26.toInt(), 18f) }
    if (!blindMode) {
      val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
      val labels = arrayOf("Camera", "Points", "3D view")
      val buttons = ArrayList<TextView>()
      for (index in labels.indices) {
        val item = control(labels[index], "Show ${labels[index]} view") {
          viewMode = index
          detectionOverlay.cloudMode = index == 1
          detectionOverlay.visibility = if (index == 2) android.view.View.INVISIBLE else android.view.View.VISIBLE
          detectionOverlay.invalidate()
          for (j in buttons.indices) buttons[j].background = pill(if (j == index) 0xff47755b.toInt() else 0xff28473b.toInt())
        }
        buttons.add(item); modeRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
      }
      buttons[0].background = pill(0xff47755b.toInt())
      bottom.addView(modeRow, LinearLayout.LayoutParams(-1, -2))
      recordButton = control("Record 3 views", "Record three demo videos and stereo sound") {
        if (recordAction == 0) { recordAction = if (recording) -1 else 1; recordButton.text = if (recording) "Saving…" else "Starting…" }
      }
      bottom.addView(recordButton, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(3), dp(8), dp(3), 0) })
    }
    val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    audioToggle = control("Sound on", "Toggle spatial audio") {
      audioEnabled = !audioEnabled
      AudioEngine.setEnabled(audioEnabled)
      if (audioEnabled) { AudioEngine.setConfig(audioVolume, previewRange, audioVoices); audioStarted = AudioEngine.start() }
      else { AudioEngine.setSources(FloatArray(0)); AudioEngine.stop(); audioStarted = false }
      updateAudioToggle()
    }
    tools.addView(audioToggle, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    lateinit var detectButton: TextView
    detectButton = control("Detection on", "Toggle object detection") {
      detectionEnabled = !detectionEnabled
      detectButton.text = if (detectionEnabled) "Detection on" else "Detection off"
      detectionSummary = if (detectionEnabled) "DETECTOR STARTING" else "Detection off"
      if (!detectionEnabled) { detectionOverlay.result = null; detectionOverlay.invalidate() }
    }
    tools.addView(detectButton, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
    bottom.addView(tools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    top.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(16), 0, dp(16), dp(18)) })
    updateAudioToggle()
    root.addView(top)
    setContentView(root)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      initializeSession()
    } else {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
    }
  }

  private val statusUpdater = object : Runnable {
    override fun run() {
      if (!isFinishing) {
        if (activityResumed && audioEnabled && !AudioEngine.isRunning() && android.os.SystemClock.elapsedRealtime() >= nextAudioRetryMillis) {
          nextAudioRetryMillis = android.os.SystemClock.elapsedRealtime() + 3000L
          audioStarted = AudioEngine.start()
        }
        status.text = when {
          trackingLabel.contains("PAUSED") || trackingLabel.contains("FROZEN") -> "Tracking paused · Move slowly"
          trackingLabel.contains("ERROR") || trackingLabel.contains("UNAVAILABLE") -> trackingLabel
          recording -> if (audioEnabled) "Recording views and stereo audio" else "Recording views · sound off"
          reduceFloor && !floorFilterActive -> "Finding the floor…"
          !validDepth -> "Move slowly to begin scanning"
          else -> "Scanning your surroundings"
        }
        handler.postDelayed(this, 400)
      }
    }
  }

  private fun updateAudioToggle() {
    if (::audioToggle.isInitialized) audioToggle.text = if (audioEnabled) "Sound on" else "Sound off"
  }

  private fun initializeSession() {
    try {
      when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> { installRequested = true; return }
        ArCoreApk.InstallStatus.INSTALLED -> Unit
      }
      val created = Session(this)
      val config = Config(created)
      if (!created.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
        created.close()
        trackingLabel = "Depth API is not supported on this phone"
        Toast.makeText(this, trackingLabel, Toast.LENGTH_LONG).show()
        return
      }
      config.depthMode = Config.DepthMode.AUTOMATIC
      config.focusMode = Config.FocusMode.AUTO
      if (reduceFloor) config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
      created.configure(config)
      session = created
      trackingLabel = "ARCORE READY · MOVE TO MAP"
      if (activityResumed) created.resume()
    } catch (e: Exception) {
      trackingLabel = when (e) {
        is com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException -> "Install Google Play Services for AR"
        is com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException -> "ARCore installation was cancelled"
        else -> "ARCore unavailable: ${e.message ?: e.javaClass.simpleName}"
      }
      Toast.makeText(this, trackingLabel, Toast.LENGTH_LONG).show()
    }
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    if (requestCode == CAMERA_REQUEST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) initializeSession()
    else { trackingLabel = "Camera permission is needed for AR depth"; Toast.makeText(this, trackingLabel, Toast.LENGTH_LONG).show() }
  }

  override fun onResume() {
    super.onResume()
    activityResumed = true
    lastAudioSelectionNanos = 0L
    lastAudioUpdateNanos = 0L
    lastDepthSampleNanos = 0L
    lastCorrectionNanos = 0L
    lastRawDepthTimestamp = 0L
    lastDetectionNanos = 0L
    selectedAudioData = FloatArray(0)
    if (::glView.isInitialized) glView.onResume()
    handler.post(statusUpdater)
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && session == null) {
      initializeSession()
    } else if (session != null && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      try { session?.resume() } catch (e: Exception) { trackingLabel = "Could not resume ARCore: ${e.message}" }
    }
    AudioEngine.setConfig(audioVolume, previewRange, audioVoices)
    AudioEngine.setEnabled(audioEnabled)
    audioStarted = if (audioEnabled) AudioEngine.start() else false
    if (!audioStarted && audioEnabled) trackingLabel = "AUDIO OUTPUT UNAVAILABLE"
    updateAudioToggle()
  }

  override fun onPause() {
    activityResumed = false
    handler.removeCallbacks(statusUpdater)
    AudioEngine.setSources(FloatArray(0))
    soundingPointCount = 0
    soundingTaggedCount = 0
    AudioEngine.stop()
    audioStarted = false
    if (::glView.isInitialized) {
      glView.queueEvent { if (demoRecorder != null) stopDemoRecording() }
      glView.onPause()
    }
    try { session?.pause() } catch (_: Exception) {}
    super.onPause()
  }

  override fun onDestroy() { if (::objectDetector.isInitialized) objectDetector.close(); mapAnchor?.detach(); mapAnchor = null; session?.close(); session = null; super.onDestroy() }

  override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
    textureSession = null
    GLES20.glClearColor(0.055f, 0.095f, 0.08f, 1f)
    val textures = IntArray(1)
    GLES20.glGenTextures(1, textures, 0)
    cameraTextureId = textures[0]
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Position; attribute vec3 a_Color; uniform mat4 u_Mvp; varying vec3 v_Color; void main(){ gl_Position=u_Mvp*a_Position; gl_PointSize=3.0; v_Color=a_Color; }")
    fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; varying vec3 v_Color; void main(){ gl_FragColor=vec4(v_Color,0.95); }")
    program = GLES20.glCreateProgram().also { GLES20.glAttachShader(it, vertexShader); GLES20.glAttachShader(it, fragmentShader); GLES20.glLinkProgram(it) }
    positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
    colorHandle = GLES20.glGetAttribLocation(program, "a_Color")
    mvpHandle = GLES20.glGetUniformLocation(program, "u_Mvp")
    val cameraVertexShader = compileShader(GLES20.GL_VERTEX_SHADER,
      "attribute vec4 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord; void main(){ gl_Position=a_Position; v_TexCoord=a_TexCoord; }")
    val cameraFragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER,
      "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES u_CameraTexture; varying vec2 v_TexCoord; void main(){ gl_FragColor=texture2D(u_CameraTexture,v_TexCoord); }")
    cameraProgram = GLES20.glCreateProgram().also {
      GLES20.glAttachShader(it, cameraVertexShader); GLES20.glAttachShader(it, cameraFragmentShader); GLES20.glLinkProgram(it)
    }
    cameraPositionHandle = GLES20.glGetAttribLocation(cameraProgram, "a_Position")
    cameraTexCoordHandle = GLES20.glGetAttribLocation(cameraProgram, "a_TexCoord")
    cameraTextureHandle = GLES20.glGetUniformLocation(cameraProgram, "u_CameraTexture")
    markerProgram = GLES20.glCreateProgram().also {
      GLES20.glAttachShader(it, compileShader(GLES20.GL_VERTEX_SHADER, "attribute vec4 a_Position; uniform mat4 u_Mvp; void main(){gl_Position=u_Mvp*a_Position;gl_PointSize=14.0;}"))
      GLES20.glAttachShader(it, compileShader(GLES20.GL_FRAGMENT_SHADER, "precision mediump float; void main(){gl_FragColor=vec4(0.85,1.0,0.52,1.0);}"))
      GLES20.glLinkProgram(it)
    }
    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
  }

  override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { GLES20.glViewport(0, 0, width, height) }

  override fun onDrawFrame(gl: GL10?) {
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
    val active = session
    if (active != null) {
      try {
        if (textureSession !== active) {
          active.setCameraTextureName(cameraTextureId)
          textureSession = active
        }
        active.setDisplayGeometry(windowManager.defaultDisplay.rotation, glView.width, glView.height)
        val frame = active.update()
        if (!blindMode && viewMode == 0) drawCameraBackground(frame)
        val camera = frame.camera
        trackingLabel = when (camera.trackingState) {
          TrackingState.TRACKING -> "TRACKING · SWEEP SLOWLY"
          TrackingState.PAUSED -> "TRACKING PAUSED · MAP FROZEN · ${camera.trackingFailureReason}"
          else -> "TRACKING STOPPED"
        }
        if (camera.trackingState == TrackingState.TRACKING && mapAnchor == null) {
          mapAnchor = active.createAnchor(camera.pose)
        }
        val anchor = mapAnchor
        val cameraInMap = if (camera.trackingState == TrackingState.TRACKING && anchor?.trackingState == TrackingState.TRACKING)
          anchor.pose.inverse().compose(camera.pose) else null
        if (cameraInMap != null) {
          cameraPosition[0] = cameraInMap.tx(); cameraPosition[1] = cameraInMap.ty(); cameraPosition[2] = cameraInMap.tz()
        } else if (soundingPointCount != 0) {
          AudioEngine.setSources(FloatArray(0))
          soundingPointCount = 0
          soundingTaggedCount = 0
        }
        if (cameraInMap == null && camera.trackingState == TrackingState.TRACKING && anchor != null) {
          trackingLabel = "MAP ANCHOR PAUSED · MAP FROZEN"
        }
        if (cameraInMap != null && frame.timestamp - lastDepthSampleNanos >= sampleIntervalNanos) {
          lastDepthSampleNanos = frame.timestamp
          sampleDepth(frame, cameraInMap)
        }
        if (cameraInMap != null && frame.timestamp - lastCorrectionNanos >= 250_000_000L) {
          lastCorrectionNanos = frame.timestamp
          correctVisiblePoints(frame, cameraInMap)
        }
        if (detectionEnabled && cameraInMap != null && frame.timestamp - lastDetectionNanos >= 750_000_000L) {
          lastDetectionNanos = frame.timestamp
          objectDetector.submit(frame, glView.width, glView.height, cameraInMap, pointData.get().coordinates)
        }
        if (audioEnabled && audioStarted && cameraInMap != null && frame.timestamp - lastAudioUpdateNanos >= sampleIntervalNanos) {
          lastAudioUpdateNanos = frame.timestamp
          refreshAudioSources(anchor!!.pose.inverse().compose(camera.displayOrientedPose), anchor.pose, frame.timestamp)
        }
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 30f)
        if (cameraInMap != null && anchor != null) {
          Matrix.multiplyMM(worldViewProjection, 0, projectionMatrix, 0, viewMatrix, 0)
          anchor.pose.toMatrix(anchorMatrix, 0)
          Matrix.multiplyMM(viewProjection, 0, worldViewProjection, 0, anchorMatrix, 0)
          hasMapProjection = true
          if (!blindMode && viewMode == 1) drawPoints()
          if (!blindMode && viewMode == 2) {
            makeThirdView(anchor.pose, camera.displayOrientedPose, glView.width.toFloat() / maxOf(1, glView.height))
            drawPoints(thirdMap); drawViewerMarker(thirdMap)
          }
          if (viewMode == 1 && !blindMode && detectionEnabled && frame.timestamp - lastCloudOverlayNanos >= 200_000_000L) {
            lastCloudOverlayNanos = frame.timestamp
            updateCloudOverlay()
          }
        } else if (hasMapProjection) {
          // Freeze the last reliable view while ARCore recovers tracking.
          if (viewMode == 1) drawPoints()
        }
        handleDemoRecording(frame, anchor, cameraInMap, camera.displayOrientedPose)
      } catch (_: com.google.ar.core.exceptions.CameraNotAvailableException) {
        trackingLabel = "CAMERA UNAVAILABLE · RESTART SCAN"
      } catch (e: Exception) {
        android.util.Log.e("EchoSpaceMap", "Frame processing failed", e)
        trackingLabel = "FRAME ERROR · ${e.javaClass.simpleName}"
      }
    }
  }

  private fun makeThirdView(anchorPose: Pose, cameraWorld: Pose, aspect: Float) {
    val forward = cameraWorld.rotateVector(floatArrayOf(0f, 0f, -1f))
    val length = kotlin.math.sqrt(forward[0] * forward[0] + forward[2] * forward[2])
    val fx = if (length > 0.05f) forward[0] / length else 0f
    val fz = if (length > 0.05f) forward[2] / length else -1f
    val x = cameraWorld.tx(); val y = cameraWorld.ty(); val z = cameraWorld.tz()
    Matrix.setLookAtM(thirdView, 0, x - fx * 4f, y + 3f, z - fz * 4f,
      x + fx * 1.5f, y, z + fz * 1.5f, 0f, 1f, 0f)
    Matrix.perspectiveM(thirdProjection, 0, 55f, aspect.coerceAtLeast(0.1f), 0.1f, 60f)
    Matrix.multiplyMM(thirdWorld, 0, thirdProjection, 0, thirdView, 0)
    anchorPose.toMatrix(anchorMatrix, 0)
    Matrix.multiplyMM(thirdMap, 0, thirdWorld, 0, anchorMatrix, 0)
  }

  private fun drawViewerMarker(matrix: FloatArray) {
    val marker = directFloatBuffer(cameraPosition)
    GLES20.glUseProgram(markerProgram)
    val pos = GLES20.glGetAttribLocation(markerProgram, "a_Position")
    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(markerProgram, "u_Mvp"), 1, false, matrix, 0)
    GLES20.glEnableVertexAttribArray(pos); GLES20.glVertexAttribPointer(pos, 3, GLES20.GL_FLOAT, false, 0, marker)
    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1); GLES20.glDisableVertexAttribArray(pos)
  }

  private fun handleDemoRecording(frame: Frame, anchor: Anchor?, cameraInMap: Pose?, cameraWorld: Pose) {
    val action = recordAction
    if (action == 1) {
      recordAction = 0
      try {
        demoRecorder = DemoRecorder(this).also { it.start() }; recording = true
        runOnUiThread { if (::recordButton.isInitialized) recordButton.text = "Stop recording" }
      } catch (e: Exception) {
        demoRecorder = null; recording = false
        runOnUiThread { if (::recordButton.isInitialized) recordButton.text = "Record 3 views"; Toast.makeText(this, "Three-view recording unavailable: ${e.message}", Toast.LENGTH_LONG).show() }
      }
    } else if (action == -1) {
      recordAction = 0; stopDemoRecording()
    }
    val recorder = demoRecorder ?: return
    if (anchor == null || cameraInMap == null || !hasMapProjection) return
    try {
      val rendered = recorder.frame(frame.timestamp) { mode ->
        GLES20.glClearColor(0.055f, 0.095f, 0.08f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        when (mode) {
          0 -> { drawCameraBackground(frame); if (detectionEnabled) demoOverlay.draw(detectionOverlay.result) }
          1 -> drawPoints(viewProjection, true)
          2 -> {
            makeThirdView(anchor.pose, cameraWorld, DemoRecorder.WIDTH.toFloat() / DemoRecorder.HEIGHT)
            drawPoints(thirdMap, true); drawViewerMarker(thirdMap)
          }
        }
      }
      if (rendered && demoAudio == null && audioEnabled) {
        demoAudio = DemoAudioRecorder(applicationContext) { message -> runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_LONG).show() } }
        AudioEngine.demoAudioCapture = demoAudio
      }
    } catch (e: Exception) {
      android.util.Log.e("EchoSpaceDemo", "Recording interrupted", e)
      AudioEngine.demoAudioCapture = null; demoAudio?.finish(); demoAudio = null
      recorder.abort(); demoRecorder = null; recording = false
      runOnUiThread { if (::recordButton.isInitialized) recordButton.text = "Record 3 views"; Toast.makeText(this, "Recording stopped: ${e.message}", Toast.LENGTH_LONG).show() }
    } finally { GLES20.glViewport(0, 0, glView.width, glView.height) }
  }

  private fun stopDemoRecording() {
    val recorder = demoRecorder ?: return
    demoRecorder = null; recording = false
    AudioEngine.demoAudioCapture = null; demoAudio?.finish(); demoAudio = null
    demoOverlay.release()
    runOnUiThread { if (::recordButton.isInitialized) recordButton.text = "Record 3 views" }
    recorder.stop { result -> runOnUiThread { Toast.makeText(this, result, Toast.LENGTH_LONG).show() } }
  }

  private fun refreshAudioSources(cameraInMap: Pose, anchorPose: Pose, timestampNanos: Long) {
    val up = anchorPose.inverse().rotateVector(floatArrayOf(0f, 1f, 0f))
    val forward = SpatialProximity.heading(up, cameraInMap.rotateVector(floatArrayOf(0f, 0f, -1f)), lastHeading)
    lastHeading = forward
    val right = SpatialProximity.right(forward, up)
    val snapshot = pointData.get()
    val data = snapshot.coordinates
    if (data.isEmpty()) {
      AudioEngine.setSources(FloatArray(0))
      soundingPointCount = 0
      soundingTaggedCount = 0
      selectedAudioData = data
      selectedAudioOffsets = IntArray(0)
      return
    }

    // Choose a spatially balanced set at most four times per second; pose and
    // stereo direction still update for every audio update between selections.
    if (selectedAudioData.isEmpty() || timestampNanos < lastAudioSelectionNanos || timestampNanos - lastAudioSelectionNanos >= 250_000_000L) {
      val now = android.os.SystemClock.elapsedRealtime()
      val activeCategories = IntArray(snapshot.categories.size) { index ->
        if (now <= snapshot.expiresAt[index]) snapshot.categories[index] else -1
      }
      val excluded = if (reduceFloor) floorPointMask(data, activeCategories, anchorPose, cameraInMap) else null
      selectedAudioOffsets = AudioPointSelector.selectOriented(data, cameraPosition, previewRange, audioVoices,
        activeCategories, up, right, forward, heightBelow, heightAbove, excluded)
      selectedAudioData = data
      selectedAudioCategories = snapshot.categories
      selectedAudioExpiry = snapshot.expiresAt
      lastAudioSelectionNanos = timestampNanos
    }
    val selected = FloatArray(selectedAudioOffsets.size * 3)
    val selectedCategories = IntArray(selectedAudioOffsets.size) { -1 }
    val selectedIds = LongArray(selectedAudioOffsets.size)
    val nowMillis = android.os.SystemClock.elapsedRealtime()
    val point = FloatArray(3)
    var written = 0
    for (offset in selectedAudioOffsets) {
      val dx = selectedAudioData[offset] - cameraPosition[0]
      val dy = selectedAudioData[offset + 1] - cameraPosition[1]
      val dz = selectedAudioData[offset + 2] - cameraPosition[2]
      val localX = SpatialProximity.dot(dx, dy, dz, right)
      val localY = SpatialProximity.dot(dx, dy, dz, up)
      val localZ = -SpatialProximity.dot(dx, dy, dz, forward)
      if (!SpatialProximity.withinZone(localX, localY, localZ, previewRange, heightBelow, heightAbove)) continue
      point[0] = selectedAudioData[offset]
      point[1] = selectedAudioData[offset + 1]
      point[2] = selectedAudioData[offset + 2]
      selectedIds[written] = pointKey(point)
      selected[written * 3] = localX
      selected[written * 3 + 1] = localY
      selected[written * 3 + 2] = localZ
      val pointIndex = offset / 3
      if (nowMillis <= selectedAudioExpiry[pointIndex]) selectedCategories[written] = selectedAudioCategories[pointIndex]
      // Unclassified points above the phone receive a distinct overhead cue.
      if (selectedCategories[written] < 0 && localY > 0.35f) selectedCategories[written] = 18
      written++
    }
    AudioEngine.setSources(
      if (written == selectedAudioOffsets.size) selected else selected.copyOf(written * 3),
      if (written == selectedAudioOffsets.size) selectedCategories else selectedCategories.copyOf(written),
      if (written == selectedAudioOffsets.size) selectedIds else selectedIds.copyOf(written),
    )
    soundingPointCount = written
    soundingTaggedCount = selectedCategories.take(written).count { it in 0..17 }
    if (BuildConfig.DEBUG && timestampNanos - lastAudioDiagnosticsNanos >= 2_000_000_000L) {
      val sectors = IntArray(4) // front, right, back, left, relative to the phone
      for (i in 0 until written) {
        val x = selected[i * 3]
        val z = selected[i * 3 + 2]
        val sector = if (kotlin.math.abs(x) > kotlin.math.abs(z)) {
          if (x >= 0) 1 else 3
        } else if (z < 0) 0 else 2
        sectors[sector]++
      }
      android.util.Log.i("EchoSpaceMap", "stored=${data.size / 3} sounding=$written front=${sectors[0]} right=${sectors[1]} back=${sectors[2]} left=${sectors[3]}")
      lastAudioDiagnosticsNanos = timestampNanos
    }
  }

  /** Optional conservative floor attenuation: only unclassified points on a large observed plane. */
  private fun floorPointMask(data: FloatArray, categories: IntArray, anchorPose: Pose, cameraInMap: Pose): BooleanArray? {
    val active = session ?: return null
    val phoneHeight = anchorPose.compose(cameraInMap).ty()
    val floor = active.getAllTrackables(Plane::class.java).asSequence()
      .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING }
      .filter { phoneHeight - it.centerPose.ty() in 0.8f..2.5f && it.extentX * it.extentZ >= 2f }
      .maxByOrNull { it.extentX * it.extentZ }
    if (floor == null) { floorFilterActive = false; return null }
    val polygonBuffer = floor.polygon.duplicate()
    val polygon = FloatArray(polygonBuffer.remaining())
    polygonBuffer.get(polygon)
    if (polygon.size < 6) { floorFilterActive = false; return null }
    val transform = FloatArray(16)
    floor.centerPose.inverse().compose(anchorPose).toMatrix(transform, 0)
    val excluded = BooleanArray(categories.size)
    for (i in categories.indices) {
      if (categories[i] >= 0) continue
      val x = data[i * 3]; val y = data[i * 3 + 1]; val z = data[i * 3 + 2]
      val py = transform[1] * x + transform[5] * y + transform[9] * z + transform[13]
      if (kotlin.math.abs(py) > 0.05f) continue
      val px = transform[0] * x + transform[4] * y + transform[8] * z + transform[12]
      val pz = transform[2] * x + transform[6] * y + transform[10] * z + transform[14]
      excluded[i] = SpatialProximity.insidePolygon(px, pz, polygon)
    }
    floorFilterActive = true
    return excluded
  }

  private fun sampleDepth(frame: Frame, cameraInMap: Pose) {
    var image: android.media.Image? = null
    try {
      image = frame.acquireDepthImage16Bits()
      validDepth = true
      val plane = image.planes[0]
      val buffer = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
      val width = image.width
      val height = image.height
      val step = (12 - (density * 10 / 100)).coerceAtLeast(2)
      val camera = frame.camera
      val intrinsics = camera.imageIntrinsics
      val f = intrinsics.focalLength
      val pp = intrinsics.principalPoint
      // The depth image can be cropped differently from the CPU camera image.
      // Convert depth texture coordinates into the camera intrinsics' pixel space.
      val textureCorners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
      val imageCorners = FloatArray(6)
      frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, textureCorners, Coordinates2d.IMAGE_PIXELS, imageCorners)
      var added = 0
      synchronized(pointLock) {
        var compacted = false
        loop@ for (y in step / 2 until height step step) {
          for (x in step / 2 until width step step) {
            val byteOffset = y * plane.rowStride + x * plane.pixelStride
            if (byteOffset + 1 >= buffer.capacity()) continue
            val millimeters = buffer.getShort(byteOffset).toInt() and 0xffff
            if (millimeters < 180 || millimeters > 8000) continue
            val z = millimeters / 1000f
            val u = (x + 0.5f) / width
            val v = (y + 0.5f) / height
            val imageX = imageCorners[0] + u * (imageCorners[2] - imageCorners[0]) + v * (imageCorners[4] - imageCorners[0])
            val imageY = imageCorners[1] + u * (imageCorners[3] - imageCorners[1]) + v * (imageCorners[5] - imageCorners[1])
            val px = (imageX - pp[0]) * z / f[0]
            val py = -(imageY - pp[1]) * z / f[1]
            val world = cameraInMap.transformPoint(floatArrayOf(px, py, -z))
            var key = pointKey(world)
            if (!points.containsKey(key)) {
              while (points.size >= MAX_POINTS) {
                compactPointMap()
                compacted = true
                cloudAutoThinned = true
              }
              key = pointKey(world)
              if (!points.containsKey(key)) { points[key] = MapPoint(world); added++ }
            }
          }
        }
        if (added > 0 || compacted) {
          publishPointSnapshot()
        }
      }
    } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
      validDepth = false
    } finally { image?.close() }
  }

  /** Remove only points contradicted by repeated fresh, confident depth behind them. */
  private fun correctVisiblePoints(frame: Frame, cameraInMap: Pose) {
    if (points.isEmpty()) return
    var raw: android.media.Image? = null
    var confidence: android.media.Image? = null
    try {
      raw = frame.acquireRawDepthImage16Bits()
      if (raw.timestamp <= 0L || raw.timestamp == lastRawDepthTimestamp) return
      confidence = frame.acquireRawDepthConfidenceImage()
      if (confidence.width != raw.width || confidence.height != raw.height) return
      val intrinsics = frame.camera.imageIntrinsics
      val dimensions = intrinsics.imageDimensions
      val corners = floatArrayOf(0f, 0f, dimensions[0].toFloat(), 0f, 0f, dimensions[1].toFloat())
      val imageToDepth = FloatArray(6)
      val imageToView = FloatArray(6)
      frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.TEXTURE_NORMALIZED, imageToDepth)
      frame.transformCoordinates2d(Coordinates2d.IMAGE_PIXELS, corners, Coordinates2d.VIEW_NORMALIZED, imageToView)
      val matrix = FloatArray(16)
      cameraInMap.inverse().toMatrix(matrix, 0)
      val focal = intrinsics.focalLength
      val principal = intrinsics.principalPoint
      val depthPlane = raw.planes[0]
      val depthBuffer = depthPlane.buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
      val confidencePlane = confidence.planes[0]
      val confidenceBuffer = confidencePlane.buffer.duplicate()
      val width = raw.width; val height = raw.height
      val pixel = FloatArray(3)
      var removed = 0
      synchronized(pointLock) {
        val iterator = points.entries.iterator()
        while (iterator.hasNext()) {
          val point = iterator.next().value
          if (!MapCorrection.project(point.position, matrix, focal, principal, dimensions[0], dimensions[1], pixel)) continue
          val u = pixel[0]; val v = pixel[1]
          val vx = imageToView[0] + u * (imageToView[2] - imageToView[0]) + v * (imageToView[4] - imageToView[0])
          val vy = imageToView[1] + u * (imageToView[3] - imageToView[1]) + v * (imageToView[5] - imageToView[1])
          if (vx !in 0f..1f || vy !in 0f..1f) continue
          val tx = imageToDepth[0] + u * (imageToDepth[2] - imageToDepth[0]) + v * (imageToDepth[4] - imageToDepth[0])
          val ty = imageToDepth[1] + u * (imageToDepth[3] - imageToDepth[1]) + v * (imageToDepth[5] - imageToDepth[1])
          if (tx < 0f || tx >= 1f || ty < 0f || ty >= 1f) continue
          val px = (tx * width).toInt(); val py = (ty * height).toInt()
          var clear = 0; var blocked = 0
          for (dy in -1..1) for (dx in -1..1) {
            val sx = px + dx; val sy = py + dy
            if (sx !in 0 until width || sy !in 0 until height) continue
            val di = sy * depthPlane.rowStride + sx * depthPlane.pixelStride
            val ci = sy * confidencePlane.rowStride + sx * confidencePlane.pixelStride
            if (di < 0 || di + 1 >= depthBuffer.limit() || ci < 0 || ci >= confidenceBuffer.limit()) continue
            val observed = (depthBuffer.getShort(di).toInt() and 0xffff) / 1000f
            val certainty = confidenceBuffer.get(ci).toInt() and 0xff
            if (!MapCorrection.isReliable(certainty, observed)) continue
            if (MapCorrection.isBehind(observed, pixel[2])) clear++ else blocked++
          }
          val observation = MapCorrection.classify(pixel[2], clear, blocked)
          if (point.correction.observe(observation, raw.timestamp)) { iterator.remove(); removed++ }
        }
        if (removed > 0) {
          publishPointSnapshot()
          // Discard selection from older snapshots immediately after removals.
          selectedAudioData = FloatArray(0)
        }
      }
      lastRawDepthTimestamp = raw.timestamp
      if (removed > 0) android.util.Log.i("EchoSpaceMap", "live correction removed=$removed")
    } catch (_: com.google.ar.core.exceptions.NotYetAvailableException) {
      // Keep the existing map when raw depth/confidence isn't ready.
    } catch (e: RuntimeException) {
      android.util.Log.w("EchoSpaceMap", "Raw depth correction unavailable", e)
    } finally {
      confidence?.close()
      raw?.close()
    }
  }

  private fun drawPoints(matrix: FloatArray = viewProjection, allowBlind: Boolean = false) {
    if (blindMode && !allowBlind) return
    val snapshot = pointData.get()
    val data = snapshot.coordinates
    if (data.isEmpty()) return
    var count = 0
    var i = 0
    var buffer = renderBuffer
    if (buffer == null || buffer.capacity() < data.size) {
      buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
      renderBuffer = buffer
    }
    var colorBuffer = renderColorBuffer
    if (colorBuffer == null || colorBuffer.capacity() < data.size) {
      colorBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
      renderColorBuffer = colorBuffer
    }
    val nowMillis = android.os.SystemClock.elapsedRealtime()
    if (renderedSnapshot !== snapshot || nowMillis - renderedColorsAt >= 500L) {
      buffer.clear(); colorBuffer.clear()
      while (i + 2 < data.size) {
        buffer.put(data[i]); buffer.put(data[i + 1]); buffer.put(data[i + 2]); count += 3
        val pointIndex = i / 3
        val category = if (nowMillis <= snapshot.expiresAt[pointIndex]) snapshot.categories[pointIndex] else -1
        val color = if (category in SEMANTIC_COLORS.indices) SEMANTIC_COLORS[category] else UNKNOWN_COLOR
        colorBuffer.put(color[0]); colorBuffer.put(color[1]); colorBuffer.put(color[2])
        i += 3
      }
      renderedSnapshot = snapshot; renderedColorsAt = nowMillis; renderedPointCount = count
    }
    count = renderedPointCount
    if (count == 0) return
    buffer.position(0)
    colorBuffer.position(0)
    GLES20.glUseProgram(program)
    GLES20.glUniformMatrix4fv(mvpHandle, 1, false, matrix, 0)
    GLES20.glEnableVertexAttribArray(positionHandle)
    GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 12, buffer)
    GLES20.glEnableVertexAttribArray(colorHandle)
    GLES20.glVertexAttribPointer(colorHandle, 3, GLES20.GL_FLOAT, false, 12, colorBuffer)
    GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count / 3)
    GLES20.glDisableVertexAttribArray(positionHandle)
    GLES20.glDisableVertexAttribArray(colorHandle)
  }

  private fun updateCloudOverlay() {
    val snapshot = pointData.get()
    val points = snapshot.coordinates
    val maxPoints = 3000
    val result = FloatArray(maxPoints * 3)
    val stride = maxOf(1, (labeledPointCount + maxPoints - 1) / maxPoints)
    val now = android.os.SystemClock.elapsedRealtime()
    var taggedIndex = 0
    var written = 0
    for (i in snapshot.categories.indices) {
      val category = snapshot.categories[i]
      if (category !in SEMANTIC_COLORS.indices || now > snapshot.expiresAt[i]) continue
      if (taggedIndex++ % stride != 0) continue
      val offset = i * 3
      val x = points[offset]; val y = points[offset + 1]; val z = points[offset + 2]
      val w = viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15]
      if (w <= 0f) continue
      val nx = (viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12]) / w
      val ny = (viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13]) / w
      if (nx !in -1f..1f || ny !in -1f..1f) continue
      result[written * 3] = (nx + 1f) * 0.5f
      result[written * 3 + 1] = (1f - ny) * 0.5f
      result[written * 3 + 2] = category.toFloat()
      written++
      if (written == maxPoints) break
    }
    detectionOverlay.cloudPoints = result.copyOf(written * 3)
    detectionOverlay.postInvalidate()
  }

  private fun drawCameraBackground(frame: Frame) {
    frame.transformCoordinates2d(
      Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, cameraQuad,
      Coordinates2d.TEXTURE_NORMALIZED, cameraTexCoords,
    )
    cameraTexBuffer.clear()
    cameraTexBuffer.put(cameraTexCoords).position(0)
    GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    GLES20.glDepthMask(false)
    GLES20.glUseProgram(cameraProgram)
    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
    GLES20.glUniform1i(cameraTextureHandle, 0)
    GLES20.glEnableVertexAttribArray(cameraPositionHandle)
    GLES20.glVertexAttribPointer(cameraPositionHandle, 2, GLES20.GL_FLOAT, false, 8, cameraQuadBuffer)
    GLES20.glEnableVertexAttribArray(cameraTexCoordHandle)
    GLES20.glVertexAttribPointer(cameraTexCoordHandle, 2, GLES20.GL_FLOAT, false, 8, cameraTexBuffer)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GLES20.glDisableVertexAttribArray(cameraPositionHandle)
    GLES20.glDisableVertexAttribArray(cameraTexCoordHandle)
    GLES20.glDepthMask(true)
    GLES20.glEnable(GLES20.GL_DEPTH_TEST)
  }

  /** Apply evidence only to voxels present in the captured map snapshot. */
  private fun applySemanticObservations(batch: SemanticAssociation.Matches, capturedAt: Long) {
    var changed = false
    synchronized(pointLock) {
      val coordinate = FloatArray(3)
      for (i in batch.offsets.indices) {
        val offset = batch.offsets[i]
        if (offset < 0 || offset + 2 >= batch.points.size) continue
        coordinate[0] = batch.points[offset]; coordinate[1] = batch.points[offset + 1]; coordinate[2] = batch.points[offset + 2]
        val point = points[pointKey(coordinate)] ?: continue
        val dx = point.position[0] - coordinate[0]; val dy = point.position[1] - coordinate[1]; val dz = point.position[2] - coordinate[2]
        if (dx * dx + dy * dy + dz * dz > voxelSizeMeters * voxelSizeMeters * 3f) continue
        if (point.semantic.observe(batch.categories[i], batch.scores[i], capturedAt)) changed = true
      }
      if (changed) publishPointSnapshot()
    }
  }

  private fun publishPointSnapshot() {
    val coordinates = FloatArray(points.size * 3)
    val categories = IntArray(points.size) { -1 }
    val expiresAt = LongArray(points.size) { Long.MAX_VALUE }
    var index = 0
    var labeled = 0
    val nowMillis = android.os.SystemClock.elapsedRealtime()
    for (point in points.values) {
      coordinates[index * 3] = point.position[0]; coordinates[index * 3 + 1] = point.position[1]; coordinates[index * 3 + 2] = point.position[2]
      categories[index] = point.semantic.category
      expiresAt[index] = point.semantic.expiresAt()
      if (categories[index] >= 0 && nowMillis <= expiresAt[index]) labeled++
      index++
    }
    pointData.set(CloudSnapshot(coordinates, categories, expiresAt))
    depthCount = points.size
    labeledPointCount = labeled
  }

  private fun pointKey(point: FloatArray): Long {
    // Symmetric nearest-voxel quantization prevents depth jitter near zero from
    // creating separate cells on opposite sides of the camera origin.
    val qx = (point[0] / voxelSizeMeters).roundToInt()
    val qy = (point[1] / voxelSizeMeters).roundToInt()
    val qz = (point[2] / voxelSizeMeters).roundToInt()
    return (qx.toLong() shl 42) xor ((qy.toLong() and 0x1fffff) shl 21) xor (qz.toLong() and 0x1fffff)
  }

  private fun compactPointMap() {
    voxelSizeMeters *= 1.5f
    val compacted = LinkedHashMap<Long, MapPoint>(points.size)
    for (point in points.values) {
      val key = pointKey(point.position)
      val old = compacted[key]
      if (old == null || (old.semantic.category < 0 && point.semantic.category >= 0)) compacted[key] = point
    }
    points.clear()
    points.putAll(compacted)
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
    val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
    check(status[0] != 0) { "OpenGL shader compilation failed" }
    return shader
  }

  companion object {
    private const val CAMERA_REQUEST = 41
    private const val MAX_POINTS = 90_000
    private const val INITIAL_VOXEL_SIZE_METERS = 0.10f
    private val UNKNOWN_COLOR = floatArrayOf(0.63f, 0.88f, 0.72f)
    private val SEMANTIC_COLORS = arrayOf(
      floatArrayOf(1f, 0.72f, 0.30f), floatArrayOf(0.40f, 0.72f, 1f), floatArrayOf(1f, 0.57f, 0.40f),
      floatArrayOf(0.75f, 0.55f, 1f), floatArrayOf(0.98f, 0.52f, 0.76f), floatArrayOf(0.87f, 0.71f, 0.45f),
      floatArrayOf(0.94f, 0.85f, 0.45f), floatArrayOf(0.98f, 0.51f, 0.49f), floatArrayOf(0.59f, 0.75f, 1f),
      floatArrayOf(0.70f, 0.63f, 0.97f), floatArrayOf(0.98f, 0.74f, 0.47f), floatArrayOf(0.67f, 0.76f, 0.83f),
      floatArrayOf(0.35f, 0.85f, 0.56f), floatArrayOf(0.45f, 0.84f, 0.91f), floatArrayOf(1f, 0.70f, 0.35f),
      floatArrayOf(1f, 0.62f, 0.32f), floatArrayOf(1f, 0.38f, 0.34f), floatArrayOf(0.92f, 0.68f, 0.37f),
    )

    private fun directFloatBuffer(values: FloatArray): FloatBuffer =
      ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); position(0) }
  }
}
