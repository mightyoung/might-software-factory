export function paintPreviewThumbnail(source: HTMLCanvasElement, target: HTMLCanvasElement) {
  const width = 128, height = 96;
  if (!source.width || !source.height || target === source || target.width !== width || target.height !== height) return false;
  const painter = target.getContext("2d");
  if (!painter) return false;
  const scale = Math.min(width / source.width, height / source.height);
  const w = source.width * scale, h = source.height * scale;
  painter.clearRect(0, 0, width, height);
  painter.drawImage(source, (width - w) / 2, (height - h) / 2, w, h);
  return true;
}
