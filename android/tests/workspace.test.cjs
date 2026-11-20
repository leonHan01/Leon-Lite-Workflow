const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const assets = path.join(__dirname, '../app/src/main/assets');
const webRoot = path.join(__dirname, '../../src/main/resources/static');
const handshake = 'test-native-capability-92f1';
const integration = fs.readFileSync(path.join(assets, 'native-workspace.js'), 'utf8').replace('__LEON_NATIVE_HANDSHAKE__', handshake);
const flush = () => new Promise(resolve => setImmediate(resolve));

test('all management workspace routes have an Android entry or a detail route', () => {
    const app = fs.readFileSync(path.join(webRoot, 'workspace/app.js'), 'utf8');
    const routes = [...app.split('const views =')[0].matchAll(/'([^']+)':\s*\[/g)].map(match => match[1]);
    const modules = JSON.parse(fs.readFileSync(path.join(assets, 'modules.json'), 'utf8'));
    const entries = new Set(modules.map(module => module.path));
    assert.equal(entries.size, modules.length);
    for (const route of routes) {
        assert.ok(entries.has(route) || ['/', '/json-record.html'].includes(route), 'missing Android route: ' + route);
    }
    for (const entry of entries) assert.ok(fs.existsSync(path.join(webRoot, entry)), entry);
    assert.equal(modules.length, 13);
});

function harness() {
    const listeners = {};
    const clicks = [];
    const sent = [];
    const classes = [];
    const port = { postMessage(value) { sent.push(JSON.parse(value)); }, start() {}, close() {} };
    const location = { origin: 'https://121.40.254.112' };
    const window = { addEventListener(type, callback) { listeners[type] = callback; } };
    const document = { documentElement: { classList: { add: value => classes.push(value) } },
        addEventListener(type, callback) { if (type === 'click') clicks.push(callback); } };
    class FileReader {
        readAsDataURL(blob) { blob.arrayBuffer().then(bytes => { this.result = 'data:;base64,' + Buffer.from(bytes).toString('base64'); this.onload(); }); }
    }
    let fetched = 0;
    vm.runInNewContext(integration, { window, document, location, FileReader,
        fetch: async () => { fetched++; return { blob: async () => new Blob(['a'.repeat(110000)]) }; } });
    const connect = (origin = '', data = handshake) => listeners.message({origin, data, ports:[port]});
    const message = (type, id) => port.onmessage({data:JSON.stringify({type, id})});
    function click(elements) {
        let prevented = false;
        clicks[0]({ composedPath:() => elements, preventDefault() { prevented = true; }, stopImmediatePropagation() {} });
        return prevented;
    }
    return { window, connect, message, click, sent, classes, port, get fetched() { return fetched; } };
}

test('the native export channel requires its capability and supports opaque native senders', () => {
    const app = harness();
    assert.deepEqual(app.classes, ['leon-android']);
    app.connect('https://evil.example', 'leon-admin-connect');
    assert.equal(app.click([{matches: selector => selector === '.advanced-download'}]), false);
    assert.equal(app.sent.length, 0);
    app.connect();
    assert.equal(app.click([{matches: selector => selector === '.advanced-download'}]), true);
    assert.equal(app.sent[0].type, 'export-url');
    assert.equal(app.sent[0].url, 'https://121.40.254.112/api/admin/database-backup');
    assert.equal(app.fetched, 0, 'SQL backup streams directly to the Android document');
});

test('native port transfer targets the exact configured origin', () => {
    const browser = fs.readFileSync(path.join(__dirname, '../app/src/main/java/com/leon/workflow/admin/WorkspaceBrowser.java'), 'utf8');
    assert.match(browser, /postWebMessage\(new WebMessage\(handshake,[^\n]+Uri\.parse\(server\.origin\(\)\)\)/);
    assert.doesNotMatch(browser, /addJavascriptInterface|Uri\.parse\("\*"\)/);
});

test('blob exports stream bounded chunks with acknowledgements, including shadow DOM links', async () => {
    const app = harness(); app.connect();
    const anchor = { tagName:'A', href:'blob:https://121.40.254.112/export', download:'快捷项.json' };
    assert.equal(app.click([{}, anchor]), true);
    await flush();
    const start = app.sent[0]; assert.equal(start.type, 'blob-start'); assert.equal(start.size, 110000);
    assert.equal(start.name, '快捷项.json');
    app.message('blob-ready', start.id); await flush();
    assert.equal(app.sent.length, 2);
    assert.equal(Buffer.from(app.sent[1].data, 'base64').length, 49152);
    await flush(); assert.equal(app.sent.length, 2, 'waits for Android to write each chunk');
    app.message('blob-ack', start.id); await flush();
    app.message('blob-ack', start.id); await flush();
    app.message('blob-ack', start.id); await flush();
    const chunks = app.sent.filter(message => message.type === 'blob-chunk');
    assert.equal(chunks.length, 3);
    assert.equal(Buffer.concat(chunks.map(chunk => Buffer.from(chunk.data, 'base64'))).toString(), 'a'.repeat(110000));
    assert.equal(app.sent.at(-1).type, 'blob-end');
});

test('cancelling the system save dialog releases the pending blob export', async () => {
    const app = harness(); app.connect();
    app.click([{tagName:'A', href:'blob:https://121.40.254.112/export', download:'file.json'}]); await flush();
    app.message('blob-cancel', app.sent[0].id); await flush();
    app.message('blob-ready', app.sent[0].id); await flush();
    assert.equal(app.sent.length, 1);
});

test('ordinary links and exports outside Android keep their browser behavior', () => {
    const app = harness();
    assert.equal(app.click([{tagName:'A', href:'blob:https://121.40.254.112/export'}]), false);
    app.connect();
    assert.equal(app.click([{tagName:'A', href:'https://example.test/file'}]), false);
    assert.equal(app.sent.length, 0);
});
