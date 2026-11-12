const routes = {
    '/music.html': ['music', '歌曲管理', 'workspace-music'],
    '/': ['home', '总览', 'workspace-overview'],
    '/index.html': ['home', '总览', 'workspace-overview'],
    '/simple-kv.html': ['kv', '快捷项', 'workspace-kv'],
    '/json-record-search.html': ['records', 'JSON 记录', 'workspace-record-search'],
    '/json-record.html': ['detail', 'JSON 记录详情', 'workspace-record-detail'],
    '/monitor.html': ['monitor', '服务器监控', 'workspace-monitor'],
    '/advanced-settings.html': ['advanced', '高级设置', 'workspace-advanced'],
};
const views = new Map();
const pending = new Map();
const remembered = new Map();
const stack = document.getElementById('workspaceViews');
const status = document.getElementById('workspaceStatus');
let active = null;
let sequence = 0;
let lastViewedPage = null;

async function getView(definition, url) {
    const [key, , className] = definition;
    if (views.has(key)) return views.get(key);
    if (pending.has(key)) return pending.get(key);
    const task = (async () => {
        const response = await fetch('/workspace/views/' + key + '.html');
        if (response.status === 401) {
            location.assign('/admin-login.html?next=' + encodeURIComponent(url.pathname + url.search));
        }
        if (!response.ok) throw new Error(response.status === 401 ? '登录已失效，请刷新页面重新登录。' : '页面加载失败，请重试。');
        const markup = await response.text();
        const module = await import('./' + key + '.js');
        const pane = document.createElement('section');
        pane.className = 'ws-pane ' + className;
        pane.hidden = true;
        pane.innerHTML = markup;
        stack.append(pane);
        const view = { key, pane, controller: {}, scroll: 0 };
        try {
            view.controller = module.mount(pane, {
                url: url.href,
                navigate,
                replace(path) {
                    if (active?.key !== key) return;
                    const next = new URL(path, location.origin);
                    if (next.origin !== location.origin || !routes[next.pathname]) return;
                    history.replaceState(null, '', next.pathname + next.search);
                    remembered.set(key, next.pathname + next.search);
                },
            }) || {};
        } catch (error) {
            pane.remove();
            throw error;
        }
        views.set(key, view);
        return view;
    })();
    pending.set(key, task);
    try { return await task; } finally { pending.delete(key); }
}

function updateNavigation(definition) {
    document.body.classList.toggle('is-music-page', definition[0] === 'music');
    const key = definition[0] === 'detail' ? 'records' : definition[0];
    document.getElementById('workspaceTitle').textContent = definition[1];
    document.title = definition[1] + ' · Leon Workflow';
    for (const link of document.querySelectorAll('.lw-nav-link')) {
        link.title = link.getAttribute('aria-label') || link.textContent.trim();
        const target = routes[new URL(link.href).pathname];
        const selected = target?.[0] === key;
        link.classList.toggle('is-active', selected);
        if (selected) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    }
}

export async function navigate(path, { historyMode = 'push', recall = false } = {}) {
    let url = new URL(path, location.origin);
    let definition = routes[url.pathname];
    if (url.origin !== location.origin || !definition) return;
    if (recall && remembered.has(definition[0])) url = new URL(remembered.get(definition[0]), location.origin);
    definition = routes[url.pathname];
    const ticket = ++sequence;
    status.hidden = false;
    status.textContent = '正在加载…';
    try {
        const view = await getView(definition, url);
        if (ticket !== sequence) return;
        if (active && active !== view) {
            active.scroll = window.scrollY;
            active.pane.hidden = true;
            active.controller.deactivate?.();
        }
        const switching = active !== view;
        const page = url.pathname + url.search;
        const pageChanged = page !== lastViewedPage;
        active = view;
        if (historyMode === 'push' && location.href !== url.href) history.pushState(null, '', url.pathname + url.search);
        remembered.set(definition[0], url.pathname + url.search);
        view.pane.hidden = false;
        updateNavigation(definition);
        if (pageChanged) {
            lastViewedPage = page;
            fetch('/api/monitor/page-view', { method: 'POST', keepalive: true })
                .then(response => { if (response.ok) document.dispatchEvent(new Event('workspace-page-view')); }).catch(() => {});
        }
        view.controller.activate?.(url.href);
        if (switching) window.scrollTo({ top: view.scroll, behavior: 'instant' });
        status.hidden = true;
    } catch (error) {
        if (ticket !== sequence) return;
        status.textContent = error.message;
        const retry = document.createElement('button');
        retry.textContent = '重新加载';
        retry.addEventListener('click', () => navigate(url.href, { historyMode }));
        status.append(' ', retry);
    }
}

document.addEventListener('click', event => {
    const link = event.target.closest('a[href]');
    if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey ||
        link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
    const url = new URL(link.href);
    if (url.origin !== location.origin || !routes[url.pathname]) return;
    event.preventDefault();
    navigate(url.href, { recall: !!link.closest('.lw-nav') });
});
window.addEventListener('popstate', () => navigate(location.href, { historyMode: 'none' }));
navigate(location.href, { historyMode: 'none' });

document.getElementById('workspaceLogout').addEventListener('click', async event => {
    event.currentTarget.disabled = true;
    try {
        const response = await fetch('/auth/logout', { method: 'POST' });
        if (!response.ok) throw new Error('退出失败，请重试');
        location.assign('/admin-login.html');
    } catch (error) {
        status.hidden = false;
        status.textContent = error.message;
        document.getElementById('workspaceLogout').disabled = false;
    }
});
