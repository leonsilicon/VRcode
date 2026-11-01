#!/usr/bin/env bun
/**
 * Prepare the walkie-talkie voice prop from its Poly Pizza download, without an asset editor: scale
 * it to the prop size, put its grip root in the fist and a speech anchor on its speaker, add the
 * tangents every prop carries, shrink its texture to 1k, and record it in catalog.json.
 *
 * The source stands on its base with the antenna up (+Y, as the props expect) and its speaker front
 * toward +Z. On the headset a prop's −Z faces the wearer (the megaphone's mouthpiece is at −Z; see
 * assets/voice-props/README.md), so the walkie-talkie turns 180° about its grip axis to face them.
 */
import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { type Accessor, NodeIO, type vec3 } from "@gltf-transform/core";
import { dedup, prune, unweld, weld } from "@gltf-transform/functions";
import { generateTangents } from "mikktspace";
import { repoRoot, run } from "./lib/process";

export const walkieTalkie = {
  id: "walkie-talkie",
  label: "Walkie-talkie",
  source: "source/walkie-talkie-poly.glb",
  file: "walkie-talkie.glb",
  gripNode: "PROP_Grip_walkie_talkie",
  speechAnchorNode: "PROP_SpeechAnchor_walkie_talkie",
} as const;

/** Antenna tip to base, like the 20 cm banana; the body is then about 9 × 5.6 × 4.5 cm. */
const HEIGHT_M = 0.2;
// Measured in the source's own units: the body (without its knobs and antenna) spans y 0–6 around
// the x = z = 0 axis, and its three speaker slots sit on the +Z front around y = 3.9.
const BODY_TOP = 6;
/** The fist's hollow, as a fraction of the body's height: the hand holds the lower half, speaker free. */
const GRIP = 0.45;
const SPEAKER_Y = 3.9;
const TEXTURE_PX = 1024;
const ATTRIBUTION = "\"Walkie talkie\" by Poly by Google (https://poly.pizza/m/aYdn7ZZaYqa), CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/)";

function points(position: Accessor) {
  return Array.from({ length: position.getCount() }, (_, i) => position.getElement(i, [0, 0, 0]) as vec3);
}

async function resizePng(png: Uint8Array, size: number) {
  const directory = await mkdtemp(join(tmpdir(), "vrcode-walkie-talkie-"));
  try {
    await Bun.write(join(directory, "in.png"), png);
    await run(["sips", "-s", "format", "png", "-Z", String(size), join(directory, "in.png"), "--out", join(directory, "out.png")], { timeout: 60_000 });
    return new Uint8Array(await Bun.file(join(directory, "out.png")).arrayBuffer());
  } finally { await rm(directory, { recursive: true, force: true }); }
}

/** Writes `walkie-talkie.glb` from the source download and upserts its catalog entry (after the news microphone). */
export async function prepareWalkieTalkie(directory = resolve(repoRoot, "assets/voice-props")) {
  const io = new NodeIO();
  const document = await io.read(resolve(directory, walkieTalkie.source));
  const root = document.getRoot();
  assert.equal(root.listMeshes().length, 1, "One source mesh");
  assert.equal(root.listNodes().length, 1, "One source node");
  const [mesh] = root.listMeshes(), [model] = root.listNodes(), [scene] = root.listScenes();
  const primitives = mesh.listPrimitives();
  assert.equal(primitives.length, 1, "One source primitive");
  const position = primitives[0].getAttribute("POSITION")!, normal = primitives[0].getAttribute("NORMAL")!;

  // Measure in source units, then move the grip to the origin, turn the front to −Z and scale to metres.
  const source = points(position);
  const low = Math.min(...source.map((p) => p[1])), high = Math.max(...source.map((p) => p[1]));
  const scale = HEIGHT_M / (high - low);
  const pivot: vec3 = [0, low + GRIP * (BODY_TOP - low), 0];
  const front = source.filter(([x, y]) => Math.abs(x) < 1 && Math.abs(y - SPEAKER_Y) < 0.4);
  assert(front.length > 0, "Speaker front vertices");
  const frontZ = Math.max(...front.map((p) => p[2]));
  const turn = ([x, y, z]: vec3): vec3 => [-x, y, -z]; // 180° about +Y
  const place = (p: vec3) => turn(p.map((value, axis) => (value - pivot[axis]) * scale) as vec3);
  source.forEach((p, i) => position.setElement(i, place(p)));
  points(normal).forEach((n, i) => normal.setElement(i, turn(n)));
  const anchor = place([0, SPEAKER_Y, frontZ]);

  model.setName("WalkieTalkie");
  mesh.setName("WalkieTalkie");
  root.listMaterials().forEach((material) => material.setName("WalkieTalkie"));
  scene.removeChild(model);
  const grip = document.createNode(walkieTalkie.gripNode)
    .addChild(model)
    .addChild(document.createNode(walkieTalkie.speechAnchorNode).setTranslation(anchor));
  scene.addChild(grip);

  for (const texture of root.listTextures()) {
    assert.equal(texture.getMimeType(), "image/png", "PNG source texture");
    texture.setImage(await resizePng(texture.getImage()!, TEXTURE_PX)).setName("WalkieTalkie_ColorBase");
  }
  // MikkTSpace tangents need unwelded triangles; weld afterwards merges only identical vertices again.
  // glTF Transform's tangents() only serves primitives with a normal map, which this one lacks, yet
  // every prop ships tangents (validate-assets.ts), so call MikkTSpace directly.
  await document.transform(unweld());
  for (const primitive of mesh.listPrimitives()) {
    const array = (semantic: string) => {
      const values = primitive.getAttribute(semantic)?.getArray();
      assert(values instanceof Float32Array, `Float ${semantic}`);
      return values;
    };
    const tangent = generateTangents(array("POSITION"), array("NORMAL"), array("TEXCOORD_0"));
    primitive.setAttribute("TANGENT", document.createAccessor().setType("VEC4").setArray(tangent).setBuffer(root.listBuffers()[0]));
  }
  // keepLeaves: the speech anchor is an empty leaf node. keepAttributes: no normal map uses the tangents.
  await document.transform(weld(), dedup(), prune({ keepLeaves: true, keepAttributes: true }));
  const asset = root.getAsset();
  asset.generator = "vrcode scripts/prepare-walkie-talkie.ts (glTF Transform)";
  // CC BY 3.0 asks for attribution; the shipped file carries it (see assets/voice-props/CREDITS.md).
  asset.copyright = `${ATTRIBUTION}; scaled, re-centred on its grip, tangents added and texture resized by vrcode`;
  const glb = await io.writeBinary(document);
  await Bun.write(resolve(directory, walkieTalkie.file), glb);

  const placed = points(document.getRoot().listMeshes()[0].listPrimitives()[0].getAttribute("POSITION")!);
  const dimensions = [0, 1, 2].map((axis) => Math.max(...placed.map((p) => p[axis])) - Math.min(...placed.map((p) => p[axis])));
  const triangles = document.getRoot().listMeshes()[0].listPrimitives().reduce((sum, p) => sum + p.getIndices()!.getCount() / 3, 0);
  const round = (value: number) => Math.round(value * 1e6) / 1e6 || 0;
  const entry = {
    id: walkieTalkie.id,
    label: walkieTalkie.label,
    file: walkieTalkie.file,
    gripNode: walkieTalkie.gripNode,
    speechAnchorNode: walkieTalkie.speechAnchorNode,
    speechAnchorM: anchor.map(round),
    dimensionsM: dimensions.map(round),
    triangles,
    materials: root.listMaterials().length,
    bytes: glb.length,
    license: "CC-BY-3.0",
    author: "Poly by Google",
    source: "https://poly.pizza/m/aYdn7ZZaYqa",
    indicator: "shared app UI; no built-in emissive indicator",
    handFit: "initial grip placement; headset calibration pending",
  };
  const catalogPath = resolve(directory, "catalog.json");
  const catalog = await Bun.file(catalogPath).json() as { variants: { id: string }[] };
  const index = catalog.variants.findIndex((item) => item.id === walkieTalkie.id);
  if (index >= 0) catalog.variants[index] = entry;
  else catalog.variants.splice(catalog.variants.findIndex((item) => item.id === "news-microphone") + 1, 0, entry);
  await Bun.write(catalogPath, JSON.stringify(catalog, null, 2) + "\n");
  return entry;
}

if (import.meta.main) {
  if (Bun.argv.length > 2) {
    console.log(`Usage: bun scripts/prepare-walkie-talkie.ts
Writes assets/voice-props/${walkieTalkie.file} from ${walkieTalkie.source} and records it in catalog.json.`);
    if (!Bun.argv.slice(2).every((arg) => arg === "--help")) process.exitCode = 1;
  } else console.log(JSON.stringify(await prepareWalkieTalkie(), null, 2));
}
