import { expect, test } from "bun:test";
import { parseGLB, validateAssets } from "../validate-assets";

test("every delivered GLB meets the asset catalog contract", async () => {
  const report = await validateAssets();
  expect(report.variants.map((variant) => variant.id)).toEqual(["microphone", "news-microphone", "megaphone", "banana"]);
  expect(report.variants.every((variant) => variant.status === "passed")).toBe(true);
});

test("GLB parser rejects truncation and incorrect chunk lengths", async () => {
  const raw = Buffer.from(await Bun.file(new URL("../../assets/microphone/microphone.glb", import.meta.url)).arrayBuffer());
  expect(() => parseGLB(raw.subarray(0, 12))).toThrow();
  expect(() => parseGLB(raw.subarray(0, raw.length - 4))).toThrow();
  const invalid = Buffer.from(raw);
  invalid.writeUInt32LE(0, 20 + invalid.readUInt32LE(12));
  expect(() => parseGLB(invalid)).toThrow();
});
