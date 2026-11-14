const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
let workflow = null, versions = [], runs = [], credentials = [];
const initial = () => ({ nodes: [{ id: 'start', name: '手动触发', type: 'manual', config: {}, position: { x: 80, y: 140 } }], edges: [] });
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const reply = (data, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data)); };
    if (url.pathname === '/api/monitor/page-view') { res.writeHead(204); return res.end(); }
    if (url.pathname.startsWith('/api/workflows')) {
        let body = ''; for await (const part of req) body += part; const data = body ? JSON.parse(body) : {};
        if (req.method !== 'GET') assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        const route = url.pathname.slice('/api/workflows'.length);
        if (route === '/capabilities') return reply({ runtimes: { javascript: 'GraalJS', groovy: 'Groovy' }, sandbox: 'macOS Sandbox', credentialsEnabled: true });
        if (route === '/credentials') {
            if (req.method === 'POST') { const value = { id: 'credential-1', name: data.name, kind: data.kind }; credentials.push(value); return reply(value); }
            return reply(credentials);
        }
        if (route === '/test-node') return reply({ output: { items: [{ json: { name: 'LEON' } }] }, logs: '调试完成', durationMs: 22, branch: '' });
        if (!route) {
            if (req.method === 'POST') workflow = { id: 'workflow-1', name: data.name, definition: initial(), revision: 1, publishedVersion: 0 };
            return reply(req.method === 'POST' ? workflow : workflow ? [workflow] : []);
        }
        if (route === '/workflow-1') {
            if (req.method === 'PUT') workflow = { ...workflow, ...data, revision: workflow.revision + 1 };
            if (req.method === 'DELETE') { workflow = null; return reply({ deleted: true }); }
            return reply(workflow);
        }
        if (route === '/workflow-1/publish') { workflow.publishedVersion++; versions.unshift({ version: workflow.publishedVersion, definition: structuredClone(workflow.definition), runtimes: { javascript: 'GraalJS', groovy: 'Groovy' }, createdAt: new Date().toISOString() }); return reply(workflow); }
        if (route === '/workflow-1/versions') return reply(versions);
        if (route === '/workflow-1/runs') {
            if (req.method === 'POST') {
                const run = { id: 'run-1', version: workflow.publishedVersion, status: 'RUNNING', createdAt: new Date().toISOString(), startedAt: new Date().toISOString(), definition: versions[0].definition,
                    input: data.input, output: null, nodes: versions[0].definition.nodes.map(n => ({ nodeId: n.id, name: n.name, status: 'PENDING', input: data.input, output: null })) };
                runs.unshift(run);
                setTimeout(() => { run.status = 'SUCCEEDED'; run.finishedAt = new Date().toISOString(); run.output = { items: [{ json: { name: 'leon', amount: 250 } }] }; run.nodes.forEach(n => { n.status = n.nodeId === 'rejected' ? 'SKIPPED' : 'SUCCEEDED'; n.output = run.output; n.logs = n.nodeId === 'calculate' ? 'Groovy：金额翻倍' : ''; }); }, 900);
                return reply(run, 202);
            }
            return reply(runs);
        }
        if (route === '/runs/run-1') return reply(runs[0]);
        return reply({ message: 'not found' }, 404);
    }
    const file = path.resolve(root, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
    try { res.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : file.endsWith('.js') ? 'application/javascript' : 'text/html; charset=utf-8'); res.end(fs.readFileSync(file)); }
    catch { res.writeHead(404); res.end(); }
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH, args: ['--no-sandbox'] });
    try {
        const page = await browser.newPage({ viewport: { width: 1600, height: 1100 } }); page.setDefaultTimeout(7000);
        const errors = []; page.on('pageerror', e => errors.push(e.message)); page.on('dialog', d => d.accept());
        const base = 'http://127.0.0.1:' + server.address().port;
        await page.goto(base + '/workflows.html', { waitUntil: 'networkidle' });
        await page.getByRole('button', { name: '＋ 新建工作流', exact: true }).click();
        await page.getByRole('textbox', { name: '新工作流名称' }).fill('双语言演示 <安全>');
        await page.getByRole('button', { name: '创建工作流', exact: true }).click();
        await page.waitForSelector('[data-node="start"]');
        await page.getByRole('button', { name: '载入双语言示例', exact: true }).click();
        assert.equal(await page.locator('[data-node]').count(), 6);
        const original = await page.getByRole('textbox', { name: '脚本代码' }).inputValue();
        await page.locator('[data-field="language"]').selectOption('groovy');
        assert.ok((await page.getByRole('textbox', { name: '脚本代码' }).inputValue()).includes('def main'));
        await page.locator('[data-field="language"]').selectOption('javascript');
        assert.equal(await page.getByRole('textbox', { name: '脚本代码' }).inputValue(), original, '切换语言丢失源码');
        await page.getByRole('button', { name: '保存草稿', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        const before = workflow.definition.nodes[1].position;
        const handle = page.locator('[data-drag="clean"]'); await handle.scrollIntoViewIfNeeded(); const box = await handle.boundingBox();
        await page.mouse.move(box.x + 35, box.y + 20); await page.mouse.down(); await page.mouse.move(box.x + 55, box.y + 55, { steps: 5 }); await page.mouse.up();
        await page.getByRole('button', { name: '保存草稿', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        assert.ok(workflow.definition.nodes[1].position.y > before.y, '拖动位置未保存');
        await page.getByRole('button', { name: '发布版本', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-action="run"]').textContent.includes('v1'));
        await page.getByRole('button', { name: '▶ 运行 v1', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-run-detail] .wf-badge')?.textContent === '成功');
        assert.ok((await page.locator('[data-run-detail]').textContent()).includes('250'));
        assert.equal(await page.locator('[data-node="rejected"]').getAttribute('class').then(c => c.includes('status-SKIPPED')), true);
        await page.locator('[data-node="calculate"]').click();
        await page.getByRole('button', { name: '调试此节点', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-run-detail]').textContent.includes('草稿节点调试'));
        await page.getByRole('button', { name: '管理凭据', exact: true }).click();
        await page.locator('[data-credential-form] [name="name"]').fill('演示凭据');
        await page.locator('[data-credential-form] [name="secret"]').fill('example-only');
        await page.getByRole('button', { name: '保存凭据', exact: true }).click();
        await page.waitForSelector('.wf-credential-row');
        await page.locator('[data-close="credentials"]').click();
        await page.getByRole('textbox', { name: '工作流名称', exact: true }).fill('未保存草稿');
        await page.locator('.lw-sidebar').getByRole('link', { name: '总览', exact: true }).click();
        await page.locator('.lw-sidebar').getByRole('link', { name: '工作流编排', exact: true }).click();
        assert.equal(await page.getByRole('textbox', { name: '工作流名称', exact: true }).inputValue(), '未保存草稿');
        await page.getByRole('button', { name: '保存草稿', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        await page.screenshot({ path: process.env.WORKFLOW_SCREENSHOT || '/private/tmp/leon-workflows-desktop.png', fullPage: true });
        await page.setViewportSize({ width: 390, height: 844 });
        await page.screenshot({ path: '/private/tmp/leon-workflows-mobile.png', fullPage: true });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false, '移动端出现页面横向溢出');
        assert.deepEqual(errors, []);
        console.log('Workflow browser checks passed: create, mixed-language canvas, source retention, drag, save/publish/run, branches, preview, credentials, navigation, mobile.');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; server.close(); });
