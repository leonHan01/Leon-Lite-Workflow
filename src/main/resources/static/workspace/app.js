const routes = {
    '/workspace/tools-pro.html': ['tools-pro', 'Leon Tools Pro', 'workspace-tools-pro'],
    '/workflow-runs.html': ['workflow-runs', '运行实例', 'workspace-workflow-runs'],
    '/workflows.html': ['workflows', '工作流', 'workspace-workflows'],
    '/workspace/database.html': ['database', '数据库管理', 'workspace-database'],
    '/workspace/users.html': ['users', '用户管理', 'workspace-users'],
    '/workspace/agents.html': ['agents', 'Agent 工作台', 'workspace-agents'],
    '/music-statistics.html': ['music-statistics', '歌曲统计', 'workspace-music'],
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

const sidebarPreferenceKey = 'leon-workspace-sidebar-collapsed';
const compactSidebar = window.matchMedia('(max-width: 900px)');
let sidebarPreference = null;
try {
    const stored = localStorage.getItem(sidebarPreferenceKey);
    if (stored === 'true' || stored === 'false') sidebarPreference = stored === 'true';
} catch { /* Navigation remains usable when browser storage is unavailable. */ }

const sidebar = document.querySelector('.lw-sidebar');
const sidebarHeader = document.createElement('div');
sidebarHeader.className = 'lw-sidebar-header';
sidebar.prepend(sidebarHeader);
sidebarHeader.append(sidebar.querySelector('.lw-brand'));
const sidebarToggle = document.createElement('button');
sidebarToggle.type = 'button';
sidebarToggle.className = 'lw-sidebar-toggle';
sidebarToggle.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="4" width="18" height="16" rx="3"/><path d="M9 4v16m7-11-3 3 3 3"/></svg>';
sidebarHeader.append(sidebarToggle);

function updateSidebar() {
    const collapsed = sidebarPreference ?? compactSidebar.matches;
    document.body.classList.toggle('is-sidebar-collapsed', collapsed);
    sidebarToggle.setAttribute('aria-expanded', String(!collapsed));
    sidebarToggle.setAttribute('aria-label', collapsed ? '展开侧边栏' : '折叠侧边栏');
    sidebarToggle.title = collapsed ? '展开侧边栏' : '折叠侧边栏';
}
sidebarToggle.addEventListener('click', () => {
    sidebarPreference = !document.body.classList.contains('is-sidebar-collapsed');
    try { localStorage.setItem(sidebarPreferenceKey, String(sidebarPreference)); } catch { /* Keep the current choice in memory. */ }
    updateSidebar();
    window.dispatchEvent(new Event('resize'));
});
compactSidebar.addEventListener('change', updateSidebar);
updateSidebar();

const navigation = sidebar.querySelector('.lw-nav');
const agentLink = document.createElement('a');
agentLink.className = 'lw-nav-link'; agentLink.href = '/workspace/agents.html';
agentLink.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><rect x="3" y="6" width="18" height="15" rx="4"/><path d="M12 2v4M8 16h8"/><circle cx="8" cy="11" r="1"/><circle cx="16" cy="11" r="1"/></svg><span>Agent 工作台</span>'; agentLink.setAttribute('aria-label', 'Agent 工作台');
navigation.append(agentLink);
const databaseLink = document.createElement('a');
databaseLink.className = 'lw-nav-link'; databaseLink.href = '/workspace/database.html';
databaseLink.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg><span>数据库管理</span>';
databaseLink.setAttribute('aria-label', '数据库管理');
navigation.append(databaseLink);
const usersLink = document.createElement('a');
usersLink.className = 'lw-nav-link'; usersLink.href = '/workspace/users.html';
usersLink.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="9" cy="8" r="3"/><path d="M3 20v-2a6 6 0 0 1 12 0v2M16 5a3 3 0 0 1 0 6M17 14a5 5 0 0 1 4 5v1"/></svg><span>用户管理</span>';
usersLink.setAttribute('aria-label', '用户管理'); navigation.append(usersLink);
const navigationOrderKey = 'leon-workspace-navigation-order';
const navigationItems = () => Array.from(navigation.children);
const navigationId = item => item.dataset.navGroup ? 'group:' + item.dataset.navGroup : new URL(item.href).pathname;
function restoreNavigationOrder(order) {
    if (!Array.isArray(order)) return;
    const items = new Map(navigationItems().map(item => [navigationId(item), item]));
    for (const id of order) {
        const item = items.get(id);
        if (item) { navigation.append(item); items.delete(id); }
    }
    for (const item of items.values()) navigation.append(item);
}
function saveNavigationOrder() {
    try { localStorage.setItem(navigationOrderKey, JSON.stringify(navigationItems().map(navigationId))); } catch { /* Keep the current order in memory. */ }
}
try { restoreNavigationOrder(JSON.parse(localStorage.getItem(navigationOrderKey))); } catch { /* Use the default order. */ }
window.addEventListener('storage', event => {
    if (event.key !== navigationOrderKey || !event.newValue) return;
    try { restoreNavigationOrder(JSON.parse(event.newValue)); } catch { /* Ignore invalid preferences. */ }
});

let draggedNavigationItem = null;
function clearNavigationDrag() {
    navigationItems().forEach(item => item.classList.remove('is-nav-dragging', 'is-nav-drop-before', 'is-nav-drop-after'));
}
for (const item of navigationItems()) {
    const handle = item.matches('.lw-nav-group') ? item.querySelector('.lw-nav-toggle') : item;
    handle.draggable = true;
    handle.setAttribute('aria-keyshortcuts', 'Alt+Shift+ArrowUp Alt+Shift+ArrowDown');
    handle.setAttribute('aria-description', '拖动排序，或按 Alt 加 Shift 和上下方向键调整顺序');
    if (item.matches('.lw-nav-group')) handle.title = '工作流（拖动排序）';
    if (item.matches('.lw-nav-group')) item.querySelectorAll('a').forEach(link => { link.draggable = false; });
    handle.addEventListener('dragstart', event => {
        draggedNavigationItem = item; item.classList.add('is-nav-dragging');
        event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', navigationId(item));
    });
    handle.addEventListener('dragend', () => { draggedNavigationItem = null; clearNavigationDrag(); });
    handle.addEventListener('keydown', event => {
        if (!event.altKey || !event.shiftKey || !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
        const neighbor = event.key === 'ArrowUp' ? item.previousElementSibling : item.nextElementSibling;
        if (!neighbor) return;
        event.preventDefault();
        navigation.insertBefore(item, event.key === 'ArrowUp' ? neighbor : neighbor.nextElementSibling);
        saveNavigationOrder(); handle.focus();
    });
}
navigation.addEventListener('dragover', event => {
    if (!draggedNavigationItem) return;
    const target = Array.from(navigation.children).find(item => item.contains(event.target));
    clearNavigationDrag(); draggedNavigationItem.classList.add('is-nav-dragging');
    if (!target || target === draggedNavigationItem) return;
    event.preventDefault(); event.dataTransfer.dropEffect = 'move';
    const rect = target.getBoundingClientRect();
    const horizontal = matchMedia('(max-width: 700px)').matches;
    const before = horizontal ? event.clientX < rect.left + rect.width / 2 : event.clientY < rect.top + rect.height / 2;
    target.classList.add(before ? 'is-nav-drop-before' : 'is-nav-drop-after');
});
navigation.addEventListener('drop', event => {
    if (!draggedNavigationItem) return;
    const target = navigationItems().find(item => item.classList.contains('is-nav-drop-before') || item.classList.contains('is-nav-drop-after'));
    if (target) {
        event.preventDefault();
        navigation.insertBefore(draggedNavigationItem, target.classList.contains('is-nav-drop-before') ? target : target.nextElementSibling);
        saveNavigationOrder();
    }
    draggedNavigationItem = null; clearNavigationDrag();
});

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

function expandNavigationGroup(group, expanded) {
    group.querySelector('.lw-nav-toggle').setAttribute('aria-expanded', String(expanded));
    group.querySelector('.lw-nav-children').hidden = !expanded;
}

function updateNavigation(definition) {
    document.body.classList.toggle('is-music-page', ['music', 'music-statistics'].includes(definition[0]));
    document.body.classList.toggle('is-workflow-page', definition[0] === 'workflows');
    const key = definition[0] === 'detail' ? 'records' : definition[0] === 'music-statistics' ? 'music' : definition[0];
    document.getElementById('workspaceTitle').textContent = definition[1];
    document.title = definition[1] + ' · Leon Workflow';
    for (const link of document.querySelectorAll('.lw-nav-link')) {
        link.title = (link.getAttribute('aria-label') || link.textContent.trim()) + (link.parentElement === navigation ? '（拖动排序）' : '');
        const target = routes[new URL(link.href).pathname];
        const selected = target?.[0] === key;
        link.classList.toggle('is-active', selected);
        if (selected) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    }
    for (const group of document.querySelectorAll('.lw-nav-group')) {
        const selected = !!group.querySelector('.lw-nav-link.is-active');
        group.classList.toggle('has-active', selected);
        if (selected) expandNavigationGroup(group, true);
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

for (const toggle of document.querySelectorAll('.lw-nav-toggle')) {
    toggle.addEventListener('click', () => expandNavigationGroup(toggle.closest('.lw-nav-group'), toggle.getAttribute('aria-expanded') !== 'true'));
}

document.addEventListener('click', event => {
    const link = event.target.closest('a[href]');
    if (!link || event.defaultPrevented || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey ||
        link.hasAttribute('download') || (link.target && link.target !== '_self')) return;
    const url = new URL(link.href);
    if (url.origin !== location.origin || !routes[url.pathname]) return;
    event.preventDefault();
    navigate(url.href, { recall: !!link.closest('.lw-nav') && routes[url.pathname][0] !== 'workflows' });
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
