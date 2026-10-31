import type { Point } from "./types";
export function pointHash(x: number) {
  x = Math.imul(x ^ (x >>> 16), 0x45d9f3b);
  x = Math.imul(x ^ (x >>> 16), 0x45d9f3b);
  return (x ^ (x >>> 16)) >>> 0;
}
// Listener-independent priority avoids replacing voices as angular bins shift.
export function selectAudioPoints(points: Point[], limit: number): Point[] {
  if (limit <= 0) return [];
  return [...points]
    .sort((a, b) => pointHash(a.id) - pointHash(b.id))
    .slice(0, limit);
}
export function surfaceEnabled(
  point: Pick<Point, "category" | "objectId">,
  floor: boolean,
  ceiling: boolean,
) {
  if (point.category === "floor" && !floor) return false;
  if (point.objectId.startsWith("ceiling-") && !ceiling) return false;
  return true;
}
