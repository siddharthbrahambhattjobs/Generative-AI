import { CommonModule } from '@angular/common';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-login-page',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <section class="login-page">
      <div class="login-card">
        <h1>Spring AI</h1>
        <h2>Sign in to your workspace</h2>
        <p>Phase 2 auth shell with local developer token and OAuth2 starter flow.</p>

        <label for="email">Email</label>
        <input
          id="email"
          type="email"
          [(ngModel)]="email"
          [disabled]="loading()"
          placeholder="developer@example.com"
        />

        <button type="button" (click)="continueWithDevToken()" [disabled]="loading()">
          Continue with developer token
        </button>

        <div class="divider">or</div>

        <button type="button" class="google-btn" (click)="continueWithGoogle()" [disabled]="loading()">
          Continue with Google
        </button>
      </div>
    </section>
  `,
  styles: [`
    .login-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      background: linear-gradient(135deg, #f8fafc, #e2e8f0);
      padding: 24px;
    }

    .login-card {
      width: 100%;
      max-width: 420px;
      background: #ffffff;
      border-radius: 16px;
      padding: 32px;
      box-shadow: 0 10px 30px rgba(0, 0, 0, 0.08);
      border: 1px solid #e5e7eb;
    }

    h1 {
      margin: 0 0 8px;
      font-size: 28px;
      color: #111827;
    }

    h2 {
      margin: 0 0 12px;
      font-size: 20px;
      color: #1f2937;
    }

    p {
      margin: 0 0 20px;
      color: #6b7280;
      line-height: 1.5;
    }

    label {
      display: block;
      margin-bottom: 8px;
      font-weight: 600;
      color: #374151;
    }

    input {
      width: 100%;
      padding: 12px 14px;
      margin-bottom: 16px;
      border: 1px solid #d1d5db;
      border-radius: 10px;
      font-size: 14px;
      box-sizing: border-box;
      background: #f9fafb;
      color: #111827;
    }

    input:focus {
      outline: none;
      border-color: #2563eb;
      box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.15);
      background: #ffffff;
    }

    button {
      width: 100%;
      padding: 12px 16px;
      border: none;
      border-radius: 10px;
      font-size: 14px;
      font-weight: 600;
      cursor: pointer;
      background: #2563eb;
      color: #ffffff;
      margin-bottom: 12px;
    }

    button:hover {
      background: #1d4ed8;
    }

    .google-btn {
      background: #ffffff;
      color: #111827;
      border: 1px solid #d1d5db;
    }

    .google-btn:hover {
      background: #f3f4f6;
    }

    .divider {
      text-align: center;
      margin: 12px 0;
      color: #6b7280;
      font-size: 14px;
    }
  `]
})
export class LoginPageComponent implements OnInit {
  email = 'developer@example.com';
  readonly loading = signal(false);

  constructor(
    private readonly authService: AuthService,
    private readonly router: Router
  ) { }

  // login.component.ts
  ngOnInit() {
    // ✅ If already authenticated, skip login and go straight to app
    if (this.authService.isAuthenticated()) {
      this.router.navigateByUrl('/');
      return;
    }
    // ... rest of login logic
  }

  continueWithDevToken(): void {
    if (!this.email || !this.email.trim()) {
      alert('Please enter a valid email address.');
      return;
    }
    this.loading.set(true);
    this.authService.requestDevToken(this.email.trim()).subscribe({
      next: () => {
        this.loading.set(false);
        void this.router.navigateByUrl('/chat');
      },
      error: (err) => {
        console.error('Dev token login failed', err);
        this.loading.set(false);
        alert('Login failed. Check browser console and Network tab.');
      }
    });
  }
  continueWithGoogle(): void {
    this.authService.loginWithGoogle();
  }
}