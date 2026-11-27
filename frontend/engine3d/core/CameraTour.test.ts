import assert from "node:assert/strict";
import { test } from "node:test";
import { CameraTour } from "./CameraTour.ts";

test("advances deterministic authored order with explicit play and resets when changing files", () => {
  const tour = new CameraTour();
  tour.replace(["a", "b", "c"]);
  assert.equal(tour.current(), null);
  assert.equal(tour.play(), "a");
  assert.equal(tour.advance(), "b");
  assert.equal(tour.advance(), "c");
  assert.equal(tour.advance(), null);
  assert.equal(tour.playing, false);
  assert.equal(tour.play(), "a");
  tour.replace(["d"]);
  assert.equal(tour.current(), null);
  assert.equal(tour.playing, false);
});

test("manual selection interrupts playback and rejected IDs leave state unchanged", () => {
  const tour = new CameraTour();
  tour.replace(["a", "b"]);
  assert.equal(tour.play(), "a");
  assert.equal(tour.select("z"), false);
  assert.equal(tour.playing, true);
  assert.equal(tour.select("b"), true);
  assert.equal(tour.playing, false);
  assert.equal(tour.current(), "b");
  assert.equal(tour.advance(), null);
});
