// Import library CSS
import './node_modules/bulma/css/bulma.min.css';
import './node_modules/json-formatter-js/dist/json-formatter.css';
import './node_modules/@fortawesome/fontawesome-free/css/all.min.css';

// Import libraries from node_modules
import JSONFormatter from 'json-formatter-js';
import hljs from 'highlight.js/lib/core';
import json from 'highlight.js/lib/languages/json';
import mermaid from 'mermaid';

// Setup Highlight.js
hljs.registerLanguage('json', json);

// Initialize Mermaid
mermaid.initialize({
    startOnLoad: false,
    theme: 'default',
    securityLevel: 'loose',
});

// Attach to window so the rest of the app can see them
window.JSONFormatter = JSONFormatter;
window.hljs = hljs;
window.mermaid = mermaid;

// Helper: Dark Mode Detection
window.isDarkMode = () =>
    window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;

// Helper: Clipboard Logic
window.copyToClipboard = (text) => {
    navigator.clipboard.writeText(text).then(() => {
        const btn = document.activeElement;
        if (btn && (btn.classList.contains('copy-btn') || btn.closest('.copy-btn'))) {
            const target = btn.classList.contains('copy-btn') ? btn : btn.closest('.copy-btn');
            const icon = target.querySelector('i');
            const originalClass = icon.className;

            icon.className = 'fas fa-check';
            setTimeout(() => {
                icon.className = originalClass;
            }, 2000);
        }
    });
};