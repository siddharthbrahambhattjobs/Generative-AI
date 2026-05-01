import {
  AfterViewInit,
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  ElementRef,
  NgZone,
  OnInit,
  ViewChild,
  signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { AuthService } from '../../../../core/services/auth.service';
import { ConversationService } from '../../../../core/services/chat/conversation.service';
import { ChatStreamService, ChatStreamEvent } from '../../../../core/services/chat/chat-stream.service';
import { FileService, UploadedFileItem } from '../../../../core/services/file/file.service';
import { Conversation } from '../../../../core/models/conversation.model';
import { ChatMessage } from '../../../../core/models/chat-message.model';
import { SpeechRecognitionService } from '../../../../core/services/speech/speech-recognition.service';

type UiChatMessage = ChatMessage & {
  id: string;
  attachedFileNames?: string[];
};

@Component({
  selector: 'app-chat-shell-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat-shell-page.component.html',
  styleUrl: './chat-shell-page.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChatShellPageComponent implements OnInit, AfterViewInit {

  @ViewChild('messagesContainer') private messagesContainer?: ElementRef<HTMLDivElement>;
  @ViewChild('promptBox') private promptBox?: ElementRef<HTMLTextAreaElement>;

  readonly conversations = signal<Conversation[]>([]);
  readonly messages = signal<UiChatMessage[]>([]);
  readonly currentConversationId = signal<string | null>(null);
  readonly pendingFiles = signal<File[]>([]);
  readonly streaming = signal(false);
  readonly uploading = signal(false);
  readonly showScrollToBottom = signal(false);

  prompt = '';

  private speechBaseText = '';
  private speechFinalCommitted = false;
  private isSubmitting = false;

  constructor(
    private readonly authService: AuthService,
    private readonly conversationService: ConversationService,
    private readonly chatStreamService: ChatStreamService,
    private readonly fileService: FileService,
    private readonly router: Router,
    private readonly ngZone: NgZone,
    private readonly cdr: ChangeDetectorRef,
    readonly speechService: SpeechRecognitionService
  ) { }

  // ─── Lifecycle ────────────────────────────────────────────────────────────

  ngOnInit(): void {
    this.loadConversations();
  }

  ngAfterViewInit(): void {
    queueMicrotask(() => this.scrollToBottom('auto'));
  }

  // ─── Conversations ────────────────────────────────────────────────────────

  loadConversations(): void {
    if (!this.authService.isAuthenticated()) return;

    this.conversationService.list().subscribe({
      next: (conversations) => {
        this.conversations.set(conversations ?? []);
        this.cdr.markForCheck();
      },
      error: (err) => {
        if (err?.status !== 401) {
          console.error('Failed to load conversations', err?.error, err);
        }
        this.conversations.set([]);
        this.cdr.markForCheck();
      }
    });
  }

  createConversation(): void {
    this.conversationService.create('New chat').subscribe({
      next: (conversation) => {
        this.conversations.update(list => [conversation, ...list]);
        this.currentConversationId.set(conversation.id);
        this.messages.set([]);
        this.cdr.markForCheck();
        queueMicrotask(() => this.scrollToBottom('smooth'));
      },
      error: (err) => console.error('Failed to create conversation', err)
    });
  }

  openConversation(conversation: Conversation): void {
    this.currentConversationId.set(conversation.id);

    this.conversationService.getMessages(conversation.id).subscribe({
      next: (fullConversation) => {
        const normalizedMessages: UiChatMessage[] = (fullConversation.messages ?? []).map((msg, index) => ({
          ...msg,
          id: (msg as UiChatMessage).id ?? `${conversation.id}-${index}-${crypto.randomUUID()}`
        }));
        this.messages.set(normalizedMessages);
        this.cdr.markForCheck();
        queueMicrotask(() => this.scrollToBottom('auto'));
      },
      error: (err) => {
        console.error('Failed to load conversation', err);
        this.messages.set([]);
        this.cdr.markForCheck();
      }
    });
  }

  deleteConversation(conv: Conversation, event: MouseEvent): void {
    event.stopPropagation();

    if (!confirm(`Delete "${conv.title || 'Untitled'}"? This cannot be undone.`)) return;

    this.conversationService.deleteConversation(conv.id).subscribe({
      next: () => {
        this.conversations.update(list => list.filter(c => c.id !== conv.id));
        if (this.currentConversationId() === conv.id) {
          this.currentConversationId.set(null);
          this.messages.set([]);
        }
        this.cdr.markForCheck();
      },
      error: (err) => console.error('Failed to delete conversation', err)
    });
  }

  // ─── Send Message ─────────────────────────────────────────────────────────

  async sendMessage(): Promise<void> {
    if (this.isSubmitting || this.streaming() || this.uploading()) return;

    const selectedFiles = this.pendingFiles();
    const message = this.prompt.trim().replace(/\s+/g, ' ')
      || (selectedFiles.length > 0 ? 'Please analyze the uploaded file.' : '');

    if (!message && selectedFiles.length === 0) return;

    this.isSubmitting = true;

    const shouldStickToBottom = this.isNearBottom();
    const attachedFileNames = selectedFiles.map(f => f.name);
    const assistantMessageId = crypto.randomUUID();

    this.prompt = '';
    this.resizeTextarea();

    this.messages.update(current => [
      ...current,
      { id: crypto.randomUUID(), role: 'USER', content: message, attachedFileNames } as UiChatMessage,
      { id: assistantMessageId, role: 'ASSISTANT', content: '' } as UiChatMessage
    ]);

    if (shouldStickToBottom) {
      queueMicrotask(() => this.scrollToBottom('smooth'));
    }

    this.streaming.set(true);
    this.cdr.markForCheck();

    try {
      let conversationId = this.currentConversationId();
      let fileIds: string[] = [];

      if (selectedFiles.length > 0) {
        this.uploading.set(true);
        try {
          const uploadResponse = await firstValueFrom(
            this.fileService.uploadFiles(selectedFiles, message, conversationId)
          );

          fileIds = uploadResponse?.files?.map((f: UploadedFileItem) => f.fileId) ?? [];

          if (!conversationId && uploadResponse?.conversationId) {
            conversationId = uploadResponse.conversationId;
            this.currentConversationId.set(conversationId);
            this.cdr.markForCheck();
          }

          this.pendingFiles.set([]);
          this.cdr.markForCheck();

          // FIXED: Poll each file until Kafka consumer finishes processing
          for (const fileId of fileIds) {
            await this.pollFileStatus(fileId);
          }

        } catch (uploadErr) {
          console.error('File upload failed', uploadErr);
          this.streaming.set(false); // ✅ FIX: Turn off the typing indicator immediately
          this.ensureAssistantFallbackById(assistantMessageId, 'File upload failed. Please try again.');
          return;
        } finally {
          this.uploading.set(false);
        }
      }

      await this.chatStreamService.stream(
        { message, conversationId, fileIds },
        {
          onEvent: (event: ChatStreamEvent) =>
            this.ngZone.run(() => this.handleStreamEvent(event, assistantMessageId)),
          onError: (error) => {
            this.ngZone.run(() => {
              console.error('Streaming failed', error);
              this.streaming.set(false);
              this.ensureAssistantFallbackById(
                assistantMessageId,
                'Sorry, something went wrong while streaming the response.'
              );
            });
          },
          onClose: () => {
            this.ngZone.run(() => {
              this.streaming.set(false);
              this.cdr.markForCheck();
            });
          }
        }
      );
    } catch (err) {
      console.error('Send message failed', err);
      this.streaming.set(false);
      this.ensureAssistantFallbackById(assistantMessageId, 'Unable to complete the request right now.');
    } finally {
      this.isSubmitting = false;
    }
  }

  // NEW: Poll Kafka-processed file status until READY or FAILED
  private async pollFileStatus(fileId: string, maxAttempts = 30): Promise<void> {
    const READY_STATUSES = ['COMPLETED', 'NO_TEXT', 'NO_CHUNKS', 'READY_FOR_RETRIEVAL'];
    const FAILED_STATUS = 'FAILED';

    // ... rest of the method remains the same ...

    for (let i = 0; i < maxAttempts; i++) {
      try {
        const status = await firstValueFrom(this.fileService.getFileStatus(fileId));

        if (READY_STATUSES.includes(status.processingStatus)) {
          return; // File is ready — proceed to chat
        }

        if (status.processingStatus === FAILED_STATUS) {
          throw new Error(`File processing failed for: ${status.fileName}`);
        }

        // Still processing (QUEUED / UPLOADED / EXTRACTED) — wait and retry
        await new Promise(resolve => setTimeout(resolve, 1500));

      } catch (err: any) {
        if (err?.message?.startsWith('File processing failed')) throw err;
        console.warn(`Status poll attempt ${i + 1} failed:`, err?.message);
        await new Promise(resolve => setTimeout(resolve, 1500));
      }
    }

    throw new Error('File processing timed out. Please try again.');
  }

  // ─── Speech ───────────────────────────────────────────────────────────────

  toggleSpeech(): void {
    if (!this.speechService.isSupported()) {
      alert('Speech recognition is not supported. Please use Chrome or Edge.');
      return;
    }

    if (this.speechService.isListening()) {
      this.speechService.stop();
      return;
    }

    this.speechBaseText = this.prompt.trim();
    this.speechFinalCommitted = false;

    this.speechService.start(
      (interim) => {
        this.prompt = [this.speechBaseText, interim].filter(Boolean).join(' ').trim();
        this.resizeTextarea();
        this.cdr.markForCheck();
      },
      (final) => {
        if (this.speechFinalCommitted) return;
        this.speechFinalCommitted = true;
        this.prompt = [this.speechBaseText, final].filter(Boolean).join(' ').trim();
        this.resizeTextarea();
        this.cdr.markForCheck();
      },
      (error) => {
        console.error('Speech recognition error:', error);
        this.speechFinalCommitted = false;
        if (error === 'not-allowed') {
          alert('Microphone access was denied. Please allow microphone permission.');
        }
        this.cdr.markForCheck();
      }
    );
  }

  // ─── Stream Events ────────────────────────────────────────────────────────

  private handleStreamEvent(event: ChatStreamEvent, assistantMessageId: string): void {
    if (event.type === 'start' && event.conversationId) {
      this.currentConversationId.set(event.conversationId);
      this.cdr.markForCheck();
      return;
    }

    if (event.type === 'token' && event.content) {
      this.messages.update(current =>
        current.map(msg => {
          if (msg.id !== assistantMessageId) return msg;

          const existing = msg.content ?? '';
          const incoming = event.content!;

          const needsSpace =
            existing.length > 0 &&
            !/\s$/.test(existing) &&
            /^[0-9]/.test(incoming) &&
            /[a-zA-Z:,.]$/.test(existing);

          return { ...msg, content: existing + (needsSpace ? ' ' : '') + incoming };
        })
      );
      this.cdr.markForCheck();
      queueMicrotask(() => this.scrollToBottom('smooth'));
      return;
    }

    if (event.type === 'error') {
      this.streaming.set(false);
      this.ensureAssistantFallbackById(
        assistantMessageId,
        event.content || 'Something went wrong while generating the response.'
      );
      this.cdr.markForCheck();
      return;
    }

    if (event.type === 'complete') {
      this.streaming.set(false);
      this.cdr.markForCheck();
      if (this.authService.isAuthenticated()) {
        this.loadConversations();
      }
    }
  }

  private ensureAssistantFallbackById(messageId: string, fallbackText: string): void {
    this.messages.update(current =>
      current.map(msg =>
        msg.id === messageId && !msg.content?.trim()
          ? { ...msg, content: fallbackText }
          : msg
      )
    );
    this.cdr.markForCheck();
  }

  // ─── UI Helpers ───────────────────────────────────────────────────────────

  trackByMessageId(index: number, msg: UiChatMessage): string {
    return msg.id;
  }

  onMessagesScroll(): void {
    this.showScrollToBottom.set(!this.isNearBottom(80));
    this.cdr.markForCheck();
  }

  scrollToBottom(behavior: ScrollBehavior = 'smooth'): void {
    const el = this.messagesContainer?.nativeElement;
    if (!el) return;
    el.scrollTo({ top: el.scrollHeight, behavior });
    this.showScrollToBottom.set(false);
    this.cdr.markForCheck();
  }

  private isNearBottom(threshold = 48): boolean {
    const el = this.messagesContainer?.nativeElement;
    if (!el) return true;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= threshold;
  }

  onPromptKeydown(event: KeyboardEvent): void {
    if (event.key !== 'Enter' || event.isComposing || event.shiftKey) return;
    event.preventDefault();
    void this.sendMessage();
  }

  onPromptInput(): void {
    this.resizeTextarea();
  }

  private resizeTextarea(): void {
    const textarea = this.promptBox?.nativeElement;
    if (!textarea) return;
    textarea.style.height = '0px';
    textarea.style.height = `${Math.min(textarea.scrollHeight, 180)}px`;
  }

  onFilesSelected(event: Event): void {
    const files = Array.from((event.target as HTMLInputElement).files ?? []);
    this.pendingFiles.update(current => [...current, ...files]);
    this.cdr.markForCheck();
  }

  removeFile(file: File): void {
    this.pendingFiles.update(files => files.filter(item => item !== file));
    this.cdr.markForCheck();
  }

  logout(): void {
    this.authService.logout();
    void this.router.navigate(['/login']);
  }

  currentUserName(): string {
    return this.authService.currentUser()?.name
      || this.authService.currentUser()?.email
      || 'User';
  }

  getFileIcon(fileName: string): string {
    const ext = fileName.split('.').pop()?.toLowerCase() ?? '';
    if (['pdf'].includes(ext)) return '📄';
    if (['doc', 'docx'].includes(ext)) return '📝';
    if (['xls', 'xlsx', 'csv'].includes(ext)) return '📊';
    if (['png', 'jpg', 'jpeg', 'gif', 'webp'].includes(ext)) return '🖼️';
    if (['txt', 'md'].includes(ext)) return '📃';
    if (['zip', 'rar', '7z'].includes(ext)) return '🗜️';
    return '📎';
  }

  canSend(): boolean {
    return !this.isSubmitting
      && !this.streaming()
      && !this.uploading()
      && (!!this.prompt.trim() || this.pendingFiles().length > 0);
  }
}