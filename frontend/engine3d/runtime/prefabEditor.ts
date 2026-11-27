import { createPrefabBank, type PrefabVersion } from "../core/prefabBank.ts";

const bank = createPrefabBank();
const field = (id: string) => document.getElementById("prefab-" + id) as HTMLInputElement;
const form = document.querySelector<HTMLFormElement>("#prefab-form")!;
const status = document.getElementById("prefab-status")!;
const error = document.getElementById("prefab-error")!;
const preview = document.getElementById("prefab-preview")!;
const versions = document.getElementById("prefab-versions")!;
const footprint = document.getElementById("prefab-footprint")!;
const outline = document.getElementById("prefab-outline")!;
const markers = document.getElementById("prefab-anchor-markers")!;
const caption = document.getElementById("prefab-preview-caption")!;
const ink = { normal: "#54776d", warning: "#957323", alarm: "#9c3846" } as const;
const stateName = { normal: "正常", warning: "预警", alarm: "告警" } as const;

let selectedVersion: PrefabVersion | undefined;
function show(selected: PrefabVersion, saved: boolean) {
  selectedVersion = selected;
  const place = document.querySelector<HTMLButtonElement>("#prefab-place")!;
  place.disabled = false;
  document.getElementById("prefab-place-version")!.textContent = `将放置 ${selected.id} · v${selected.version}；后续保存新版本不会更新已放置实例。`;
  error.hidden = true;
  status.textContent = (saved ? "已保存：" : "已选择：") + selected.name + "，版本 " + selected.version + "。";
  const { length, width, height } = selected.equipment.dimensions;
  const anchor = selected.anchors[0];
  field("id").value = selected.id;
  field("name").value = selected.name;
  field("kind").value = selected.equipment.kind;
  field("state").value = selected.state;
  field("length").value = String(length);
  field("width").value = String(width);
  field("height").value = String(height);
  field("anchor").value = anchor?.id ?? "";
  for (const [i, axis] of ["x", "y", "z"].entries()) field("anchor-" + axis).value = String(anchor?.position[i] ?? 0);

  caption.textContent = selected.name + " · v" + selected.version + " · " + length + " × " + width + " × " + height
    + " 米 · " + stateName[selected.state] + (anchor ? " · 锚点 " + anchor.id : "");
  const margin = Math.max(length, width) * 0.1;
  footprint.setAttribute("viewBox", [-(length / 2 + margin), -(width / 2 + margin), length + margin * 2, width + margin * 2].join(" "));
  outline.setAttribute("x", String(-length / 2));
  outline.setAttribute("y", String(-width / 2));
  outline.setAttribute("width", String(length));
  outline.setAttribute("height", String(width));
  outline.setAttribute("fill", ink[selected.state]);
  markers.replaceChildren(...selected.anchors.map(({ id, position: [x, , z] }) => {
    const marker = document.createElementNS("http://www.w3.org/2000/svg", "circle");
    marker.setAttribute("cx", String(x));
    marker.setAttribute("cy", String(z));
    marker.setAttribute("r", String(Math.max(length, width) * 0.04));
    marker.setAttribute("fill", "#fff");
    marker.setAttribute("data-anchor-id", id);
    return marker;
  }));
  preview.hidden = false;

  versions.replaceChildren(...bank.list().map((entry) => {
    const row = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = entry.name + " · " + entry.id + " · v" + entry.version;
    button.setAttribute("aria-pressed", String(entry === selected));
    button.addEventListener("click", () => {
      show(entry, false);
      // The list is rebuilt to update aria-pressed; focus follows the same version.
      versions.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus();
    });
    row.append(button);
    return row;
  }));
}

form.addEventListener("submit", (event) => {
  event.preventDefault();
  try {
    const anchorId = field("anchor").value.trim();
    const saved = bank.save({
      id: field("id").value.trim(), name: field("name").value,
      equipment: { kind: field("kind").value, dimensions: {
        length: field("length").valueAsNumber, width: field("width").valueAsNumber, height: field("height").valueAsNumber,
      } },
      state: field("state").value,
      anchors: anchorId ? [{ id: anchorId, position: ["x", "y", "z"].map((axis) => field("anchor-" + axis).valueAsNumber) }] : [],
    });
    show(saved, true);
  } catch (failure) {
    error.textContent = failure instanceof Error ? failure.message : "无法保存组件。";
    error.hidden = false;
  }
});

export function selectedPrefabVersion() { return selectedVersion; }
