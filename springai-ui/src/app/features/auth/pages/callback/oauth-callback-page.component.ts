import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService, AuthTokenResponse } from '../../../../core/services/auth.service';

@Component({
  selector: 'app-oauth-callback-page',
  standalone: true,
  template: `
    <section>
      <p>Signing you in...</p>
    </section>
  `
})
export class OauthCallbackPageComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly authService = inject(AuthService);

  constructor() {
    const queryParams = this.route.snapshot.queryParamMap;
    const accessToken = queryParams.get('accessToken');
    const email = queryParams.get('email');

    if (accessToken) {
      const response: AuthTokenResponse = {
        accessToken,
        user: email ? { email } : undefined
      };

      this.authService.handleOAuthCallback(response);
      void this.router.navigateByUrl('/chat');
      return;
    }

    void this.router.navigateByUrl('/login');
  }
}