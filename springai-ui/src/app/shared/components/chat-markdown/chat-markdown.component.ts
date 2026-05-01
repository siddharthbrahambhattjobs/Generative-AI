import {
    AfterViewInit,
    ChangeDetectionStrategy,
    Component,
    ElementRef,
    Input,
    OnChanges,
    Renderer2,
    ViewChild
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import { marked } from 'marked';
import DOMPurify from 'dompurify';

@Component({
    selector: 'app-chat-markdown',
    standalone: true,
    imports: [CommonModule],
    templateUrl: './chat-markdown.component.html',
    styleUrl: './chat-markdown.component.scss',
    changeDetection: ChangeDetectionStrategy.OnPush
})
export class ChatMarkdownComponent implements OnChanges, AfterViewInit {
    @Input({ required: true }) content = '';
    @ViewChild('container') container?: ElementRef<HTMLDivElement>;

    renderedHtml: SafeHtml = '';

    constructor(
        private readonly sanitizer: DomSanitizer,
        private readonly renderer: Renderer2
    ) {
        marked.setOptions({
            gfm: true,
            breaks: true
        });
    }

    ngOnChanges(): void {
        this.renderMarkdown();
        queueMicrotask(() => this.decorateCodeBlocks());
    }

    ngAfterViewInit(): void {
        this.decorateCodeBlocks();
    }

    private renderMarkdown(): void {
        const rawHtml = marked.parse(this.content ?? '') as string;
        const cleanHtml = DOMPurify.sanitize(rawHtml, {
            USE_PROFILES: { html: true }
        });

        this.renderedHtml = this.sanitizer.bypassSecurityTrustHtml(cleanHtml);
    }

    private decorateCodeBlocks(): void {
        const host = this.container?.nativeElement;
        if (!host) return;

        const codeBlocks = host.querySelectorAll('pre');

        codeBlocks.forEach((pre) => {
            if (pre.parentElement?.classList.contains('code-block-wrapper')) {
                return;
            }

            const wrapper = this.renderer.createElement('div');
            this.renderer.addClass(wrapper, 'code-block-wrapper');

            const toolbar = this.renderer.createElement('div');
            this.renderer.addClass(toolbar, 'code-toolbar');

            const label = this.renderer.createElement('span');
            this.renderer.addClass(label, 'code-language');

            const code = pre.querySelector('code');
            const className = code?.className ?? '';
            const languageMatch = className.match(/language-([\w-]+)/);
            const language = languageMatch?.[1] ?? 'code';

            label.textContent = language;

            const copyButton = this.renderer.createElement('button');
            this.renderer.addClass(copyButton, 'copy-button');
            this.renderer.setAttribute(copyButton, 'type', 'button');
            copyButton.textContent = 'Copy';

            this.renderer.listen(copyButton, 'click', () => {
                const text = code?.textContent ?? pre.textContent ?? '';

                navigator.clipboard.writeText(text)
                    .then(() => {
                        copyButton.textContent = 'Copied';
                        setTimeout(() => {
                            copyButton.textContent = 'Copy';
                        }, 1600);
                    })
                    .catch(() => {
                        copyButton.textContent = 'Failed';
                        setTimeout(() => {
                            copyButton.textContent = 'Copy';
                        }, 1600);
                    });
            });

            this.renderer.appendChild(toolbar, label);
            this.renderer.appendChild(toolbar, copyButton);

            const parent = pre.parentNode;
            if (!parent) return;

            this.renderer.insertBefore(parent, wrapper, pre);
            this.renderer.removeChild(parent, pre);
            this.renderer.appendChild(wrapper, toolbar);
            this.renderer.appendChild(wrapper, pre);
        });

        const links = host.querySelectorAll('a');
        links.forEach((link) => {
            this.renderer.setAttribute(link, 'target', '_blank');
            this.renderer.setAttribute(link, 'rel', 'noopener noreferrer');
        });
    }
}