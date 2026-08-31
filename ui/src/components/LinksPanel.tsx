'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { authFetch, errorMessage, formatBytes, getSession } from '@/lib/api';

interface ShareLink {
  slug: string;
  url: string;
  fileName: string;
  size: number;
  createdAt: number;
  expiresAt: number;
  downloads: number;
  protected: boolean;
  revoked: boolean;
}

interface Plan {
  tier: string;
  entitlements: { maxStorageBytes: number; maxActiveLinks: number };
  usage: { storageBytesUsed: number; activeLinks: number };
}

const NEVER = 9223372036854775807;

function expiryLabel(expiresAt: number): string {
  if (expiresAt >= NEVER) return 'never expires';
  const hours = Math.round((expiresAt - Date.now()) / 3_600_000);
  if (hours <= 0) return 'expired';
  if (hours < 48) return `expires in ${hours} h`;
  return `expires in ${Math.round(hours / 24)} d`;
}

/**
 * Mode 4 — Link Share. Upload to cloud storage, get /s/{slug}; the file
 * stays available after you leave. Free plan: 2-day expiry, 10 GB, 10 links.
 */
export default function LinksPanel() {
  const [links, setLinks] = useState<ShareLink[]>([]);
  const [plan, setPlan] = useState<Plan | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const refresh = useCallback(async () => {
    const [linksResponse, planResponse] = await Promise.all([
      authFetch('/api/v1/links'),
      authFetch('/api/v1/plan'),
    ]);
    if (linksResponse.ok) setLinks(await linksResponse.json());
    if (planResponse.ok) setPlan(await planResponse.json());
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const upload = async (file: File) => {
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const response = await authFetch('/api/v1/links/upload', { method: 'POST', body: form });
      if (!response.ok) throw new Error(await errorMessage(response));
      await refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setBusy(false);
      if (fileInput.current) fileInput.current.value = '';
    }
  };

  const copy = async (slug: string) => {
    await navigator.clipboard.writeText(`${window.location.origin}/s/${slug}`);
    setCopied(slug);
    setTimeout(() => setCopied(null), 1500);
  };

  const remove = async (slug: string) => {
    await authFetch(`/api/v1/links/${slug}`, { method: 'DELETE' });
    refresh();
  };

  if (!getSession()) {
    return <p className="text-sm text-neutral-400">Sign in to create shareable links.</p>;
  }

  return (
    <div className="space-y-6">
      <div className="space-y-3">
        <input
          ref={fileInput}
          type="file"
          className="block w-full text-sm text-neutral-500 file:mr-3 file:rounded-md file:border-0 file:bg-neutral-100 file:px-3 file:py-1.5 file:text-sm file:text-neutral-700"
          onChange={(e) => e.target.files?.[0] && upload(e.target.files[0])}
          disabled={busy}
        />
        {busy && <p className="text-xs text-neutral-400">Uploading to storage…</p>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        <p className="text-xs text-neutral-400">
          Links keep working after you close this tab. Free plan: 2-day expiry.
        </p>
      </div>

      {plan && (
        <p className="text-xs text-neutral-400">
          {plan.tier} · {formatBytes(plan.usage.storageBytesUsed)} of{' '}
          {formatBytes(plan.entitlements.maxStorageBytes)} · {plan.usage.activeLinks}/
          {plan.entitlements.maxActiveLinks === 2147483647 ? '∞' : plan.entitlements.maxActiveLinks}{' '}
          links
        </p>
      )}

      {links.length === 0 ? (
        <p className="text-sm text-neutral-400">No links yet.</p>
      ) : (
        <ul className="divide-y divide-neutral-100 rounded-md border border-neutral-200">
          {links.map((link) => (
            <li key={link.slug} className="px-3 py-2.5">
              <div className="flex items-center justify-between gap-2">
                <p className="truncate text-sm text-neutral-900">{link.fileName}</p>
                <div className="flex shrink-0 gap-2 text-xs">
                  <button className="text-neutral-500 hover:text-neutral-900" onClick={() => copy(link.slug)}>
                    {copied === link.slug ? 'Copied' : 'Copy link'}
                  </button>
                  <button className="text-neutral-300 hover:text-red-600" onClick={() => remove(link.slug)}>
                    Delete
                  </button>
                </div>
              </div>
              <p className="mt-0.5 text-xs text-neutral-400">
                {formatBytes(link.size)} · {link.downloads} download{link.downloads === 1 ? '' : 's'} ·{' '}
                {link.revoked ? 'revoked' : expiryLabel(link.expiresAt)}
              </p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
