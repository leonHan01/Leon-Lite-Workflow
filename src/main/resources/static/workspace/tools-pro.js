export function mount(pane) {
    const frame = pane.querySelector('iframe');
    const refresh = () => frame.contentWindow?.postMessage('leon-tools-refresh', location.origin);
    window.addEventListener('focus', () => { if (!pane.hidden) refresh(); });
    return { activate: refresh };
}
