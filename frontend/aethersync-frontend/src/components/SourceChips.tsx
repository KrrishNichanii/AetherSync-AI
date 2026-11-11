import React from 'react';
import { BookOpen, ExternalLink, FileText } from 'lucide-react';
import type { SourceReferenceDto } from '../types';

interface SourceChipsProps {
  sources: SourceReferenceDto[];
}

/** Compact, clickable source references rendered under an AI answer. */
const SourceChips: React.FC<SourceChipsProps> = ({ sources }) => {
  if (!sources.length) return null;

  return (
    <div className="mt-2.5">
      <p className="text-[10px] font-semibold uppercase tracking-wider text-slate-500 mb-1.5">Sources</p>
      <div className="flex flex-wrap gap-1.5">
        {sources.map((s) => {
          const score = s.bestScore !== null ? `${Math.round(s.bestScore * 100)}% match` : undefined;
          const body = (
            <>
              <span className="flex-shrink-0 w-4 h-4 rounded bg-neon-500/15 text-neon-400 text-[10px] font-bold flex items-center justify-center">
                {s.index}
              </span>
              {s.sourceType === 'confluence'
                ? <BookOpen className="w-3 h-3 flex-shrink-0 text-neon-400" />
                : <FileText className="w-3 h-3 flex-shrink-0 text-slate-400" />}
              <span className="truncate max-w-[220px]">{s.title}</span>
              {s.url && <ExternalLink className="w-3 h-3 flex-shrink-0 opacity-60 group-hover:opacity-100" />}
            </>
          );

          const base =
            'group inline-flex items-center gap-1.5 max-w-full px-2 py-1 rounded-lg border text-xs transition-colors';

          return s.url ? (
            <a
              key={`${s.index}-${s.url}`}
              href={s.url}
              target="_blank"
              rel="noopener noreferrer"
              title={[s.title, score, s.url].filter(Boolean).join(' · ')}
              className={`${base} bg-[#161616] border-[#262626] text-slate-200 hover:border-neon-500/60 hover:text-neon-300 hover:bg-neon-500/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-neon-500`}
            >
              {body}
            </a>
          ) : (
            <span
              key={`${s.index}-${s.title}`}
              title={[s.title, score, 'Uploaded document'].filter(Boolean).join(' · ')}
              className={`${base} bg-[#161616] border-[#262626] text-slate-300`}
            >
              {body}
            </span>
          );
        })}
      </div>
    </div>
  );
};

export default SourceChips;
