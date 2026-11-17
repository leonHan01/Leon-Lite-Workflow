(() => {
    'use strict';
    function token(text, start, end = start) {
        if (start < 0 || start !== end || start > text.length) return null;
        const match = /(?:^|[^A-Za-z0-9_])([@＠][\p{L}\p{N}\p{M}_-]{0,64})$/u.exec(text.slice(0, start));
        if (!match) return null;
        const beginning = match.index + match[0].length - match[1].length;
        const trailing = /^[\p{L}\p{N}\p{M}_-]*/u.exec(text.slice(start))[0].length;
        return { start: beginning, end: start + trailing, query: text.slice(beginning + 1, start) };
    }
    function matches(agent, query) {
        const needle = query.toLowerCase();
        return agent.name.toLowerCase().includes(needle) || agent.handle.toLowerCase().includes(needle);
    }
    function replace(text, mention, handle) {
        const suffix = text.slice(mention.end);
        const boundary = /[\p{L}\p{N}_]$/u.test(text.slice(0, mention.start)) ? ' ' : '';
        const insertion = boundary + '@' + handle + (/^\s/u.test(suffix) ? '' : ' ');
        const existingSpace = /^(\r\n|\s)/u.exec(suffix)?.[0].length || 0;
        return { text: text.slice(0, mention.start) + insertion + suffix, caret: mention.start + insertion.length + existingSpace };
    }
    const api = { token, matches, replace };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else globalThis.LeonMentions = api;
})();
