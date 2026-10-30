// Browser sessions use an HttpOnly cookie. Intentional management writes also
// carry a custom header and are checked against the configured exact origin.
(() => {
    const originalFetch = window.fetch.bind(window);
    window.fetch = (input, options = {}) => {
        const request = input instanceof Request ? input : null;
        const url = new URL(request ? request.url : String(input), window.location.href);
        const method = String(options.method || request?.method || 'GET').toUpperCase();
        const sameOrigin = url.origin === window.location.origin;
        const api = sameOrigin && (url.pathname.startsWith('/api/') || url.pathname.startsWith('/alfred/'));
        const management = api || (sameOrigin && url.pathname.startsWith('/auth/'));
        if (management && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
            const headers = new Headers(options.headers || request?.headers);
            headers.set('X-Requested-With', 'XMLHttpRequest');
            options = { ...options, headers };
        }
        return originalFetch(input, options).then(response => {
            if (api && response.status === 401) {
                const next = window.location.pathname + window.location.search;
                window.location.assign('/admin-login.html?next=' + encodeURIComponent(next));
            }
            return response;
        });
    };
})();
