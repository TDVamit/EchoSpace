package com.echospace.app;

public final class AudioCueCheck {
  public static void main(String[] args) {
    AudioCueMath cue = AudioCueMath.INSTANCE;
    float continuous = cue.envelope(0, -1f, 0f);
    if (continuous != cue.envelope(0, 1f, 0f)) throw new AssertionError("continuous cue varies");
    if (!(cue.envelope(1, 1f, 0f) > 0.95f && cue.envelope(1, -1f, 0f) < 0.1f))
      throw new AssertionError("single pulse not distinct");
    if (cue.envelope(2, 0f, 1f) < .95f || cue.envelope(2, 0f, -1f) > .1f)
      throw new AssertionError("double pulse not distinct");
    if (cue.texture(0, .5f, 1f) == cue.texture(1, .5f, 1f)) throw new AssertionError("textures identical");
    for (int c = 0; c < 18; c++) for (int i = 0; i <= 4000; i++) {
      double phase = i * Math.PI * 2 / 4000;
      float envelope = cue.envelope(c, (float)Math.sin(phase), (float)Math.sin(phase * 2));
      if (!Float.isFinite(envelope) || envelope < 0 || envelope > 1.001f) throw new AssertionError("unbounded envelope");
    }
    System.out.println("PASS: distinct continuous and pulsed cues with bounded smooth envelopes");
  }
}
