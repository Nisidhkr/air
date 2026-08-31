'use client';

import { useState } from 'react';
import { FiCopy, FiCheck } from 'react-icons/fi';
import type { Share } from '@/app/page';

interface InviteCodeProps {
  share: Share | null;
}

export default function InviteCode({ share }: InviteCodeProps) {
  const [copied, setCopied] = useState(false);

  if (!share) return null;

  const code = `${share.port}-${share.token}`;

  const copyToClipboard = () => {
    navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  return (
    <div className="rounded-md border border-neutral-200 p-4">
      <p className="mb-2 text-sm text-neutral-500">
        Share this code. It works as long as this page stays open.
      </p>
      <div className="flex items-center gap-2">
        <code className="flex-1 truncate rounded bg-neutral-50 px-3 py-2 font-mono text-sm text-neutral-800">
          {code}
        </code>
        <button
          onClick={copyToClipboard}
          className="rounded border border-neutral-200 p-2 text-neutral-600 transition-colors hover:bg-neutral-50"
          aria-label="Copy invite code"
        >
          {copied ? <FiCheck className="h-4 w-4" /> : <FiCopy className="h-4 w-4" />}
        </button>
      </div>
    </div>
  );
}
