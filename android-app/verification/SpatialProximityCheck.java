package com.echospace.app;

public final class SpatialProximityCheck {
  private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
  public static void main(String[] args) {
    SpatialProximity p = SpatialProximity.INSTANCE;
    check(p.withinZone(.3f, -.8f, 0f, 1f, 1.5f, .6f), "nearby chair below phone");
    check(!p.withinZone(1.1f, -.8f, 0f, 1f, 1.5f, .6f), "outside horizontal range");
    check(!p.withinZone(.3f, -1.6f, 0f, 1f, 1.5f, .6f), "below vertical zone");
    check(p.withinZone(0f, .5f, 0f, 1f, 1.5f, .6f), "direct overhead point");
    check(!p.withinZone(.3f, .7f, 0f, 1f, 1.5f, .6f), "above vertical zone");
    check(p.insidePolygon(0f, 0f, new float[]{-1,-1,1,-1,1,1,-1,1}), "floor polygon inside");
    check(!p.insidePolygon(2f, 0f, new float[]{-1,-1,1,-1,1,1,-1,1}), "floor polygon outside");
    float[] up = {0,0,1}, forward = {0,1,-1}, previous = {0,1,0};
    float[] heading = p.heading(up, forward, previous), right = p.right(heading, up);
    check(Math.abs(heading[2]) < .0001 && Math.abs(heading[1] - 1) < .0001 && Math.abs(right[0] - 1) < .0001, "tilted-map axes");
    float[] xyz = {.3f, 0f, -.8f,  1.1f, 0f, -.8f, 0f,0f,.5f, 0f,0f,1.2f};
    int[] categories = {1,-1,-1,-1};
    int[] selected = AudioPointSelector.INSTANCE.selectOriented(xyz, new float[3], 1f, 16, categories, up, right, heading, 1.5f,.6f,null);
    boolean chair=false, overhead=false, far=false, high=false;
    for (int offset : selected) { if(offset==0)chair=true; if(offset==3)far=true; if(offset==6)overhead=true; if(offset==9)high=true; }
    check(chair && overhead && !far && !high, "oriented selector eligibility");
    boolean[] excluded={true,false,false,false};
    selected=AudioPointSelector.INSTANCE.selectOriented(xyz,new float[3],1f,16,categories,up,right,heading,1.5f,.6f,excluded);
    for(int offset:selected) check(offset!=0, "floor mask applied");
    System.out.println("PASS: horizontal range, height zone, overhead, tilted axes and floor polygon");
  }
}
