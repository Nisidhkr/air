'use client';

import { useEffect, useState } from 'react';
import axios from 'axios';
import { formatSize } from '@/lib/format';

interface Offer {
  offerId: string;
  fromName: string;
  fromOs: string;
  fileCount: number;
  totalBytes: number;
  fileNames: string[];
}

/**
 * Device approval popup: polls for pending LAN offers and renders
 * "<device> wants to send N files (size)" with Accept / Reject.
 */
export default function IncomingOffers() {
  const [offers, setOffers] = useState<Offer[]>([]);
  const [trust, setTrust] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      try {
        const response = await axios.get('/api/lan/offers');
        if (!cancelled) setOffers(response.data ?? []);
      } catch {
        /* backend not reachable; keep polling */
      }
    };
    poll();
    const timer = setInterval(poll, 3000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  const respond = async (offerId: string, action: 'accept' | 'reject') => {
    setBusy(true);
    try {
      await axios.post(`/api/lan/offers/${offerId}`, { action, trust: action === 'accept' && trust });
      setOffers((prev) => prev.filter((o) => o.offerId !== offerId));
      setTrust(false);
    } finally {
      setBusy(false);
    }
  };

  if (offers.length === 0) return null;
  const offer = offers[0];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/30 p-6">
      <div className="w-full max-w-sm rounded-lg border border-neutral-200 bg-white p-5 shadow-lg">
        <p className="text-sm text-neutral-900">
          <span className="font-medium">{offer.fromName}</span> wants to send{' '}
          {offer.fileCount} file{offer.fileCount > 1 ? 's' : ''} ({formatSize(offer.totalBytes)})
        </p>
        <ul className="mt-2 max-h-24 overflow-y-auto text-xs text-neutral-500">
          {offer.fileNames.slice(0, 8).map((name) => (
            <li key={name} className="truncate">{name}</li>
          ))}
          {offer.fileNames.length > 8 && <li>… and {offer.fileNames.length - 8} more</li>}
        </ul>

        <label className="mt-4 flex items-center gap-2 text-xs text-neutral-500">
          <input type="checkbox" checked={trust} onChange={(e) => setTrust(e.target.checked)} />
          Always accept from this device
        </label>

        <div className="mt-4 flex gap-2">
          <button
            className="btn-primary flex-1"
            disabled={busy}
            onClick={() => respond(offer.offerId, 'accept')}
          >
            Accept
          </button>
          <button
            className="flex-1 rounded-md border border-neutral-300 px-4 py-2.5 text-sm text-neutral-700 transition-colors hover:bg-neutral-50 disabled:opacity-50"
            disabled={busy}
            onClick={() => respond(offer.offerId, 'reject')}
          >
            Reject
          </button>
        </div>
      </div>
    </div>
  );
}
