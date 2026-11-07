// "Delete from server": a danger button (only when the companion says this account may delete it) and a confirm dialog that
// names the title, what goes and the size. Never default focus: Cancel is the autofocus, so a stray OK press keeps the title.
import { useEffect, useState } from "react";
import { deleteFromServer, deleteInfo } from "../api/libdelete";
import { sizeText, whatGoes, type DeleteInfo } from "../lib/libDelete";
import { SheetClose, useEscapeClose } from "./BackArrow";
import { flashNote } from "./TasteButtons";
import { back } from "../lib/router";
import "../screens/iptv/iptv.css";

export function DeleteFromServer({ id, className = "btn danger", label = "Delete from server", afterDelete }: { id: string; className?: string; label?: string; afterDelete?: () => void }) {
  const [info, setInfo] = useState<DeleteInfo | null>(null);
  const [open, setOpen] = useState(false);
  useEffect(() => {
    setInfo(null);
    const c = new AbortController();
    void deleteInfo(id, c.signal).then((i) => !c.signal.aborted && setInfo(i));
    return () => c.abort();
  }, [id]);
  if (!info) return null;
  return (
    <>
      <button className={className} data-nav aria-haspopup="dialog" onClick={() => setOpen(true)}>
        {label}
      </button>
      {open && <ConfirmDelete id={id} info={info} onClose={() => setOpen(false)} afterDelete={afterDelete} />}
    </>
  );
}

export function ConfirmDelete({ id, info, onClose, afterDelete }: { id: string; info: DeleteInfo; onClose: () => void; afterDelete?: () => void }) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEscapeClose(onClose, !busy);
  const size = sizeText(info.bytes);
  const run = async () => {
    setBusy(true);
    const r = await deleteFromServer(id);
    if (!r.ok) {
      setErr(r.error);
      setBusy(false);
      return;
    }
    flashNote(`Deleted ${info.title}${r.freedBytes ? ` (${sizeText(r.freedBytes)} freed)` : ""}`);
    onClose();
    if (afterDelete) afterDelete();
    else back("/home");
  };
  return (
    <div className="livetv-sheet-back" onClick={busy ? undefined : onClose}>
      <div className="livetv-sheet" role="alertdialog" aria-label={`Delete ${info.title}`} onClick={(e) => e.stopPropagation()}>
        <SheetClose onClose={onClose} />
        <p className="iptv-hint">Delete from server</p>
        <h2>{info.title}</h2>
        <p>
          This removes {whatGoes(info.kind)}
          {size ? ` (${size})` : ""}. {info.note ?? ""}
        </p>
        <p>
          <strong>This can't be undone.</strong>
        </p>
        {err && <p className="iptv-err">{err}</p>}
        <div className="buttons">
          <button className="btn" data-nav data-autofocus autoFocus disabled={busy} onClick={onClose}>
            Cancel
          </button>
          <button className="btn danger" data-nav disabled={busy} onClick={() => void run()}>
            {busy ? "Deleting…" : size ? `Delete for good (${size})` : "Delete for good"}
          </button>
        </div>
      </div>
    </div>
  );
}
