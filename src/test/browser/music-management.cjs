const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
let tracks = [];
let writes = 0;
const id = '00000000-0000-0000-0000-000000000001';
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/api/monitor/page-view') { res.writeHead(204); return res.end(); }
    if (url.pathname === '/api/music/tracks' && req.method === 'POST') {
        assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        assert.match(req.headers['content-type'], /^multipart\/form-data; boundary=/);
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => {
            assert.match(Buffer.concat(chunks).toString(), /filename="song.wav"/);
            tracks.push({ id, title: '<b>song</b>', artist: '测试歌手', album: '测试专辑', size: 44 });
            writes++;
            res.writeHead(201, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify(tracks[0]));
        });
        return;
    }
    if (url.pathname === `/api/music/tracks/${id}` && req.method === 'DELETE') {
        assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        tracks = [];
        writes++;
        res.writeHead(204); return res.end();
    }
    if (url.pathname === '/api/music/tracks') {
        res.setHeader('Content-Type', 'application/json'); return res.end(JSON.stringify(tracks));
    }
    const file = path.join(root, url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname));
    if (!file.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
    try {
        res.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : file.endsWith('.js') ? 'application/javascript' : 'text/html; charset=utf-8');
        res.end(fs.readFileSync(file));
    } catch { res.writeHead(404); res.end(); }
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH, args: ['--no-sandbox'] });
    try {
        const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
        page.setDefaultTimeout(5000);
        const errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto(`http://127.0.0.1:${server.address().port}/music.html`);
        await page.getByText('曲库还是空的，上传第一首歌曲吧。').waitFor();
        await page.locator('input[name=files]').setInputFiles({ name: 'song.wav', mimeType: 'audio/wav', buffer: Buffer.alloc(44) });
        await page.locator('input[name=artist]').fill('测试歌手');
        await page.locator('input[name=album]').fill('测试专辑');
        await page.getByRole('button', { name: '上传到曲库 ↗' }).click();
        await page.getByText('已上传 1 首。').waitFor();
        assert.equal(await page.locator('[data-tracks] strong').innerText(), '<b>song</b>');
        assert.equal(await page.locator('[data-tracks] strong b').count(), 0, 'song metadata must be text');
        assert.equal(await page.locator('audio').getAttribute('preload'), 'none');
        await page.locator('[data-search]').fill('不存在');
        await page.getByText('没有匹配的歌曲。').waitFor();
        await page.locator('[data-search]').fill('测试歌手');
        assert.equal(await page.locator('[data-tracks] tr').count(), 1);
        for (const width of [1440, 800]) {
            await page.setViewportSize({ width, height: 900 });
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'page should not overflow');
        }
        await page.setViewportSize({ width: 1440, height: 1000 });
        if (process.env.SCREENSHOT_PATH) await page.screenshot({ path: process.env.SCREENSHOT_PATH, fullPage: true });
        page.on('dialog', dialog => dialog.accept());
        await page.locator('[data-tracks]').getByRole('button', { name: '删除', exact: true }).click();
        await page.getByText('曲库还是空的，上传第一首歌曲吧。').waitFor();
        assert.equal(writes, 2);
        await page.reload();
        await page.getByText('曲库还是空的，上传第一首歌曲吧。').waitFor();
        assert.deepEqual(errors, []);
        console.log('Music management browser checks passed.');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
