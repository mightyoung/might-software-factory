import { useEffect, useId, useRef } from "react";

export function WorkbenchConfirmDialog({ title, description, confirmLabel, onCancel, onConfirm }: {
  title: string;
  description: string;
  confirmLabel: string;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const dialog = useRef<HTMLDialogElement>(null);
  const cancel = useRef<HTMLButtonElement>(null);
  const confirm = useRef<HTMLButtonElement>(null);
  const id = useId();
  useEffect(() => {
    const opener = document.activeElement;
    const node = dialog.current!;
    node.showModal();
    cancel.current?.focus();
    return () => {
      node.close();
      if (opener instanceof HTMLElement && opener.isConnected) opener.focus();
    };
  }, []);
  return (
    <dialog ref={dialog} className="workbench-dialog" role="alertdialog" aria-modal="true"
      aria-labelledby={id} aria-describedby={id + "-description"}
      onCancel={(event) => { event.preventDefault(); onCancel(); }}
      onKeyDown={(event) => {
        if (event.key !== "Tab") return;
        const [from, to] = event.shiftKey ? [cancel, confirm] : [confirm, cancel];
        if (document.activeElement === from.current) {
          event.preventDefault();
          to.current?.focus();
        }
      }}>
      <h2 id={id}>{title}</h2>
      <p id={id + "-description"}>{description}</p>
      <div className="report-actions">
        <button ref={cancel} type="button" className="button button-subtle" onClick={onCancel}>继续编辑</button>
        <button ref={confirm} type="button" className="button button-primary" onClick={onConfirm}>
          {confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
