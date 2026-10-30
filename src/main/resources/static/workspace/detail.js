import { bindActions } from './events.js';
export function mount(root, route) {
const byId = id => root.querySelector('#' + CSS.escape(id));
let currentUrl = new URL(route.url);

    const meta = byId('meta');
    const jsonLayout = byId('jsonLayout');
    const rawPanel = byId('rawPanel');
    const rawContent = byId('rawContent');
    const searchKeyword = byId('searchKeyword');
    const searchResults = byId('searchResults');
    const searchResultsList = byId('searchResultsList');
    const searchCount = byId('searchCount');
    const currentMatch = byId('currentMatch');
    const prevMatchButton = byId('prevMatch');
    const nextMatchButton = byId('nextMatch');
    const embeddedJsonModal = byId('embeddedJsonModal');
    const embeddedJsonTitle = byId('embeddedJsonTitle');
    const embeddedJsonContent = byId('embeddedJsonContent');
    let currentRecord = null;
    let recordRequestSequence = 0;
    let activeEmbeddedJSON = '';
    let searchMatches = [];
    let currentMatchIndex = 0;
    let searchTimer = null;
    let clearLineHighlightTimer = null;
    const panelData = new Map();


    root.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            closeEmbeddedJSONModal();
        }
    });

    jsonLayout.addEventListener('click', event => {
        const embeddedString = event.target.closest('.json-embedded-string');
        if (embeddedString) {
            openEmbeddedJSONModal(embeddedString);
            return;
        }

        const keyElement = event.target.closest('.json-key');
        if (keyElement) {
            copyFieldValue(keyElement);
        }
    });

    async function reloadRecord() {
        const requestId = ++recordRequestSequence;
        const id = currentUrl.searchParams.get('id');
        if (!id) {
            renderError('缺少记录 id');
            return;
        }

        try {
            const response = await fetch(`/api/json-record/${encodeURIComponent(id)}`);
            if (!response.ok) {
                throw new Error(`记录不存在：${id}`);
            }
            const record = await response.json();
            if (requestId !== recordRequestSequence) return;
            currentRecord = record;
            renderRecord(currentRecord);
        } catch (error) {
            if (requestId === recordRequestSequence) renderError(error.message);
        }
    }

    function renderRecord(record) {
        panelData.clear();
        meta.innerHTML = [
            badge(`ID ${record.id}`),
            badge(record.sourceType || 'JSON'),
            badge(formatDateTime(record.createTime))
        ].join('');

        const panels = [];
        if (record.reqJson) {
            panels.push(buildPanel('Request', record.reqJson, 'reqJson'));
        }
        if (record.respJson) {
            panels.push(buildPanel('Response', record.respJson, 'respJson'));
        }
        if (record.bodyJson) {
            panels.push(buildPanel('JSON', record.bodyJson, 'bodyJson'));
        }

        jsonLayout.classList.toggle('single', panels.length <= 1);
        jsonLayout.innerHTML = panels.join('');
        searchJSON();

        if (record.rawText && record.rawText !== record.bodyJson) {
            rawContent.textContent = record.rawText;
            rawPanel.hidden = false;
        } else {
            rawPanel.hidden = true;
        }
    }

    function buildPanel(title, jsonText, fieldName) {
        const parsed = parseJsonText(jsonText);
        const formatted = parsed == null ? (jsonText || '') : JSON.stringify(parsed, null, 2);
        const searchablePaths = parsed == null ? [] : findAllPaths(parsed).map(item => {
            const displayValue = stringifyValueForSearch(item.value);
            return {
                ...item,
                displayValue,
                searchText: `${item.key} ${displayValue}`.toLowerCase()
            };
        });
        panelData.set(fieldName, { title, parsed, formatted, searchablePaths });
        return `
            <article class="panel">
                <div class="panel-header">
                    <span class="panel-title">${escapeHtml(title)}</span>
                    <div class="panel-tools">
                        <button data-ws-click="copyField" data-ws-arg="${fieldName}">复制</button>
                    </div>
                </div>
                <div class="output-area" data-field="${escapeAttribute(fieldName)}">${parsed == null ? `<span class="error">${escapeHtml(formatted)}</span>` : renderJSON(parsed, fieldName)}</div>
            </article>
        `;
    }

    function parseJsonText(jsonText) {
        try {
            return JSON.parse(jsonText);
        } catch (error) {
            return null;
        }
    }

    function formatJsonText(jsonText) {
        try {
            return JSON.stringify(JSON.parse(jsonText), null, 2);
        } catch (error) {
            return jsonText || '';
        }
    }

    function renderJSON(value, fieldName) {
        const lines = [];
        appendValueLines({
            value,
            lines,
            indentLevel: 0,
            path: '$',
            key: null,
            comma: false,
            arrayStyle: null,
            fieldName
        });

        return lines.map((line, index) => {
            const classes = ['line-number'];
            if (line.arrayClass) {
                classes.push('array-element-line', line.arrayClass);
            }
            return `<span id="${escapeAttribute(fieldName)}-line-${index + 1}" class="${classes.join(' ')}" data-field="${escapeAttribute(fieldName)}" data-line="${index + 1}" data-path="${escapeAttribute(line.path || '$')}" data-key="${escapeAttribute(line.key || '')}">${line.html}</span>`;
        }).join('');
    }

    function appendValueLines({ value, lines, indentLevel, path, key, comma, arrayStyle, fieldName }) {
        if (Array.isArray(value)) {
            appendArrayLines({ value, lines, indentLevel, path, key, comma, arrayStyle, fieldName });
            return;
        }

        if (value && typeof value === 'object') {
            appendObjectLines({ value, lines, indentLevel, path, key, comma, arrayStyle, fieldName });
            return;
        }

        const prefix = buildLinePrefix(indentLevel, key, path, fieldName);
        const html = `${prefix}${renderPrimitive(value, path, fieldName)}${comma ? '<span class="json-punctuation">,</span>' : ''}`;
        lines.push({ html, path, key, arrayClass: arrayStyle });
    }

    function appendObjectLines({ value, lines, indentLevel, path, key, comma, arrayStyle, fieldName }) {
        const entries = Object.entries(value);
        const prefix = buildLinePrefix(indentLevel, key, path, fieldName);

        if (entries.length === 0) {
            const html = `${prefix}<span class="json-punctuation">{}</span>${comma ? '<span class="json-punctuation">,</span>' : ''}`;
            lines.push({ html, path, key, arrayClass: arrayStyle });
            return;
        }

        lines.push({
            html: `${prefix}<span class="json-punctuation">{</span>`,
            path,
            key,
            arrayClass: arrayStyle
        });

        entries.forEach(([childKey, childValue], index) => {
            appendValueLines({
                value: childValue,
                lines,
                indentLevel: indentLevel + 1,
                path: pathForKey(path, childKey),
                key: childKey,
                comma: index < entries.length - 1,
                arrayStyle,
                fieldName
            });
        });

        lines.push({
            html: `${indent(indentLevel)}<span class="json-punctuation">}</span>${comma ? '<span class="json-punctuation">,</span>' : ''}`,
            path,
            key,
            arrayClass: arrayStyle
        });
    }

    function appendArrayLines({ value, lines, indentLevel, path, key, comma, arrayStyle, fieldName }) {
        const prefix = buildLinePrefix(indentLevel, key, path, fieldName);

        if (value.length === 0) {
            const html = `${prefix}<span class="json-punctuation">[]</span>${comma ? '<span class="json-punctuation">,</span>' : ''}`;
            lines.push({ html, path, key, arrayClass: arrayStyle });
            return;
        }

        lines.push({
            html: `${prefix}<span class="json-punctuation">[</span>`,
            path,
            key,
            arrayClass: arrayStyle
        });

        const complexArray = value.some(isComplexValue);
        value.forEach((item, index) => {
            appendValueLines({
                value: item,
                lines,
                indentLevel: indentLevel + 1,
                path: `${path}[${index}]`,
                key: null,
                comma: index < value.length - 1,
                arrayStyle: complexArray && isComplexValue(item) ? `array-bg-${index % 8}` : arrayStyle,
                fieldName
            });
        });

        lines.push({
            html: `${indent(indentLevel)}<span class="json-punctuation">]</span>${comma ? '<span class="json-punctuation">,</span>' : ''}`,
            path,
            key,
            arrayClass: arrayStyle
        });
    }

    function buildLinePrefix(indentLevel, key, path, fieldName) {
        if (key === null || key === undefined) {
            return indent(indentLevel);
        }

        return `${indent(indentLevel)}<span class="json-key" data-field="${escapeAttribute(fieldName)}" data-key="${escapeAttribute(key)}" data-path="${escapeAttribute(path)}">${escapeHtml(JSON.stringify(key))}</span><span class="json-punctuation">: </span>`;
    }

    function renderPrimitive(value, path, fieldName) {
        if (typeof value === 'string') {
            const jsonCandidate = parseEmbeddedJSON(value);
            const escapedJsonString = escapeHtml(JSON.stringify(value));

            if (jsonCandidate.ok) {
                return `<span class="json-embedded-string" title="点击查看美化后的 JSON" data-field="${escapeAttribute(fieldName)}" data-path="${escapeAttribute(path)}">${escapedJsonString}<span class="embedded-json-badge">JSON</span></span>`;
            }

            return `<span class="json-string">${escapedJsonString}</span>`;
        }

        if (typeof value === 'number') {
            return `<span class="json-number">${String(value)}</span>`;
        }

        if (typeof value === 'boolean') {
            return `<span class="json-boolean">${String(value)}</span>`;
        }

        if (value === null) {
            return '<span class="json-null">null</span>';
        }

        return `<span class="json-string">${escapeHtml(JSON.stringify(value))}</span>`;
    }

    function parseEmbeddedJSON(value) {
        if (typeof value !== 'string') {
            return { ok: false };
        }

        const trimmed = value.trim();
        if (!trimmed || !((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']')))) {
            return { ok: false };
        }

        try {
            const parsed = JSON.parse(trimmed);
            if (parsed && typeof parsed === 'object') {
                return { ok: true, value: parsed };
            }
        } catch (error) {
            return { ok: false };
        }

        return { ok: false };
    }

    function indent(level) {
        return '  '.repeat(level);
    }

    function pathForKey(parentPath, key) {
        return `${parentPath}.${JSON.stringify(key)}`;
    }

    function isComplexValue(value) {
        return value !== null && typeof value === 'object';
    }

    function renderError(message) {
        meta.innerHTML = badge('ERROR');
        jsonLayout.classList.add('single');
        jsonLayout.innerHTML = `
            <article class="panel">
                <div class="panel-header">
                    <span class="panel-title">加载失败</span>
                </div>
                <pre class="error">${escapeHtml(message)}</pre>
            </article>
        `;
        rawPanel.hidden = true;
    }

    function badge(text) {
        return `<span class="badge">${escapeHtml(String(text || '-'))}</span>`;
    }

    function formatDateTime(value) {
        if (!value) {
            return '-';
        }
        return String(value).replace('T', ' ').slice(0, 19);
    }

    function copyField(fieldName) {
        const data = panelData.get(fieldName);
        if (!data) {
            return;
        }
        copyText(data.formatted).then(() => showToast('已复制'));
    }

    function copyRaw() {
        if (!currentRecord || !currentRecord.rawText) {
            return;
        }
        copyText(currentRecord.rawText).then(() => showToast('已复制'));
    }

    function copyPageUrl() {
        copyText(window.location.href).then(() => showToast('URL 已复制'));
    }

    function copyFieldValue(element) {
        const fieldName = element.getAttribute('data-field');
        const keyName = element.getAttribute('data-key');
        const path = element.getAttribute('data-path');
        const data = panelData.get(fieldName);
        if (!data) {
            return;
        }

        const value = getValueByPath(data.parsed, path);
        if (value === undefined) {
            return;
        }

        const result = {};
        result[keyName] = value;
        copyText(JSON.stringify(result, null, 2)).then(() => showToast('字段已复制'));
    }

    function getValueByPath(obj, path) {
        if (!path || path === '$') {
            return obj;
        }

        let current = obj;
        const tokens = parsePath(path);
        for (const token of tokens) {
            if (current !== null && current !== undefined && Object.prototype.hasOwnProperty.call(current, token)) {
                current = current[token];
            } else {
                return undefined;
            }
        }
        return current;
    }

    function parsePath(path) {
        const tokens = [];
        const regex = /\.("([^"\\]|\\.)*")|\[(\d+)]/g;
        let match;
        while ((match = regex.exec(path)) !== null) {
            if (match[0].startsWith('.')) {
                tokens.push(JSON.parse(match[0].slice(1)));
            } else {
                tokens.push(Number(match[3]));
            }
        }
        return tokens;
    }

    function searchJSON() {
        const keyword = searchKeyword.value.trim().toLowerCase();
        clearHighlight();

        if (!keyword || panelData.size === 0) {
            searchResults.classList.remove('active');
            searchMatches = [];
            updateMatchNavigation();
            return;
        }

        searchMatches = [];
        panelData.forEach((data, fieldName) => {
            if (!data.parsed) {
                return;
            }
            const matches = data.searchablePaths.filter(item => item.searchText.includes(keyword));
            matches.forEach(match => {
                searchMatches.push({
                    ...match,
                    fieldName,
                    panelTitle: data.title
                });
            });
        });

        if (searchMatches.length === 0) {
            searchResults.classList.remove('active');
            updateMatchNavigation();
            return;
        }

        searchCount.textContent = `${searchMatches.length} 个匹配`;
        searchResultsList.innerHTML = searchMatches.map((match, index) => `
            <div class="search-result-item" data-ws-click="scrollToMatch" data-ws-arg="${index}">
                <span class="search-result-path">${escapeHtml(match.panelTitle)} ${escapeHtml(match.path)}</span>
                <span class="search-result-value">${escapeHtml(match.displayValue)}</span>
            </div>
        `).join('');
        searchResults.classList.add('active');
        highlightMatches(keyword);
        currentMatchIndex = 0;
        updateMatchNavigation();
    }

    function clearSearch() {
        searchKeyword.value = '';
        clearTimeout(searchTimer);
        searchJSON();
    }

    function scheduleSearchJSON() {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(searchJSON, 180);
    }

    function findAllPaths(obj, prefix = '$', results = []) {
        if (Array.isArray(obj)) {
            obj.forEach((value, index) => {
                const path = `${prefix}[${index}]`;
                results.push({ path, key: String(index), value });
                if (value && typeof value === 'object') {
                    findAllPaths(value, path, results);
                }
            });
            return results;
        }

        if (!obj || typeof obj !== 'object') {
            return results;
        }

        Object.entries(obj).forEach(([key, value]) => {
            const path = pathForKey(prefix, key);
            results.push({ path, key, value });
            if (value && typeof value === 'object') {
                findAllPaths(value, path, results);
            }
        });
        return results;
    }

    function stringifyValueForSearch(value) {
        if (typeof value === 'string') {
            return value;
        }
        if (value && typeof value === 'object') {
            return JSON.stringify(value);
        }
        return String(value);
    }

    function highlightMatches(keyword) {
        const keywordLower = keyword.toLowerCase();
        root.querySelectorAll('.line-number').forEach(line => {
            if (line.textContent.toLowerCase().includes(keywordLower)) {
                line.classList.add('search-hit');
            }
        });
    }

    function clearHighlight() {
        clearLineHighlight();
        root.querySelectorAll('.search-hit').forEach(line => line.classList.remove('search-hit'));
    }

    function scrollToMatch(index) {
        if (index < 0 || index >= searchMatches.length) {
            return;
        }

        currentMatchIndex = index;
        updateMatchNavigation();
        clearLineHighlight();

        const match = searchMatches[index];
        const target = Array.from(root.querySelectorAll('.line-number')).find(line => {
            return line.getAttribute('data-field') === match.fieldName && line.getAttribute('data-path') === match.path;
        });
        if (!target) {
            return;
        }

        target.scrollIntoView({ behavior: 'smooth', block: 'center' });
        target.classList.add('line-highlighted-value');
        clearTimeout(clearLineHighlightTimer);
        clearLineHighlightTimer = setTimeout(clearLineHighlight, 2400);
    }

    function prevMatch() {
        if (currentMatchIndex > 0) {
            scrollToMatch(currentMatchIndex - 1);
        }
    }

    function nextMatch() {
        if (currentMatchIndex < searchMatches.length - 1) {
            scrollToMatch(currentMatchIndex + 1);
        }
    }

    function updateMatchNavigation() {
        const total = searchMatches.length;
        currentMatch.textContent = `${total ? currentMatchIndex + 1 : 0}/${total}`;
        prevMatchButton.disabled = total <= 1 || currentMatchIndex <= 0;
        nextMatchButton.disabled = total <= 1 || currentMatchIndex >= total - 1;
    }

    function clearLineHighlight() {
        root.querySelectorAll('.line-highlighted-value').forEach(line => {
            line.classList.remove('line-highlighted-value');
        });
    }

    function openEmbeddedJSONModal(element) {
        const fieldName = element.getAttribute('data-field');
        const path = element.getAttribute('data-path');
        const data = panelData.get(fieldName);
        if (!data) {
            return;
        }

        const value = getValueByPath(data.parsed, path);
        const parsed = parseEmbeddedJSON(value);
        if (!parsed.ok) {
            return;
        }

        activeEmbeddedJSON = JSON.stringify(parsed.value, null, 2);
        embeddedJsonTitle.textContent = `${data.title} ${path}`;
        embeddedJsonContent.textContent = activeEmbeddedJSON;
        embeddedJsonModal.classList.add('active');
    }

    function closeEmbeddedJSONModal() {
        embeddedJsonModal.classList.remove('active');
    }

    function handleModalBackdropClick(event) {
        if (event.target === embeddedJsonModal) {
            closeEmbeddedJSONModal();
        }
    }

    function copyEmbeddedJSON() {
        if (!activeEmbeddedJSON) {
            return;
        }
        copyText(activeEmbeddedJSON).then(() => showToast('内嵌 JSON 已复制'));
    }

    function copyText(text) {
        if (navigator.clipboard && window.isSecureContext && location.protocol !== 'file:') {
            return navigator.clipboard.writeText(text).catch(() => fallbackCopyText(text));
        }
        return fallbackCopyText(text);
    }

    function fallbackCopyText(text) {
        return new Promise((resolve, reject) => {
            const textarea = document.createElement('textarea');
            textarea.value = text;
            textarea.setAttribute('readonly', '');
            textarea.style.position = 'fixed';
            textarea.style.top = '-9999px';
            textarea.style.left = '-9999px';
            root.appendChild(textarea);
            textarea.focus();
            textarea.select();
            try {
                document.execCommand('copy') ? resolve() : reject(new Error('copy failed'));
            } catch (error) {
                reject(error);
            } finally {
                textarea.remove();
            }
        });
    }

    function showToast(message) {
        const existing = root.querySelector('.toast');
        if (existing) {
            existing.remove();
        }
        const toast = document.createElement('div');
        toast.className = 'toast';
        toast.textContent = message;
        root.appendChild(toast);
        setTimeout(() => toast.remove(), 1800);
    }

    function escapeHtml(value) {
        const div = document.createElement('div');
        div.textContent = value == null ? '' : String(value);
        return div.innerHTML;
    }

    function escapeAttribute(text) {
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

function openSearch() { route.navigate('/json-record-search.html'); }

bindActions(root, { reloadRecord, renderRecord, buildPanel, parseJsonText, formatJsonText, renderJSON, appendValueLines, appendObjectLines, appendArrayLines, buildLinePrefix, renderPrimitive, parseEmbeddedJSON, indent, pathForKey, isComplexValue, renderError, badge, formatDateTime, copyField, copyRaw, copyPageUrl, copyFieldValue, getValueByPath, parsePath, searchJSON, clearSearch, scheduleSearchJSON, findAllPaths, stringifyValueForSearch, highlightMatches, clearHighlight, scrollToMatch, prevMatch, nextMatch, updateMatchNavigation, clearLineHighlight, openEmbeddedJSONModal, closeEmbeddedJSONModal, handleModalBackdropClick, copyEmbeddedJSON, copyText, fallbackCopyText, showToast, escapeHtml, escapeAttribute, openSearch });
reloadRecord();
return { activate(url) { const next = new URL(url); if (next.search !== currentUrl.search) { currentUrl = next; searchKeyword.value = ''; reloadRecord(); } } };
}
