export function bindActions(root, actions) {
    for (const type of ['click', 'input', 'change']) {
        const attribute = 'data-ws-' + type;
        root.addEventListener(type, event => {
            const control = event.target.closest('[' + attribute + ']');
            if (!control || !root.contains(control)) return;
            const name = control.getAttribute(attribute);
            if (!Object.hasOwn(actions, name)) return;
            const raw = control.getAttribute('data-ws-arg');
            const arg = raw === null ? event : /^\d+$/.test(raw) ? Number(raw) : raw;
            actions[name](arg);
        });
    }
}
