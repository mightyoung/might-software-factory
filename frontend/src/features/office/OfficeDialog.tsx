import { useEffect, useRef, useState } from "react";
import { Button } from "@astryxdesign/core/Button";
import { WorkbenchConfirmDialog } from "../../components/WorkbenchConfirmDialog";
import { ThemeSelector } from "../theme/ThemeProvider";
import { officeEditors, type OfficeEditorKind } from "./editors";
import "./OfficeDialog.css";

const channel = "might-office-v1";
export function OfficeDialog({ kind, onClose, theme }: { kind: OfficeEditorKind; onClose(): void; theme: "light" | "dark" }) {
  const editor = officeEditors[kind];
  const dialog = useRef<HTMLDialogElement>(null);
  const frame = useRef<HTMLIFrameElement>(null);
  const closeButton = useRef<HTMLButtonElement>(null);
  const requestId = useRef(0);
  const closeTimer = useRef<number | undefined>(undefined);
  const exitPhase = useRef<"idle" | "checking" | "confirming">("idle");
  const [confirmClose, setConfirmClose] = useState<"dirty" | "unknown" | null>(null);
  const [loaded, setLoaded] = useState(false);
  const initialTheme = useRef(theme);
  const currentTheme = useRef(theme);
  currentTheme.current = theme;
  const close = useRef(onClose);
  close.current = onClose;

  function clearCheck() {
    window.clearTimeout(closeTimer.current);
  }
  function warn(mode: "dirty" | "unknown") {
    clearCheck();
    exitPhase.current = "confirming";
    setConfirmClose(mode);
  }
  function checkClose() {
    if (exitPhase.current !== "idle") return;
    const content = frame.current?.contentWindow;
    if (!content) return warn("unknown");
    exitPhase.current = "checking";
    const id = ++requestId.current;
    closeTimer.current = window.setTimeout(() => warn("unknown"), 2500);
    content.postMessage({ channel, type: "close-check", requestId: id }, location.origin);
  }
  function requestClose() {
    if (loaded) checkClose();
    else if (exitPhase.current === "idle") warn("unknown");
  }
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const element = dialog.current!;
    element.showModal();
    closeButton.current?.focus();
    function message(event: MessageEvent) {
      if (event.origin !== location.origin || event.source !== frame.current?.contentWindow || event.data?.channel !== channel) return;
      if (event.data.type === "close-request") checkClose();
      if (event.data.type === "close-state" && exitPhase.current === "checking" &&
        event.data.requestId === requestId.current && typeof event.data.dirty === "boolean") {
        if (event.data.dirty) warn("dirty");
        else {
          clearCheck();
          exitPhase.current = "idle";
          close.current();
        }
      }
      if (event.data.type === "ready") {
        setLoaded(true);
        frame.current?.contentWindow?.postMessage({ channel, type: "theme", theme: currentTheme.current }, location.origin);
      }
    }
    window.addEventListener("message", message);
    return () => {
      window.removeEventListener("message", message);
      clearCheck();
      element.close();
      if (opener?.isConnected) opener.focus();
    };
  }, [editor]);
  useEffect(() => {
    frame.current?.contentWindow?.postMessage({ channel, type: "theme", theme }, location.origin);
  }, [theme]);

  return <><dialog ref={dialog} className="office-dialog" aria-labelledby="office-dialog-heading" onCancel={event => {
    event.preventDefault();requestClose();
  }}>
    <header className="office-dialog-heading"><h2 id="office-dialog-heading">{editor.title}</h2>
      <div className="office-dialog-actions"><ThemeSelector /><Button ref={closeButton} onClick={requestClose} label={editor.closeLabel} /></div>
    </header>
    {!loaded && <p className="office-dialog-loading" role="status">正在加载{editor.frameTitle}…</p>}
    <iframe ref={frame} onLoad={() => { if (exitPhase.current === "checking") warn("unknown"); }} src={`${editor.page}?theme=${initialTheme.current}`} title={editor.frameTitle}
      sandbox="allow-scripts allow-same-origin allow-downloads allow-modals" />
  </dialog>
    {confirmClose && <WorkbenchConfirmDialog
      title={confirmClose === "dirty" ? "丢弃未导出的修改？" : "无法确认是否有未导出的修改"}
      description={confirmClose === "dirty" ? editor.discardPrompt
        : "编辑器未确认。继续编辑可保留会话，再次点击关闭可重试。仍可选择丢弃并关闭。"}
      confirmLabel="丢弃并关闭"
      onCancel={() => {
        exitPhase.current = "idle";
        setConfirmClose(null);
      }}
      onConfirm={() => {
        exitPhase.current = "idle";
        setConfirmClose(null);
        close.current();
      }}
    />}
  </>;
}
