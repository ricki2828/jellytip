// Settings → Integrations (admins): one card per service (Yahoo Fantasy, Reddit) with what it is for, the link to create the
// app, Client ID and Client Secret fields, Save and a status line. The secret is typed, sent, then cleared; it is never shown.
import { useEffect, useState } from "react";
import { getSession } from "../api/jellyfin";
import { integrations, saveIntegration } from "../api/integrations";
import { SERVICES, statusText, type IntegrationStatus, type Service } from "../lib/integrations";

function Card({ s, status, onSaved }: { s: (typeof SERVICES)[number]; status: IntegrationStatus | undefined; onSaved: (st: IntegrationStatus) => void }) {
  const [id, setId] = useState("");
  const [secret, setSecret] = useState("");
  const [show, setShow] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    const r = await saveIntegration(s.key, id.trim(), secret.trim());
    setBusy(false);
    // The values leave the page either way
    setId("");
    setSecret("");
    setShow(false);
    if ("error" in r) setErr(r.error);
    else onSaved(r.status);
  };
  return (
    <form className="settings-block settings-pad" onSubmit={save} autoComplete="off">
      <strong>{s.title}</strong>
      <span className="settings-sub">{s.purpose}</span>
      <span className="settings-sub">
        {s.hint}{" "}
        <a href={s.createUrl} target="_blank" rel="noopener noreferrer" data-nav>
          {s.createLabel}
        </a>
      </span>
      <div className="settings-inline settings-chips-gap">
        <input className="settings-input" data-nav type="password" autoComplete="off" value={id} onChange={(e) => setId(e.target.value)} placeholder="Client ID" spellCheck={false} aria-label={`${s.title} client ID`} />
        <input
          className="settings-input"
          data-nav
          type={show ? "text" : "password"}
          autoComplete="new-password"
          value={secret}
          onChange={(e) => setSecret(e.target.value)}
          placeholder="Client Secret"
          spellCheck={false}
          aria-label={`${s.title} client secret`}
        />
        <button type="button" className="btn small" data-nav onClick={() => setShow((v) => !v)} aria-pressed={show}>
          {show ? "Hide" : "Show"}
        </button>
        <button className="btn small primary" data-nav disabled={busy || !id.trim() || !secret.trim()}>
          {busy ? "Saving…" : "Save"}
        </button>
      </div>
      <span className={`settings-sub${status?.configured && status.checkedOk === false ? " settings-err" : ""}`}>{statusText(status)}</span>
      {err && <span className="settings-err">{err}</span>}
    </form>
  );
}

export function IntegrationsSettings() {
  const admin = !!getSession()?.isAdmin;
  const [list, setList] = useState<IntegrationStatus[] | null | undefined>(undefined);
  useEffect(() => {
    if (!admin) return;
    let alive = true;
    void integrations().then((l) => alive && setList(l));
    return () => {
      alive = false;
    };
  }, [admin]);
  if (!admin || !list) return null;
  const upsert = (st: IntegrationStatus) => setList((cur) => [...(cur ?? []).filter((x) => x.service !== st.service), st]);
  return (
    <>
      <h2 className="settings-head">Integrations</h2>
      {SERVICES.map((s) => (
        <Card key={s.key} s={s} status={list.find((x) => x.service === (s.key as Service))} onSaved={upsert} />
      ))}
    </>
  );
}
