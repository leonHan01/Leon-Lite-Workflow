// Web-hosted adapter: transient cache only; business data remains on the server.
export const chrome = (() => {
    const listeners = () => ({ addListener() {}, removeListener() {} });
    const area = () => {
        const data = new Map();
        return {
            onChanged: listeners(),
            async get(keys) {
                if (keys == null) return Object.fromEntries(data);
                const result = {};
                for (const key of typeof keys === 'string' ? [keys] : Array.isArray(keys) ? keys : Object.keys(keys)) {
                    if (data.has(key)) result[key] = structuredClone(data.get(key));
                    else if (typeof keys === 'object' && !Array.isArray(keys)) result[key] = keys[key];
                }
                return result;
            },
            async set(values) { for (const [key, value] of Object.entries(values)) data.set(key, structuredClone(value)); },
            async remove(keys) { for (const key of Array.isArray(keys) ? keys : [keys]) data.delete(key); },
        };
    };
    const unavailable = async () => { throw new Error('此功能需要在 Chrome 扩展中使用'); };
    return {
        storage: { local: area(), session: area() },
        tabs: { query: async () => [], onActivated: listeners(), onUpdated: listeners(), duplicate: unavailable, update: unavailable, remove: unavailable },
        windows: { getCurrent: async () => ({ id: 0 }) },
        runtime: {
            getURL: path => new URL(path, location.origin).href,
            async sendMessage(message) {
                if (message?.type !== 'OPEN_OR_FOCUS') return { error: '此功能需要在 Chrome 扩展中使用' };
                try {
                    const url = new URL(message.url);
                    if (!['http:', 'https:'].includes(url.protocol)) return { error: '只能打开 HTTP 或 HTTPS 链接' };
                    window.open(url.href, '_blank', 'noopener,noreferrer');
                    return { ok: true };
                } catch { return { error: '链接地址无效' }; }
            },
        },
    };
})();
