const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const record = { id: 7, sourceType: 'JSON', bodyJson: '{"hello":"world"}', rawText: '{"hello":"world"}', createTime:'2026-10-31T10:00:00' };
let rows=[{id:1,kvKey:'GitHub',kvValue:'https://github.com',kvType:'url',kvCategory:'开发'}];
const server = http.createServer((req, res) => {
 const url = new URL(req.url, 'http://localhost');
 if (url.pathname.startsWith('/api/')) {
  if(req.method==='POST') {
   let body='';req.on('data',data=>body+=data);req.on('end',()=>{
    const item=JSON.parse(body);
    if(url.pathname.endsWith('/save') && url.pathname.includes('simple-kv')) {
     if(item.id) rows=rows.map(row=>row.id===item.id?{...row,...item}:row);else rows.push({...item,id:2});
    }
    if(url.pathname.endsWith('/delete'))rows=rows.filter(row=>row.id!==item.id);
    res.setHeader('Content-Type','application/json');res.end(JSON.stringify({status:'success'}));
   });return;
  }
  let value=[];
  if(url.pathname==='/api/simple-kv/list')value=rows;
  if(url.pathname==='/api/simple-kv/icons')value=['github.png','日历.png'];
  if(url.pathname==='/api/options')value=[{value:'1',text:'测试配置'}];
  if(url.pathname==='/api/text'){res.setHeader('Content-Type','text/plain');return res.end('保存前的配置');}
  if(url.pathname==='/api/json-record/search')value=[{id:7,sourceType:'JSON',preview:'hello world',createTime:record.createTime}];
  if(url.pathname==='/api/json-record/7')value=record;
  if(req.method==='POST')value={status:'success'};
  res.setHeader('Content-Type','application/json');return res.end(JSON.stringify(value));
 }
 const file=path.join(root,url.pathname==='/'?'index.html':decodeURIComponent(url.pathname));
 if(!file.startsWith(root+path.sep)){res.writeHead(403);return res.end();}
 try {res.setHeader('Content-Type',file.endsWith('.css')?'text/css':file.endsWith('.js')?'application/javascript':'text/html; charset=utf-8');res.end(fs.readFileSync(file));}
 catch{res.writeHead(404);res.end();}
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH,args:['--no-sandbox']});
 try {
  const page=await browser.newPage({viewport:{width:1440,height:1000}});page.setDefaultTimeout(5000);const errors=[];page.on('pageerror',e=>errors.push(e.message));
  const base='http://127.0.0.1:'+server.address().port;
  await page.goto(base+'/simple-kv.html',{waitUntil:'networkidle'});
  await page.evaluate(()=>document.querySelector('.lw-sidebar').dataset.identity='persistent');
  await page.locator('#addKey').fill('未提交的快捷项');
  await page.locator('.lw-sidebar').getByRole('link',{name:'配置编辑',exact:true}).click();
  await page.waitForURL('**/diamond.html');
  assert.equal(await page.locator('.lw-sidebar').getAttribute('data-identity').catch(()=>null),'persistent','切换页签导致工作空间框架丢失');
  await page.waitForFunction(()=>document.querySelector('#configStatus')?.textContent.includes('已加载'));
  await page.locator('#editableText').fill('未提交的配置');
  await page.locator('.lw-sidebar').getByRole('link',{name:'快捷项',exact:true}).click();
  assert.equal(await page.locator('#addKey').inputValue(),'未提交的快捷项','切换导致快捷项草稿丢失');
  await page.locator('.kv-item').getByText('编辑',{exact:true}).click();
  await page.locator('#editValue').fill('https://github.com/new');
  await page.locator('#editIconPicker .icon-picker-trigger').click();
  await page.locator('#editIconSearch').fill('日历');
  await page.locator('#editIconDropdown .icon-picker-item').getByText('日历').click();
  await page.locator('#editModal [data-ws-click="saveEditKV"]').click();
  await page.waitForFunction(()=>document.querySelector('.kv-item-value')?.textContent.includes('github.com/new'));
  assert.equal(rows[0].kvIcon,'日历.png','编辑时未保存图标');
  await page.locator('#addValue').fill('草稿内容');
  await page.locator('#addIconPicker .icon-picker-trigger').click();
  await page.locator('#addIconSearch').fill('github');
  await page.locator('#addIconDropdown .icon-picker-item').getByText('github').click();
  await page.locator('.kv-add-button').click();
  await page.waitForFunction(()=>document.querySelector('#countLabel')?.textContent.includes('2'));
  assert.equal(rows[1].kvIcon,'github.png','新建时未保存图标');
  await page.locator('.kv-item').filter({hasText:'未提交的快捷项'}).getByText('删除',{exact:true}).click();
  await page.locator('#deleteModal [data-ws-click="confirmDeleteKV"]').click();
  await page.waitForFunction(()=>document.querySelector('#countLabel')?.textContent.includes('1'));
  await page.locator('.lw-sidebar').getByRole('link',{name:'配置编辑',exact:true}).click();
  assert.equal(await page.locator('#editableText').inputValue(),'未提交的配置','切换导致配置草稿丢失');
  await page.locator('[data-ws-click="saveText"]').click();
  await page.waitForFunction(()=>document.querySelector('#configStatus')?.textContent.includes('修改已保存'));
  await page.locator('.lw-sidebar').getByRole('link',{name:'JSON 记录',exact:true}).click();
  await page.locator('.result-item').first().click();await page.waitForURL('**/json-record.html?id=7');
  assert.equal(await page.locator('.lw-sidebar').getAttribute('data-identity'),'persistent');
  await page.waitForFunction(()=>document.querySelector('#jsonLayout')?.textContent.includes('hello'));
  await page.goBack();await page.waitForURL('**/json-record-search.html');assert.equal(await page.locator('.result-item').count(),1);
  await page.reload({waitUntil:'networkidle'});assert.equal(await page.locator('.lw-sidebar').count(),1,'直接刷新没有工作空间框架');
  assert.deepEqual(errors,[]);
  if(process.env.SCREENSHOT_PATH) {
   await page.waitForFunction(()=>document.querySelector('.ws-pane:not([hidden])'));
   await page.waitForTimeout(200);
   await page.screenshot({path:process.env.SCREENSHOT_PATH,fullPage:true});
   for (const [file,label] of [['config','配置编辑'],['kv','快捷项']]) {
    await page.locator('.lw-sidebar').getByRole('link',{name:label,exact:true}).click();
    await page.waitForTimeout(200);
    await page.screenshot({path:process.env.SCREENSHOT_PATH.replace('.png','-'+file+'.png'),fullPage:true});
   }
  }
  if(process.env.SCREENSHOT_PATH) {
   await page.locator('.lw-sidebar').getByRole('link',{name:'JSON 记录',exact:true}).click();
   await page.waitForTimeout(200);
   await page.screenshot({path:process.env.SCREENSHOT_PATH.replace('.png','-list.png'),fullPage:true});
   await page.locator('.result-item').first().click();
   await page.waitForFunction(()=>document.querySelector('#jsonLayout')?.textContent.includes('hello'));
   await page.locator('#searchKeyword').fill('hello');
   await page.waitForFunction(()=>document.querySelector('#searchCount')?.textContent.includes('1'));
   await page.screenshot({path:process.env.SCREENSHOT_PATH.replace('.png','-detail.png'),fullPage:true});
  }
  console.log('PASS: persistent shell, draft retention, JSON detail, history, refresh, no script collisions');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e.message);server.close();process.exitCode=1});
