/* A MessagePort is handed only to this exact HTTPS origin. No JavaScript interface is exposed. */
(() => {
    if (window.__leonAndroidInstalled) return;
    window.__leonAndroidInstalled = true;
    document.documentElement.classList.add('leon-android');
    // postWebMessage has an opaque native sender origin. The native host targets this
    // exact HTTPS origin and provides an unpredictable capability for the handshake.
    const handshake = '__LEON_NATIVE_HANDSHAKE__';
    let port;
    let nextId = 0;
    const jobs = new Map();
    const send = value => port.postMessage(JSON.stringify(value));
    const readChunk = blob => new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
        reader.onerror = () => reject(new Error('读取导出文件失败'));
        reader.readAsDataURL(blob);
    });
    const signal = job => new Promise((resolve, reject) => { job.resolve = resolve; job.reject = reject; });
    async function stream(job) {
        try {
            for (let offset = 0; offset < job.blob.size; offset += 49152) {
                const data = await readChunk(job.blob.slice(offset, offset + 49152));
                const ack = signal(job);
                send({ type:'blob-chunk', id:job.id, data });
                await ack;
            }
            send({ type:'blob-end', id:job.id });
        } catch { send({ type:'blob-abort', id:job.id }); }
        finally { jobs.delete(job.id); }
    }
    window.addEventListener('message', event => {
        if (event.data !== handshake || !event.ports?.[0]) return;
        port?.close();
        port = event.ports[0];
        port.onmessage = event => {
            let message;
            try { message = JSON.parse(event.data); } catch { return; }
            const job = jobs.get(message.id);
            if (!job) return;
            if (message.type === 'blob-ready') stream(job);
            if (message.type === 'blob-ack') job.resolve?.();
            if (message.type === 'blob-cancel') {
                job.reject?.(new Error('导出已取消'));
                jobs.delete(job.id);
            }
        };
        port.start();
    });
    document.addEventListener('click', event => {
        if (!port) return;
        const path = event.composedPath();
        if (path.some(element => element.matches?.('.advanced-download'))) {
            event.preventDefault(); event.stopImmediatePropagation();
            send({type:'export-url', url:location.origin + '/api/admin/database-backup',
                name:'leon-workflow-backup-' + new Date().toISOString().slice(0,10) + '.sql', mime:'application/sql'});
            return;
        }
        const link = path.find(element => element.tagName === 'A');
        if (!link || !link.href.startsWith('blob:')) return;
        event.preventDefault(); event.stopImmediatePropagation();
        // Start reading immediately: exporters may revoke the object URL after click() returns.
        fetch(link.href).then(response => response.blob()).then(blob => {
            const id = String(++nextId);
            jobs.set(id, { id, blob });
            send({type:'blob-start', id, size:blob.size, name:link.download || 'leon-export.json',
                mime:blob.type.split(';')[0] || 'application/octet-stream'});
        }).catch(() => send({type:'notice', text:'无法读取导出文件，请重新导出'}));
    }, true);
})();
