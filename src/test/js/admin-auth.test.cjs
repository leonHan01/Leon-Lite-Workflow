const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

test('management writes carry the CSRF header only to same-origin APIs', async () => {
    const calls = [];
    const window = { location: { href: 'https://example.test:8080/simple-kv.html', origin: 'https://example.test:8080' },
        fetch: async (...args) => { calls.push(args); return {}; } };
    const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/admin-auth.js'), 'utf8');
    vm.runInNewContext(script, { window, URL, Request, Headers });
    await window.fetch('/api/save', { method: 'POST', headers: { 'Content-Type': 'application/json' } });
    assert.equal(calls[0][1].headers.get('X-Requested-With'), 'XMLHttpRequest');
    assert.equal(calls[0][1].headers.get('Content-Type'), 'application/json');
    await window.fetch('/api/options');
    assert.equal(calls[1][1].headers, undefined);
    await window.fetch('https://other.test/api/save', { method: 'POST' });
    assert.equal(calls[2][1].headers, undefined);
    await window.fetch(new Request('https://example.test:8080/api/save', { method: 'POST' }));
    assert.equal(calls[3][1].headers.get('X-Requested-With'), 'XMLHttpRequest');
});

test('expired browser sessions return to login with the current deep link', async () => {
    const redirects = [];
    const window = { location: { href: 'https://example.test/json-record.html?id=42', origin: 'https://example.test',
        pathname: '/json-record.html', search: '?id=42', assign: url => redirects.push(url) },
        fetch: async () => ({ status: 401 }) };
    const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/admin-auth.js'), 'utf8');
    vm.runInNewContext(script, { window, URL, Request, Headers });
    await window.fetch('/api/json-record/42');
    assert.deepEqual(redirects, ['/admin-login.html?next=%2Fjson-record.html%3Fid%3D42']);
    await window.fetch('/auth/login', { method: 'POST' });
    await window.fetch('https://other.test/api/save');
    assert.equal(redirects.length, 1);
});
