import { useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { ThemeSelector } from "../theme/ThemeProvider";
import "./OfficeDialog.css";

const channel = "might-office-v1";
export function OfficeDialog({ onClose, theme }: { onClose(): void; theme: "light" | "dark" }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const frame = useRef<HTMLIFrameElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const dirty = useRef(false);
  const [loaded, setLoaded] = useState(false);
  const initialTheme = useRef(theme);
  const currentTheme = useRef(theme);
  currentTheme.current = theme;
  const close = useRef(onClose);
  close.current = onClose;

  function finishClose() {
    if (!dirty.current || window.confirm("工作簿有尚未导出的修改。确认丢弃并关闭？")) close.current();
  }
  function requestClose() {
    if (loaded) frame.current?.contentWindow?.postMessage({ channel, type: "close-check" }, location.origin);
    else finishClose();
  }
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const element = dialog.current!;
    element.showModal();
    closeButton.current?.focus();
    function message(event: MessageEvent) {
      if (event.origin !== location.origin || event.source !== frame.current?.contentWindow || event.data?.channel !== channel) return;
      if (event.data.type === "state" && typeof event.data.dirty === "boolean") dirty.current = event.data.dirty;
      if (event.data.type === "close-request") frame.current?.contentWindow?.postMessage({ channel, type: "close-check" }, location.origin);
      if (event.data.type === "close-state" && typeof event.data.dirty === "boolean") {
        dirty.current = event.data.dirty;finishClose();
      }
      if (event.data.type === "ready") {
        setLoaded(true);
        frame.current?.contentWindow?.postMessage({ channel, type: "theme", theme: currentTheme.current }, location.origin);
      }
    }
    window.addEventListener("message", message);
    return () => {
      window.removeEventListener("message", message);
      element.close();
      if (opener?.isConnected) opener.focus();
    };
  }, []);
  useEffect(() => {
    frame.current?.contentWindow?.postMessage({ channel, type: "theme", theme }, location.origin);
  }, [theme]);

  return <dialog ref={dialog} className="office-dialog" aria-labelledby="office-dialog-heading" onCancel={event => {
    event.preventDefault();requestClose();
  }}>
    <header className="office-dialog-heading"><h2 id="office-dialog-heading">在线表格</h2>
      <div className="office-dialog-actions"><ThemeSelector /><Button ref={closeButton} onClick={requestClose} label="关闭表格" /></div>
    </header>
    {!loaded && <p className="office-dialog-loading" role="status">正在加载表格编辑器…</p>}
    <iframe ref={frame} src={`/office/index.html?theme=${initialTheme.current}`} title="在线工作簿编辑器"
      sandbox="allow-scripts allow-same-origin allow-downloads allow-modals" />
  </dialog>;
}
