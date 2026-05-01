import { Injectable, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, tap, catchError, EMPTY } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface AuthUser {
  id?: string;
  email: string;
  name?: string;
  pictureUrl?: string;
}

export interface AuthTokenResponse {
  accessToken: string;
  refreshToken?: string;
  tokenType?: string;
  expiresAt?: string;
  user?: AuthUser;
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  readonly accessToken = signal<string | null>(this.readToken());
  readonly currentUser = signal<AuthUser | null>(this.readUser());
  readonly isAuthenticated = computed(() => !!this.accessToken());

  constructor(private readonly httpClient: HttpClient) { }

  requestDevToken(email: string): Observable<AuthTokenResponse> {
    return this.httpClient
      .post<AuthTokenResponse>(`${environment.apiBaseUrl}/api/auth/dev-token`, { email })
      .pipe(tap((response) => this.persistSession(response)));
  }

  loginWithGoogle(): void {
    window.location.href = '/oauth2/authorization/google';
  }

  handleOAuthCallback(response: AuthTokenResponse): void {
    this.persistSession(response);
  }

  getAuthorizationHeader(): string | null {
    const token = this.accessToken();
    return token ? `Bearer ${token}` : null;
  }

  checkSession(): Observable<AuthUser> {
    if (!this.accessToken()) {
      return EMPTY;
    }

    return this.httpClient
      .get<AuthUser>(`${environment.apiBaseUrl}/api/auth/me`)
      .pipe(
        tap((user) => {
          this.currentUser.set(user);
          localStorage.setItem('current_user', JSON.stringify(user));
        }),
        catchError((err) => {
          console.error('Session check failed', err);
          this.logout();
          return EMPTY;
        })
      );
  }

  logout(): void {
    this.accessToken.set(null);
    this.currentUser.set(null);
    localStorage.removeItem('access_token');
    localStorage.removeItem('current_user');
  }

  private persistSession(response: AuthTokenResponse): void {
    this.accessToken.set(response.accessToken);
    this.currentUser.set(response.user ?? null);
    localStorage.setItem('access_token', response.accessToken);

    if (response.user) {
      localStorage.setItem('current_user', JSON.stringify(response.user));
    } else {
      localStorage.removeItem('current_user');
    }
  }

  private readToken(): string | null {
    try {
      return localStorage.getItem('access_token');
    } catch {
      return null;
    }
  }

  private readUser(): AuthUser | null {
    try {
      const raw = localStorage.getItem('current_user');
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }
}