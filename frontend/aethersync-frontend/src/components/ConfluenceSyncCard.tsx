import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AlertTriangle, BookOpen, CheckCircle2, ChevronDown, ChevronUp, Loader2, RefreshCw, XCircle } from 'lucide-react';
import { clsx } from 'clsx';
import toast from 'react-hot-toast';
import axios from 'axios';
import { syncApi } from '../services/api';
import { useAuthStore } from '../store/authStore';
import type { ConfluenceSyncStatusDto, SyncRunStatus } from '../types';

const POLL_WHILE_RUNNING_MS = 3000;
const POLL_IDLE_MS = 60000;

function timeAgo(iso: string | null): string {
  if (!iso) return 'never';
  const diff = Date.now() - new Date(iso).getTime();
  const mins = Math.round(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

const STATUS_STYLE: Record<SyncRunStatus, { label: string; className: string; icon: React.ReactNode }> = {
  RUNNING: { label: 'Syncing', className: 'text-neon-400', icon: <Loader2 className="w-3 h-3 animate-spin" /> },
  SUCCESS: { label: 'Up to date', className: 'text-neon-400', icon: <CheckCircle2 className="w-3 h-3" /> },
  PARTIAL_SUCCESS: { label: 'Partial', className: 'text-amber-400', icon: <AlertTriangle className="w-3 h-3" /> },
  FAILED: { label: 'Failed', className: 'text-red-400', icon: <XCircle className="w-3 h-3" /> },
};

function errorMessage(err: unknown, fallback: string): string {
  if (axios.isAxiosError(err)) {
    return (err.response?.data as { message?: string } | undefined)?.message ?? fallback;
  }
  return err instanceof Error ? err.message : fallback;
}

const ConfluenceSyncCard: React.FC = () => {
  const isAdmin = useAuthStore((s) => s.user?.role === 'ADMIN');
  const [status, setStatus] = useState<ConfluenceSyncStatusDto | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [triggering, setTriggering] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const wasRunning = useRef(false);

  const load = useCallback(async () => {
    try {
      const res = await syncApi.status();
      setStatus(res.data);
      setLoadFailed(false);

      // Notify once when a run we were watching finishes.
      if (wasRunning.current && !res.data.running && res.data.lastRun) {
        const run = res.data.lastRun;
        const summary = `${run.pagesAdded} added · ${run.pagesUpdated} updated · ${run.pagesDeleted} removed`;
        if (run.status === 'SUCCESS') toast.success(`Confluence sync complete: ${summary}`);
        else if (run.status === 'PARTIAL_SUCCESS') toast(`Sync finished with ${run.pagesFailed} failed page(s)`, { icon: '⚠️' });
        else toast.error('Confluence sync failed');
      }
      wasRunning.current = res.data.running;
    } catch {
      setLoadFailed(true);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    const interval = window.setInterval(load, status?.running ? POLL_WHILE_RUNNING_MS : POLL_IDLE_MS);
    return () => window.clearInterval(interval);
  }, [load, status?.running]);

  const handleSync = async () => {
    setTriggering(true);
    try {
      await syncApi.trigger();
      wasRunning.current = true;
      toast.success('Confluence sync started');
      await load();
    } catch (err) {
      toast.error(errorMessage(err, 'Could not start sync'));
    } finally {
      setTriggering(false);
    }
  };

  if (loadFailed && !status) {
    return null; // backend without the sync API, or transient error; don't clutter the sidebar
  }

  const run = status?.currentRun ?? status?.lastRun ?? null;
  const runStyle = status?.running ? STATUS_STYLE.RUNNING : run ? STATUS_STYLE[run.status] : null;

  return (
    <div className="rounded-xl border border-[#262626] bg-[#161616] overflow-hidden">
      <div className="flex items-center gap-2.5 p-3">
        <div className="p-1.5 rounded-lg bg-neon-500/10 border border-neon-500/20 flex-shrink-0">
          <BookOpen className="w-4 h-4 text-neon-400" />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <p className="text-xs font-semibold text-white truncate">Confluence</p>
            {status && (
              <span className="text-[10px] font-mono font-semibold text-neon-400 bg-neon-500/10 border border-neon-500/20 px-1.5 rounded">
                {status.spaceKey}
              </span>
            )}
          </div>
          {!status ? (
            <p className="text-[11px] text-slate-500 mt-0.5">Loading…</p>
          ) : !status.configured ? (
            <p className="text-[11px] text-amber-400 mt-0.5">Not configured</p>
          ) : (
            <p className="text-[11px] text-slate-500 mt-0.5 truncate">
              {status.syncedPages} pages · {status.indexedChunks} chunks · {timeAgo(status.lastSuccessfulSyncAt)}
            </p>
          )}
        </div>
        {status?.configured && isAdmin && (
          <button
            onClick={handleSync}
            disabled={triggering || status.running}
            aria-label="Sync Confluence now"
            title={status.running ? 'Sync in progress' : 'Sync now'}
            className="p-1.5 rounded-lg text-slate-400 hover:text-neon-400 hover:bg-neon-500/10 disabled:opacity-60 disabled:cursor-not-allowed transition-colors cursor-pointer focus-visible:ring-2 focus-visible:ring-neon-500"
          >
            <RefreshCw className={clsx('w-3.5 h-3.5', (triggering || status.running) && 'animate-spin text-neon-400')} />
          </button>
        )}
      </div>

      {status && !status.configured && (
        <p className="px-3 pb-3 -mt-1 text-[11px] text-slate-500 leading-relaxed">
          Set <code className="text-slate-300">CONFLUENCE_USERNAME</code> and{' '}
          <code className="text-slate-300">CONFLUENCE_API_TOKEN</code> on the backend to enable sync.
        </p>
      )}

      {status?.configured && runStyle && run && (
        <>
          <button
            onClick={() => setShowDetails((v) => !v)}
            className="w-full flex items-center justify-between px-3 py-1.5 border-t border-[#262626] text-[11px] cursor-pointer hover:bg-white/[0.02]"
          >
            <span className={clsx('flex items-center gap-1.5 font-medium', runStyle.className)}>
              {runStyle.icon}
              {runStyle.label}
              {status.running && run.pagesScanned > 0 && (
                <span className="text-slate-500 font-normal">
                  · {run.pagesAdded + run.pagesUpdated + run.pagesUnchanged + run.pagesFailed}/{run.pagesScanned}
                </span>
              )}
            </span>
            {showDetails ? <ChevronUp className="w-3 h-3 text-slate-500" /> : <ChevronDown className="w-3 h-3 text-slate-500" />}
          </button>
          {showDetails && (
            <div className="px-3 pb-3 pt-1 text-[11px] text-slate-400 space-y-1.5 animate-fade-in">
              <div className="grid grid-cols-3 gap-1.5 text-center">
                {[
                  ['added', run.pagesAdded],
                  ['updated', run.pagesUpdated],
                  ['removed', run.pagesDeleted],
                  ['unchanged', run.pagesUnchanged],
                  ['failed', run.pagesFailed],
                  ['chunks', run.chunksWritten],
                ].map(([label, value]) => (
                  <div key={label} className="bg-[#0A0A0A] border border-[#262626] rounded-lg py-1">
                    <p className={clsx('text-xs font-bold', label === 'failed' && Number(value) > 0 ? 'text-red-400' : 'text-slate-200')}>
                      {value}
                    </p>
                    <p className="text-[10px] text-slate-500">{label}</p>
                  </div>
                ))}
              </div>
              <p className="text-slate-500">
                {run.triggerType.toLowerCase()} run{run.triggeredBy ? ` by ${run.triggeredBy}` : ''} · started {timeAgo(run.startedAt)}
                {run.durationMs != null && ` · ${(run.durationMs / 1000).toFixed(1)}s`}
              </p>
              {status.scheduleEnabled && <p className="text-slate-600 font-mono">schedule: {status.cron}</p>}
              {run.errorMessage && (
                <p className="text-red-300/90 bg-red-500/10 border border-red-500/20 rounded-lg px-2 py-1.5 whitespace-pre-wrap break-words max-h-32 overflow-y-auto">
                  {run.errorMessage}
                </p>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
};

export default ConfluenceSyncCard;
