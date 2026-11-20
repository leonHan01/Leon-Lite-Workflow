// Read-only production entry checks. Credentials and business data stay in memory.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium, request } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');

if (!process.argv.includes('--live')) throw new Error('Use --live to check the configured HTTPS service');
const root = path.resolve(__dirname, '../..');
const base = new URL(process.env.LEON_ADMIN_AUDIT_URL || 'https://121.40.254.112');
assert.equal(base.protocol, 'https:');
assert.equal(base.username + base.password + base.search + base.hash, '');
const modules = JSON.parse(fs.readFileSync(path.join(root, 'android/app/src/main/assets/modules.json'), 'utf8'));
const clientCss = fs.readFileSync(path.join(root, 'android/app/src/main/assets/native-workspace.css'), 'utf8');
const clientScript = fs.readFileSync(path.join(root, 'android/app/src/main/assets/native-workspace.js'), 'utf8')
    .replace('__LEON_NATIVE_HANDSHAKE__', 'read-only-entry-audit');
const primaryApis = {
    home: [], 'tools-pro': ['/api/web-collections'], kv: ['/api/simple-kv/list', '/api/simple-kv/icons'],
    records: ['/api/json-record/search'], workflows: ['/api/workflows'], 'workflow-runs': ['/api/workflows/runs'],
    agents: ['/api/agent-admin', '/api/agent-admin/devices'], users: ['/api/agent-admin/users'],
    music: ['/api/music/tracks', '/api/music/playlists'], 'music-statistics': ['/api/music/tracks'],
    monitor: ['/api/monitor'], database: ['/api/admin/database-backup'],
    advanced: ['/api/workflows/models', '/api/workflows/credentials'],
};
const report = { checkedAt: new Date().toISOString(), entries: [], failures: [], sessionRevoked: false };
let api, browser, loggedIn = false, stage = 'setup', currentEntry;
const safePath = value => new URL(value).pathname.replace(/[0-9a-f]{8}-[0-9a-f-]{27,}/gi, ':id').replace(/\/\d+(?=\/|$)/g, '/:id');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
async function waitFor(check, timeout = 12000) {
    const until = Date.now() + timeout;
    while (Date.now() < until) { if (await check()) return; await pause(100); }
    throw new Error('entry-check-timeout');
}
function engineError(error) {
    const source = String(error.stack || '').match(/\/workspace\/[\w/-]+\.js(?::\d+:\d+)?/);
    return { kind: error.name, source: source?.[0] || 'page script' };
}

(async () => {
    api = await request.newContext({ baseURL: base.origin, timeout: 20000,
        extraHTTPHeaders: { Origin: base.origin, 'X-Requested-With': 'XMLHttpRequest' } });
    try {
        stage = 'login';
        const tokenFile = process.env.LEON_ADMIN_AUDIT_TOKEN_FILE || path.join(process.env.HOME, '.config/leon-workflow/api-token');
        const login = await api.post('/auth/login', { data: { token: fs.readFileSync(tokenFile, 'utf8').trim(), remember: false } });
        assert.equal(login.status(), 200); loggedIn = true;
        const state = await api.storageState();
        assert.ok(state.cookies.some(cookie => cookie.name === '__Host-leon_session'));
        const app = await api.get('/workspace/app.js'); assert.equal(app.status(), 200);
        const routeDefinitions = [...(await app.text()).split('const views =')[0].matchAll(/'([^']+)':\s*\['([^']+)'/g)];
        const routes = new Map(routeDefinitions.map(match => [match[1], match[2]]));
        stage = 'browser launch';
        browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH,
            args: ['--no-sandbox', '--disable-gpu'] });
        const context = await browser.newContext({ storageState: state, viewport: { width: 393, height: 851 },
            userAgent: 'Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 LeonWorkflowAdmin/1.0' });
        const interceptedWrites = new Set();
        await context.route('**/*', async route => {
            const req = route.request(), url = new URL(req.url());
            if (url.origin !== base.origin || ['GET', 'HEAD', 'OPTIONS'].includes(req.method())) return route.continue();
            interceptedWrites.add(safePath(req.url()));
            // Page-view counters are deliberately excluded from this read-only audit.
            if (url.pathname === '/api/monitor/page-view') return route.fulfill({ status: 204, body: '' });
            return route.fulfill({ status: 409, contentType: 'application/json',
                body: '{"message":"Read-only entry audit blocked a write"}' });
        });
        for (const module of modules) {
            currentEntry = module;
            const key = routes.get(module.path);
            const row = { title: module.title, path: module.path, phases: {}, apiFailures: [], scriptErrors: [], resourceFailures: [] };
            report.entries.push(row);
            stage = 'resources';
            const direct = await api.get(module.path, { maxRedirects: 0 });
            row.directStatus = direct.status();
            row.resources = {};
            for (const resource of ['/workspace/views/' + key + '.html', '/workspace/' + key + '.js']) {
                const response = await api.get(resource);
                row.resources[resource] = response.status();
                assert.equal(response.status(), 200);
                assert.ok(!((await response.text()).includes('id="loginForm"')), 'unexpected-login-resource');
            }
            assert.ok(key);
            const page = await context.newPage();
            let responses = [];
            page.on('response', response => {
                const url = new URL(response.url());
                if (url.origin !== base.origin) return;
                if (url.pathname.startsWith('/api/')) {
                    responses.push({ path: url.pathname, status: response.status() });
                    if (response.status() >= 400) row.apiFailures.push({ path: safePath(response.url()), status: response.status() });
                } else if (url.pathname.startsWith('/workspace/') && response.status() >= 400)
                    row.resourceFailures.push({ path: safePath(response.url()), status: response.status() });
            });
            page.on('pageerror', error => row.scriptErrors.push(engineError(error)));
            async function navigate(destination, replace) {
                await page.evaluate(async ({ destination, replace }) => {
                    const workspace = await import('/workspace/app.js');
                    if (replace) history.replaceState(null, '', destination);
                    await workspace.navigate(destination, { historyMode: replace ? 'none' : 'push' });
                }, { destination, replace });
            }
            async function ready(requireApis = true) {
                await page.waitForFunction(expected => {
                    const visible = [...document.querySelectorAll('.ws-pane')].filter(pane => !pane.hidden);
                    const target = visible[0];
                    return document.getElementById('workspaceStatus')?.hidden && visible.length === 1 &&
                        target?.classList.contains('workspace-' + expected);
                }, key === 'home' ? 'overview' : key === 'kv' ? 'kv' : key === 'records' ? 'record-search'
                    : ['music', 'music-statistics'].includes(key) ? 'music' : key === 'advanced' ? 'advanced' : key,
                { timeout: 12000 });
                if (requireApis) for (const expected of primaryApis[key] || [])
                    await waitFor(() => responses.some(response => response.path === expected && response.status === 200));
                await pause(300);
                assert.equal(new URL(page.url()).pathname, module.path);
                assert.equal(row.apiFailures.length + row.resourceFailures.length + row.scriptErrors.length, 0);
            }
            async function bootstrap() {
                responses = [];
                const response = await page.goto(base.origin + '/index.html', { waitUntil: 'load', timeout: 20000 });
                assert.equal(response.status(), 200);
                await page.addStyleTag({ content: clientCss }); await page.evaluate(clientScript);
                await navigate(module.path, true); await ready();
            }
            try {
                stage = 'cold'; await bootstrap(); row.phases.cold = 'passed';
                stage = 'warm'; await navigate(module.path === '/index.html' ? '/json-record-search.html' : '/index.html', false);
                // Mounted views retain their data and forms; a revisit need not refetch.
                responses = []; await navigate(module.path, false); await ready(false); row.phases.warm = 'passed';
                stage = 'refresh'; await bootstrap(); row.phases.refresh = 'passed';
                if (key === 'tools-pro') {
                    stage = 'tools tabs';
                    for (const tab of ['tools', 'notes', 'todos', 'chat', 'collections']) {
                        await page.locator('.workspace-tools-pro-panel').locator('[data-tab="' + tab + '"]').click();
                        await pause(400);
                    }
                    assert.equal(row.apiFailures.length + row.scriptErrors.length, 0);
                    row.phases.toolsTabs = 'passed';
                }
            } catch (error) {
                row.failedStage = stage;
                report.failures.push({ title: module.title, stage, kind: error.name });
            } finally { await page.close(); }
            console.log(module.title + ': ' + (row.failedStage ? 'FAILED ' + row.failedStage : 'passed') +
                ' (direct HTTP ' + row.directStatus + ')');
        }
        report.interceptedWrites = [...interceptedWrites];
        assert.ok(report.interceptedWrites.every(value => value === '/api/monitor/page-view'), 'unexpected-automatic-write');
    } catch (error) {
        report.failures.push({ title: currentEntry?.title || 'setup', stage, kind: error.name });
    } finally {
        if (browser) await browser.close();
        if (loggedIn) {
            const logout = await api.post('/auth/logout', { data: {} });
            report.sessionRevoked = logout.status() === 204;
        }
        if (api) await api.dispose();
        const output = path.join(root, 'dist/android/entry-audit.json');
        fs.mkdirSync(path.dirname(output), { recursive: true });
        fs.writeFileSync(output, JSON.stringify(report, null, 2) + '\n');
        console.log(JSON.stringify({ checked: report.entries.length, failures: report.failures.length,
            sessionRevoked: report.sessionRevoked, report: output }));
        if (report.failures.length || !report.sessionRevoked) process.exitCode = 1;
    }
})().catch(error => { console.error('Entry audit aborted (' + error.name + ')'); process.exitCode = 1; });
