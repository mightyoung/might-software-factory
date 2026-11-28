import { VideoSlots } from "../core/VideoSlots.ts";

export function mountSyntheticVideo() {
  const gallery = document.querySelector<HTMLElement>("#video-samples")!;
  const cards = document.querySelector<HTMLElement>("#video-slots")!;
  const status = document.querySelector<HTMLElement>("#video-status")!;
  const now = () => performance.now();
  const observed = new Map<string, { visible: boolean; sync: () => void }>();
  let suspended = false;
  const active = () => !document.hidden && !suspended;
  const observer = new IntersectionObserver((records) => {
    for (const record of records) {
      const id = (record.target as HTMLElement).dataset.slot!;
      const entry = observed.get(id);
      if (!entry) continue;
      entry.visible = record.isIntersecting;
      slots.setVisible(id, entry.visible && active(), now());
      entry.sync();
    }
  }, { threshold: 0.1 });
  const render = () => {
    const next = `当前 ${slots.ids().length}/4 路合成视频。`;
    if (status.textContent !== next) status.textContent = next;
  };
  const slots = new VideoSlots((id) => {
    const source = document.createElement("canvas");
    source.width = 320;
    source.height = 180;
    const ctx = source.getContext("2d");
    if (!ctx) throw new Error("Canvas 2D is unavailable");
    const card = document.createElement("article");
    card.dataset.slot = id;
    const title = document.createElement("h3");
    title.textContent = `合成信号 ${id}`;
    const video = document.createElement("video");
    video.muted = true;
    video.autoplay = true;
    video.playsInline = true;
    video.setAttribute("aria-label", `合成信号 ${id} 画面`);
    const close = document.createElement("button");
    close.type = "button";
    close.textContent = `停止 ${id}`;
    close.onclick = () => { slots.close(id); render(); gallery.querySelector<HTMLButtonElement>(`[data-signal="${id}"]`)?.focus(); };
    card.append(title, video, close);
    let frame = 0;
    const draw = () => {
      ctx.fillStyle = "#123b35";
      ctx.fillRect(0, 0, 320, 180);
      ctx.fillStyle = "#ffcb45";
      ctx.fillRect(frame++ % 270, 105, 50, 12);
      ctx.font = "18px system-ui";
      ctx.fillText(`SYNTHETIC ${id}`, 16, 36);
    };
    const stream = source.captureStream(12);
    video.srcObject = stream;
    let raf: number | null = null;
    let last = -Infinity;
    const visible = () => {
      if (!active() || !observed.get(id)?.visible) return false;
      const { left, right, top, bottom } = card.getBoundingClientRect();
      return right > 0 && bottom > 0 && left < window.innerWidth && top < window.innerHeight;
    };
    const sync = () => {
      if (visible()) {
        if (raf === null) raf = window.requestAnimationFrame(tick);
      } else if (raf !== null) {
        window.cancelAnimationFrame(raf);
        raf = null;
      }
    };
    function tick(time: number) {
      raf = null;
      if (!visible()) return;
      if (time - last >= 1000 / 12) { draw(); last = time; }
      raf = window.requestAnimationFrame(tick);
    }
    cards.append(card);
    observed.set(id, { visible: false, sync });
    observer.observe(card);
    return () => {
      observer.unobserve(card);
      observed.delete(id);
      sync();
      video.srcObject = null;
      for (const track of stream.getTracks()) track.stop();
      card.remove();
    };
  });
  if (typeof HTMLCanvasElement.prototype.captureStream !== "function") {
    status.textContent = "当前浏览器无法演示合成视频。";
    return;
  }
  for (const id of ["A", "B", "C", "D", "E", "F"]) {
    const button = document.createElement("button");
    button.type = "button";
    button.dataset.signal = id;
    button.textContent = `打开信号 ${id}`;
    button.onclick = () => {
      try {
        slots.open(id, now());
        if (!active() || !observed.get(id)?.visible) slots.setVisible(id, false, now());
        render();
      } catch { status.textContent = "当前浏览器无法启动合成视频。"; }
    };
    gallery.append(button);
  }
  const interval = window.setInterval(() => { slots.sweep(now()); render(); }, 1000);
  function syncVisibility() {
    for (const [id, entry] of observed) {
      slots.setVisible(id, entry.visible && active(), now());
      entry.sync();
    }
  }
  document.addEventListener("visibilitychange", syncVisibility);
  window.addEventListener("pageshow", () => { suspended = false; syncVisibility(); });
  window.addEventListener("pagehide", (event) => {
    suspended = true;
    syncVisibility();
    if (!event.persisted) { window.clearInterval(interval); observer.disconnect(); slots.closeAll(); }
  });
  render();
}
