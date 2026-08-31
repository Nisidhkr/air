'use client';

import { useState } from 'react';
import { saveSession, clearSession, getSession, errorMessage } from '@/lib/api';

/** Minimal sign in / register. On success the session lands in localStorage. */
export default function AuthPanel({ onDone }: { onDone?: () => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'register') {
        const response = await fetch('/api/v1/auth/register', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ username, password, email: email || undefined }),
        });
        if (!response.ok) throw new Error(await errorMessage(response));
      }
      const login = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username, password }),
      });
      if (!login.ok) throw new Error(await errorMessage(login));
      const tokens = await login.json();
      saveSession({
        accessToken: tokens.accessToken,
        refreshToken: tokens.refreshToken,
        username: username.trim().replace(/^@/, '').toLowerCase(),
      });
      onDone?.();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setBusy(false);
    }
  };

  const signOut = async () => {
    const session = getSession();
    if (session) {
      fetch('/api/v1/auth/logout', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: session.refreshToken }),
      }).catch(() => undefined);
    }
    clearSession();
    onDone?.();
  };

  const session = getSession();
  if (session) {
    return (
      <div className="flex items-center justify-between rounded-md border border-neutral-200 px-4 py-3">
        <p className="text-sm text-neutral-700">
          Signed in as <span className="font-medium">@{session.username}</span>
        </p>
        <button className="text-sm text-neutral-400 hover:text-neutral-700" onClick={signOut}>
          Sign out
        </button>
      </div>
    );
  }

  return (
    <form onSubmit={submit} className="space-y-3 rounded-md border border-neutral-200 p-4">
      <div className="flex gap-4 text-sm">
        <button
          type="button"
          className={mode === 'login' ? 'font-medium text-neutral-900' : 'text-neutral-400'}
          onClick={() => setMode('login')}
        >
          Sign in
        </button>
        <button
          type="button"
          className={mode === 'register' ? 'font-medium text-neutral-900' : 'text-neutral-400'}
          onClick={() => setMode('register')}
        >
          Create account
        </button>
      </div>

      <input
        className="input-field"
        placeholder="username"
        value={username}
        onChange={(e) => setUsername(e.target.value)}
        autoComplete="username"
        required
      />
      <input
        className="input-field"
        type="password"
        placeholder="password (8+ characters)"
        value={password}
        onChange={(e) => setPassword(e.target.value)}
        autoComplete={mode === 'register' ? 'new-password' : 'current-password'}
        required
        minLength={8}
      />
      {mode === 'register' && (
        <input
          className="input-field"
          type="email"
          placeholder="email (optional)"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
        />
      )}

      {error && <p className="text-sm text-red-600">{error}</p>}

      <button className="btn-primary w-full" disabled={busy}>
        {busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}
      </button>
    </form>
  );
}
