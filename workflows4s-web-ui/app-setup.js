// Import libraries from node_modules
import JSONFormatter from './node_modules/json-formatter-js/dist/json-formatter.mjs';
import hljs from './node_modules/highlight.js/lib/core.js';
import json from './node_modules/highlight.js/lib/languages/json.js';
import mermaid from './node_modules/mermaid/dist/mermaid.esm.mjs';

// Setup Highlight.js
hljs.registerLanguage('json', json);

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