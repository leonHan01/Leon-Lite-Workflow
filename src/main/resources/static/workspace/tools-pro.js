import { mountToolsPanel } from './toolspro/js/sidepanel.js';

export function mount(pane, workspace) {
    const host = pane.querySelector('.workspace-tools-pro-panel');
    const root = host.attachShadow({ mode: 'open' });
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = '/workspace/toolspro/css/sidepanel.css';
    root.append(link, ...host.childNodes);
    return mountToolsPanel(root, workspace.navigate);
}
