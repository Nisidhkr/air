// Session + fetch helpers for the unified /api/v1 surface.
// Tokens live in localStorage; a 401 clears the session (basic UI —
// silent refresh is a later nicety).

export interface Session {
  accessToken: string;
  refreshToken: string;
  username: string;
}

const KEY = 'air.session';

export function getSession(): Session | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = localStorage.getItem(KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function saveSession(session: Session) {
  localStorage.setItem(KEY, JSON.stringify(session));
  window.dispatchEvent(new Event('air-session'));
}

export function clearSession() {
  localStorage.removeItem(KEY);
  window.dispatchEvent(new Event('air-session'));
}

/** fetch with Authorization; clears the session on 401. */
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  const session = getSession();
  const headers = new Headers(init.headers);
  if (session) headers.set('Authorization', `Bearer ${session.accessToken}`);
  const response = await fetch(input, { ...init, headers });
  if (response.status === 401 && session) clearSession();
  return response;
}

/** Extracts a user-safe message from an API error body. */
export async function errorMessage(response: Response): Promise<string> {
  try {
    const body = await response.json();
    return body.error ?? `Request failed (${response.status})`;
  } catch {
    return `Request failed (${response.status})`;
  }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1024;
    unit++;
  } while (value >= 1024 && unit < units.length - 1);
  return `${value.toFixed(1)} ${units[unit]}`;
}
