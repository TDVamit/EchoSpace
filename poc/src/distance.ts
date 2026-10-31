/** Zero at/outside the hearing radius; smoothly increases to one at the listener. */
export function distanceFade(distance: number, radius: number): number {
  if (!Number.isFinite(distance) || radius <= 0) return 0;
  const x = Math.max(0, Math.min(1, 1 - distance / radius));
  return x * x * (3 - 2 * x);
}
export function wallDucking(distance: number, radius: number): number {
  return 1 - 0.4 * distanceFade(distance, radius);
}
