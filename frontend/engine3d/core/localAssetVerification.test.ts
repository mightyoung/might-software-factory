import assert from "node:assert/strict";
import { test } from "node:test";
import { LocalAssetBank } from "./LocalAssetBank.ts";
import { syntheticGlb } from "./glbFixture.ts";
import { MAX_MANIFEST_BYTES, verifyLocalAssetReference } from "./localAssetVerification.ts";

test("matches actual SHA-256 and recomputes GLB metrics in the manifest's declared unit", async () => {
  const bank = new LocalAssetBank();
  const bytes = syntheticGlb();
  const original = await bank.register("original.glb", bytes, "mm");
  const other = await bank.register("other.glb", syntheticGlb((doc) => { doc.asset.generator = "partial"; }));
  assert.notEqual(other.id, original.id);
  assert.deepEqual(await verifyLocalAssetReference(JSON.stringify(bank.manifest()), bytes), original);
  assert.equal(bank.list().length, 2);
});

test("rejects changed binary, metadata mismatches, unknown fields and oversized manifests", async () => {
  const bank = new LocalAssetBank();
  const bytes = syntheticGlb();
  await bank.register("original.glb", bytes, "mm");
  const verify = (change: (manifest: ReturnType<LocalAssetBank["manifest"]>) => void) => {
    const manifest = bank.manifest();
    change(manifest);
    return verifyLocalAssetReference(JSON.stringify(manifest), bytes);
  };
  await assert.rejects(verifyLocalAssetReference(JSON.stringify(bank.manifest()), syntheticGlb((doc) => {
    doc.asset.generator = "different bytes";
  })), /SHA-256/);
  await assert.rejects(verify(({ assets }) => { assets[0].metrics.vertices++; }), /指标/);
  await assert.rejects(verify(({ assets }) => { assets[0].size++; }), /指标/);
  await assert.rejects(verify(({ assets }) => { assets[0].sourceUnit = "m"; }), /指标/);
  await assert.rejects(verify(({ assets }) => { assets.push(structuredClone(assets[0])); }), /重复/);
  await assert.rejects(verify(({ assets }) => { Object.assign(assets[0], { url: "https://example.test/asset" }); }), /契约/);
  await assert.rejects(verifyLocalAssetReference(" ".repeat(MAX_MANIFEST_BYTES + 1), bytes), /16 KiB/);
});
