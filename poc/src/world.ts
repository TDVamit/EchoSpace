import * as THREE from "three";
import { seeded, type Category, type Point, type WorldObject } from "./types";
export interface World {
  scene: THREE.Scene;
  objects: WorldObject[];
  meshes: THREE.Mesh[];
  solids: THREE.Box3[];
  points: Point[];
  findPoint: (hit: THREE.Intersection) => Point | null;
  dispose: () => void;
}
export function createWorld(): World {
  const scene = new THREE.Scene();
  scene.background = new THREE.Color("#111b24");
  scene.fog = new THREE.Fog("#111b24", 28, 65);
  scene.add(new THREE.HemisphereLight(0xe9f5ff, 0x56626a, 2));
  const sun = new THREE.DirectionalLight(0xffe4b9, 2.8);
  sun.position.set(-5, 12, 3);
  scene.add(sun);
  const objects: WorldObject[] = [];
  const meshes: THREE.Mesh[] = [];
  const solids: THREE.Box3[] = [];
  const points: Point[] = [];
  let current: WorldObject;
  const material = (c: string, roughness = 0.8) =>
    new THREE.MeshStandardMaterial({
      color: c,
      roughness,
      side: THREE.DoubleSide,
    });
  const mats = {
    wall: material("#c6ccca"),
    floor: material("#3b4a50"),
    wood: material("#ab845b"),
    dark: material("#39494c"),
    fabric: material("#788989"),
    green: material("#588775"),
    metal: material("#bdc6c5"),
    door: material("#b3a07c"),
    hazard: material("#dc6b48"),
  };
  function object(id: string, name: string, category: Category) {
    current = { id, name, category, meshes: [], bounds: new THREE.Box3() };
    objects.push(current);
  }
  function mesh(
    geometry: THREE.BufferGeometry,
    mat: THREE.Material,
    x: number,
    y: number,
    z: number,
    solid = true,
    rotation = 0,
  ) {
    geometry.computeBoundingBox();
    const m = new THREE.Mesh(geometry, mat);
    m.position.set(x, y, z);
    m.rotation.y = rotation;
    m.updateMatrixWorld(true);
    m.userData.objectId = current.id;
    scene.add(m);
    meshes.push(m);
    current.meshes.push(m);
    const b = new THREE.Box3().setFromObject(m);
    current.bounds.union(b);
    if (solid) solids.push(b);
    return m;
  }
  function box(
    w: number,
    h: number,
    d: number,
    x: number,
    y: number,
    z: number,
    mat: THREE.Material,
    solid = true,
  ) {
    return mesh(new THREE.BoxGeometry(w, h, d), mat, x, y, z, solid);
  }
  function wall(
    id: string,
    w: number,
    h: number,
    d: number,
    x: number,
    y: number,
    z: number,
  ) {
    object(id, "Wall · " + id.replace("wall-", ""), "wall");
    box(w, h, d, x, y, z, mats.wall);
  }
  object("floor-main", "Main room floor", "floor");
  box(18, 0.2, 22, 0, -0.1, -4, mats.floor, false);
  object("floor-hall", "Corridor floor", "floor");
  box(4, 0.2, 13, 0, -0.1, -21.5, mats.floor, false);
  object("floor-studio", "Studio floor", "floor");
  box(14, 0.2, 11, 0, -0.1, -33.5, mats.floor, false);
  wall("wall-west", 0.25, 3.8, 22, -9, 1.9, -4);
  wall("wall-east", 0.25, 3.8, 22, 9, 1.9, -4);
  wall("wall-entry", 18, 3.8, 0.25, 0, 1.9, 7);
  wall("wall-north-left", 7, 3.8, 0.25, -5.5, 1.9, -15);
  wall("wall-north-right", 7, 3.8, 0.25, 5.5, 1.9, -15);
  wall("wall-lintel", 4, 1, 0.25, 0, 3.3, -15);
  wall("wall-hall-left", 0.25, 3.8, 13, -2, 1.9, -21.5);
  wall("wall-hall-right", 0.25, 3.8, 13, 2, 1.9, -21.5);
  wall("wall-studio-west", 0.25, 3.8, 11, -7, 1.9, -33.5);
  wall("wall-studio-east", 0.25, 3.8, 11, 7, 1.9, -33.5);
  wall("wall-studio-back", 14, 3.8, 0.25, 0, 1.9, -39);
  wall("wall-studio-front-left", 5, 3.8, 0.25, -4.5, 1.9, -28);
  wall("wall-studio-front-right", 5, 3.8, 0.25, 4.5, 1.9, -28);
  wall("wall-studio-lintel", 4, 1, 0.25, 0, 3.3, -28);
  // Ceiling surfaces stay in the map; translucent in the architectural inspection view.
  const ceiling = material("#a1aeac");
  object("ceiling-main", "Main room ceiling", "wall");
  box(18, 0.12, 22, 0, 3.85, -4, ceiling, false);
  object("ceiling-hall", "Corridor ceiling", "wall");
  box(4, 0.12, 13, 0, 3.85, -21.5, ceiling, false);
  object("ceiling-studio", "Studio ceiling", "wall");
  box(14, 0.12, 11, 0, 3.85, -33.5, ceiling, false);
  function frame(id: string, z: number) {
    object(
      id,
      z === -15 ? "Open doorway · corridor" : "Open doorway · studio",
      "door",
    );
    box(0.12, 2.8, 0.4, -1.93, 1.4, z, mats.door);
    box(0.12, 2.8, 0.4, 1.93, 1.4, z, mats.door);
    box(4, 0.12, 0.4, 0, 2.76, z, mats.door);
  }
  frame("door-corridor", -15);
  frame("door-studio", -28);
  function table(id: string, x: number, z: number, w = 2.6, d = 1.5) {
    object(
      id,
      id === "table-main" ? "Oak dining table" : "Studio worktable",
      "table",
    );
    box(w, 0.15, d, x, 0.93, z, mats.wood);
    for (const dx of [-w / 2 + 0.15, w / 2 - 0.15])
      for (const dz of [-d / 2 + 0.15, d / 2 - 0.15])
        box(0.1, 0.85, 0.1, x + dx, 0.43, z + dz, mats.dark);
  }
  table("table-main", -2.7, -3);
  table("table-studio", -2.8, -34, 3, 1.2);
  function chair(id: string, x: number, z: number, rot = 0) {
    object(id, "Chair " + id.split("-").pop(), "chair");
    const group = new THREE.Group();
    group.position.set(x, 0, z);
    group.rotation.y = rot;
    const add = (
      w: number,
      h: number,
      d: number,
      px: number,
      py: number,
      pz: number,
      mat: THREE.Material,
    ) => {
      group.updateMatrix();
      const pos = new THREE.Vector3(px, py, pz).applyMatrix4(group.matrix);
      return mesh(
        new THREE.BoxGeometry(w, h, d),
        mat,
        pos.x,
        pos.y,
        pos.z,
        true,
        rot,
      );
    };
    add(0.65, 0.13, 0.65, 0, 0.53, 0, mats.wood);
    add(0.65, 0.7, 0.09, 0, 0.92, 0.28, mats.wood);
    for (const xx of [-0.24, 0.24])
      for (const zz of [-0.24, 0.24])
        add(0.06, 0.48, 0.06, xx, 0.24, zz, mats.dark);
  }
  chair("chair-1", -2.7, -1.7, Math.PI);
  chair("chair-2", -2.7, -4.3);
  chair("chair-3", -4.65, -3, Math.PI / 2);
  chair("chair-4", -2.8, -32.5, Math.PI);
  object("sofa-main", "Lounge sofa", "sofa");
  box(3.5, 0.45, 1.2, 6, 0.45, -5, mats.fabric);
  box(3.5, 0.7, 0.24, 6, 0.98, -5.5, mats.fabric);
  box(0.2, 0.6, 1.2, 4.3, 0.82, -5, mats.fabric);
  box(0.2, 0.6, 1.2, 7.7, 0.82, -5, mats.fabric);
  object("table-coffee", "Coffee table", "table");
  box(2, 0.12, 1.1, 6, 0.48, -2.8, mats.wood);
  box(1.2, 0.4, 0.55, 6, 0.2, -2.8, mats.dark);
  object("cabinet-main", "Storage cabinet", "cabinet");
  box(3, 1.8, 0.6, -6, 0.9, -13.6, mats.wood);
  for (const x of [-6.75, -5.25])
    box(0.04, 0.8, 0.04, x, 1, -13.27, mats.metal);
  object("cabinet-studio", "Studio storage", "cabinet");
  box(1, 1.8, 3, 5.9, 0.9, -34, mats.dark);
  function plant(id: string, x: number, z: number) {
    object(id, "Potted plant", "plant");
    mesh(
      new THREE.CylinderGeometry(0.3, 0.22, 0.55, 12),
      mats.wood,
      x,
      0.28,
      z,
    );
    mesh(new THREE.CylinderGeometry(0.035, 0.05, 1.3, 6), mats.dark, x, 1, z);
    mesh(new THREE.IcosahedronGeometry(0.65, 1), mats.green, x, 1.5, z, false);
  }
  plant("plant-main", 7, -12);
  plant("plant-entry", -7, 4.8);
  plant("plant-studio", -5.8, -37.6);
  // A synthetic, initially unclassified booth for the simulated YOLO flow.
  object("unknown-studio-cabin", "Unidentified object", "unknown");
  box(2.2, 0.12, 2, 3.1, 0.08, -33.4, mats.dark, false);
  box(2.2, 0.12, 2, 3.1, 2.22, -33.4, mats.dark, false);
  box(0.12, 2.2, 2, 2.0, 1.16, -33.4, mats.fabric, false);
  box(0.12, 2.2, 2, 4.2, 1.16, -33.4, mats.fabric, false);
  box(2.2, 2.2, 0.12, 3.1, 1.16, -34.34, mats.fabric, false);
  box(0.62, 2.2, 0.12, 2.31, 1.16, -32.46, mats.fabric, false);
  box(0.62, 2.2, 0.12, 3.89, 1.16, -32.46, mats.fabric, false);
  box(1.0, 0.14, 0.14, 3.1, 2.18, -32.46, mats.wood, false);
  object("hazard-demo", "Hazard prop · simulated", "hazard");
  box(0.7, 0.45, 0.7, 1.12, 0.23, -23, mats.hazard);
  mesh(
    new THREE.ConeGeometry(0.28, 0.8, 7),
    new THREE.MeshStandardMaterial({
      color: "#ffbb64",
      emissive: "#c75a24",
      emissiveIntensity: 0.7,
    }),
    1.12,
    0.85,
    -23,
    false,
  );
  // A few ceiling lights and floor seams make room proportions readable without visual clutter.
  for (const z of [2, -7, -13, -18, -24, -31, -37]) {
    const light = new THREE.PointLight(0xe0f0f1, 12, 11, 2);
    light.position.set(0, 3.3, z);
    scene.add(light);
    const strip = new THREE.Mesh(
      new THREE.BoxGeometry(z > -15 || z < -28 ? 2.8 : 1.8, 0.02, 0.09),
      new THREE.MeshBasicMaterial({ color: "#e6f4ed" }),
    );
    strip.position.set(0, 3.74, z);
    scene.add(strip);
  }
  const grid = new THREE.GridHelper(18, 18, 0x647078, 0x48565d);
  grid.position.set(0, 0.005, -4);
  scene.add(grid);
  // Deterministic surface samples; all share IDs across scan and preloaded modes.
  const lookup = new Map<string, number[]>();
  const cell = 0.22;
  const key = (id: string, p: THREE.Vector3) =>
    `${id}:${Math.floor(p.x / cell)},${Math.floor(p.y / cell)},${Math.floor(p.z / cell)}`;
  const a = new THREE.Vector3(),
    b = new THREE.Vector3(),
    c = new THREE.Vector3(),
    ab = new THREE.Vector3(),
    ac = new THREE.Vector3(),
    normal = new THREE.Vector3();
  const rng = seeded(812357);
  for (const o of objects)
    for (const m of o.meshes) {
      const g = m.geometry;
      const pos = g.getAttribute("position");
      const indices = g.index;
      const count = indices ? indices.count : pos.count;
      for (let t = 0; t < count; t += 3) {
        a.fromBufferAttribute(pos, indices ? indices.getX(t) : t).applyMatrix4(
          m.matrixWorld,
        );
        b.fromBufferAttribute(
          pos,
          indices ? indices.getX(t + 1) : t + 1,
        ).applyMatrix4(m.matrixWorld);
        c.fromBufferAttribute(
          pos,
          indices ? indices.getX(t + 2) : t + 2,
        ).applyMatrix4(m.matrixWorld);
        ab.subVectors(b, a);
        ac.subVectors(c, a);
        normal.crossVectors(ab, ac);
        const area = normal.length() / 2;
        normal.normalize();
        const n = Math.max(1, Math.ceil(area / 0.028));
        for (let j = 0; j < n; j++) {
          const u = Math.sqrt(rng()),
            v = rng();
          const p = a
            .clone()
            .addScaledVector(ab, u * (1 - v))
            .addScaledVector(ac, u * v);
          const pt: Point = {
            id: points.length,
            position: p,
            normal: normal.clone(),
            objectId: o.id,
            category: o.category,
          };
          points.push(pt);
          const k = key(o.id, p);
          const bucket = lookup.get(k);
          if (bucket) bucket.push(pt.id);
          else lookup.set(k, [pt.id]);
        }
      }
    }
  const findPoint = (hit: THREE.Intersection) => {
    const id = hit.object.userData.objectId as string;
    let best: Point | null = null;
    let distance = 0.25 ** 2;
    const q = hit.point;
    const bx = Math.floor(q.x / cell),
      by = Math.floor(q.y / cell),
      bz = Math.floor(q.z / cell);
    for (let x = -1; x <= 1; x++)
      for (let y = -1; y <= 1; y++)
        for (let z = -1; z <= 1; z++) {
          const list = lookup.get(`${id}:${bx + x},${by + y},${bz + z}`);
          if (list)
            for (const i of list) {
              const d = points[i].position.distanceToSquared(q);
              if (d < distance) {
                best = points[i];
                distance = d;
              }
            }
        }
    return best;
  };
  return {
    scene,
    objects,
    meshes,
    solids,
    points,
    findPoint,
    dispose: () => {
      scene.traverse((o) => {
        if (o instanceof THREE.Mesh) {
          o.geometry.dispose();
          for (const m of Array.isArray(o.material) ? o.material : [o.material])
            m.dispose();
        }
      });
    },
  };
}
