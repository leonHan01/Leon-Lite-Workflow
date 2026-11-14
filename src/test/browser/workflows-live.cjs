const assert = require('node:assert/strict');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
(async () => {
    const browser = await chromium.launch({ headless: true, executablePath: process.env.CHROME_PATH, args: ['--no-sandbox'] });
    try {
        const page = await browser.newPage({ viewport: { width: 1500, height: 1050 } });
        const errors = []; page.on('pageerror', e => errors.push(e.message)); page.setDefaultTimeout(15000);
        await page.goto(process.env.WORKFLOW_BASE_URL + '/workflows.html', { waitUntil: 'networkidle' });
        await page.getByRole('button', { name: '＋ 新建工作流', exact: true }).click();
        await page.getByRole('textbox', { name: '新工作流名称' }).fill('真实双语言工作流');
        await page.getByRole('button', { name: '创建工作流', exact: true }).click();
        await page.waitForSelector('[data-node="start"]');
        await page.getByRole('button', { name: '载入双语言示例', exact: true }).click();
        await page.getByRole('button', { name: '发布版本', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-action="run"]').textContent.includes('v1'));
        await page.getByRole('button', { name: '▶ 运行 v1', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-run-detail] .wf-badge')?.textContent === '成功', { timeout: 25000 });
        assert.ok((await page.locator('[data-run-detail]').textContent()).includes('250'));
        assert.ok((await page.locator('[data-node="rejected"]').getAttribute('class')).includes('status-SKIPPED'));
        await page.locator('[data-step="calculate"] summary').click();
        const groovyDetail = await page.locator('[data-step="calculate"]').textContent();
        assert.ok(groovyDetail.includes('Groovy：金额翻倍'), groovyDetail);
        assert.ok((await page.locator('[data-notice]').textContent()).includes('运行成功'));
        await page.evaluate(() => window.scrollTo(0, 0));
        await page.screenshot({ path: '/private/tmp/leon-workflows-live.png', fullPage: true });
        await page.reload({ waitUntil: 'networkidle' });
        await page.waitForSelector('[data-run]');
        await page.locator('[data-run]').first().click();
        await page.waitForFunction(() => document.querySelector('[data-run-detail] .wf-badge')?.textContent === '成功');
        assert.deepEqual(errors, []);
        console.log('Live workflow UI passed: publish, real JS/Groovy execution, IF branch, logs and persisted history after refresh.');
    } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
