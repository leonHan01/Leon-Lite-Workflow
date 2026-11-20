import { Marked } from './vendor/marked.esm.js';
import katex from './vendor/katex/katex.mjs';

// Recognize math before Markdown consumes backslash delimiters, without touching code spans.
const markdown = new Marked({ gfm:true, breaks:true, extensions:[
    {
        name:'displayMath', level:'block',
        start(src) { const match = /(?:^|\n) {0,3}(?:\\\[|\$\$)/.exec(src); return match ? match.index + (match[0].startsWith('\n') ? 1 : 0) : undefined; },
        tokenizer(src) {
            const match = /^ {0,3}(?:\\\[([\s\S]+?)\\\]|\$\$([\s\S]+?)\$\$)[ \t]*(?:\n|$)/.exec(src);
            return match ? {type:'displayMath',raw:match[0],text:match[1] ?? match[2]} : undefined;
        }
    },
    {
        name:'inlineMath', level:'inline',
        start(src) { const index = src.search(/\\\[|\\\(|\$/); return index < 0 ? undefined : index; },
        tokenizer(src) {
            let match = /^(?:\\\[([\s\S]+?)\\\]|\$\$([\s\S]+?)\$\$)/.exec(src);
            if (match) return {type:'displayMath',raw:match[0],text:match[1] ?? match[2]};
            match = /^\\\(([\s\S]+?)\\\)/.exec(src);
            if (match) return {type:'inlineMath',raw:match[0],text:match[1]};
            // Require non-space delimiters and reject a closing dollar followed by a digit,
            // so ordinary prices such as "$5 and $10" remain text.
            match = /^\$(?!\$|\s)([^\n$`]*?[^\s\\`])\$(?!\$|\d)/.exec(src);
            if (match && /^\d/.test(match[1]) && /[\u3400-\u9fff，。！？；：]/.test(match[1])) return undefined;
            return match ? {type:'inlineMath',raw:match[0],text:match[1]} : undefined;
        }
    }
] });

// Render parsed tokens as DOM nodes so conversation text cannot inject HTML.
const entity = document.createElement('textarea');
function decode(value) {
    return String(value ?? '').replace(/&(?:#\d+|#x[\da-f]+|[a-z][a-z\d]+);/gi, match => {
        entity.innerHTML = match;
        return entity.value;
    });
}
function url(value, image = false) {
    try {
        const result = new URL(decode(value), location.href);
        return (image ? ['http:', 'https:'] : ['http:', 'https:', 'mailto:']).includes(result.protocol) ? result.href : null;
    } catch { return null; }
}
function appendMath(parent, text, raw, displayMode) {
    const node = document.createElement('span');
    node.className = displayMode ? 'users-math-block' : 'users-math-inline';
    try {
        katex.render(text, node, {displayMode, throwOnError:true, trust:false, maxExpand:300, maxSize:10});
        parent.append(node);
    } catch { parent.append(document.createTextNode(raw)); }
}
function appendTokens(parent, tokens) {
    for (const token of tokens || []) {
        let node;
        switch (token.type) {
            case 'space': case 'def': break;
            case 'text':
                if (token.tokens) appendTokens(parent, token.tokens);
                else parent.append(document.createTextNode(decode(token.text)));
                break;
            case 'escape': parent.append(document.createTextNode(token.text)); break;
            case 'html': parent.append(document.createTextNode(token.text || token.raw)); break;
            case 'br': parent.append(document.createElement('br')); break;
            case 'hr': parent.append(document.createElement('hr')); break;
            case 'displayMath': case 'inlineMath': appendMath(parent, token.text, token.raw, token.type === 'displayMath'); break;
            case 'codespan': node = document.createElement('code'); node.textContent = token.text; parent.append(node); break;
            case 'code':
                if (/^(?:math|latex)$/i.test((token.lang || '').trim())) { appendMath(parent, token.text, token.raw, true); break; }
                node = document.createElement('pre');
                { const code = document.createElement('code'); code.textContent = token.text; node.append(code); }
                parent.append(node); break;
            case 'heading': case 'paragraph': case 'strong': case 'em': case 'del': case 'blockquote':
                node = document.createElement(token.type === 'heading' ? 'h' + Math.max(1, Math.min(6, token.depth)) : ({ paragraph:'p', strong:'strong', em:'em', del:'del', blockquote:'blockquote' })[token.type]);
                appendTokens(node, token.tokens); parent.append(node); break;
            case 'link': {
                const href = url(token.href);
                if (!href) { appendTokens(parent, token.tokens); break; }
                node = document.createElement('a'); node.href = href; node.target = '_blank'; node.rel = 'noopener noreferrer';
                if (token.title) node.title = decode(token.title);
                appendTokens(node, token.tokens); parent.append(node); break;
            }
            case 'image': {
                const src = url(token.href, true);
                if (!src) { parent.append(document.createTextNode(decode(token.text))); break; }
                node = document.createElement('img'); node.src = src; node.alt = decode(token.text); node.loading = 'lazy'; node.referrerPolicy = 'no-referrer';
                if (token.title) node.title = decode(token.title);
                parent.append(node); break;
            }
            case 'list':
                node = document.createElement(token.ordered ? 'ol' : 'ul');
                if (token.ordered && Number.isSafeInteger(token.start) && token.start !== 1) node.start = token.start;
                for (const item of token.items) {
                    const li = document.createElement('li');
                    if (item.task) { const check = document.createElement('input'); check.type = 'checkbox'; check.disabled = true; check.checked = !!item.checked; li.append(check, document.createTextNode(' ')); }
                    appendTokens(li, item.tokens); node.append(li);
                }
                parent.append(node); break;
            case 'table': {
                const wrap = document.createElement('div'); wrap.className = 'users-markdown-table';
                const table = document.createElement('table'), head = document.createElement('thead'), body = document.createElement('tbody');
                const row = (cells, tag) => {
                    const tr = document.createElement('tr');
                    for (const cell of cells) { const td = document.createElement(tag); if (['left','center','right'].includes(cell.align)) td.style.textAlign = cell.align; appendTokens(td, cell.tokens); tr.append(td); }
                    return tr;
                };
                head.append(row(token.header, 'th')); for (const cells of token.rows) body.append(row(cells, 'td'));
                table.append(head, body); wrap.append(table); parent.append(wrap); break;
            }
            default: parent.append(document.createTextNode(token.raw || token.text || ''));
        }
    }
}
export function renderUserMarkdown(target, text) {
    target.replaceChildren();
    try { appendTokens(target, markdown.lexer(text || '')); }
    catch { target.textContent = text || ''; }
}
