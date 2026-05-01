import { ChatMessage } from './chat-message.model';

export interface Conversation {
  id: string;
  title?: string;
  messages?: ChatMessage[];
  createdAt?: string;
  updatedAt?: string;
}
