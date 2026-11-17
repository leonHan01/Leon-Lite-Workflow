const test = require('node:test');
const assert = require('node:assert/strict');
const mentions = require('../../main/resources/static/workspace/mentions.js');

test('bare @, full-width @ and Unicode name / handle filtering', () => {
    assert.deepEqual(mentions.token('你好 @', 4), { start: 3, end: 4, query: '' });
    assert.equal(mentions.token('＠写', 2).query, '写');
    assert.equal(mentions.replace('你好@写', mentions.token('你好@写', 4), 'writer').text, '你好 @writer ');
    assert.ok(mentions.matches({ name: '写作助手', handle: 'writer' }, '写作'));
    assert.ok(mentions.matches({ name: '写作助手', handle: 'writer' }, 'WRI'));
    assert.ok(!mentions.matches({ name: '技术顾问', handle: 'coder' }, 'wri'));
});
test('email addresses, completed mentions, punctuation and selections do not open candidates', () => {
    for (const value of ['me@example.com', '@writer ', '@a.', 'some@thing']) assert.equal(mentions.token(value, value.length), null);
    assert.equal(mentions.token('@w', 0, 2), null);
});
test('caret replacement preserves emoji, text after caret and other mentions', () => {
    const text = '😀 @wriXYZ 保留';
    assert.deepEqual(mentions.token(text, 7), { start: 3, end: 10, query: 'wri' });
    assert.deepEqual(mentions.replace(text, mentions.token(text, 7), 'writer'), { text: '😀 @writer 保留', caret: 11 });
    assert.equal(mentions.token('😀 @writer 保留', 11), null);
    const multiple = '@coder 和 @w';
    assert.deepEqual(mentions.replace(multiple, mentions.token(multiple, multiple.length), 'writer'), { text: '@coder 和 @writer ', caret: 17 });
});
