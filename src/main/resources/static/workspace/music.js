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
        selectionChanged();
    }
    async function writePlaylist(action) {
        if (writing) return;
        writing = true; renderPlaylists();
        try { await action(); await refresh(); }
        catch (error) { playlistStatus.textContent = error.message; }
        finally { writing = false; renderPlaylists(); }
    }

    let loading = false;
    let refreshPromise = Promise.resolve();
    let loaded = false;

    async function checked(response) {
        if (response.ok) return response;
        let message = response.status === 413 ? '文件超过服务器上传限制，请检查反向代理的上传大小设置。' : '操作失败，请稍后重试。';
        try { message = (await response.json()).message || message; } catch { /* May be a proxy error page. */ }
        throw new Error(message);
    }

    function render() {
        const query = find('[data-search]').value.trim().toLocaleLowerCase();
        rows.replaceChildren();
        const list = playlists.find(item => item.id === playlistFilter.value);
        const byId = new Map(tracks.map(track => [track.id, track]));
        const songs = list ? list.trackIds.map(id => byId.get(id)).filter(Boolean) : tracks;
        visible = songs.filter(track => `${track.title} ${track.artist} ${track.album}`.toLocaleLowerCase().includes(query));
        find('[data-count]').textContent = `· ${tracks.length} 首`;
        if (!visible.length) listStatus.textContent = !list && !tracks.length ? '曲库还是空的，上传第一首歌曲吧。' : query ? '没有匹配的歌曲。' : list ? '这张在线歌单还没有歌曲。从全部曲库勾选歌曲后加入。' : '没有匹配的歌曲。';
        else listStatus.textContent = '';
        for (const track of visible) {
            const row = document.createElement('tr');
            const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.checked = selected.has(track.id);
            checkbox.setAttribute('aria-label', `选择 ${track.title}`);
            checkbox.addEventListener('change', () => { if (checkbox.checked) selected.add(track.id); else selected.delete(track.id); selectionChanged(); });
            row.insertCell().append(checkbox);
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
            if (!failures.length) form.reset();
            status.textContent = `已上传 ${uploaded} 首。${failures.join('；')}`;
            await refresh();
        } finally { for (const element of form.elements) element.disabled = false; }
    });
    find('[data-create-playlist]').addEventListener('submit', event => {
        event.preventDefault(); const name = event.currentTarget.elements.name.value.trim(); if (!name) return;
        writePlaylist(async () => {
            const list = await (await checked(await fetch('/api/music/playlists', { method: 'POST', ...json({ name }) }))).json();
            playlists.push(list); renderPlaylists(); playlistFilter.value = list.id;
            find('[data-create-playlist]').reset(); selected.clear(); playlistStatus.textContent = `已创建「${list.name}」。`;
        });
    });
    playlistFilter.addEventListener('change', () => { selected.clear(); renderPlaylists(); render(); });
    destination.addEventListener('change', selectionChanged);
    find('[data-select-all]').addEventListener('change', event => {
        for (const track of visible) { if (event.target.checked) selected.add(track.id); else selected.delete(track.id); }
        render();
    });
    find('[data-add-selected]').addEventListener('click', () => {
        const id = destination.value, trackIds = [...selected]; if (!id || !trackIds.length) return;
        writePlaylist(async () => {
            for (let offset = 0; offset < trackIds.length; offset += 1000) {
                await checked(await fetch(`${endpoint(id)}/tracks`, { method: 'POST', ...json({ trackIds: trackIds.slice(offset, offset + 1000) }) }));
            }
            selected.clear(); playlistStatus.textContent = '所选歌曲已加入在线歌单，重复歌曲会自动跳过。';
        });
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
    return { activate() { if (!loaded) refresh(); }, deactivate() { root.querySelectorAll('audio').forEach(audio => audio.pause()); } };
}
