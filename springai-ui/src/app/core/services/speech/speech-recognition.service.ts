import { Injectable, NgZone, signal } from '@angular/core';

@Injectable({ providedIn: 'root' })
export class SpeechRecognitionService {

    private recognition: any = null;
    readonly isListening = signal(false);
    readonly isSupported = signal(false);

    constructor(private readonly ngZone: NgZone) {
        const SpeechRecognition =
            (window as any).SpeechRecognition ||
            (window as any).webkitSpeechRecognition;

        if (SpeechRecognition) {
            this.isSupported.set(true);
            this.recognition = new SpeechRecognition();
            this.recognition.continuous = false;      // stop after one sentence
            this.recognition.interimResults = true;   // show partial results live
            this.recognition.lang = 'en-US';
        }
    }

    start(
        onInterim: (text: string) => void,
        onFinal: (text: string) => void,
        onError?: (error: string) => void
    ): void {
        if (!this.recognition || this.isListening()) return;

        this.recognition.onstart = () => {
            this.ngZone.run(() => this.isListening.set(true));
        };

        this.recognition.onresult = (event: any) => {
            let interim = '';
            let final = '';

            for (let i = event.resultIndex; i < event.results.length; i++) {
                const transcript = event.results[i][0].transcript;
                if (event.results[i].isFinal) {
                    final += transcript;
                } else {
                    interim += transcript;
                }
            }

            this.ngZone.run(() => {
                if (interim) onInterim(interim);
                if (final) onFinal(final.trim());
            });
        };

        this.recognition.onerror = (event: any) => {
            this.ngZone.run(() => {
                this.isListening.set(false);
                onError?.(event.error);
            });
        };

        this.recognition.onend = () => {
            this.ngZone.run(() => this.isListening.set(false));
        };

        this.recognition.start();
    }

    stop(): void {
        if (this.recognition && this.isListening()) {
            this.recognition.stop();
        }
    }

    setLanguage(lang: string): void {
        if (this.recognition) {
            this.recognition.lang = lang;
        }
    }
}