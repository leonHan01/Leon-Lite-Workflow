const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
(async () => {
    const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/workspace/music-statistics.js'), 'utf8');
    const { summarize } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
    const data = summarize([
        { artist: ' A ', album: 'Album', extension: 'mp3', playCount: 3, duration: 90, size: 1024, uploadedAt: '2026-11-02T12:00:00' },
        { artist: 'A', album: 'Album', extension: 'MP3', playCount: null, duration: 0, size: 0, uploadedAt: 'invalid' },
        { artist: '', album: '未知专辑', extension: 'flac', playCount: 1, duration: 30, size: 2048, uploadedAt: '2025-12-02T12:00:00' },
        { artist: '', playCount: -1, duration: -5, uploadedAt: '2025-11-02T12:00:00' }
    ], new Date(2026, 10, 14));
    assert.equal(data.plays, 4); assert.equal(data.played, 2);
    assert.equal(data.artists.get('A'), 2); assert.equal(data.albums.size, 1);
    assert.equal(data.formats.get('MP3'), 2); assert.equal(data.formats.get('未知格式'), 1);
    assert.equal(data.duration, 120); assert.equal(data.durationKnown, 2);
    assert.equal(data.size, 3072); assert.equal(data.sizeKnown, 2);
    assert.equal(data.months[0].key, '2025-12'); assert.equal(data.months[0].count, 1);
    assert.equal(data.months[11].key, '2026-11'); assert.equal(data.months[11].count, 1);
    assert.equal(data.months.reduce((sum, item) => sum + item.count, 0), 2);
    assert.equal(summarize([], new Date(2026, 10, 14)).played, 0);
    console.log('Statistics aggregation checks passed');
})().catch(error => { console.error(error); process.exit(1); });
