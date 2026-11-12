export function mount(root) {
    const find = selector => root.querySelector(selector);
    const form = find('[data-upload]');
    const status = find('[data-status]');
    const listStatus = find('[data-list-status]');
    const rows = find('[data-tracks]');
    let tracks = [];
    let playlists = [];
    const selected = new Set();
    let visible = [];
    let writing = false;
    const playlistStatus = find('[data-playlist-status]');
    const playlistFilter = find('[data-playlist-filter]');
    const destination = find('[data-destination]');
    const endpoint = id => `/api/music/playlists/${encodeURIComponent(id)}`;
    const json = value => ({ headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(value) });
    const cards = new Map();
    const dragType = 'application/x-sonora-tracks';
    const dragHint = find('[data-drag-hint]');
    const defaultDragHint = dragHint.textContent;
    let draggedIds = [];
    let dragPlaylistId = '';
    let dragPlaylistOrder = [];
    let dragPreview = null;
    let newPlaylistId = null;
    let pendingPlaylistId = null;
    let lastDropResult = null;

    function report(message, error = false) {
        playlistStatus.textContent = message;
        playlistStatus.classList.toggle('is-error', error);
    }
    function selectPlaylist(id) {
        playlistFilter.value = id;
        selected.clear(); renderPlaylists(); render();
    }
    function acceptsDrag(event) {
        return !writing && draggedIds.length > 0 && [...(event.dataTransfer?.types || [])].includes(dragType);
    }
    function clearDrag() {
        draggedIds = [];
        dragPlaylistId = ''; dragPlaylistOrder = [];
        root.classList.remove('is-dragging');
        root.querySelectorAll('.is-drag-source').forEach(row => row.classList.remove('is-drag-source'));
        cards.forEach(card => card.classList.remove('is-drag-over'));
        clearSortMarker();
        dragPreview?.remove(); dragPreview = null;
        dragHint.textContent = defaultDragHint;
        renderPlaylistCards();
    }
    function clearSortMarker() {
        rows.querySelectorAll('.is-sort-before, .is-sort-after').forEach(row => row.classList.remove('is-sort-before', 'is-sort-after'));
    }
    function sortTarget(event) {
        if (!acceptsDrag(event) || !dragPlaylistId || playlistFilter.value !== dragPlaylistId) return null;
        const row = event.target.closest('tr[data-track-id]');
        if (!row || !rows.contains(row) || draggedIds.includes(row.dataset.trackId)) return null;
        const rect = row.getBoundingClientRect();
        return { row, after: event.clientY >= rect.top + rect.height / 2 };
    }
    async function savePlaylistOrder(id, trackIds, expectedTrackIds) {
        if (writing || (trackIds.length === expectedTrackIds.length && trackIds.every((track, index) => expectedTrackIds[index] === track))) return;
        report('正在保存歌单顺序…');
        await writePlaylist(async () => {
            const updated = await (await checked(await fetch(`${endpoint(id)}/order`, { method: 'PUT', ...json({ trackIds, expectedTrackIds }) }))).json();
            playlists = playlists.map(item => item.id === id ? updated : item);
            renderPlaylists(); render();
            lastDropResult = { id, message: '顺序已保存' };
            report('歌单顺序已保存，客户端刷新歌单后按新顺序播放。');
        });
    }
    function moveTrack(id, direction) {
        const list = playlists.find(item => item.id === playlistFilter.value);
        if (!list || writing) return;
        const next = [...list.trackIds], index = next.indexOf(id), target = index + direction;
        if (index < 0 || target < 0 || target >= next.length) return;
        [next[index], next[target]] = [next[target], next[index]];
        savePlaylistOrder(list.id, next, [...list.trackIds]);
    }
    function updateSortControls() {
        rows.setAttribute('aria-busy', String(writing));
        rows.querySelectorAll('[data-move-track]').forEach(button => { button.disabled = writing || button.dataset.boundary === 'true'; });
    }
    function renderPlaylistCards() {
        const container = find('[data-playlist-cards]');
        for (const [id, card] of cards) {
            if (!playlists.some(list => list.id === id)) { card.remove(); cards.delete(id); }
        }
        for (const list of playlists) {
            let card = cards.get(list.id);
            if (!card) {
                card = document.createElement('button'); card.type = 'button'; card.className = 'music-playlist-card';
                card.dataset.playlistId = list.id;
                card.append(document.createElement('strong'), document.createElement('small'));
                card.addEventListener('click', () => selectPlaylist(list.id));
                card.addEventListener('dragover', event => {
                    if (!acceptsDrag(event)) {
                        if ([...(event.dataTransfer?.types || [])].includes('Files')) { event.preventDefault(); event.dataTransfer.dropEffect = 'none'; }
                        return;
                    }
                    event.preventDefault(); event.dataTransfer.dropEffect = 'copy';
                    card.classList.add('is-drag-over');
                    card.querySelector('small').textContent = `松开加入 ${draggedIds.length} 首歌曲`;
                });
                card.addEventListener('dragleave', event => {
                    if (!card.contains(event.relatedTarget)) { card.classList.remove('is-drag-over'); renderPlaylistCards(); }
                });
                card.addEventListener('drop', event => {
                    event.preventDefault(); event.stopPropagation();
                    const ids = acceptsDrag(event) ? [...draggedIds] : [];
                    clearDrag();
                    if (ids.length) addTracksToPlaylist(list.id, ids);
                    else if (!writing) report('请从曲库拖入已上传的歌曲；也可以勾选歌曲后批量加入。');
                });
                cards.set(list.id, card); container.append(card);
            }
            card.querySelector('strong').textContent = list.name;
            const feedback = lastDropResult?.id === list.id ? lastDropResult.message : null;
            card.querySelector('small').textContent = pendingPlaylistId === list.id ? '正在加入…' : feedback ? `${list.trackIds.length} 首歌曲 · ${feedback}` : (newPlaylistId === list.id ? '新建歌单 · 拖入歌曲' : `${list.trackIds.length} 首歌曲 · 拖入添加`);
            card.disabled = writing;
            card.setAttribute('aria-pressed', String(playlistFilter.value === list.id));
            card.setAttribute('aria-busy', String(pendingPlaylistId === list.id));
            card.setAttribute('aria-label', `${list.name}，${list.trackIds.length} 首歌曲，拖入歌曲加入`);
            card.classList.toggle('is-new', newPlaylistId === list.id);
        }
        find('[data-playlist-empty]').hidden = playlists.length > 0;
        find('[data-all-tracks]').setAttribute('aria-pressed', String(!playlistFilter.value));
        find('[data-library-count]').textContent = String(tracks.length);
    }

    async function addTracksToPlaylist(id, trackIds) {
        if (writing) return;
        const list = playlists.find(item => item.id === id);
        if (!list) { report('目标歌单已移除，请刷新后重试。', true); return; }
        const valid = new Set(tracks.map(track => track.id));
        const ids = [...new Set(trackIds)].filter(track => valid.has(track));
        if (!ids.length) { report('这些歌曲已不在曲库中，请刷新后重试。', true); return; }
        const existing = new Set(list.trackIds);
        const incoming = ids.filter(track => !existing.has(track));
        if (!incoming.length) {
            lastDropResult = { id, message: '这些歌曲已在歌单中' };
            report(`所选 ${ids.length} 首歌曲已在「${list.name}」中，无需重复加入。`);
            renderPlaylistCards(); return;
        }
        pendingPlaylistId = id; lastDropResult = null;
        let added = 0;
        const success = await writePlaylist(async () => {
            for (let offset = 0; offset < incoming.length; offset += 1000) {
                const chunk = incoming.slice(offset, offset + 1000);
                const updated = await (await checked(await fetch(`${endpoint(id)}/tracks`, { method: 'POST', ...json({ trackIds: chunk }) }))).json();
                playlists = playlists.map(item => item.id === id ? updated : item);
                chunk.forEach(track => selected.delete(track)); added += chunk.length;
            }
            ids.forEach(track => selected.delete(track));
            if (newPlaylistId === id) newPlaylistId = null;
            lastDropResult = { id, message: `已加入 ${added} 首` };
            report(`已将 ${added} 首歌曲加入「${list.name}」。${ids.length > incoming.length ? `已跳过 ${ids.length - incoming.length} 首重复歌曲。` : ''}`);
        });
        pendingPlaylistId = null;
        if (!success) {
            lastDropResult = { id, message: '加入未完成 · 可重试' };
            if (added) report(`已加入 ${added} 首，其余歌曲加入失败，请重试。`, true);
        }
        renderPlaylists();
    }
    function selectionChanged() {
        find('[data-selection-count]').textContent = `已选 ${selected.size} 首`;
        find('[data-add-selected]').disabled = writing || !selected.size || !destination.value;
        const all = find('[data-select-all]');
        all.checked = visible.length > 0 && visible.every(track => selected.has(track.id));
        all.indeterminate = !all.checked && visible.some(track => selected.has(track.id));
        all.disabled = !visible.length;
    }
    function options(select, empty) {
        const prior = select.value;
        select.replaceChildren(new Option(empty, ''));
        for (const list of playlists) select.add(new Option(`${list.name} · ${list.trackIds.length} 首`, list.id));
        select.value = playlists.some(list => list.id === prior) ? prior : '';
    }
    function renderPlaylists() {
        options(playlistFilter, '全部在线曲库');
        options(destination, '选择目标在线歌单');
        options(form.elements.playlistId, '仅上传到曲库');
        find('[data-rename-playlist]').disabled = !playlistFilter.value || writing;
        find('[data-delete-playlist]').disabled = !playlistFilter.value || writing;
        find('[data-create-playlist] button').disabled = writing;
        renderPlaylistCards();
        selectionChanged();
    }
    async function writePlaylist(action) {
        if (writing) return false;
        writing = true; renderPlaylists();
        updateSortControls();
        playlistStatus.classList.remove('is-error');
        try { await action(); await refresh(); return true; }
        catch (error) {
            report(error.message, true);
            if (error.status === 409) await refresh();
            return false;
        }
        finally { writing = false; renderPlaylists(); updateSortControls(); }
    }

    let loading = false;
    let refreshPromise = Promise.resolve();
    let loaded = false;

    async function checked(response) {
        if (response.ok) return response;
        let message = response.status === 413 ? '文件超过服务器上传限制，请检查反向代理的上传大小设置。' : '操作失败，请稍后重试。';
        try { message = (await response.json()).message || message; } catch { /* May be a proxy error page. */ }
        const error = new Error(message); error.status = response.status; throw error;
    }

    function render() {
        const query = find('[data-search]').value.trim().toLocaleLowerCase();
        rows.replaceChildren();
        const list = playlists.find(item => item.id === playlistFilter.value);
        const byId = new Map(tracks.map(track => [track.id, track]));
        const songs = list ? list.trackIds.map(id => byId.get(id)).filter(Boolean) : tracks;
        find('[data-order-hint]').hidden = !list;
        visible = songs.filter(track => `${track.title} ${track.artist} ${track.album}`.toLocaleLowerCase().includes(query));
        find('[data-library-title]').textContent = list?.name || '在线曲库';
        find('[data-count]').textContent = `· ${songs.length} 首`;
        if (!visible.length) listStatus.textContent = !list && !tracks.length ? '曲库还是空的，上传第一首歌曲吧。' : query ? '没有匹配的歌曲。' : list ? '这张在线歌单还没有歌曲。从全部曲库勾选歌曲后加入。' : '没有匹配的歌曲。';
        else listStatus.textContent = '';
        for (const track of visible) {
            const row = document.createElement('tr');
            row.dataset.trackId = track.id; row.draggable = true;
            let dragBlocked = false;
            row.addEventListener('pointerdown', event => { dragBlocked = Boolean(event.target.closest('audio, input, button, select, a')); });
            row.addEventListener('dragstart', event => {
                if (dragBlocked || writing || loading || !event.dataTransfer) { event.preventDefault(); return; }
                draggedIds = selected.has(track.id) ? [...selected] : [track.id];
                dragPlaylistId = list?.id || ''; dragPlaylistOrder = list ? [...list.trackIds] : [];
                event.dataTransfer.effectAllowed = list ? 'copyMove' : 'copy';
                event.dataTransfer.setData(dragType, JSON.stringify(draggedIds));
                event.dataTransfer.setData('text/plain', draggedIds.length === 1 ? track.title : `${draggedIds.length} 首歌曲`);
                dragPreview = document.createElement('div'); dragPreview.className = 'music-drag-preview';
                dragPreview.textContent = `♫ ${draggedIds.length === 1 ? track.title : `${draggedIds.length} 首歌曲`}  ${list ? '↕' : '＋'}`;
                document.body.append(dragPreview); event.dataTransfer.setDragImage(dragPreview, 16, 16);
                root.classList.add('is-dragging');
                rows.querySelectorAll('tr').forEach(item => item.classList.toggle('is-drag-source', draggedIds.includes(item.dataset.trackId)));
                dragHint.textContent = list ? `正在移动 ${draggedIds.length} 首歌曲，上下拖动排序；拖到歌单卡片则加入该歌单。` : `正在拖动 ${draggedIds.length} 首歌曲，松开到目标歌单即可加入。`;
            });
            row.addEventListener('dragend', clearDrag);
            const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.checked = selected.has(track.id);
            checkbox.setAttribute('aria-label', `选择 ${track.title}`);
            checkbox.addEventListener('change', () => { if (checkbox.checked) selected.add(track.id); else selected.delete(track.id); selectionChanged(); });
            const selectionCell = row.insertCell(); selectionCell.className = 'music-track-select';
            const handle = document.createElement('span'); handle.className = 'music-drag-handle'; handle.textContent = '⠿'; handle.setAttribute('aria-hidden', 'true'); handle.title = '拖动歌曲加入歌单';
            selectionCell.append(handle, checkbox);
            if (list) {
                const index = document.createElement('span'); index.className = 'music-track-index';
                index.textContent = String(list.trackIds.indexOf(track.id) + 1); selectionCell.append(index);
            }
            const song = row.insertCell();
            const title = document.createElement('strong');
            title.textContent = track.title;
            const artist = document.createElement('small');
            artist.textContent = track.artist;
            song.append(title, artist);
            row.insertCell().textContent = track.album;
            row.insertCell().textContent = `${(track.size / 1024 / 1024).toFixed(1)} MB`;
            const audio = document.createElement('audio');
            audio.controls = true;
            audio.preload = 'none';
            audio.src = `/api/music/tracks/${encodeURIComponent(track.id)}/audio`;
            audio.setAttribute('aria-label', `试听 ${track.title}`);
            audio.addEventListener('play', () => root.querySelectorAll('audio').forEach(other => { if (other !== audio) other.pause(); }));
            audio.addEventListener('error', () => { listStatus.textContent = '无法试听，请检查登录状态及浏览器音频格式支持。'; });
            row.insertCell().append(audio);
            const remove = document.createElement('button');
            remove.type = 'button';
            remove.textContent = '删除';
            remove.addEventListener('click', async () => {
                if (!confirm(`删除《${track.title}》？服务器上的音频也会删除。客户端已缓存的副本仍可离线播放。`)) return;
                remove.disabled = true;
                try {
                    await checked(await fetch(`/api/music/tracks/${encodeURIComponent(track.id)}`, { method: 'DELETE' }));
                    tracks = tracks.filter(item => item.id !== track.id);
                    selected.delete(track.id);
                    await refresh();
                } catch (error) { listStatus.textContent = error.message; remove.disabled = false; }
            });
            const actions = row.insertCell();
            if (list) {
                const position = list.trackIds.indexOf(track.id);
                for (const [direction, symbol, label] of [[-1, '↑', '上移'], [1, '↓', '下移']]) {
                    const move = document.createElement('button'); move.type = 'button'; move.textContent = symbol;
                    move.dataset.moveTrack = track.id;
                    move.dataset.boundary = String(direction < 0 ? position === 0 : position === list.trackIds.length - 1);
                    move.setAttribute('aria-label', `${label} ${track.title}`); move.title = `${label}一首`;
                    move.addEventListener('click', () => moveTrack(track.id, direction)); actions.append(move);
                }
                const detach = document.createElement('button'); detach.type = 'button'; detach.textContent = '移出歌单';
                detach.addEventListener('click', () => writePlaylist(async () => {
                    await checked(await fetch(`${endpoint(list.id)}/tracks/${encodeURIComponent(track.id)}`, { method: 'DELETE' }));
                    selected.delete(track.id); playlistStatus.textContent = '已移出歌单，歌曲仍保留在曲库。';
                }));
                actions.append(detach);
            }
            actions.append(remove);
            rows.append(row);
        }
        updateSortControls();
        selectionChanged();
    }

    async function refresh() {
        if (loading) { await refreshPromise; return refresh(); }
        let finish;
        refreshPromise = new Promise(resolve => { finish = resolve; });
        loading = true;
        find('[data-refresh]').disabled = true;
        listStatus.textContent = '正在加载曲库…';
        try {
            const [songs, lists] = await Promise.all(['/api/music/tracks', '/api/music/playlists'].map(async path => (await checked(await fetch(path))).json()));
            tracks = songs; playlists = lists;
            if (!loaded) find('[data-uploader]').open = tracks.length === 0;
            for (const id of selected) if (!tracks.some(track => track.id === id)) selected.delete(id);
            renderPlaylists();
            loaded = true;
            render();
        } catch (error) { listStatus.textContent = error.message; }
        finally { loading = false; find('[data-refresh]').disabled = false; finish(); }
    }

    async function duration(file) {
        const url = URL.createObjectURL(file);
        const audio = new Audio();
        try {
            return await new Promise(resolve => {
                const timeout = setTimeout(() => finish(0), 3000);
                function finish(value) { clearTimeout(timeout); audio.onloadedmetadata = null; audio.onerror = null; resolve(value); }
                audio.onloadedmetadata = () => finish(Number.isFinite(audio.duration) ? audio.duration : 0);
                audio.onerror = () => finish(0);
                audio.src = url;
            });
        } finally { audio.removeAttribute('src'); audio.load(); URL.revokeObjectURL(url); }
    }

    form.addEventListener('submit', async event => {
        event.preventDefault();
        const files = [...form.elements.files.files];
        if (!files.length) return;
        if (files.some(file => !file.size || file.size > 128 * 1024 * 1024)) { status.textContent = '歌曲不能为空，且单首不能超过 128 MB。'; return; }
        const artist = form.elements.artist.value;
        const album = form.elements.album.value;
        const playlistId = form.elements.playlistId.value;
        for (const element of form.elements) element.disabled = true;
        let uploaded = 0;
        const failures = [];
        try {
            for (const [index, file] of files.entries()) {
                status.textContent = `正在上传 ${index + 1} / ${files.length}：${file.name}`;
                try {
                    const body = new FormData();
                    body.append('file', file);
                    body.append('artist', artist);
                    body.append('album', album);
                    body.append('duration', String(await duration(file)));
                    const song = await (await checked(await fetch('/api/music/tracks', { method: 'POST', body }))).json();
                    uploaded++;
                    if (playlistId) {
                        try { await checked(await fetch(`${endpoint(playlistId)}/tracks`, { method: 'POST', ...json({ trackIds: [song.id] }) })); }
                        catch (error) { failures.push(`${file.name} 已上传，但加入歌单失败：${error.message}`); }
                    }
                } catch (error) { failures.push(`${file.name}：${error.message}`); }
            }
            if (!failures.length) { form.reset(); find('[data-uploader]').open = false; }
            status.textContent = `已上传 ${uploaded} 首。${failures.join('；')}`;
            await refresh();
        } finally { for (const element of form.elements) element.disabled = false; }
    });
    find('[data-create-playlist]').addEventListener('submit', event => {
        event.preventDefault(); const name = event.currentTarget.elements.name.value.trim(); if (!name) return;
        writePlaylist(async () => {
            const list = await (await checked(await fetch('/api/music/playlists', { method: 'POST', ...json({ name }) }))).json();
            playlists.push(list); newPlaylistId = list.id; renderPlaylists();
            playlistFilter.value = ''; destination.value = list.id; find('[data-search]').value = '';
            find('[data-create-playlist]').reset(); selected.clear();
            report(`已创建「${list.name}」。从曲库拖入歌曲即可加入。`);
        });
    });
    playlistFilter.addEventListener('change', () => selectPlaylist(playlistFilter.value));
    find('[data-all-tracks]').addEventListener('click', () => selectPlaylist(''));
    destination.addEventListener('change', selectionChanged);
    find('[data-select-all]').addEventListener('change', event => {
        for (const track of visible) { if (event.target.checked) selected.add(track.id); else selected.delete(track.id); }
        render();
    });
    find('[data-add-selected]').addEventListener('click', () => {
        const id = destination.value, trackIds = [...selected]; if (!id || !trackIds.length) return;
        addTracksToPlaylist(id, trackIds);
    });
    find('[data-rename-playlist]').addEventListener('click', () => {
        const list = playlists.find(item => item.id === playlistFilter.value); if (!list) return;
        const name = prompt('新的歌单名称', list.name); if (!name?.trim()) return;
        writePlaylist(async () => { await checked(await fetch(endpoint(list.id), { method: 'PUT', ...json({ name: name.trim() }) })); playlistStatus.textContent = '歌单已重命名。'; });
    });
    find('[data-delete-playlist]').addEventListener('click', () => {
        const list = playlists.find(item => item.id === playlistFilter.value);
        if (!list || !confirm(`删除歌单「${list.name}」？歌曲和音频仍保留在曲库中。`)) return;
        writePlaylist(async () => { await checked(await fetch(endpoint(list.id), { method: 'DELETE' })); selected.clear(); playlistStatus.textContent = '歌单已删除，歌曲仍保留在曲库。'; });
    });
    find('[data-refresh]').addEventListener('click', refresh);
    find('[data-search]').addEventListener('input', render);
    rows.addEventListener('dragover', event => {
        const target = sortTarget(event);
        clearSortMarker();
        if (!target) return;
        event.preventDefault(); event.dataTransfer.dropEffect = 'move';
        target.row.classList.add(target.after ? 'is-sort-after' : 'is-sort-before');
    });
    rows.addEventListener('dragleave', event => { if (!rows.contains(event.relatedTarget)) clearSortMarker(); });
    rows.addEventListener('drop', event => {
        event.preventDefault();
        const target = sortTarget(event), id = dragPlaylistId, expected = [...dragPlaylistOrder], moving = new Set(draggedIds);
        clearDrag();
        if (!target) return;
        const moved = expected.filter(track => moving.has(track)), remaining = expected.filter(track => !moving.has(track));
        const index = remaining.indexOf(target.row.dataset.trackId);
        if (index < 0 || !moved.length) return;
        remaining.splice(index + (target.after ? 1 : 0), 0, ...moved);
        savePlaylistOrder(id, remaining, expected);
    });
    return { activate() { if (!loaded) refresh(); }, deactivate() { clearDrag(); root.querySelectorAll('audio').forEach(audio => audio.pause()); } };
}
