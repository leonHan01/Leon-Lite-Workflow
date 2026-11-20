import { renderUserMarkdown } from './user-markdown.js';
const base = '/api/agent-admin/users';
if (!document.querySelector('link[data-users-styles]')) {
    const styles = document.createElement('link'); styles.rel = 'stylesheet'; styles.href = '/workspace/users.css'; styles.dataset.usersStyles = ''; document.head.append(styles);
}
const el = (tag, text, cls) => { const node = document.createElement(tag); if (text != null) node.textContent = text; if (cls) node.className = cls; return node; };
async function api(path, options = {}) {
    const response = await fetch(path, { cache: 'no-store', ...options });
    if (!response.ok) { let message = '操作失败，请重试。'; try { const body = await response.json(); message = body.message || body.detail || message; } catch {} throw new Error(message); }
    return response.json();
}
const post = body => ({ method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
export function mount(root) {
    const $ = name => root.querySelector('[data-' + name + ']');
    let user = null, room = null, messages = [], older = false, version = 0, loaded = false;
    let backgroundVersion = null, backgroundSaved = '';
    let users = [], analysisBusy = false;
    const dialog = $('conversation-dialog');
    const status = text => { (dialog.open ? $('dialog-status') : $('status')).textContent = text; };
    $('dialog-close').onclick = () => dialog.close();
    dialog.addEventListener('close', () => { $('dialog-status').textContent = ''; });
    const userPath = () => base + '/' + encodeURIComponent(user.id);
    function empty(container, title, detail) {
        const state = el('div', null, 'users-empty users-empty-compact');
        const icon = el('span', '↗', 'users-empty-icon'); icon.setAttribute('aria-hidden', 'true');
        state.append(icon, el('strong', title), el('span', detail)); container.replaceChildren(state);
    }
    function item(container, label, detail, active, click, kind = 'user') {
        const button = el('button', null, 'users-item' + (active ? ' is-selected' : '')); button.type = 'button'; button.setAttribute('aria-pressed', String(active));
        const avatar = el('span', kind === 'user' ? Array.from(label.trim())[0]?.toUpperCase() || '?' : kind === 'group' ? '群' : '✦', 'users-avatar users-avatar-' + kind);
        avatar.setAttribute('aria-hidden', 'true');
        const text = el('span', null, 'users-item-text'); text.append(el('strong', label), el('small', detail));
        button.append(avatar, text, el('span', '›', 'users-item-arrow')); button.onclick = click; container.append(button); return button;
    }
    function renderUsers() {
        const query = $('user-search').value.trim().toLocaleLowerCase();
        const visible = users.filter(row => row.username.toLocaleLowerCase().includes(query));
        $('user-count').textContent = users.length; $('users').replaceChildren();
        for (const row of visible) item($('users'), row.username, '加入于 ' + new Date(row.createdAt).toLocaleDateString(), row.id === user?.id, () => selectUser(row));
        if (!visible.length) empty($('users'), query ? '没有找到用户' : '暂无用户', query ? '试试其他关键词' : '用户注册后会显示在这里');
    }
    $('user-search').addEventListener('input', renderUsers);
    async function loadUsers() {
        try {
            users = await api(base); renderUsers();
            loaded = true;
        } catch (error) { status(error.message); }
    }
    async function selectUser(row) {
        if (user && backgroundVersion !== null && $('background').value !== backgroundSaved && !confirm('背景信息尚未保存，切换用户将放弃修改。确定切换？')) return;
        user = row; room = null; messages = []; version++; $('user-title').textContent = row.username + ' 的会话'; $('room-count').textContent = '0';
        $('analyze').disabled = analysisBusy;
        $('older').hidden = true;
        empty($('rooms'), '正在加载会话', '请稍候');
        backgroundVersion = null; backgroundSaved = ''; $('background').value = ''; $('background').disabled = true; $('background-save').disabled = true; $('background-reload').disabled = true;
        updateBackgroundCount();
        $('analysis-result').textContent = ''; $('analysis-status').textContent = ''; status('');
        renderUsers(); loadBackground(); await loadRooms();
    }
    function updateBackgroundCount() { $('background-count').textContent = $('background').value.length + ' / 1000'; }
    $('background').addEventListener('input', updateBackgroundCount);
    async function loadBackground() {
        if (!user) return; const selected = user, path = userPath();
        $('background').disabled = true; $('background-save').disabled = true; $('background-reload').disabled = true; $('background-status').textContent = '正在加载…';
        try {
            const data = await api(path + '/background'); if (selected !== user) return;
            backgroundVersion = data.version; backgroundSaved = data.background; $('background').value = data.background;
            updateBackgroundCount();
            $('background').disabled = false; $('background-save').disabled = false; $('background-reload').disabled = false; $('background-status').textContent = '';
        } catch (error) { if (selected === user) { $('background-status').textContent = error.message; $('background-reload').disabled = false; } }
    }
    $('background-reload').onclick = () => {
        if (backgroundVersion !== null && $('background').value !== backgroundSaved && !confirm('重新加载将放弃尚未保存的修改。确定继续？')) return;
        loadBackground();
    };
    $('background-save').onclick = async () => {
        if (!user || backgroundVersion === null) return;
        const selected = user, path = userPath(), value = $('background').value;
        $('background').disabled = true; $('background-save').disabled = true; $('background-reload').disabled = true; $('background-status').textContent = '正在保存…';
        try {
            const data = await api(path + '/background', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ background: value, version: backgroundVersion }) });
            if (selected !== user) return;
            backgroundVersion = data.version; backgroundSaved = data.background; $('background-status').textContent = '已保存；后续对话会使用这份背景。';
        } catch (error) { if (selected === user) $('background-status').textContent = error.message; }
        finally { if (selected === user) { $('background').disabled = false; $('background-save').disabled = false; $('background-reload').disabled = false; } }
    };
    async function loadRooms() {
        const selected = user, token = version;
        try {
            const rows = await api(userPath() + '/conversations'); if (token !== version || selected !== user) return;
            $('rooms').replaceChildren(); $('room-count').textContent = rows.length;
            for (const row of rows) {
                const button = item($('rooms'), row.name, (row.kind === 'group' ? '群聊' : '私聊') + ' · ' + row.messageCount + ' 条消息' + (row.lastMessageAt ? ' · ' + new Date(row.lastMessageAt).toLocaleDateString() : ''), row.id === room?.id, () => selectRoom(row), row.kind === 'group' ? 'group' : 'direct');
                button.dataset.roomId = row.id;
            }
            if (!rows.length) empty($('rooms'), '暂无会话', '该用户还没有对话记录');
        } catch (error) { status(error.message); }
    }
    async function selectRoom(row) {
        room = row; messages = []; version++; $('room-title').textContent = row.name; $('room-caption').textContent = (row.kind === 'group' ? '群聊' : '私聊') + ' · ' + row.messageCount + ' 条消息';
        for (const button of $('rooms').querySelectorAll('[data-room-id]')) { const active = button.dataset.roomId === row.id; button.classList.toggle('is-selected', active); button.setAttribute('aria-pressed', String(active)); }
        empty($('messages'), '正在加载对话', '请稍候'); $('older').hidden = true; $('dialog-status').textContent = '';
        if (!dialog.open) dialog.showModal();
        await loadMessages(false);
    }
    function renderMessages() {
        const container = $('messages'); container.replaceChildren();
        if (!messages.length) empty(container, '暂无消息', '这个会话还没有对话记录');
        for (const message of messages) {
            const assistant = message.role === 'assistant';
            const card = el('article', null, 'users-message' + (assistant ? ' is-assistant' : ''));
            const avatar = el('span', assistant ? '✦' : Array.from(message.sender?.trim() || '?')[0].toUpperCase(), 'users-message-avatar'); avatar.setAttribute('aria-hidden', 'true');
            const content = el('div', null, 'users-message-content');
            const meta = el('div', null, 'users-message-meta'); meta.append(el('strong', message.sender + (assistant ? ' · Agent' : '')), el('time', new Date(message.createdAt).toLocaleString()));
            const bubble = el('div', null, 'users-message-bubble');
            if (message.text) { const text = el('div', null, 'users-message-text users-markdown'); renderUserMarkdown(text, message.text); bubble.append(text); }
            for (const image of message.images || []) {
                const img = document.createElement('img'); img.loading = 'lazy'; img.alt = '对话图片';
                img.src = userPath() + '/images/' + encodeURIComponent(image.id) + '?' + new URLSearchParams({ conversationId: room.id }); bubble.append(img);
            }
            content.append(meta, bubble); card.append(avatar, content); container.append(card);
        }
        $('older').hidden = !older;
    }
    async function loadMessages(previous) {
        const token = version, selected = room, query = new URLSearchParams({ conversationId: room.id });
        if (previous && messages.length) query.set('before', messages[0].id);
        try {
            const page = await api(userPath() + '/messages?' + query); if (token !== version || selected !== room || !dialog.open) return;
            const area = $('messages'), height = area.scrollHeight, top = area.scrollTop;
            messages = previous ? [...page.messages, ...messages] : page.messages; older = page.hasMore; renderMessages();
            area.scrollTop = previous ? top + area.scrollHeight - height : area.scrollHeight;
        } catch (error) { if (token === version && dialog.open) status(error.message); }
    }
    $('older').onclick = () => loadMessages(true);
    $('refresh').onclick = () => { loadUsers(); if (user) loadRooms(); if (room && dialog.open) loadMessages(false); };
    $('delete').onclick = async () => {
        if (!room || !user) return; const token = version, selected = room, path = userPath();
        try {
            const preview = await api(path + '/delete-preview?' + new URLSearchParams({ conversationId: selected.id }));
            if (token !== version) return;
            if (preview.hasActiveWork) { status('该会话正在处理回复或上下文，请完成后再删除。'); return; }
            const home = selected.id === 'shared' || selected.id.startsWith('home:');
            if (!confirm((home ? '个人首页不能移除，将永久清空其中' : '将永久删除会话“' + selected.name + '”及其中') + preview.messageCount + ' 条消息、图片和上下文。群聊会影响所有成员，且无法撤销。确定继续？')) return;
            $('delete').disabled = true;
            const result = await api(path + '/delete', post({ conversationId: selected.id, requestId: crypto.randomUUID(), expectedLatestMessageId: preview.latestMessageId, expectedHistoryRevision: preview.historyRevision, confirm: true }));
            if (token !== version) return;
            dialog.close(); status(result.homeRetained ? '个人首页记录已清空。' : '会话已删除。'); room = null; version++; $('older').hidden = true; loadRooms();
        } catch (error) { status(error.message); } finally { $('delete').disabled = false; }
    };
    $('analyze').onclick = async () => {
        if (!user) { $('analysis-status').textContent = '请先选择用户。'; return; }
        if (!$('agent').value) { $('analysis-status').textContent = '请先配置并启用 Agent。'; return; }
        const selected = user; analysisBusy = true; $('analyze').disabled = true; $('analysis-status').textContent = '正在分析，可能需要一些时间…'; $('analysis-result').textContent = '';
        try {
            const result = await api(userPath() + '/analysis', post({ agentId: $('agent').value }));
            if (selected !== user) return;
            $('analysis-status').textContent = '分析了 ' + result.messageCount + ' 条发言' + (result.truncated ? '（仅包含部分最近消息）' : '') + '。'; $('analysis-result').textContent = result.analysis;
        } catch (error) { if (selected === user) $('analysis-status').textContent = error.message; }
        finally { analysisBusy = false; $('analyze').disabled = !user; }
    };
    async function loadAgents() {
        try { const rows = await api('/api/agents'); $('agent').replaceChildren(); for (const agent of rows.filter(a => a.enabled)) { const option = el('option', agent.name + ' · ' + agent.model); option.value = agent.id; $('agent').append(option); } } catch (error) { status(error.message); }
    }
    return { activate() { if (!loaded) { loadUsers(); loadAgents(); } }, deactivate() { version++; if (dialog.open) dialog.close(); } };
}
