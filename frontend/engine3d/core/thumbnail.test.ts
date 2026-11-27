import assert from "node:assert/strict";
import { test } from "node:test";
import { paintPreviewThumbnail } from "./thumbnail.ts";

test("fits current frame within a fixed 128×96 thumbnail and leaves no stretched dimensions", () => {
  const calls: unknown[][] = [];
  const painter = {
    clearRect(...args: number[]) { calls.push(args); },
    drawImage(...args: unknown[]) { calls.push(args); },
  };
  const source = { width: 400, height: 200 } as HTMLCanvasElement;
  const target = { width: 128, height: 96, getContext: () => painter } as unknown as HTMLCanvasElement;
  assert.equal(paintPreviewThumbnail(source, target), true);
  assert.deepEqual(calls, [[0, 0, 128, 96], [source, 0, 16, 128, 64]]);
});

test("fails closed for zero source area, non-fixed destination or unavailable 2D context", () => {
  const source = { width: 0, height: 200 } as HTMLCanvasElement;
  const target = { width: 128, height: 96, getContext: () => null } as unknown as HTMLCanvasElement;
  assert.equal(paintPreviewThumbnail(source, target), false);
  source.width = 400;
  assert.equal(paintPreviewThumbnail(source, target), false);
  target.width = 4096;
  target.getContext = (() => assert.fail("must not obtain context for oversized destination")) as typeof target.getContext;
  assert.equal(paintPreviewThumbnail(source, target), false);
});
