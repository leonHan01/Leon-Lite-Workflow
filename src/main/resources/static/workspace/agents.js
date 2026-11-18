import './mentions.js';
export function mount(pane) {
    if (!document.getElementById('agents-css')) { const css = document.createElement('link'); css.id = 'agents-css'; css.rel = 'stylesheet'; css.href = '/workspace/agents.css'; document.head.append(css); }
    let viewActive = false, presenceTimer = null, presenceEpoch = 0;
const $ = id => pane.querySelector('#' + CSS.escape(id));
let agents = [], conversations = [], editing = null, current = 'shared', rows = [], more = false, generation = 0, timer, loading = false, activeTab = 'config';
let avatarCatalog = [], selectedAvatarId = 'alfred', uploadingAvatar = false, models = [];
const deviceId = (() => { let value = localStorage.getItem('leon-agent-web-device'); if (!value) { value = crypto.randomUUID(); localStorage.setItem('leon-agent-web-device', value); } return value; })();
function node(tag, text, className) { const element = document.createElement(tag); if (text != null) element.textContent = text; if (className) element.className = className; return element; }
function notice(message, error = false) { $('notice').textContent = message; $('notice').hidden = !message; $('notice').className = error ? 'error' : ''; }
async function api(path, options = {}) {
    const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', ...options, headers: { 'Content-Type': 'application/json', ...options.headers } });
    if (!response.ok) { let body = {}; try { body = await response.json(); } catch {} throw new Error(body.message || body.detail || `请求未完成（HTTP ${response.status}）`); }
    return response.json();
}
function button(text, action, className) { const b = node('button', text, className); b.type = 'button'; b.addEventListener('click', action); return b; }
function avatarImage(id, className = '', name = '') {
    const image = document.createElement('img'); image.className = 'avatar ' + className;
    image.src = '/api/agent-avatars/' + encodeURIComponent(id || 'alfred'); image.alt = name ? name + '头像' : ''; image.loading = 'lazy';
    return image;
}
function selectAvatar(id) {
    selectedAvatarId = id; const entry = avatarCatalog.find(a => a.id === id);
    $('avatarPreview').src = '/api/agent-avatars/' + encodeURIComponent(id);
    $('avatarName').textContent = entry?.name || '自定义头像';
    renderAvatarPicker();
}
function renderAvatarPicker() {
    $('presetAvatars').replaceChildren(); $('uploadedAvatars').replaceChildren();
    for (const entry of avatarCatalog) {
        const option = button('', () => { selectAvatar(entry.id); $('avatarDialog').close(); }, 'avatar-option');
        option.setAttribute('aria-label', entry.name); option.setAttribute('aria-pressed', String(entry.id === selectedAvatarId));
        option.append(avatarImage(entry.id), node('small', entry.name));
        $(entry.builtin ? 'presetAvatars' : 'uploadedAvatars').append(option);
    }
    $('uploadedAvatarTitle').hidden = !avatarCatalog.some(a => !a.builtin);
}
$('chooseAvatar').onclick = () => { renderAvatarPicker(); $('avatarDialog').showModal(); };
$('closeAvatars').onclick = () => $('avatarDialog').close();
$('uploadAvatar').onclick = () => $('avatarFile').click();
$('avatarFile').onchange = async () => {
    const file = $('avatarFile').files[0]; if (!file) return;
    if (file.size > 5 * 1024 * 1024 || !['image/png', 'image/jpeg'].includes(file.type)) { notice('请选择不超过 5 MB 的 PNG 或 JPG 图片。', true); $('avatarFile').value = ''; return; }
    const target = editing?.id || null;
    uploadingAvatar = true; $('saveAgent').disabled = true; $('uploadAvatar').disabled = true; $('newAgent').disabled = true;
    const data = new FormData(); data.append('file', file);
    try {
        const response = await fetch('/api/agent-admin/avatars', { method: 'POST', credentials: 'same-origin', body: data });
        const body = await response.json(); if (!response.ok) throw new Error(body.message || '头像上传未完成');
        if (!avatarCatalog.some(a => a.id === body.id)) avatarCatalog.push(body);
        if ((editing?.id || null) === target) selectAvatar(body.id);
        notice('头像已上传，点击「保存 Agent」后生效。');
    } catch (error) { notice(error.message, true); }
    finally { uploadingAvatar = false; $('avatarFile').value = ''; $('saveAgent').disabled = false; $('uploadAvatar').disabled = false; $('newAgent').disabled = false; }
};
async function refreshPresence() {
    clearTimeout(presenceTimer); if (!viewActive || document.hidden) return;
    const epoch = ++presenceEpoch;
    try {
        const state = await api('/api/agent-admin/devices'); if (epoch !== presenceEpoch || !viewActive) return;
        $('onlineCount').textContent = String(state.onlineCount); $('onlineDevices').replaceChildren();
        if (!state.devices.length) $('onlineDevices').append(node('p', '暂无在线设备。打开已连接的 App 后自动显示。', 'subtle'));
        for (const device of state.devices) {
            const row = node('div', null, 'presence-device');
            row.append(node('span', '', 'presence-dot'), node('strong', device.name), node('small', (device.platform === 'macos' ? 'macOS' : 'Android') + ' · ' + Math.max(0, Math.floor((Date.now() - device.lastSeenAt) / 1000)) + ' 秒前活跃'));
            $('onlineDevices').append(row);
        }
    } catch (error) { if (epoch === presenceEpoch && viewActive) { $('onlineCount').textContent = '—'; $('onlineDevices').replaceChildren(node('p', '设备状态暂时不可用，下次自动重试。', 'subtle')); } }
    finally { if (epoch === presenceEpoch && viewActive) presenceTimer = setTimeout(refreshPresence, 5000); }
}
function modelOptions(value = '') {
    $('modelId').replaceChildren();
    const legacy = editing && !editing.modelId;
    const placeholder = node('option', legacy ? `沿用原配置：${editing.provider} · ${editing.model}` : '请选择已保存的模型'); placeholder.value = '';
    $('modelId').append(placeholder);
    for (const model of models) {
        const option = node('option', `${model.name} · ${model.provider} / ${model.model}${model.enabled ? '' : '（已停用）'}`);
        option.value = model.id; option.disabled = !model.enabled; $('modelId').append(option);
    }
    if (value && !models.some(m => m.id === value)) { const missing = node('option', '原模型已不存在，请重新选择'); missing.value = value; missing.disabled = true; $('modelId').append(missing); }
    $('modelId').value = value; $('modelId').required = !legacy; modelHint();
}
function modelHint() {
    const profile = models.find(m => m.id === $('modelId').value);
    $('modelHint').textContent = profile ? `${profile.provider === 'openai' ? 'OpenAI' : 'DeepSeek'} · ${profile.model}。复用模型凭据；修改模型配置会同步影响此 Agent。${profile.enabled ? '' : '所选模型已停用，请重新选择。'}`
        : editing && !editing.modelId ? '现有独立配置仍可使用。选择已保存的模型后，将改用统一模型与凭据。' : models.some(m => m.enabled) ? '请选择一个可用模型，无需另填 API Key。' : '还没有可用模型，请先在模型配置中添加并启用。';
}
$('modelId').onchange = modelHint;
$('reloadModels').onclick = async () => { try { await loadAgents(); notice('模型列表已刷新。'); } catch (error) { notice(error.message, true); } };
function resetForm(agent = null) {
    if (uploadingAvatar) return;
    editing = agent; $('agentForm').reset();
    selectAvatar(agent?.avatarId || 'alfred');
    for (const key of ['name', 'handle', 'systemPrompt']) $(key).value = agent?.[key] || '';
    modelOptions(agent?.modelId || ''); $('enabled').checked = agent?.enabled ?? true;
    $('formTitle').textContent = agent ? `编辑 ${agent.name}` : '新建 Agent';
    renderAgents();
}
function renderAgents() {
    $('agentList').replaceChildren(); $('directList').replaceChildren();
    if (!agents.length) $('agentList').append(node('p', '还没有 Agent，创建你的第一位助手。', 'subtle'));
    for (const a of agents) {
        const card = button('', () => resetForm(a), 'agent-card' + (editing?.id === a.id ? ' active' : ''));
        const identity = node('div', null, 'avatar-row'), identityText = node('div', null, 'identity-text');
        identityText.append(node('strong', a.name), node('small', `@${a.handle} · ${a.provider}`), node('span', a.enabled ? '已启用' : '已停用', 'badge' + (a.enabled ? '' : ' off')));
        identity.append(avatarImage(a.avatarId, '', a.name), identityText); card.append(identity);
        $('agentList').append(card);
        if (a.enabled) { const directButton = button(`${a.name} ↗`, async () => { try { const c = await api(`/api/conversations/direct/${a.id}`, { method: 'POST', body: '{}' }); await refreshConversations(); selectConversation(c.id); } catch (e) { notice(e.message, true); } }, 'conversation avatar-row'); directButton.prepend(avatarImage(a.avatarId)); $('directList').append(directButton); }
    }
    renderMentions(); updateMentionCandidates();
}
async function loadAgents() {
    const data = await api('/api/agent-admin'); agents = data.agents; avatarCatalog = data.avatars || []; models = data.models || []; modelOptions($('modelId').value || editing?.modelId || ''); selectAvatar(selectedAvatarId);
    $('saveAgent').disabled = !data.keyStorageEnabled;
    if (!data.keyStorageEnabled) notice('请先配置服务器 LEON_WORKFLOW_CREDENTIAL_KEY，启用 Agent 的加密凭据存储。', true);
    renderAgents();
}
$('newAgent').onclick = () => { notice(''); resetForm(); $('name').focus(); };
$('agentForm').onsubmit = async event => {
    event.preventDefault(); $('saveAgent').disabled = true; notice('');
    const body = Object.fromEntries(['name', 'handle', 'systemPrompt'].map(k => [k, $(k).value]));
    if ($('modelId').value) body.modelId = $('modelId').value;
    if (uploadingAvatar) return; body.avatarId = selectedAvatarId;
    body.enabled = $('enabled').checked; body.version = editing?.version;
    try {
        const saved = await api('/api/agent-admin' + (editing ? '/' + editing.id : ''), { method: editing ? 'PUT' : 'POST', body: JSON.stringify(body) });
        await loadAgents(); resetForm(saved); notice('Agent 已保存。客户端同步后即可开始私聊或在群聊中 @它。');
    } catch (e) { notice(e.message, true); } finally { $('saveAgent').disabled = false; }
};
function tab(which) {
    activeTab = which; $('configPanel').hidden = which !== 'config'; $('chatPanel').hidden = which !== 'chat';
    $('configTab').classList.toggle('active', which === 'config'); $('chatTab').classList.toggle('active', which === 'chat');
    clearTimeout(timer); if (which === 'chat') refreshConversations().then(() => selectConversation(current)).catch(e => notice(e.message, true));
}
$('configTab').onclick = () => tab('config'); $('chatTab').onclick = () => tab('chat');
async function refreshConversations() { conversations = await api('/api/conversations'); renderConversations(); }
function renderConversations() {
    $('conversationList').replaceChildren();
    for (const c of conversations) $('conversationList').append(button((c.kind === 'direct' ? '◉ ' : '# ') + c.name, () => selectConversation(c.id), 'conversation' + (current === c.id ? ' active' : '')));
}
let mentionCandidates = [], mentionToken = null, mentionIndex = 0, mentionKey = '', dismissedMentionKey = '', mentionComposing = false;
function hideMentionCandidates(dismiss = false) {
    if (dismiss) dismissedMentionKey = mentionKey;
    $('mentionSuggestions').hidden = true; $('composer').removeAttribute('aria-activedescendant');
    mentionCandidates = []; mentionToken = null;
}
function eligibleMentionAgents() {
    const room = conversations.find(c => c.id === current);
    return room?.kind === 'group' ? agents.filter(a => a.enabled && room.agentIds.includes(a.id)) : [];
}
function updateMentionCandidates() {
    const input = $('composer');
    const token = !mentionComposing && document.activeElement === input && activeTab === 'chat' ? LeonMentions.token(input.value, input.selectionStart, input.selectionEnd) : null;
    const key = token ? `${current}:${token.start}:${token.end}:${input.selectionStart}:${token.query}` : '';
    const room = conversations.find(c => c.id === current);
    if (!token || room?.kind !== 'group' || key === dismissedMentionKey) { hideMentionCandidates(); if (!key) { dismissedMentionKey = ''; mentionKey = ''; mentionIndex = 0; } return; }
    if (key !== mentionKey) mentionIndex = 0;
    mentionKey = key; mentionToken = token;
    mentionCandidates = eligibleMentionAgents().filter(a => LeonMentions.matches(a, token.query)).slice(0, 8);
    mentionIndex = Math.min(mentionIndex, Math.max(0, mentionCandidates.length - 1));
    $('mentionOptions').replaceChildren();
    if (!mentionCandidates.length) $('mentionOptions').append(node('p', '没有匹配的 Agent', 'mention-empty'));
    mentionCandidates.forEach((agent, index) => {
        const option = button('', () => chooseMentionCandidate(index), 'mention-candidate');
        option.id = 'mention-candidate-' + index; option.setAttribute('role', 'option'); option.tabIndex = -1;
        option.setAttribute('aria-selected', String(index === mentionIndex));
        option.addEventListener('mousedown', event => event.preventDefault());
        option.addEventListener('mouseenter', () => { mentionIndex = index; highlightMentionCandidate(); });
        const label = node('div'); label.append(node('strong', agent.name), node('small', '@' + agent.handle));
        option.append(avatarImage(agent.avatarId), label); $('mentionOptions').append(option);
    });
    $('mentionSuggestions').hidden = false; highlightMentionCandidate();
}
function highlightMentionCandidate() {
    [...$('mentionOptions').children].forEach((option, index) => option.setAttribute('aria-selected', String(index === mentionIndex)));
    if (mentionCandidates.length) $('composer').setAttribute('aria-activedescendant', 'mention-candidate-' + mentionIndex);
    else $('composer').removeAttribute('aria-activedescendant');
}
function chooseMentionCandidate(index) {
    const agent = mentionCandidates[index], input = $('composer');
    const live = LeonMentions.token(input.value, input.selectionStart, input.selectionEnd);
    if (!agent || !live || !eligibleMentionAgents().some(a => a.id === agent.id)) return;
    const result = LeonMentions.replace(input.value, live, agent.handle);
    input.value = result.text; input.focus(); input.setSelectionRange(result.caret, result.caret);
    hideMentionCandidates(); dismissedMentionKey = ''; mentionKey = '';
}
$('composer').addEventListener('input', updateMentionCandidates);
$('composer').addEventListener('click', updateMentionCandidates);
$('composer').addEventListener('focus', updateMentionCandidates);
$('composer').addEventListener('blur', () => hideMentionCandidates());
$('composer').addEventListener('compositionstart', () => { mentionComposing = true; hideMentionCandidates(); });
$('composer').addEventListener('compositionend', () => { mentionComposing = false; requestAnimationFrame(updateMentionCandidates); });
document.addEventListener('selectionchange', () => { if (document.activeElement === $('composer')) updateMentionCandidates(); });
$('composer').addEventListener('keydown', event => {
    if (event.isComposing || mentionComposing || $('mentionSuggestions').hidden) return;
    if (event.key === 'Escape') { event.preventDefault(); hideMentionCandidates(true); return; }
    if (!mentionCandidates.length || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey) return;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
        event.preventDefault(); mentionIndex = (mentionIndex + (event.key === 'ArrowDown' ? 1 : -1) + mentionCandidates.length) % mentionCandidates.length; highlightMentionCandidate();
        $('mentionOptions').children[mentionIndex]?.scrollIntoView({ block: 'nearest' });
    } else if (event.key === 'Enter' || event.key === 'Tab') { event.preventDefault(); chooseMentionCandidate(mentionIndex); }
});
function renderMentions() {
    $('mentionBar').replaceChildren(); const c = conversations.find(c => c.id === current);
    if (!c || c.kind === 'direct') return;
    for (const a of agents.filter(a => a.enabled && c.agentIds.includes(a.id))) $('mentionBar').append(button(`@${a.handle} · ${a.name}`, () => { $('composer').value += ($('composer').value && !/\s$/.test($('composer').value) ? ' ' : '') + `@${a.handle} `; $('composer').focus(); }));
}
function selectConversation(id) {
    hideMentionCandidates(); dismissedMentionKey = ''; mentionKey = '';
    current = id; generation++; loading = false; rows = []; more = false; $('composer').value = ''; clearTimeout(timer);
    const c = conversations.find(c => c.id === id); $('chatTitle').textContent = c?.name || '会话';
    $('chatHint').textContent = c?.kind === 'direct' ? '直接发送消息，Agent 将使用此私聊的上下文回复。' : '只有被 @ 的 Agent 会回复；普通消息只在设备间同步。';
    renderConversations(); renderMentions(); renderMessages(); poll(true);
}
async function poll(initial = false) {
    if (!viewActive || loading || activeTab !== 'chat' || document.hidden) return;
    loading = true; const epoch = generation; clearTimeout(timer);
    try {
        const query = new URLSearchParams({ conversationId: current, limit: '100' });
        if (!initial) query.set('after', String(rows.at(-1)?.id || 0));
        const page = await api('/api/device-messages?' + query);
        if (epoch !== generation) return;
        if (initial) more = page.hasMore;
        if (page.messages.length) { merge(page.messages); renderMessages(); }
        renderJobs(page.jobs || []); $('olderMessages').hidden = !more; $('chatStatus').textContent = '已连接 · 自动同步';
        timer = setTimeout(() => poll(false), !initial && page.hasMore ? 0 : 2000);
    } catch (e) { if (epoch === generation) { $('chatStatus').textContent = e.message; timer = setTimeout(() => poll(initial), 5000); } }
    finally { if (epoch === generation) loading = false; }
}
function merge(incoming) { const map = new Map(rows.map(m => [m.id, m])); incoming.forEach(m => map.set(m.id, m)); rows = [...map.values()].sort((a,b) => a.id-b.id); }
function renderMessages() {
    const container = $('messages'); const atBottom = container.scrollHeight - container.scrollTop - container.clientHeight < 80;
    container.replaceChildren();
    if (!rows.length) container.append(node('p', '从一句话开始。', 'subtle'));
    for (const m of rows) {
        const item = node('div', null, 'message' + (m.deviceId === deviceId ? ' mine' : ''));
        if (m.role === 'assistant') item.append(avatarImage(agents.find(a => a.id === m.agentId)?.avatarId, 'message-avatar', m.sender));
        item.append(node('small', m.sender + (m.role === 'assistant' ? ' · Agent' : '')), node('div', m.text, 'body'), node('small', new Date(m.createdAt).toLocaleString())); container.append(item);
    }
    if (atBottom) container.scrollTop = container.scrollHeight;
}
function renderJobs(jobs) {
    $('jobs').replaceChildren();
    for (const job of jobs) {
        const item = node('div', `${job.agentName} · ${job.status === 'FAILED' ? job.error : job.status === 'RUNNING' ? '正在回复…' : '等待回复…'}`, 'job');
        if (job.status === 'FAILED') item.append(button('重试回复', async () => {
            try { await api(`/api/device-messages/${job.messageId}/agents/${job.agentId}/retry`, { method: 'POST', body: '{}' }); poll(); } catch (e) { notice(e.message, true); }
        }));
        $('jobs').append(item);
    }
}
let outgoing = null;
$('sendForm').onsubmit = async event => {
    event.preventDefault(); const text = $('composer').value.trim(); if (!text) return;
    $('sendMessage').disabled = true; const conversationId = current;
    if (!outgoing || outgoing.text !== text || outgoing.conversationId !== current) outgoing = { clientMessageId: crypto.randomUUID(), deviceId, sender: '网页', platform: 'macos', text, conversationId: current, mentionAgentIds: [] };
    try { await api('/api/device-messages', { method: 'POST', body: JSON.stringify(outgoing) }); if (current === conversationId) { $('composer').value = ''; hideMentionCandidates(); } outgoing = null; poll(); }
    catch (e) { notice(e.message + '；可再次点击发送，消息标识将复用。', true); }
    finally { $('sendMessage').disabled = false; }
};
$('olderMessages').onclick = async () => {
    if (!rows.length) return; const epoch = generation; $('olderMessages').disabled = true;
    try { const page = await api('/api/device-messages?' + new URLSearchParams({ conversationId: current, before: String(rows[0].id), limit: '100' })); if (epoch !== generation) return; const height = $('messages').scrollHeight, top = $('messages').scrollTop; merge(page.messages); more = page.hasMore; renderMessages(); $('messages').scrollTop = top + $('messages').scrollHeight - height; $('olderMessages').hidden = !more; }
    catch (e) { notice(e.message, true); } finally { $('olderMessages').disabled = false; }
};
$('refreshChat').onclick = () => poll();
$('newGroup').onclick = () => {
    $('groupMembers').replaceChildren(); $('groupName').value = '';
    for (const a of agents.filter(a => a.enabled)) { const label = node('label'); const box = document.createElement('input'); box.type = 'checkbox'; box.value = a.id; label.append(box, avatarImage(a.avatarId, 'avatar-member'), document.createTextNode(`${a.name} @${a.handle}`)); $('groupMembers').append(label); }
    $('groupDialog').showModal();
};
$('cancelGroup').onclick = () => $('groupDialog').close();
$('groupForm').onsubmit = async event => {
    event.preventDefault(); try { const agentIds = [...$('groupMembers').querySelectorAll('input:checked')].map(e => e.value); const c = await api('/api/conversations/groups', { method: 'POST', body: JSON.stringify({ name: $('groupName').value, agentIds }) }); $('groupDialog').close(); await refreshConversations(); selectConversation(c.id); } catch (e) { notice(e.message, true); }
};
document.addEventListener('visibilitychange', () => { clearTimeout(timer); if (viewActive && !document.hidden) { refreshPresence(); if (activeTab === 'chat') poll(); } else clearTimeout(presenceTimer); });
return {
    async activate() { viewActive = true; refreshPresence(); try { await loadAgents(); if (activeTab === 'chat') { await refreshConversations(); poll(); } } catch (error) { notice(error.message, true); } },
    deactivate() { viewActive = false; presenceEpoch++; clearTimeout(presenceTimer); generation++; loading = false; clearTimeout(timer); hideMentionCandidates(); for (const id of ['avatarDialog', 'groupDialog']) { if ($(id).open) $(id).close(); } }
};
}
