let chartLibrary;
function loadCharts() {
    if (window.echarts) return Promise.resolve(window.echarts);
    if (!chartLibrary) chartLibrary = new Promise((resolve, reject) => {
        const script = document.createElement('script');
        script.src = '/workspace/vendor/echarts-5.6.0.min.js';
        script.onload = () => window.echarts ? resolve(window.echarts) : reject(new Error('图表组件加载失败。'));
        script.onerror = () => { script.remove(); reject(new Error('图表组件加载失败，请检查网络后刷新。')); };
        document.head.append(script);
    }).catch(error => { chartLibrary = null; throw error; });
    return chartLibrary;
}
const percent = value => value == null ? '—' : value.toFixed(1) + '%';
const bytes = value => {
    if (value == null) return '—';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    let index = 0;
    while (value >= 1024 && index < units.length - 1) { value /= 1024; index++; }
    return value.toFixed(index ? 1 : 0) + ' ' + units[index];
};

export function mount(pane) {
    const status = pane.querySelector('.monitor-status');
    const button = pane.querySelector('.monitor-refresh');
    const value = name => pane.querySelector(`[data-value="${name}"]`);
    const detail = name => pane.querySelector(`[data-detail="${name}"]`);
    let charts, timer, active = false, loading = false, refreshPending = false;
    const resize = () => charts?.forEach(chart => chart.resize());

    async function refresh() {
        if (loading) { refreshPending = true; return; }
        loading = true;
        button.disabled = true;
        try {
            const response = await fetch('/api/monitor', { cache: 'no-store' });
            if (!response.ok) throw new Error('监控数据加载失败，请重试。');
            const data = await response.json();
            const current = data.current || {};
            value('cpu').textContent = percent(current.cpuPercent);
            value('memory').textContent = percent(current.memoryPercent);
            value('disk').textContent = percent(current.diskPercent);
            value('pv').textContent = (data.pageViews.at(-1)?.views ?? 0).toLocaleString();
            detail('memory').textContent = `${bytes(current.memoryUsedBytes)} / ${bytes(current.memoryTotalBytes)}`;
            detail('disk').textContent = `根文件系统 · ${bytes(current.diskUsedBytes)} / ${bytes(current.diskTotalBytes)}`;
            status.textContent = current.time ? `更新于 ${new Date(current.time).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}（北京时间），每分钟自动刷新` : '等待首次采样…';
            const echarts = await loadCharts();
            if (!charts) charts = ['cpu', 'memory', 'disk', 'pv'].map(name => echarts.init(pane.querySelector(`[data-chart="${name}"]`)));
            const axis = { axisLine: { lineStyle: { color: '#e2e9e5' } }, axisLabel: { color: '#8b9892', fontSize: 11 }, axisTick: { show: false } };
            const timeAxis = { ...axis, type: 'time', axisLabel: { ...axis.axisLabel, formatter: time => new Date(time).toLocaleTimeString('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false }) } };
            const percentAxis = { ...axis, type: 'value', min: 0, max: 100, axisLabel: { ...axis.axisLabel, formatter: '{value}%' }, splitLine: { lineStyle: { color: '#eff2ef' } } };
            [['CPU', 'cpuPercent', '#4f6ee8'], ['内存', 'memoryPercent', '#40a995'], ['磁盘', 'diskPercent', '#e3a34c']].forEach(([name, key, color], index) => {
                charts[index].setOption({ color: [color], tooltip: { trigger: 'axis' }, grid: { left: 42, right: 20, top: 20, bottom: 32 }, xAxis: timeAxis, yAxis: percentAxis, series: [{ name, type: 'line', showSymbol: false, connectNulls: false, data: data.samples.map(item => [item.time, item[key]]) }] });
            });
            charts[3].setOption({ color: ['#4f6ee8'], tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } }, grid: { left: 42, right: 20, top: 20, bottom: 32 }, xAxis: { ...axis, type: 'category', data: data.pageViews.map(item => item.day.slice(5)) }, yAxis: { ...axis, type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eff2ef' } } }, series: [{ name: '浏览量', type: 'bar', barMaxWidth: 24, itemStyle: { borderRadius: [4, 4, 0, 0] }, data: data.pageViews.map(item => item.views) }] });
            if (active) resize();
        } catch (error) { status.textContent = error.message || '监控数据加载失败，请重试。'; }
        finally {
            loading = false; button.disabled = false;
            if (refreshPending && active) { refreshPending = false; refresh(); }
        }
    }
    button.addEventListener('click', refresh);
    window.addEventListener('resize', resize);
    document.addEventListener('workspace-page-view', () => { if (active) refresh(); });
    return {
        activate() { active = true; clearInterval(timer); refresh(); timer = setInterval(refresh, 60000); resize(); },
        deactivate() { active = false; clearInterval(timer); }
    };
}
