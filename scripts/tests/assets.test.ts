import { expect, test } from "bun:test";
import { parseGLB, validateAssets, validateVariant } from "../validate-assets";
import { brandGLB, brandNewsMic, providers } from "../brand-news-mic";
import { prepareWalkieTalkie, walkieTalkie } from "../prepare-walkie-talkie";
import { copyFile, mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

/** Splits a branded GLB into its flag PNG and everything else, with flag-length-dependent offsets removed. */
function splitFlag(raw: Buffer) {
  const { data, binary } = parseGLB(raw);
  const images = data.images!.filter((image) => image.name === "Provider flag");
  expect(images).toHaveLength(1);
  const view = data.bufferViews[images[0].bufferView!];
  const start = view.byteOffset ?? 0, end = start + view.byteLength;
  const flag = Buffer.from(binary.subarray(start, end));
  const rest = Buffer.concat([binary.subarray(0, start), binary.subarray(end + (4 - view.byteLength % 4) % 4)]);
  view.byteLength = 0;
  for (const other of data.bufferViews) delete other.byteOffset;
  data.buffers[0].byteLength = 0;
  return { flag, json: data, rest };
}

test("news mic branding writes one valid GLB per AI provider and is safe to rerun after export", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vrcode-mic-branding-"));
  try {
    for (const name of ["news-microphone.glb", "catalog.json", ...providers.map((provider) => provider.flag)]) {
      await copyFile(new URL(`../../assets/voice-props/${name}`, import.meta.url), join(directory, name));
    }
    const read = async (name: string) => Buffer.from(await Bun.file(join(directory, name)).arrayBuffer());
    const readAll = () => Promise.all(providers.map((provider) => read(`news-microphone-${provider.id}.glb`)));
    const base = await read("news-microphone.glb");
    await brandNewsMic(directory);
    const first = await readAll(), firstCatalog = await read("catalog.json");
    await brandNewsMic(directory);
    const second = await readAll();
    expect((await read("news-microphone.glb")).equals(base)).toBe(true);
    expect(second.every((glb, i) => glb.equals(first[i]))).toBe(true);
    expect((await read("catalog.json")).equals(firstCatalog)).toBe(true);

    const item = JSON.parse(firstCatalog.toString()).variants.find((variant: { id: string }) => variant.id === "news-microphone");
    expect(Object.keys(item.logos)).toEqual(["claude", "openai", "gemini"]);
    expect(item.license).toBe("original vrcode geometry; Claude logo belongs to Anthropic, OpenAI logo belongs to OpenAI, Gemini logo belongs to Google");
    const parts = await Promise.all(providers.map(async (provider, i) => {
      expect(item.logos[provider.id]).toEqual({ file: `news-microphone-${provider.id}.glb`, flag: provider.flag, bytes: first[i].length });
      const { data } = parseGLB(first[i]);
      expect(data.nodes.filter((node) => node.name?.startsWith("Provider_Logo_")).length).toBe(4);
      expect(data.nodes.some((node) => node.name?.startsWith("NEWS_Lettering_") || node.name?.startsWith("OpenAI_Blossom_"))).toBe(false);
      const png = await read(provider.flag);
      // Rebranding a variant reproduces it, so the output does not depend on which flag the base export carried.
      expect(brandGLB(first[i], png).glb.equals(first[i])).toBe(true);
      const part = splitFlag(first[i]);
      expect(part.flag.equals(png)).toBe(true);
      return part;
    }));
    expect(new Set(first.map((glb) => glb.toString("base64"))).size).toBe(providers.length);
    for (const part of parts.slice(1)) {
      expect(part.json).toEqual(parts[0].json);
      expect(part.rest.equals(parts[0].rest)).toBe(true);
    }
    const result = await validateVariant(item, directory);
    expect(result.status).toBe("passed");
    expect(result.logos!.map((logo) => [logo.id, logo.status])).toEqual(providers.map((provider) => [provider.id, "passed"]));
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("the walkie-talkie is prepared reproducibly from its source download", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vrcode-walkie-talkie-"));
  try {
    await mkdir(join(directory, "source"));
    for (const name of [walkieTalkie.source, "catalog.json"]) {
      await copyFile(new URL(`../../assets/voice-props/${name}`, import.meta.url), join(directory, name));
    }
    const read = async (name: string) => Buffer.from(await Bun.file(join(directory, name)).arrayBuffer());
    const entry = await prepareWalkieTalkie(directory);
    const first = await read(walkieTalkie.file);
    expect((await prepareWalkieTalkie(directory)).bytes).toBe(first.length);
    expect((await read(walkieTalkie.file)).equals(first)).toBe(true);
    const ids = (JSON.parse((await read("catalog.json")).toString()).variants as { id: string }[]).map((item) => item.id);
    expect(ids.filter((id) => id === walkieTalkie.id)).toHaveLength(1);
    expect(ids.indexOf(walkieTalkie.id)).toBe(ids.indexOf("news-microphone") + 1);
    expect((await validateVariant(entry, directory)).status).toBe("passed");
    // The checked-in prop is this output (the texture resize is the macOS sips tool, so allow its byte count to differ).
    const delivered = (await Bun.file(new URL("../../assets/voice-props/catalog.json", import.meta.url)).json()).variants
      .find((item: { id: string }) => item.id === walkieTalkie.id);
    expect({ ...delivered, bytes: 0 }).toEqual({ ...entry, bytes: 0 });
    const { data } = parseGLB(first);
    expect(data.nodes.find((node) => node.name === walkieTalkie.gripNode)?.children).toHaveLength(2);
    expect((data.asset as { copyright?: string }).copyright).toContain("CC BY 3.0");
  } finally { await rm(directory, { recursive: true, force: true }); }
});

test("every delivered GLB meets the asset catalog contract", async () => {
  const report = await validateAssets();
  expect(report.variants.map((variant) => variant.id)).toEqual(["microphone", "news-microphone", "walkie-talkie", "megaphone", "banana"]);
  expect(report.variants.every((variant) => variant.status === "passed")).toBe(true);
  const logos = report.variants.find((variant) => variant.id === "news-microphone")!.logos!;
  expect(logos.map((logo) => [logo.id, logo.file, logo.status])).toEqual(
    providers.map((provider) => [provider.id, `news-microphone-${provider.id}.glb`, "passed"]),
  );
});

test("the megaphone's bell faces +Z, away from the wearer, and its mouthpiece -Z", async () => {
  const { data } = parseGLB(Buffer.from(await Bun.file(new URL("../../assets/voice-props/megaphone.glb", import.meta.url)).arrayBuffer()));
  const position = data.accessors[data.meshes[0].primitives[0].attributes.POSITION] as { min: number[]; max: number[] };
  // The bell's rim is 21 cm in front of the grip; the mouthpiece end is 7 cm behind it.
  expect(position.max[2]).toBeGreaterThan(0.2);
  expect(position.min[2]).toBeGreaterThan(-0.1);
  expect(data.nodes.find((node) => node.name === "PROP_SpeechAnchor_megaphone")!.translation![2]).toBeLessThan(0);
});

test("GLB parser rejects truncation and incorrect chunk lengths", async () => {
  const raw = Buffer.from(await Bun.file(new URL("../../assets/microphone/microphone.glb", import.meta.url)).arrayBuffer());
  expect(() => parseGLB(raw.subarray(0, 12))).toThrow();
  expect(() => parseGLB(raw.subarray(0, raw.length - 4))).toThrow();
  const invalid = Buffer.from(raw);
  invalid.writeUInt32LE(0, 20 + invalid.readUInt32LE(12));
  expect(() => parseGLB(invalid)).toThrow();
});
