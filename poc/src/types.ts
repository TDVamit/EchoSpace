import type * as THREE from "three";
export const categories = [
  "wall",
  "floor",
  "door",
  "table",
  "chair",
  "sofa",
  "plant",
  "cabinet",
  "hazard",
  "unknown",
  "studio-cabin",
] as const;
export type Category = (typeof categories)[number];
export type Texture = "noise" | "sine" | "triangle" | "bell";
export interface Rule {
  texture: Texture;
  frequency: number;
  gain: number;
  pulse: number;
}
export interface Rules {
  categories: Record<Category, Rule>;
  instances: Record<string, Rule>;
}
export const colors: Record<Category, string> = {
  wall: "#a6c9cc",
  floor: "#647d8d",
  door: "#e2b66f",
  table: "#d0a377",
  chair: "#99b899",
  sofa: "#a6a4cf",
  plant: "#75bd91",
  cabinet: "#ccaf91",
  hazard: "#f58266",
  unknown: "#f0bd67",
  "studio-cabin": "#8dc5ba",
};
export const defaultRules: Rules = {
  categories: {
    wall: { texture: "noise", frequency: 280, gain: 0.28, pulse: 0 },
    floor: { texture: "noise", frequency: 140, gain: 0.13, pulse: 0 },
    door: { texture: "bell", frequency: 660, gain: 0.6, pulse: 2 },
    table: { texture: "triangle", frequency: 330, gain: 0.45, pulse: 1 },
    chair: { texture: "sine", frequency: 440, gain: 0.5, pulse: 3 },
    sofa: { texture: "sine", frequency: 220, gain: 0.4, pulse: 0 },
    plant: { texture: "noise", frequency: 1000, gain: 0.3, pulse: 4 },
    cabinet: { texture: "triangle", frequency: 260, gain: 0.4, pulse: 1 },
    hazard: { texture: "triangle", frequency: 880, gain: 0.7, pulse: 6 },
    unknown: { texture: "bell", frequency: 880, gain: 0.55, pulse: 2 },
    "studio-cabin": { texture: "sine", frequency: 360, gain: 0.45, pulse: 1 },
  },
  instances: {},
};
export interface Point {
  id: number;
  position: THREE.Vector3;
  normal: THREE.Vector3;
  objectId: string;
  category: Category;
}
export interface WorldObject {
  id: string;
  name: string;
  category: Category;
  meshes: THREE.Mesh[];
  bounds: THREE.Box3;
}
export interface Settings {
  density: number;
  volume: number;
  range: number;
  objectRanges: Record<string, number>;
  budget: number;
  compensate: boolean;
  throughWalls: boolean;
  view: "scene" | "overlay" | "points";
  cameraMode: "walk" | "top";
  pointSize: number;
  blind: boolean;
  spatialNoise: boolean;
  hearFloor: boolean;
  hearCeiling: boolean;
  wallCues: boolean;
  wallCueVolume: number;
  wallCueMode: "pulsed" | "continuous";
  wallPointVolume: number;
  autoQuietWalls: boolean;
}
export interface Stats {
  wallCues: number;
  knownWalls: number;
  stored: number;
  eligible: number;
  requested: number;
  active: number;
  fps: number;
  level: number;
  scanning: boolean;
  mode: "new" | "full";
  detected: string[];
  target: string | null;
  distance: number;
  position: [number, number, number];
  yaw: number;
  locked: boolean;
  error: string;
}
export const initialStats: Stats = {
  wallCues: 0,
  knownWalls: 0,
  stored: 0,
  eligible: 0,
  requested: 0,
  active: 0,
  fps: 0,
  level: 0,
  scanning: false,
  mode: "new",
  detected: [],
  target: null,
  distance: 0,
  position: [0, 1.65, 5.5],
  yaw: 0,
  locked: false,
  error: "",
};
export function seeded(seed: number) {
  let x = seed | 0;
  return () => {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    return (x >>> 0) / 4294967296;
  };
}
export function resolveRule(
  rules: Rules,
  point: Pick<Point, "objectId" | "category">,
) {
  return rules.instances[point.objectId] ?? rules.categories[point.category];
}
