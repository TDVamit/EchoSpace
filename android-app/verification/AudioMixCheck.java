package com.echospace.app;

public final class AudioMixCheck {
  public static void main(String[] args) {
    AudioMixMath m = AudioMixMath.INSTANCE;
    float one = m.objectGain(0.5f, 0.6f, 1);
    float many = m.objectGain(0.5f, 0.6f, 32) * 32;
    if (Math.abs(one - many) > 0.00001f || one <= 0 || m.objectGain(.5f, 0, 1) != 0)
      throw new AssertionError("Object tone budget or distance fade incorrect");
    for (float peak = 0.01f; peak < 20; peak += 0.01f) {
      float gain = m.limiter(peak);
      if (gain > 1 || gain <= 0 || gain * peak > 0.981f)
        throw new AssertionError("Linked limiter exceeded output range");
    }
    if (Math.abs(m.limiter(.75f) - m.limiter(.7501f)) > .001f)
      throw new AssertionError("Limiter knee is discontinuous");
    System.out.println("PASS: bounded object tone budget, distance fade and continuous linked limiter");
  }
}
