import assert from "node:assert/strict";
import { test } from "node:test";
import { createMeshoptWorkerClient, type MeshoptPort } from "./meshoptWorkerClient.ts";

test("owns transferred input and terminates superseded, completed and closed workers", async () => {
  const ports: Array<MeshoptPort & { stopped: number }> = [];
  const client = createMeshoptWorkerClient(() => {
    const port: MeshoptPort & { stopped: number } = {
      onmessage: null, onerror: null, onmessageerror: null, stopped: 0,
      postMessage(message, transfer) { assert.equal(message.bytes, transfer[0]); structuredClone(message, { transfer }); },
      terminate() { port.stopped++; },
    };
    ports.push(port);
    return port;
  });
  const raw = new ArrayBuffer(30);
  const first = client.run(raw);
  assert.equal(raw.byteLength, 0);
  const stale = ports[0].onmessage!;
  const second = client.run(new ArrayBuffer(30));
  assert.equal(await first, null);
  stale({ data: { ok: true, bytes: new ArrayBuffer(30) } });
  assert.equal(ports[0].stopped, 1);
  ports[1].onmessage!({ data: { ok: true, bytes: new ArrayBuffer(30) } });
  assert.equal((await second)?.byteLength, 30);
  assert.equal(ports[1].stopped, 1);
  const third = client.run(new ArrayBuffer(30));
  client.cancel();
  assert.equal(await third, null);
  assert.equal(ports[2].stopped, 1);
});

test("rejects unsupported output and overlength input without retaining a worker", async () => {
  let called = 0;
  const port: MeshoptPort = { onmessage: null, onerror: null, onmessageerror: null,
    postMessage() {}, terminate() {} };
  const client = createMeshoptWorkerClient(() => { called++; return port; });
  await assert.rejects(client.run(new ArrayBuffer(2 * 1024 * 1024)), /大小/);
  assert.equal(called, 0);
  const result = client.run(new ArrayBuffer(30));
  port.onmessage!({ data: { ok: true, bytes: new ArrayBuffer(2 * 1024 * 1024) } });
  await assert.rejects(result, /结果/);
  assert.equal(port.onmessage, null);
});

test("a real isolated worker transfers decoded bytes accepted by the unmodified static parser", async () => {
  const { Worker } = await import("node:worker_threads");
  const { meshoptFixture } = await import("./meshoptFixture.ts");
  const { openStaticGlb } = await import("./glbIntake.ts");
  const client = createMeshoptWorkerClient(() => {
    const worker = new Worker(`
      const { parentPort, workerData } = require("node:worker_threads");
      globalThis.self = { postMessage: (message, transfer) => parentPort.postMessage(message, transfer) };
      import(workerData).then(() => parentPort.once("message", (data) => self.onmessage({ data })))
        .catch((error) => { throw error; });
    `, { eval: true, workerData: new URL("./meshopt.worker.ts", import.meta.url).href });
    const port: MeshoptPort = {
      onmessage: null, onerror: null, onmessageerror: null,
      postMessage: (message, transfer) => worker.postMessage(message, transfer as ArrayBuffer[]),
      terminate() { void worker.terminate(); },
    };
    worker.on("message", (data) => port.onmessage?.({ data }));
    worker.on("error", () => port.onerror?.());
    worker.on("messageerror", () => port.onmessageerror?.());
    return port;
  });
  const source = await meshoptFixture();
  const output = await client.run(source);
  assert.equal(source.byteLength, 0);
  assert.ok(output);
  const model = openStaticGlb(output);
  try {
    assert.deepEqual(model.metrics, { vertices: 6, triangles: 2, dimensions: [1, 2, 3] });
  } finally { model.dispose(); }
  const damaged = await meshoptFixture((_doc, packed) => { packed[0] = 0; });
  await assert.rejects(client.run(damaged), /无法解码/);
});
