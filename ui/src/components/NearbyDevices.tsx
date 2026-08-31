'use client';

import { useEffect, useRef, useState } from 'react';
import axios from 'axios';
import { formatSize } from '@/lib/format';

interface Device {
  deviceId: string;
  name: string;
  os: string;
  deviceType: string;
  online: boolean;
  trusted: boolean;
  lastSeenEpochMs: number;
}

export default function NearbyDevices() {
  const [devices, setDevices] = useState<Device[]>([]);
  const [sendingTo, setSendingTo] = useState<string | null>(null);
  const [statusMsg, setStatusMsg] = useState('');
  const [error, setError] = useState('');
  const [manualAddress, setManualAddress] = useState('');
  const [connecting, setConnecting] = useState(false);
  const targetDevice = useRef<Device | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      try {
        const response = await axios.get('/api/lan/devices');
        if (!cancelled) setDevices(response.data.devices ?? []);
      } catch {
        /* backend not up yet; keep polling */
      }
    };
    poll();
    const timer = setInterval(poll, 3000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  const connectManually = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = manualAddress.trim();
    if (!trimmed) return;
    const [host, portStr] = trimmed.split(':');
    setConnecting(true);
    setError('');
    try {
      const response = await axios.post('/api/lan/connect', {
        host,
        port: portStr ? parseInt(portStr, 10) : 9090,
      });
      setStatusMsg(`Connected to ${response.data.name}.`);
      setManualAddress('');
    } catch (err) {
      const detail = axios.isAxiosError(err) && typeof err.response?.data === 'string'
        ? err.response.data
        : 'Could not reach that address.';
      setError(detail);
    } finally {
      setConnecting(false);
    }
  };

  const pickFiles = (device: Device, folder: boolean) => {
    targetDevice.current = device;
    (folder ? folderInput : fileInput).current?.click();
  };

  const onFilesChosen = async (list: FileList | null) => {
    const device = targetDevice.current;
    if (!device || !list || list.length === 0) return;
    const files = Array.from(list);
    setSendingTo(device.deviceId);
    setError('');
    try {
      const ports: number[] = [];
      let done = 0;
      for (const file of files) {
        const relPath = (file as File & { webkitRelativePath?: string }).webkitRelativePath;
        const formData = new FormData();
        formData.append('file', file);
        setStatusMsg(`Preparing ${++done}/${files.length}: ${file.name}`);
        const response = await axios.post('/api/upload', formData, {
          headers: relPath ? { 'X-Relative-Path': relPath } : undefined,
        });
        ports.push(response.data.port);
      }
      setStatusMsg('Offering files…');
      await axios.post('/api/lan/send', { deviceId: device.deviceId, ports });
      const total = files.reduce((sum, f) => sum + f.size, 0);
      setStatusMsg(
        `Offered ${files.length} file${files.length > 1 ? 's' : ''} (${formatSize(total)}) to ${device.name} — waiting for them to accept.`
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to send. Is the device still online?');
      setStatusMsg('');
    } finally {
      setSendingTo(null);
      if (fileInput.current) fileInput.current.value = '';
      if (folderInput.current) folderInput.current.value = '';
    }
  };

  return (
    <div className="space-y-4">
      <input
        ref={fileInput}
        type="file"
        multiple
        className="hidden"
        onChange={(e) => onFilesChosen(e.target.files)}
      />
      <input
        ref={folderInput}
        type="file"
        multiple
        className="hidden"
        {...({ webkitdirectory: '' } as object)}
        onChange={(e) => onFilesChosen(e.target.files)}
      />

      {devices.length === 0 && (
        <p className="py-8 text-center text-sm text-neutral-400">
          Looking for nearby devices… Make sure PeerLink is open on the other
          device and both are on the same network.
        </p>
      )}

      <ul className="divide-y divide-neutral-100">
        {devices.map((device) => (
          <li key={device.deviceId} className="flex items-center gap-3 py-3">
            <span
              className={`h-2 w-2 shrink-0 rounded-full ${device.online ? 'bg-green-500' : 'bg-neutral-300'}`}
              title={device.online ? 'Online' : 'Offline'}
            />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm text-neutral-900">
                {device.name}
                {device.trusted && <span className="ml-2 text-xs text-neutral-400">trusted</span>}
              </p>
              <p className="text-xs text-neutral-400">
                {device.os} · {device.online ? 'Online' : `last seen ${new Date(device.lastSeenEpochMs).toLocaleTimeString()}`}
              </p>
            </div>
            {device.online && (
              <div className="flex gap-2">
                <button
                  className="rounded border border-neutral-200 px-3 py-1.5 text-xs text-neutral-700 transition-colors hover:bg-neutral-50 disabled:opacity-50"
                  disabled={sendingTo !== null}
                  onClick={() => pickFiles(device, false)}
                >
                  {sendingTo === device.deviceId ? 'Sending…' : 'Send files'}
                </button>
                <button
                  className="rounded border border-neutral-200 px-3 py-1.5 text-xs text-neutral-700 transition-colors hover:bg-neutral-50 disabled:opacity-50"
                  disabled={sendingTo !== null}
                  onClick={() => pickFiles(device, true)}
                >
                  Folder
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>

      {statusMsg && <p className="text-xs text-neutral-500">{statusMsg}</p>}
      {error && <p className="text-sm text-red-600">{error}</p>}

      <form onSubmit={connectManually} className="border-t border-neutral-100 pt-4">
        <p className="mb-1.5 text-xs text-neutral-400">
          Device not appearing? Auto-discovery needs multicast (blocked on some
          networks and on WSL2 NAT) — add it by address instead:
        </p>
        <div className="flex gap-2">
          <input
            type="text"
            value={manualAddress}
            onChange={(e) => setManualAddress(e.target.value)}
            placeholder="192.168.1.42 or 192.168.1.42:9090"
            className="input-field flex-1 font-mono text-xs"
            disabled={connecting}
          />
          <button
            type="submit"
            className="rounded border border-neutral-200 px-3 py-1.5 text-xs text-neutral-700 transition-colors hover:bg-neutral-50 disabled:opacity-50"
            disabled={connecting || !manualAddress.trim()}
          >
            {connecting ? 'Connecting…' : 'Add'}
          </button>
        </div>
      </form>
    </div>
  );
}
