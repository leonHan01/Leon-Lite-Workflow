const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
let workflow = null, versions = [], runs = [], credentials = [];
let schedule = { enabled: false, cron: '0 0 9 * * *', timeZone: 'Asia/Shanghai', input: { items: [{ json: {} }] }, revision: 0, nextRunAt: null };
const initial = () => ({ nodes: [{ id: 'start', name: '开始', type: 'start', config: {}, position: { x: 80, y: 140 } }, { id: 'end', name: '结束', type: 'end', config: {}, position: { x: 420, y: 140 } }], edges: [{ id: 'start_end', source: 'start', target: 'end', branch: '' }] });
const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://localhost');
    const reply = (data, status = 200) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(data)); };
    if (url.pathname === '/api/monitor/page-view') { res.writeHead(204); return res.end(); }
    if (url.pathname.startsWith('/api/workflows')) {
        let body = ''; for await (const part of req) body += part; const data = body ? JSON.parse(body) : {};
        if (req.method !== 'GET') assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        const route = url.pathname.slice('/api/workflows'.length);
        if (route === '/workflow-1/schedule') {
            if (req.method === 'PUT') schedule = { ...schedule, ...data, revision: schedule.revision + 1, nextRunAt: data.enabled ? new Date(Date.now() + 60000).toISOString() : null };
            return reply(schedule);
        }
        if (route === '/schedule-preview') return reply({ timeZone: data.timeZone, nextRuns: Array.from({length:5}, (_,i) => new Date(Date.now() + (i+1)*60000).toISOString()) });
        if (route === '/run-options') return reply(workflow ? [{id:workflow.id,name:workflow.name,deleted:false}] : []);
        if (route === '/runs') {
            const filtered = runs.filter(r => (!url.searchParams.get('workflowId') || r.workflowId === url.searchParams.get('workflowId')) && (!url.searchParams.get('status') || r.status === url.searchParams.get('status')) && (!url.searchParams.get('trigger') || r.triggerType === url.searchParams.get('trigger')) && (!url.searchParams.get('q') || r.id.includes(url.searchParams.get('q')) || r.workflowName.includes(url.searchParams.get('q'))));
            const page = Number(url.searchParams.get('page') || 1), pageSize = Number(url.searchParams.get('pageSize') || 20);
            return reply({ items: filtered.slice((page-1)*pageSize,page*pageSize), total:filtered.length, page,pageSize,summary:{total:filtered.length,active:filtered.filter(r=>['RUNNING','QUEUED'].includes(r.status)).length,succeeded:filtered.filter(r=>r.status==='SUCCEEDED').length,failed:filtered.filter(r=>r.status==='FAILED').length} });
        }
        if (route === '/capabilities') return reply({ runtimes: { javascript: 'GraalJS', groovy: 'Groovy' }, sandbox: 'macOS Sandbox', credentialsEnabled: true });
        if (route === '/credentials') {
            if (req.method === 'POST') { const value = { id: 'credential-' + (credentials.length + 1), name: data.name, kind: data.kind }; credentials.push(value); return reply(value); }
            return reply(credentials);
        }
        if (route === '/test-node') return reply({ output: data.node.type === 'feishu' ? data.input : { items: [{ json: { name: 'LEON' } }] }, logs: data.node.type === 'feishu' ? '飞书消息已发送' : '调试完成', durationMs: 22, branch: '' });
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
                const run = { id: 'run-1', workflowId:workflow.id,workflowName:workflow.name,triggerType:'MANUAL',version: workflow.publishedVersion, status: 'RUNNING', createdAt: new Date().toISOString(), startedAt: new Date().toISOString(), definition: versions[0].definition,
                    input: data.input, output: null, nodes: versions[0].definition.nodes.map(n => ({ nodeId: n.id, name: n.name, status: 'PENDING', input: data.input, output: null })) };
                runs.unshift(run);
                setTimeout(() => { run.status = 'SUCCEEDED'; run.finishedAt = new Date().toISOString(); run.output = { items: [{ json: { name: 'leon', amount: 250 } }] }; run.nodes.forEach(n => { n.status = n.nodeId === 'rejected' ? 'SKIPPED' : 'SUCCEEDED'; n.output = run.output; n.logs = n.nodeId === 'calculate' ? 'Groovy：金额翻倍' : ''; }); }, 900);
                return reply(run, 202);
            }
            return reply(runs);
        }
        if (route.startsWith('/runs/')) {
            const id=route.split('/')[2], run=runs.find(r=>r.id===id);
            if(!run)return reply({message:'not found'},404);
            if(route.endsWith('/cancel')) {run.status='CANCELLED';run.finishedAt=new Date().toISOString();}
            return reply(run);
        }
        return reply({ message: 'not found' }, 404);
    }
    const file = path.resolve(root, '.' + decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
    if (!file.startsWith(root + path.sep)) { res.writeHead(403); return res.end(); }
    try { res.setHeader('Content-Type', file.endsWith('.png') ? 'image/png' : file.endsWith('.css') ? 'text/css' : file.endsWith('.js') ? 'application/javascript' : 'text/html; charset=utf-8'); res.end(fs.readFileSync(file)); }
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
        assert.equal(await page.locator('[data-node]').count(), 2);
        assert.equal(await page.locator('[data-node="start"] .wf-port-in').count(), 0);
        assert.equal(await page.locator('[data-node="end"] .wf-port-out').count(), 0);
        assert.equal(await page.locator('[data-action="delete-node"]').count(), 0);
        const savePaletteGraph = async () => {
            await page.getByRole('button', {name:'保存草稿',exact:true}).click();
            await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        };
        for (const [kind, scale] of [['http',1],['javascript',.5],['groovy',2],['feishu',1],['if',1]]) {
            await page.locator('[data-node="end"]').click();
            await page.getByRole('button',{name:'重置画布缩放',exact:true}).click();
            for (let i=0; i<(scale===.5?5:scale===2?10:0); i++) await page.getByRole('button',{name:scale===.5?'缩小画布':'放大画布',exact:true}).click();
            await page.evaluate(() => { window.scrollTo(0,0); const board=document.querySelector('.wf-canvas-scroll'); board.scrollLeft=350; board.scrollTop=220; });
            const board = await page.locator('.wf-canvas-scroll').boundingBox();
            const source = await page.locator(`[data-add="${kind}"]`).boundingBox();
            const drop = {x:board.x+320,y:board.y+310};
            const beforeEdges = structuredClone(workflow.definition.edges), endPosition = structuredClone(workflow.definition.nodes.find(n=>n.id==='end').position);
            await page.mouse.move(source.x+source.width/2,source.y+source.height/2); await page.mouse.down();
            await page.mouse.move(drop.x,drop.y,{steps:12});
            assert.equal(await page.locator('[data-node]').count(),2,'松开前创建了节点');
            assert.equal(await page.locator('.wf-library-preview.is-valid').count(),1,'拖动节点没有有效落点预览');
            const expected = await page.locator('.wf-canvas-scroll').evaluate((e,{drop,scale}) => {
                const r=e.getBoundingClientRect(); return {x:(drop.x-r.left+e.scrollLeft)/scale-104,y:(drop.y-r.top+e.scrollTop)/scale-50};
            },{drop,scale});
            if (kind==='feishu') await page.screenshot({path:'/private/tmp/leon-workflow-library-drag.png',fullPage:false});
            await page.mouse.up(); await savePaletteGraph();
            assert.equal(workflow.definition.nodes.length,3,'拖入没有创建一个节点或重复创建');
            const added = workflow.definition.nodes.find(n=>!['start','end'].includes(n.type));
            assert.equal(added.type,['javascript','groovy'].includes(kind)?'script':kind);
            if (added.type==='script') assert.equal(added.config.language,kind);
            assert.ok(Math.abs(added.position.x-expected.x)<1 && Math.abs(added.position.y-expected.y)<1,`缩放 ${scale} 或滚动后落点错误`);
            assert.deepEqual(workflow.definition.edges,beforeEdges,'拖入节点改变了现有连线');
            assert.deepEqual(workflow.definition.nodes.find(n=>n.id==='end').position,endPosition,'拖入节点移动了结束节点');
            assert.equal(await page.locator('.wf-library-preview').count(),0);
            assert.equal(await page.locator('.is-library-drop-target').count(),0);
            await page.getByRole('button',{name:'删除节点',exact:true}).click(); await savePaletteGraph();
        }
        await page.getByRole('button',{name:'重置画布缩放',exact:true}).click();
        await page.evaluate(() => { window.scrollTo(0,0); document.querySelector('.wf-canvas-scroll').scrollTo(0,0); });
        for (const cancel of ['outside','escape','pointercancel']) {
            const source=await page.locator('[data-add="feishu"]').boundingBox(), board=await page.locator('.wf-canvas-scroll').boundingBox();
            await page.mouse.move(source.x+source.width/2,source.y+source.height/2); await page.mouse.down();
            await page.mouse.move(board.x+320,board.y+310,{steps:10});
            if (cancel==='outside') await page.mouse.move(board.x-30,board.y+100,{steps:4});
            if (cancel==='escape') await page.keyboard.press('Escape');
            if (cancel==='pointercancel') await page.locator('[data-add="feishu"]').dispatchEvent('pointercancel',{pointerId:1,pointerType:'mouse'});
            await page.mouse.up(); await savePaletteGraph();
            assert.equal(workflow.definition.nodes.length,2,`取消 ${cancel} 仍创建了节点`);
            assert.equal(await page.locator('.wf-library-preview').count(),0);
            assert.equal(await page.locator('.is-library-drop-target').count(),0);
        }
        await page.locator('[data-add="feishu"]').click();
        await page.locator('[data-action="feishu-credentials"]').click();
        await page.waitForSelector('[data-credentials-dialog][open]');
        const credentialForm = page.locator('[data-credential-form]');
        assert.equal(await credentialForm.locator('[name="kind"]').inputValue(), 'feishu');
        assert.equal(await credentialForm.locator('[name="secret"]').isDisabled(), true);
        assert.equal(await credentialForm.locator('[name="webhookUrl"]').isVisible(), true);
        await credentialForm.locator('[name="name"]').fill('飞书测试机器人');
        await credentialForm.locator('[name="webhookUrl"]').fill('https://open.feishu.cn/open-apis/bot/v2/hook/browser-example');
        await credentialForm.locator('[name="feishuSecret"]').fill('browser-test-signing-secret');
        await page.getByRole('button', {name:'保存凭据',exact:true}).click();
        await page.waitForSelector('.wf-credential-row');
        assert.equal(await credentialForm.locator('[name="webhookUrl"]').inputValue(), '');
        assert.equal(await credentialForm.locator('[name="feishuSecret"]').inputValue(), '');
        await page.locator('[data-close="credentials"]').click();
        assert.equal(await page.locator('[data-field="credentialId"]').inputValue(), 'credential-1');
        assert.equal(await page.locator('[data-feishu-title]').isVisible(), false);
        const message = '通知：{{ input.items[0].json.name }}';
        await page.getByRole('textbox', {name:'飞书消息内容',exact:true}).fill(message);
        await page.locator('[data-field="messageType"]').selectOption('card');
        assert.equal(await page.locator('[data-feishu-title]').isVisible(), true);
        await page.locator('[data-field="title"]').fill('工作流测试通知');
        await page.locator('[data-field="messageType"]').selectOption('text');
        assert.equal(await page.getByRole('textbox', {name:'飞书消息内容',exact:true}).inputValue(), message);
        await page.locator('[data-field="messageType"]').selectOption('card');
        assert.equal(await page.locator('[data-field="title"]').inputValue(), '工作流测试通知');
        await page.getByRole('button', {name:'保存草稿',exact:true}).click();
        await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        const feishu = workflow.definition.nodes.find(n => n.type === 'feishu');
        assert.deepEqual(feishu.config, {credentialId:'credential-1',messageType:'card',title:'工作流测试通知',text:message,timeoutMs:10000});
        assert.ok(!JSON.stringify(workflow).includes('browser-example'));
        assert.ok(!JSON.stringify(workflow).includes('browser-test-signing-secret'));
        const feishuImage = page.locator(`[data-node="${feishu.id}"] .wf-feishu img`);
        await feishuImage.evaluate(img => img.decode());
        assert.equal(await feishuImage.evaluate(img => img.naturalWidth), 200);
        await page.getByRole('button', {name:'发送测试消息',exact:true}).click();
        await page.waitForFunction(() => document.querySelector('[data-run-detail]').textContent.includes('飞书消息已发送'));
        await page.evaluate(() => { window.scrollTo(0,0); document.querySelector('.wf-inspector').scrollTop = 0; });
        await page.screenshot({path:'/private/tmp/leon-workflow-feishu-node.png',fullPage:false});
        await page.getByRole('button', {name:'删除节点',exact:true}).click();
        assert.equal(await page.locator('[data-node]').count(), 2);
        await page.locator('[data-add="javascript"]').click();
        await page.locator('[data-add="if"]').click();
        await page.getByRole('button', { name: '保存草稿', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        const condition = workflow.definition.nodes.find(n => n.type === 'if');
        assert.equal(workflow.definition.nodes.length, 4);
        assert.equal(workflow.definition.edges.filter(e => e.source === condition.id && e.target === 'end').length, 2);
        assert.equal(await page.locator('[data-connect="false"] option[value="end"]').count(), 0, '添加连接列表不应重复显示已有连接');
        const scriptId = workflow.definition.nodes.find(n => n.type === 'script').id;
        const originalEdge = workflow.definition.edges.find(e => e.source === scriptId && e.target === condition.id);
        const saveGraph = async () => {
            await page.getByRole('button', {name:'保存草稿',exact:true}).click();
            await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        };
        const clickEdge = async id => {
            await page.getByRole('button',{name:'适应视图',exact:true}).click();
            const point = await page.locator(`[data-edge="${id}"] .wf-edge-hit`).evaluate(path => {
                for (const fraction of [.5,...Array.from({length:19},(_,i)=>(i+1)/20)]) {
                    const point = path.getPointAtLength(path.getTotalLength() * fraction).matrixTransform(path.getScreenCTM());
                    if (document.elementFromPoint(point.x,point.y)?.closest('[data-edge]') === path.parentElement) return {x:point.x,y:point.y};
                }
            });
            assert.ok(point, '连线没有可点击的可见区域');
            await page.mouse.click(point.x, point.y);
            assert.equal(await page.locator('[data-edge].is-selected').getAttribute('data-edge'), id);
        };
        const connectPorts = async (source, target, branch = '', halfScale = false) => {
            await page.getByRole('button',{name:'适应视图',exact:true}).click();
            if (halfScale) {
                await page.getByRole('button',{name:'重置画布缩放',exact:true}).click();
                for (let i=0;i<5;i++) await page.getByRole('button',{name:'缩小画布',exact:true}).click();
                assert.equal(await page.locator('[data-zoom]').textContent(),'50%');
            }
            const from = await page.locator(`[data-source="${source}"][data-branch="${branch}"]`).boundingBox();
            const to = await page.locator(`[data-target="${target}"]`).boundingBox();
            const count = await page.locator('[data-edge]').count();
            await page.mouse.move(from.x+from.width/2,from.y+from.height/2); await page.mouse.down();
            await page.mouse.move(to.x+to.width/2,to.y+to.height/2,{steps:10});
            assert.equal(await page.locator('.wf-edge-preview').count(),1,'按住拖动时没有预览线');
            assert.equal(await page.locator('[data-edge]').count(),count,'松开前就创建了连线');
            assert.equal(await page.locator('.wf-port.is-drop-target').getAttribute('data-target'),target);
            const tip=await page.locator('.wf-edge-preview').evaluate(path=>{
                const p=path.getPointAtLength(path.getTotalLength()).matrixTransform(path.getScreenCTM());return {x:p.x,y:p.y};
            });
            assert.ok(Math.abs(tip.x-to.x-to.width/2)<2 && Math.abs(tip.y-to.y-to.height/2)<2,'缩放后预览线没有跟随鼠标');
            await page.mouse.up();
            assert.equal(await page.locator('.wf-edge-preview').count(),0,'松开后残留预览线');
        };
        const dragEndpoint = async (endpoint, port) => {
            const handle = await page.locator(`[data-reconnect="${endpoint}"]`).boundingBox();
            const target = await page.locator(port).boundingBox();
            await page.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2); await page.mouse.down();
            await page.mouse.move(target.x + target.width / 2, target.y + target.height / 2, {steps:10}); await page.mouse.up();
        };
        await clickEdge(originalEdge.id);
        assert.equal(await page.locator('.wf-edge-handle').count(),2);
        await page.keyboard.press('Delete'); await saveGraph();
        assert.ok(!workflow.definition.edges.some(e=>e.id===originalEdge.id),'Delete 未删除选中连线');
        // A click without holding and dragging must not leave a pending mouse connection.
        await page.locator(`[data-source="${scriptId}"]`).click();
        await page.locator(`[data-target="${condition.id}"]`).click(); await saveGraph();
        assert.ok(!workflow.definition.edges.some(e=>e.source===scriptId && e.target===condition.id));
        const beforeCancel = workflow.definition.edges.length;
        await page.getByRole('button',{name:'适应视图',exact:true}).click();
        const sourcePort=await page.locator('[data-source="start"]').boundingBox();
        const board=await page.locator('.wf-canvas-scroll').boundingBox();
        for (const cancel of ['blank','escape']) {
            await page.mouse.move(sourcePort.x+sourcePort.width/2,sourcePort.y+sourcePort.height/2); await page.mouse.down();
            await page.mouse.move(board.x+board.width/2,board.y+280,{steps:8});
            assert.equal(await page.locator('.wf-edge-preview').count(),1);
            if (cancel==='escape') await page.keyboard.press('Escape');
            await page.mouse.up(); await saveGraph();
            assert.equal(workflow.definition.edges.length,beforeCancel,'取消拖动时创建了连线');
            assert.equal(await page.locator('.wf-edge-preview').count(),0);
            assert.equal(await page.locator('.wf-port.is-drop-target').count(),0);
        }
        await connectPorts('start', condition.id); await saveGraph();
        assert.equal(workflow.definition.edges.filter(e=>e.source==='start').length,2,'新增连接覆盖了原有出口');
        const editedId = workflow.definition.edges.find(e=>e.source==='start' && e.target===condition.id).id;
        await page.locator('[data-edge-field="target"]').selectOption('end');
        await page.getByRole('button',{name:'保存连线修改',exact:true}).click(); await saveGraph();
        assert.equal(workflow.definition.edges.find(e=>e.id===editedId).target,'end');
        await page.locator('[data-edge-field="source"]').selectOption(scriptId);
        await page.getByRole('button',{name:'保存连线修改',exact:true}).click(); await saveGraph();
        assert.equal(workflow.definition.edges.find(e=>e.id===editedId).source,scriptId);
        await page.getByRole('button',{name:'重新连接终点',exact:true}).click(); await page.keyboard.press('Escape');
        assert.equal(await page.locator('[data-edge-field="target"]').inputValue(),'end','取消重新连接时丢失原连线');
        await page.getByRole('button',{name:'重新连接终点',exact:true}).click();
        await page.locator(`[data-target="${condition.id}"]`).click(); await saveGraph();
        assert.equal(workflow.definition.edges.find(e=>e.id===editedId).target,condition.id);
        const beforeDuplicate = workflow.definition.edges.length;
        await connectPorts(scriptId,condition.id);
        assert.ok((await page.locator('[data-notice]').textContent()).includes('已经'));
        await page.keyboard.press('Escape'); await saveGraph();
        assert.equal(workflow.definition.edges.length,beforeDuplicate);
        await clickEdge(editedId);
        await page.locator('[data-edge-field="target"]').selectOption(scriptId);
        await page.getByRole('button',{name:'保存连线修改',exact:true}).click(); await saveGraph();
        assert.equal(workflow.definition.edges.find(e=>e.id===editedId).target,condition.id,'无效重连改变了原连线');
        await clickEdge(editedId);
        await dragEndpoint('target','[data-target="end"]'); await saveGraph();
        assert.equal(workflow.definition.edges.find(e=>e.id===editedId).target,'end','拖动终点未更新连线');
        await clickEdge(editedId);
        await dragEndpoint('source','[data-source="start"]'); await saveGraph();
        assert.equal(workflow.definition.edges.find(e=>e.id===editedId).source,'start','拖动起点未更新连线');
        assert.equal(workflow.definition.edges.filter(e=>e.source==='start').length,2);
        await page.locator('[data-edge-field="source"]').selectOption(scriptId);
        await page.locator('[data-edge-field="target"]').selectOption(condition.id);
        await page.getByRole('button',{name:'保存连线修改',exact:true}).click(); await saveGraph();
        await clickEdge(editedId);
        await page.screenshot({path:'/private/tmp/leon-workflow-edge-editing.png',fullPage:false});
        await page.getByRole('button',{name:'删除连线',exact:true}).click(); await saveGraph();
        assert.ok(!workflow.definition.edges.some(e=>e.id===editedId));
        await connectPorts(scriptId,condition.id); await saveGraph();
        for (const branch of ['true','false']) {
            const previous=workflow.definition.edges.find(e=>e.source===condition.id && e.branch===branch);
            await clickEdge(previous.id); await page.keyboard.press('Delete'); await saveGraph();
            await connectPorts(condition.id,'end',branch,true); await saveGraph();
            assert.equal(workflow.definition.edges.filter(e=>e.source===condition.id && e.branch===branch && e.target==='end').length,1);
        }
        // Keyboard users can still choose an output, then activate a destination.
        await page.locator('[data-source="start"]').focus(); await page.keyboard.press('Enter');
        await page.locator('[data-target="end"]').click(); await saveGraph();
        const keyboardEdge=workflow.definition.edges.find(e=>e.source==='start' && e.target==='end');
        assert.ok(keyboardEdge,'键盘方式无法创建连线');
        await clickEdge(keyboardEdge.id); await page.keyboard.press('Delete'); await saveGraph();
        await page.locator('[data-node="end"]').click();
        assert.equal(await page.locator('[data-action="delete-node"]').count(), 0);
        assert.equal(await page.locator('[data-connect]').count(), 0);
        await page.getByRole('button', { name: '载入双语言示例', exact: true }).click();
        await page.getByRole('button',{name:'重置画布缩放',exact:true}).click();
        assert.equal(await page.locator('[data-node]').count(), 7);
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
        const canvasHeight = await page.locator('.wf-canvas-scroll').evaluate(e=>e.clientHeight);
        assert.ok(canvasHeight>=680,'画布可视高度没有增加');
        const oldExtent = await page.locator('.wf-canvas').evaluate(e=>e.offsetWidth);
        await page.getByRole('button',{name:'扩展画布',exact:true}).click();
        assert.ok(await page.locator('.wf-canvas').evaluate(e=>e.offsetWidth)>oldExtent,'画布范围没有扩展');
        await page.getByRole('button',{name:'重置画布缩放',exact:true}).click();
        for(let i=0;i<5;i++)await page.getByRole('button',{name:'缩小画布',exact:true}).click();
        assert.equal(await page.locator('[data-zoom]').textContent(),'50%');
        await page.locator('[data-drag="clean"]').scrollIntoViewIfNeeded();
        const zoomedBox=await page.locator('[data-drag="clean"]').boundingBox();
        const beforeScaled=structuredClone(workflow.definition.nodes[1].position);
        await page.mouse.move(zoomedBox.x+30,zoomedBox.y+15);await page.mouse.down();
        await page.mouse.move(zoomedBox.x+510,zoomedBox.y+45,{steps:12});await page.mouse.up();
        await page.getByRole('button',{name:'保存草稿',exact:true}).click();
        await page.waitForFunction(()=>document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        assert.ok(workflow.definition.nodes[1].position.x>1250,'节点仍被旧画布边界截住');
        assert.ok(Math.abs(workflow.definition.nodes[1].position.x-beforeScaled.x-960)<12,'缩放后的拖拽坐标不正确');
        await page.getByRole('button',{name:'全屏编辑',exact:true}).click();
        await page.waitForSelector('[data-editor].is-focused');
        assert.ok(await page.locator('.wf-canvas-scroll').evaluate(e=>e.clientHeight)>canvasHeight,'全屏编辑未增加可视区域');
        await page.getByRole('button',{name:'适应视图',exact:true}).click();
        await page.screenshot({path:'/private/tmp/leon-workflow-canvas-expanded.png',fullPage:false});
        await page.keyboard.press('Escape');
        assert.equal(await page.locator('body').evaluate(e=>e.classList.contains('wf-focus-open')),false);
        await page.getByRole('button',{name:'重置画布缩放',exact:true}).click();
        await page.getByRole('button', { name: '发布版本', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-action="run"]').textContent.includes('v1'));
        await page.getByRole('button', {name:'定时执行',exact:true}).click();
        await page.locator('[data-schedule-form] [name="enabled"]').check();
        await page.locator('[data-schedule-form] [name="preset"]').selectOption('custom');
        await page.getByRole('textbox',{name:'Cron 表达式',exact:true}).fill('0 30 8 * * *');
        await page.getByRole('button',{name:'预览执行时间',exact:true}).click();
        await page.waitForSelector('[data-schedule-preview] li');
        assert.equal(await page.locator('[data-schedule-preview] li').count(),5);
        await page.getByRole('button',{name:'保存定时配置',exact:true}).click();
        await page.waitForSelector('[data-schedule-summary]:not([hidden])');
        assert.equal(schedule.cron,'0 30 8 * * *');assert.equal(schedule.enabled,true);
        await page.getByRole('button', {name:'定时执行',exact:true}).click();
        await page.locator('[data-schedule-form] [name="enabled"]').uncheck();
        await page.getByRole('button',{name:'保存定时配置',exact:true}).click();
        await page.waitForSelector('[data-schedule-summary][hidden]',{state:'attached'});
        assert.equal(schedule.enabled,false);
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
        await page.waitForFunction(() => document.querySelectorAll('.wf-credential-row').length === 2);
        await page.locator('[data-close="credentials"]').click();
        await page.getByRole('textbox', { name: '工作流名称', exact: true }).fill('未保存草稿');
        await page.locator('.lw-sidebar').getByRole('link', { name: '总览', exact: true }).click();
        await page.locator('.lw-sidebar').getByRole('link', { name: '工作流编排', exact: true }).click();
        assert.equal(await page.getByRole('textbox', { name: '工作流名称', exact: true }).inputValue(), '未保存草稿');
        await page.getByRole('button', { name: '保存草稿', exact: true }).click();
        await page.waitForFunction(() => document.querySelector('[data-version]').textContent.includes('草稿已保存'));
        await page.screenshot({ path: process.env.WORKFLOW_SCREENSHOT || '/private/tmp/leon-workflows-desktop.png', fullPage: true });
        const originalRun = structuredClone(runs[0]);
        runs.push(...Array.from({length:25},(_,i)=>({...originalRun,id:'history-'+i,status:i%2?'SUCCEEDED':'FAILED',triggerType:i%3?'MANUAL':'SCHEDULED'})));
        runs.unshift({...originalRun,id:'scheduled-running',status:'RUNNING',triggerType:'SCHEDULED',scheduledAt:new Date().toISOString()});
        await page.locator('[data-all-runs]').click();
        await page.waitForSelector('[data-rows] [data-open]');
        assert.equal(await page.locator('[data-rows] [data-open]').count(),20);
        await page.locator('[data-next]').click();
        await page.waitForFunction(()=>document.querySelector('[data-page-label]').textContent.includes('第 2'));
        assert.equal(await page.locator('[data-rows] [data-open]').count(),7);
        await page.getByRole('combobox',{name:'筛选状态',exact:true}).selectOption('RUNNING');
        await page.getByRole('combobox',{name:'筛选触发方式',exact:true}).selectOption('SCHEDULED');
        await page.getByRole('button',{name:'筛选',exact:true}).click();
        await page.waitForFunction(()=>document.querySelector('[data-count="total"]').textContent==='1');
        await page.getByRole('button',{name:'查看详情',exact:true}).click();
        await page.waitForSelector('[data-details] [data-action="cancel-run"]');
        assert.ok((await page.locator('[data-details]').textContent()).includes('定时触发'));
        await page.getByRole('button',{name:'停止运行',exact:true}).click();
        await page.waitForFunction(()=>document.querySelector('[data-details] .wf-badge')?.textContent==='已取消');
        await page.locator('[data-reset]').click();
        await page.waitForFunction(()=>document.querySelector('[data-count="total"]').textContent==='27');
        await page.locator('[data-page-size]').selectOption('50');
        await page.waitForFunction(()=>document.querySelectorAll('[data-rows] [data-open]').length===27);
        await page.locator('[data-rows] [data-open]').first().click();
        await page.waitForSelector('.wr-detail-title');
        await page.screenshot({path:'/private/tmp/leon-workflow-runs-desktop.png',fullPage:true});
        await page.setViewportSize({ width: 390, height: 844 });
        await page.screenshot({ path: '/private/tmp/leon-workflow-runs-mobile.png', fullPage: true });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2), false, '移动端出现页面横向溢出');
        await page.locator('.lw-sidebar').getByRole('link',{name:'工作流编排',exact:true}).click();
        await page.getByRole('button',{name:'全屏编辑',exact:true}).click();
        assert.ok(await page.locator('.wf-canvas-scroll').evaluate(e=>e.clientHeight)>250,'手机全屏画布不可用');
        await page.getByRole('button',{name:'展开节点配置',exact:true}).click();
        assert.equal(await page.locator('.wf-inspector').isVisible(),true);
        await page.getByRole('button',{name:'关闭节点配置',exact:true}).click();
        await page.getByRole('button',{name:'退出全屏',exact:true}).click();
        assert.equal(await page.locator('body').evaluate(e=>e.classList.contains('wf-focus-open')),false);
        assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+2),false,'移动端编排界面横向溢出');
        assert.deepEqual(errors, []);
        console.log('Workflow browser checks passed: library drag/drop for all nodes, zoom/scroll positions, cancellation, Feishu configuration, canvas, scheduling, instances, navigation and mobile.');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; server.close(); });
