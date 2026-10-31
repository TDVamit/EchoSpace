package com.echospace.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlin.math.sqrt
import kotlin.math.sin

/** Streams a small set of point voices through Android's standard stereo audio output. */
object AudioEngine {
  private const val SAMPLE_RATE = 44_100
  private const val FRAMES_PER_BLOCK = 512
  private const val MAX_VOICES = 256
  private const val NOISE_GROUPS = 16

  private data class SourceFrame(val positions: FloatArray, val categories: IntArray, val ids: LongArray)
  @Volatile private var sources = SourceFrame(FloatArray(0), IntArray(0), LongArray(0))
  @Volatile private var volume = 0.35f
  @Volatile private var hearingRange = 1f
  @Volatile private var voiceLimit = 128
  @Volatile private var enabled = true
  @Volatile private var running = false
  @Volatile private var track: AudioTrack? = null
  private var worker: Thread? = null
  @Volatile internal var demoAudioCapture: DemoAudioRecorder? = null

  private val filtered = FloatArray(NOISE_GROUPS)
  private val filteredTwice = FloatArray(NOISE_GROUPS)
  private val noiseGroupCount = IntArray(NOISE_GROUPS)
  private val noiseLeftTarget = FloatArray(NOISE_GROUPS)
  private val noiseRightTarget = FloatArray(NOISE_GROUPS)
  private val noiseLeftCurrent = FloatArray(NOISE_GROUPS)
  private val noiseRightCurrent = FloatArray(NOISE_GROUPS)
  private val cueLeftTarget = FloatArray(19)
  private val cueRightTarget = FloatArray(19)
  private val cueLeftCurrent = FloatArray(19)
  private val cueRightCurrent = FloatArray(19)
  private val noiseMix = FloatArray(MAX_VOICES) { 1f }
  private val sharedPhases = FloatArray(19)
  private val sharedPulsePhases = FloatArray(19)
  private val toneSteps = FloatArray(19) { SemanticTone.frequencyHz(it) * 2048f / SAMPLE_RATE }
  private val pulseSteps = FloatArray(19) { SemanticTone.pulseHz(it) / SAMPLE_RATE }
  private val activeSlots = IntArray(MAX_VOICES)
  @Volatile var underrunCount: Int = 0
    private set
  private val slotCategories = IntArray(MAX_VOICES) { -1 }
  private val priorCategories = IntArray(MAX_VOICES) { -1 }
  private val categoryTransition = FloatArray(MAX_VOICES) { 1f }
  private val toneLeftTargets = FloatArray(MAX_VOICES)
  private val toneRightTargets = FloatArray(MAX_VOICES)
  private val toneLeft = FloatArray(MAX_VOICES)
  private val toneRight = FloatArray(MAX_VOICES)
  private val categoryCounts = IntArray(19)
  private val randomState = IntArray(NOISE_GROUPS) { 0x9e3779b9.toInt() xor (0x85ebca6b.toInt() * (it + 1)) }
  private val leftGains = FloatArray(MAX_VOICES)
  private val rightGains = FloatArray(MAX_VOICES)
  private val currentLeft = FloatArray(MAX_VOICES)
  private val currentRight = FloatArray(MAX_VOICES)
  private val slotIds = LongArray(MAX_VOICES) { Long.MIN_VALUE }
  private val slotUsed = BooleanArray(MAX_VOICES)
  private val sineTable = FloatArray(2048) { sin(2.0 * Math.PI * it / 2048).toFloat() }

  @Synchronized
  fun start(): Boolean {
    if (running && track != null) return true
    return try {
      val minimumBytes = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_STEREO,
        AudioFormat.ENCODING_PCM_16BIT,
      )
      if (minimumBytes <= 0) return false
      val builder = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build(),
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build(),
        )
        .setBufferSizeInBytes(maxOf(minimumBytes * 3, FRAMES_PER_BLOCK * 4 * 8))
        .setTransferMode(AudioTrack.MODE_STREAM)
      val created = builder.build()
      if (created.state != AudioTrack.STATE_INITIALIZED) {
        created.release()
        return false
      }
      underrunCount = 0
      created.play()
      track = created
      running = true
      worker = Thread({
        try {
          renderLoop()
        } catch (e: RuntimeException) {
          android.util.Log.e("EchoSpaceAudio", "Audio worker stopped unexpectedly", e)
        } finally {
          if (track === created) {
            track = null
            running = false
            try { created.release() } catch (_: RuntimeException) {}
            android.util.Log.w("EchoSpaceAudio", "Audio output stopped; scanner will retry")
          }
        }
      }, "EchoSpace-Audio").apply { start() }
      true
    } catch (_: RuntimeException) {
      running = false
      track?.release()
      track = null
      false
    }
  }

  @Synchronized
  fun stop() {
    running = false
    val current = track
    track = null
    try { current?.pause() } catch (_: RuntimeException) {}
    try { current?.flush() } catch (_: RuntimeException) {}
    try { current?.stop() } catch (_: RuntimeException) {}
    try { current?.release() } catch (_: RuntimeException) {}
    worker?.interrupt()
    try { worker?.join(250) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    worker = null
  }

  fun isRunning(): Boolean = running && track != null && worker?.isAlive == true
  fun setEnabled(value: Boolean) { enabled = value }
  fun setConfig(newVolume: Float, rangeMeters: Float, maxVoices: Int) {
    volume = newVolume.coerceIn(0f, 1f)
    hearingRange = rangeMeters.coerceIn(1f, 20f)
    voiceLimit = maxVoices.coerceIn(8, MAX_VOICES)
  }
  fun setSources(relativeXyz: FloatArray, categories: IntArray = IntArray(relativeXyz.size / 3) { -1 }, ids: LongArray = LongArray(relativeXyz.size / 3) { it.toLong() }) {
    require(relativeXyz.size % 3 == 0 && categories.size == relativeXyz.size / 3 && ids.size == categories.size)
    sources = SourceFrame(relativeXyz.copyOf(), categories.copyOf(), ids.copyOf())
  }

  private fun renderLoop() {
    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
    val output = ShortArray(FRAMES_PER_BLOCK * 2)
    var previousSources: SourceFrame? = null
    var previousVolume = -1f
    var previousRange = -1f
    var previousLimit = -1
    var nextUnderrunCheck = 0L
    while (running) {
      val currentTrack = track ?: break
      val sourceFrame = sources
      val positions = sourceFrame.positions
      val categories = sourceFrame.categories
      val count = minOf(voiceLimit, positions.size / 3, MAX_VOICES)
      val masterVolume = if (enabled) volume else 0f
      val range = hearingRange
      val rangeSq = range * range
      if (sourceFrame !== previousSources || masterVolume != previousVolume || range != previousRange || voiceLimit != previousLimit) {
      previousSources = sourceFrame
      previousVolume = masterVolume
      previousRange = range
      previousLimit = voiceLimit
      java.util.Arrays.fill(leftGains, 0f)
      java.util.Arrays.fill(rightGains, 0f)
      java.util.Arrays.fill(toneLeftTargets, 0f)
      java.util.Arrays.fill(toneRightTargets, 0f)
      java.util.Arrays.fill(slotUsed, false)
      java.util.Arrays.fill(categoryCounts, 0)
      for (i in 0 until count) if (categories[i] in 0..18) categoryCounts[categories[i]]++
      val slots = HashMap<Long, Int>(MAX_VOICES * 2)
      for (i in 0 until MAX_VOICES) if (slotIds[i] != Long.MIN_VALUE) slots[slotIds[i]] = i
      // Reserve existing slots first so a new point cannot steal a surviving voice.
      for (i in 0 until count) slots[sourceFrame.ids[i]]?.let { slotUsed[it] = true }
      val mixNormalizer = 1f / sqrt(maxOf(count, 1).toFloat())
      for (i in 0 until count) {
        val id = sourceFrame.ids[i]
        var slot = slots[id] ?: -1
        if (slot < 0) {
          var quietest = Float.MAX_VALUE
          for (j in 0 until MAX_VOICES) {
            if (!slotUsed[j]) {
              val level = kotlin.math.abs(currentLeft[j]) + kotlin.math.abs(currentRight[j])
              if (level < quietest) { quietest = level; slot = j }
            }
          }
          if (slot < 0) continue
          slotIds[slot] = id
          slotUsed[slot] = true
        }
        val x = positions[i * 3]
        val y = positions[i * 3 + 1]
        val z = positions[i * 3 + 2]
        val distanceSq = x * x + y * y + z * z
        val horizontalSq = x * x + z * z
        if (distanceSq < 0.0016f || horizontalSq >= rangeSq || masterVolume <= 0f) continue
        val proximity = (1f - sqrt(horizontalSq) / range).coerceIn(0f, 1f)
        // Linear falloff reaches silence continuously at the range boundary.
        val attenuation = proximity
        val gain = masterVolume * attenuation * mixNormalizer
        val horizontal = sqrt(x * x + z * z)
        val pan = if (horizontal > 0.0001f) (x / horizontal).coerceIn(-1f, 1f) else 0f
        leftGains[slot] = gain * sqrt((1f - pan) * 0.5f)
        rightGains[slot] = gain * sqrt((1f + pan) * 0.5f)
        val category = categories[i]
        if (slotCategories[slot] != category) {
          priorCategories[slot] = slotCategories[slot]
          slotCategories[slot] = category
          categoryTransition[slot] = 0f
        }
        if (category in 0..18) {
          // A category gets a bounded share independent of unrelated background voices.
          // Coherent voices share that category's budget rather than drowning it out.
          val objectGain = AudioMixMath.objectGain(masterVolume, attenuation, categoryCounts[category])
          toneLeftTargets[slot] = objectGain * sqrt((1f - pan) * 0.5f)
          toneRightTargets[slot] = objectGain * sqrt((1f + pan) * 0.5f)
        }
      }
      }
      var activeCount = 0
      for (i in 0 until MAX_VOICES) {
        val level = kotlin.math.abs(currentLeft[i]) + kotlin.math.abs(currentRight[i]) +
          kotlin.math.abs(toneLeft[i]) + kotlin.math.abs(toneRight[i]) +
          kotlin.math.abs(leftGains[i]) + kotlin.math.abs(rightGains[i]) +
          kotlin.math.abs(toneLeftTargets[i]) + kotlin.math.abs(toneRightTargets[i])
        if (level >= 0.000001f) activeSlots[activeCount++] = i
        else if (!slotUsed[i]) slotIds[i] = Long.MIN_VALUE
      }

      // Individual point gains still track position, distance and category, but
      // synthesis uses a fixed number of shared channels instead of one DSP
      // generator per point. Group gain changes are interpolated per sample.
      java.util.Arrays.fill(noiseGroupCount, 0)
      java.util.Arrays.fill(noiseLeftTarget, 0f)
      java.util.Arrays.fill(noiseRightTarget, 0f)
      java.util.Arrays.fill(cueLeftTarget, 0f)
      java.util.Arrays.fill(cueRightTarget, 0f)
      for (activeIndex in 0 until activeCount) {
        val i = activeSlots[activeIndex]
        val group = ((slotIds[i] xor (slotIds[i] ushr 32)).toInt() and Int.MAX_VALUE) % NOISE_GROUPS
        noiseGroupCount[group]++
        // Block-rate smoothing (equivalent to the former 45 ms per-sample filter).
        currentLeft[i] += (leftGains[i] - currentLeft[i]) * 0.207f
        currentRight[i] += (rightGains[i] - currentRight[i]) * 0.207f
        toneLeft[i] += (toneLeftTargets[i] - toneLeft[i]) * 0.207f
        toneRight[i] += (toneRightTargets[i] - toneRight[i]) * 0.207f
        val category = slotCategories[i]
        val oldCategory = priorCategories[i]
        val transition = categoryTransition[i]
        categoryTransition[i] = minOf(1f, transition + 0.256f)
        val noiseTarget = if (category in 0..18) 0.55f else 1.5f
        noiseMix[i] += (noiseTarget - noiseMix[i]) * 0.207f
        noiseLeftTarget[group] += currentLeft[i] * noiseMix[i]
        noiseRightTarget[group] += currentRight[i] * noiseMix[i]
        if (category in 0..18) {
          cueLeftTarget[category] += toneLeft[i] * transition
          cueRightTarget[category] += toneRight[i] * transition
        }
        if (oldCategory in 0..18 && transition < 1f) {
          cueLeftTarget[oldCategory] += toneLeft[i] * (1f - transition)
          cueRightTarget[oldCategory] += toneRight[i] * (1f - transition)
        }
      }
      for (group in 0 until NOISE_GROUPS) {
        val scale = 1f / sqrt(maxOf(noiseGroupCount[group], 1).toFloat())
        noiseLeftTarget[group] *= scale
        noiseRightTarget[group] *= scale
      }
      for (frame in 0 until FRAMES_PER_BLOCK) {
        var left = 0f
        var right = 0f
        for (group in 0 until NOISE_GROUPS) {
          noiseLeftCurrent[group] += (noiseLeftTarget[group] - noiseLeftCurrent[group]) * 0.0005f
          noiseRightCurrent[group] += (noiseRightTarget[group] - noiseRightCurrent[group]) * 0.0005f
          var random = randomState[group]
          random = random xor (random shl 13)
          random = random xor (random ushr 17)
          random = random xor (random shl 5)
          randomState[group] = random
          val noise = ((random ushr 8).toFloat() / 8_388_607.5f) - 1f
          val filteredSample = filtered[group] + 0.025f * (noise - filtered[group])
          filtered[group] = filteredSample
          val softNoise = filteredTwice[group] + 0.025f * (filteredSample - filteredTwice[group])
          filteredTwice[group] = softNoise
          left += softNoise * 2.5f * noiseLeftCurrent[group]
          right += softNoise * 2.5f * noiseRightCurrent[group]
        }
        for (category in 0..18) {
          cueLeftCurrent[category] += (cueLeftTarget[category] - cueLeftCurrent[category]) * 0.0005f
          cueRightCurrent[category] += (cueRightTarget[category] - cueRightCurrent[category]) * 0.0005f
          val phaseIndex = sharedPhases[category].toInt() and 2047
          val texture = AudioCueMath.texture(category, sineTable[phaseIndex], sineTable[(phaseIndex * 2) and 2047])
          val pulseIndex = (sharedPulsePhases[category] * 2048).toInt() and 2047
          val pulse = AudioCueMath.envelope(category, sineTable[pulseIndex], sineTable[(pulseIndex * 2) and 2047])
          val sound = texture * pulse
          left += sound * cueLeftCurrent[category]
          right += sound * cueRightCurrent[category]
          val next = sharedPhases[category] + toneSteps[category]
          sharedPhases[category] = if (next >= 2048f) next - 2048f else next
          val nextPulse = sharedPulsePhases[category] + pulseSteps[category]
          sharedPulsePhases[category] = if (nextPulse >= 1f) nextPulse - 1f else nextPulse
        }
        val peak = maxOf(kotlin.math.abs(left), kotlin.math.abs(right))
        val limiter = AudioMixMath.limiter(peak)
        output[frame * 2] = (left * limiter * 32767f).toInt().toShort()
        output[frame * 2 + 1] = (right * limiter * 32767f).toInt().toShort()
      }

      demoAudioCapture?.offer(output)
      try {
        var offset = 0
        while (running && offset < output.size) {
          val written = currentTrack.write(output, offset, output.size - offset, AudioTrack.WRITE_BLOCKING)
          if (written <= 0) break
          offset += written
        }
        if (offset < output.size && running) {
          android.util.Log.e("EchoSpaceAudio", "AudioTrack write stopped after $offset of ${output.size} samples")
          break
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now >= nextUnderrunCheck) {
          underrunCount = currentTrack.underrunCount
          nextUnderrunCheck = now + 1000L
        }
      } catch (e: IllegalStateException) {
        android.util.Log.e("EchoSpaceAudio", "AudioTrack state error", e)
        break
      } catch (e: IllegalArgumentException) {
        android.util.Log.e("EchoSpaceAudio", "AudioTrack write error", e)
        break
      }
    }
  }
}
