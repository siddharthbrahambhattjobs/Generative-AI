export interface AuthTokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  userId: string;
  email: string;
  displayName: string;
}

export interface AuthState {
  token: string | null;
  email: string | null;
  displayName: string | null;
  userId: string | null;
}
