'use client';

import { useState } from 'react';

interface FileDownloadProps {
  onDownload: (code: string) => Promise<void>;
  isDownloading: boolean;
}

export default function FileDownload({ onDownload, isDownloading }: FileDownloadProps) {
  const [code, setCode] = useState('');
  const [error, setError] = useState('');

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      await onDownload(code.trim());
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Download failed. Please try again.');
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4">
      <div>
        <label htmlFor="inviteCode" className="mb-1.5 block text-sm text-neutral-600">
          Invite code
        </label>
        <input
          type="text"
          id="inviteCode"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          placeholder="54321-9f8e7d6c…"
          className="input-field font-mono"
          disabled={isDownloading}
          required
        />
        {error && <p className="mt-1.5 text-sm text-red-600">{error}</p>}
      </div>

      <button type="submit" className="btn-primary w-full" disabled={isDownloading}>
        {isDownloading ? 'Starting download…' : 'Download'}
      </button>

      <p className="text-xs text-neutral-400">
        The file streams directly to your disk — large files are fine, and your
        browser can pause and resume the download.
      </p>
    </form>
  );
}
