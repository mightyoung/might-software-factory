import { VideoSlots } from "../core/VideoSlots.ts";

export function mountSyntheticVideo() {
  const gallery = document.querySelector<HTMLElement>("#video-samples")!;
  const cards = document.querySelector<HTMLElement>("#video-slots")!;
  const status = document.querySelector<HTMLElement>("#video-status")!;
  const now = () => performance.now();
  const observed = new Map<string, boolean>();
  const observer = new IntersectionObserver((records) => {
    for (const record of records) {
      const id = (record.target as HTMLElement).dataset.slot!;
      if (!observed.has(id)) continue;
      observed.set(id, record.isIntersecting);
      slots.setVisible(id, record.isIntersecting && !document.hidden, now());
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
    const ctx = source.getContext("2d")!;
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
    draw();
    const stream = source.captureStream(12);
    video.srcObject = stream;
    const loop = window.setInterval(draw, 84);
    cards.append(card);
    observed.set(id, true);
    observer.observe(card);
    return () => {
      observer.unobserve(card);
      observed.delete(id);
      window.clearInterval(loop);
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
        if (document.hidden) slots.setVisible(id, false, now());
        render();
      } catch { status.textContent = "当前浏览器无法启动合成视频。"; }
    };
    gallery.append(button);
  }
  const interval = window.setInterval(() => { slots.sweep(now()); render(); }, 1000);
  document.addEventListener("visibilitychange", () => {
    for (const [id, visible] of observed) slots.setVisible(id, visible && !document.hidden, now());
  });
  window.addEventListener("pagehide", (event) => {
    if (!event.persisted) { window.clearInterval(interval); observer.disconnect(); slots.closeAll(); }
  });
  render();
}
