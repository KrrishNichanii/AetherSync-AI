import React, { useState, useRef, useEffect, useCallback, useMemo, createContext, useContext } from 'react';
import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import Prism from 'prismjs';
import 'prismjs/components/prism-javascript';
import 'prismjs/components/prism-typescript';
import 'prismjs/components/prism-jsx';
import 'prismjs/components/prism-tsx';
import 'prismjs/components/prism-java';
import 'prismjs/components/prism-python';
import 'prismjs/components/prism-json';
import 'prismjs/components/prism-sql';
import 'prismjs/components/prism-bash';
import 'prismjs/components/prism-yaml';
import 'prismjs/components/prism-css';
import 'prismjs/components/prism-markdown';
import {
  Send, Bot, User, Loader2, ChevronDown, ChevronUp, FileText, BookOpen,
  Zap, Sparkles, RotateCcw, Square, Copy, Check, Wifi, WifiOff, ExternalLink,
} from 'lucide-react';
import { chatApi } from '../services/api';
import { useDocumentStore } from '../store/documentStore';
import { useConversationStore } from '../store/conversationStore';
import type { ConversationMessage, CitationDto } from '../types';
import { clsx } from 'clsx';
import toast from 'react-hot-toast';
import MermaidDiagram from './MermaidDiagram';
import SourceChips from './SourceChips';
import { deriveSources, safeHttpUrl } from '../utils/sources';

/* ─── Prompt templates ──────────────────────────────────────────── */
const PROMPT_TEMPLATES = [
  {
    category: 'Explore the Knowledge Base',
    icon: '🧭',
    templates: [
      { label: 'Space Overview', prompt: 'Give me an overview of what is documented in the MFS Confluence space, grouped by topic, with links to the key pages.' },
      { label: 'Onboarding Guide', prompt: 'I am new to the team. Based on our Confluence documentation, what should I read first and what are the most important concepts?' },
      { label: 'Recent Decisions', prompt: 'Summarize the key technical decisions and their rationale documented in Confluence, citing the source pages.' },
    ],
  },
  {
    category: 'Architecture & Diagrams',
    icon: '🗺️',
    templates: [
      { label: 'System Architecture', prompt: 'Explain the system architecture described in our documentation and draw it as a Mermaid flowchart.' },
      { label: 'Request Flow', prompt: 'Walk through the main request/data flow end to end and illustrate it with a Mermaid sequence diagram.' },
      { label: 'Data Model', prompt: 'Describe the main entities and their relationships from the documentation as a Mermaid ER diagram.' },
    ],
  },
  {
    category: 'Operations',
    icon: '🛠️',
    templates: [
      { label: 'Runbooks', prompt: 'List the operational runbooks and incident procedures documented in Confluence, with links.' },
      { label: 'Deployment Steps', prompt: 'How do we deploy to production? Give step-by-step instructions with links to the source pages.' },
      { label: 'Glossary', prompt: 'Build a glossary of the internal terms and acronyms used across our documentation.' },
    ],
  },
];

/* ─── Prism token-to-React renderer (safe, no dangerouslySetInnerHTML) ─── */
function renderPrismTokens(tokens: (string | Prism.Token)[], keyPrefix = ''): React.ReactNode[] {
  return tokens.map((token, idx) => {
    const key = `${keyPrefix}-${idx}`;
    if (typeof token === 'string') {
      return token;
    }
    const className = `token ${token.type} ${Array.isArray(token.alias) ? token.alias.join(' ') : token.alias || ''}`;
    if (typeof token.content === 'string') {
      return (
        <span key={key} className={className}>
          {token.content}
        </span>
      );
    }
    if (Array.isArray(token.content)) {
      return (
        <span key={key} className={className}>
          {renderPrismTokens(token.content as (string | Prism.Token)[], key)}
        </span>
      );
    }
    return (
      <span key={key} className={className}>
        {String(token.content)}
      </span>
    );
  });
}

/* ─── Code block component ────────────────────────────────────────── */
const CodeBlock: React.FC<{ language: string; code: string }> = ({ language, code }) => {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    await navigator.clipboard.writeText(code);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
    toast.success('Code copied to clipboard');
  };

  const normalizedLang = (language || 'text').toLowerCase();
  const grammar = Prism.languages[normalizedLang] || Prism.languages.text;
  const tokens = grammar ? Prism.tokenize(code, grammar) : [code];

  return (
    <div className="my-3 rounded-xl border border-[#262626] bg-[#050505] overflow-hidden text-xs shadow-md">
      <div className="flex items-center justify-between px-3.5 py-2 bg-[#1C1C1C] border-b border-[#262626] text-slate-400 font-mono text-[11px]">
        <div className="flex items-center gap-2">
          <div className="flex gap-1.5">
            <div className="w-2.5 h-2.5 rounded-full bg-red-500/70" />
            <div className="w-2.5 h-2.5 rounded-full bg-yellow-500/70" />
            <div className="w-2.5 h-2.5 rounded-full bg-green-500/70" />
          </div>
          <span className="font-semibold uppercase tracking-wider text-neon-400 ml-1">{language || 'code'}</span>
        </div>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1.5 text-[11px] text-slate-400 hover:text-white px-2 py-1 rounded bg-[#161616] hover:bg-[#262626] transition-colors cursor-pointer"
        >
          {copied ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
          <span>{copied ? 'Copied!' : 'Copy'}</span>
        </button>
      </div>
      <div className="p-3.5 overflow-x-auto">
        <pre className="font-mono text-xs leading-relaxed text-slate-200 m-0 p-0 bg-transparent border-0">
          <code className={`language-${normalizedLang}`}>{renderPrismTokens(tokens, 'code')}</code>
        </pre>
      </div>
    </div>
  );
};

/* ─── ReactMarkdown Renderer ──────────────────────────────────────── */
/*
 * The components map is defined once at module scope. react-markdown treats each
 * entry as a component type, so recreating these functions on every render would
 * remount code blocks and Mermaid diagrams on every streamed token.
 */
const StreamingContext = createContext(false);

const MarkdownCode: NonNullable<Components['code']> = ({ className, children }) => {
  const isStreaming = useContext(StreamingContext);
  const match = /language-([\w-]+)/.exec(className || '');
  const codeString = String(children).replace(/\n$/, '');
  const language = match ? match[1].toLowerCase() : '';

  if (language === 'mermaid') {
    return <MermaidDiagram code={codeString} isStreaming={isStreaming} />;
  }
  if (match || codeString.includes('\n')) {
    return <CodeBlock language={language} code={codeString} />;
  }
  return (
    <code className="px-1.5 py-0.5 mx-0.5 bg-[#050505] text-neon-300 rounded text-[13px] font-mono border border-[#262626]">
      {children}
    </code>
  );
};

const MARKDOWN_COMPONENTS: Components = {
  p: ({ children }) => <p className="mb-2.5 last:mb-0 leading-relaxed text-slate-200">{children}</p>,
  h1: ({ children }) => <h1 className="text-xl font-bold text-white mt-4 mb-2 pb-1 border-b border-[#262626] first:mt-0">{children}</h1>,
  h2: ({ children }) => <h2 className="text-lg font-bold text-white mt-3.5 mb-1.5 first:mt-0">{children}</h2>,
  h3: ({ children }) => <h3 className="text-base font-semibold text-slate-100 mt-3 mb-1 first:mt-0">{children}</h3>,
  h4: ({ children }) => <h4 className="text-xs font-bold text-neon-300 uppercase tracking-wider mt-2.5 mb-1 first:mt-0">{children}</h4>,
  ul: ({ children }) => <ul className="list-disc ml-5 pl-1 space-y-1.5 my-2 text-slate-200">{children}</ul>,
  ol: ({ children }) => <ol className="list-decimal ml-5 pl-1 space-y-1.5 my-2 text-slate-200">{children}</ol>,
  li: ({ children }) => <li className="leading-relaxed pl-0.5">{children}</li>,
  blockquote: ({ children }) => (
    <blockquote className="border-l-4 border-neon-500 bg-neon-500/10 px-3.5 py-2 rounded-r-lg text-slate-300 italic my-2.5 text-xs leading-relaxed">
      {children}
    </blockquote>
  ),
  hr: () => <hr className="border-[#262626] my-3.5" />,
  a: ({ href, children }) => {
    // Inline citations from the model point back to Confluence; style them as source links.
    const external = !!safeHttpUrl(href);
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="inline-flex items-baseline gap-0.5 text-neon-400 hover:text-neon-300 underline decoration-neon-500/40 hover:decoration-neon-300 underline-offset-2 transition-colors font-medium"
      >
        {children}
        {external && <ExternalLink className="w-3 h-3 self-center opacity-70" aria-hidden="true" />}
      </a>
    );
  },
  strong: ({ children }) => <strong className="font-semibold text-white">{children}</strong>,
  em: ({ children }) => <em className="italic text-slate-300">{children}</em>,
  del: ({ children }) => <del className="line-through text-slate-400">{children}</del>,
  table: ({ children }) => (
    <div className="my-3 overflow-x-auto rounded-xl border border-[#262626] shadow-sm">
      <table className="min-w-full divide-y divide-[#262626] text-left text-xs">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-[#1C1C1C]">{children}</thead>,
  tbody: ({ children }) => <tbody className="divide-y divide-[#161616] bg-[#050505]">{children}</tbody>,
  th: ({ children }) => <th className="px-3.5 py-2.5 font-semibold text-white">{children}</th>,
  td: ({ children }) => <td className="px-3.5 py-2 text-slate-300">{children}</td>,
  code: MarkdownCode,
  pre: ({ children }) => <>{children}</>,
};

const REMARK_PLUGINS = [remarkGfm];

const MarkdownContent: React.FC<{ content: string; isStreaming?: boolean }> = ({ content, isStreaming = false }) => (
  <StreamingContext.Provider value={isStreaming}>
    <div className="text-sm leading-relaxed overflow-hidden text-slate-200 space-y-2">
      <ReactMarkdown remarkPlugins={REMARK_PLUGINS} components={MARKDOWN_COMPONENTS}>
        {content}
      </ReactMarkdown>
      {isStreaming && <span className="cursor-blink" />}
    </div>
  </StreamingContext.Provider>
);

/* ─── Citation card ─────────────────────────────────────────────── */
const CitationCard: React.FC<{ citation: CitationDto; index: number }> = ({ citation, index }) => {
  const [expanded, setExpanded] = useState(false);
  const score = citation.similarityScore ?? 0;
  const pct = (score * 100).toFixed(0);
  const barColor = score >= 0.8 ? 'bg-neon-500' : score >= 0.6 ? 'bg-amber-500' : 'bg-red-500';
  const textColor = score >= 0.8 ? 'text-neon-400' : score >= 0.6 ? 'text-amber-400' : 'text-red-400';

  const isConfluence = (citation.sourceType ?? citation.metadata?.source) === 'confluence';
  const title = citation.sourceTitle || citation.fileName;
  const url = safeHttpUrl(citation.sourceUrl);
  const ext = citation.fileName?.split('.').pop()?.toLowerCase();
  const extColors: Record<string, string> = { pdf: 'text-red-400', docx: 'text-blue-400', csv: 'text-green-400', md: 'text-neon-300' };

  return (
    <div className="border border-[#262626] rounded-xl overflow-hidden bg-[#0A0A0A] hover:border-[#3A3A3A] transition-colors">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-2.5 p-2.5 text-left cursor-pointer"
      >
        <span className="flex-shrink-0 w-5 h-5 bg-neon-500/15 text-neon-400 rounded-md text-xs flex items-center justify-center font-bold border border-neon-500/20">
          {index + 1}
        </span>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            {isConfluence
              ? <BookOpen className="w-3 h-3 flex-shrink-0 text-neon-400" />
              : <FileText className={clsx('w-3 h-3 flex-shrink-0', extColors[ext ?? ''] ?? 'text-slate-400')} />}
            <p className="text-xs text-slate-200 font-medium truncate">{title}</p>
            {citation.pageNumber && <span className="text-xs text-slate-500 flex-shrink-0">p.{citation.pageNumber}</span>}
          </div>
          <div className="flex items-center gap-2 mt-1">
            <div className="flex-1 h-1 bg-[#161616] rounded-full overflow-hidden">
              <div className={clsx('h-1 rounded-full', barColor)} style={{ width: `${pct}%` }} />
            </div>
            <span className={clsx('text-[10px] font-bold flex-shrink-0', textColor)}>{pct}%</span>
          </div>
        </div>
        {expanded ? <ChevronUp className="w-3.5 h-3.5 text-slate-500 flex-shrink-0" /> : <ChevronDown className="w-3.5 h-3.5 text-slate-500 flex-shrink-0" />}
      </button>
      {expanded && (
        <div className="px-3 pb-3 pt-0 border-t border-[#161616] animate-fade-in">
          <p className="text-xs text-slate-400 leading-relaxed mt-2 whitespace-pre-line">{citation.snippet}</p>
          {url && (
            <a
              href={url}
              target="_blank"
              rel="noopener noreferrer"
              className="mt-2 inline-flex items-center gap-1 text-[11px] font-medium text-neon-400 hover:text-neon-300"
            >
              Open in Confluence <ExternalLink className="w-3 h-3" />
            </a>
          )}
        </div>
      )}
    </div>
  );
};

/* ─── Message bubble ────────────────────────────────────────────── */
const MessageBubble: React.FC<{ message: ConversationMessage }> = ({ message }) => {
  const isUser = message.messageType === 'USER';
  const [showPassages, setShowPassages] = useState(false);
  const [copied, setCopied] = useState(false);
  const [showTime, setShowTime] = useState(false);

  // One chip per Confluence page / uploaded file (citations are per chunk).
  const sources = useMemo(() => deriveSources(message.citations), [message.citations]);
  const passageCount = message.citations?.length ?? 0;

  const handleCopy = async () => {
    await navigator.clipboard.writeText(message.content);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
    toast.success('Copied to clipboard');
  };

  const timeStr = message.createdAt
    ? new Date(message.createdAt).toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' })
    : '';

  return (
    <div
      className={clsx('flex gap-3 group', isUser ? 'flex-row-reverse' : 'flex-row')}
      onMouseEnter={() => setShowTime(true)}
      onMouseLeave={() => setShowTime(false)}
    >
      {/* Avatar */}
      <div className={clsx(
        'flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center shadow-sm mt-0.5',
        isUser
          ? 'bg-[#161616] border border-[#262626]'
          : 'bg-neon-500 shadow-neon-500/30'
      )}>
        {isUser ? <User className="w-3.5 h-3.5 text-slate-300" /> : <Bot className="w-3.5 h-3.5 text-black" />}
      </div>

      {/* Content */}
      <div className={clsx('max-w-[78%] min-w-0', isUser ? 'items-end' : 'items-start')}>
        <div className={clsx(
          'rounded-2xl px-4 py-3 text-sm',
          isUser
            ? 'bg-neon-500/10 border border-neon-500/30 rounded-tr-sm'
            : 'bg-[#161616] border border-[#262626] rounded-tl-sm'
        )}>
          {isUser ? (
            <p className="text-white leading-relaxed whitespace-pre-wrap">{message.content}</p>
          ) : (
            <MarkdownContent content={message.content} isStreaming={message.isStreaming} />
          )}

          {/* Source chips: Confluence page titles linking back to the original page */}
          {!isUser && !message.isStreaming && sources.length > 0 && (
            <div className="mt-3 pt-2.5 border-t border-[#262626]">
              <SourceChips sources={sources} />
            </div>
          )}
        </div>

        {/* Footer: actions */}
        <div className={clsx('flex items-center gap-2 mt-1.5', isUser ? 'justify-end' : 'justify-start')}>
          {timeStr && (
            <span className={clsx('text-xs text-slate-600 transition-opacity', showTime ? 'opacity-100' : 'opacity-0')}>
              {timeStr}
            </span>
          )}

          {!isUser && message.responseTimeMs !== undefined && message.responseTimeMs > 0 && (
            <span className="flex items-center gap-1 text-xs text-slate-600">
              <Zap className="w-2.5 h-2.5" />{message.responseTimeMs}ms
            </span>
          )}

          {!isUser && !message.isStreaming && passageCount > 0 && (
            <button
              onClick={() => setShowPassages(!showPassages)}
              className="flex items-center gap-1 text-xs text-slate-500 hover:text-neon-300 transition-colors cursor-pointer"
            >
              {passageCount} {passageCount === 1 ? 'passage' : 'passages'}
              {showPassages ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
            </button>
          )}

          {!isUser && !message.isStreaming && message.content && (
            <button
              onClick={handleCopy}
              className="opacity-0 group-hover:opacity-100 flex items-center gap-1 text-xs text-slate-500 hover:text-slate-300 transition-all cursor-pointer"
              title="Copy message"
            >
              {copied ? <Check className="w-3.5 h-3.5 text-neon-400" /> : <Copy className="w-3.5 h-3.5" />}
            </button>
          )}
        </div>

        {/* Retrieved passages (chunk-level detail) */}
        {!isUser && showPassages && passageCount > 0 && (
          <div className="mt-2 space-y-1.5 animate-fade-in">
            {message.citations!.map((c, i) => (
              <CitationCard key={i} citation={c} index={i} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

/* ─── Typing indicator ───────────────────────────────────────────── */
const TypingIndicator = () => (
  <div className="flex gap-3 animate-fade-in">
    <div className="w-8 h-8 flex-shrink-0 rounded-full bg-neon-500 flex items-center justify-center">
      <Bot className="w-3.5 h-3.5 text-black" />
    </div>
    <div className="bg-[#161616] border border-[#262626] rounded-2xl rounded-tl-sm px-4 py-3">
      <div className="flex items-center gap-1 h-4">
        <div className="typing-dot" />
        <div className="typing-dot" />
        <div className="typing-dot" />
      </div>
    </div>
  </div>
);

/* ─── Main ChatView ──────────────────────────────────────────────── */
const ChatView: React.FC = () => {
  const { selectedDocumentId, documents } = useDocumentStore();
  const {
    activeConversationId,
    messages: allMessages,
    streamingMode,
    setStreamingMode,
    appendMessage,
    updateLastMessageContent,
    updateMessageMetadata,
    newChat,
  } = useConversationStore();

  const messages = allMessages[activeConversationId] || [];

  const [input, setInput] = useState('');
  const [isLoading, setIsLoading] = useState(false);
  const [isStreaming, setIsStreaming] = useState(false);
  const [showTemplates, setShowTemplates] = useState(true);

  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const chatContainerRef = useRef<HTMLDivElement>(null);
  const shouldAutoScrollRef = useRef(true);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const selectedDoc = documents.find((d) => d.id === selectedDocumentId);

  const handleScroll = useCallback(() => {
    const el = chatContainerRef.current;
    if (!el) return;
    const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    // Keep auto-scroll active if user is near bottom (<120px)
    shouldAutoScrollRef.current = distanceToBottom < 120;
  }, []);

  useEffect(() => {
    if (shouldAutoScrollRef.current) {
      bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
    }
  }, [messages.length, isLoading, isStreaming]);

  /* ── Stream handler ── */
  const sendStreaming = useCallback(async (question: string, convId: string) => {
    const assistantMsgId = crypto.randomUUID();
    const startTime = Date.now();

    // Append empty streaming assistant bubble
    await appendMessage(convId, {
      id: assistantMsgId,
      messageType: 'ASSISTANT',
      content: '',
      createdAt: new Date().toISOString(),
      isStreaming: true,
    });

    setIsStreaming(true);

    const controller = new AbortController();
    abortRef.current = controller;

    // Fetch citations in parallel so it doesn't block UI after streaming ends
    const citationPromise = chatApi.searchSimilarity({
      query: question,
      documentId: selectedDocumentId ?? undefined,
      topK: 5,
    }).catch(() => null);

    try {
      const response = await chatApi.stream(
        {
          question,
          documentId: selectedDocumentId ?? undefined,
          topK: 5,
          conversationId: convId,
        },
        controller.signal
      );

      if (!response.ok) {
        let errorDetail = `HTTP ${response.status}`;
        try {
          const errData = await response.json();
          if (errData?.message) errorDetail = errData.message;
        } catch {
          // ignore JSON parsing errors
        }
        throw new Error(errorDetail);
      }
      if (!response.body) throw new Error('No response body');

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let accumulated = '';
      let streamEnded = false;
      let lastRenderTime = 0;
      const THROTTLE_MS = 35; // 35ms buffer to avoid excessive React renders on individual tokens

      const flushUpdate = (streaming: boolean) => {
        updateLastMessageContent(convId, assistantMsgId, accumulated, streaming);
        if (shouldAutoScrollRef.current) {
          bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
        }
      };

      const contentType = response.headers.get('content-type') || '';
      const isEventStream = contentType.includes('text/event-stream');

      while (!streamEnded) {
        const { done, value } = await reader.read();
        if (done) break;

        const textChunk = decoder.decode(value, { stream: true });

        if (isEventStream) {
          buffer += textChunk;
          // SSE events are separated by double newlines \n\n or \r\n\r\n
          const eventBlocks = buffer.split(/\r?\n\r?\n/);
          // The last piece is either incomplete or trailing after the delimiter
          buffer = eventBlocks.pop() || '';

          for (const block of eventBlocks) {
            if (!block) continue;

            const lines = block.split(/\r?\n/);
            const dataLines: string[] = [];

            for (const line of lines) {
              if (line.startsWith('data:')) {
                const data = line.slice(5);
                dataLines.push(data);
              }
            }

            if (dataLines.length > 0) {
              const eventData = dataLines.join('\n');
              if (eventData.trim() === '[DONE]') {
                streamEnded = true;
                break;
              }
              accumulated += eventData;
            }
          }
        } else {
          if (textChunk.includes('[DONE]')) {
            accumulated += textChunk.replace('[DONE]', '');
            streamEnded = true;
          } else {
            accumulated += textChunk;
          }
        }

        const now = Date.now();
        if (now - lastRenderTime >= THROTTLE_MS || streamEnded) {
          lastRenderTime = now;
          flushUpdate(true);
        }
      }

      // Flush remaining SSE buffer if any
      if (!streamEnded && isEventStream && buffer.trim()) {
        const lines = buffer.split(/\r?\n/);
        const dataLines: string[] = [];
        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5);
            dataLines.push(data);
          }
        }
        if (dataLines.length > 0) {
          const eventData = dataLines.join('\n');
          if (eventData.trim() !== '[DONE]') {
            accumulated += eventData;
          }
        }
      }

      try {
        await reader.cancel();
      } catch {
        // Ignore reader cancellation error
      }

      const responseTimeMs = Date.now() - startTime;

      // Stream completed — IMMEDIATELY remove streaming state
      setIsStreaming(false);
      abortRef.current = null;
      updateLastMessageContent(convId, assistantMsgId, accumulated, false);
      if (shouldAutoScrollRef.current) {
        bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
      }

      // Attach citations once similarity search finishes in background
      const simRes = await citationPromise;
      if (simRes?.data?.matches?.length) {
        await updateMessageMetadata(convId, assistantMsgId, {
          citations: simRes.data.matches,
          responseTimeMs,
          isStreaming: false,
        });
      } else {
        await updateMessageMetadata(convId, assistantMsgId, {
          responseTimeMs,
          isStreaming: false,
        });
      }

    } catch (err: unknown) {
      if ((err as Error).name === 'AbortError') {
        updateLastMessageContent(convId, assistantMsgId, 'Response generation stopped.', false);
      } else {
        const msg = err instanceof Error ? err.message : 'Stream failed';
        toast.error(msg);
        updateLastMessageContent(convId, assistantMsgId, `❌ ${msg}`, false);
      }
    } finally {
      setIsStreaming(false);
      abortRef.current = null;
    }
  }, [selectedDocumentId, appendMessage, updateLastMessageContent, updateMessageMetadata]);

  /* ── Normal query handler ── */
  const sendNormal = useCallback(async (question: string, convId: string) => {
    setIsLoading(true);
    try {
      const res = await chatApi.query({
        question,
        documentId: selectedDocumentId ?? undefined,
        topK: 5,
        conversationId: convId,
      });

      await appendMessage(convId, {
        id: crypto.randomUUID(),
        messageType: 'ASSISTANT',
        content: res.data.answer,
        citations: res.data.citations,
        responseTimeMs: res.data.responseTimeMs,
        createdAt: new Date().toISOString(),
      });
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Something went wrong';
      toast.error(msg);
      await appendMessage(convId, {
        id: crypto.randomUUID(),
        messageType: 'ASSISTANT',
        content: `❌ ${msg}`,
        createdAt: new Date().toISOString(),
      });
    } finally {
      setIsLoading(false);
    }
  }, [selectedDocumentId, appendMessage]);

  /* ── Send dispatcher ── */
  const sendMessage = useCallback(async (question: string) => {
    if (!question.trim() || isLoading || isStreaming) return;
    setShowTemplates(false);

    const convId = activeConversationId || crypto.randomUUID();
    const userMsg: ConversationMessage = {
      id: crypto.randomUUID(),
      messageType: 'USER',
      content: question.trim(),
      createdAt: new Date().toISOString(),
    };

    setInput('');
    if (inputRef.current) { inputRef.current.style.height = 'auto'; }

    await appendMessage(convId, userMsg);

    if (streamingMode) {
      await sendStreaming(question.trim(), convId);
    } else {
      await sendNormal(question.trim(), convId);
    }
    inputRef.current?.focus();
  }, [isLoading, isStreaming, activeConversationId, streamingMode, appendMessage, sendStreaming, sendNormal]);

  const stopStream = () => {
    abortRef.current?.abort();
  };

  const handleNewChat = () => {
    newChat();
    setShowTemplates(true);
    abortRef.current?.abort();
  };

  const isBusy = isLoading || isStreaming;
  const charCount = input.length;

  return (
    <div className="flex flex-col h-full bg-[#0A0A0A]">
      {/* ── Chat header ── */}
      <div className="flex items-center justify-between px-6 py-3 border-b border-[#161616] flex-shrink-0 bg-[#0F0F0F]">
        <div className="flex items-center gap-2.5">
          <div className="w-6 h-6 bg-gradient-to-br from-neon-500 to-neon-700 rounded-lg flex items-center justify-center">
            <Sparkles className="w-3 h-3 text-black" />
          </div>
          <span className="text-sm font-semibold text-white">
            {selectedDoc ? selectedDoc.filename : 'Knowledge Base'}
          </span>
          {selectedDoc && (
            <span className="text-[10px] font-semibold bg-neon-500/15 text-neon-400 px-2 py-0.5 rounded-full border border-neon-500/25 uppercase tracking-wide">
              Focused
            </span>
          )}
          {!selectedDoc && (
            <span className="hidden sm:inline-flex items-center gap-1 text-[10px] font-semibold bg-neon-500/10 text-neon-400 px-2 py-0.5 rounded-full border border-neon-500/25 uppercase tracking-wide">
              <BookOpen className="w-2.5 h-2.5" /> Confluence + uploads
            </span>
          )}
        </div>

        <div className="flex items-center gap-3">
          {/* Streaming toggle */}
          <button
            onClick={() => setStreamingMode(!streamingMode)}
            className={clsx(
              'flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg border transition-all cursor-pointer',
              streamingMode
                ? 'bg-neon-500/10 border-neon-500/30 text-neon-400 hover:bg-neon-500/15'
                : 'bg-[#161616] border-[#262626] text-slate-400 hover:text-white'
            )}
            title={streamingMode ? 'Streaming ON — click to disable' : 'Streaming OFF — click to enable'}
          >
            {streamingMode
              ? <><Wifi className="w-3 h-3" /> Streaming</>
              : <><WifiOff className="w-3 h-3" /> Normal</>}
          </button>

          {/* New chat */}
          {messages.length > 0 && (
            <button
              onClick={handleNewChat}
              className="flex items-center gap-1.5 text-xs text-slate-400 hover:text-white border border-[#262626] hover:border-[#3A3A3A] px-3 py-1.5 rounded-lg transition-colors cursor-pointer"
            >
              <RotateCcw className="w-3 h-3" /> New chat
            </button>
          )}
        </div>
      </div>

      {/* ── Messages area ── */}
      <div ref={chatContainerRef} onScroll={handleScroll} className="flex-1 overflow-y-auto px-6 py-6 space-y-5">
        {/* Welcome screen */}
        {messages.length === 0 && showTemplates && (
          <div className="animate-fade-in max-w-2xl mx-auto">
            <div className="text-center mb-8">
              <div className="relative inline-block mb-4">
                <div className="absolute inset-0 bg-gradient-to-br from-neon-500 to-neon-700 rounded-2xl blur-xl opacity-40" />
                <div className="relative w-16 h-16 bg-gradient-to-br from-neon-500 to-neon-700 rounded-2xl flex items-center justify-center shadow-xl shadow-neon-500/30">
                  <Bot className="w-8 h-8 text-black" />
                </div>
              </div>
              <h2 className="text-2xl font-bold text-white mb-2">AetherSync AI</h2>
              <p className="text-slate-400 text-sm leading-relaxed max-w-sm mx-auto">
                {selectedDoc
                  ? `Chatting with "${selectedDoc.filename}". Ask anything or pick a template below.`
                  : 'Ask anything about the team’s Confluence documentation and your uploads. Answers cite their sources and can include live diagrams.'}
              </p>
              {streamingMode && (
                <div className="mt-3 inline-flex items-center gap-1.5 text-xs text-neon-400 bg-neon-500/10 border border-neon-500/20 px-3 py-1 rounded-full">
                  <Wifi className="w-3 h-3" /> Real-time streaming enabled
                </div>
              )}
            </div>

            {/* Template grid */}
            <div className="space-y-3">
              {PROMPT_TEMPLATES.map((cat) => (
                <div key={cat.category} className="rounded-2xl border border-[#262626] bg-[#161616] p-4">
                  <div className="flex items-center gap-2 mb-3">
                    <span className="text-base">{cat.icon}</span>
                    <h3 className="text-xs font-bold uppercase tracking-wider text-neon-400">{cat.category}</h3>
                  </div>
                  <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                    {cat.templates.map((t) => (
                      <button
                        key={t.label}
                        onClick={() => sendMessage(t.prompt)}
                        className="text-left p-3 rounded-xl bg-[#0F0F0F] hover:bg-neon-500/5 border border-[#262626] hover:border-neon-500/40 transition-all text-xs text-slate-200 hover:text-white group cursor-pointer"
                      >
                        <span className="font-medium block mb-0.5">{t.label}</span>
                        <span className="text-slate-500 text-[10px] line-clamp-2 group-hover:text-slate-400 transition-colors">{t.prompt.slice(0, 55)}…</span>
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Message list */}
        {messages.map((msg) => (
          <MessageBubble key={msg.id} message={msg} />
        ))}

        {/* Typing indicator (non-streaming mode) */}
        {isLoading && !isStreaming && <TypingIndicator />}

        <div ref={bottomRef} />
      </div>

      {/* ── Input area ── */}
      <div className="px-6 py-4 border-t border-[#161616] flex-shrink-0 bg-[#0F0F0F]">
        {/* Streaming status */}
        {isStreaming && (
          <div className="flex items-center gap-2 mb-2">
            <div className="flex gap-0.5">
              <div className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-bounce" style={{ animationDelay: '0ms' }} />
              <div className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-bounce" style={{ animationDelay: '100ms' }} />
              <div className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-bounce" style={{ animationDelay: '200ms' }} />
            </div>
            <span className="text-xs text-emerald-400 font-medium">AI is responding…</span>
          </div>
        )}

        <div className={clsx(
          'flex gap-3 items-end bg-[#161616] border rounded-2xl px-4 py-3 transition-all',
          isBusy ? 'border-[#262626]' : 'border-[#262626] focus-within:border-neon-500/50'
        )}>
          <textarea
            ref={inputRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(input); }
            }}
            placeholder={
              isBusy ? 'Waiting for response…' :
              selectedDoc ? `Ask about "${selectedDoc.filename}"…` :
              'Ask about your Confluence docs or uploads…'
            }
            rows={1}
            disabled={isBusy}
            className="flex-1 bg-transparent text-sm text-white placeholder-slate-500 resize-none outline-none leading-relaxed disabled:opacity-60"
            style={{ minHeight: '24px', maxHeight: '144px' }}
            onInput={(e) => {
              const t = e.currentTarget;
              t.style.height = 'auto';
              t.style.height = `${Math.min(t.scrollHeight, 144)}px`;
            }}
          />

          {/* Stop / Send button */}
          {isStreaming ? (
            <button
              onClick={stopStream}
              className="flex-shrink-0 p-2 bg-red-500/20 hover:bg-red-500/30 border border-red-500/30 text-red-400 rounded-xl transition-colors cursor-pointer"
              title="Stop generation"
            >
              <Square className="w-4 h-4" />
            </button>
          ) : (
            <button
              onClick={() => sendMessage(input)}
              disabled={!input.trim() || isBusy}
              className="flex-shrink-0 p-2 bg-neon-600 hover:bg-neon-500 disabled:opacity-40 disabled:cursor-not-allowed text-black rounded-xl transition-all shadow-sm shadow-neon-500/25 cursor-pointer"
            >
              {isLoading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Send className="w-4 h-4" />}
            </button>
          )}
        </div>

        {/* Footer hint */}
        <div className="flex items-center justify-between mt-2 px-1">
          <p className="text-[11px] text-slate-600">
            Enter to send · Shift+Enter for new line
          </p>
          <div className="flex items-center gap-2">
            {charCount > 0 && (
              <span className={clsx('text-[11px]', charCount > 1000 ? 'text-amber-500' : 'text-slate-600')}>
                {charCount}
              </span>
            )}
            {messages.length > 0 && (
              <span className="text-[11px] text-slate-600">{messages.length} msgs</span>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ChatView;
