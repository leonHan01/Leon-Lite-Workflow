import { chrome } from "../shim.js";
const TEXT_URL = /https?:\/\/[^\s<>"']+/gi;

function pushText(parts, value) {
  if (!value) return;
  const last = parts[parts.length - 1];
  if (last?.text != null) last.text += value;
  else parts.push({ text: value });
}

export function splitTextLinks(content) {
  const text = String(content || "");
  const parts = [];
  let last = 0;
  for (const match of text.matchAll(TEXT_URL)) {
    const raw = match[0];
    const url = raw.replace(/[),.;:!?，。；：！？、]+$/u, "");
    if (!url) continue;
    if (match.index > last) pushText(parts, text.slice(last, match.index));
    parts.push({ url });
    const end = match.index + raw.length;
    if (url.length < raw.length) pushText(parts, raw.slice(url.length));
    last = end;
  }
  if (last < text.length) pushText(parts, text.slice(last));
  return parts.length ? parts : [{ text }];
}

export function linkLabel(url) {
  try {
    const host = new URL(url).hostname.replace(/^www\./, "");
    return host ? `[${host}]` : "[链接]";
  } catch {
    return "[链接]";
  }
}

export async function openSavedUrl(url) {
  const result = await chrome.runtime.sendMessage({ type: "OPEN_OR_FOCUS", url, active: true });
  if (result?.error) throw new Error(result.error);
}
