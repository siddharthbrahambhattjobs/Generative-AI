import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Conversation } from '../../models/conversation.model';

@Injectable({ providedIn: 'root' })
export class ConversationService {
  private readonly api = '/api/conversations';

  constructor(private readonly http: HttpClient) { }

  list(): Observable<Conversation[]> {
    return this.http.get<Conversation[]>(this.api);
  }
  getMessages(conversationId: string): Observable<Conversation> {
    return this.http.get<Conversation>(`${this.api}/${conversationId}`);
  }

  create(title: string): Observable<Conversation> {
    return this.http.post<Conversation>(this.api, { title });
  }

  deleteConversation(conversationId: string): Observable<void> {
    return this.http.delete<void>(`/api/conversations/${conversationId}`);
  }
}