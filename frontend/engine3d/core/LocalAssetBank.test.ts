import assert from "node:assert/strict";
import { test } from "node:test";
import { LocalAssetBank } from "./LocalAssetBank.ts";
import { syntheticGlb } from "./glbFixture.ts";

test("retains a model after its preview closes, dedupes content and isolates caller bytes", async () => {
  const bank = new LocalAssetBank();
  const original = syntheticGlb();
  const added = await bank.register("first.glb", original);
  new Uint8Array(original)[0] = 0;
  const duplicate = await bank.register("renamed.glb", syntheticGlb());
  assert.equal(added.id, duplicate.id);
  assert.equal(bank.list().length, 1);
  assert.equal(duplicate.name, "first.glb");
  const copy = bank.read(added.id)!;
  assert.equal(new DataView(copy).getUint32(0, true), 0x46546c67);
  new Uint8Array(copy)[0] = 0;
  assert.equal(new DataView(bank.read(added.id)!).getUint32(0, true), 0x46546c67);
  assert.equal(bank.remove(added.id), true);
  assert.equal(bank.read(added.id), null);
});

test("validates content, caps objects, and leaves previous entries unchanged on refusal", async () => {
  const bank = new LocalAssetBank(1);
  await assert.rejects(bank.register("not.glb", new ArrayBuffer(2)), /GLB/);
  const added = await bank.register("first.glb", syntheticGlb());
  const second = syntheticGlb((json) => { json.asset.generator = "different"; });
  await assert.rejects(bank.register("second.glb", second), /容量/);
  assert.deepEqual(bank.list().map((entry) => entry.id), [added.id]);
});

test("retains first declared unit and exports bounded, detached verified metadata only", async () => {
  const bank = new LocalAssetBank();
  const raw = syntheticGlb();
  const first = await bank.register("millimetres.glb", raw, "mm");
  const duplicate = await bank.register("metres.glb", raw, "m");
  assert.deepEqual(duplicate, first);
  assert.equal(first.sourceUnit, "mm");
  assert.deepEqual(first.dimensionsMetres, [0.002, 0.002, 0.002]);
  assert.equal(first.checkUnits, false);
  assert.match(first.id, /^[a-f0-9]{64}$/);
  const manifest = bank.manifest();
  assert.equal(manifest.format, "might-local-asset-metadata-v1");
  assert.equal(manifest.assets[0].metrics.triangles, 4);
  assert.equal(manifest.assets[0].size, raw.byteLength);
  manifest.assets[0].metrics.dimensions[0] = 900;
  assert.deepEqual(bank.manifest().assets[0], first);
  assert.equal(Object.hasOwn(manifest.assets[0], "bytes"), false);
  assert.equal(Object.hasOwn(manifest.assets[0], "url"), false);
  assert.equal(bank.remove(first.id), true);
  assert.deepEqual(bank.manifest().assets, []);
  await assert.rejects(bank.register("bad.glb", raw, "unsupported"), /单位/);
  await assert.rejects(bank.register("https://example.test/model.glb", raw), /GLB/);
});
