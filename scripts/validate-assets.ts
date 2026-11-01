#!/usr/bin/env bun
/** Validate the actual runtime GLBs with Bun, without launching an asset editor. */
import assert from "node:assert/strict";
import { resolve } from "node:path";
import { repoRoot } from "./lib/process";

type Vec3 = [number, number, number];
type Prop = {
  id: string; file: string; gripNode: string; speechAnchorNode: string; speechAnchorM: number[];
  dimensionsM: number[]; triangles: number; materials: number; bytes: number;
};
type Accessor = { bufferView: number; byteOffset?: number; componentType: number; count: number; type: string; sparse?: unknown };
type Node = { name?: string; mesh?: number; children?: number[]; matrix?: number[]; translation?: number[]; rotation?: number[]; scale?: number[] };
type GLTF = {
  asset: { version: string }; scene?: number; scenes: { nodes: number[] }[]; nodes: Node[];
  buffers: { byteLength: number; uri?: string }[]; bufferViews: { buffer: number; byteOffset?: number; byteLength: number; byteStride?: number }[];
  images?: { uri?: string; bufferView?: number; mimeType?: string }[]; accessors: Accessor[];
  meshes: { primitives: { indices: number; attributes: Record<string, number>; mode?: number; material?: number }[] }[];
  materials: { alphaMode?: string }[]; extensionsRequired?: string[]; cameras?: unknown[]; animations?: unknown[]; skins?: unknown[];
};

export function parseGLB(raw: Buffer): { data: GLTF; binary: Buffer } {
  assert(raw.length >= 28, "Truncated GLB");
  assert.equal(raw.readUInt32LE(0), 0x46546c67, "GLB magic");
  assert.equal(raw.readUInt32LE(4), 2, "GLB version");
  assert.equal(raw.readUInt32LE(8), raw.length, "GLB declared length");
  const jsonLength = raw.readUInt32LE(12);
  assert.equal(raw.readUInt32LE(16), 0x4e4f534a, "First chunk must be JSON");
  assert.equal(jsonLength % 4, 0, "JSON chunk alignment");
  const binHeader = 20 + jsonLength;
  assert(binHeader + 8 <= raw.length, "Missing binary chunk");
  assert.equal(raw.readUInt32LE(binHeader + 4), 0x004e4942, "Second chunk must be BIN");
  assert.equal(binHeader + 8 + raw.readUInt32LE(binHeader), raw.length, "Binary chunk length");
  return { data: JSON.parse(raw.subarray(20, binHeader).toString()), binary: raw.subarray(binHeader + 8) };
}

const identity = [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1];
function multiply(a: number[], b: number[]) {
  return Array.from({ length: 16 }, (_, i) => {
    const row = i % 4, col = Math.floor(i / 4);
    return [0, 1, 2, 3].reduce((sum, k) => sum + a[k * 4 + row] * b[col * 4 + k], 0);
  });
}
function matrix(node: Node) {
  if (node.matrix) return node.matrix;
  const [x, y, z, w] = node.rotation ?? [0, 0, 0, 1];
  const [sx, sy, sz] = node.scale ?? [1, 1, 1];
  const [tx, ty, tz] = node.translation ?? [0, 0, 0];
  return [
    (1 - 2 * (y * y + z * z)) * sx, 2 * (x * y + z * w) * sx, 2 * (x * z - y * w) * sx, 0,
    2 * (x * y - z * w) * sy, (1 - 2 * (x * x + z * z)) * sy, 2 * (y * z + x * w) * sy, 0,
    2 * (x * z + y * w) * sz, 2 * (y * z - x * w) * sz, (1 - 2 * (x * x + y * y)) * sz, 0,
    tx, ty, tz, 1,
  ];
}
function transform(m: number[], v: Vec3): Vec3 {
  return [0, 1, 2].map((i) => m[i] * v[0] + m[4 + i] * v[1] + m[8 + i] * v[2] + m[12 + i]) as Vec3;
}
function near(actual: number[], expected: number[], label: string) {
  assert.equal(actual.length, expected.length, label);
  assert(actual.every((value, i) => Number.isFinite(value) && Math.abs(value - expected[i]) < 1e-6), `${label}: ${actual} != ${expected}`);
}

export async function validateProp(item: Prop, directory = resolve(repoRoot, "assets/voice-props")) {
  const raw = Buffer.from(await Bun.file(resolve(directory, item.file)).arrayBuffer());
  const { data, binary } = parseGLB(raw);
  assert.equal(raw.length, item.bytes, "Catalog file size");
  assert.equal(data.asset.version, "2.0");
  assert.equal(data.scenes.length, 1, "Exactly one scene");
  assert.equal(data.extensionsRequired?.length ?? 0, 0, "No required extensions");
  assert.equal(data.cameras?.length ?? 0, 0, "No runtime cameras");
  assert.equal(data.animations?.length ?? 0, 0, "No runtime animations");
  assert.equal(data.skins?.length ?? 0, 0, "No runtime skins");
  assert.equal(data.buffers.length, 1, "One embedded binary buffer");
  assert.equal(data.buffers[0].uri, undefined, "No external buffer");
  assert(data.buffers[0].byteLength <= binary.length && binary.length - data.buffers[0].byteLength < 4, "Embedded buffer length");
  for (const view of data.bufferViews) {
    assert.equal(view.buffer, 0);
    assert((view.byteOffset ?? 0) >= 0 && (view.byteOffset ?? 0) + view.byteLength <= data.buffers[0].byteLength, "Buffer view bounds");
  }
  for (const image of data.images ?? []) {
    assert.equal(image.uri, undefined, "Textures must be embedded");
    assert(image.bufferView !== undefined && data.bufferViews[image.bufferView], "Embedded texture buffer view");
    assert(["image/png", "image/jpeg"].includes(image.mimeType ?? ""), "Supported texture format");
  }
  const rootIndex = data.nodes.findIndex((node) => node.name === item.gripNode);
  assert(rootIndex >= 0, "Grip node exists");
  const root = data.nodes[rootIndex];
  assert.equal(root.mesh, undefined, "Grip is a transform node");
  near(matrix(root), identity, "Grip at origin with unit scale and rotation");
  assert.deepEqual(data.scenes[data.scene ?? 0].nodes, [rootIndex], "Grip is the only scene root");
  assert.equal(data.materials.length, item.materials, "Material count");
  const alphaModes = data.materials.map((m) => m.alphaMode ?? "OPAQUE");
  assert.deepEqual(alphaModes, Array(item.materials).fill("OPAQUE"), "Opaque prop materials");

  let triangles = 0, primitives = 0, anchorFound = false;
  const low: Vec3 = [Infinity, Infinity, Infinity], high: Vec3 = [-Infinity, -Infinity, -Infinity];
  const visited = new Set<number>();
  function visit(index: number, parent: number[]) {
    assert(!visited.has(index), "Node hierarchy cannot contain cycles or shared children");
    visited.add(index);
    const node = data.nodes[index];
    assert(node, "Node exists");
    const world = multiply(parent, matrix(node));
    if (node.name === item.speechAnchorNode) {
      near(transform(world, [0, 0, 0]), item.speechAnchorM, "Speech anchor");
      anchorFound = true;
    }
    if (node.mesh !== undefined) {
      for (const primitive of data.meshes[node.mesh].primitives) {
        primitives++;
        assert.equal(primitive.mode ?? 4, 4, "Explicit triangles");
        for (const attribute of ["POSITION", "NORMAL", "TEXCOORD_0", "TANGENT"]) assert(data.accessors[primitive.attributes[attribute]], attribute);
        const indices = data.accessors[primitive.indices];
        assert(indices && indices.type === "SCALAR" && indices.count % 3 === 0, "Triangle indices");
        triangles += indices.count / 3;
        const position = data.accessors[primitive.attributes.POSITION];
        assert.equal(position.componentType, 5126, "Float32 positions");
        assert.equal(position.type, "VEC3");
        assert.equal(position.sparse, undefined, "Dense positions");
        const view = data.bufferViews[position.bufferView];
        const stride = view.byteStride ?? 12;
        const offset = position.byteOffset ?? 0;
        assert(position.count > 0 && stride >= 12 && offset + (position.count - 1) * stride + 12 <= view.byteLength, "Position accessor bounds");
        for (let v = 0; v < position.count; v++) {
          const start = (view.byteOffset ?? 0) + offset + v * stride;
          const point = transform(world, [binary.readFloatLE(start), binary.readFloatLE(start + 4), binary.readFloatLE(start + 8)]);
          for (let axis = 0; axis < 3; axis++) {
            assert(Number.isFinite(point[axis]), "Finite mesh positions");
            low[axis] = Math.min(low[axis], point[axis]);
            high[axis] = Math.max(high[axis], point[axis]);
          }
        }
      }
    }
    for (const child of node.children ?? []) visit(child, world);
  }
  visit(rootIndex, identity);
  assert.equal(visited.size, data.nodes.length, "No orphan asset nodes");
  assert(anchorFound, "Speech anchor exists under grip");
  assert.equal(triangles, item.triangles, "Triangle count");
  const dimensions = high.map((value, i) => value - low[i]);
  near(dimensions, item.dimensionsM, "Actual transformed vertex dimensions");
  return { id: item.id, status: "passed", bytes: raw.length, triangles, primitives, alphaModes, dimensionsM: dimensions };
}

export async function validateAssets() {
  const catalog = await Bun.file(resolve(repoRoot, "assets/voice-props/catalog.json")).json() as { variants: Prop[] };
  return {
    status: "passed", runtime: `Bun ${Bun.version}`,
    variants: await Promise.all(catalog.variants.map((item) => validateProp(item))),
    checks: ["GLB structure and embedded buffers/textures", "Triangle and material counts", "Normals, UVs and tangents", "Grip origin and speech anchors", "Dimensions from actual transformed vertices", "Source alpha modes"],
    notVerified: ["Blender GLB re-import/render", "Mesh manifoldness and winding", "Quest runtime, hand fit and frame time"],
  };
}

if (import.meta.main) {
  if (Bun.argv.length > 2) {
    console.log("Usage: bun scripts/validate-assets.ts\nPrints a JSON validation report for the delivered GLBs.");
    if (!Bun.argv.slice(2).every((arg) => arg === "--help")) process.exitCode = 1;
  } else validateAssets().then((report) => console.log(JSON.stringify(report, null, 2))).catch((error) => { console.error(`validate-assets: ${error.message}`); process.exitCode = 1; });
}
