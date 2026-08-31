'use client';

import { useEffect, useState } from 'react';
import axios from 'axios';
import FileUpload from '@/components/FileUpload';
import FileDownload from '@/components/FileDownload';
import InviteCode from '@/components/InviteCode';
import NearbyDevices from '@/components/NearbyDevices';
import IncomingOffers from '@/components/IncomingOffers';
import TransfersPanel from '@/components/TransfersPanel';
import AuthPanel from '@/components/AuthPanel';
import PeoplePanel from '@/components/PeoplePanel';
import LinksPanel from '@/components/LinksPanel';
import { getSession, formatBytes } from '@/lib/api';

export interface Share {
  port: number;
  token: string;
}

type Tab = 'direct' | 'nearby' | 'people' | 'links' | 'transfers';

const TABS: { id: Tab; label: string }[] = [
  { id: 'direct', label: 'Direct' },
  { id: 'nearby', label: 'Nearby' },
  { id: 'people', label: 'People' },
  { id: 'links', label: 'Links' },
  { id: 'transfers', label: 'Transfers' },
];

export default function Home() {
  const [uploadedFile, setUploadedFile] = useState<File | null>(null);
  const [isUploading, setIsUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [isDownloading, setIsDownloading] = useState(false);
  const [share, setShare] = useState<Share | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('direct');
  const [showAuth, setShowAuth] = useState(false);
  const [username, setUsername] = useState<string | null>(null);

  // Keep the header in sync with the session (login/logout in any panel).
  useEffect(() => {
    const sync = () => setUsername(getSession()?.username ?? null);
    sync();
    window.addEventListener('air-session', sync);
    return () => window.removeEventListener('air-session', sync);
  }, []);

  const handleFileUpload = async (file: File) => {
    setUploadedFile(file);
    setShare(null);
    setIsUploading(true);
    setUploadPercent(0);

    try {
      const formData = new FormData();
      formData.append('file', file);

      const response = await axios.post('/api/upload', formData, {
        onUploadProgress: (e) => {
          if (e.total) setUploadPercent(Math.round((e.loaded / e.total) * 100));
        },
      });

      setShare({ port: response.data.port, token: response.data.token });
    } catch (error) {
      console.error('Error uploading file:', error);
      alert('Failed to upload file. Please try again.');
    } finally {
      setIsUploading(false);
    }
  };

  const handleDownload = async (code: string) => {
    // Invite code format: "<port>-<token>"
    const dash = code.indexOf('-');
    const port = parseInt(code.slice(0, dash === -1 ? code.length : dash), 10);
    const token = dash === -1 ? '' : code.slice(dash + 1).trim();
    if (isNaN(port) || port <= 0 || port > 65535 || !token) {
      throw new Error('Invalid code. Expected format: port-token');
    }

    const url = `/api/download/${port}?token=${encodeURIComponent(token)}`;
    setIsDownloading(true);
    try {
      // Probe with an aborted fetch so auth/availability errors can be shown,
      // then hand the URL to the browser's native download manager. The file
      // streams straight to disk (no in-memory blob), so 40 GB files work,
      // and the browser can pause/resume thanks to backend Range support.
      const controller = new AbortController();
      const probe = await fetch(url, { signal: controller.signal });
      const status = probe.status;
      controller.abort();
      if (status === 401 || status === 403) {
        throw new Error('Invalid invite code.');
      }
      if (status >= 400) {
        throw new Error('Peer not reachable. The share may have ended.');
      }

      const link = document.createElement('a');
      link.href = url;
      link.download = '';
      document.body.appendChild(link);
      link.click();
      link.remove();
    } finally {
      setIsDownloading(false);
    }
  };

  const tabClass = (tab: Tab) =>
    `flex-1 py-2.5 text-sm transition-colors ${
      activeTab === tab
        ? 'border-b-2 border-neutral-900 text-neutral-900 font-medium'
        : 'border-b border-neutral-200 text-neutral-400 hover:text-neutral-600'
    }`;

  return (
    <div className="mx-auto max-w-md px-6 py-16">
      <header className="mb-8">
        <div className="flex items-baseline justify-between">
          <h1 className="text-2xl font-semibold tracking-tight text-neutral-900">Air</h1>
          <button
            className="text-sm text-neutral-400 hover:text-neutral-700"
            onClick={() => setShowAuth((v) => !v)}
          >
            {username ? `@${username}` : 'Sign in'}
          </button>
        </div>
        <p className="mt-1 text-sm text-neutral-500">One place to share files — any way.</p>
      </header>

      {showAuth && (
        <div className="mb-8">
          <AuthPanel onDone={() => setShowAuth(false)} />
        </div>
      )}

      <div className="mb-8 flex">
        {TABS.map((tab) => (
          <button key={tab.id} className={tabClass(tab.id)} onClick={() => setActiveTab(tab.id)}>
            {tab.label}
          </button>
        ))}
      </div>

      {activeTab === 'direct' && (
        <div className="space-y-10">
          <section className="space-y-4">
            <h2 className="text-sm font-medium text-neutral-900">Send</h2>
            <FileUpload onFileUpload={handleFileUpload} isUploading={isUploading} />

            {uploadedFile && (
              <p className="text-sm text-neutral-500">
                {uploadedFile.name}
                <span className="text-neutral-400"> · {formatBytes(uploadedFile.size)}</span>
              </p>
            )}

            {isUploading && (
              <div>
                <div className="h-1 w-full overflow-hidden rounded bg-neutral-100">
                  <div
                    className="h-full bg-neutral-900 transition-all"
                    style={{ width: `${uploadPercent}%` }}
                  />
                </div>
                <p className="mt-2 text-xs text-neutral-400">Uploading… {uploadPercent}%</p>
              </div>
            )}

            <InviteCode share={share} />
          </section>

          <section className="space-y-4">
            <h2 className="text-sm font-medium text-neutral-900">Receive</h2>
            <FileDownload onDownload={handleDownload} isDownloading={isDownloading} />
          </section>
        </div>
      )}
      {activeTab === 'nearby' && <NearbyDevices />}
      {activeTab === 'people' && <PeoplePanel />}
      {activeTab === 'links' && <LinksPanel />}
      {activeTab === 'transfers' && <TransfersPanel />}

      <IncomingOffers />

      <footer className="mt-16 text-center text-xs text-neutral-300">
        Air © {new Date().getFullYear()} — direct · nearby · people · links
      </footer>
    </div>
  );
}
