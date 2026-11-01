#!/usr/bin/env bun
/** Write one news mic per AI provider, each showing that provider's flag, from the exported base GLB. */
import assert from "node:assert/strict";
import { resolve } from "node:path";
import { parseGLB } from "./validate-assets";
import { repoRoot } from "./lib/process";

/** The providers offered under Settings → AI mic → AI provider. The app loads `news-microphone-<id>.glb`. */
export const providers = [
  { id: "claude", flag: "claude-flag.png", owner: "Anthropic", mark: "Claude" },
  { id: "openai", flag: "openai-flag.png", owner: "OpenAI", mark: "OpenAI" },
  { id: "gemini", flag: "gemini-flag.png", owner: "Google", mark: "Gemini" },
] as const;
export type Logo = { file: string; flag: string; bytes: number };

const logoMaterial = "Provider_Logo", logoImage = "Provider flag";
// Earlier exports and brandings named the flag faces after NEWS lettering or the OpenAI Blossom.
const logoMaterials = [logoMaterial, "NEWS_Lettering", "OpenAI_Blossom"];
const logoNodes = [logoMaterial, "NEWS_Lettering", "OpenAI_Blossom"];
const logoImages = [logoImage, "OpenAI flag"];

/** Returns a copy of `base` with `png` on the four flag faces; the result does not depend on the flag `base` already had. */
export function brandGLB(base: Buffer, png: Buffer) {
  assert.equal(png.subarray(0, 8).toString("hex"), "89504e470d0a1a0a", "Flag is a PNG");
  const { data, binary } = parseGLB(base);
  // The parser intentionally exposes only the validation subset of glTF.
  const gltf = data as typeof data & {
    textures: { source: number; sampler?: number }[];
    samplers: object[];
  };
  const buffers = gltf.bufferViews.map((view) => binary.subarray(view.byteOffset ?? 0, (view.byteOffset ?? 0) + view.byteLength));
  function view(buffer: Buffer, target?: number) {
    const index = buffers.length;
    buffers.push(buffer);
    gltf.bufferViews.push({ buffer: 0, byteLength: buffer.length, ...(target ? { target } : {}) });
    return index;
  }
  function attribute(values: number[], type: string, size: number) {
    const index = gltf.accessors.length;
    const array = new Float32Array(values);
    gltf.accessors.push({ bufferView: view(Buffer.from(array.buffer), 34962), componentType: 5126, count: values.length / size, type,
      ...(type === "VEC3" ? {
        min: [0, 1, 2].map((axis) => Math.min(...values.filter((_, i) => i % 3 === axis))),
        max: [0, 1, 2].map((axis) => Math.max(...values.filter((_, i) => i % 3 === axis))),
      } : {}),
    });
    return index;
  }
  let imageIndex = gltf.images!.findIndex((image) => logoImages.includes((image as { name?: string }).name ?? ""));
  if (imageIndex < 0) imageIndex = gltf.images!.length;
  gltf.images![imageIndex] = { bufferView: view(png), mimeType: "image/png", ...{ name: logoImage } };
  let textureIndex = gltf.textures.findIndex((texture) => texture.source === imageIndex);
  if (textureIndex < 0) {
    textureIndex = gltf.textures.length;
    const sampler = gltf.samplers.length;
    gltf.samplers.push({ magFilter: 9729, minFilter: 9987, wrapS: 33071, wrapT: 33071 });
    gltf.textures.push({ source: imageIndex, sampler });
  }
  const materials = gltf.materials as { name: string; pbrMetallicRoughness: object }[];
  const logo = materials.findIndex((material) => logoMaterials.includes(material.name));
  const flag = materials.findIndex((material) => ["NEWS_FlagRed", "NEWS_FlagBlack"].includes(material.name));
  assert(logo >= 0 && flag >= 0, "Expected news mic flag and logo materials");
  materials[logo] = { name: logoMaterial, pbrMetallicRoughness: { baseColorTexture: { index: textureIndex }, metallicFactor: 0, roughnessFactor: 0.65 } };
  materials[flag] = { name: "NEWS_FlagBlack", pbrMetallicRoughness: { baseColorFactor: [0, 0, 0, 1], metallicFactor: 0, roughnessFactor: 0.65 } };
  // Replace the raised NEWS letters (or an earlier logo) with a correctly oriented square on each vertical face.
  for (let side = 0; side < 4; side++) {
    const node = gltf.nodes.find((node) => logoNodes.some((prefix) => node.name === `${prefix}_${side + 1}`));
    assert(node?.mesh !== undefined, `Flag face ${side + 1}`);
    node.name = `${logoMaterial}_${side + 1}`;
    const angle = side * Math.PI / 2, c = Math.cos(angle), s = Math.sin(angle);
    const distance = side % 2 === 0 ? 0.02815 : 0.03115;
    const positions: number[] = [], normals: number[] = [], tangents: number[] = [];
    for (const [x, y] of [[-0.026, -0.026], [0.026, -0.026], [0.026, 0.026], [-0.026, 0.026]]) {
      positions.push(x * c + distance * s, 0.078 + y, -x * s + distance * c);
      normals.push(s, 0, c);
      tangents.push(c, 0, -s, 1);
    }
    const indices = gltf.accessors.length;
    gltf.accessors.push({ bufferView: view(Buffer.from(new Uint16Array([0, 1, 2, 0, 2, 3]).buffer), 34963), componentType: 5123, count: 6, type: "SCALAR" });
    gltf.meshes[node.mesh] = { ...{ name: node.name }, primitives: [{ indices, material: logo, attributes: {
      POSITION: attribute(positions, "VEC3", 3), NORMAL: attribute(normals, "VEC3", 3),
      TEXCOORD_0: attribute([0, 1, 1, 1, 1, 0, 0, 0], "VEC2", 2), TANGENT: attribute(tangents, "VEC4", 4),
    } }] };
  }
  // Compact unused lettering/previous branding buffers so the output does not depend on the input's flag.
  const usedAccessors = new Set(gltf.meshes.flatMap((mesh) => mesh.primitives.flatMap((p) => [p.indices, ...Object.values(p.attributes)])));
  const accessorMap = new Map([...usedAccessors].map((index, next) => [index, next]));
  gltf.accessors = [...usedAccessors].map((index) => gltf.accessors[index]);
  for (const mesh of gltf.meshes) for (const primitive of mesh.primitives) {
    primitive.indices = accessorMap.get(primitive.indices)!;
    for (const key of Object.keys(primitive.attributes)) primitive.attributes[key] = accessorMap.get(primitive.attributes[key])!;
  }
  const usedViews = new Set([...gltf.accessors.map((a) => a.bufferView), ...gltf.images!.map((image) => image.bufferView!)]);
  const viewMap = new Map([...usedViews].map((index, next) => [index, next]));
  let offset = 0;
  const chunks: Buffer[] = [];
  gltf.bufferViews = [...usedViews].map((index) => {
    const buffer = buffers[index], padding = Buffer.alloc((4 - buffer.length % 4) % 4);
    const result = { ...gltf.bufferViews[index], byteOffset: offset };
    chunks.push(buffer, padding);
    offset += buffer.length + padding.length;
    return result;
  });
  for (const accessor of gltf.accessors) accessor.bufferView = viewMap.get(accessor.bufferView)!;
  for (const image of gltf.images!) image.bufferView = viewMap.get(image.bufferView!)!;
  gltf.buffers[0].byteLength = offset;
  const rawJson = Buffer.from(JSON.stringify(gltf));
  const json = Buffer.concat([rawJson, Buffer.alloc((4 - rawJson.length % 4) % 4, 32)]);
  const header = Buffer.alloc(20), binHeader = Buffer.alloc(8);
  header.writeUInt32LE(0x46546c67); header.writeUInt32LE(2, 4);
  header.writeUInt32LE(28 + json.length + offset, 8);
  header.writeUInt32LE(json.length, 12); header.writeUInt32LE(0x4e4f534a, 16);
  binHeader.writeUInt32LE(offset); binHeader.writeUInt32LE(0x004e4942, 4);
  const glb = Buffer.concat([header, json, binHeader, ...chunks]);
  const triangles = gltf.meshes.reduce((total, mesh) => total + mesh.primitives.reduce((sum, p) => sum + gltf.accessors[p.indices].count / 3, 0), 0);
  return { glb, triangles };
}

/** Reads `news-microphone.glb` (left untouched) and writes `news-microphone-<provider>.glb` for every provider. */
export async function brandNewsMic(directory = resolve(repoRoot, "assets/voice-props")) {
  const base = Buffer.from(await Bun.file(resolve(directory, "news-microphone.glb")).arrayBuffer());
  const logos: Record<string, Logo> = {};
  let triangles: number | undefined;
  for (const provider of providers) {
    const png = Buffer.from(await Bun.file(resolve(directory, provider.flag)).arrayBuffer());
    const branded = brandGLB(base, png);
    assert(triangles === undefined || triangles === branded.triangles, "Logo variants share one geometry");
    triangles = branded.triangles;
    const file = `news-microphone-${provider.id}.glb`;
    await Bun.write(resolve(directory, file), branded.glb);
    logos[provider.id] = { file, flag: provider.flag, bytes: branded.glb.length };
  }
  const catalogPath = resolve(directory, "catalog.json");
  const catalog = await Bun.file(catalogPath).json();
  const index = catalog.variants.findIndex((item: { id: string }) => item.id === "news-microphone");
  assert(index >= 0, "Catalog has a news-microphone entry");
  const item = catalog.variants[index];
  item.bytes = base.length;
  item.triangles = triangles;
  item.license = `original vrcode geometry; ${providers.map((p) => `${p.mark} logo belongs to ${p.owner}`).join(", ")}`;
  // Keep the per-provider files next to the base file's own size.
  const entries = Object.entries(item).filter(([key]) => key !== "logos");
  entries.splice(entries.findIndex(([key]) => key === "bytes") + 1, 0, ["logos", logos]);
  catalog.variants[index] = Object.fromEntries(entries);
  await Bun.write(catalogPath, JSON.stringify(catalog, null, 2) + "\n");
  return { bytes: item.bytes, triangles, logos };
}

if (import.meta.main) {
  if (Bun.argv.length > 2) {
    console.log(`Usage: bun scripts/brand-news-mic.ts
Writes news-microphone-<provider>.glb (${providers.map((p) => p.id).join(", ")}) from news-microphone.glb,
each with the provider's flag on the four flag faces, and records them in the catalog entry.`);
    if (!Bun.argv.slice(2).every((arg) => arg === "--help")) process.exitCode = 1;
  } else console.log(JSON.stringify(await brandNewsMic(), null, 2));
}
