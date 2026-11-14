import { renderRunDetails } from './workflow-run-details.js';
const templates = {
    javascript: `function main(input, ctx) {\n  return {\n    items: input.items.map(item => ({\n      json: { ...item.json, name: String(item.json.name ?? '').toUpperCase() }\n    }))\n  };\n}`,
    groovy: `def main(Map input, Map ctx) {\n    return [items: input.items.collect { item ->\n        [json: item.json + [name: (item.json.name ?: '').toUpperCase()]]\n    }]\n}`,
};
const labels = { manual: '手动触发', http: 'HTTP 请求', script: '脚本', if: '条件判断' };
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
    let workflows = [], workflow = null, graph = null, selected = null, connecting = null;
    let dirty = false, busy = false, active = false, initialized = false, timer = null, currentRun = null, capabilities = {}, credentials = [], schedule = null;
    const notice = (message, error = false) => {
        $('[data-notice]').textContent = message; $('[data-notice]').classList.toggle('is-error', error);
        const dialog = pane.querySelector('dialog[open]');
        if (dialog) {
            let status = dialog.querySelector('[data-form-status]');
            if (!status) { status = document.createElement('p'); status.dataset.formStatus = ''; status.className = 'wf-notice'; status.setAttribute('role', 'status'); dialog.append(status); }
            status.textContent = message; status.classList.toggle('is-error', error);
        }
    };
    async function api(path = '', options = {}) {
        const response = await fetch('/api/workflows' + path, { cache: 'no-store', ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(data.message || data.detail || `请求未完成（${response.status}）`);
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
        pane.querySelectorAll('[data-action="save"],[data-action="publish"],[data-action="create"],[data-action="reload"],[data-action="delete-workflow"],[data-action="test"]').forEach(b => b.disabled = busy || (!workflow && b.dataset.action !== 'create'));
        const run = $('[data-action="run"]');
        run.disabled = busy || !workflow?.publishedVersion;
        run.textContent = workflow?.publishedVersion ? `▶ 运行 v${workflow.publishedVersion}` : '先发布后运行';
        if (workflow) $('[data-version]').textContent = `${dirty ? '有未保存修改 · ' : '草稿已保存 · '}${workflow.publishedVersion ? '已发布 v' + workflow.publishedVersion : '尚未发布'}`;
    }
    function markDirty() { dirty = true; controls(); }
    function picker() {
        $('[data-workflows]').innerHTML = workflows.length ? workflows.map(w => `<option value="${esc(w.id)}" ${w.id === workflow?.id ? 'selected' : ''}>${esc(w.name)}</option>`).join('') : '<option value="">还没有工作流，先新建一个</option>';
    }
    async function loadList() { workflows = await api(); picker(); }
    async function load(id) {
        clearTimeout(timer); currentRun = null;
        workflow = await api('/' + encodeURIComponent(id)); graph = structuredClone(workflow.definition);
        graph.nodes ??= []; graph.edges ??= []; selected = graph.nodes[0]?.id || null; dirty = false; connecting = null;
        $('[data-editor]').hidden = false; $('[data-name]').value = workflow.name;
        $('[data-run-detail]').innerHTML = '<div class="wf-empty">运行一次工作流，看看数据如何流转。</div>';
        picker(); renderCanvas(); inspector(); controls();
        workspace.replace('/workflows.html?id=' + encodeURIComponent(id));
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
        workflows = workflows.map(w => w.id === result.id ? result : w); picker(); controls(); renderCanvas();
        notice('草稿已保存。');
    }
    function selectNode(id) { commitInspector(); selected = id; connecting = null; renderCanvas(); inspector(); }
    function nodeStyle(node) {
        const requested = node.type === 'script' ? (node.config?.language === 'groovy' ? 'groovy' : 'script') : node.type;
        const kind = ['manual', 'http', 'script', 'groovy', 'if'].includes(requested) ? requested : 'script';
        return { kind, icon: ({ manual: '▶', http: '↗', script: 'JS', groovy: 'G', if: '⑂' })[kind], label: node.type === 'script' ? (node.config.language === 'groovy' ? 'Groovy' : 'JavaScript') : labels[node.type] };
    }
    function renderCanvas() {
        if (!graph) return;
        const runNodes = new Map((currentRun?.nodes || []).map(n => [n.nodeId, n.status]));
        $('[data-nodes]').innerHTML = graph.nodes.map(node => {
            const style = nodeStyle(node), status = runNodes.get(node.id), position = node.position || { x: 60, y: 100 };
            const x = Math.max(15, Math.min(1250, Number(position.x) || 60)), y = Math.max(20, Math.min(850, Number(position.y) || 100));
            return `<div class="wf-node ${node.type === 'if' ? 'is-if' : ''} ${selected === node.id ? 'is-selected' : ''} ${status ? 'status-' + esc(status) : ''}" data-node="${esc(node.id)}" style="left:${x}px;top:${y}px" tabindex="0" role="button" aria-label="配置节点 ${esc(node.name)}">
                ${node.type !== 'manual' ? `<button type="button" class="wf-port wf-port-in" data-target="${esc(node.id)}" aria-label="连接到 ${esc(node.name)}"></button>` : ''}
                <div class="wf-node-head" data-drag="${esc(node.id)}"><b class="wf-icon wf-${style.kind}">${style.icon}</b><div><span class="wf-node-name">${esc(node.name)}</span><span class="wf-node-type">${esc(style.label)}</span></div></div>
                <div class="wf-node-foot"><span>${esc(node.id)}</span><span>${esc(statuses[status] || '')}</span></div>
                ${(node.type === 'if' ? ['true', 'false'] : ['']).map(branch => `<button type="button" class="wf-port wf-port-out ${connecting?.source === node.id && connecting?.branch === branch ? 'is-connecting' : ''}" data-source="${esc(node.id)}" data-branch="${branch}" aria-label="${esc(node.name)} ${branch || '默认'}出口"></button>${branch ? `<span class="wf-branch-label ${branch}">${branch}</span>` : ''}`).join('')}
            </div>`;
        }).join('');
        lines();
        $('[data-canvas-hint]').textContent = connecting ? '正在连线：点击目标节点左侧圆点，或按 Esc 取消' : '点击节点配置 · 拖动标题移动 · 支持 true / false 分支';
    }
    function lines() {
        $('[data-lines]').innerHTML = graph.edges.map(edge => {
            const source = pane.querySelector(`[data-node="${CSS.escape(edge.source)}"]`), target = pane.querySelector(`[data-node="${CSS.escape(edge.target)}"]`);
            if (!source || !target) return '';
            const x = source.offsetLeft + 208, y = source.offsetTop + (edge.branch === 'true' ? 64 : edge.branch === 'false' ? 94 : 50), tx = target.offsetLeft, ty = target.offsetTop + 50;
            const bend = Math.max(60, Math.abs(tx - x) * .5);
            return `<path class="wf-edge" d="M${x},${y} C${x + bend},${y} ${tx - bend},${ty} ${tx},${ty}" marker-end="url(#wf-arrow)"/>${edge.branch ? `<text class="wf-edge-label" x="${x + 20}" y="${y - 8}">${esc(edge.branch)}</text>` : ''}`;
        }).join('');
    }
    function connectionFields(node) {
        return (node.type === 'if' ? ['true', 'false'] : ['']).map(branch => {
            const edge = graph.edges.find(e => e.source === node.id && (e.branch || '') === branch);
            return field(branch ? `${branch} 分支` : '下一个节点', `<select data-connect="${branch}"><option value="">结束流程</option>${graph.nodes.filter(n => n.id !== node.id && n.type !== 'manual').map(n => `<option value="${esc(n.id)}" ${edge?.target === n.id ? 'selected' : ''}>${esc(n.name)}</option>`).join('')}</select>`);
        }).join('');
    }
    function inspector() {
        const node = graph?.nodes.find(n => n.id === selected);
        if (!node) { $('[data-inspector]').innerHTML = '<div class="wf-empty">选择一个节点进行配置。</div>'; return; }
        const c = node.config ||= {}, style = nodeStyle(node);
        let content = field('节点名称', inputField('name', node.name, 'maxlength="80"'));
        if (node.type === 'manual') content += '<p class="wf-help">点击右上角运行按钮，将下方输入 JSON 传入已发布的工作流。</p>';
        if (node.type === 'script') {
            content += field('脚本语言', select('language', c.language, [['javascript', 'JavaScript'], ['groovy', 'Groovy']]));
            content += '<p class="wf-help">入口 main(input, ctx)，返回 { items: [{ json: … }] }。网络请求请使用 HTTP 节点。</p>';
            content += `<div class="wf-code-wrap"><pre aria-hidden="true" data-highlight></pre><textarea aria-label="脚本代码" data-field="source" spellcheck="false" wrap="off">${esc(c.source)}</textarea></div>`;
            content += field('脚本参数 ctx.params（JSON）', `<textarea data-field="params" spellcheck="false">${esc(pretty(c.params || {}))}</textarea>`);
        }
        if (node.type === 'http') {
            content += field('请求方法', select('method', c.method || 'GET', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD'].map(v => [v, v])));
            content += field('请求地址', inputField('url', c.url, 'placeholder="https://example.com/api"'));
            content += field('凭据', select('credentialId', c.credentialId || '', [['', '不使用凭据'], ...credentials.map(v => [v.id, v.name])]));
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
        if (['http', 'script'].includes(node.type)) content += field('超时（毫秒，100–30000）', inputField('timeoutMs', c.timeoutMs || 10000, 'type="number" min="100" max="30000" step="100"'));
        content += `<h3 class="wf-section-label">连接出口</h3>${connectionFields(node)}`;
        content += `<div class="wf-node-actions"><button type="button" data-action="test">调试此节点</button>${node.type !== 'manual' ? '<button type="button" class="wf-danger" data-action="delete-node">删除节点</button>' : ''}</div>`;
        $('[data-inspector]').innerHTML = `<h2>${esc(style.label)}</h2><div class="wf-node-id">${esc(node.id)}</div>${content}`;
        highlight(); controls();
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
    function connect(source, target, branch) {
        if (source === target || graph.nodes.find(n => n.id === target)?.type === 'manual') throw new Error('不能连接到自身或触发节点');
        if (graph.edges.some(e => e.target === target && !(e.source === source && (e.branch || '') === branch))) throw new Error('一期暂不支持多路汇合，请为分支添加独立节点');
        const edges = graph.edges.filter(e => !(e.source === source && (e.branch || '') === branch));
        const seen = new Set(), queue = target ? [target] : [];
        while (queue.length) {
            const id = queue.pop(); if (id === source) throw new Error('不能创建循环连线'); if (seen.has(id)) continue; seen.add(id);
            edges.filter(e => e.source === id).forEach(e => queue.push(e.target));
        }
        if (target) edges.push({ id: 'e_' + crypto.randomUUID(), source, target, branch });
        graph.edges = edges; connecting = null; markDirty(); renderCanvas(); inspector();
    }
    function add(kind) {
        if (!workflow) return notice('请先新建工作流。', true);
        commitInspector(); if (graph.nodes.length >= 64) throw new Error('一个流程最多包含 64 个节点');
        const parent = graph.nodes.find(n => n.id === selected), id = 'n_' + crypto.randomUUID().replaceAll('-', '').slice(0, 10);
        const type = ['javascript', 'groovy'].includes(kind) ? 'script' : kind;
        const config = type === 'script' ? { language: kind, source: templates[kind], sources: { [kind]: templates[kind] }, params: {}, timeoutMs: 10000 }
            : type === 'http' ? { method: 'GET', url: '', headers: {}, body: null, timeoutMs: 10000, failOnHttpError: true }
            : { left: '{{ input.items[0].json.amount }}', operator: 'gte', right: 100 };
        const node = { id, type, name: kind === 'groovy' ? 'Groovy 脚本' : kind === 'javascript' ? 'JavaScript 脚本' : labels[type], config,
            position: { x: Math.min(1200, (parent?.position?.x || 60) + 280), y: parent?.position?.y || 140 } };
        graph.nodes.push(node);
        if (parent) {
            const branch = (parent.type === 'if' ? ['true', 'false'] : ['']).find(b => !graph.edges.some(e => e.source === parent.id && (e.branch || '') === b));
            if (branch !== undefined) graph.edges.push({ id: 'e_' + crypto.randomUUID(), source: parent.id, target: id, branch });
            if (branch === 'false') node.position.y = Math.min(800, node.position.y + 180);
        }
        selected = id; markDirty(); renderCanvas(); inspector();
        const element = pane.querySelector(`[data-node="${CSS.escape(id)}"]`); element?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
    }
    function example() {
        if (dirty && !confirm('载入示例将替换当前草稿，是否继续？')) return;
        const node = (id, type, name, x, y, config = {}) => ({ id, type, name, position: { x, y }, config });
        const js = `function main(input, ctx) {\n  console.log('JavaScript：金额加 5');\n  return {items: input.items.map(item => ({json: {...item.json, amount: Number(item.json.amount ?? 0) + 5}}))};\n}`;
        const groovy = `def main(Map input, Map ctx) {\n    println 'Groovy：金额翻倍'\n    return [items: input.items.collect { [json: it.json + [amount: (it.json.amount ?: 0) * 2]] }]\n}`;
        graph = { nodes: [node('start', 'manual', '手动触发', 50, 160), node('clean', 'script', 'JavaScript 整理', 330, 160, { language: 'javascript', source: js, params: {}, timeoutMs: 10000 }), node('calculate', 'script', 'Groovy 计算', 610, 160, { language: 'groovy', source: groovy, params: {}, timeoutMs: 10000 }), node('check', 'if', '金额是否达标', 890, 160, { left: '{{ input.items[0].json.amount }}', operator: 'gte', right: 200 }), node('accepted', 'script', '达标分支', 1170, 70, { language: 'javascript', source: 'function main(input, ctx) { return input; }', params: {}, timeoutMs: 10000 }), node('rejected', 'script', '未达标分支', 1170, 330, { language: 'groovy', source: 'def main(Map input, Map ctx) { return input }', params: {}, timeoutMs: 10000 })], edges: [['start', 'clean', ''], ['clean', 'calculate', ''], ['calculate', 'check', ''], ['check', 'accepted', 'true'], ['check', 'rejected', 'false']].map(([source, target, branch], i) => ({ id: 'example_' + i, source, target, branch })) };
        selected = 'clean'; currentRun = null; markDirty(); renderCanvas(); inspector(); notice('双语言示例已载入。默认输入 120 → JavaScript 加 5 → Groovy 翻倍得到 250 → true 分支。');
    }
    function runInput() { try { return JSON.parse($('[data-input]').value); } catch { throw new Error('运行输入需要有效的 JSON'); } }
    async function refreshRuns() {
        if (!workflow) return;
        const id = workflow.id, rows = await api('/' + id + '/runs'); if (workflow?.id !== id) return;
        $('[data-runs]').innerHTML = rows.length ? rows.map(row => `<button type="button" class="wf-run-row ${row.id === currentRun?.id ? 'is-active' : ''}" data-run="${esc(row.id)}"><span>v${row.version} · ${esc(date(row.createdAt))}</span>${badge(row.status)}</button>`).join('') : '<p class="wf-muted">还没有运行记录</p>';
    }
    function showRun(run) {
        currentRun = run; renderCanvas();
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
        if (active && currentRun && ['RUNNING', 'QUEUED'].includes(currentRun.status)) {
            timer = setTimeout(async () => { const id = currentRun.id; try { await viewRun(id); } catch (e) { notice(e.message, true); schedulePoll(); } }, 1200);
        }
    }
    function credentialList() {
        $('[data-credential-list]').innerHTML = credentials.length ? credentials.map(c => `<div class="wf-credential-row"><span>${esc(c.name)} <small class="wf-muted">${esc(c.kind)}</small></span><button type="button" data-delete-credential="${esc(c.id)}">删除</button></div>`).join('') : '<p class="wf-muted">尚未保存凭据</p>';
        $('[data-credential-form] button[type="submit"]').disabled = !capabilities.credentialsEnabled;
        $('[data-credential-hint]').textContent = capabilities.credentialsEnabled ? '凭据仅用于 HTTPS 请求。' : '凭据存储尚未启用，请联系管理员配置后使用。';
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
        create: () => { $('[data-create-dialog]').showModal(); $('[data-create-form] input').focus(); },
        reload: async () => { if (!dirty || confirm('重新加载会丢弃未保存的草稿，是否继续？')) { await loadList(); if (workflow) await load(workflow.id); } },
        save,
        publish: async () => { await save(); workflow = await send('/' + workflow.id + '/publish', { revision: workflow.revision }); controls(); notice(`版本 v${workflow.publishedVersion} 已发布。后续编辑不会影响该版本。`); },
        run: async () => { const run = await send('/' + workflow.id + '/runs', { input: runInput() }); showRun(run); await refreshRuns(); schedulePoll(); notice(`正在运行已发布版本 v${run.version}${dirty ? '；当前草稿修改不参与本次运行' : ''}。`); },
        test: async () => { commitInspector(); const node = graph.nodes.find(n => n.id === selected); const result = await send('/test-node', { node, input: runInput() }); $('[data-run-detail]').innerHTML = `<div class="wf-run-summary"><span>草稿节点调试 · ${esc(node.name)}</span><span>${result.durationMs} ms</span>${result.branch ? `<span>分支 ${esc(result.branch)}</span>` : ''}</div><h3>输出</h3><pre>${esc(pretty(result.output))}</pre><h3>日志</h3><pre>${esc(result.logs || '无日志')}</pre>`; notice('节点调试成功。'); },
        'delete-node': () => { const node = graph.nodes.find(n => n.id === selected); if (node?.type === 'manual') return; graph.nodes = graph.nodes.filter(n => n.id !== selected); graph.edges = graph.edges.filter(e => e.source !== selected && e.target !== selected); selected = graph.nodes[0]?.id; markDirty(); renderCanvas(); inspector(); },
        'delete-workflow': async () => { if (!workflow || !confirm(`删除“${workflow.name}”？历史运行记录会保留。`)) return; await api('/' + workflow.id, { method: 'DELETE' }); workflow = null; graph = null; dirty = false; currentRun = null; clearTimeout(timer); $('[data-editor]').hidden = true; workspace.replace('/workflows.html'); await loadList(); if (workflows.length) await load(workflows[0].id); else notice('工作流已删除。'); },
        'refresh-runs': refreshRuns,
        'cancel-run': async () => { if (currentRun) { showRun(await send('/runs/' + currentRun.id + '/cancel', {})); await refreshRuns(); notice('运行已停止。已经发送的 HTTP 请求可能已在对端生效。'); } },
        versions: async () => { const versions = await api('/' + workflow.id + '/versions'); $('[data-run-detail]').innerHTML = '<h3>发布版本记录</h3>' + versions.map(v => `<details><summary>v${v.version}<small>${esc(date(v.createdAt))}</small></summary><pre>${esc(pretty(v.runtimes))}</pre></details>`).join(''); },
        example,
        credentials: async () => { credentials = await api('/credentials'); credentialList(); $('[data-credentials-dialog]').showModal(); },
    };
    pane.addEventListener('click', event => {
        const target = event.target.closest('button,[data-node]'); if (!target) return;
        if (target.dataset.close) { pane.querySelector(`[data-${target.dataset.close}-dialog]`).close(); return; }
        if (target.dataset.action) { perform(actions[target.dataset.action]); return; }
        try {
            if (target.dataset.add) add(target.dataset.add);
            else if (target.dataset.source) { commitInspector(); connecting = { source: target.dataset.source, branch: target.dataset.branch }; renderCanvas(); }
            else if (target.dataset.target) { if (connecting) connect(connecting.source, target.dataset.target, connecting.branch); else selectNode(target.dataset.target); }
            else if (target.dataset.run) perform(() => viewRun(target.dataset.run));
            else if (target.dataset.deleteCredential) perform(async () => { if (!confirm('删除后，引用此凭据的节点需要重新选择凭据，是否继续？')) return; await api('/credentials/' + target.dataset.deleteCredential, { method: 'DELETE' }); credentials = await api('/credentials'); credentialList(); inspector(); });
            else if (target.dataset.node && target.dataset.node !== selected) selectNode(target.dataset.node);
        } catch (error) { notice(error.message, true); }
    });
    pane.addEventListener('input', event => {
        const target = event.target;
        if (target.matches('[data-name]')) { markDirty(); return; }
        if (!target.matches('[data-inspector] input,[data-inspector] textarea,[data-inspector] select')) return;
        const node = graph?.nodes.find(n => n.id === selected); if (!node) return;
        if (target.dataset.field === 'source') {
            node.config.source = target.value; node.config.sources ||= {}; node.config.sources[node.config.language] = target.value; highlight();
        }
        if (target.dataset.field === 'name') { node.name = target.value; const name = pane.querySelector(`[data-node="${CSS.escape(node.id)}"] .wf-node-name`); if (name) name.textContent = target.value; }
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
        if (target.matches('[data-workflows]')) { const id = target.value; if (dirty && !confirm('切换将丢弃未保存的草稿，是否继续？')) { picker(); return; } perform(() => load(id)); }
        if (target.matches('[data-credential-form] [name="kind"]')) $('[data-credential-user]').hidden = target.value === 'bearer';
        try {
            if (target.matches('[data-field="language"]')) {
                commitInspector(); const node = graph.nodes.find(n => n.id === selected); node.config.sources ||= {};
                node.config.sources[node.config.language] = node.config.source; node.config.language = target.value;
                node.config.source = node.config.sources[target.value] || templates[target.value]; markDirty(); renderCanvas(); inspector();
            }
            if (target.matches('[data-connect]')) { commitInspector(); connect(selected, target.value, target.dataset.connect); }
        } catch (error) { notice(error.message, true); }
    });
    pane.addEventListener('keydown', event => {
        if (event.key === 'Escape') { connecting = null; if (graph) renderCanvas(); }
        if ((event.key === 'Enter' || event.key === ' ') && event.target.matches('[data-node]')) { event.preventDefault(); try { selectNode(event.target.dataset.node); } catch (e) { notice(e.message, true); } }
        if (event.key === 'Tab' && event.target.matches('[data-field="source"]')) { event.preventDefault(); const t = event.target; t.setRangeText('  ', t.selectionStart, t.selectionEnd, 'end'); t.dispatchEvent(new Event('input', { bubbles: true })); }
    });
    pane.addEventListener('pointerdown', event => {
        const handle = event.target.closest('[data-drag]'); if (!handle || event.button !== 0) return;
        const node = graph.nodes.find(n => n.id === handle.dataset.drag), element = handle.closest('[data-node]');
        const start = { x: event.clientX, y: event.clientY, left: element.offsetLeft, top: element.offsetTop }; let moved = false;
        handle.setPointerCapture(event.pointerId);
        const move = e => { if (Math.abs(e.clientX - start.x) + Math.abs(e.clientY - start.y) > 3) moved = true; if (!moved) return; const x = Math.max(15, Math.min(1250, start.left + e.clientX - start.x)), y = Math.max(20, Math.min(850, start.top + e.clientY - start.y)); element.style.left = x + 'px'; element.style.top = y + 'px'; node.position = { x, y }; lines(); };
        const end = () => { handle.removeEventListener('pointermove', move); handle.removeEventListener('pointerup', end); handle.removeEventListener('pointercancel', end); if (moved) markDirty(); };
        handle.addEventListener('pointermove', move); handle.addEventListener('pointerup', end); handle.addEventListener('pointercancel', end);
    });
    $('[data-schedule-form]').addEventListener('submit', event => {
        event.preventDefault();
        perform(async () => {
            const id = workflow.id;
            schedule = await send('/' + id + '/schedule', scheduleBody(), 'PUT');
            $('[data-schedule-dialog]').close(); await refreshSchedule();
            notice(schedule.enabled ? '定时执行已启用，将执行当前已发布版本。' : '定时执行已暂停，已有实例继续运行。');
        });
    });
    $('[data-create-form]').addEventListener('submit', event => {
        event.preventDefault(); const form = event.currentTarget;
        perform(async () => { if (dirty && !confirm('新建将离开当前未保存的草稿，是否继续？')) return; const result = await send('', { name: form.elements.name.value }); $('[data-create-dialog]').close(); form.reset(); await loadList(); await load(result.id); });
    });
    $('[data-credential-form]').addEventListener('submit', event => {
        event.preventDefault(); const form = event.currentTarget, data = new FormData(form), kind = data.get('kind'), secret = data.get('secret');
        perform(async () => { const value = kind === 'bearer' ? { token: secret } : kind === 'basic' ? { username: data.get('username'), password: secret } : { header: data.get('username'), secret }; await send('/credentials', { name: data.get('name'), kind, value }); form.reset(); $('[data-credential-user]').hidden = true; credentials = await api('/credentials'); credentialList(); inspector(); notice('凭据已保存。'); });
    });
    window.addEventListener('beforeunload', event => { if (dirty) { event.preventDefault(); event.returnValue = ''; } });
    return {
        async activate(url) {
            active = true;
            if (!initialized) {
                initialized = true;
                await perform(async () => {
                    [capabilities, credentials] = await Promise.all([api('/capabilities'), api('/credentials')]); await loadList();
                    const id = new URL(url).searchParams.get('id') || workflows[0]?.id;
                    if (id) await load(id); else notice('从新建工作流开始，或创建后载入双语言示例。');
                    if (capabilities.sandbox === 'unavailable') notice('脚本运行环境尚未安装；HTTP 和条件节点可以正常使用。', true);
                });
            } else {
                const requested = new URL(url).searchParams.get('id');
                if (requested && requested !== workflow?.id) {
                    if (dirty && !confirm('打开其他工作流会丢弃当前未保存的草稿，是否继续？')) { workspace.replace('/workflows.html?id=' + workflow.id); return; }
                    await perform(() => load(requested));
                } else { schedulePoll(); if (workflow) refreshSchedule().catch(e => notice(e.message, true)); }
            }
        },
        deactivate() { active = false; clearTimeout(timer); },
    };
}
