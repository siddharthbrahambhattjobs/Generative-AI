import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../../environments/environment';

export interface UploadedFileItem {
  fileId: string;
  fileName: string;
  processingStatus?: string;
  category?: string;
}

export interface UploadResponse {
  conversationId?: string;
  files?: UploadedFileItem[];
}

// NEW: Matches AttachmentStatus.java record
export interface AttachmentStatus {
  fileId: string;
  fileName: string;
  processingStatus: string;
  active: boolean;
  versionNumber?: number;
}

@Injectable({ providedIn: 'root' })
export class FileService {

  constructor(private readonly http: HttpClient) { }

  uploadFiles(files: File[], message: string, conversationId: string | null): Observable<UploadResponse> {
    const formData = new FormData();
    formData.append('message', message);
    if (conversationId) {
      formData.append('conversationId', conversationId);
    }
    for (const file of files) {
      formData.append('files', file, file.name);
    }
    return this.http.post<UploadResponse>(`${environment.apiBaseUrl}/api/uploads`, formData);
  }

  // NEW: Poll attachment processing status
  getFileStatus(fileId: string): Observable<AttachmentStatus> {
    return this.http.get<AttachmentStatus>(`${environment.apiBaseUrl}/api/uploads/status/${fileId}`);
  }
}