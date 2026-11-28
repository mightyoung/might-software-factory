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
