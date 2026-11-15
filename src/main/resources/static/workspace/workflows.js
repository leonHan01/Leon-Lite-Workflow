import { renderRunDetails, renderFailure } from './workflow-run-details.js';
const templates = {
    javascript: `function main(input, ctx) {\n  return {\n    items: input.items.map(item => ({\n      json: { ...item.json, name: String(item.json.name ?? '').toUpperCase() }\n    }))\n  };\n}`,
    groovy: `def main(Map input, Map ctx) {\n    return [items: input.items.collect { item ->\n        [json: item.json + [name: (item.json.name ?: '').toUpperCase()]]\n    }]\n}`,
};
const labels = { start: '开始', end: '结束', http: 'HTTP 请求', script: '脚本', if: '条件判断', feishu: '飞书消息', email: '发送邮件', 'github-trending': 'GitHub Trending 分析', 'tech-weekly': '科技爱好者周刊分析' };
const feishuIcon = '<img src="/workspace/icons/feishu.png" alt="" aria-hidden="true">';
const githubIcon = '<img src="/workspace/icons/github.png" alt="" aria-hidden="true">';
const javascriptIcon = '<img src="/workspace/icons/javascript.png" alt="" aria-hidden="true">';
const groovyIcon = '<img src="/workspace/icons/groovy.png" alt="" aria-hidden="true">';
const emailIcon = '<img src="/workspace/icons/email.png" alt="" aria-hidden="true">';
const httpIcon = '<img src="/workspace/icons/http.png" alt="" aria-hidden="true">';
const trendingSources = [['https://github.com/trending?since=weekly', '周榜'], ['https://github.com/trending?since=monthly', '月榜']];
const trendingSource = value => !value || value === 'https://github.com/trending' || value === 'https://github.com/trending?since=daily' ? trendingSources[0][0] : value;
const statuses = { QUEUED: '排队中', PENDING: '等待', RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消', SKIPPED: '已跳过' };
const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const pretty = value => JSON.stringify(value, null, 2);
const date = value => value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false }) : '—';
const badge = value => `<span class="wf-badge ${esc(value)}">${esc(statuses[value] || value)}</span>`;
const select = (name, value, choices) => `<select data-field="${name}">${choices.map(([id, label]) => `<option value="${esc(id)}" ${String(value) === id ? 'selected' : ''}>${esc(label)}</option>`).join('')}</select>`;
const field = (title, content) => `<label>${title}${content}</label>`;
const inputField = (name, value, attrs = '') => `<input data-field="${name}" value="${esc(value)}" ${attrs}>`;

export function mount(pane, workspace) {
    if (!document.getElementById('workflow-css')) {
        const link = document.createElement('link'); link.id = 'workflow-css'; link.rel = 'stylesheet'; link.href = '/workspace/workflows.css'; document.head.append(link);
    }
    if (!document.getElementById('workflow-runs-css')) { const link = document.createElement('link'); link.id = 'workflow-runs-css'; link.rel = 'stylesheet'; link.href = '/workspace/workflow-runs.css'; document.head.append(link); }
    const $ = selector => pane.querySelector(selector);
    const nodeDialog = $('[data-node-dialog]');
    const inspectorContent = $('[data-inspector]');
    const inspectorPanel = $('.wf-layout > .wf-inspector');
    let workflows = [], workflow = null, graph = null, selected = null, selectedEdge = null, connecting = null;
    let editorVisible = false, listRunTimer = null, listRunTarget = null;
    const listRuns = new Map();
    const MAX_POSITION = 50000;
    let zoom = 1, world = { width: 2400, height: 1600 }, focused = false, focusPanels = null;
    let dirty = false, busy = false, active = false, initialized = false, timer = null, currentRun = null, capabilities = {}, credentials = [], schedule = null;
    let cancelLibraryDrag = null;
    let editingCredential = null;
    const notice = (message, error = false) => {
        $('[data-notice]').textContent = message; $('[data-notice]').classList.toggle('is-error', error);
        const dialog = [...pane.querySelectorAll('dialog[open]')].at(-1);
        if (dialog) {
            let status = dialog.querySelector('[data-form-status]');
            if (!status) {
                status = document.createElement('p'); status.dataset.formStatus = ''; status.className = 'wf-notice'; status.setAttribute('role', 'status');
                const footer = dialog.querySelector('.wf-node-dialog-footer');
                if (footer) footer.prepend(status); else dialog.append(status);
            }
            status.textContent = message; status.classList.toggle('is-error', error);
        }
    };
    async function api(path = '', options = {}) {
        const response = await fetch('/api/workflows' + path, { cache: 'no-store', ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            const error = new Error(data.message || data.detail || `请求未完成（${response.status}）`);
            error.logs = typeof data.logs === 'string' ? data.logs : '';
            throw error;
        }
        return data;
    }
    const send = (path, body, method = 'POST') => api(path, { method, body: JSON.stringify(body) });
    async function perform(action) {
        if (busy) return;
        busy = true; controls();
        try { await action(); } catch (error) { notice(error.message || '操作未完成，请重试。', true); }
        finally { busy = false; controls(); }
    }
    function controls() {
        pane.querySelectorAll('[data-action="save"],[data-action="save-node"],[data-action="publish"],[data-action="create"],[data-action="reload"],[data-action="delete-workflow"],[data-action="test"]').forEach(b => b.disabled = busy || (!workflow && !['create', 'reload'].includes(b.dataset.action)));
        pane.querySelectorAll('[data-delete-workflow]').forEach(b => b.disabled = busy);
        pane.querySelectorAll('[data-list-run]').forEach(b => { const item = workflows.find(w => w.id === b.dataset.listRun), run = listRuns.get(b.dataset.listRun); b.disabled = busy || !item?.publishedVersion || ['QUEUED','RUNNING'].includes(run?.status); });
        const run = $('[data-action="run"]');
        run.disabled = busy || !workflow?.publishedVersion;
        run.textContent = workflow?.publishedVersion ? `▶ 运行 v${workflow.publishedVersion}` : '先发布后运行';
        if (workflow) $('[data-version]').textContent = `${dirty ? '有未保存修改 · ' : '草稿已保存 · '}${workflow.publishedVersion ? '已发布 v' + workflow.publishedVersion : '尚未发布'}`;
    }
    function markDirty() { dirty = true; controls(); }
    function renderWorkflowList() {
        const query = $('[data-workflow-search]').value.trim().toLocaleLowerCase();
        const visible = workflows.filter(w => (w.id === workflow?.id && dirty ? $('[data-name]').value : w.name).toLocaleLowerCase().includes(query));
        $('[data-workflow-count]').textContent = query ? `匹配 ${visible.length} / ${workflows.length} 个` : `共 ${workflows.length} 个`;
        $('[data-workflow-rows]').innerHTML = workflows.length ? `<table class="wf-list-table"><thead><tr><th>工作流名称</th><th>发布状态</th><th>运行状态</th><th>操作</th></tr></thead><tbody>${visible.map(w => {
            const unsaved = w.id === workflow?.id && dirty;
            const name = unsaved ? $('[data-name]').value : w.name;
            const href = '/workflows.html?id=' + encodeURIComponent(w.id);
            const run = listRuns.get(w.id), nodes = run?.nodes || [], completed = nodes.filter(n => ['SUCCEEDED','FAILED','SKIPPED','CANCELLED'].includes(n.status)).length;
            const runningNames = nodes.filter(n => n.status === 'RUNNING').map(n => n.name).filter(Boolean).join('、');
            const progress = run ? `${statuses[run.status] || run.status}${nodes.length ? ` · ${completed}/${nodes.length} 节点` : ''}${runningNames ? ` · 正在执行 ${runningNames}` : ''}` : '暂无运行记录';
            const running = run && ['QUEUED','RUNNING'].includes(run.status);
            return `<tr data-workflow-row="${esc(w.id)}"><td><a class="wf-list-name" href="${esc(href)}">${esc(name)}</a>${unsaved ? '<small class="wf-list-unsaved">有未保存修改</small>' : ''}</td><td><span class="wf-list-state ${w.publishedVersion ? 'is-published' : ''}">${w.publishedVersion ? '已发布 v' + esc(w.publishedVersion) : '未发布'}</span></td><td class="wf-list-progress" aria-live="polite">${esc(progress)}${run?.error ? `<small class="wf-list-error">${esc(run.error)}</small>` : ''}</td><td><div class="wf-list-actions"><button type="button" data-list-run="${esc(w.id)}" aria-label="运行 ${esc(name)}" ${!w.publishedVersion || running || busy ? 'disabled' : ''} title="${!w.publishedVersion ? '请先发布工作流' : running ? '工作流正在运行' : '运行已发布版本'}">▶ 运行</button><a href="${esc(href)}" aria-label="编辑 ${esc(name)}">编辑</a><button type="button" class="wf-danger" data-delete-workflow="${esc(w.id)}" ${busy ? 'disabled' : ''} aria-label="删除 ${esc(name)}">删除</button></div></td></tr>`;
        }).join('')}</tbody></table>${visible.length ? '' : '<div class="wf-list-empty">没有找到匹配的工作流。</div>'}` : '<div class="wf-list-empty"><strong>还没有工作流</strong><p>点击上方“新建工作流”，开始编排你的第一个流程。</p></div>';
    }
    function setEditorVisible(visible) {
        editorVisible = visible;
        $('[data-editor]').hidden = !visible;
        $('[data-workflow-list]').hidden = visible;
        pane.querySelectorAll('[data-editor-only]').forEach(el => el.hidden = !visible);
        pane.querySelectorAll('[data-list-only]').forEach(el => el.hidden = visible);
        $('[data-page-title]').textContent = visible ? '工作流编排' : '工作流列表';
        if (visible) clearTimeout(listRunTimer);
        if (!visible) { closeNodeDialog(false); cancelLibraryDrag?.(); clearTimeout(timer); focus(false); renderWorkflowList(); }
        else requestAnimationFrame(syncWorld);
    }
    async function deleteWorkflow(id) {
        const item = workflows.find(w => w.id === id);
        if (!item || !confirm(`删除“${item.name}”？历史运行记录会保留。`)) return;
        await api('/' + encodeURIComponent(id), { method: 'DELETE' });
        if (workflow?.id === id) {
            closeNodeDialog(false); workflow = null; graph = null; dirty = false; currentRun = null; clearTimeout(timer);
        }
        await loadList(); setEditorVisible(false);
        workspace.navigate('/workflows.html'); notice('工作流已删除。');
    }
    async function loadList() {
        workflows = await api(); renderWorkflowList();
        await Promise.allSettled(workflows.map(async w => {
            try {
                const rows = await api('/' + encodeURIComponent(w.id) + '/runs');
                if (rows.length) listRuns.set(w.id, await api('/runs/' + encodeURIComponent(rows[0].id)));
                else listRuns.delete(w.id);
            } catch { listRuns.set(w.id, {status:'状态获取失败'}); }
        }));
        renderWorkflowList(); scheduleListPoll();
    }
    function scheduleListPoll() {
        clearTimeout(listRunTimer);
        if (!active || editorVisible || ![...listRuns.values()].some(r => ['RUNNING','QUEUED'].includes(r.status))) return;
        listRunTimer = setTimeout(async () => {
            await Promise.allSettled([...listRuns.entries()].filter(([, r]) => ['RUNNING','QUEUED'].includes(r.status)).map(async ([id, run]) => {
                try { listRuns.set(id, await api('/runs/' + encodeURIComponent(run.id))); }
                catch (error) { notice('运行状态刷新失败：' + error.message, true); }
            }));
            if (active && !editorVisible) { renderWorkflowList(); scheduleListPoll(); }
        }, 1200);
    }

    async function load(id, push = false) {
        closeNodeDialog(false);
        clearTimeout(timer); currentRun = null; zoom = 1; world = { width: 2400, height: 1600 };
        $('.wf-canvas-scroll').scrollTo(0, 0);
        workflow = await api('/' + encodeURIComponent(id)); graph = structuredClone(workflow.definition);
        graph.nodes ??= []; graph.edges ??= []; selected = graph.nodes[0]?.id || null; selectedEdge = null; dirty = false; connecting = null;
        setEditorVisible(true); $('[data-name]').value = workflow.name;
        $('[data-run-detail]').innerHTML = '<div class="wf-empty">运行一次工作流，看看数据如何流转。</div>';
        renderWorkflowList(); renderCanvas(); inspector(); controls();
        if (push) workspace.navigate('/workflows.html?id=' + encodeURIComponent(id));
        else workspace.replace('/workflows.html?id=' + encodeURIComponent(id));
        await refreshRuns(); await refreshSchedule(); notice('草稿已载入。保存、发布后即可运行。');
    }
    function commitInspector() {
        const node = graph?.nodes.find(n => n.id === selected);
        if (!node || !$('[data-inspector] [data-field]')) return;
        const get = key => $(`[data-inspector] [data-field="${key}"]`)?.value;
        node.name = get('name') ?? node.name;
        const json = (key, fallback) => {
            const value = get(key);
            try { return value?.trim() ? JSON.parse(value) : fallback; }
            catch { throw new Error(`${key === 'params' ? '脚本参数' : 'Headers'} 需要有效的 JSON 对象`); }
        };
        if (node.type === 'script') {
            node.config.params = json('params', {}); node.config.timeoutMs = Number(get('timeoutMs'));
        } else if (node.type === 'http') {
            node.config.method = get('method'); node.config.url = get('url'); node.config.headers = json('headers', {});
            node.config.timeoutMs = Number(get('timeoutMs')); node.config.credentialId = get('credentialId');
            node.config.failOnHttpError = get('failOnHttpError') === 'true';
            node.config.body = parseValue(get('body'));
        } else if (node.type === 'if') {
            node.config.left = parseValue(get('left')); node.config.right = parseValue(get('right')); node.config.operator = get('operator');
        } else if (node.type === 'feishu') {
            node.config.credentialId = get('credentialId'); node.config.messageType = get('messageType');
            node.config.title = get('title') ?? node.config.title; node.config.text = get('text'); node.config.timeoutMs = Number(get('timeoutMs'));
        } else if (node.type === 'email') {
            for (const key of ['credentialId', 'to', 'cc', 'bcc', 'subject', 'body', 'format']) node.config[key] = get(key);
            node.config.timeoutMs = Number(get('timeoutMs'));
        } else if (node.type === 'tech-weekly') {
            node.config.timeoutMs = Number(get('timeoutMs'));
        } else if (node.type === 'github-trending') {
            node.config.sourceUrl = get('sourceUrl');
            node.config.maxProjects = Number(get('maxProjects')); node.config.timeoutMs = Number(get('timeoutMs'));
        }
    }
    function parseValue(value) {
        if (value == null || !value.trim()) return null;
        try { return JSON.parse(value); } catch { return value; }
    }
    function displayValue(value) { return value == null ? '' : typeof value === 'string' ? value : pretty(value); }
    async function save() {
        commitInspector();
        const result = await send('/' + workflow.id, { name: $('[data-name]').value, definition: graph, revision: workflow.revision }, 'PUT');
        workflow = result; graph = structuredClone(result.definition); dirty = false;
        workflows = workflows.map(w => w.id === result.id ? result : w); renderWorkflowList(); controls(); renderCanvas(); inspector();
        notice('草稿已保存。');
    }
    function selectNode(id) {
        commitInspector(); selected = id; selectedEdge = null; connecting = null;
        // Keep the clicked DOM node alive so a second click can produce dblclick.
        pane.querySelectorAll('[data-node]').forEach(el => el.classList.toggle('is-selected', el.dataset.node === id));
        pane.querySelectorAll('.is-connecting').forEach(el => el.classList.remove('is-connecting'));
        lines(); inspector(); canvasHint();
    }
    function configureNode(id, focusField = false) {
        if (selected !== id || selectedEdge) selectNode(id);
        if (!focusField) { panel('inspector', false); return; }
        $('[data-node-settings]').append(inspectorContent);
        nodeDialog.querySelector('[data-form-status]')?.remove();
        if (!nodeDialog.open) nodeDialog.showModal();
        document.body.classList.add('wf-node-dialog-open');
        requestAnimationFrame(() => {
            const input = $('[data-inspector] [data-field="name"]');
            input?.focus({ preventScroll: true }); input?.scrollIntoView({ block: 'nearest' });
        });
    }
    function closeNodeDialog(commit = true) {
        if (!nodeDialog.open) return;
        if (commit) commitInspector();
        inspectorPanel.append(inspectorContent);
        nodeDialog.close();
        document.body.classList.remove('wf-node-dialog-open');
        pane.querySelector(`[data-node="${CSS.escape(selected || '')}"]`)?.focus({ preventScroll: true });
    }
    nodeDialog.addEventListener('cancel', event => {
        event.preventDefault();
        try { closeNodeDialog(); } catch (error) { notice(error.message, true); }
    });
    function selectEdge(id) {
        commitInspector(); selected = null; selectedEdge = id; connecting = null;
        renderCanvas(); inspector(); panel('inspector', false);
        pane.querySelector(`[data-edge="${CSS.escape(id)}"]`)?.focus({ preventScroll: true });
    }
    function nodeStyle(node) {
        const requested = node.type === 'script' ? (node.config?.language === 'groovy' ? 'groovy' : 'script') : node.type;
        const kind = ['start', 'end', 'http', 'script', 'groovy', 'if', 'feishu', 'email', 'github-trending', 'tech-weekly'].includes(requested) ? requested : 'script';
        return { kind, icon: ({ start: '▶', end: '■', http: httpIcon, script: javascriptIcon, groovy: groovyIcon, if: '⑂', feishu: feishuIcon, email: emailIcon, 'github-trending': githubIcon, 'tech-weekly': githubIcon })[kind], label: node.type === 'script' ? (node.config.language === 'groovy' ? 'Groovy' : 'JavaScript') : labels[node.type] };
    }
    function renderCanvas() {
        if (!graph) return;
        const runNodes = new Map((currentRun?.nodes || []).map(n => [n.nodeId, n.status]));
        $('[data-nodes]').innerHTML = graph.nodes.map(node => {
            const style = nodeStyle(node), status = runNodes.get(node.id), position = node.position || { x: 60, y: 100 };
            const x = Math.max(15, Math.min(MAX_POSITION, Number(position.x) || 60)), y = Math.max(20, Math.min(MAX_POSITION, Number(position.y) || 100));
            return `<div class="wf-node ${node.type === 'if' ? 'is-if' : ''} ${selected === node.id ? 'is-selected' : ''} ${status ? 'status-' + esc(status) : ''}" data-node="${esc(node.id)}" style="left:${x}px;top:${y}px" tabindex="0" role="button" aria-label="配置节点 ${esc(node.name)}">
                ${node.type !== 'start' ? `<button type="button" class="wf-port wf-port-in" data-target="${esc(node.id)}" aria-label="连接到 ${esc(node.name)}"></button>` : ''}
                <div class="wf-node-head" data-drag="${esc(node.id)}"><b class="wf-icon wf-${style.kind}">${style.icon}</b><div><span class="wf-node-name">${esc(node.name)}</span><span class="wf-node-type">${esc(style.label)}</span></div></div>
                <div class="wf-node-foot"><span>${esc(statuses[status] || '')}</span></div>
                ${(node.type === 'end' ? [] : node.type === 'if' ? ['true', 'false'] : ['']).map(branch => `<button type="button" class="wf-port wf-port-out ${connecting?.source === node.id && connecting?.branch === branch ? 'is-connecting' : ''}" data-source="${esc(node.id)}" data-branch="${branch}" aria-label="${esc(node.name)} ${branch || '默认'}出口"></button>${branch ? `<span class="wf-branch-label ${branch}">${branch}</span>` : ''}`).join('')}
            </div>`;
        }).join('');
        syncWorld(); lines();
        canvasHint();
    }
    function canvasHint() {
        $('[data-canvas-hint]').textContent = connecting ? (connecting.point ? `按住拖动连线，松开到节点${connecting.endpoint === 'source' ? '出口' : '入口'}完成；Esc 取消` : connecting.endpoint === 'source' ? '重新连接起点：点击节点右侧出口，Esc 取消' : '连接到节点左侧入口，Esc 取消') : '双击节点配置 · 拖动出口连线 · Ctrl/⌘+滚轮缩放';
    }
    function syncWorld() {
        const scroll = $('.wf-canvas-scroll'), canvas = $('.wf-canvas'), stage = $('.wf-canvas-stage');
        for (const node of graph?.nodes || []) {
            const element = pane.querySelector(`[data-node="${CSS.escape(node.id)}"]`);
            world.width = Math.max(world.width, (Number(node.position?.x) || 60) + (element?.offsetWidth || 140) + 500);
            world.height = Math.max(world.height, (Number(node.position?.y) || 100) + 500);
        }
        const width = Math.max(world.width, scroll.clientWidth / zoom), height = Math.max(world.height, scroll.clientHeight / zoom);
        canvas.style.width = width + 'px'; canvas.style.height = height + 'px'; canvas.style.transform = `scale(${zoom})`;
        stage.style.width = width * zoom + 'px'; stage.style.height = height * zoom + 'px';
        $('[data-zoom]').textContent = Math.round(zoom * 100) + '%';
    }
    function setZoom(value, point) {
        const scroll = $('.wf-canvas-scroll'), old = zoom;
        const anchor = point || { x: scroll.clientWidth / 2, y: scroll.clientHeight / 2 };
        const x = (scroll.scrollLeft + anchor.x) / old, y = (scroll.scrollTop + anchor.y) / old;
        zoom = Math.max(.05, Math.min(2, Math.round(value * 100) / 100)); syncWorld(); if (graph) lines();
        scroll.scrollLeft = x * zoom - anchor.x; scroll.scrollTop = y * zoom - anchor.y;
    }
    function fitView() {
        if (!graph?.nodes.length) return;
        const scroll = $('.wf-canvas-scroll');
        const positions = graph.nodes.map(n => {
            const element = pane.querySelector(`[data-node="${CSS.escape(n.id)}"]`);
            return { x: Number(n.position?.x) || 60, y: Number(n.position?.y) || 100, width: element?.offsetWidth || 140, height: element?.offsetHeight || 100 };
        });
        const left = Math.min(...positions.map(p => p.x)), top = Math.min(...positions.map(p => p.y));
        const width = Math.max(...positions.map(p => p.x + p.width)) - left, height = Math.max(...positions.map(p => p.y + p.height)) - top;
        setZoom(Math.min((scroll.clientWidth - 80) / Math.max(width, 140), (scroll.clientHeight - 80) / Math.max(height, 100), 1.25));
        scroll.scrollLeft = Math.max(0, (left + width / 2) * zoom - scroll.clientWidth / 2);
        scroll.scrollTop = Math.max(0, (top + height / 2) * zoom - scroll.clientHeight / 2);
    }
    function panel(kind, hidden) {
        const layout = $('.wf-layout'); layout.classList.toggle('is-' + kind + '-hidden', hidden);
        const button = $(`[data-view="${kind}"]`);
        button.setAttribute('aria-pressed', String(!hidden)); button.setAttribute('aria-label', (hidden ? '展开' : '收起') + (kind === 'library' ? '节点库' : '节点配置'));
        requestAnimationFrame(syncWorld);
    }
    function focus(value) {
        if (focused === value) return;
        focused = value; $('[data-editor]').classList.toggle('is-focused', value); document.body.classList.toggle('wf-focus-open', value);
        if (value) {
            focusPanels = { library: $('.wf-layout').classList.contains('is-library-hidden'), inspector: $('.wf-layout').classList.contains('is-inspector-hidden') };
            panel('library', true); panel('inspector', true);
        } else if (focusPanels) { panel('library', focusPanels.library); panel('inspector', focusPanels.inspector); focusPanels = null; }
        requestAnimationFrame(syncWorld);
    }
    const edgeName = edge => {
        const name = id => graph.nodes.find(n => n.id === id)?.name || id;
        return `${name(edge.source)} → ${name(edge.target)}${edge.branch ? ' (' + edge.branch + ')' : ''}`;
    };
    function edgePoints(edge) {
        const source = pane.querySelector(`[data-node="${CSS.escape(edge.source)}"]`), target = pane.querySelector(`[data-node="${CSS.escape(edge.target)}"]`);
        if (!source || !target) return null;
        return { x: source.offsetLeft + source.offsetWidth, y: source.offsetTop + (edge.branch === 'true' ? 64 : edge.branch === 'false' ? 94 : 50), tx: target.offsetLeft, ty: target.offsetTop + 50 };
    }
    function curve({ x, y, tx, ty }) {
        const bend = Math.max(60, Math.abs(tx - x) * .5);
        return `M${x},${y} C${x + bend},${y} ${tx - bend},${ty} ${tx},${ty}`;
    }
    function lines() {
        let handles = '';
        const content = graph.edges.map(edge => {
            const points = edgePoints(edge); if (!points) return '';
            const chosen = edge.id === selectedEdge, path = curve(points), label = esc(edgeName(edge));
            if (chosen) handles = ['source', 'target'].map(endpoint => {
                const offset = Math.min(22 / zoom, Math.max(8, Math.abs(points.tx - points.x) / 4));
                const x = endpoint === 'source' ? points.x + offset : points.tx - offset, y = endpoint === 'source' ? points.y : points.ty;
                return `<circle class="wf-edge-handle" cx="${x}" cy="${y}" r="${7 / zoom}" data-reconnect="${endpoint}" data-edge-id="${esc(edge.id)}" tabindex="0" role="button" aria-label="拖动连线${endpoint === 'source' ? '起点' : '终点'}"/>`;
            }).join('');
            return `<g class="wf-edge-group ${chosen ? 'is-selected' : ''} ${connecting?.edgeId === edge.id ? 'is-reconnecting' : ''}" data-edge="${esc(edge.id)}" tabindex="0" role="button" aria-label="选择连线 ${label}"><path class="wf-edge" d="${path}" marker-end="url(#${chosen ? 'wf-arrow-selected' : 'wf-arrow'})"/><path class="wf-edge-hit" d="${path}"/>${edge.branch ? `<text class="wf-edge-label" x="${points.x + 20}" y="${points.y - 8}">${esc(edge.branch)}</text>` : ''}</g>`;
        }).join('');
        let preview = '';
        if (connecting?.point) {
            const edge = connecting.edgeId ? graph.edges.find(e => e.id === connecting.edgeId) : { source: connecting.source, branch: connecting.branch, target: connecting.source };
            const points = edge && edgePoints(edge);
            if (points) {
                const { x, y } = connecting.point;
                if (connecting.endpoint === 'source') { points.x = x; points.y = y; }
                else { points.tx = x; points.ty = y; }
                preview = `<path class="wf-edge wf-edge-preview" d="${curve(points)}"/>`;
            }
        }
        $('[data-lines]').innerHTML = content + handles + preview;
    }
    function connectionFields(node) {
        if (node.type === 'end') return '';
        return (node.type === 'if' ? ['true', 'false'] : ['']).map(branch => {
            const edges = graph.edges.filter(e => e.source === node.id && (e.branch || '') === branch);
            const rows = edges.map(edge => `<div class="wf-connection-row"><button type="button" data-select-edge="${esc(edge.id)}" title="编辑连线 ${esc(edgeName(edge))}">${esc(graph.nodes.find(n => n.id === edge.target)?.name || edge.target)} ↗</button><button type="button" data-delete-edge="${esc(edge.id)}" aria-label="删除连线 ${esc(edgeName(edge))}">×</button></div>`).join('');
            return `<div class="wf-connection-list">${field(branch ? `${branch} 分支 · 添加连接` : '添加后续节点', `<select data-connect="${branch}"><option value="">选择节点添加连接…</option>${graph.nodes.filter(n => n.id !== node.id && n.type !== 'start' && !edges.some(e => e.target === n.id)).map(n => `<option value="${esc(n.id)}">${esc(n.name)}</option>`).join('')}</select>`)}${rows || '<p class="wf-help">尚未连接，发布前必须连接到结束节点。</p>'}</div>`;
        }).join('');
    }
    function edgeInspector(edge) {
        const source = graph.nodes.find(n => n.id === edge.source);
        const options = (nodes, value) => nodes.map(n => `<option value="${esc(n.id)}" ${n.id === value ? 'selected' : ''}>${esc(n.name)}</option>`).join('');
        $('[data-inspector]').innerHTML = `<h2>连线配置</h2><p class="wf-help">可删除或修改两端节点，也可拖动蓝色端点重新连接；Esc 取消时保留原连线。</p>${field('起点节点', `<select data-edge-field="source">${options(graph.nodes.filter(n => n.type !== 'end'), edge.source)}</select>`)}<label data-edge-branch ${source?.type === 'if' ? '' : 'hidden'}>起点出口<select data-edge-field="branch">${(source?.type === 'if' ? ['true', 'false'] : ['']).map(b => `<option value="${b}" ${b === (edge.branch || '') ? 'selected' : ''}>${b || '默认'}</option>`).join('')}</select></label>${field('终点节点', `<select data-edge-field="target">${options(graph.nodes.filter(n => n.type !== 'start'), edge.target)}</select>`)}<div class="wf-edge-actions"><button type="button" data-action="apply-edge">保存连线修改</button><button type="button" data-action="reconnect-source">重新连接起点</button><button type="button" data-action="reconnect-target">重新连接终点</button><button type="button" class="wf-danger" data-action="delete-edge">删除连线</button></div><p class="wf-help">也可按 Delete / Backspace 删除选中连线。同一出口可以连接多个后续节点。</p>`;
    }
    function inspector() {
        const edge = graph?.edges.find(e => e.id === selectedEdge);
        if (edge) { edgeInspector(edge); return; }
        const node = graph?.nodes.find(n => n.id === selected);
        if (!node) { $('[data-inspector]').innerHTML = '<div class="wf-empty">选择节点或连线进行配置。</div>'; return; }
        const c = node.config ||= {}, style = nodeStyle(node);
        let content = field('节点名称', inputField('name', node.name, 'maxlength="80"'));
        if (node.type === 'start') content += '<p class="wf-help">流程唯一的开始节点，不能删除。手动运行或定时触发时，将运行输入传入后续节点。</p>';
        if (node.type === 'end') content += '<p class="wf-help">流程唯一的结束节点，不能删除。所有执行分支必须连接到这里；多条执行路径的 items 按到达顺序汇总为最终输出。</p>';
        if (node.type === 'script') {
            content += field('脚本语言', select('language', c.language, [['javascript', 'JavaScript'], ['groovy', 'Groovy']]));
            content += '<p class="wf-help">入口 main(input, ctx)，返回 { items: [{ json: … }] }。网络请求请使用 HTTP 节点。</p>';
            content += `<div class="wf-code-wrap"><pre aria-hidden="true" data-highlight></pre><textarea aria-label="脚本代码" data-field="source" spellcheck="false" wrap="off">${esc(c.source)}</textarea></div>`;
            content += field('脚本参数 ctx.params（JSON）', `<textarea data-field="params" spellcheck="false">${esc(pretty(c.params || {}))}</textarea>`);
        }
        if (node.type === 'http') {
            content += field('请求方法', select('method', c.method || 'GET', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'].map(v => [v, v])));
            content += field('请求地址', inputField('url', c.url, 'placeholder="https://example.com/api"'));
            content += field('凭据', select('credentialId', c.credentialId || '', [['', '不使用凭据'], ...credentials.filter(v => ['bearer', 'basic', 'header'].includes(v.kind)).map(v => [v.id, v.name])]));
            content += `<button type="button" data-action="edit-node-credential" ${c.credentialId ? '' : 'disabled'}>编辑所选凭据</button>`;
            content += field('Headers（JSON）', `<textarea data-field="headers" spellcheck="false">${esc(pretty(c.headers || {}))}</textarea>`);
            content += field('Body（JSON 或文本）', `<textarea data-field="body" spellcheck="false">${esc(displayValue(c.body))}</textarea>`);
            content += '<p class="wf-help">字段可引用 {{ input.items[0].json.name }}。凭据通过上方“管理凭据”保存。请求不会自动重试或跟随重定向。</p>';
            content += field('收到 4xx / 5xx 时', select('failOnHttpError', c.failOnHttpError !== false, [['true', '将节点标记为失败'], ['false', '继续并返回响应']]));
        }
        if (node.type === 'if') {
            content += field('左侧值 / 字段引用', inputField('left', displayValue(c.left)));
            content += field('条件', select('operator', c.operator || 'eq', [['eq', '等于'], ['neq', '不等于'], ['gt', '大于'], ['gte', '大于或等于'], ['lt', '小于'], ['lte', '小于或等于'], ['contains', '包含'], ['exists', '存在'], ['truthy', '为真']]));
            content += field('右侧值（支持 JSON）', inputField('right', displayValue(c.right)));
            content += '<p class="wf-help">例如：左侧 {{ input.items[0].json.amount }}，条件“大于或等于”，右侧 100。</p>';
        }
        if (node.type === 'github-trending') {
            content += field('榜单周期', select('sourceUrl', trendingSource(c.sourceUrl), trendingSources));
            content += field('榜单地址', `<input value="${esc(trendingSource(c.sourceUrl))}" readonly data-trending-url aria-label="Trending 榜单地址">`);
            content += field('最多分析项目数（1–25）', inputField('maxProjects', c.maxProjects ?? 25, 'type="number" min="1" max="25" step="1"'));
            content += field('总超时（毫秒，1000–240000）', inputField('timeoutMs', c.timeoutMs || 180000, 'type="number" min="1000" max="240000" step="1000"'));
            content += '<p class="wf-help">服务器抓取榜单，再由 DeepSeek 逐个分析项目用途和使用场景。复用高级设置中的 DeepSeek API Key；此节点只生成内容。</p>';
            if (capabilities.githubTrendingReady === false) content += '<p class="wf-run-error">请先到高级设置填写 DeepSeek API Key。</p>';
            content += '<div class="wf-trending-output"><h3>后续节点可引用</h3>' + [
                ['飞书正文（Markdown 卡片）', 'feishuMarkdown'], ['飞书正文（纯文本）', 'feishuText'], ['邮件主题', 'emailSubject'], ['邮件正文（纯文本）', 'emailBody'], ['邮件正文（HTML）', 'emailHtml'],
            ].map(([title, key]) => field(title, `<input readonly value="{{ input.items[0].json.${key} }}" aria-label="${title}引用">`)).join('') + '<p class="wf-help">将出口连接到飞书或邮件节点，在后续节点点击“使用 Trending 分析结果”即可填入引用。</p></div>';
        }
        if (node.type === 'tech-weekly') {
            content += field('总超时（毫秒，1000–240000）', inputField('timeoutMs', c.timeoutMs || 180000, 'type="number" min="1000" max="240000" step="1000"'));
            content += '<p class="wf-help">每次运行读取仓库索引和最新一期正文，由 DeepSeek 分析并生成飞书卡片和邮件内容。使用高级设置中的 DeepSeek API Key。</p>';
            content += '<div class="wf-trending-output"><h3>后续节点可引用</h3>' + [['飞书卡片正文','feishuMarkdown'],['飞书卡片标题','title'],['邮件主题','emailSubject'],['邮件正文','emailBody'],['邮件正文（HTML）','emailHtml']].map(([title,key]) => field(title, `<input readonly value="{{ input.items[0].json.${key} }}" aria-label="${title}引用">`)).join('') + '</div>';
        }
        if (node.type === 'feishu') {
            content += '<button type="button" data-action="use-trending-feishu">使用周刊分析结果</button>';
            content += '<button type="button" data-action="use-trending-feishu">使用 Trending 分析结果</button>';
            content += field('飞书机器人', select('credentialId', c.credentialId || '', [['', '请选择机器人凭据'], ...credentials.filter(v => v.kind === 'feishu').map(v => [v.id, v.name])]));
            content += '<button type="button" data-action="feishu-credentials">添加飞书机器人</button>';
            content += `<button type="button" data-action="edit-node-credential" ${c.credentialId ? '' : 'disabled'}>编辑所选凭据</button>`;
            content += field('消息类型', select('messageType', c.messageType || 'text', [['text', '文本'], ['card', 'Markdown 卡片']]));
            content += `<label data-feishu-title ${c.messageType === 'card' ? '' : 'hidden'}>卡片标题${inputField('title', c.title || '工作流通知', 'maxlength="200"')}</label>`;
            content += field('消息内容', `<textarea aria-label="飞书消息内容" data-field="text" rows="5" maxlength="20480" spellcheck="false">${esc(c.text)}</textarea>`);
            content += '<p class="wf-help">可引用 {{ input.items[0].json.name }} 等上游字段。每次执行发送一条消息，成功后继续传递原输入。调试和运行会真实发送消息。</p>';
        }
        if (node.type === 'email') {
            content += '<button type="button" data-action="use-trending-email">使用周刊分析结果</button>';
            content += '<button type="button" data-action="use-trending-email">使用 Trending 分析结果</button>';
            content += field('SMTP 邮箱', select('credentialId', c.credentialId || '', [['', '请选择邮箱凭据'], ...credentials.filter(v => v.kind === 'smtp').map(v => [v.id, v.name])]));
            content += '<button type="button" data-action="smtp-credentials">添加 SMTP 邮箱</button>';
            content += `<button type="button" data-action="edit-node-credential" ${c.credentialId ? '' : 'disabled'}>编辑所选凭据</button>`;
            content += field('收件人', inputField('to', c.to, 'placeholder="name@example.com，多个邮箱用逗号分隔" maxlength="8192"'));
            content += field('抄送（可选）', inputField('cc', c.cc, 'maxlength="8192"'));
            content += field('密送（可选）', inputField('bcc', c.bcc, 'maxlength="8192"'));
            content += field('邮件主题', inputField('subject', c.subject, 'maxlength="1000"'));
            content += field('正文格式', select('format', c.format || 'text', [['text', '纯文本'], ['html', 'HTML']]));
            content += field('邮件正文', `<textarea aria-label="邮件正文" data-field="body" rows="7" spellcheck="false">${esc(c.body)}</textarea>`);
            content += '<p class="wf-help">收件人、主题和正文可引用 {{ input.items[0].json.name }} 等上游字段。每次执行提交一封邮件，成功后传递原输入。调试会真实发送；失败不会自动重试。</p>';
        }
        if (['http', 'script', 'feishu', 'email'].includes(node.type)) content += field('超时（毫秒，100–30000）', inputField('timeoutMs', c.timeoutMs || 10000, 'type="number" min="100" max="30000" step="100"'));
        if (node.type !== 'end') content += `<h3 class="wf-section-label">连接出口</h3>${connectionFields(node)}`;
        content += `<div class="wf-node-actions"><button type="button" data-action="test">${node.type === 'feishu' ? '发送测试消息' : node.type === 'email' ? '发送测试邮件' : node.type === 'tech-weekly' ? '生成周刊分析' : node.type === 'github-trending' ? '生成 Trending 分析' : '调试此节点'}</button>${!['start', 'end'].includes(node.type) ? '<button type="button" class="wf-danger" data-action="delete-node">删除节点</button>' : ''}</div>`;
        $('[data-inspector]').innerHTML = `<h2>${esc(style.label)}</h2><div data-node-error></div>${content}<div data-node-debug></div>`;
        nodeError(); highlight(); controls();
    }
    function nodeError() {
        const target = $('[data-node-error]'); if (!target) return;
        const step = currentRun?.nodes?.find(n => n.nodeId === selected && n.status === 'FAILED');
        target.innerHTML = step ? `<p class="wf-help">最近查看的运行 · 发布版本 v${esc(currentRun.version)}</p>${renderFailure(step.error, step.logs)}` : '';
    }
    function highlight() {
        const textarea = $('[data-field="source"]'), pre = $('[data-highlight]'); if (!textarea || !pre) return;
        const code = textarea.value, pattern = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`|\b(?:function|return|const|let|var|if|else|for|while|def|new|Map|List|import|true|false|null|throw|try|catch|in|class)\b|\b\d+(?:\.\d+)?\b/g;
        let html = '', end = 0;
        for (const match of code.matchAll(pattern)) {
            const token = match[0], kind = token.startsWith('//') || token.startsWith('/*') ? 'comment' : /^["'`]/.test(token) ? 'string' : /^\d/.test(token) ? 'number' : 'keyword';
            html += esc(code.slice(end, match.index)) + `<span class="tok-${kind}">${esc(token)}</span>`; end = match.index + token.length;
        }
        pre.innerHTML = html + esc(code.slice(end)) + '\n'; pre.scrollTop = textarea.scrollTop; pre.scrollLeft = textarea.scrollLeft;
    }
    function connect(source, target, branch = '', edgeId = null) {
        const sourceNode = graph.nodes.find(n => n.id === source), targetNode = graph.nodes.find(n => n.id === target);
        if (!sourceNode || !targetNode) throw new Error('请选择有效的起点和终点节点');
        if (sourceNode.type === 'end') throw new Error('结束节点不能有出口');
        if (source === target || targetNode.type === 'start') throw new Error('不能连接到自身或开始节点');
        if (sourceNode.type === 'if' ? !['true', 'false'].includes(branch) : branch !== '') throw new Error('请选择正确的起点出口');
        if (edgeId && !graph.edges.some(e => e.id === edgeId)) throw new Error('连线已不存在，请重新选择');
        const edges = graph.edges.filter(e => e.id !== edgeId);
        if (edges.some(e => e.source === source && e.target === target && (e.branch || '') === branch)) throw new Error('这两个节点已经通过该出口连接');
        if (targetNode.type !== 'end' && edges.some(e => e.target === target)) throw new Error('仅结束节点支持多路汇入，请为分支添加独立处理节点');
        if (edges.length >= 128) throw new Error('一个流程最多包含 128 条连线');
        const seen = new Set(), queue = [target];
        while (queue.length) {
            const id = queue.pop(); if (id === source) throw new Error('不能创建循环连线'); if (seen.has(id)) continue; seen.add(id);
            edges.filter(e => e.source === id).forEach(e => queue.push(e.target));
        }
        const edge = { id: edgeId || 'e_' + crypto.randomUUID(), source, target, branch };
        // Reconnection preserves the execution order and every other outgoing connection.
        graph.edges = edgeId ? graph.edges.map(e => e.id === edgeId ? edge : e) : [...edges, edge];
        selected = null; selectedEdge = edge.id; connecting = null; markDirty(); renderCanvas(); inspector(); panel('inspector', false);
        pane.querySelector(`[data-edge="${CSS.escape(edge.id)}"]`)?.focus({ preventScroll: true });
        notice(edgeId ? '连线已重新连接。' : '连线已添加，同一出口可继续连接其他节点。');
    }
    function deleteEdge(id = selectedEdge) {
        if (!graph?.edges.some(e => e.id === id)) return;
        commitInspector();
        graph.edges = graph.edges.filter(e => e.id !== id);
        if (selectedEdge === id) selectedEdge = null;
        connecting = null; markDirty(); renderCanvas(); inspector();
        $('.wf-canvas-scroll').focus({ preventScroll: true });
        notice('连线已删除。发布前请确保所有执行路径连接到结束节点。');
    }
    function beginReconnect(id, endpoint) {
        commitInspector(); const edge = graph.edges.find(e => e.id === id); if (!edge) return;
        closeNodeDialog();
        selected = null; selectedEdge = id; connecting = { edgeId: id, endpoint };
        renderCanvas(); inspector();
        $('.wf-canvas-scroll').focus({ preventScroll: true });
        notice(endpoint === 'source' ? '点击新起点的出口圆点；Esc 取消。' : '点击新终点的入口圆点；Esc 取消。');
    }
    function connectPort(kind, nodeId, branch = '') {
        if (connecting?.edgeId) {
            const edge = graph.edges.find(e => e.id === connecting.edgeId); if (!edge) return;
            if (connecting.endpoint === 'source' && kind === 'source') connect(nodeId, edge.target, branch, edge.id);
            else if (connecting.endpoint === 'target' && kind === 'target') connect(edge.source, nodeId, edge.branch || '', edge.id);
            else throw new Error('请点击' + (connecting.endpoint === 'source' ? '起点出口' : '终点入口'));
        } else if (kind === 'source') {
            commitInspector(); selectedEdge = null; selected = nodeId; connecting = { source: nodeId, branch };
            renderCanvas(); inspector();
            $('.wf-canvas-scroll').focus({ preventScroll: true });
        } else if (connecting) connect(connecting.source, nodeId, connecting.branch);
        else selectNode(nodeId);
    }
    function add(kind, position) {
        if (!workflow) return notice('请先新建工作流。', true);
        commitInspector(); const maxNodes = capabilities.maxNodes || 65;
        if (graph.nodes.length >= maxNodes) throw new Error(`一个流程最多包含 ${maxNodes} 个节点`);
        const parent = position ? null : graph.nodes.find(n => n.id === selected), id = 'n_' + crypto.randomUUID().replaceAll('-', '').slice(0, 10);
        if (parent?.type === 'end') throw new Error('请选择开始或处理节点，再添加后续节点');
        const end = graph.nodes.find(n => n.type === 'end');
        const type = ['javascript', 'groovy'].includes(kind) ? 'script' : kind;
        const config = type === 'script' ? { language: kind, source: templates[kind], sources: { [kind]: templates[kind] }, params: {}, timeoutMs: 10000 }
            : type === 'http' ? { method: 'GET', url: '', headers: {}, body: null, timeoutMs: 10000, failOnHttpError: true }
            : type === 'feishu' ? { credentialId: credentials.find(v => v.kind === 'feishu')?.id || '', messageType: 'text', title: '工作流通知', text: '工作流通知：{{ input.items[0].json.name }}', timeoutMs: 10000 }
            : type === 'email' ? { credentialId: credentials.find(v => v.kind === 'smtp')?.id || '', to: '', cc: '', bcc: '', subject: '工作流通知', body: '你好，{{ input.items[0].json.name }}', format: 'text', timeoutMs: 10000 }
            : type === 'tech-weekly' ? { timeoutMs: 180000 }
            : type === 'github-trending' ? { sourceUrl: trendingSources[0][0], maxProjects: 25, timeoutMs: 180000 }
            : { left: '{{ input.items[0].json.amount }}', operator: 'gte', right: 100 };
        const node = { id, type, name: kind === 'groovy' ? 'Groovy 脚本' : kind === 'javascript' ? 'JavaScript 脚本' : labels[type], config,
            position: position || { x: Math.min(MAX_POSITION - 300, (parent?.position?.x || 60) + 280), y: parent?.position?.y || 140 } };
        graph.nodes.push(node);
        if (parent) {
            const branch = (parent.type === 'if' ? ['true', 'false'] : ['']).find(b => !graph.edges.some(e => e.source === parent.id && (e.branch || '') === b && e.target !== end?.id));
            if (branch !== undefined) {
                graph.edges = graph.edges.filter(e => !(e.source === parent.id && (e.branch || '') === branch));
                graph.edges.push({ id: 'e_' + crypto.randomUUID(), source: parent.id, target: id, branch });
            }
            if (branch === 'false') node.position.y = Math.min(MAX_POSITION, node.position.y + 180);
        }
        if (end && !position) {
            for (const branch of type === 'if' ? ['true', 'false'] : ['']) graph.edges.push({ id: 'e_' + crypto.randomUUID(), source: id, target: end.id, branch });
            if (node.position.x + 240 > (end.position?.x || 0)) end.position = { x: Math.min(MAX_POSITION, node.position.x + 280), y: node.position.y };
        }
        selected = id; selectedEdge = null; connecting = null; markDirty(); renderCanvas(); inspector();
        const element = pane.querySelector(`[data-node="${CSS.escape(id)}"]`); element?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
        if (position) notice('节点已放置，可拖动端口连接其他节点。');
    }
    function example() {
        if (dirty && !confirm('载入示例将替换当前草稿，是否继续？')) return;
        const node = (id, type, name, x, y, config = {}) => ({ id, type, name, position: { x, y }, config });
        const js = `function main(input, ctx) {\n  console.log('JavaScript：金额加 5');\n  return {items: input.items.map(item => ({json: {...item.json, amount: Number(item.json.amount ?? 0) + 5}}))};\n}`;
        const groovy = `def main(Map input, Map ctx) {\n    println 'Groovy：金额翻倍'\n    return [items: input.items.collect { [json: it.json + [amount: (it.json.amount ?: 0) * 2]] }]\n}`;
        graph = { nodes: [node('start', 'start', '开始', 50, 160), node('clean', 'script', 'JavaScript 整理', 330, 160, { language: 'javascript', source: js, params: {}, timeoutMs: 10000 }), node('calculate', 'script', 'Groovy 计算', 610, 160, { language: 'groovy', source: groovy, params: {}, timeoutMs: 10000 }), node('check', 'if', '金额是否达标', 890, 160, { left: '{{ input.items[0].json.amount }}', operator: 'gte', right: 200 }), node('accepted', 'script', '达标分支', 1170, 70, { language: 'javascript', source: 'function main(input, ctx) { return input; }', params: {}, timeoutMs: 10000 }), node('rejected', 'script', '未达标分支', 1170, 330, { language: 'groovy', source: 'def main(Map input, Map ctx) { return input }', params: {}, timeoutMs: 10000 }), node('end', 'end', '结束', 1450, 160)], edges: [['start', 'clean', ''], ['clean', 'calculate', ''], ['calculate', 'check', ''], ['check', 'accepted', 'true'], ['check', 'rejected', 'false'], ['accepted', 'end', ''], ['rejected', 'end', '']].map(([source, target, branch], i) => ({ id: 'example_' + i, source, target, branch })) };
        selected = 'clean'; selectedEdge = null; connecting = null; currentRun = null; markDirty(); renderCanvas(); inspector(); notice('双语言示例已载入。默认输入 120 → JavaScript 加 5 → Groovy 翻倍得到 250 → true 分支。');
    }
    function runInput() { try { return JSON.parse($('[data-input]').value); } catch { throw new Error('运行输入需要有效的 JSON'); } }
    async function refreshRuns() {
        if (!workflow) return;
        const id = workflow.id, rows = await api('/' + id + '/runs'); if (workflow?.id !== id) return;
        $('[data-runs]').innerHTML = rows.length ? rows.map(row => `<button type="button" class="wf-run-row ${row.id === currentRun?.id ? 'is-active' : ''}" data-run="${esc(row.id)}"><span>v${row.version} · ${esc(date(row.createdAt))}</span>${badge(row.status)}</button>`).join('') : '<p class="wf-muted">还没有运行记录</p>';
    }
    function showRun(run) {
        currentRun = run; renderCanvas(); nodeError();
        const open = new Set([...pane.querySelectorAll('[data-step][open]')].map(d => d.dataset.step));
        $('[data-run-detail]').innerHTML = renderRunDetails(run, open, true);
    }
    async function viewRun(id) {
        const workflowId = workflow?.id, run = await api('/runs/' + id);
        if (workflow?.id !== workflowId) return;
        const wasRunning = currentRun?.id === id && ['RUNNING', 'QUEUED'].includes(currentRun.status);
        showRun(run); await refreshRuns(); schedulePoll();
        if (wasRunning && !['RUNNING', 'QUEUED'].includes(run.status)) notice(`版本 v${run.version} 运行${statuses[run.status] || run.status}。`, run.status === 'FAILED');
    }
    function schedulePoll() {
        clearTimeout(timer);
        if (active && editorVisible && currentRun && ['RUNNING', 'QUEUED'].includes(currentRun.status)) {
            timer = setTimeout(async () => { const id = currentRun.id; try { await viewRun(id); } catch (e) { notice(e.message, true); schedulePoll(); } }, 1200);
        }
    }
    function credentialList() {
        $('[data-credential-list]').innerHTML = credentials.length ? credentials.map(c => `<div class="wf-credential-row"><span>${esc(c.name)} <small class="wf-muted">${esc(c.kind === 'feishu' ? '飞书机器人' : c.kind === 'smtp' ? 'SMTP 邮箱' : c.kind)}</small></span><div class="wf-actions"><button type="button" data-edit-credential="${esc(c.id)}">编辑</button><button type="button" data-delete-credential="${esc(c.id)}">删除</button></div></div>`).join('') : '<p class="wf-muted">尚未保存凭据</p>';
        $('[data-credential-form] button[type="submit"]').disabled = !capabilities.credentialsEnabled;
        $('[data-credential-hint]').textContent = !capabilities.credentialsEnabled ? '凭据存储尚未启用，请联系管理员配置后使用。' : editingCredential ? '密码、令牌、Webhook 和签名密钥不回显，留空保留原值。修改将用于所有引用此凭据的节点。' : 'HTTP / 飞书使用 HTTPS；SMTP 邮箱使用 SSL/TLS 或 STARTTLS。';
        credentialFields();
    }
    function resetCredentialForm(kind = 'bearer') {
        editingCredential = null;
        $('[data-credentials-dialog] [data-form-status]')?.remove();
        const form = $('[data-credential-form]'); form.reset(); form.elements.kind.disabled = false; form.elements.kind.value = kind;
        $('[data-credential-title]').textContent = '新增凭据';
        form.querySelector('button[type="submit"]').textContent = '保存凭据';
        $('[data-action="new-credential"]').hidden = true;
        for (const name of ['secret', 'webhookUrl', 'feishuSecret', 'smtpPassword']) form.elements[name].placeholder = '';
        credentialFields();
    }
    async function editCredential(id) {
        commitInspector();
        const credential = await api('/credentials/' + encodeURIComponent(id));
        resetCredentialForm(credential.kind); editingCredential = credential;
        const form = $('[data-credential-form]'), value = credential.value;
        form.elements.name.value = credential.name; form.elements.kind.disabled = true;
        if (credential.kind === 'smtp') {
            for (const [field, key] of Object.entries({smtpHost:'host', smtpPort:'port', smtpSecurity:'security', smtpUsername:'username', smtpFrom:'from'})) form.elements[field].value = value[key] ?? '';
        } else if (['basic', 'header'].includes(credential.kind)) form.elements.username.value = value[credential.kind === 'basic' ? 'username' : 'header'] || '';
        for (const name of ['secret', 'webhookUrl', 'feishuSecret', 'smtpPassword']) form.elements[name].placeholder = '留空保留原值';
        $('[data-credential-title]').textContent = '编辑凭据';
        form.querySelector('button[type="submit"]').textContent = '保存修改';
        $('[data-action="new-credential"]').hidden = false;
        credentialList();
        form.elements.name.focus(); form.elements.name.scrollIntoView({block:'nearest'});
    }
    async function openCredentials(kind = 'bearer', id = null) {
        commitInspector(); credentials = await api('/credentials'); resetCredentialForm(kind); credentialList();
        if (id) await editCredential(id);
        $('[data-credentials-dialog]').showModal();
        $('[data-credential-form]').elements.name.focus();
    }
    function credentialFields() {
        const form = $('[data-credential-form]'), kind = form.elements.kind.value, isFeishu = kind === 'feishu', isSmtp = kind === 'smtp';
        $('[data-credential-user]').hidden = !['basic', 'header'].includes(kind);
        $('[data-credential-secret]').hidden = isFeishu || isSmtp;
        $('[data-credential-feishu]').hidden = !isFeishu;
        $('[data-credential-smtp]').hidden = !isSmtp;
        $('[data-credential-smtp]').disabled = !isSmtp;
        form.elements.secret.disabled = isFeishu || isSmtp; form.elements.secret.required = !editingCredential && !isFeishu && !isSmtp;
        form.elements.webhookUrl.disabled = !isFeishu; form.elements.webhookUrl.required = !editingCredential && isFeishu;
        form.elements.feishuSecret.disabled = !isFeishu;
        form.elements.smtpPassword.required = !editingCredential && isSmtp;
        $('[data-credential-clear]').hidden = !editingCredential || !isFeishu;
        form.elements.clearSecret.disabled = !editingCredential || !isFeishu;
    }
    async function refreshSchedule() {
        const id = workflow?.id; if (!id) return;
        const value = await api('/' + id + '/schedule'); if (workflow?.id !== id) return;
        schedule = value;
        const summary = $('[data-schedule-summary]');
        summary.hidden = !value.enabled;
        summary.textContent = value.enabled ? `◷ 定时已启用 · ${value.cron} · ${value.timeZone} · 下次 ${value.nextRunAt ? new Date(value.nextRunAt).toLocaleString('zh-CN', { timeZone: value.timeZone, hour12: false }) : '—'}${value.lastError ? ' · ' + value.lastError : ''}` : '';
        $('[data-all-runs]').href = '/workflow-runs.html?workflowId=' + encodeURIComponent(id);
    }
    function scheduleBody() {
        const form = $('[data-schedule-form]'); let input;
        try { input = JSON.parse(form.elements.input.value); } catch { throw new Error('定时运行输入需要有效的 JSON'); }
        return { enabled: form.elements.enabled.checked, cron: form.elements.cron.value, timeZone: form.elements.timeZone.value, input, revision: schedule?.revision ?? 0 };
    }
    async function previewSchedule() {
        const form = $('[data-schedule-form]');
        const value = await send('/schedule-preview', { cron: form.elements.cron.value, timeZone: form.elements.timeZone.value });
        $('[data-schedule-preview]').innerHTML = '<strong>接下来 5 次执行（' + esc(value.timeZone) + '）</strong><ol>' + value.nextRuns.map(v => '<li>' + esc(new Date(v).toLocaleString('zh-CN', { timeZone: value.timeZone, hour12: false })) + '</li>').join('') + '</ol>';
    }
    const actions = {
        'use-trending-feishu': () => {
            commitInspector(); const node = graph.nodes.find(n => n.id === selected); if (node?.type !== 'feishu') return;
            Object.assign(node.config, {messageType:'card', title:'{{ input.items[0].json.title }}', text:'{{ input.items[0].json.feishuMarkdown }}'});
            markDirty(); inspector(); notice('已设置分析结果的 Markdown 卡片；请连接到分析节点之后。');
        },
        'use-trending-email': () => {
            commitInspector(); const node = graph.nodes.find(n => n.id === selected); if (node?.type !== 'email') return;
            Object.assign(node.config, {subject:'{{ input.items[0].json.emailSubject }}', body:'{{ input.items[0].json.emailBody }}', format:'text'});
            markDirty(); inspector(); notice('已填入分析结果的邮件主题和正文引用，请填写收件人并连接分析节点。');
        },
        'apply-edge': () => connect($('[data-edge-field="source"]').value, $('[data-edge-field="target"]').value, $('[data-edge-field="branch"]').value, selectedEdge),
        'delete-edge': () => deleteEdge(),
        'reconnect-source': () => beginReconnect(selectedEdge, 'source'),
        'reconnect-target': () => beginReconnect(selectedEdge, 'target'),
        schedule: async () => {
            await refreshSchedule();
            const form = $('[data-schedule-form]');
            form.elements.enabled.checked = schedule.enabled;
            form.elements.cron.value = schedule.cron; form.elements.timeZone.value = schedule.timeZone;
            form.elements.input.value = pretty(schedule.revision ? schedule.input : runInput());
            form.elements.preset.value = [...form.elements.preset.options].some(o => o.value === schedule.cron) ? schedule.cron : 'custom';
            $('[data-cron-label]').hidden = form.elements.preset.value !== 'custom';
            $('[data-schedule-preview]').textContent = capabilities.scheduledExecution === false ? '定时调度服务当前已暂停，保存的配置会在服务启用后生效。' : schedule.lastError || '';
            $('[data-schedule-dialog]').showModal();
        },
        'schedule-preview': previewSchedule,
        'back-to-list': async () => { setEditorVisible(false); await loadList(); workspace.navigate('/workflows.html'); notice('选择工作流进行编辑，或新建工作流。'); },
        create: () => { $('[data-create-dialog]').showModal(); $('[data-create-form] input').focus(); },
        reload: async () => { if (!editorVisible || !dirty || confirm('重新加载会丢弃未保存的草稿，是否继续？')) { await loadList(); if (editorVisible && workflow) await load(workflow.id); else notice('工作流列表已刷新。'); } },
        save,
        'save-node': async () => { await save(); closeNodeDialog(); },
        publish: async () => { await save(); workflow = await send('/' + workflow.id + '/publish', { revision: workflow.revision }); controls(); notice(`版本 v${workflow.publishedVersion} 已发布。后续编辑不会影响该版本。`); },
        run: async () => { const run = await send('/' + workflow.id + '/runs', { input: runInput() }); showRun(run); await refreshRuns(); schedulePoll(); notice(`正在运行已发布版本 v${run.version}${dirty ? '；当前草稿修改不参与本次运行' : ''}。`); },
        test: async () => {
            const node = graph.nodes.find(n => n.id === selected);
            const heading = `<div class="wf-run-summary"><span>草稿节点调试 · ${esc(node.name)}</span></div>`;
            try {
                commitInspector();
                if (node.type === 'github-trending') notice('正在抓取 GitHub Trending，并由 DeepSeek 分析项目，请稍候…');
                const result = await send('/test-node', { node, input: runInput() });
                const report = ['github-trending','tech-weekly'].includes(node.type) ? result.output?.items?.[0]?.json : null;
                const preview = report ? `<h3>飞书消息预览</h3><pre>${esc(report.feishuMarkdown || report.feishuText)}</pre><h3>邮件主题</h3><p>${esc(report.emailSubject)}</p><h3>邮件正文预览</h3><pre>${esc(report.emailBody)}</pre>` : '';
                const content = heading + `<p>${result.durationMs} ms</p>${result.branch ? `<p>分支 ${esc(result.branch)}</p>` : ''}${preview}<h3>输出</h3><pre>${esc(pretty(result.output))}</pre><h3>日志</h3><pre>${esc(result.logs || '无日志')}</pre>`;
                $('[data-run-detail]').innerHTML = content;
                if (node.id === selected && $('[data-node-debug]')) {
                    const target = $('[data-node-debug]'); target.innerHTML = content;
                    if (nodeDialog.open) requestAnimationFrame(() => target.querySelector('h3')?.scrollIntoView({block:'center'}));
                }
                notice('节点调试成功。');
            } catch (error) {
                const content = heading + renderFailure(error.message, error.logs);
                $('[data-run-detail]').innerHTML = content;
                if (node.id === selected && $('[data-node-debug]')) {
                    const target = $('[data-node-debug]'); target.innerHTML = content;
                    requestAnimationFrame(() => target.scrollIntoView({block:'center'}));
                }
                throw error;
            }
        },
        'delete-node': () => { const node = graph.nodes.find(n => n.id === selected); if (['start', 'end'].includes(node?.type)) return; closeNodeDialog(false); graph.nodes = graph.nodes.filter(n => n.id !== selected); graph.edges = graph.edges.filter(e => e.source !== selected && e.target !== selected); selected = graph.nodes[0]?.id; markDirty(); renderCanvas(); inspector(); },
        'delete-workflow': () => deleteWorkflow(workflow?.id),
        'refresh-runs': refreshRuns,
        'cancel-run': async () => { if (currentRun) { showRun(await send('/runs/' + currentRun.id + '/cancel', {})); await refreshRuns(); notice('运行已停止。已经发送的请求或邮件可能已在对端生效。'); } },
        versions: async () => { const versions = await api('/' + workflow.id + '/versions'); $('[data-run-detail]').innerHTML = '<h3>发布版本记录</h3>' + versions.map(v => `<details><summary>v${v.version}<small>${esc(date(v.createdAt))}</small></summary><pre>${esc(pretty(v.runtimes))}</pre></details>`).join(''); },
        example,
        credentials: () => openCredentials(),
        'feishu-credentials': () => openCredentials('feishu'),
        'smtp-credentials': () => openCredentials('smtp'),
        'edit-node-credential': () => { const id = $('[data-field="credentialId"]')?.value; if (id) return openCredentials('bearer', id); },
        'new-credential': () => { resetCredentialForm($('[data-credential-form]').elements.kind.value); credentialList(); },
    };
    pane.addEventListener('click', event => {
        const target = event.target.closest('button,[data-node],[data-edge],[data-reconnect]'); if (!target) return;
        if (target.dataset.reconnect) return;
        if (target.dataset.view) {
            const action = target.dataset.view;
            if (action === 'library' || action === 'inspector') panel(action, !$('.wf-layout').classList.contains('is-' + action + '-hidden'));
            else if (action === 'zoom-in') setZoom(zoom + .1);
            else if (action === 'zoom-out') setZoom(zoom - .1);
            else if (action === 'fit') fitView();
            else if (action === 'reset') setZoom(1);
            else if (action === 'expand') { world.width = Math.min(MAX_POSITION + 1000, world.width + 1200); world.height = Math.min(MAX_POSITION + 1000, world.height + 800); syncWorld(); notice('画布已扩展，可以继续向右和向下编排节点。'); }
            else if (action === 'focus') focus(!focused);
            return;
        }
        if (target.dataset.close) {
            if (target.dataset.close === 'node') {
                try { closeNodeDialog(); } catch (error) { notice(error.message, true); }
            } else pane.querySelector(`[data-${target.dataset.close}-dialog]`).close();
            return;
        }
        if (target.dataset.listRun) { listRunTarget = target.dataset.listRun; const item = workflows.find(w => w.id === listRunTarget); if (!item?.publishedVersion) return; $('[data-list-run-name]').textContent = `${item.name} · 已发布 v${item.publishedVersion}`; $('[data-list-run-dialog]').showModal(); return; }
        if (target.dataset.deleteWorkflow) { perform(() => deleteWorkflow(target.dataset.deleteWorkflow)); return; }
        if (target.dataset.action) { perform(actions[target.dataset.action]); return; }
        try {
            if (target.dataset.edge || target.dataset.selectEdge) selectEdge(target.dataset.edge || target.dataset.selectEdge);
            else if (target.dataset.deleteEdge) deleteEdge(target.dataset.deleteEdge);
            else if (target.dataset.add) add(target.dataset.add);
            else if (target.dataset.source && (event.detail === 0 || connecting?.edgeId)) connectPort('source', target.dataset.source, target.dataset.branch);
            else if (target.dataset.target) connectPort('target', target.dataset.target);
            else if (target.dataset.run) perform(() => viewRun(target.dataset.run));
            else if (target.dataset.editCredential) perform(() => editCredential(target.dataset.editCredential));
            else if (target.dataset.deleteCredential) perform(async () => { if (!confirm('删除后，引用此凭据的节点需要重新选择凭据，是否继续？')) return; await api('/credentials/' + target.dataset.deleteCredential, { method: 'DELETE' }); if (editingCredential?.id === target.dataset.deleteCredential) resetCredentialForm(); credentials = await api('/credentials'); credentialList(); inspector(); });
            else if (target.dataset.node) configureNode(target.dataset.node);
        } catch (error) { notice(error.message, true); }
    });
    pane.addEventListener('dblclick', event => {
        const node = event.target.closest('[data-node]');
        if (!node || event.target.closest('button')) return;
        event.preventDefault();
        try { configureNode(node.dataset.node, true); } catch (error) { notice(error.message, true); }
    });
    pane.addEventListener('input', event => {
        const target = event.target;
        if (target.matches('[data-workflow-search]')) { renderWorkflowList(); return; }
        if (target.matches('[data-name]')) { markDirty(); return; }
        if (!target.matches('[data-inspector] input,[data-inspector] textarea,[data-inspector] select')) return;
        const node = graph?.nodes.find(n => n.id === selected); if (!node) return;
        if (target.dataset.field === 'source') {
            node.config.source = target.value; node.config.sources ||= {}; node.config.sources[node.config.language] = target.value; highlight();
        }
        if (target.dataset.field === 'name') { node.name = target.value; const name = pane.querySelector(`[data-node="${CSS.escape(node.id)}"] .wf-node-name`); if (name) { name.textContent = target.value; syncWorld(); lines(); } }
        markDirty();
    });
    pane.addEventListener('scroll', event => { if (event.target.matches?.('[data-field="source"]')) highlight(); }, true);
    pane.addEventListener('change', event => {
        const target = event.target;
        if (target.matches('[data-schedule-form] [name="preset"]')) {
            const custom = target.value === 'custom'; $('[data-cron-label]').hidden = !custom;
            if (!custom) $('[data-schedule-form]').elements.cron.value = target.value;
            $('[data-schedule-preview]').textContent = '';
        }
        if (target.matches('[data-credential-form] [name="kind"]')) credentialFields();
        if (target.matches('[data-credential-form] [name="smtpSecurity"]')) $('[data-credential-form]').elements.smtpPort.value = target.value === 'ssl' ? '465' : '587';
        if (target.matches('[data-field="credentialId"]')) $('[data-action="edit-node-credential"]').disabled = !target.value;
        if (target.matches('[data-field="sourceUrl"]')) $('[data-trending-url]').value = target.value;
        try {
            if (target.matches('[data-field="messageType"]')) { commitInspector(); $('[data-feishu-title]').hidden = target.value !== 'card'; }
            if (target.matches('[data-edge-field="source"]')) {
                const isIf = graph.nodes.find(n => n.id === target.value)?.type === 'if';
                $('[data-edge-branch]').hidden = !isIf;
                $('[data-edge-field="branch"]').innerHTML = (isIf ? ['true', 'false'] : ['']).map(b => `<option value="${b}">${b || '默认'}</option>`).join('');
            }
            if (target.matches('[data-field="language"]')) {
                commitInspector(); const node = graph.nodes.find(n => n.id === selected); node.config.sources ||= {};
                node.config.sources[node.config.language] = node.config.source; node.config.language = target.value;
                node.config.source = node.config.sources[target.value] || templates[target.value]; markDirty(); renderCanvas(); inspector();
            }
            if (target.matches('[data-connect]') && target.value) { commitInspector(); connect(selected, target.value, target.dataset.connect); }
        } catch (error) { notice(error.message, true); }
    });
    pane.addEventListener('keydown', event => {
        if (event.key === 'Escape' && pane.querySelector('dialog[open]')) return;
        if (event.key === 'Escape' && cancelLibraryDrag) { event.preventDefault(); cancelLibraryDrag(); return; }
        if (event.key === 'Escape') { const wasConnecting = !!connecting; connecting = null; if (graph) renderCanvas(); if (!wasConnecting && !pane.querySelector('dialog[open]')) focus(false); }
        if (['Delete', 'Backspace'].includes(event.key) && selectedEdge && !event.target.closest('input,textarea,select,[contenteditable="true"],dialog')) { event.preventDefault(); deleteEdge(); }
        if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-edge]')) { event.preventDefault(); try { selectEdge(event.target.dataset.edge); } catch (e) { notice(e.message, true); } }
        if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-reconnect]')) { event.preventDefault(); beginReconnect(event.target.dataset.edgeId, event.target.dataset.reconnect); }
        if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-node]')) { event.preventDefault(); try { configureNode(event.target.dataset.node, true); } catch (e) { notice(e.message, true); } }
        if (event.key === 'Tab' && event.target.matches('[data-field="source"]')) { event.preventDefault(); const t = event.target; t.setRangeText('  ', t.selectionStart, t.selectionEnd, 'end'); t.dispatchEvent(new Event('input', { bubbles: true })); }
    });
    pane.querySelectorAll('[data-add]').forEach(button => { button.title = '点击添加，或按住拖到画布指定位置'; });
    pane.addEventListener('dragstart', event => { if (event.target.closest('[data-add]')) event.preventDefault(); });
    pane.addEventListener('pointerdown', event => {
        const button = event.target.closest('[data-add]');
        if (!button || event.button !== 0 || event.isPrimary === false || !workflow || busy) return;
        const scroll = $('.wf-canvas-scroll'), start = { x: event.clientX, y: event.clientY };
        let moved = false, cancelled = false, preview = null;
        const dropPosition = e => {
            const rect = scroll.getBoundingClientRect();
            if (e.clientX < rect.left || e.clientX >= rect.left + scroll.clientWidth || e.clientY < rect.top || e.clientY >= rect.top + scroll.clientHeight
                    || !scroll.contains(document.elementFromPoint(e.clientX, e.clientY))) return null;
            return { x: Math.max(15, Math.min(MAX_POSITION, (e.clientX - rect.left + scroll.scrollLeft) / zoom - (preview?.offsetWidth || 140) / 2)),
                y: Math.max(20, Math.min(MAX_POSITION, (e.clientY - rect.top + scroll.scrollTop) / zoom - 50)) };
        };
        const move = e => {
            if (cancelled) return;
            moved ||= Math.abs(e.clientX - start.x) + Math.abs(e.clientY - start.y) > 5;
            if (!moved) return;
            e.preventDefault();
            if (!preview) {
                const kind = button.dataset.add, style = nodeStyle({ type: ['javascript', 'groovy'].includes(kind) ? 'script' : kind, config: { language: kind } });
                const name = kind === 'groovy' ? 'Groovy 脚本' : kind === 'javascript' ? 'JavaScript 脚本' : style.label;
                preview = document.createElement('div'); preview.className = 'wf-library-preview' + (kind === 'if' ? ' is-if' : ''); preview.setAttribute('aria-hidden', 'true');
                preview.innerHTML = `<b class="wf-icon wf-${style.kind}">${style.icon}</b><div><strong>${esc(name)}</strong><small>${esc(style.label)}</small></div>`;
                pane.append(preview); button.classList.add('is-dragging');
            }
            let position = dropPosition(e);
            if (position) {
                const rect = scroll.getBoundingClientRect();
                if (e.clientX > rect.left + scroll.clientWidth - 30) scroll.scrollLeft += 18; else if (e.clientX < rect.left + 30) scroll.scrollLeft -= 18;
                if (e.clientY > rect.top + scroll.clientHeight - 30) scroll.scrollTop += 18; else if (e.clientY < rect.top + 30) scroll.scrollTop -= 18;
                position = dropPosition(e);
            }
            const rect = scroll.getBoundingClientRect();
            preview.style.left = (position ? rect.left + position.x * zoom - scroll.scrollLeft : e.clientX - preview.offsetWidth / 2 * zoom) + 'px';
            preview.style.top = (position ? rect.top + position.y * zoom - scroll.scrollTop : e.clientY - 50 * zoom) + 'px';
            preview.style.transform = `scale(${zoom})`;
            preview.classList.toggle('is-valid', !!position); scroll.classList.toggle('is-library-drop-target', !!position);
        };
        const cancel = () => { cancelled = true; preview?.remove(); scroll.classList.remove('is-library-drop-target'); button.classList.remove('is-dragging'); };
        const end = e => {
            button.removeEventListener('pointermove', move); button.removeEventListener('pointerup', end); button.removeEventListener('pointercancel', end); button.removeEventListener('lostpointercapture', end);
            window.removeEventListener('blur', end); cancelLibraryDrag = null;
            if (button.hasPointerCapture(event.pointerId)) button.releasePointerCapture(event.pointerId);
            const position = e.type === 'pointerup' && moved && !cancelled ? dropPosition(e) : null;
            preview?.remove(); scroll.classList.remove('is-library-drop-target'); button.classList.remove('is-dragging');
            if (moved || cancelled) {
                // Pointer capture sends the synthetic click back to the library button after a drop.
                const ignoreClick = click => { click.preventDefault(); click.stopImmediatePropagation(); };
                button.addEventListener('click', ignoreClick, { capture: true, once: true });
                setTimeout(() => button.removeEventListener('click', ignoreClick, true), 0);
            }
            if (e.type !== 'pointerup' || !moved || cancelled) return;
            if (!position) return notice('拖拽已取消，请松开到画布内放置节点。');
            try { add(button.dataset.add, position); } catch (error) { notice(error.message, true); }
        };
        cancelLibraryDrag = cancel; button.setPointerCapture(event.pointerId);
        button.addEventListener('pointermove', move); button.addEventListener('pointerup', end); button.addEventListener('pointercancel', end); button.addEventListener('lostpointercapture', end);
        window.addEventListener('blur', end);
    });
    pane.addEventListener('pointerdown', event => {
        const handle = event.target.closest('[data-drag]'); if (!handle || event.button !== 0) return;
        const node = graph.nodes.find(n => n.id === handle.dataset.drag), element = handle.closest('[data-node]');
        const scroll = $('.wf-canvas-scroll');
        const start = { x: event.clientX, y: event.clientY, left: element.offsetLeft, top: element.offsetTop, scrollLeft: scroll.scrollLeft, scrollTop: scroll.scrollTop }; let moved = false;
        handle.setPointerCapture(event.pointerId);
        const move = e => {
            if (Math.abs(e.clientX - start.x) + Math.abs(e.clientY - start.y) > 3) moved = true;
            if (!moved) return;
            const rect = scroll.getBoundingClientRect();
            if (e.clientX > rect.right - 30) scroll.scrollLeft += 18; else if (e.clientX < rect.left + 30) scroll.scrollLeft -= 18;
            if (e.clientY > rect.bottom - 30) scroll.scrollTop += 18; else if (e.clientY < rect.top + 30) scroll.scrollTop -= 18;
            const x = Math.max(15, Math.min(MAX_POSITION, start.left + (e.clientX - start.x + scroll.scrollLeft - start.scrollLeft) / zoom));
            const y = Math.max(20, Math.min(MAX_POSITION, start.top + (e.clientY - start.y + scroll.scrollTop - start.scrollTop) / zoom));
            element.style.left = x + 'px'; element.style.top = y + 'px'; node.position = { x, y }; syncWorld(); lines();
        };
        const end = () => { handle.removeEventListener('pointermove', move); handle.removeEventListener('pointerup', end); handle.removeEventListener('pointercancel', end); if (moved) markDirty(); };
        handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', end); handle.addEventListener('pointercancel', end);
    });
    $('.wf-canvas-scroll').addEventListener('pointerdown', event => {
        const handle = event.target.closest('[data-reconnect]'), source = event.target.closest('[data-source]');
        if ((!handle && !source) || event.button !== 0 || event.isPrimary === false) return;
        // The explicit reconnect buttons also allow choosing a port with a click.
        if (!handle && connecting?.edgeId) return;
        event.preventDefault(); event.stopPropagation();
        const scroll = event.currentTarget, edgeId = handle?.dataset.edgeId, endpoint = handle?.dataset.reconnect || 'target';
        const point = e => {
            const rect = scroll.getBoundingClientRect();
            return { x: (e.clientX - rect.left + scroll.scrollLeft) / zoom, y: (e.clientY - rect.top + scroll.scrollTop) / zoom };
        };
        const start = { x: event.clientX, y: event.clientY }; let moved = false;
        try {
            if (handle) beginReconnect(edgeId, endpoint);
            else connectPort('source', source.dataset.source, source.dataset.branch || '');
        } catch (error) { notice(error.message, true); return; }
        const connection = connecting;
        connection.point = point(event); renderCanvas();
        scroll.setPointerCapture(event.pointerId);
        const move = e => {
            if (connecting !== connection) return;
            moved ||= Math.abs(e.clientX - start.x) + Math.abs(e.clientY - start.y) > 4;
            if (!moved) return;
            connecting.point = point(e);
            pane.querySelectorAll('.wf-port.is-drop-target').forEach(port => port.classList.remove('is-drop-target'));
            document.elementFromPoint(e.clientX, e.clientY)?.closest(endpoint === 'source' ? '[data-source]' : '[data-target]')?.classList.add('is-drop-target');
            lines();
        };
        const end = e => {
            scroll.removeEventListener('pointermove', move); scroll.removeEventListener('pointerup', end); scroll.removeEventListener('pointercancel', end);
            if (scroll.hasPointerCapture(event.pointerId)) scroll.releasePointerCapture(event.pointerId);
            try {
                if (e.type === 'pointerup' && moved && connecting === connection) {
                    const element = document.elementFromPoint(e.clientX, e.clientY);
                    const port = element?.closest(endpoint === 'source' ? '[data-source]' : '[data-target]');
                    const node = graph.nodes.find(n => n.id === element?.closest('[data-node]')?.dataset.node);
                    if (port) connectPort(endpoint, endpoint === 'source' ? port.dataset.source : port.dataset.target, port.dataset.branch || '');
                    else if (node && (endpoint === 'target' || node.type !== 'if')) connectPort(endpoint, node.id);
                    else notice(edgeId ? '未连接到有效端口，已保留原连线。' : '未连接到目标节点，已取消本次连线。');
                }
            } catch (error) { notice(error.message, true); }
            finally {
                connecting = null; renderCanvas(); inspector();
                const focusTarget = selectedEdge ? pane.querySelector(`[data-edge="${CSS.escape(selectedEdge)}"]`) : scroll;
                focusTarget?.focus({ preventScroll: true });
            }
        };
        scroll.addEventListener('pointermove', move); scroll.addEventListener('pointerup', end); scroll.addEventListener('pointercancel', end);
    });
    $('.wf-canvas-scroll').addEventListener('wheel', event => {
        if (!event.ctrlKey && !event.metaKey) return;
        event.preventDefault(); const rect = event.currentTarget.getBoundingClientRect();
        setZoom(zoom * Math.exp(-event.deltaY * .002), { x: event.clientX - rect.left, y: event.clientY - rect.top });
    }, { passive: false });
    $('.wf-canvas-scroll').addEventListener('pointerdown', event => {
        if (event.button !== 0 || event.pointerType === 'touch' || event.target.closest('[data-node],button,[data-edge],[data-reconnect]')) return;
        const scroll = event.currentTarget, start = { x: event.clientX, y: event.clientY, left: scroll.scrollLeft, top: scroll.scrollTop };
        scroll.setPointerCapture(event.pointerId); scroll.classList.add('is-panning');
        const move = e => { scroll.scrollLeft = start.left - e.clientX + start.x; scroll.scrollTop = start.top - e.clientY + start.y; };
        const end = () => { scroll.classList.remove('is-panning'); scroll.removeEventListener('pointermove', move); scroll.removeEventListener('pointerup', end); scroll.removeEventListener('pointercancel', end); };
        scroll.addEventListener('pointermove', move); scroll.addEventListener('pointerup', end); scroll.addEventListener('pointercancel', end);
    });
    const resize = new ResizeObserver(() => { if (active && graph) syncWorld(); }); resize.observe($('.wf-canvas-scroll'));
    $('[data-schedule-form]').addEventListener('submit', event => {
        event.preventDefault();
        perform(async () => {
            const id = workflow.id;
            schedule = await send('/' + id + '/schedule', scheduleBody(), 'PUT');
            $('[data-schedule-dialog]').close(); await refreshSchedule();
            notice(schedule.enabled ? '定时执行已启用，将执行当前已发布版本。' : '定时执行已暂停，已有实例继续运行。');
        });
    });
    $('[data-list-run-form]').addEventListener('submit', event => {
        event.preventDefault();
        perform(async () => {
            let input; try { input = JSON.parse(event.currentTarget.elements.input.value); } catch { throw new Error('运行输入需要有效的 JSON'); }
            if (!input || Array.isArray(input) || typeof input !== 'object') throw new Error('运行输入需要是 JSON 对象');
            const id = listRunTarget, run = await send('/' + encodeURIComponent(id) + '/runs', {input});
            listRuns.set(id, run); $('[data-list-run-dialog]').close(); renderWorkflowList(); scheduleListPoll();
            notice('已开始运行发布版本，列表将自动更新进度。');
        });
    });
    $('[data-create-form]').addEventListener('submit', event => {
        event.preventDefault(); const form = event.currentTarget;
        perform(async () => { if (dirty && !confirm('新建将离开当前未保存的草稿，是否继续？')) return; const result = await send('', { name: form.elements.name.value }); $('[data-create-dialog]').close(); form.reset(); await loadList(); await load(result.id, true); });
    });
    $('[data-credential-form]').addEventListener('submit', event => {
        event.preventDefault(); const form = event.currentTarget, data = new FormData(form), kind = form.elements.kind.value, secret = data.get('secret'), editingId = editingCredential?.id;
        perform(async () => {
            const value = kind === 'feishu' ? { webhookUrl: data.get('webhookUrl'), secret: data.get('feishuSecret') || '' }
                : kind === 'smtp' ? { host: data.get('smtpHost'), port: Number(data.get('smtpPort')), security: data.get('smtpSecurity'), username: data.get('smtpUsername'), password: data.get('smtpPassword'), from: data.get('smtpFrom') }
                : kind === 'bearer' ? { token: secret } : kind === 'basic' ? { username: data.get('username'), password: secret } : { header: data.get('username'), secret };
            const created = await send('/credentials' + (editingId ? '/' + encodeURIComponent(editingId) : ''), { name: data.get('name'), kind, value, clearSecret: data.get('clearSecret') === 'on' }, editingId ? 'PUT' : 'POST');
            if (!editingId && ((kind === 'feishu' && graph?.nodes.find(n => n.id === selected)?.type === 'feishu') || (kind === 'smtp' && graph?.nodes.find(n => n.id === selected)?.type === 'email'))) {
                commitInspector(); graph.nodes.find(n => n.id === selected).config.credentialId = created.id; markDirty();
            }
            resetCredentialForm(); credentials = await api('/credentials'); credentialList(); inspector(); notice(editingId ? '凭据已更新，已有节点继续使用此凭据。' : '凭据已保存。');
        });
    });
    $('[data-credentials-dialog]').addEventListener('close', () => { if (!$('[data-credentials-dialog]').open) resetCredentialForm(); });
    window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
    return {
        async activate(url) {
            active = true;
            const requested = new URL(url).searchParams.get('id');
            if (!initialized) {
                await perform(async () => {
                    [capabilities, credentials] = await Promise.all([api('/capabilities'), api('/credentials')]); await loadList();
                    initialized = true;
                    if (requested) await load(requested);
                    else { setEditorVisible(false); notice('选择工作流进行编辑，或新建工作流。'); }
                    if (capabilities.sandbox === 'unavailable') notice('脚本运行环境尚未安装；HTTP 和条件节点可以正常使用。', true);
                });
            } else if (!requested) {
                setEditorVisible(false);
                await perform(async () => { await loadList(); notice('选择工作流进行编辑，或新建工作流。'); });
            } else if (requested !== workflow?.id) {
                if (dirty && !confirm('打开其他工作流会丢弃当前未保存的草稿，是否继续？')) {
                    workspace.replace(editorVisible ? '/workflows.html?id=' + encodeURIComponent(workflow.id) : '/workflows.html'); return;
                }
                await perform(() => load(requested));
            } else {
                setEditorVisible(true); schedulePoll();
                if (!busy) refreshSchedule().catch(e => notice(e.message, true));
            }
        },
        deactivate() { clearTimeout(listRunTimer); closeNodeDialog(false); cancelLibraryDrag?.(); active = false; clearTimeout(timer); focus(false); },
    };
}
