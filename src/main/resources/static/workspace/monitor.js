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

function renderDeployments(pane, history, showAll) {
    const rows = pane.querySelector('[data-deployments]');
    const message = pane.querySelector('[data-deployment-message]');
    const records = history?.records || [];
    rows.replaceChildren();
    const visible = showAll ? records : records.slice(0, 5);
    const total = history?.total ?? records.length;
    pane.querySelector('[data-deployment-summary]').textContent = `共 ${total} 次部署 · 展示 ${visible.length} 次${total > records.length ? '（最多显示最近 50 次）' : ''} · 重启不重复记录`;
    const more = pane.querySelector('[data-deployment-more]');
    more.hidden = records.length <= 5;
    more.textContent = showAll ? '收起较早记录' : total > records.length ? `查看最近 ${records.length} 条` : `查看全部记录（${records.length}）`;
    more.setAttribute('aria-expanded', String(showAll));
    message.textContent = history?.available === false ? '部署记录暂时无法读取，请稍后刷新。'
        : history?.unreadable ? `有 ${history.unreadable} 条部署记录无法读取，其余记录正常展示。`
        : records.length ? '' : '暂无部署记录，使用部署脚本发布后会自动记录。';
    const statuses = { DEPLOYING: ['部署中', 'pending'], SUCCESS: ['成功', 'success'],
        ROLLED_BACK: ['已回滚', 'rollback'], FAILED: ['失败', 'failed'], ROLLBACK_FAILED: ['回滚失败', 'failed'] };
    for (const record of visible) {
        const row = document.createElement('tr');
        const time = document.createElement('time');
        time.dateTime = record.startedAt;
        time.textContent = new Date(record.startedAt).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', hour12: false });
        row.insertCell().append(time);
        const version = row.insertCell();
        const name = document.createElement('strong');
        name.textContent = record.version || '—';
        version.append(name);
        if (record.id === history.currentId) {
            const badge = document.createElement('span');
            badge.className = 'monitor-current-release';
            badge.textContent = '当前运行';
            version.append(badge);
        }
        const commit = document.createElement('small');
        if (/^[0-9a-f]{7,40}$/i.test(record.commit || '')) {
            const link = document.createElement('a');
            link.href = `https://github.com/leonHan01/Leon-Lite-Workflow/commit/${record.commit}`;
            link.target = '_blank'; link.rel = 'noopener noreferrer';
            link.textContent = `Git commit ${record.commit.slice(0, 8)}`;
            link.title = record.commit;
            commit.append(link);
        } else commit.textContent = 'Git commit 未记录';
        if (record.dirty) commit.append(' · 含工作区改动');
        version.append(commit);
        const subject = document.createElement('small');
        subject.textContent = record.commitMessage || '提交说明未记录';
        version.append(subject);
        row.insertCell().textContent = record.description || '—';
        const [label, style] = statuses[record.status] || ['未知', 'pending'];
        const result = document.createElement('span');
        result.className = `monitor-deployment-result is-${style}`;
        result.textContent = label;
        row.insertCell().append(result);
        const seconds = record.finishedAt ? Math.max(0, Math.round((new Date(record.finishedAt) - new Date(record.startedAt)) / 1000)) : null;
        row.insertCell().textContent = seconds == null ? (record.status === 'DEPLOYING' ? '进行中' : '—')
            : seconds >= 60 ? `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` : `${seconds} 秒`;
        rows.append(row);
    }
    pane.querySelector('.monitor-deployment-scroll').hidden = records.length === 0;
}

export function mount(pane) {
    const status = pane.querySelector('.monitor-status');
    const button = pane.querySelector('.monitor-refresh');
    const deployButton = pane.querySelector('.monitor-deploy');
    const value = name => pane.querySelector(`[data-value="${name}"]`);
    const detail = name => pane.querySelector(`[data-detail="${name}"]`);
    let charts, timer, active = false, loading = false, refreshPending = false, deploying = false;
    let showAllDeployments = false, latestDeployments;
    const resize = () => charts?.forEach(chart => chart.resize());
    function renderControl(control) {
        deploying = control?.busy || false;
        deployButton.disabled = !control?.enabled || deploying;
        deployButton.textContent = deploying ? '部署进行中…' : '一键部署';
        const stages = { QUEUED: '已排队', FETCHING: '拉取代码', BUILDING: 'Maven 构建', DEPLOYING: '更新服务', VERIFYING: '验证服务', SUCCESS: '部署成功', FAILED: '部署失败', ROLLED_BACK: '已恢复旧版', ROLLBACK_FAILED: '回滚失败' };
        pane.querySelector('[data-deploy-stage]').textContent = control?.job ? stages[control.job.stage] || '部署服务' : '部署服务';
        pane.querySelector('[data-deploy-progress]').textContent = !control?.enabled ? '服务器尚未启用一键部署。'
            : control.job?.message || (deploying ? '已有部署正在执行，请等待完成。' : '就绪 · 点击即可部署 main 分支最新代码。');
        pane.querySelector('[data-reload-workspace]').hidden = control?.job?.stage !== 'SUCCESS';
    }
    function schedule() {
        clearTimeout(timer);
        if (active) timer = setTimeout(refresh, deploying ? 4000 : 60000);
    }

    async function refresh() {
        if (loading) { refreshPending = true; return; }
        loading = true;
        button.disabled = true;
        try {
            const response = await fetch('/api/monitor', { cache: 'no-store' });
            if (!response.ok) throw new Error('监控数据加载失败，请重试。');
            const data = await response.json();
            renderControl(data.deploymentControl);
            latestDeployments = data.deployments;
            renderDeployments(pane, latestDeployments, showAllDeployments);
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
        } catch (error) { status.textContent = deploying ? '服务正在更新，正在自动重新连接…' : error.message || '监控数据加载失败，请重试。'; }
        finally {
            loading = false; button.disabled = false;
            if (refreshPending && active) { refreshPending = false; refresh(); }
            else schedule();
        }
    }
    button.addEventListener('click', refresh);
    pane.querySelector('[data-reload-workspace]').addEventListener('click', () => location.reload());
    pane.querySelector('[data-deployment-more]').addEventListener('click', () => {
        showAllDeployments = !showAllDeployments;
        renderDeployments(pane, latestDeployments, showAllDeployments);
    });
    deployButton.addEventListener('click', async () => {
        deployButton.disabled = true;
        deployButton.textContent = '正在提交…';
        try {
            const response = await fetch('/api/monitor/deploy', { method: 'POST' });
            if (!response.ok) throw new Error(response.status === 409 ? '已有部署正在执行，请等待完成。' : response.status === 403 ? '登录或写入来源校验未通过，请重新登录。' : '部署请求提交失败，请稍后重试。');
            const job = await response.json();
            renderControl({ enabled: true, busy: true, job });
            refresh();
        } catch (error) {
            pane.querySelector('[data-deploy-progress]').textContent = error.message;
            deployButton.disabled = false;
            deployButton.textContent = '一键部署';
        }
    });
    window.addEventListener('resize', resize);
    document.addEventListener('workspace-page-view', () => { if (active) refresh(); });
    return {
        activate() { active = true; clearTimeout(timer); refresh(); resize(); },
        deactivate() { active = false; clearTimeout(timer); }
    };
}
