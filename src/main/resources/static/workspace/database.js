const endpoint = '/api/admin/database-backup'; // Existing HTTPS proxy route.
const number = new Intl.NumberFormat('zh-CN');
function bytes(value) {
    if (value == null) return '—';
    if (value < 1024) return number.format(value) + ' B';
    const units = ['KB', 'MB', 'GB', 'TB'];
    let i = -1;
    do { value /= 1024; i++; } while (value >= 1024 && i < units.length - 1);
    return value.toFixed(value < 10 ? 2 : 1) + ' ' + units[i];
}
function identifier(value) { return '"' + value.replaceAll('"', '""') + '"'; }

export function mount(root) {
    const tbody = root.querySelector('[data-db-tables]');
    const tableStatus = root.querySelector('[data-db-table-status]');
    const queryStatus = root.querySelector('[data-db-query-status]');
    const sql = root.querySelector('[data-db-sql]');
    const refresh = root.querySelector('[data-db-refresh]');
    const run = root.querySelector('[data-db-run]');
    const result = root.querySelector('[data-db-result]');
    const sortButtons = root.querySelectorAll('[data-db-sort]');
    let loaded = false;
    let tables = [];
    let sortKey = null;
    let sortDirection = 'descending';

    function renderTables() {
        const ordered = [...tables];
        if (sortKey) ordered.sort((a, b) => {
            const left = a[sortKey], right = b[sortKey];
            if (left == null || right == null) return left == null ? (right == null ? 0 : 1) : -1;
            return (left - right) * (sortDirection === 'ascending' ? 1 : -1);
        });
        tbody.replaceChildren();
        for (const table of ordered) {
            const tr = document.createElement('tr');
            const name = document.createElement('td');
            const button = document.createElement('button'); button.type = 'button'; button.className = 'db-table-link';
            button.textContent = table.schema + '.' + table.name;
            button.addEventListener('click', () => { sql.value = 'SELECT * FROM ' + identifier(table.schema) + '.' + identifier(table.name); sql.focus(); });
            name.append(button); tr.append(name);
            for (const text of [table.rows == null ? '—' : number.format(table.rows), bytes(table.bytes), '']) {
                const cell = document.createElement('td'); cell.textContent = text; tr.append(cell);
            }
            tbody.append(tr);
        }
        for (const button of sortButtons) {
            const selected = button.dataset.dbSort === sortKey;
            const column = button.closest('th');
            if (selected) column.setAttribute('aria-sort', sortDirection);
            else column.removeAttribute('aria-sort');
            button.querySelector('span').textContent = selected ? (sortDirection === 'ascending' ? '↑' : '↓') : '↕';
        }
    }

    async function loadTables() {
        refresh.disabled = true; tableStatus.textContent = '正在统计数据表…';
        try {
            const response = await fetch(endpoint + '?tables', { cache: 'no-store' });
            if (!response.ok) throw new Error('数据表加载失败，请检查登录状态后重试。');
            const data = await response.json();
            tables = data.tables;
            renderTables();
            tableStatus.textContent = '共 ' + number.format(tables.length) + ' 张表';
            loaded = true;
        } catch (error) { tableStatus.textContent = error.message; }
        finally { refresh.disabled = false; }
    }
    async function execute() {
        run.disabled = true; queryStatus.textContent = '正在执行查询…'; result.hidden = true;
        try {
            const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sql: sql.value }) });
            if (!response.ok) throw new Error(response.status === 403 ? '请从本站管理页面登录后查询。' : '查询失败，请检查 SQL、表名和查询权限，或缩小查询范围。');
            const data = await response.json();
            const head = result.querySelector('thead'); const body = result.querySelector('tbody');
            head.replaceChildren(); body.replaceChildren();
            const header = document.createElement('tr');
            for (const label of data.columns) { const th = document.createElement('th'); th.textContent = label; header.append(th); }
            head.append(header);
            for (const values of data.rows) {
                const row = document.createElement('tr');
                for (const value of values) { const cell = document.createElement('td'); cell.textContent = value === null ? 'NULL' : value; if (value === null) cell.className = 'db-null'; row.append(cell); }
                body.append(row);
            }
            result.hidden = false;
            queryStatus.textContent = '返回 ' + number.format(data.rows.length) + ' 行' + (data.truncated ? '（结果已截断）' : '') + '。';
        } catch (error) { queryStatus.textContent = error.message; }
        finally { run.disabled = false; }
    }
    refresh.addEventListener('click', loadTables);
    for (const button of sortButtons) button.addEventListener('click', () => {
        const key = button.dataset.dbSort;
        sortDirection = sortKey === key && sortDirection === 'descending' ? 'ascending' : 'descending';
        sortKey = key;
        renderTables();
    });
    run.addEventListener('click', execute);
    sql.addEventListener('keydown', event => { if (event.key === 'Enter' && (event.ctrlKey || event.metaKey) && !run.disabled) { event.preventDefault(); execute(); } });
    return { activate() { if (!loaded) loadTables(); } };
}
