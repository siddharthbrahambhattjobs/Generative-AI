import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { catchError, throwError } from 'rxjs';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const authService = inject(AuthService);
  const router = inject(Router);
  const token = authService.accessToken();

  // Public endpoints — no token needed
  const isPublic = req.url.includes('/api/auth/dev-token')
    || req.url.includes('/oauth2/')
    || req.url.includes('/login');

  const authReq = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authReq).pipe(
    catchError((error: HttpErrorResponse) => {
      if (error.status === 401 && !isPublic) {
        authService.logout();
        void router.navigateByUrl('/login');
      }
      return throwError(() => error);
    })
  );
};