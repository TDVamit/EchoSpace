package com.echospace.app;

public final class MapCorrectionCheck {
  private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
  public static void main(String[] args) {
    MapCorrection rule = MapCorrection.INSTANCE;
    check(!rule.isReliable(179, 2f) && !rule.isReliable(255, 0f), "missing or low confidence depth");
    check(rule.isReliable(180, 2f), "confidence threshold");
    check(!rule.isBehind(1.1f, 1f) && rule.isBehind(1.3f, 1f), "depth margin");
    check(rule.classify(1f, 2, 0) == MapCorrection.UNKNOWN, "too few samples");
    check(rule.classify(1f, 8, 1) == MapCorrection.PRESENT_OR_OCCLUDED, "occlusion protects points");
    check(rule.classify(1f, 3, 0) == MapCorrection.CLEAR, "clear samples");
    MapCorrectionEvidence evidence = new MapCorrectionEvidence();
    long t = 1_000_000_000L;
    check(!evidence.observe(MapCorrection.CLEAR, t), "first observation");
    check(!evidence.observe(MapCorrection.CLEAR, t), "duplicate frame");
    check(!evidence.observe(MapCorrection.UNKNOWN, t + 1), "unknown observation");
    check(!evidence.observe(MapCorrection.CLEAR, t + 1_000_000_000L), "second observation");
    check(!evidence.observe(MapCorrection.PRESENT_OR_OCCLUDED, t + 2_000_000_000L), "occlusion resets evidence");
    for (int i = 3; i < 6; i++) check(!evidence.observe(MapCorrection.CLEAR, t + i * 1_000_000_000L), "premature removal");
    check(evidence.observe(MapCorrection.CLEAR, t + 6_000_000_000L), "four fresh observations");
    float[] identity = {1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1};
    float[] result = new float[3];
    check(rule.project(new float[]{0,0,-1}, identity, new float[]{100,100}, new float[]{50,50}, 100, 100, result), "visible point");
    check(Math.abs(result[0] - .5f) < .0001f && Math.abs(result[1] - .5f) < .0001f, "project center");
    check(!rule.project(new float[]{3,0,-1}, identity, new float[]{100,100}, new float[]{50,50}, 100, 100, result), "offscreen point");
    check(!rule.project(new float[]{0,0,1}, identity, new float[]{100,100}, new float[]{50,50}, 100, 100, result), "behind camera");
    System.out.println("PASS: projection, confidence, occlusion, fresh evidence and removal");
  }
}
