import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { noAuthGuard } from './core/guards/noauth.guard';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'login',
    pathMatch: 'full'
  },
  {
    path: 'login',
    canActivate: [noAuthGuard],
    loadComponent: () =>
      import('./features/auth/pages/login/login-page.component').then(
        m => m.LoginPageComponent
      )
  },
  {
    path: 'chat',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/chat/pages/chat-shell/chat-shell-page.component').then(
        m => m.ChatShellPageComponent
      )
  },
  {
    path: 'auth/callback',
    loadComponent: () =>
      import('./features/auth/pages/callback/oauth-callback-page.component').then(
        m => m.OauthCallbackPageComponent
      )
  },
  {
    path: '**',
    redirectTo: 'login'
  }
];