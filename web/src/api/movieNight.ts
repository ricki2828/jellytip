// Companion calls for the shared movie night (/v1/me/movienight*). Every call is made with the person's own Jellyfin token;
// the server decides who is calling and never reads a user id from the body.
import { companionCall } from "./companionCall";
import { parseSession, type Session, type Vote } from "../lib/movieNight";

export type Result = { ok: true; session: Session } | { ok: false; status: number; error: string };

const errText = (status: number, data: unknown): string => {
  const e = (data as { error?: unknown } | null)?.error;
  if (typeof e === "string" && e) return e;
  if (status === 0) return "Can't reach the server.";
  if (status === 404) return "No movie night with that code.";
  return "Something went wrong.";
};

async function call(method: "GET" | "POST", path: string, body?: unknown, signal?: AbortSignal): Promise<Result> {
  const a = await companionCall(method, path, body, { signal });
  const session = a.status === 200 ? parseSession(a.data) : null;
  return session ? { ok: true, session } : { ok: false, status: a.status, error: a.status === 200 ? "Unexpected answer from the server." : errText(a.status, a.data) };
}

export const createNight = (items: string[]) => call("POST", "v1/me/movienight", { items });
export const joinNight = (code: string) => call("POST", `v1/me/movienight/${code}/join`, {});
export const getNight = (code: string, signal?: AbortSignal) => call("GET", `v1/me/movienight/${code}`, undefined, signal);
export const voteNight = (code: string, item: string, vote: Vote) => call("POST", `v1/me/movienight/${code}/vote`, { item, vote });
export const closeNight = (code: string) => call("POST", `v1/me/movienight/${code}/close`, {});
