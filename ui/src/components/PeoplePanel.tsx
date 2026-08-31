'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { authFetch, errorMessage, formatBytes, getSession } from '@/lib/api';

interface Profile {
  userId: string;
  username: string;
  displayName: string;
  online: boolean;
}

interface IncomingRequest {
  requestId: string;
  fromUsername: string;
  fileName: string;
  size: number;
  createdAt: number;
}

/**
 * Mode 3 — Username Share. Send: pick a file (becomes a live share via the
 * gateway) and request delivery to @user. Receive: pending requests poll
 * every few seconds; accept streams the file straight to disk.
 */
export default function PeoplePanel() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Profile[]>([]);
  const [target, setTarget] = useState<Profile | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [incoming, setIncoming] = useState<IncomingRequest[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);

  const search = async (q: string) => {
    setQuery(q);
    setTarget(null);
    if (q.trim().length < 2) {
      setResults([]);
      return;
    }
    const response = await authFetch(`/api/v1/users/search?q=${encodeURIComponent(q)}`);
    if (response.ok) setResults(await response.json());
  };

  const refreshIncoming = useCallback(async () => {
    const response = await authFetch('/api/v1/requests');
    if (response.ok) setIncoming(await response.json());
  }, []);

  useEffect(() => {
    refreshIncoming();
    const timer = setInterval(refreshIncoming, 5000);
    return () => clearInterval(timer);
  }, [refreshIncoming]);

  const send = async () => {
    if (!target || !file) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      // 1. Create the live share (sender stays online while it's pulled).
      const form = new FormData();
      form.append('file', file);
      const upload = await fetch('/api/upload', { method: 'POST', body: form });
      if (!upload.ok) throw new Error('Upload failed');
      const share = await upload.json();

      // 2. Ask @user to accept it.
      const request = await authFetch('/api/v1/requests', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ toUsername: target.username, port: share.port }),
      });
      if (!request.ok) throw new Error(await errorMessage(request));
      setNotice(`Request sent to @${target.username} — they'll see it within a few seconds.`);
      setFile(null);
      if (fileInput.current) fileInput.current.value = '';
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not send request');
    } finally {
      setBusy(false);
    }
  };

  const respond = async (requestId: string, action: 'accept' | 'reject') => {
    const response = await authFetch(`/api/v1/requests/${requestId}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action }),
    });
    if (response.ok && action === 'accept') {
      const details = await response.json();
      // Native download manager: streams to disk, resumable via Range.
      const link = document.createElement('a');
      link.href = `/api/download/${details.port}?token=${encodeURIComponent(details.token)}`;
      link.download = '';
      document.body.appendChild(link);
      link.click();
      link.remove();
    }
    refreshIncoming();
  };

  if (!getSession()) {
    return <p className="text-sm text-neutral-400">Sign in to send files to a username.</p>;
  }

  return (
    <div className="space-y-8">
      <section className="space-y-3">
        <h2 className="text-sm font-medium text-neutral-900">Send to a person</h2>
        <input
          className="input-field"
          placeholder="Search username, e.g. @aman"
          value={query}
          onChange={(e) => search(e.target.value)}
        />
        {results.length > 0 && !target && (
          <ul className="divide-y divide-neutral-100 rounded-md border border-neutral-200">
            {results.map((profile) => (
              <li key={profile.userId}>
                <button
                  className="flex w-full items-center justify-between px-3 py-2 text-left text-sm hover:bg-neutral-50"
                  onClick={() => setTarget(profile)}
                >
                  <span>@{profile.username}</span>
                  <span className={profile.online ? 'text-xs text-green-600' : 'text-xs text-neutral-300'}>
                    {profile.online ? 'online' : 'offline'}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
        {target && (
          <div className="space-y-3 rounded-md border border-neutral-200 p-3">
            <p className="text-sm">
              To <span className="font-medium">@{target.username}</span>
              <button className="ml-2 text-xs text-neutral-400 hover:text-neutral-700" onClick={() => setTarget(null)}>
                change
              </button>
            </p>
            <input
              ref={fileInput}
              type="file"
              className="block w-full text-sm text-neutral-500 file:mr-3 file:rounded-md file:border-0 file:bg-neutral-100 file:px-3 file:py-1.5 file:text-sm file:text-neutral-700"
              onChange={(e) => setFile(e.target.files?.[0] ?? null)}
            />
            <button className="btn-primary w-full" disabled={!file || busy} onClick={send}>
              {busy ? 'Sending…' : 'Send request'}
            </button>
          </div>
        )}
        {notice && <p className="text-sm text-neutral-500">{notice}</p>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        <p className="text-xs text-neutral-400">Keep this tab open until the transfer finishes — it streams from your machine.</p>
      </section>

      <section className="space-y-3">
        <h2 className="text-sm font-medium text-neutral-900">Incoming requests</h2>
        {incoming.length === 0 ? (
          <p className="text-sm text-neutral-400">Nothing pending.</p>
        ) : (
          <ul className="space-y-2">
            {incoming.map((request) => (
              <li key={request.requestId} className="rounded-md border border-neutral-200 px-3 py-2">
                <p className="text-sm">
                  <span className="font-medium">@{request.fromUsername}</span> wants to send{' '}
                  <span className="font-medium">{request.fileName}</span>
                  <span className="text-neutral-400"> · {formatBytes(request.size)}</span>
                </p>
                <div className="mt-2 flex gap-2">
                  <button className="btn-primary px-3 py-1.5" onClick={() => respond(request.requestId, 'accept')}>
                    Accept
                  </button>
                  <button
                    className="rounded-md border border-neutral-300 px-3 py-1.5 text-sm text-neutral-600 hover:border-neutral-500"
                    onClick={() => respond(request.requestId, 'reject')}
                  >
                    Reject
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
