'use client';

import { useEffect, useState } from 'react';
import axios from 'axios';
import { formatEta, formatSize } from '@/lib/format';

interface Transfer {
  id: string;
  direction: 'SEND' | 'RECEIVE';
  status: string;
  fileName: string;
  peerName: string;
  totalBytes: number;
  transferredBytes: number;
  percent: number;
  mbPerSec: number;
  etaSeconds: number;
  error: string | null;
}

const STATUS_LABELS: Record<string, string> = {
  QUEUED: 'Queued',
  ACTIVE: 'Transferring',
  PAUSED: 'Paused',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  AWAITING_APPROVAL: 'Waiting for approval',
  REJECTED: 'Rejected',
};

export default function TransfersPanel() {
  const [transfers, setTransfers] = useState<Transfer[]>([]);

  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      try {
        const response = await axios.get('/api/transfers');
        if (!cancelled) setTransfers(response.data ?? []);
      } catch {
        /* keep polling */
      }
    };
    poll();
    const timer = setInterval(poll, 1500);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  const act = async (id: string, action: 'pause' | 'resume' | 'cancel') => {
    try {
      await axios.post(`/api/transfers/${id}`, { action });
    } catch {
      /* state changed concurrently; next poll corrects the view */
    }
  };

  if (transfers.length === 0) {
    return (
      <p className="py-8 text-center text-sm text-neutral-400">
        No transfers yet. Send something to a nearby device, or accept an
        incoming offer.
      </p>
    );
  }

  return (
    <ul className="divide-y divide-neutral-100">
      {transfers.map((t) => (
        <li key={t.id} className="py-3">
          <div className="flex items-center gap-2">
            <span className="text-neutral-400">{t.direction === 'RECEIVE' ? '↓' : '↑'}</span>
            <p className="min-w-0 flex-1 truncate text-sm text-neutral-900">{t.fileName}</p>
            <span className="shrink-0 text-xs text-neutral-400">
              {t.direction === 'RECEIVE' ? 'from' : 'to'} {t.peerName}
            </span>
          </div>

          {(t.status === 'ACTIVE' || t.status === 'PAUSED') && t.direction === 'RECEIVE' && (
            <div className="mt-2 h-1 w-full overflow-hidden rounded bg-neutral-100">
              <div
                className="h-full bg-neutral-900 transition-all"
                style={{ width: `${Math.min(100, t.percent)}%` }}
              />
            </div>
          )}

          <div className="mt-1.5 flex items-center gap-3 text-xs text-neutral-400">
            <span>{STATUS_LABELS[t.status] ?? t.status}</span>
            {t.direction === 'RECEIVE' && t.status === 'ACTIVE' && (
              <>
                <span>
                  {formatSize(t.transferredBytes)} / {formatSize(t.totalBytes)} ({t.percent.toFixed(1)}%)
                </span>
                <span>{t.mbPerSec.toFixed(1)} MB/s</span>
                <span>ETA {formatEta(t.etaSeconds)}</span>
              </>
            )}
            {t.status === 'COMPLETED' && <span>{formatSize(t.totalBytes)}</span>}
            {t.error && <span className="text-red-500">{t.error}</span>}

            {t.direction === 'RECEIVE' && (
              <span className="ml-auto flex gap-2">
                {t.status === 'ACTIVE' && (
                  <button className="hover:text-neutral-700" onClick={() => act(t.id, 'pause')}>
                    Pause
                  </button>
                )}
                {(t.status === 'PAUSED' || t.status === 'FAILED') && (
                  <button className="hover:text-neutral-700" onClick={() => act(t.id, 'resume')}>
                    Resume
                  </button>
                )}
                {['ACTIVE', 'QUEUED', 'PAUSED'].includes(t.status) && (
                  <button className="hover:text-red-600" onClick={() => act(t.id, 'cancel')}>
                    Cancel
                  </button>
                )}
              </span>
            )}
          </div>
        </li>
      ))}
    </ul>
  );
}
