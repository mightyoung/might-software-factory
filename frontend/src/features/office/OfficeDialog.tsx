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
  const dirty = useRef(false);
  const exitPhase = useRef<"idle" | "checking" | "confirming">("idle");
  const [confirmClose, setConfirmClose] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const initialTheme = useRef(theme);
  const currentTheme = useRef(theme);
  currentTheme.current = theme;
  const close = useRef(onClose);
  close.current = onClose;

  function finishClose() {
    if (!dirty.current) {
      exitPhase.current = "idle";
      close.current();
      return;
    }
    exitPhase.current = "confirming";
    setConfirmClose(true);
  }
  function checkClose() {
    const content = frame.current?.contentWindow;
    if (!content || exitPhase.current !== "idle") return;
    exitPhase.current = "checking";
    content.postMessage({ channel, type: "close-check" }, location.origin);
  }
  function requestClose() {
    if (loaded) checkClose();
    else if (exitPhase.current === "idle") finishClose();
  }
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const element = dialog.current!;
    element.showModal();
    closeButton.current?.focus();
    function message(event: MessageEvent) {
      if (event.origin !== location.origin || event.source !== frame.current?.contentWindow || event.data?.channel !== channel) return;
      if (event.data.type === "state" && typeof event.data.dirty === "boolean") dirty.current = event.data.dirty;
      if (event.data.type === "close-request") checkClose();
      if (event.data.type === "close-state" && exitPhase.current === "checking" && typeof event.data.dirty === "boolean") {
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
    <iframe ref={frame} src={`${editor.page}?theme=${initialTheme.current}`} title={editor.frameTitle}
      sandbox="allow-scripts allow-same-origin allow-downloads allow-modals" />
  </dialog>
    {confirmClose && <WorkbenchConfirmDialog
      title="丢弃未导出的修改？"
      description={editor.discardPrompt}
      confirmLabel="丢弃并关闭"
      onCancel={() => {
        exitPhase.current = "idle";
        setConfirmClose(false);
      }}
      onConfirm={() => {
        exitPhase.current = "idle";
        setConfirmClose(false);
        close.current();
      }}
    />}
  </>;
}
