import assert from "node:assert/strict";
import { test } from "node:test";
import { createPlayerSnapshot, parsePlayerSnapshot } from "./playerSnapshot.ts";

const draft = JSON.stringify({ snapshotVersion: 1,
  scene: { schemaVersion: 1, screenId: "test-player", devices: [] },
  floorplan: { walls: [], zones: [] } });

test("creates a versioned local playback file distinct from Studio draft", async () => {
  const json = await createPlayerSnapshot(draft);
  const next = await parsePlayerSnapshot(json);
  assert.equal(next.scene.screenId, "test-player");
  await assert.rejects(parsePlayerSnapshot(draft), /放映/);
});

test("rejects mutated content and unsafe fields without treating a digest as authorization", async () => {
  const json = await createPlayerSnapshot(draft);
  const value = JSON.parse(json);
  const malicious = { ...value, draft: value.draft.replace("test-player", "other-player") };
  await assert.rejects(parsePlayerSnapshot(JSON.stringify(malicious)), /摘要/);
  await assert.rejects(parsePlayerSnapshot(JSON.stringify({ ...value, url: "https://example.com" })), /放映/);
  await assert.rejects(createPlayerSnapshot('{"bad": true}'));
});
