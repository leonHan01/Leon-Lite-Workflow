const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const users = [{id:'a',username:'Alice',createdAt:1763596800000}, {id:'b',username:'Bob',createdAt:1763596800000}];
let rooms = [{id:'thread:1',name:'测试对话',kind:'direct',messageCount:2,lastMessageAt:1763596800000}];
let background = {background:'喜欢简洁的回答',version:1};
const markdown = [
    '## 推荐方案', '', '**重点** 与 `code`，还有 [安全链接](https://example.com) 和 [危险链接](javascript:alert(1))。', '',
    '- 列表一', '- 列表二', '', '> 一段引用', '', '| 名称 | 值 |', '| --- | --- |', '| 一 | 二 |', '',
    '```js', 'const x = "<tag>";', '```', '', '<img src=x onerror="window.markdownXss=1">', '', '![不安全图片](javascript:alert(1))', '', '&lt;hello&gt;'
].join('\n');
const server = http.createServer(async (req,res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname.startsWith('/api/')) {
        let body = ''; for await (const chunk of req) body += chunk;
        let data = {};
        if (url.pathname === '/api/agent-admin/users') data = users;
        else if (url.pathname === '/api/agents') data = [{id:'agent',enabled:true,name:'Helper',model:'test'}];
        else if (url.pathname.endsWith('/conversations')) data = rooms;
        else if (url.pathname.endsWith('/background')) { if (req.method === 'PUT') background = {background:JSON.parse(body).background,version:background.version+1}; data = background; }
        else if (url.pathname.endsWith('/messages')) data = url.searchParams.has('before')
            ? {messages:[{id:1,sender:'Alice',role:'user',text:'更早的消息',createdAt:1763596800000,images:[]}],hasMore:false}
            : {messages:[{id:2,sender:'Helper',role:'assistant',text:markdown,createdAt:1763596860000,images:[]}],hasMore:true};
        else if (url.pathname.endsWith('/analysis')) data = {analysis:'偏好清晰的建议。',messageCount:1,truncated:false};
        else if (url.pathname.endsWith('/delete-preview')) data = {messageCount:2,latestMessageId:2,historyRevision:0,hasActiveWork:false};
        else if (url.pathname.endsWith('/delete')) { rooms = []; data = {homeRetained:false,clearedMessages:2}; }
        res.writeHead(200, {'Content-Type':'application/json'}); return res.end(JSON.stringify(data));
    }
    const file = path.resolve(root, '.' + decodeURIComponent(url.pathname));
    if (!file.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
    try { const bytes=fs.readFileSync(file); res.writeHead(200, {'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':'text/html; charset=utf-8'}); res.end(bytes); }
    catch { res.writeHead(404); res.end(); }
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_PATH, args:['--no-sandbox']});
    try {
        const page = await browser.newPage({viewport:{width:1400,height:900}}); const errors=[];
        page.on('pageerror', error => errors.push(error.message));
        await page.goto('http://127.0.0.1:' + server.address().port + '/workspace/users.html', {waitUntil:'networkidle'});
        await page.getByRole('button',{name:/Alice/}).click();
        const conversation = page.getByRole('button',{name:/测试对话/}); await conversation.click();
        const dialog = page.getByRole('dialog', {name:'测试对话'}); await dialog.getByRole('heading',{name:'推荐方案'}).waitFor();
        assert.equal(await dialog.locator('.users-markdown strong').first().textContent(), '重点');
        assert.equal(await dialog.locator('.users-markdown li').count(), 2);
        assert.equal(await dialog.locator('.users-markdown table td').count(), 2);
        assert.match(await dialog.locator('.users-markdown pre code').textContent(), /<tag>/);
        assert.equal(await dialog.getByRole('link',{name:'安全链接'}).getAttribute('rel'), 'noopener noreferrer');
        assert.equal(await dialog.getByRole('link',{name:'危险链接'}).count(), 0);
        assert.equal(await dialog.locator('img').count(), 0);
        assert.equal(await page.evaluate(() => window.markdownXss), undefined);
        assert.ok((await dialog.locator('.users-markdown').textContent()).includes('<hello>'));
        await dialog.getByRole('button',{name:'加载更早消息'}).click(); await dialog.getByText('更早的消息').waitFor();
        await page.keyboard.press('Escape'); assert.equal(await dialog.count(), 0); assert.equal(await conversation.evaluate(el => document.activeElement === el), true);
        await conversation.click(); await dialog.getByRole('heading',{name:'推荐方案'}).waitFor();
        await page.setViewportSize({width:390,height:844});
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false);
        const rect = await dialog.boundingBox(); assert.ok(rect.width <= 390 && rect.height <= 844);
        await dialog.getByRole('button',{name:'关闭会话详情'}).click();
        await page.locator('[data-background]').fill('喜欢摄影'); await page.getByRole('button',{name:'保存背景'}).click();
        await page.getByText('已保存；后续对话会使用这份背景。').waitFor(); assert.equal(background.background,'喜欢摄影');
        await page.getByRole('button',{name:'分析用户画像'}).click(); await page.getByText('偏好清晰的建议。').waitFor();
        await conversation.click(); page.once('dialog', prompt => prompt.accept()); await dialog.getByRole('button',{name:'删除会话'}).click();
        await page.getByText('会话已删除。').waitFor(); assert.equal(await dialog.count(), 0);
        assert.deepEqual(errors, []); console.log('User management browser checks passed');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode=1; });
