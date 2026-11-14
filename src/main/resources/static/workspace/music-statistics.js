let chartLibrary;
function loadCharts() {
    if (window.echarts) return Promise.resolve(window.echarts);
    if (!chartLibrary) chartLibrary = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = '/workspace/vendor/echarts-5.6.0.min.js';
        script.onload = () => window.echarts ? resolve(window.echarts) : reject(new Error('图表组件加载失败。'));
        script.onerror = () => { script.remove(); reject(new Error('图表组件加载失败。')); };
        document.head.append(script);
    }).catch(error => { chartLibrary = null; throw error; });
    return chartLibrary;
}
const countValue = value => Number.isFinite(Number(value)) ? Math.max(0, Number(value)) : 0;
export function summarize(tracks, now = new Date()) {
    const artists = new Map(), albums = new Set(), formats = new Map();
    const months = Array.from({ length: 12 }, (_, index) => {
        const date = new Date(now.getFullYear(), now.getMonth() - 11 + index, 1);
        return { key: `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`, count: 0 };
    });
    const monthMap = new Map(months.map(month => [month.key, month]));
    let plays = 0, played = 0, duration = 0, durationKnown = 0, size = 0, sizeKnown = 0;
    for (const track of tracks) {
        const artist = track.artist?.trim() || '未知艺术家';
        artists.set(artist, (artists.get(artist) || 0) + 1);
        if (track.album?.trim() && track.album.trim() !== '未知专辑') albums.add(`${artist}\u0000${track.album.trim()}`);
        const format = track.extension?.trim().toUpperCase() || '未知格式';
        formats.set(format, (formats.get(format) || 0) + 1);
        const count = countValue(track.playCount); plays += count; if (count > 0) played++;
        const seconds = countValue(track.duration); if (seconds > 0) { duration += seconds; durationKnown++; }
        const bytes = countValue(track.size); if (bytes > 0) { size += bytes; sizeKnown++; }
        if (track.uploadedAt) {
            const date = new Date(track.uploadedAt);
            if (!Number.isNaN(date.getTime())) {
                const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
                const month = monthMap.get(key); if (month) month.count++;
            }
        }
    }
    return { artists, albums, formats, months, plays, played, duration, durationKnown, size, sizeKnown };
}
const colors = ['#4f9871', '#9ebf98', '#d1ab77', '#848fb8', '#d28f82', '#97b7b9', '#b7a4be', '#c8d2c9'];
function readableBytes(bytes) {
    if (!bytes) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB']; let unit = 0;
    while (bytes >= 1024 && unit < units.length - 1) { bytes /= 1024; unit++; }
    return `${new Intl.NumberFormat('zh-CN', { maximumFractionDigits: unit ? 1 : 0 }).format(bytes)} ${units[unit]}`;
}
function readableDuration(seconds) {
    if (!seconds) return '—';
    const minutes = Math.round(seconds / 60);
    return minutes === 0 ? '不足 1 分钟' : minutes < 60 ? `${minutes} 分钟` : `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`;
}
export function mount(root) {
    const find = selector => root.querySelector(selector);
    const status = find('[data-status]'), results = find('[data-results]'), refreshButton = find('[data-refresh]');
    const number = new Intl.NumberFormat('zh-CN');
    const charts = new Map();
    let sequence = 0, active = false;
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
    function legend(selector, values) {
        const container = find(selector); container.replaceChildren();
        values.forEach((item, index) => {
            const row = document.createElement('li');
            const dot = document.createElement('i'); dot.style.backgroundColor = colors[index % colors.length]; dot.setAttribute('aria-hidden', 'true');
            const name = document.createElement('span'); name.textContent = item.name;
            const value = document.createElement('strong'); value.textContent = `${number.format(item.value)} 首`;
            row.append(dot, name, value); container.append(row);
        });
    }
    function resize() { if (active) charts.forEach(chart => chart.resize()); }
    function renderChart(echarts, name, option, show) {
        const element = find(`[data-chart="${name}"]`);
        element.hidden = !show;
        if (!show) { charts.get(name)?.clear(); return; }
        let chart = charts.get(name);
        if (!chart) { chart = echarts.init(element, null, { renderer: 'canvas' }); charts.set(name, chart); }
        chart.setOption(option, true); chart.resize();
    }
    async function refresh() {
        const ticket = ++sequence;
        refreshButton.disabled = true; status.textContent = '正在加载统计数据…';
        try {
            const response = await fetch('/api/music/tracks', { cache: 'no-store' });
            if (!response.ok) throw new Error('统计数据加载失败，请重试。');
            const tracks = await response.json();
            if (!Array.isArray(tracks)) throw new Error('统计数据格式有误，请重试。');
            if (ticket !== sequence || !active) return;
            const data = summarize(tracks);
            find('[data-total]').textContent = number.format(tracks.length);
            find('[data-artists]').textContent = number.format(data.artists.size);
            find('[data-albums]').textContent = number.format(data.albums.size);
            find('[data-plays]').textContent = number.format(data.plays);
            find('[data-played]').textContent = `${number.format(data.played)} 首`;
            find('[data-played-detail]').textContent = tracks.length ? `占曲库 ${Math.round(data.played / tracks.length * 100)}%` : '暂无歌曲';
            find('[data-duration]').textContent = readableDuration(data.duration);
            find('[data-duration-detail]').textContent = `${number.format(data.durationKnown)} / ${number.format(tracks.length)} 首有时长信息`;
            find('[data-size]').textContent = readableBytes(data.size);
            find('[data-size-detail]').textContent = `${number.format(data.sizeKnown)} / ${number.format(tracks.length)} 首有文件大小`;
            const songs = tracks.map(track => ({ label: track.title || '未知歌曲', subtitle: track.artist || '未知艺术家', value: countValue(track.playCount) })).filter(track => track.value > 0)
                .sort((a, b) => b.value - a.value || a.label.localeCompare(b.label, 'zh-CN'));
            const artistItems = [...data.artists].map(([label, value]) => ({ label, value })).sort((a, b) => b.value - a.value || a.label.localeCompare(b.label, 'zh-CN'));
            ranking(find('[data-play-ranking]'), songs, '次'); ranking(find('[data-artist-ranking]'), artistItems, '首');
            find('[data-play-empty]').hidden = songs.length > 0; find('[data-artist-empty]').hidden = artistItems.length > 0;
            const formats = [...data.formats].map(([name, value]) => ({ name, value })).sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
            const coverage = [{ name: '已试听', value: data.played }, { name: '未试听', value: tracks.length - data.played }];
            legend('[data-format-legend]', formats); legend('[data-coverage-legend]', tracks.length ? coverage : []);
            const uploadList = find('[data-upload-list]'); uploadList.replaceChildren();
            for (const month of data.months) { const item = document.createElement('li'); item.textContent = `${month.key}：${number.format(month.count)} 首`; uploadList.append(item); }
            const hasUploads = data.months.some(month => month.count > 0);
            find('[data-formats-empty]').hidden = tracks.length > 0;
            find('[data-coverage-empty]').hidden = tracks.length > 0;
            find('[data-uploads-empty]').hidden = hasUploads;
            results.hidden = false; status.textContent = '';
            const chartStatus = find('[data-chart-status]'); chartStatus.textContent = '';
            try {
                const echarts = await loadCharts();
                if (ticket !== sequence || !active) return;
                const pie = values => ({ color: colors, tooltip: { trigger: 'item', renderMode: 'richText', formatter: '{b}: {c} 首 ({d}%)' }, series: [{ type: 'pie', radius: ['58%', '79%'], center: ['50%', '50%'], avoidLabelOverlap: true, label: { show: false }, itemStyle: { borderColor: '#fff', borderWidth: 3, borderRadius: 5 }, data: values.map((item, index) => ({ ...item, itemStyle: { color: colors[index % colors.length] } })).filter(item => item.value > 0) }] });
                renderChart(echarts, 'formats', pie(formats), tracks.length > 0);
                renderChart(echarts, 'coverage', pie(coverage), tracks.length > 0);
                renderChart(echarts, 'uploads', { color: ['#79ad89'], tooltip: { trigger: 'axis', renderMode: 'richText', axisPointer: { type: 'shadow' } }, grid: { left: 32, right: 12, top: 15, bottom: 30 }, xAxis: { type: 'category', data: data.months.map(month => month.key.slice(5)), axisTick: { show: false }, axisLine: { lineStyle: { color: '#e4ece5' } }, axisLabel: { color: '#8b9b90', fontSize: 10 } }, yAxis: { type: 'value', minInterval: 1, axisLabel: { color: '#8b9b90', fontSize: 10 }, splitLine: { lineStyle: { color: '#eff4ef' } } }, series: [{ name: '上传歌曲', type: 'bar', barMaxWidth: 23, itemStyle: { borderRadius: [4, 4, 0, 0] }, data: data.months.map(month => month.count) }] }, hasUploads);
            } catch {
                if (ticket !== sequence || !active) return;
                charts.forEach(chart => chart.clear()); root.querySelectorAll('[data-chart]').forEach(element => { element.hidden = true; });
                chartStatus.textContent = '图表加载失败，仍可查看下方文字明细；请刷新重试。';
            }
        } catch (error) {
            if (ticket === sequence && active) { results.hidden = true; status.textContent = error.message || '统计数据加载失败，请重试。'; }
        } finally { if (ticket === sequence) refreshButton.disabled = false; }
    }
    refreshButton.addEventListener('click', refresh);
    window.addEventListener('resize', resize);
    return { activate() { active = true; refresh(); }, deactivate() { active = false; sequence++; refreshButton.disabled = false; } };
}
