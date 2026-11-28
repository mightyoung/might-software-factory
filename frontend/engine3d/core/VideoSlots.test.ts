import assert from "node:assert/strict";
import { test } from "node:test";
import { VideoSlots } from "./VideoSlots.ts";

test("hard cap evicts least recently used first and releases exactly once", () => {
  const releases: string[] = [];
  let starts = 0;
  const deck = new VideoSlots((id) => { starts++; return () => releases.push(id); });
  for (const id of ["a", "b", "c", "d"]) deck.open(id, 0);
  deck.open("a", 1);
  deck.open("e", 2);
  assert.deepEqual(deck.ids(), ["c", "d", "a", "e"]);
  assert.equal(starts, 5);
  assert.deepEqual(releases, ["b"]);
  deck.close("e");
  deck.close("e");
  deck.closeAll();
  assert.equal(releases.length, 5);
});

test("hidden resources stay within their grace and expire without repeated hide resetting the clock", () => {
  const releases: string[] = [];
  const deck = new VideoSlots((id) => () => releases.push(id));
  deck.open("a", 0);
  deck.open("b", 0);
  deck.setVisible("a", false, 100);
  deck.setVisible("a", false, 10000);
  deck.setVisible("b", false, 10000);
  deck.setVisible("b", true, 12000);
  deck.sweep(15099);
  assert.deepEqual(releases, []);
  deck.sweep(15100);
  assert.deepEqual(releases, ["a"]);
  deck.closeAll();
  assert.deepEqual(releases, ["a", "b"]);
});
