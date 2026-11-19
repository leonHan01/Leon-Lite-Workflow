export function mountAppMessenger(pane, {api, avatarImage, loadDirectory}) {
    let historyRevision = null;
    const $ = id => pane.querySelector('#' + id), dialog = $('appMessenger');
    let directory = {agents: [], conversations: []}, room = '', rows = [], cursor = 0, epoch = 0, timer = null, polling = false, sending = false, outgoing = null;
    function element(tag, text, className) { const value = document.createElement(tag); if (text != null) value.textContent = text; if (className) value.className = className; return value; }
    function status(text, error = false) { $('appMessageStatus').textContent = text; $('appMessageStatus').className = error ? 'error' : 'subtle'; }
    function controls() {
        const length = $('appMessageText').value.length;
        $('appMessageLength').textContent = `${length} / 8000`;
        $('sendAppMessage').disabled = sending || !room || !length || length > 8000 || !$('appMessageText').value.trim();
        $('sendAppMessage').textContent = sending ? '正在发送…' : '发送到 App ↑';
        $('appMessageAgent').disabled = sending; $('appMessageTarget').disabled = sending;
    }
    function option(value, text) { const item = element('option', text); item.value = value; return item; }
    function targets() {
        const agentId = $('appMessageAgent').value, previous = $('appMessageTarget').value;
        $('appMessageTarget').replaceChildren(option('', '与此 Agent 的私聊'));
        for (const c of directory.conversations.filter(c => c.kind === 'group' && (c.id === 'shared' || c.agentIds.includes(agentId)))) $('appMessageTarget').append(option(c.id, c.name));
        if ([...$('appMessageTarget').options].some(o => o.value === previous)) $('appMessageTarget').value = previous;
        const agent = directory.agents.find(a => a.id === agentId); $('appMessageIdentity').replaceChildren();
        if (agent) $('appMessageIdentity').append(avatarImage(agent.avatarId, '', agent.name), element('strong', agent.name), element('small', `@${agent.handle} · 以此身份发送`));
        else $('appMessageIdentity').append(element('span', '请先创建并启用一个 Agent。'));
    }
    function render() {
        const history = $('appMessageHistory'), atBottom = history.scrollHeight - history.scrollTop - history.clientHeight < 70, top = history.scrollTop;
        history.replaceChildren();
        if (!rows.length) history.append(element('p', '还没有消息，从这里给 App 发一条吧。', 'subtle'));
        for (const message of rows) {
            const bubble = element('div', null, 'messenger-bubble' + (message.agentId === $('appMessageAgent').value ? ' mine' : ''));
            bubble.append(element('small', message.sender + (message.role === 'assistant' ? ' · Agent' : '')), element('p', message.text), element('small', new Date(message.createdAt).toLocaleString())); history.append(bubble);
        }
        history.scrollTop = atBottom ? history.scrollHeight : top;
    }
    function merge(messages) { const all = new Map(rows.map(m => [m.id, m])); messages.forEach(m => all.set(m.id, m)); rows = [...all.values()].sort((a, b) => a.id - b.id).slice(-100); render(); }
    async function poll(initial = false) {
        if (!dialog.open || document.hidden || !room || polling) return;
        const selectedEpoch = epoch; polling = true; clearTimeout(timer);
        try {
            const query = new URLSearchParams({conversationId: room, limit: '100'}); if (!initial) query.set('after', String(cursor));
            const page = await api('/api/device-messages?' + query);
            if (selectedEpoch !== epoch || !dialog.open) return;
            if (page.historyRevision != null) {
                if (historyRevision !== page.historyRevision && (historyRevision !== null || page.historyRevision > 0)) { rows = []; render(); }
                historyRevision = page.historyRevision;
            }
            if (page.messages.length) { cursor = page.messages.at(-1).id; merge(page.messages); }
            $('appMessageHistoryStatus').textContent = '与 App 同步 · 显示最近 100 条消息';
            timer = setTimeout(() => poll(), !initial && page.hasMore ? 0 : 2000);
        } catch (error) {
            if (selectedEpoch === epoch && dialog.open) { $('appMessageHistoryStatus').textContent = '历史消息同步失败，正在重试…'; timer = setTimeout(() => poll(initial), 5000); }
        } finally { if (selectedEpoch === epoch) polling = false; }
    }
    async function selectRoom() {
        const selectedEpoch = ++epoch; clearTimeout(timer); polling = false; room = ''; rows = []; cursor = 0; historyRevision = null; render(); controls();
        const agentId = $('appMessageAgent').value; if (!agentId) return;
        $('appMessageHistoryStatus').textContent = '正在打开会话…';
        try {
            const target = $('appMessageTarget').value;
            const conversation = target ? directory.conversations.find(c => c.id === target) : await api('/api/conversations/direct/' + encodeURIComponent(agentId), {method: 'POST', body: '{}'});
            if (selectedEpoch !== epoch || !dialog.open) return;
            if (!conversation) throw new Error('目标会话不存在，请重新打开窗口刷新。');
            room = conversation.id; controls(); await poll(true);
        } catch (error) { if (selectedEpoch === epoch && dialog.open) status(error.message, true); }
    }
    async function open(preferredAgentId) {
        if (dialog.open) return;
        dialog.showModal(); if (sending) return;
        const selectedEpoch = ++epoch; room = ''; rows = []; cursor = 0; render(); controls(); status('正在加载 Agent 和会话…');
        try {
            const loaded = await loadDirectory(); if (selectedEpoch !== epoch || !dialog.open) return;
            directory = loaded; const previous = preferredAgentId || $('appMessageAgent').value;
            const enabled = directory.agents.filter(a => a.enabled);
            $('appMessageAgent').replaceChildren(...enabled.map(a => option(a.id, `${a.name} (@${a.handle})`)));
            if (enabled.some(a => a.id === previous)) $('appMessageAgent').value = previous;
            targets(); status(enabled.length ? '正文直接发送给 App，不调用模型。' : '请先在 Agent 配置中创建并启用 Agent。', !enabled.length);
            await selectRoom();
        } catch (error) { if (selectedEpoch === epoch && dialog.open) status(error.message, true); }
    }
    $('appMessageAgent').onchange = () => { targets(); status('正文直接发送给 App，不调用模型。'); selectRoom(); };
    $('appMessageTarget').onchange = () => { status('正文直接发送给 App，不调用模型。'); selectRoom(); };
    $('appMessageText').oninput = controls;
    $('closeAppMessenger').onclick = () => dialog.close();
    dialog.addEventListener('close', () => { epoch++; clearTimeout(timer); polling = false; });
    $('appMessageForm').onsubmit = async event => {
        event.preventDefault(); if (sending || !room) return;
        const text = $('appMessageText').value.trim(); if (!text || text.length > 8000) return;
        const target = {agentId: $('appMessageAgent').value, conversationId: room, text};
        if (!outgoing || Object.keys(target).some(key => outgoing[key] !== target[key])) outgoing = {...target, clientMessageId: crypto.randomUUID()};
        sending = true; $('appMessageText').disabled = true; controls(); status('正在发送…');
        try {
            const message = await api('/api/agent-admin/messages', {method: 'POST', body: JSON.stringify(outgoing)});
            outgoing = null;
            if (room === target.conversationId && $('appMessageAgent').value === target.agentId) {
                if ($('appMessageText').value.trim() === text) $('appMessageText').value = '';
                if (dialog.open) { merge([message]); $('appMessageHistory').scrollTop = $('appMessageHistory').scrollHeight; }
                status('已保存到会话，App 在线时会自动同步。');
            }
            // Only GET responses advance the receive cursor, so intervening App replies are not skipped.
        } catch (error) { if (room === target.conversationId && $('appMessageAgent').value === target.agentId) status(error.message + '；可再次点击发送重试，同一消息不会重复保存。', true); }
        finally { sending = false; $('appMessageText').disabled = false; controls(); if (dialog.open) poll(); }
    };
    return {open, visibilityChanged() { if (dialog.open) poll(); }, deactivate() { if (dialog.open) dialog.close(); clearTimeout(timer); }};
}
