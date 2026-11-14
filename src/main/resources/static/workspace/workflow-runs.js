import { escapeHtml as esc, statusBadge, formatDate, renderRunDetails } from './workflow-run-details.js';

export function mount(pane, workspace) {
    for (const [id, href] of [['workflow-css', '/workspace/workflows.css'], ['workflow-runs-css', '/workspace/workflow-runs.css']]) {
        if (!document.getElementById(id)) { const link = document.createElement('link'); link.id = id; link.rel = 'stylesheet'; link.href = href; document.head.append(link); }
    }
    const $ = selector => pane.querySelector(selector), form = $('[data-filters]');
    let page = 1, pageSize = 20, total = 0, active = false, timer = null;
    let requestId = 0, detailRequestId = 0, currentRun = null, selectedId = null, loading = false;
    const filters = { workflowId: '', status: '', trigger: '', q: '' };
    async function api(path, options = {}) {
        const response = await fetch('/api/workflows' + path, { cache: 'no-store', ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.message || data.detail || `读取失败（${response.status}）`);
        return data;
    }
    function status(message, error = false) { $('[data-status]').textContent = message; $('[data-status]').classList.toggle('is-error', error); }
    function updateUrl() {
        const query = new URLSearchParams();
        for (const [key, value] of Object.entries(filters)) if (value) query.set(key, value);
        if (page > 1) query.set('page', page);
        if (pageSize !== 20) query.set('pageSize', pageSize);
        if (selectedId) query.set('run', selectedId);
        workspace.replace('/workflow-runs.html' + (query.size ? '?' + query : ''));
    }
    function controls() {
        $('[data-prev]').disabled = loading || page <= 1;
        $('[data-next]').disabled = loading || page * pageSize >= total;
        $('[data-refresh]').disabled = loading;
    }
    function duration(run) {
        if (!run.startedAt) return '—';
        const ms = Math.max(0, new Date(run.finishedAt || Date.now()) - new Date(run.startedAt));
        return ms < 1000 ? ms + ' ms' : ms < 60000 ? (ms / 1000).toFixed(1) + ' s' : (ms / 60000).toFixed(1) + ' min';
    }
    function renderRows(items) {
        $('[data-rows]').innerHTML = items.length ? items.map(run => `<tr class="${selectedId === run.id ? 'is-selected' : ''}" data-row="${esc(run.id)}"><td><button type="button" class="wr-open-title" data-open-title="${esc(run.id)}"><strong>${esc(run.workflowName)}${run.workflowDeleted ? '<small>已删除</small>' : ''}</strong><code title="${esc(run.id)}">${esc(run.id)}</code></button></td><td>${statusBadge(run.status)}</td><td><span class="wr-trigger ${run.triggerType === 'SCHEDULED' ? 'is-scheduled' : ''}">${run.triggerType === 'SCHEDULED' ? '◷ 定时' : '手动'}</span><small>v${run.version}</small></td><td>${esc(formatDate(run.startedAt || run.createdAt))}${!run.startedAt ? '<small>创建时间 · 尚未开始</small>' : ''}</td><td class="wr-duration">${duration(run)}</td><td><button type="button" data-open="${esc(run.id)}">查看详情</button></td></tr>`).join('') : '<tr><td colspan="6"><div class="wf-empty">没有符合条件的运行实例。<br><small>可以调整筛选条件，或前往编排页运行工作流。</small></div></td></tr>';
    }
    async function detail(id) {
        const ticket = ++detailRequestId;
        const value = await api('/runs/' + encodeURIComponent(id));
        if (ticket !== detailRequestId || selectedId !== id) return;
        currentRun = value;
        const open = new Set([...pane.querySelectorAll('[data-step][open]')].map(d => d.dataset.step));
        $('[data-details]').innerHTML = `<div class="wr-detail-title"><div><span>运行实例详情</span><h2>${esc(value.workflowName)}</h2></div>${value.workflowDeleted ? '<small class="wf-muted">工作流已删除，历史记录保留</small>' : `<a class="wf-text-link" href="/workflows.html?id=${encodeURIComponent(value.workflowId)}">打开编排 ↗</a>`}</div><code class="wr-full-id">${esc(value.id)}</code>${renderRunDetails(value, open)}`;
    }
    async function refresh() {
        const ticket = ++requestId;
        loading = true; controls(); clearTimeout(timer);
        try {
            const query = new URLSearchParams({ ...filters, page: String(page), pageSize: String(pageSize) });
            const result = await api('/runs?' + query);
            if (ticket !== requestId) return;
            total = result.total;
            if (page > 1 && (page - 1) * pageSize >= total) { page = Math.max(1, Math.ceil(total / pageSize)); return refresh(); }
            for (const field of ['total', 'active', 'succeeded', 'failed']) $(`[data-count="${field}"]`).textContent = result.summary[field];
            renderRows(result.items);
            $('[data-page-label]').textContent = `共 ${total} 条 · 第 ${page} / ${Math.max(1, Math.ceil(total / pageSize))} 页`;
            if (selectedId) await detail(selectedId);
            if (ticket !== requestId) return;
            status(`已更新 ${new Date().toLocaleTimeString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' })} · 统计对应当前筛选条件`);
            updateUrl();
        } catch (error) { if (ticket === requestId) status(error.message, true); }
        finally { if (ticket === requestId) { loading = false; controls(); schedule(); } }
    }
    function schedule() { clearTimeout(timer); if (active && $('[data-auto-refresh]').checked) timer = setTimeout(refresh, 4000); }
    function clearSelection() {
        selectedId = null; currentRun = null; detailRequestId++;
        $('[data-details]').innerHTML = '<div class="wf-empty">选择一个运行实例，查看详情。</div>';
    }
    async function options() {
        const workflows = await api('/run-options');
        form.elements.workflowId.innerHTML = '<option value="">全部工作流</option>' + workflows.map(w => `<option value="${esc(w.id)}">${esc(w.name)}${w.deleted ? '（已删除）' : ''}</option>`).join('');
        form.elements.workflowId.value = filters.workflowId;
    }
    form.addEventListener('submit', event => {
        event.preventDefault(); for (const key of Object.keys(filters)) filters[key] = form.elements[key].value.trim();
        page = 1; clearSelection(); refresh();
    });
    $('[data-reset]').addEventListener('click', () => { form.reset(); Object.keys(filters).forEach(k => filters[k] = ''); page = 1; clearSelection(); refresh(); });
    $('[data-refresh]').addEventListener('click', refresh);
    $('[data-auto-refresh]').addEventListener('change', schedule);
    $('[data-prev]').addEventListener('click', () => { if (page > 1) { page--; refresh(); } });
    $('[data-next]').addEventListener('click', () => { if (page * pageSize < total) { page++; refresh(); } });
    $('[data-page-size]').addEventListener('change', event => { pageSize = Number(event.target.value); page = 1; refresh(); });
    pane.addEventListener('click', async event => {
        const button = event.target.closest('button[data-open],button[data-open-title]');
        if (button) {
            selectedId = button.dataset.open || button.dataset.openTitle;
            pane.querySelectorAll('[data-row]').forEach(row => row.classList.toggle('is-selected', row.dataset.row === selectedId));
            try { await detail(selectedId); updateUrl(); $('[data-details]').scrollIntoView({ block: 'nearest', behavior: 'smooth' }); } catch (error) { status(error.message, true); }
        }
        const cancel = event.target.closest('[data-action="cancel-run"]');
        if (cancel && currentRun) {
            cancel.disabled = true;
            try { await api('/runs/' + currentRun.id + '/cancel', { method: 'POST', body: '{}' }); await refresh(); }
            catch (error) { status(error.message, true); cancel.disabled = false; }
        }
    });
    return {
        async activate(url) {
            active = true;
            const query = new URL(url).searchParams;
            for (const key of Object.keys(filters)) { filters[key] = query.get(key) || ''; form.elements[key].value = filters[key]; }
            page = Math.max(1, Number(query.get('page')) || 1); pageSize = [20, 50, 100].includes(Number(query.get('pageSize'))) ? Number(query.get('pageSize')) : 20;
            $('[data-page-size]').value = pageSize;
            const id = query.get('run'); if (selectedId !== id) clearSelection(); selectedId = id;
            try { await options(); await refresh(); }
            catch (error) { status(error.message, true); }
        },
        deactivate() { active = false; clearTimeout(timer); requestId++; detailRequestId++; loading = false; },
    };
}
