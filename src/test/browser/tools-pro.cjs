const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
let sessions = [], chatCalls = 0, notifications = [];
let notes = [{id:1,content:'插件已有便签',archived:false,createTime:new Date().toISOString()}];
const collection = {id:1,name:'共享集合',groups:[{id:2,name:'默认分组',items:[{id:3,name:'共享网页',url:'https://example.com',sortOrder:0}]}]};
const server = http.createServer(async (req,res) => {
  const url = new URL(req.url,'http://localhost');
  res.setHeader('X-Frame-Options', 'DENY');
  if (url.pathname.startsWith('/api/')) {
    let body=''; for await (const chunk of req) body+=chunk;
    if (!['GET','HEAD'].includes(req.method)) assert.equal(req.headers['x-requested-with'],'XMLHttpRequest');
    let data=[];
    if (url.pathname === '/api/web-collections' || url.pathname === '/api/web-collections/full') data=[collection];
    else if (url.pathname === '/api/toolbox' && req.method === 'GET') data=[{id:5,name:'共享工具',url:'https://example.com'}];
    else if (url.pathname === '/api/notes' && req.method === 'POST') { const input=JSON.parse(body); const note={id:notes.length+1,content:input.content,archived:false,createTime:new Date().toISOString()}; notes.push(note); data=note; }
    else if (url.pathname === '/api/notes') data=notes;
    else if (url.pathname === '/api/llm-sessions' && req.method === 'POST') { const input=JSON.parse(body); const session={id:1,title:input.title,messages:[]}; sessions.push(session); data=session; }
    else if (url.pathname === '/api/llm-sessions/full' || url.pathname === '/api/llm-sessions') data=sessions;
    else if (/^\/api\/llm-sessions\/1\/messages$/.test(url.pathname) && req.method === 'POST') { const input=JSON.parse(body); data={id:sessions[0].messages.length+1,...input,createTime:new Date().toISOString()}; sessions[0].messages.push(data); }
    else if (/^\/api\/llm-sessions\/1$/.test(url.pathname)) { if(req.method === 'PUT') Object.assign(sessions[0],JSON.parse(body)); data=sessions[0]; }
    else if (url.pathname === '/api/workspace-notification') { notifications.push(JSON.parse(body)); data={sent:true}; }
    else if (url.pathname === '/api/workspace-chat') { chatCalls++; res.writeHead(200,{'Content-Type':'application/json'}); return res.end(JSON.stringify({content:'模拟 AI 回复'})); }
    else if (url.pathname.startsWith('/api/app-settings/')) data={value:''};
    res.writeHead(200,{'Content-Type':'application/json'}); return res.end(JSON.stringify({success:true,data}));
  }
  const file=path.resolve(root,'.'+decodeURIComponent(url.pathname));
  if (!file.startsWith(root+path.sep)) { res.writeHead(403); return res.end(); }
  try { const bytes=fs.readFileSync(file); res.writeHead(200,{'Content-Type':file.endsWith('.js')?'application/javascript':file.endsWith('.css')?'text/css':file.endsWith('.png')?'image/png':'text/html; charset=utf-8'}); res.end(bytes); }
  catch { res.writeHead(404);res.end(); }
});
(async () => {
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1400,height:900}}); page.setDefaultTimeout(8000); const errors=[]; page.on('pageerror',e=>errors.push(e.message));
  const base='http://127.0.0.1:'+server.address().port;
  await page.goto(base+'/workspace/tools-pro.html',{waitUntil:'networkidle'});
  assert.equal(await page.locator('iframe[title="Leon Tools Pro"]').count(), 0);
  const frame=page.locator('.workspace-tools-pro-panel');
  await frame.getByText('共享集合',{exact:true}).waitFor();
  await frame.getByText('共享集合',{exact:true}).click();
  await frame.getByText('共享网页',{exact:true}).waitFor();
  await page.screenshot({path:'/private/tmp/leon-tools-pro-collection.png'});
  await frame.getByRole('tab',{name:'便签',exact:true}).click();
  await frame.getByText('插件已有便签',{exact:true}).waitFor();
  await frame.getByPlaceholder('搜索便签').fill('插件');
  assert.equal(await frame.evaluate(e => e.shadowRoot.activeElement?.id), 'note-search');
  await frame.getByPlaceholder('搜索便签').fill('');
  await frame.getByPlaceholder('写一条便签，回车发送').fill('工作台新便签');
  const notification = page.waitForResponse(r=>r.url().endsWith('/api/workspace-notification'));
  await frame.getByPlaceholder('写一条便签，回车发送').press('Enter');
  await frame.getByText('工作台新便签',{exact:true}).waitFor();
  assert.ok(notes.some(n=>n.content==='工作台新便签'));
  await notification;
  assert.ok(notifications.some(n=>n.title==='新增便签' && n.text==='工作台新便签'));
  const other=await browser.newPage(); await other.goto(base+'/workspace/tools-pro.html',{waitUntil:'networkidle'});
  const second=other.locator('.workspace-tools-pro-panel'); await second.getByRole('tab',{name:'便签',exact:true}).click();
  await second.getByText('工作台新便签',{exact:true}).waitFor();
  await frame.getByRole('tab',{name:'待办',exact:true}).click();
  await frame.getByPlaceholder('添加待办，回车保存').waitFor();
  await frame.getByRole('tab',{name:'工具',exact:true}).click();
  await frame.getByText('JSON 格式化',{exact:true}).waitFor();
  await frame.getByText('共享工具',{exact:true}).click({button:'right'});
  await frame.getByRole('menuitem',{name:'编辑',exact:true}).click();
  await frame.getByRole('heading',{name:'编辑工具',exact:true}).waitFor();
  await frame.getByRole('button',{name:'取消',exact:true}).click();
  await frame.getByRole('tab',{name:'LLM',exact:true}).click();
  await frame.getByPlaceholder('输入消息，回车发送').fill('测试问题');
  await frame.getByPlaceholder('输入消息，回车发送').press('Enter');
  await frame.getByText('模拟 AI 回复',{exact:true}).waitFor();
  assert.equal(chatCalls,1); assert.ok(sessions[0].messages.some(m=>m.content==='模拟 AI 回复'));
  assert.equal(await page.evaluate(() => Object.keys(localStorage).some(k => /token|credential|deepseek/i.test(k))),false);
  await page.screenshot({path:'/private/tmp/leon-tools-pro-workspace.png'});
  await page.setViewportSize({width:390,height:844});
  await frame.getByRole('tab',{name:'集合',exact:true}).click();
  await frame.getByText('共享网页',{exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth > innerWidth + 2),false);
  await page.screenshot({path:'/private/tmp/leon-tools-pro-mobile.png'});
  assert.deepEqual(errors,[]); console.log('Tools Pro workspace checks passed.');
 } finally { await browser.close();server.close(); }
})().catch(error=>{console.error(error);process.exitCode=1;server.close();});
