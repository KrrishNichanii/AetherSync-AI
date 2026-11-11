// API Types matching Spring Boot backend DTOs

export interface ApiResponse<T> {
  success: boolean;
  message: string | null;
  data: T;
  timestamp: string;
}

export interface UserDto {
  id: string;
  username: string;
  email: string;
  role: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  user: UserDto;
}

export type DocumentStatus = 'UPLOADING' | 'PROCESSING' | 'INDEXED' | 'FAILED';

export interface DocumentMetadataDto {
  id: string;
  filename: string;
  contentType: string;
  fileSize: number;
  totalPages: number | null;
  totalChunks: number | null;
  status: DocumentStatus;
  errorMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface DocumentResponseDto {
  id: string;
  fileName: string;
  fileSize: number;
  status: DocumentStatus;
  chunksCreated: number | null;
  message: string;
}

export type SourceType = 'confluence' | 'upload';

export interface CitationDto {
  documentId: string | null;
  fileName: string;
  chunkIndex: number;
  pageNumber: number | null;
  snippet: string;
  similarityScore: number;
  metadata: Record<string, unknown>;
  /** Present on responses from AetherSync backends; absent on older stored messages. */
  sourceType?: SourceType;
  sourceTitle?: string;
  sourceUrl?: string | null;
  pageId?: string | null;
}

/** De-duplicated source used for citation chips; `index` matches "[Source n]" in the prompt. */
export interface SourceReferenceDto {
  index: number;
  title: string;
  url: string | null;
  sourceType: SourceType;
  bestScore: number | null;
}

/* ─── Confluence sync ─── */

export type SyncRunStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL_SUCCESS' | 'FAILED';

export interface ConfluenceSyncRunDto {
  id: number;
  spaceKey: string;
  triggerType: 'MANUAL' | 'SCHEDULED' | 'STARTUP';
  status: SyncRunStatus;
  triggeredBy: string | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  pagesScanned: number;
  pagesAdded: number;
  pagesUpdated: number;
  pagesUnchanged: number;
  pagesDeleted: number;
  pagesFailed: number;
  chunksWritten: number;
  errorMessage: string | null;
}

export interface ConfluenceSyncStatusDto {
  configured: boolean;
  scheduleEnabled: boolean;
  cron: string;
  baseUrl: string;
  spaceKey: string;
  running: boolean;
  syncedPages: number;
  indexedChunks: number;
  lastSuccessfulSyncAt: string | null;
  currentRun: ConfluenceSyncRunDto | null;
  lastRun: ConfluenceSyncRunDto | null;
}

export interface ChatRequestDto {
  question: string;
  documentId?: string;
  topK?: number;
  minSimilarity?: number;
  conversationId?: string;
}

export interface ChatResponseDto {
  answer: string;
  conversationId: string;
  citations: CitationDto[];
  sources?: SourceReferenceDto[];
  responseTimeMs: number;
}

export interface SearchRequestDto {
  query: string;
  documentId?: string;
  topK?: number;
  similaritySearch?: number;
}

export interface SearchResultDto {
  query: string;
  totalMatches: number;
  matches: CitationDto[];
  sources?: SourceReferenceDto[];
}

// Conversation and Message Types
export interface Conversation {
  id: string;
  title: string;
  createdAt: string;
  updatedAt: string;
}

export interface ConversationMessage {
  id: string;
  messageType: 'USER' | 'ASSISTANT' | 'SYSTEM';
  content: string;
  createdAt: string;
  citations?: CitationDto[];
  responseTimeMs?: number;
  isStreaming?: boolean;
}

// UI Types
export interface Message {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  citations?: CitationDto[];
  responseTimeMs?: number;
  timestamp: Date;
  isStreaming?: boolean;
}

export interface UploadingFile {
  id: string;
  file: File;
  progress: number;
  status: 'pending' | 'uploading' | 'done' | 'error';
  error?: string;
  result?: DocumentResponseDto;
}

