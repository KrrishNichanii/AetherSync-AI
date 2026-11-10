import React, { useEffect, useId, useRef, useState } from 'react';
import { AlertTriangle, Check, Code2, Copy, Loader2, Maximize2, Minus, Network, Plus, RotateCcw, X } from 'lucide-react';
import { clsx } from 'clsx';
import toast from 'react-hot-toast';
import type { Mermaid } from 'mermaid';

/* ─── Lazy, one-time mermaid initialisation ─── */
let mermaidPromise: Promise<Mermaid> | null = null;

function loadMermaid(): Promise<Mermaid> {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        // 'strict' sanitises labels and disables click handlers/scripts in diagrams.
        securityLevel: 'strict',
        theme: 'base',
        fontFamily: 'Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
        themeVariables: {
          darkMode: true,
          background: '#0A0A0A',
          primaryColor: '#161616',
          primaryTextColor: '#F2F2F2',
          primaryBorderColor: '#2FFF88',
          secondaryColor: '#1C1C1C',
          secondaryTextColor: '#F2F2F2',
          secondaryBorderColor: '#1BC567',
          tertiaryColor: '#111111',
          tertiaryTextColor: '#F2F2F2',
          tertiaryBorderColor: '#262626',
          lineColor: '#2FFF88',
          textColor: '#E4E4E4',
          mainBkg: '#161616',
          nodeBorder: '#2FFF88',
          clusterBkg: '#0F0F0F',
          clusterBorder: '#262626',
          edgeLabelBackground: '#0A0A0A',
          actorBkg: '#161616',
          actorBorder: '#2FFF88',
          actorTextColor: '#F2F2F2',
          signalColor: '#2FFF88',
          signalTextColor: '#E4E4E4',
          noteBkgColor: '#1C1C1C',
          noteTextColor: '#E4E4E4',
          noteBorderColor: '#3A3A3A',
          fontSize: '14px',
        },
      });
      return mermaid;
    });
  }
  return mermaidPromise;
}

interface MermaidDiagramProps {
  code: string;
  /** While true, parse errors are expected (the fence is still arriving) and are not shown. */
  isStreaming?: boolean;
}

const RENDER_DEBOUNCE_MS = 250;
const MIN_ZOOM = 0.4;
const MAX_ZOOM = 3;

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ code, isStreaming = false }) => {
  const reactId = useId();
  const baseId = `mmd-${reactId.replace(/[^a-zA-Z0-9_-]/g, '')}`;
  const renderSeq = useRef(0);

  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  // Source text of the last completed render attempt; "rendering" is derived from it.
  const [settledSource, setSettledSource] = useState<string | null>(null);
  const [showCode, setShowCode] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [fullscreen, setFullscreen] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    const source = code.trim();
    if (!source) return;

    let cancelled = false;

    const timer = window.setTimeout(async () => {
      try {
        const mermaid = await loadMermaid();
        // parse() first so invalid/partial input never reaches render(), which
        // would otherwise inject an error graphic into the document body.
        const valid = await mermaid.parse(source, { suppressErrors: true });
        if (cancelled) return;
        if (!valid) {
          if (!isStreaming) {
            try {
              await mermaid.parse(source);
            } catch (e) {
              if (!cancelled) setError(e instanceof Error ? e.message : String(e));
            }
          }
          return; // keep the last good diagram on screen while streaming
        }
        const id = `${baseId}-${++renderSeq.current}`;
        const { svg: rendered } = await mermaid.render(id, source);
        if (!cancelled) {
          setSvg(rendered);
          setError(null);
        }
      } catch (e) {
        if (!cancelled && !isStreaming) {
          setError(e instanceof Error ? e.message : String(e));
        }
      } finally {
        if (!cancelled) setSettledSource(source);
      }
    }, isStreaming ? RENDER_DEBOUNCE_MS : 0);

    return () => {
      cancelled = true;
      window.clearTimeout(timer);
    };
  }, [code, isStreaming, baseId]);

  const isRendering = code.trim() !== '' && settledSource !== code.trim();

  useEffect(() => {
    if (!fullscreen) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setFullscreen(false);
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [fullscreen]);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
    toast.success('Diagram source copied');
  };

  const zoomBy = (delta: number) =>
    setZoom((z) => Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, Math.round((z + delta) * 10) / 10)));

  const toolbarButton =
    'flex items-center gap-1 text-[11px] text-slate-400 hover:text-white px-1.5 py-1 rounded bg-[#161616] hover:bg-[#262626] transition-colors cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed';

  const diagram = (inModal: boolean) => (
    <div
      className={clsx('overflow-auto', inModal ? 'flex-1 p-6' : 'p-4 max-h-[520px]')}
      onWheel={(e) => {
        if (e.ctrlKey || e.metaKey) {
          e.preventDefault();
          zoomBy(e.deltaY < 0 ? 0.1 : -0.1);
        }
      }}
    >
      {svg ? (
        <div
          className="mermaid-svg mx-auto origin-top transition-transform duration-150 [&_svg]:max-w-none [&_svg]:mx-auto"
          style={{ transform: `scale(${zoom})` }}
          // SVG produced by mermaid with securityLevel 'strict' (DOMPurify-sanitised).
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      ) : error ? null : (
        <div className="flex items-center justify-center gap-2 py-10 text-xs text-slate-500">
          <Loader2 className="w-4 h-4 animate-spin text-neon-400" />
          {isStreaming ? 'Diagram is being generated…' : 'Rendering diagram…'}
        </div>
      )}
    </div>
  );

  const toolbar = (inModal: boolean) => (
    <div className="flex items-center justify-between px-3.5 py-2 bg-[#1C1C1C] border-b border-[#262626] font-mono text-[11px]">
      <div className="flex items-center gap-2 text-slate-400">
        <Network className="w-3.5 h-3.5 text-neon-400" />
        <span className="font-semibold uppercase tracking-wider text-neon-400">mermaid</span>
        {isRendering && svg && <Loader2 className="w-3 h-3 animate-spin text-slate-500" aria-label="Updating diagram" />}
      </div>
      <div className="flex items-center gap-1">
        {!showCode && svg && (
          <>
            <button onClick={() => zoomBy(-0.2)} className={toolbarButton} title="Zoom out" disabled={zoom <= MIN_ZOOM}>
              <Minus className="w-3.5 h-3.5" />
            </button>
            <span className="w-10 text-center text-slate-500">{Math.round(zoom * 100)}%</span>
            <button onClick={() => zoomBy(0.2)} className={toolbarButton} title="Zoom in" disabled={zoom >= MAX_ZOOM}>
              <Plus className="w-3.5 h-3.5" />
            </button>
            <button onClick={() => setZoom(1)} className={toolbarButton} title="Reset zoom">
              <RotateCcw className="w-3.5 h-3.5" />
            </button>
          </>
        )}
        <button
          onClick={() => setShowCode((v) => !v)}
          className={toolbarButton}
          title={showCode ? 'Show diagram' : 'Show Mermaid source'}
        >
          {showCode ? <Network className="w-3.5 h-3.5" /> : <Code2 className="w-3.5 h-3.5" />}
        </button>
        <button onClick={handleCopy} className={toolbarButton} title="Copy Mermaid source">
          {copied ? <Check className="w-3.5 h-3.5 text-neon-400" /> : <Copy className="w-3.5 h-3.5" />}
        </button>
        {inModal ? (
          <button onClick={() => setFullscreen(false)} className={toolbarButton} title="Close (Esc)">
            <X className="w-3.5 h-3.5" />
          </button>
        ) : (
          <button onClick={() => setFullscreen(true)} className={toolbarButton} title="Fullscreen" disabled={!svg}>
            <Maximize2 className="w-3.5 h-3.5" />
          </button>
        )}
      </div>
    </div>
  );

  const body = (inModal: boolean) =>
    showCode ? (
      <pre className="p-3.5 overflow-auto font-mono text-xs leading-relaxed text-slate-200 m-0 bg-transparent">
        <code>{code}</code>
      </pre>
    ) : (
      diagram(inModal)
    );

  return (
    <>
      <div className="my-3 rounded-xl border border-[#262626] bg-[#050505] overflow-hidden text-xs shadow-md">
        {toolbar(false)}
        {error && !isStreaming && (
          <div className="flex items-start gap-2 px-3.5 py-2.5 bg-red-500/10 border-b border-red-500/20 text-red-300">
            <AlertTriangle className="w-3.5 h-3.5 flex-shrink-0 mt-0.5" />
            <div className="min-w-0">
              <p className="font-semibold text-[11px]">Could not render this diagram</p>
              <p className="text-[11px] text-red-300/80 whitespace-pre-wrap break-words line-clamp-4">{error}</p>
            </div>
          </div>
        )}
        {error && !isStreaming && !svg && !showCode ? (
          <pre className="p-3.5 overflow-auto font-mono text-xs leading-relaxed text-slate-300 m-0">
            <code>{code}</code>
          </pre>
        ) : (
          body(false)
        )}
      </div>

      {fullscreen && svg && (
        <div
          className="fixed inset-0 z-50 bg-black/80 backdrop-blur-sm flex items-center justify-center p-4 animate-fade-in"
          onClick={() => setFullscreen(false)}
          role="dialog"
          aria-modal="true"
          aria-label="Diagram"
        >
          <div
            className="w-full h-full max-w-6xl flex flex-col rounded-2xl border border-[#262626] bg-[#0A0A0A] overflow-hidden"
            onClick={(e) => e.stopPropagation()}
          >
            {toolbar(true)}
            {body(true)}
          </div>
        </div>
      )}
    </>
  );
};

export default MermaidDiagram;
