import { Injectable } from '@angular/core';
import { fetchEventSource } from '@microsoft/fetch-event-source';
import { environment } from '../../../../environments/environment';
import { AuthService } from '../auth.service';

export interface ChatStreamRequest {
  message: string;
  conversationId?: string | null;
  provider?: string | null;
  model?: string | null;
  fileIds?: string[] | null;
}

export interface ChatStreamEvent {
  type: 'start' | 'token' | 'complete' | 'error';
  conversationId?: string | null;
  content?: string | null;
  sequence?: number | null;
  done?: boolean | null;
}

@Injectable({ providedIn: 'root' })
export class ChatStreamService {
  constructor(private readonly authService: AuthService) { }

  async stream(
    request: ChatStreamRequest,
    handlers: {
      onOpen?: () => void;
      onEvent?: (event: ChatStreamEvent) => void;
      onError?: (error: unknown) => void;
      onClose?: () => void;
    }
  ): Promise<void> {
    const token = this.authService.accessToken();

    if (!token) {
      throw new Error('Missing access token');
    }

    const message = (request.message ?? '').trim();
    if (!message) {
      throw new Error('Missing chat message');
    }

    const fileIds = (request.fileIds ?? [])
      .filter((id): id is string => !!id && id.trim().length > 0);

    const payload = {
      message,
      conversationId: request.conversationId ?? null,
      provider: request.provider ?? null,
      model: request.model ?? null,
      fileIds
    };

    console.log('Chat stream request payload:', payload);

    await fetchEventSource(`${environment.apiBaseUrl}/api/chat/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'text/event-stream',
        'Authorization': `Bearer ${token}`
      },
      body: JSON.stringify(payload),
      openWhenHidden: true,
      onopen: async (response) => {
        const contentType = response.headers.get('content-type') ?? '';

        if (!response.ok) {
          throw new Error(`Stream open failed with status ${response.status}`);
        }

        if (!contentType.includes('text/event-stream')) {
          throw new Error(`Expected text/event-stream but got ${contentType}`);
        }

        handlers.onOpen?.();
      },
      onmessage: (message) => {
        if (!message.data || message.data.trim() === '') {
          return;
        }

        try {
          // First, try standard parsing
          const parsed = JSON.parse(message.data) as ChatStreamEvent;
          handlers.onEvent?.(parsed);
        } catch (error) {
          console.warn('Standard parse failed, attempting strict extraction...', message.data);

          // Fallback: If standard parsing fails (often due to unescaped quotes in the content), 
          // manually extract the fields to salvage the chunk.
          try {
            const dataStr = message.data;

            // Extract type
            const typeMatch = dataStr.match(/"type"\s*:\s*"([^"]+)"/);
            const typeStr = typeMatch ? typeMatch[1] : 'token';

            // Extract content (handling internal quotes and newlines)
            const contentMatch = dataStr.match(/"content"\s*:\s*"(.*?)"\s*,\s*"sequence"/);
            let contentStr = contentMatch ? contentMatch[1] : '';

            // Unescape common JSON characters
            contentStr = contentStr.replace(/\\n/g, '\n').replace(/\\"/g, '"').replace(/\\\\/g, '\\');

            // Extract conversationId
            const convMatch = dataStr.match(/"conversationId"\s*:\s*"([^"]+)"/);
            const convId = convMatch ? convMatch[1] : null;

            // Extract done flag
            const doneMatch = dataStr.match(/"done"\s*:\s*(true|false)/);
            const isDone = doneMatch ? doneMatch[1] === 'true' : false;

            const salvagedEvent: ChatStreamEvent = {
              type: typeStr as 'start' | 'token' | 'complete' | 'error',
              conversationId: convId,
              content: contentStr,
              sequence: 0,
              done: isDone
            };

            handlers.onEvent?.(salvagedEvent);

          } catch (fallbackError) {
            console.error('Critical SSE parsing failure', fallbackError, message.data);
            handlers.onError?.(fallbackError);
          }
        }
      },
      onclose: () => {
        handlers.onClose?.();
      },
      onerror: (error) => {
        console.error('SSE transport error', error);
        handlers.onError?.(error);
        throw error;
      }
    });
  }
}