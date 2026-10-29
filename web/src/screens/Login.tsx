import { useEffect, useRef, useState } from "react";
import { login, quickConnectEnabled, quickConnectInitiate, quickConnectPoll } from "../api/jellyfin";
import { Logo } from "../components/Nav";

export function Login() {
  const [user, setUser] = useState("");
  const [pw, setPw] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [qcAvailable, setQcAvailable] = useState(false);
  const [code, setCode] = useState<string | null>(null);
  const poll = useRef<number | null>(null);
  const polling = useRef(false);

  useEffect(() => {
    quickConnectEnabled().then(setQcAvailable);
    return () => {
      if (poll.current) clearInterval(poll.current);
    };
  }, []);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(user.trim(), pw);
    } catch (err) {
      setError((err as { status?: number }).status === 401 ? "Wrong username or password." : "Couldn't reach the server. Try again.");
    } finally {
      setBusy(false);
    }
  };

  const startQc = async () => {
    setError(null);
    try {
      const { Code, Secret } = await quickConnectInitiate();
      setCode(Code);
      if (poll.current) clearInterval(poll.current);
      const timer = window.setInterval(async () => {
        if (polling.current) return; // previous check still out (slow server): don't stack requests
        polling.current = true;
        try {
          if (await quickConnectPoll(Secret)) clearInterval(timer);
        } catch {
          clearInterval(timer);
          if (poll.current === timer) {
            setCode(null);
            setError("Quick Connect expired. Start again.");
          }
        } finally {
          polling.current = false;
        }
      }, 3000);
      poll.current = timer;
    } catch {
      setError("Quick Connect isn't available right now.");
    }
  };

  return (
    <div className="login">
      <div className="login-box">
        <Logo large />
        <form onSubmit={submit}>
          <input data-nav autoFocus placeholder="Username" autoComplete="username" value={user} onChange={(e) => setUser(e.target.value)} />
          <input data-nav type="password" placeholder="Password" autoComplete="current-password" value={pw} onChange={(e) => setPw(e.target.value)} />
          <button data-nav className="btn primary wide" disabled={busy || !user.trim()}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
        {qcAvailable && !code && (
          <button data-nav className="btn wide" onClick={startQc}>
            Use Quick Connect
          </button>
        )}
        {code && (
          <div className="qc">
            <div className="qc-code">{code}</div>
            <p>Enter this code in Jellytip on the TV (or Jellyfin › Quick Connect) to sign in here.</p>
          </div>
        )}
        {error && <p className="error">{error}</p>}
      </div>
    </div>
  );
}
