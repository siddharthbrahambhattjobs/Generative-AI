export interface ChatRequest {
  message: string;
  conversationId?: string | null;
  provider?: string | null;
  model?: string | null;
  fileIds?: string[] | null;
}

export interface ChatStreamEvent {
  type: 'start' | 'token' | 'complete' | 'error';
  conversationId: string;
  content: string;
  sequence: number;
  done: boolean;
}

interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  files?: Array<{ name: string, id: string }>;  // Add this
  timestamp: Date;
}

export interface ConversationSummary {
  id: string;
  title: string;
  status: string;
  updatedAt: string;
}

export interface ConversationDetail {
  id: string;
  title: string;
  status: string;
  updatedAt: string;
  messages: ChatMessage[];
}
