export function mount(pane) {
    const button = pane.querySelector('.advanced-download');
    const status = pane.querySelector('.advanced-status');
    button.addEventListener('click', async () => {
        button.disabled = true;
        status.textContent = '正在生成备份…';
        try {
            const response = await fetch('/api/admin/database-backup', { cache: 'no-store' });
            if (!response.ok) throw new Error('备份下载失败，请稍后重试。');
            const blob = await response.blob();
            const filename = response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] || 'leon-workflow-backup.sql';
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = filename;
            document.body.append(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            status.textContent = '备份已开始下载。';
        } catch (error) {
            status.textContent = error.message || '备份下载失败，请稍后重试。';
        } finally {
            button.disabled = false;
        }
    });
    return {};
}
