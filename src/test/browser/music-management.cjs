const assert = require('node:assert/strict');
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE_PATH || 'playwright');
const root = path.resolve(__dirname, '../../main/resources/static');
let tracks = [];
let writes = 0;
let droppedUploads = [], nextUpload = 1, failUpload = false, uploadDelay = 0, inFlightUploads = 0, maxUploadConcurrency = 0;
let playlists = [];
let playlistWrites = 0;
let nextPlaylist = 1;
const id = '00000000-0000-0000-0000-000000000001';
const secondId = '00000000-0000-0000-0000-000000000002';
let failNextAddition = false;
let failNextOrder = false, conflictNextOrder = false, orderWrites = 0;
const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    if (url.pathname === '/api/monitor/page-view') { res.writeHead(204); return res.end(); }
    if (url.pathname.startsWith('/api/music/playlists')) {
        res.setHeader('Content-Type', 'application/json');
        const parts = url.pathname.split('/').filter(Boolean);
        if (req.method === 'GET') return res.end(JSON.stringify(playlists));
        assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        const chunks = []; req.on('data', value => chunks.push(value)); req.on('end', () => {
            const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
            let list = playlists.find(item => item.id === parts[3]);
            if (req.method === 'POST' && parts.length === 3) {
                list = { id: `10000000-0000-0000-0000-${String(nextPlaylist++).padStart(12, '0')}`, name: body.name, trackIds: [] };
                playlists.push(list); res.statusCode = 201;
            } else if (!list) { res.statusCode = 404; return res.end('{}'); }
            else if (req.method === 'PUT' && parts[4] === 'order') {
                if (failNextOrder) { failNextOrder = false; res.statusCode = 503; return res.end(JSON.stringify({ message: '临时排序失败，请重试。' })); }
                if (conflictNextOrder) { conflictNextOrder = false; list.trackIds.reverse(); res.statusCode = 409; return res.end(JSON.stringify({ message: '歌单已被修改，请刷新后重新排序' })); }
                assert.deepEqual(body.expectedTrackIds, list.trackIds);
                assert.deepEqual([...body.trackIds].sort(), [...list.trackIds].sort());
                list.trackIds = body.trackIds; orderWrites++;
            } else if (req.method === 'PUT') list.name = body.name;
            else if (req.method === 'POST' && parts[4] === 'tracks') {
                if (failNextAddition) { failNextAddition = false; res.statusCode = 503; return res.end(JSON.stringify({ message: '临时失败，请重试。' })); }
                list.trackIds = [...new Set([...list.trackIds, ...body.trackIds])];
            }
            else if (req.method === 'DELETE' && parts[4] === 'tracks') list.trackIds = list.trackIds.filter(id => id !== parts[5]);
            else if (req.method === 'DELETE') playlists = playlists.filter(item => item !== list);
            playlistWrites++; res.end(JSON.stringify(list));
        }); return;
    }
    if (url.pathname.endsWith('/artwork')) {
        if (url.pathname.includes(id)) { res.writeHead(200, { 'Content-Type': 'image/png' }); return res.end(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/aV8AAAAASUVORK5CYII=', 'base64')); }
        res.writeHead(204); return res.end();
    }
    if (url.pathname === '/api/music/tracks' && req.method === 'POST') {
        assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        assert.match(req.headers['content-type'], /^multipart\/form-data; boundary=/);
        const chunks = [];
        req.on('data', chunk => chunks.push(chunk));
        req.on('end', () => {
            const text = Buffer.concat(chunks).toString();
            const filename = text.match(/filename="([^"]+)"/)?.[1];
            assert.ok(filename, 'multipart upload contains a filename');
            inFlightUploads++; maxUploadConcurrency = Math.max(maxUploadConcurrency, inFlightUploads);
            const finish = () => {
                inFlightUploads--;
                if (failUpload) { failUpload = false; res.writeHead(503, { 'Content-Type': 'application/json' }); return res.end(JSON.stringify({ message: '临时上传失败，请重试。' })); }
                const song = filename === 'song.wav' ? { id, title: '<b>song</b>', artist: '测试歌手', album: '测试专辑', size: 44 }
                    : { id: `20000000-0000-0000-0000-${String(nextUpload++).padStart(12, '0')}`, title: filename, artist: '拖入歌手', album: '上传专辑', size: 16 };
                tracks.push(song); if (filename !== 'song.wav') droppedUploads.push(filename);
                writes++;
                res.writeHead(201, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify(song));
            };
            if (uploadDelay) setTimeout(finish, uploadDelay); else finish();
        });
        return;
    }
    if (url.pathname.startsWith('/api/music/tracks/') && req.method === 'DELETE') {
        const removedId = url.pathname.split('/').pop();
        assert.equal(req.headers['x-requested-with'], 'XMLHttpRequest');
        tracks = tracks.filter(track => track.id !== removedId);
        playlists.forEach(list => { list.trackIds = list.trackIds.filter(track => track !== removedId); });
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
        assert.equal(await page.locator('.music-heading, [data-playlist-filter]').count(), 0, 'no hero or redundant playlist dropdown');
        assert.ok(await page.locator('[data-selection-bar]').isHidden(), 'bulk actions are hidden with no selection');
        assert.ok(await page.locator('[data-playlist-actions]').isHidden(), 'playlist actions are hidden in full library');
        async function createPlaylist(name) {
            await page.getByRole('textbox', { name: '新歌单名称' }).fill(name);
            await page.getByRole('button', { name: '创建在线歌单', exact: true }).click();
            await page.locator('[data-playlist-status]').filter({ hasText: `已创建「${name}」` }).waitFor();
            await page.waitForFunction(() => !document.querySelector('[data-refresh]').disabled);
            return playlists.find(list => list.name === name).id;
        }
        const firstList = await createPlaylist('<b>放松</b>');
        await page.locator('select[name=playlistId]').selectOption(firstList);
        await page.locator('input[name=files]').setInputFiles({ name: 'song.wav', mimeType: 'audio/wav', buffer: Buffer.alloc(44) });
        await page.locator('input[name=artist]').fill('测试歌手');
        await page.locator('input[name=album]').fill('测试专辑');
        await page.getByRole('button', { name: '上传到曲库 ↗' }).click();
        await page.getByText('已上传 1 首。').waitFor();
        assert.equal(await page.locator('[data-tracks] strong').innerText(), '<b>song</b>');
        assert.equal(await page.locator('[data-tracks] strong b').count(), 0, 'song metadata must be text');
        await page.locator('.music-cover.has-image').waitFor();
        assert.equal(await page.locator('.music-cover img').getAttribute('src'), `/api/music/tracks/${id}/artwork`);
        assert.equal(await page.locator('audio').count(), 0, 'audio is attached only when preview is requested');
        assert.deepEqual(playlists[0].trackIds, [id], 'upload can join a server playlist');
        const secondList = await createPlaylist('通勤');
        const card = value => page.locator(`[data-playlist-id="${value}"]`);
        const handle = value => page.locator(`[data-track-id="${value}"] .music-drag-handle`);
        async function finishWrite() { await page.waitForFunction(() => !document.querySelector('[data-refresh]').disabled && !document.querySelector('[data-playlist-cards] button:disabled')); }
        async function drag(value, target) { await handle(value).dragTo(card(target)); await finishWrite(); }
        assert.equal(await page.locator('[data-tracks] tr').count(), 1, 'new playlists keep the source library visible');
        assert.equal(await card(secondList).getAttribute('aria-pressed'), 'false');
        assert.equal(await page.locator('[data-uploader]').getAttribute('open'), null, 'successful upload collapses its controls so songs and drop targets stay visible');
        const sourceBox = await handle(id).boundingBox(), targetBox = await card(secondList).boundingBox();
        await page.mouse.move(sourceBox.x + sourceBox.width / 2, sourceBox.y + sourceBox.height / 2); await page.mouse.down();
        await page.mouse.move(targetBox.x + targetBox.width / 2, targetBox.y + targetBox.height / 2, { steps: 12 });
        await card(secondList).locator('small').filter({ hasText: '松开加入 1 首歌曲' }).waitFor();
        assert.match(await card(secondList).getAttribute('class'), /is-drag-over/, 'drop target highlights before release');
        await page.waitForFunction(id => getComputedStyle(document.querySelector(`[data-playlist-id="${id}"]`)).borderTopColor === 'rgb(68, 134, 99)', secondList);
        assert.notEqual(await card(secondList).evaluate(node => getComputedStyle(node).boxShadow), 'none', 'drag highlight remains visible under the global theme');
        if (process.env.SCREENSHOT_PATH) await page.screenshot({ path: process.env.SCREENSHOT_PATH.replace('.png', '-hover.png') });
        await page.mouse.up(); await finishWrite();
        await page.getByText('已将 1 首歌曲加入「通勤」。').waitFor();
        assert.deepEqual(playlists[1].trackIds, [id]);
        const beforeDuplicate = playlistWrites;
        await drag(id, secondList);
        await page.getByText('所选 1 首歌曲已在「通勤」中，无需重复加入。').waitFor();
        assert.equal(playlistWrites, beforeDuplicate, 'duplicate drops do not create redundant writes');

        tracks.push({ id: secondId, title: 'Second song', artist: '第二位歌手', album: 'Album', size: 88 });
        await page.getByRole('button', { name: '刷新曲库', exact: true }).click(); await finishWrite();
        const thirdList = await createPlaylist('多选拖拽');
        await page.getByRole('checkbox', { name: '全选本页' }).check();
        await drag(id, thirdList);
        await page.getByText('已将 2 首歌曲加入「多选拖拽」。').waitFor();
        assert.deepEqual(playlists.find(list => list.id === thirdList).trackIds, [id, secondId]);
        assert.equal(await page.locator('[data-selection-count]').innerText(), '已选 0 首');
        assert.equal(await page.locator('.is-dragging, .is-drag-over, .is-drag-source').count(), 0, 'drop clears drag visuals');

        const thirdId = '00000000-0000-0000-0000-000000000003';
        tracks.push({ id: thirdId, title: 'Filtered track', artist: 'Other', album: 'Album', size: 88 });
        await page.getByRole('button', { name: '刷新曲库', exact: true }).click(); await finishWrite(); await drag(thirdId, thirdList);
        await card(thirdList).click();
        const rowIds = () => page.locator('[data-tracks] tr').evaluateAll(rows => rows.map(row => row.dataset.trackId));
        async function reorder(source, target, after) {
            const row = page.locator(`[data-track-id="${target}"]`), bounds = await row.boundingBox();
            await handle(source).dragTo(row, { targetPosition: { x: 60, y: bounds.height * (after ? .8 : .2) } });
            await finishWrite();
        }
        await reorder(secondId, thirdId, true);
        assert.deepEqual(await rowIds(), [id, thirdId, secondId]);
        assert.deepEqual(playlists.find(list => list.id === thirdList).trackIds, [id, thirdId, secondId]);
        await page.getByText('歌单顺序已保存，客户端刷新歌单后按新顺序播放。').waitFor();
        const beforeNoop = orderWrites; await handle(id).dragTo(page.locator(`[data-track-id="${id}"]`));
        assert.equal(orderWrites, beforeNoop, 'dropping on the same song does not save');
        await page.locator(`[data-track-id="${secondId}"]`).getByRole('button', { name: '上移 Second song', exact: true }).click(); await finishWrite();
        assert.deepEqual(await rowIds(), [id, secondId, thirdId]);
        await page.locator('[data-search]').fill('song');
        await reorder(id, secondId, true);
        assert.deepEqual(playlists.find(list => list.id === thirdList).trackIds, [secondId, id, thirdId], 'filtered drag retains hidden tracks');
        await page.locator('[data-search]').fill('');
        await page.locator(`[data-track-id="${id}"] input[type=checkbox]`).check();
        await page.locator(`[data-track-id="${thirdId}"] input[type=checkbox]`).check();
        failNextOrder = true; await reorder(thirdId, secondId, false);
        await page.locator('[data-playlist-status].is-error').filter({ hasText: '临时排序失败，请重试。' }).waitFor();
        assert.deepEqual(await rowIds(), [secondId, id, thirdId], 'failed reorder retains saved order');
        await reorder(thirdId, secondId, false);
        assert.deepEqual(await rowIds(), [id, thirdId, secondId], 'selected block moves in original playlist order');
        await page.getByRole('checkbox', { name: '全选本页' }).check(); await page.getByRole('checkbox', { name: '全选本页' }).uncheck();
        conflictNextOrder = true; await reorder(id, secondId, true);
        await page.locator('[data-playlist-status].is-error').filter({ hasText: '歌单已被修改，请刷新后重新排序' }).waitFor();
        assert.deepEqual(await rowIds(), [secondId, thirdId, id], 'conflict refreshes the server order instead of overwriting it');
        await page.reload(); await page.locator('[data-tracks] tr').first().waitFor(); await card(thirdList).click();
        assert.deepEqual(await rowIds(), [secondId, thirdId, id], 'reordered list survives page reload');
        await drag(thirdId, firstList);
        assert.deepEqual(playlists.find(list => list.id === thirdList).trackIds, [secondId, thirdId, id], 'copy to another playlist does not reorder its source');
        await page.locator('[data-all-tracks]').click(); page.once('dialog', dialog => dialog.accept());
        await page.locator(`[data-track-id="${thirdId}"]`).getByRole('button', { name: '删除', exact: true }).click(); await finishWrite();

        failNextAddition = true;
        await drag(secondId, secondList);
        await page.locator('[data-playlist-status].is-error').filter({ hasText: '临时失败，请重试。' }).waitFor();
        assert.deepEqual(playlists[1].trackIds, [id], 'failed drops keep membership unchanged');
        assert.equal(await page.locator('[data-tracks] tr').count(), 2, 'failed drops retain source songs');
        await drag(secondId, secondList);
        await page.getByText('已将 1 首歌曲加入「通勤」。').waitFor();
        assert.deepEqual(playlists[1].trackIds, [id, secondId]);

        const beforeForeign = playlistWrites;
        const foreign = await page.evaluateHandle(() => { const data = new DataTransfer(); data.setData('application/x-sonora-tracks', '["00000000-0000-0000-0000-000000000002"]'); return data; });
        await card(firstList).dispatchEvent('drop', { dataTransfer: foreign }); await foreign.dispose();
        assert.equal(playlistWrites, beforeForeign, 'external payloads cannot add arbitrary songs');
        const start = await handle(id).boundingBox();
        await page.mouse.move(start.x + start.width / 2, start.y + start.height / 2); await page.mouse.down();
        await page.mouse.move(start.x + 45, start.y + 15, { steps: 8 });
        await page.locator('.is-dragging').waitFor();
        await page.keyboard.press('Escape'); await page.mouse.up();
        await page.waitForFunction(() => !document.querySelector('.is-dragging, .is-drag-over, .is-drag-source'));
        assert.equal(playlistWrites, beforeForeign, 'cancelled drags do not write');

        for (const width of [1440, 800, 390]) {
            await page.setViewportSize({ width, height: 900 });
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'drag layout should not overflow');
        }
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.evaluate(() => scrollTo(0, 0));
        if (process.env.SCREENSHOT_PATH) await page.screenshot({ path: process.env.SCREENSHOT_PATH, fullPage: true });
        page.once('dialog', dialog => dialog.accept());
        await page.locator(`[data-track-id="${secondId}"]`).getByRole('button', { name: '删除', exact: true }).click(); await finishWrite();
        await page.locator(`[data-playlist-id="${thirdList}"]`).click();
        page.once('dialog', dialog => dialog.accept()); await page.getByRole('button', { name: '删除歌单', exact: true }).click(); await finishWrite();
        // Keep the existing select-and-add path as a keyboard/touch alternative.
        await page.locator(`[data-playlist-id="${firstList}"]`).click();
        await page.locator('[data-tracks]').getByRole('button', { name: '移出歌单', exact: true }).click(); await finishWrite();
        await page.locator('[data-all-tracks]').click();
        assert.ok(await page.locator('[data-playlist-actions]').isHidden());
        await page.getByRole('checkbox', { name: '全选本页' }).check();
        assert.ok(await page.locator('[data-selection-bar]').isVisible());
        await page.getByRole('button', { name: '取消选择', exact: true }).click();
        assert.ok(await page.locator('[data-selection-bar]').isHidden());
        assert.ok(!(await page.getByRole('checkbox', { name: '全选本页' }).isChecked()));
        await page.getByRole('checkbox', { name: '全选本页' }).check();
        await page.locator('[data-destination]').selectOption(firstList);
        await page.getByRole('button', { name: '将所选歌曲加入歌单' }).click(); await finishWrite();
        assert.deepEqual(playlists[0].trackIds, [id], 'batch add remains available');
        await page.locator(`[data-playlist-id="${firstList}"]`).click();
        page.once('dialog', dialog => dialog.accept('夜间'));
        await page.getByRole('button', { name: '重命名歌单', exact: true }).click();
        await page.getByText('歌单已重命名。').waitFor();
        await page.waitForFunction(() => !document.querySelector('[data-refresh]').disabled);
        assert.equal(playlists[0].name, '夜间');
        await page.locator('[data-tracks]').getByRole('button', { name: '移出歌单', exact: true }).click();
        await page.getByText('这张在线歌单还没有歌曲。从全部曲库勾选歌曲后加入。').waitFor();
        assert.equal(tracks.length, 1, 'removing membership keeps audio'); assert.deepEqual(playlists[1].trackIds, [id]);
        page.once('dialog', dialog => dialog.accept());
        await page.getByRole('button', { name: '删除歌单', exact: true }).click();
        await page.getByText('歌单已删除，歌曲仍保留在曲库。').waitFor();
        await page.waitForFunction(() => !document.querySelector('[data-refresh]').disabled);
        assert.equal(tracks.length, 1); assert.equal(playlists.length, 1);
        await page.locator('[data-search]').fill('不存在');
        await page.getByText('没有匹配的歌曲。').waitFor();
        await page.locator('[data-search]').fill('测试歌手');
        assert.equal(await page.locator('[data-tracks] tr').count(), 1);
        for (const width of [1440, 800]) {
            await page.setViewportSize({ width, height: 900 });
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'page should not overflow');
        }
        await page.setViewportSize({ width: 1440, height: 1000 });
        await page.evaluate(() => scrollTo(0, 0));
        if (process.env.SCREENSHOT_PATH) await page.screenshot({ path: process.env.SCREENSHOT_PATH, fullPage: true });
        page.on('dialog', dialog => dialog.accept());
        await page.locator('[data-tracks]').getByRole('button', { name: '删除', exact: true }).click();
        await page.getByText('曲库还是空的，上传第一首歌曲吧。').waitFor();
        assert.equal(await page.locator('.music-heading, [data-playlist-filter]').count(), 0, 'no hero or redundant playlist dropdown');
        assert.ok(await page.locator('[data-selection-bar]').isHidden(), 'bulk actions are hidden with no selection');
        assert.ok(await page.locator('[data-playlist-actions]').isHidden(), 'playlist actions are hidden in full library');
        assert.equal(writes, 4);
        assert.ok(playlistWrites >= 6);
        assert.deepEqual(playlists[0].trackIds, [], 'deleting a song clears memberships');
        await page.reload();
        await page.getByText('曲库还是空的，上传第一首歌曲吧。').waitFor();
        assert.equal(await page.locator('.music-heading, [data-playlist-filter]').count(), 0, 'no hero or redundant playlist dropdown');
        assert.ok(await page.locator('[data-selection-bar]').isHidden(), 'bulk actions are hidden with no selection');
        assert.ok(await page.locator('[data-playlist-actions]').isHidden(), 'playlist actions are hidden in full library');
        const pageSongs = Array.from({ length: 55 }, (_, index) => ({ id: `20000000-0000-0000-0000-${String(index + 1).padStart(12, '0')}`, title: `分页歌曲 ${String(index + 1).padStart(2, '0')}`, artist: '分页歌手', album: '测试专辑', size: 1024 }));
        tracks = pageSongs;
        await page.getByRole('button', { name: '刷新曲库', exact: true }).click(); await finishWrite();
        const pagedList = await createPlaylist('分页整理');
        assert.deepEqual(await page.locator('[data-page-size] option').evaluateAll(options => options.map(option => option.value)), ['10', '20', '50']);
        assert.equal(await page.locator('[data-tracks] tr').count(), 10);
        assert.equal(await page.locator('[data-page-label]').innerText(), '第 1 / 6 页');
        await page.getByRole('checkbox', { name: '全选本页' }).check();
        await page.getByRole('button', { name: '下一页', exact: true }).click();
        assert.equal(await page.locator('[data-tracks] tr').first().getAttribute('data-track-id'), pageSongs[10].id);
        assert.equal(await page.getByRole('checkbox', { name: '全选本页' }).isChecked(), false);
        await page.locator('[data-tracks] tr').first().locator('input[type=checkbox]').check();
        assert.equal(await page.locator('[data-selection-count]').innerText(), '已选 11 首');
        await page.getByRole('button', { name: '将所选歌曲加入歌单' }).click(); await finishWrite();
        assert.deepEqual(playlists.find(list => list.id === pagedList).trackIds, pageSongs.slice(0, 11).map(song => song.id), 'batch addition keeps selections across pages');
        playlists.find(list => list.id === pagedList).trackIds = pageSongs.map(song => song.id);
        await page.getByRole('button', { name: '刷新曲库', exact: true }).click(); await finishWrite(); await card(pagedList).click();
        await page.locator(`[data-track-id="${pageSongs[9].id}"]`).getByRole('button', { name: `下移 ${pageSongs[9].title}`, exact: true }).click(); await finishWrite();
        const savedOrder = playlists.find(list => list.id === pagedList).trackIds;
        assert.equal(savedOrder.length, 55); assert.equal(savedOrder[10], pageSongs[9].id);
        assert.deepEqual(savedOrder.slice(11), pageSongs.slice(11).map(song => song.id), 'moving at a page boundary retains unseen members and order');
        await page.getByRole('button', { name: '下一页', exact: true }).click();
        await page.locator('[data-search]').fill('分页歌曲 01');
        assert.equal(await page.locator('[data-tracks] tr').count(), 1); assert.equal(await page.locator('[data-page-label]').innerText(), '第 1 / 1 页');
        await page.locator('[data-search]').fill('');
        for (let step = 0; step < 5; step++) await page.getByRole('button', { name: '下一页', exact: true }).click();
        assert.equal(await page.locator('[data-tracks] tr').count(), 5);
        tracks = pageSongs.slice(0, 50);
        playlists.find(list => list.id === pagedList).trackIds = savedOrder.filter(id => tracks.some(song => song.id === id));
        await page.getByRole('button', { name: '刷新曲库', exact: true }).click(); await finishWrite();
        assert.equal(await page.locator('[data-page-label]').innerText(), '第 5 / 5 页'); assert.equal(await page.locator('[data-tracks] tr').count(), 10);
        await page.locator('[data-page-size]').selectOption('20');
        assert.equal(await page.locator('[data-tracks] tr').count(), 20); assert.equal(await page.locator('[data-page-label]').innerText(), '第 1 / 3 页');
        await page.locator('[data-page-size]').selectOption('50'); assert.equal(await page.locator('[data-tracks] tr').count(), 50);
        await page.reload(); await page.locator('[data-tracks] tr').first().waitFor();
        assert.equal(await page.locator('[data-page-size]').inputValue(), '50'); assert.equal(await page.locator('[data-tracks] tr').count(), 50);
        await page.locator('[data-page-size]').selectOption('10');
        await page.setViewportSize({ width: 1644, height: 1100 }); await page.evaluate(() => scrollTo(0, 0));
        const rail = await page.locator('.lw-sidebar').boundingBox(), music = await page.locator('.music-content').boundingBox();
        assert.ok(rail.width <= 80, 'music page uses a compact navigation rail');
        assert.ok(music.x < 100 && music.x + music.width >= 1640, 'music content uses the available page width');
        if (process.env.SCREENSHOT_PATH) await page.screenshot({ path: process.env.SCREENSHOT_PATH, fullPage: true });
        // Real File objects exercise OS-file drag/drop separately from internal row sorting.
        async function filesTransfer(names) {
            return page.evaluateHandle(names => {
                const data = new DataTransfer();
                for (const name of names) data.items.add(new File(name.startsWith('empty') ? [] : ['ID3 fixture audio'], name, { type: name.endsWith('.txt') ? 'text/plain' : 'audio/mpeg' }));
                return data;
            }, names);
        }
        async function dropFiles(names, selector = '.music-content') {
            const transfer = await filesTransfer(names);
            const node = page.locator(selector);
            await node.dispatchEvent('dragenter', { dataTransfer: transfer });
            await node.dispatchEvent('dragover', { dataTransfer: transfer });
            assert.ok(await page.locator('[data-file-drop]').isVisible(), 'file drag shows upload target');
            await node.dispatchEvent('drop', { dataTransfer: transfer }); await transfer.dispose();
        }
        async function finishUpload() { await page.waitForFunction(() => !document.querySelector('[data-upload] button[type=submit]').disabled); }
        const originalSongs = tracks.map(song => song.id), memberships = playlists.map(list => [...list.trackIds]);
        await dropFiles(['direct.mp3'], '[data-tracks] tr:first-child'); await finishUpload();
        assert.deepEqual(droppedUploads, ['direct.mp3']);
        assert.ok(originalSongs.every(id => tracks.some(song => song.id === id)), 'direct uploads preserve existing songs');
        assert.deepEqual(playlists.map(list => list.trackIds), memberships, 'library drops do not alter playlists');
        assert.ok(await page.locator('[data-file-drop]').isHidden(), 'drop clears overlay');
        const dropList = playlists[0].id;
        await dropFiles(['batch-one.mp3', 'batch-two.MP3'], `[data-playlist-id="${dropList}"]`); await finishUpload();
        assert.deepEqual(droppedUploads.slice(-2), ['batch-one.mp3', 'batch-two.MP3']);
        const uploadedIds = tracks.filter(song => song.title.startsWith('batch-')).map(song => song.id);
        assert.deepEqual(playlists[0].trackIds.slice(-2), uploadedIds, 'dropping MP3 files on a playlist appends them in file order');
        await page.locator(`[data-playlist-id="${dropList}"]`).click();
        await dropFiles(['inside.mp3'], '.music-library'); await finishUpload();
        assert.ok(playlists[0].trackIds.includes(tracks.find(song => song.title === 'inside.mp3').id), 'dropping in an open playlist adds the uploaded track to that playlist');
        await page.locator('[data-all-tracks]').click();
        assert.equal(maxUploadConcurrency, 1, 'multi-file uploads run sequentially');
        const beforeInvalid = writes;
        await dropFiles(['notes.txt', 'empty.mp3']);
        assert.equal(writes, beforeInvalid, 'unsupported/empty files never reach server');
        assert.match(await page.locator('[data-status]').innerText(), /不支持的音频格式/);
        assert.match(await page.locator('[data-status]').innerText(), /不能为空/);
        await dropFiles(['mixed.mp3', 'cover.txt']); await finishUpload();
        assert.ok(droppedUploads.includes('mixed.mp3'));
        assert.match(await page.locator('[data-status]').innerText(), /已上传 1 首.*不支持的音频格式/);
        failNextAddition = true;
        await dropFiles(['kept.mp3'], `[data-playlist-id="${dropList}"]`); await finishUpload();
        const kept = tracks.find(song => song.title === 'kept.mp3');
        assert.ok(kept && !playlists[0].trackIds.includes(kept.id), 'failed playlist attachment preserves uploaded audio in the library');
        assert.match(await page.locator('[data-status]').innerText(), /已上传.*加入歌单失败/);
        failUpload = true;
        await dropFiles(['retry.mp3']); await finishUpload();
        assert.match(await page.locator('[data-status]').innerText(), /临时上传失败/);
        assert.equal(droppedUploads.includes('retry.mp3'), false);
        await dropFiles(['retry.mp3']); await finishUpload(); assert.equal(droppedUploads.includes('retry.mp3'), true);
        uploadDelay = 400;
        await dropFiles(['slow.mp3']);
        assert.ok(await page.locator('[data-upload] button[type=submit]').isDisabled());
        await page.locator('[data-status]').filter({ hasText: '等待服务器处理' }).waitFor();
        assert.ok(await page.locator('[data-upload-progress]').isVisible(), 'upload shows progress until server responds');
        assert.equal(await page.locator('[data-upload-progress]').getAttribute('value'), '100');
        await dropFiles(['blocked.mp3']);
        assert.match(await page.locator('[data-status]').innerText(), /等待当前任务完成/);
        await finishUpload(); assert.ok(await page.locator('[data-upload-progress]').isHidden()); uploadDelay = 0;
        assert.equal(droppedUploads.includes('blocked.mp3'), false, 'dropping again during an upload does not start a second batch');
        const abandoned = await filesTransfer(['cancel.mp3']);
        await page.locator('.music-content').dispatchEvent('dragenter', { dataTransfer: abandoned });
        await page.keyboard.press('Escape'); assert.ok(await page.locator('[data-file-drop]').isHidden()); await abandoned.dispose();
        await page.evaluate(() => { HTMLMediaElement.prototype.play = () => new Promise(() => {}); });
        await page.locator('[data-tracks] button').filter({ hasText: '▶ 播放' }).first().click();
        for (const width of [2200, 1440, 800, 390]) {
            await page.setViewportSize({ width, height: 900 });
            const audioWidth = (await page.locator('[data-tracks] audio').first().boundingBox()).width;
            assert.ok(audioWidth >= 360 && audioWidth <= 640, 'preview keeps an extended seek area');
            if (width === 2200) assert.ok(audioWidth >= 600, 'wide screens get a longer preview');
            assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'extended preview scrolls inside table on small screens');
        }
        await page.setViewportSize({ width: 1644, height: 1100 });
        if (process.env.SCREENSHOT_PATH) await page.screenshot({ path: process.env.SCREENSHOT_PATH.replace('.png', '-file-upload.png'), fullPage: true });
        await page.locator('.lw-sidebar').getByRole('link', { name: '高级设置', exact: true }).click();
        await page.waitForFunction(() => !document.body.classList.contains('is-music-page'));
        assert.ok((await page.locator('.lw-sidebar').boundingBox()).width > 200, 'other workspace pages keep their normal navigation');
        const inactiveWrites = writes, inactiveFiles = await filesTransfer(['inactive.mp3']);
        await page.locator('body').dispatchEvent('drop', { dataTransfer: inactiveFiles }); await inactiveFiles.dispose();
        assert.equal(writes, inactiveWrites, 'inactive music view does not intercept other pages');
        assert.deepEqual(errors, []);
        console.log('Music management checks passed (MP3 page/playlist drops, sequential multi-file upload, invalid/empty files, failure/retry, busy/cancel/inactive handling, extended preview; pagination 10/20/50, cross-page selection, boundaries, full-width layout; reorder, filtered/multi moves, no-op, failure/conflict, reload and copy): single/multi drag, duplicates, errors/retry, cancellation, external payloads, batch alternative and responsive layout.');
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });
