import React, {useRef, useEffect, useCallback} from 'react';
import Layout from '@theme/Layout';
import useBaseUrl from '@docusaurus/useBaseUrl';
import BrowserOnly from '@docusaurus/BrowserOnly';

function ScaladocFrame({baseUrl}: {baseUrl: string}) {
    const iframeRef = useRef<HTMLIFrameElement>(null);
    const lastKnownPath = useRef<string>('');
    const scaladocBase = `${baseUrl}scaladoc/`;

    // Read initial path from URL hash (e.g. /api/#workflows4s/wio/WIO.html)
    const hash = window.location.hash.slice(1);
    const initialSrc = `${scaladocBase}${hash || 'index.html'}`;

    const syncUrl = useCallback(() => {
        try {
            const iframe = iframeRef.current;
            if (!iframe?.contentWindow) return;

            const loc = iframe.contentWindow.location;
            const fullPath = loc.pathname + loc.hash;

            if (fullPath === lastKnownPath.current) return;
            lastKnownPath.current = fullPath;

            const idx = loc.pathname.indexOf('/scaladoc/');
            if (idx === -1) return;

            const relativePath = loc.pathname.slice(idx + '/scaladoc/'.length);
            const fragment = loc.hash; // scaladoc-internal anchor (e.g. #someMethod)
            const newHash = relativePath && relativePath !== 'index.html'
                ? `#${relativePath}${fragment}`
                : fragment
                    ? `#index.html${fragment}`
                    : '';
            window.history.replaceState(null, '', `${baseUrl}api/${newHash}`);
        } catch (_) {
            // cross-origin restriction — ignore
        }
    }, [baseUrl, scaladocBase]);

    // Poll iframe location since scaladoc uses pushState (SPA)
    useEffect(() => {
        const interval = setInterval(syncUrl, 300);
        return () => clearInterval(interval);
    }, [syncUrl]);

    return (
        <iframe
            ref={iframeRef}
            src={initialSrc}
            style={{width: '100%', height: 'calc(100vh - 60px)', border: 'none'}}
            title="Scaladoc API Reference"
        />
    );
}

export default function ApiReference(): React.JSX.Element {
    const baseUrl = useBaseUrl('/');
    return (
        <Layout title="API Reference" description="Scaladoc API Reference">
            <BrowserOnly fallback={<div style={{padding: '2rem'}}>Loading API Reference...</div>}>
                {() => <ScaladocFrame baseUrl={baseUrl} />}
            </BrowserOnly>
        </Layout>
    );
}
