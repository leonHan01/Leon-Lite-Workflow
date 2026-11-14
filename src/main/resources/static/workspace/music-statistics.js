export function mount(root) {
    const find = selector => root.querySelector(selector);
    const status = find('[data-status]');
    const results = find('[data-results]');
    const refreshButton = find('[data-refresh]');
    const number = new Intl.NumberFormat('zh-CN');
    let sequence = 0;

    function ranking(container, items, detail) {
        container.replaceChildren();
        const max = items[0]?.value || 1;
        items.forEach((item, index) => {
            const row = document.createElement('li');
            const rank = document.createElement('span'); rank.className = 'music-stats-rank'; rank.textContent = String(index + 1).padStart(2, '0');
            const content = document.createElement('div'); content.className = 'music-stats-item';
            const title = document.createElement('strong'); title.textContent = item.label;
            const subtitle = document.createElement('small'); subtitle.textContent = item.subtitle || '';
            const bar = document.createElement('div'); bar.className = 'music-stats-bar';
            const fill = document.createElement('span'); fill.style.width = `${item.value / max * 100}%`; bar.append(fill);
            content.append(title, subtitle, bar);
            const value = document.createElement('b'); value.textContent = `${number.format(item.value)} ${detail}`;
            row.append(rank, content, value); container.append(row);
        });
    }
    async function refresh() {
        const ticket = ++sequence;
        refreshButton.disabled = true; status.textContent = '正在加载统计数据…';
        try {
            const response = await fetch('/api/music/tracks', { cache: 'no-store' });
            if (!response.ok) throw new Error('统计数据加载失败，请重试。');
            const tracks = await response.json();
            if (ticket !== sequence) return;
            const artists = new Map(), albums = new Set();
            let plays = 0;
            for (const track of tracks) {
                const artist = track.artist?.trim() || '未知艺术家';
                artists.set(artist, (artists.get(artist) || 0) + 1);
                if (track.album?.trim() && track.album.trim() !== '未知专辑') albums.add(`${artist}\u0000${track.album.trim()}`);
                plays += Math.max(0, Number(track.playCount) || 0);
            }
            find('[data-total]').textContent = number.format(tracks.length);
            find('[data-artists]').textContent = number.format(artists.size);
            find('[data-albums]').textContent = number.format(albums.size);
            find('[data-plays]').textContent = number.format(plays);
            const songs = tracks.map(track => ({ label: track.title || '未知歌曲', subtitle: track.artist || '未知艺术家', value: Math.max(0, Number(track.playCount) || 0) })).filter(track => track.value > 0)
                .sort((a, b) => b.value - a.value || a.label.localeCompare(b.label, 'zh-CN'));
            const artistItems = [...artists].map(([label, value]) => ({ label, value })).sort((a, b) => b.value - a.value || a.label.localeCompare(b.label, 'zh-CN'));
            ranking(find('[data-play-ranking]'), songs, '次'); ranking(find('[data-artist-ranking]'), artistItems, '首');
            find('[data-play-empty]').hidden = songs.length > 0; find('[data-artist-empty]').hidden = artistItems.length > 0;
            results.hidden = false; status.textContent = '';
        } catch (error) {
            if (ticket === sequence) { results.hidden = true; status.textContent = error.message || '统计数据加载失败，请重试。'; }
        } finally { if (ticket === sequence) refreshButton.disabled = false; }
    }
    refreshButton.addEventListener('click', refresh);
    return { activate: refresh, deactivate() { sequence++; refreshButton.disabled = false; } };
}
