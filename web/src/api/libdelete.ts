import { companionCall } from "./companionCall";
import { DELETED_EVENT, markDeleted, parseInfo, parseResult, type DeleteInfo } from "../lib/libDelete";

const infoPath = (id: string) => `v1/library/items/${id}/delete-info`;

/** null = not allowed, or the server doesn't know the route: the action stays hidden. */
export async function deleteInfo(id: string, signal?: AbortSignal): Promise<DeleteInfo | null> {
  const r = await companionCall("GET", infoPath(id), undefined, { signal });
  return parseInfo(r.status, r.data);
}

export async function deleteFromServer(id: string) {
  const r = await companionCall("POST", `v1/library/items/${id}/delete`, {});
  const out = parseResult(r.status, r.data);
  if (out.ok) {
    markDeleted(id);
    window.dispatchEvent(new CustomEvent(DELETED_EVENT, { detail: id }));
  }
  return out;
}
