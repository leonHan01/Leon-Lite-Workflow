export function mount(root) {
    const find = selector => root.querySelector(selector);
    const form = find('[data-upload]');
    const status = find('[data-status]');
    const listStatus = find('[data-list-status]');
    const rows = find('[data-tracks]');
    let tracks = [];
    let loading = false;
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
        const visible = tracks.filter(track => `${track.title} ${track.artist} ${track.album}`.toLocaleLowerCase().includes(query));
        find('[data-count]').textContent = `· ${tracks.length} 首`;
        if (!visible.length) listStatus.textContent = tracks.length ? '没有匹配的歌曲。' : '曲库还是空的，上传第一首歌曲吧。';
        else listStatus.textContent = '';
        for (const track of visible) {
            const row = document.createElement('tr');
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
                    render();
                } catch (error) { listStatus.textContent = error.message; remove.disabled = false; }
            });
            row.insertCell().append(remove);
            rows.append(row);
        }
    }

    async function refresh() {
        if (loading) return;
        loading = true;
        find('[data-refresh]').disabled = true;
        listStatus.textContent = '正在加载曲库…';
        try {
            tracks = await (await checked(await fetch('/api/music/tracks'))).json();
            loaded = true;
            render();
        } catch (error) { listStatus.textContent = error.message; }
        finally { loading = false; find('[data-refresh]').disabled = false; }
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
                    await checked(await fetch('/api/music/tracks', { method: 'POST', body }));
                    uploaded++;
                } catch (error) { failures.push(`${file.name}：${error.message}`); }
            }
            if (!failures.length) form.reset();
            status.textContent = `已上传 ${uploaded} 首。${failures.join('；')}`;
            await refresh();
        } finally { for (const element of form.elements) element.disabled = false; }
    });
    find('[data-refresh]').addEventListener('click', refresh);
    find('[data-search]').addEventListener('input', render);
    return { activate() { if (!loaded) refresh(); }, deactivate() { root.querySelectorAll('audio').forEach(audio => audio.pause()); } };
}
