const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
const record = { id: 7, sourceType: 'JSON', bodyJson: '{"hello":"world"}', rawText: '{"hello":"world"}', createTime:'2026-10-31T10:00:00' };
let pageViews=0;
let deploymentJob=null, deploymentRequests=0;
let deploymentRecords = [
 {id:'release-new',startedAt:'2026-11-11T16:00:00Z',finishedAt:'2026-11-11T16:01:05Z',version:'2026.11.12.000000',commit:'bbd8d1f42d7853278c4bda200ec78f0fac430234',dirty:true,description:'<img src=x onerror=alert(1)>',status:'ROLLED_BACK'},
 {id:'release-current',startedAt:'2026-11-11T15:00:00Z',finishedAt:'2026-11-11T15:00:30Z',version:'2026.11.11.230000',commit:'0fb051e',dirty:false,description:'监控页面更新',status:'SUCCESS'},
];
let rows=[{id:1,kvKey:'GitHub',kvValue:'https://github.com',kvType:'url',kvCategory:'开发'}];
const server = http.createServer((req, res) => {
 const url = new URL(req.url, 'http://localhost');
 if (url.pathname==='/api/monitor/page-view') { pageViews++; res.writeHead(204); return res.end(); }
 if (url.pathname==='/api/monitor/deploy' && req.method==='POST') {
  assert.equal(req.headers['x-requested-with'],'XMLHttpRequest');
  deploymentRequests++;
  deploymentJob={id:'web-release-test',stage:'QUEUED',message:'部署已排队。',startedAt:new Date().toISOString()};
  res.writeHead(202,{'Content-Type':'application/json'});res.end(JSON.stringify(deploymentJob));
  setTimeout(()=>{deploymentJob={...deploymentJob,stage:'SUCCESS',message:'部署成功，服务器已更新。'};},500);
  return;
 }
 if (url.pathname==='/api/monitor') {
  res.setHeader('Content-Type','application/json');
  const current={time:new Date().toISOString(),cpuPercent:25,memoryPercent:50,memoryUsedBytes:1073741824,memoryTotalBytes:2147483648,diskPercent:40,diskUsedBytes:40,diskTotalBytes:100};
  return res.end(JSON.stringify({current,samples:[current],pageViews:[{day:'2026-10-31',views:pageViews}],deployments:{currentId:'release-current',records:deploymentRecords,total:deploymentRecords.length,available:true,unreadable:0},deploymentControl:{enabled:true,busy:deploymentJob?.stage==='QUEUED',job:deploymentJob}}));
 }
 if (url.pathname==='/api/admin/database-backup') {
  res.setHeader('Content-Type','application/sql; charset=utf-8');
  res.setHeader('Content-Disposition','attachment; filename=\"test-backup.sql\"');
  return res.end('-- database backup\n');
 }
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
  assert.equal(await page.locator('a[href="/diamond.html"]').count(),0,'仍存在配置编辑入口');
  await page.locator('.lw-sidebar').getByRole('link',{name:'JSON 记录',exact:true}).click();
  await page.waitForURL('**/json-record-search.html');
  assert.equal(await page.locator('.lw-sidebar').getAttribute('data-identity').catch(()=>null),'persistent','切换页签导致工作空间框架丢失');
  await page.locator('.lw-sidebar').getByRole('link',{name:'快捷项',exact:true}).click();
  assert.equal(await page.locator('#addKey').inputValue(),'未提交的快捷项','切换导致快捷项草稿丢失');
  await page.setViewportSize({width:800,height:600});
  await page.locator('.kv-item').getByText('编辑',{exact:true}).click();
  await page.locator('#editValue').fill('https://github.com/new');
  await page.locator('#editIconPicker .icon-picker-trigger').click();
  const bounds=await page.locator('#editIconDropdown').evaluate(menu=>{
   const dropdown=menu.getBoundingClientRect();const modal=menu.closest('.modal').getBoundingClientRect();
   return {top:dropdown.top,bottom:dropdown.bottom,left:dropdown.left,right:dropdown.right,
    modalTop:modal.top,modalBottom:modal.bottom,modalLeft:modal.left,modalRight:modal.right,viewportHeight:window.innerHeight};
  });
  assert.ok(bounds.top>=bounds.modalTop && bounds.bottom<=bounds.modalBottom && bounds.left>=bounds.modalLeft &&
   bounds.right<=bounds.modalRight && bounds.top>=0 && bounds.bottom<=bounds.viewportHeight,'图标选择列表被弹窗裁切');
  await page.locator('#editIconSearch').fill('日历');
  await page.locator('#editIconDropdown .icon-picker-item').getByText('日历').click();
  await page.locator('#editModal [data-ws-click="saveEditKV"]').click();
  await page.setViewportSize({width:1440,height:1000});
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
   for (const [file,label] of [['kv','快捷项']]) {
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
  const beforeMonitor=pageViews;
  await page.locator('.lw-sidebar').getByRole('link',{name:'服务器监控',exact:true}).click();
  await page.waitForURL('**/monitor.html');
  await page.waitForFunction(() => ['cpu','memory','disk','pv'].every(name => document.querySelector(`[data-chart="${name}"] canvas`)) && document.querySelector('[data-value="pv"]')?.textContent !== '—');
  assert.equal(await page.locator('[data-value="cpu"]').textContent(),'25.0%');
  assert.equal(await page.locator('[data-value="memory"]').textContent(),'50.0%');
  assert.equal(await page.locator('[data-deployments] tr').count(),2);
  assert.equal(await page.locator('[data-deployments] tr').first().locator('td').nth(2).textContent(),'<img src=x onerror=alert(1)>');
  assert.equal(await page.locator('[data-deployments] img').count(),0,'部署说明应作为文本显示');
  assert.match(await page.locator('[data-deployments] time').first().textContent(),/2026\/11\/12 00:00:00/,'部署时间应使用北京时间');
  assert.equal(await page.locator('[data-deployments] tr').first().locator('td').last().textContent(),'1 分 5 秒');
  assert.equal(await page.locator('[data-deployments] tr').first().locator('.monitor-deployment-result').textContent(),'已回滚');
  assert.equal(await page.locator('.monitor-current-release').locator('..').locator('strong').textContent(),'2026.11.11.230000','回滚后当前运行版本标记错误');
  await page.getByRole('button',{name:'一键部署',exact:true}).click();
  await page.getByText('部署已排队。',{exact:true}).waitFor();
  assert.equal(await page.locator('.monitor-deploy').isDisabled(),true,'部署期间应禁止重复点击');
  await page.getByText('部署成功，服务器已更新。',{exact:true}).waitFor({timeout:10000});
  assert.equal(deploymentRequests,1);
  assert.equal(await page.locator('.monitor-deploy').isEnabled(),true);
  await page.setViewportSize({width:800,height:900});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'部署列表使页面横向溢出');
  await page.setViewportSize({width:1440,height:1000});
  const chartData = await page.evaluate(() => Object.fromEntries(['cpu', 'memory', 'disk', 'pv'].map(name => {
   const option = window.echarts.getInstanceByDom(document.querySelector(`[data-chart="${name}"]`)).getOption();
   return [name, { series: option.series.map(series => ({ name: series.name, value: series.data[0] })) }];
  })));
  assert.deepEqual(chartData.cpu.series.map(item => item.name), ['CPU']);
  assert.deepEqual(chartData.memory.series.map(item => item.name), ['内存']);
  assert.deepEqual(chartData.disk.series.map(item => item.name), ['磁盘']);
  assert.equal(chartData.cpu.series[0].value[1], 25);
  assert.equal(chartData.memory.series[0].value[1], 50);
  assert.equal(chartData.disk.series[0].value[1], 40);
  assert.deepEqual(chartData.pv.series.map(item => item.name), ['浏览量']);
  await page.setViewportSize({width: 1920, height: 1000});
  const monitorBounds = await page.locator('.monitor-page').evaluate(element => {
   const page = element.getBoundingClientRect();
   const main = element.closest('.lw-main').getBoundingClientRect();
   const cards = element.querySelector('.monitor-cards').getBoundingClientRect();
   return { mainLeft: main.left, mainRight: main.right, pageLeft: page.left, pageRight: page.right, cardsLeft: cards.left, cardsRight: cards.right };
  });
  assert.ok(Math.abs(monitorBounds.pageLeft - monitorBounds.mainLeft) < 1 && Math.abs(monitorBounds.pageRight - monitorBounds.mainRight) < 1, '监控页面未铺满工作区');
  assert.ok(monitorBounds.cardsLeft - monitorBounds.mainLeft <= 30 && monitorBounds.mainRight - monitorBounds.cardsRight <= 30, '监控内容两侧留白过大');
  await page.setViewportSize({width: 1440, height: 1000});
  await page.waitForFunction(previous => document.querySelector('[data-value="pv"]')?.textContent === String(previous+1), beforeMonitor);
  await page.reload({waitUntil:'networkidle'});
  await page.waitForFunction(() => document.querySelector('[data-chart="pv"] canvas'));
  assert.equal(await page.getByRole('heading',{name:'服务器监控.'}).count(),1);
  await page.locator('.lw-sidebar').getByRole('link',{name:'高级设置',exact:true}).click();
  await page.waitForURL('**/advanced-settings.html');
  const [download]=await Promise.all([page.waitForEvent('download'),page.getByRole('button',{name:'下载 SQL 备份'}).click()]);
  assert.equal(download.suggestedFilename(),'test-backup.sql');
  await page.reload({waitUntil:'networkidle'});
  assert.equal(await page.getByRole('heading',{name:'数据库备份'}).count(),1);
  await page.goto(base+'/diamond.html');
  await page.waitForURL('**/index.html');
  assert.equal(await page.locator('a[href="/diamond.html"]').count(),0,'总览仍存在配置编辑入口');
  assert.equal(await page.locator('.lw-module-card').count(),2);
  console.log('PASS: persistent shell, draft retention, JSON detail, history, refresh, no script collisions');
 }finally{await browser.close();server.close();}
})().catch(e=>{console.error(e.message);server.close();process.exitCode=1});
