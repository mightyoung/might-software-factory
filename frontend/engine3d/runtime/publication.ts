import { createStudioPublication, type PublishedStudioVersion, type SyntheticPublicationPrincipal } from "../core/studioPublication.ts";
import { createPlayerSnapshot } from "../schema/playerSnapshot.ts";

export function mountPublication(scene: () => { screenId: string; raw(): string },
  download: (raw: string, filename: string) => void) {
  const store = createStudioPublication();
  const selectPrincipal = document.querySelector<HTMLSelectElement>("#publication-principal")!;
  const selectTarget = document.querySelector<HTMLSelectElement>("#publication-target")!;
  const expected = document.querySelector<HTMLInputElement>("#publication-expected")!;
  const readVersion = document.querySelector<HTMLInputElement>("#publication-version")!;
  const status = document.querySelector<HTMLParagraphElement>("#publication-status")!;
  const error = document.querySelector<HTMLParagraphElement>("#publication-error")!;
  const exportButton = document.querySelector<HTMLButtonElement>("#publication-export")!;
  let picked: PublishedStudioVersion | null = null;
  let generation = 0;
  function principal(): SyntheticPublicationPrincipal {
    const value = selectPrincipal.value;
    return { tenantId: value.startsWith("a-") ? "tenant-a" : "tenant-b",
      permissions: value.endsWith("-writer") ? ["studio:publish"] : ["player:read"] };
  }
  const target = () => ({ tenantId: selectTarget.value, screenId: scene().screenId });
  function clearError() { error.hidden = true; error.textContent = ""; }
  function fail(cause: unknown) {
    error.hidden = false;
    error.textContent = cause instanceof Error ? cause.message : "合成发布未成功。";
  }
  function clearRead() {
    generation++;
    picked = null;
    exportButton.disabled = true;
  }
  function switchScope() {
    clearRead();
    clearError();
    status.textContent = "切换模拟范围后请重新读取版本。";
  }
  for (const control of [selectPrincipal, selectTarget, readVersion]) control.addEventListener("change", switchScope);
  document.querySelector("#publication-commit")!.addEventListener("click", () => {
    clearRead();
    clearError();
    try {
      const written = store.publish(principal(), target(), expected.valueAsNumber, scene().raw());
      expected.value = String(written.version);
      status.textContent = `合成发布成功：第 ${written.version} 版。仅本页留存。`;
    } catch (cause) { fail(cause); }
  });
  document.querySelector("#publication-read")!.addEventListener("click", () => {
    clearRead();
    clearError();
    try {
      picked = store.read(principal(), target(), readVersion.value === "" ? undefined : readVersion.valueAsNumber) ?? null;
      exportButton.disabled = !picked;
      status.textContent = picked ? `已读取不可变版本 ${picked.version}。` : "未找到已发布版本。";
    } catch (cause) { status.textContent = "未读取任何版本。"; fail(cause); }
  });
  exportButton.addEventListener("click", async () => {
    const current = picked, before = generation;
    if (!current) return;
    clearError();
    try {
      const allowed = store.read(principal(), target(), current.version);
      if (allowed !== current) throw new Error("场景或读取版本已变化，请重新读取。");
      const raw = await createPlayerSnapshot(current.source);
      if (before !== generation || store.read(principal(), target(), current.version) !== current) return;
      download(raw, "might-published-test.json");
    } catch (cause) { if (before === generation) fail(cause); }
  });
}
