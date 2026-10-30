import { bindActions } from './events.js';
export function mount(root, route) {
const byId = id => root.querySelector('#' + CSS.escape(id));
let currentUrl = new URL(route.url);

    const keywordInput = byId('keywordInput');
    const summary = byId('summary');
    const results = byId('results');
    let searchTimer = null;
    let activeSearchController = null;
    let searchRequestId = 0;



    keywordInput.addEventListener('input', () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(searchRecords, 220);
    });

    keywordInput.addEventListener('keydown', event => {
        if (event.key === 'Enter') {
            clearTimeout(searchTimer);
            searchRecords();
        }
    });

    async function searchRecords() {
        const keyword = keywordInput.value.trim();
        const query = new URLSearchParams();
        query.set('keyword', keyword);
        query.set('limit', '50');
        updateUrl(keyword);
        const requestId = ++searchRequestId;
        if (activeSearchController) {
            activeSearchController.abort();
        }
        activeSearchController = new AbortController();

        summary.textContent = keyword ? `正在搜索：${keyword}` : '正在加载最近记录...';
        results.innerHTML = '';

        try {
            const response = await fetch(`/api/json-record/search?${query.toString()}`, {
                signal: activeSearchController.signal
            });
            if (!response.ok) {
                throw new Error(`搜索失败：${response.status}`);
            }
            const records = await response.json();
            if (requestId !== searchRequestId) {
                return;
            }
            renderResults(records, keyword);
        } catch (error) {
            if (error.name === 'AbortError' || requestId !== searchRequestId) {
                return;
            }
            summary.textContent = '搜索失败';
            results.innerHTML = `<div class="error">${escapeHtml(error.message)}</div>`;
        }
    }

    function renderResults(records, keyword) {
        const label = keyword ? `“${keyword}”` : '最近记录';
        summary.textContent = `${label}：${records.length} 条`;

        if (!records.length) {
            results.innerHTML = '<div class="empty">没有找到匹配记录</div>';
            return;
        }

        results.innerHTML = records.map(record => {
            const url = `/json-record.html?id=${encodeURIComponent(record.id)}`;
            const preview = highlightKeyword(record.preview || '', keyword);
            return `
                <a class="result-item" href="${url}">
                    <div class="result-head">
                        <div class="result-title">
                            <span>记录 #${escapeHtml(record.id)}</span>
                            <span class="badge">${escapeHtml(record.sourceType || 'JSON')}</span>
                            <span class="badge">${escapeHtml(fieldLabel(record.matchField))}</span>
                        </div>
                        <span class="time">${escapeHtml(formatDateTime(record.updateTime || record.createTime))}</span>
                    </div>
                    <div class="preview">${preview}</div>
                </a>
            `;
        }).join('');
    }

    function clearKeyword() {
        keywordInput.value = '';
        searchRecords();
        keywordInput.focus();
    }

    function loadRecentRecords() {
        keywordInput.value = '';
        searchRecords();
    }

    function updateUrl(keyword) {
        const nextUrl = keyword
            ? `${currentUrl.pathname}?q=${encodeURIComponent(keyword)}`
            : currentUrl.pathname;
        currentUrl = new URL(nextUrl, window.location.origin); route.replace(nextUrl);
    }

    function fieldLabel(fieldName) {
        if (fieldName === 'reqJson') {
            return 'Request';
        }
        if (fieldName === 'respJson') {
            return 'Response';
        }
        if (fieldName === 'bodyJson') {
            return 'JSON';
        }
        return '原始日志';
    }

    function formatDateTime(value) {
        if (!value) {
            return '-';
        }
        return String(value).replace('T', ' ').slice(0, 19);
    }

    function highlightKeyword(value, keyword) {
        const escaped = escapeHtml(value);
        if (!keyword) {
            return escaped;
        }
        return escaped.replace(new RegExp(`(${escapeRegex(keyword)})`, 'gi'), '<mark>$1</mark>');
    }

    function escapeRegex(value) {
        return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    }

    function escapeHtml(value) {
        const div = document.createElement('div');
        div.textContent = value == null ? '' : String(value);
        return div.innerHTML;
    }

bindActions(root, { searchRecords, renderResults, clearKeyword, loadRecentRecords, updateUrl, fieldLabel, formatDateTime, highlightKeyword, escapeRegex, escapeHtml });
keywordInput.value = currentUrl.searchParams.get('q') || ''; searchRecords();
return { activate(url) { const next = new URL(url); if (next.search !== currentUrl.search) { currentUrl = next; keywordInput.value = next.searchParams.get('q') || ''; searchRecords(); } } };
}
