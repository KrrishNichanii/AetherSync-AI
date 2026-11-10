import type { CitationDto, SourceReferenceDto, SourceType } from '../types';

/** Only allow absolute http(s) links to be rendered as clickable sources. */
export function safeHttpUrl(url: string | null | undefined): string | null {
  if (!url) return null;
  try {
    const parsed = new URL(url);
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? parsed.toString() : null;
  } catch {
    return null;
  }
}

function citationSourceType(c: CitationDto): SourceType {
  if (c.sourceType) return c.sourceType;
  return c.metadata?.source === 'confluence' ? 'confluence' : 'upload';
}

function citationTitle(c: CitationDto): string {
  const metaTitle = typeof c.metadata?.pageTitle === 'string' ? (c.metadata.pageTitle as string) : undefined;
  return c.sourceTitle || metaTitle || c.fileName || 'Untitled source';
}

function citationUrl(c: CitationDto): string | null {
  const metaUrl = typeof c.metadata?.url === 'string' ? (c.metadata.url as string) : undefined;
  return safeHttpUrl(c.sourceUrl ?? metaUrl);
}

/**
 * Collapses chunk-level citations into one entry per source (Confluence page or
 * uploaded file), preserving retrieval order. Mirrors the backend grouping so the
 * numbering matches the "[Source n]" labels the model was given.
 */
export function deriveSources(citations: CitationDto[] | undefined): SourceReferenceDto[] {
  if (!citations?.length) return [];
  const byKey = new Map<string, SourceReferenceDto>();

  for (const c of citations) {
    const url = citationUrl(c);
    const key = url ?? (c.documentId ? `doc:${c.documentId}` : `title:${citationTitle(c)}`);
    const existing = byKey.get(key);
    const score = typeof c.similarityScore === 'number' ? c.similarityScore : null;

    if (existing) {
      if (score !== null && (existing.bestScore === null || score > existing.bestScore)) {
        existing.bestScore = score;
      }
      continue;
    }
    byKey.set(key, {
      index: byKey.size + 1,
      title: citationTitle(c),
      url,
      sourceType: citationSourceType(c),
      bestScore: score,
    });
  }
  return [...byKey.values()];
}
