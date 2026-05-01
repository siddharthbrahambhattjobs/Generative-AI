export interface FileUploadResponse {
  attachmentId: string;
  fileName: string;
  contentType: string;
  processingStatus: string;
  category: string;
  active: boolean;
  createdAt: string;
}

export interface AttachmentSummary {
  attachmentId: string;
  fileName: string;
  contentType: string;
  processingStatus: string;
  category: string;
  active: boolean;
  fileSize: number;
  createdAt: string;
}
