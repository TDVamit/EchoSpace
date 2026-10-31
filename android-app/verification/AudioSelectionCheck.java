package com.echospace.app;

public final class AudioSelectionCheck {
  public static void main(String[] args) {
    float[] xyz = new float[303];
    int[] categories = new int[101];
    java.util.Arrays.fill(categories, -1);
    for (int i = 0; i < 100; i++) {
      xyz[3 * i] = -0.4f;
      xyz[3 * i + 2] = (i % 10 - 5) * 0.05f;
    }
    xyz[300] = 0.7f;
    categories[100] = 4;
    int[] picked = AudioPointSelector.INSTANCE.select(xyz, new float[3], 1f, 16, categories);
    boolean bed = false, other = false;
    for (int offset : picked) { if (offset == 300) bed = true; else other = true; }
    if (!bed || !other || picked.length != 16) throw new AssertionError("Expected identified bed and surrounding geometry");
    xyz[300] = 1.1f;
    picked = AudioPointSelector.INSTANCE.select(xyz, new float[3], 1f, 16, categories);
    for (int offset : picked) if (offset == 300) throw new AssertionError("Out-of-range object selected");
    xyz[300] = 0.7f;
    java.util.Arrays.fill(categories, 1);
    for (int i = 0; i < 20; i++) categories[i] = -1;
    picked = AudioPointSelector.INSTANCE.select(xyz, new float[3], 1f, 16, categories);
    int background = 0;
    for (int offset : picked) if (categories[offset / 3] < 0) background++;
    if (background < 8 || picked.length != 16) throw new AssertionError("background voice share lost");
    System.out.println("PASS: object and background voice shares and range respected");
  }
}
