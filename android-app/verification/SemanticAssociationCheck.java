package com.echospace.app;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;

/** JVM smoke checks for geometry, confidence gating, and repeated-label evidence. */
public final class SemanticAssociationCheck {
  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
  }
  public static void main(String[] args) {
    float[] identity = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};
    float[] imageTransform = {0,0, 1,0, 0,1};
    float[] xyz = {0,0,-2, 0,0,-4, 0.8f,0,-2, 0,0,2};
    short[] depth = {2000,2000,2000,2000};
    var frame = new SemanticAssociation.Frame(xyz, identity, new float[]{100,100}, new float[]{50,50},
        100,100,imageTransform,imageTransform,depth,2,2,640,640,0,0);
    float[] coefficients = new float[32]; coefficients[0] = 1;
    FloatBuffer prototype = FloatBuffer.allocate(32*160*160);
    prototype.put(80*160+80, 1f);
    var instance = new SemanticAssociation.Instance(1,0.35f,0.4f,0.4f,0.6f,0.6f,coefficients);
    var match = SemanticAssociation.INSTANCE.associate(frame,Collections.singletonList(instance),prototype);
    check(match != null && Arrays.equals(match.getOffsets(),new int[]{0}), "mask/box/depth reject occluded and out-of-frame points");
    check(Arrays.equals(match.getCategories(),new int[]{1}), "category and confidence retained");
    var low = new SemanticAssociation.Instance(1,0.349f,0.4f,0.4f,0.6f,0.6f,coefficients);
    check(SemanticAssociation.INSTANCE.associate(frame,Collections.singletonList(low),prototype).getOffsets().length == 0, "confidence cutoff");
    prototype.put(80*160+80, -1f);
    check(SemanticAssociation.INSTANCE.associate(frame,Collections.singletonList(instance),prototype).getOffsets().length == 0, "negative segmentation mask rejected");
    prototype.put(80*160+80, 1f);
    float[] largeCloud = new float[20001*3];
    for (int i=0; i<largeCloud.length; i+=3) { largeCloud[i]=100; largeCloud[i+2]=-2; }
    largeCloud[largeCloud.length-3]=0;
    var largeFrame = new SemanticAssociation.Frame(largeCloud, identity, new float[]{100,100}, new float[]{50,50},
        100,100,imageTransform,imageTransform,depth,2,2,640,640,0,0);
    check(Arrays.equals(SemanticAssociation.INSTANCE.associate(largeFrame,Collections.singletonList(instance),prototype).getOffsets(),new int[]{60000}), "large maps do not skip a fixed subset of points");
    float[] translated = {10,0,-2, 10,0,-4, 10.8f,0,-2, 10,0,2};
    float[] shiftedPose = identity.clone(); shiftedPose[12] = -10;
    var shifted = new SemanticAssociation.Frame(translated, shiftedPose, new float[]{100,100}, new float[]{50,50},
        100,100,imageTransform,imageTransform,depth,2,2,640,640,0,0);
    check(Arrays.equals(SemanticAssociation.INSTANCE.associate(shifted,Collections.singletonList(instance),prototype).getOffsets(),new int[]{0}), "captured camera pose projection");
    var label = new SemanticPointLabel();
    check(!label.observe(1,0.349f,1000), "low score rejected");
    check(!label.observe(1,0.35f,1000) && label.getCategory()==-1, "first observation tentative");
    check(!label.observe(1,0.9f,1000) && label.getCategory()==-1, "same frame not counted twice");
    check(label.observe(1,0.35f,2000) && label.getCategory()==1, "second frame confirms label");
    check(!label.observe(2,0.9f,3000) && label.getCategory()==1, "one contradictory observation does not relabel");
    check(label.observe(2,0.9f,4000) && label.getCategory()==2, "second observation changes label");
    var person = new SemanticPointLabel(); person.observe(7,0.9f,1000); person.observe(7,0.9f,2000);
    check(person.getCategory()==7 && person.expiresAt()==4500, "person labels expire after 2.5 seconds");
    var expired = new SemanticPointLabel(); expired.observe(1,0.9f,1000); expired.observe(1,0.9f,8000);
    check(expired.getCategory()==-1, "old evidence cannot confirm a new observation");
    check(SemanticTone.INSTANCE.frequencyHz(-1)==0f, "unknown points retain noise");
    java.util.Set<Float> frequencies = new java.util.HashSet<>();
    for (int category=0; category<18; category++) frequencies.add(SemanticTone.INSTANCE.frequencyHz(category));
    check(frequencies.size()==18 && !frequencies.contains(0f), "each supported class has a distinct default frequency");
    System.out.println("PASS: captured pose, masks, depth occlusion, 0.35 threshold, repeated observations, relabeling and person expiry");
  }
}
