import * as api from "./api.js";
import { el, icon, restoreFocus } from "./dom.js";
import { withBackend } from "./persist.js";
import { CHAT_KEY } from "./settings.js";
import { formatDateTime, uid } from "./storage.js";

let historyQuery = "";
let chatBusy = false;
let chatSending = false;
let stopController = null;
let paintLiveSend = null;
let refreshLiveChat = null;

export function isChatSending() {
  return chatBusy;
}

const SYSTEM_PROMPT = "You are a helpful assistant.";
const DEEPSEEK_URL = "https://api.deepseek.com/chat/completions";
const MODEL = "deepseek-v4-flash";
const PRIOR_USER_CONTEXT = 5;

function emptyStore() {
  return { currentSessionId: null, sessions: [] };
}

function clipTitle(text) {
  return api.clip(String(text || "").trim().replace(/\s+/g, " "), 40);
}

export function fromRemoteMessage(message) {
  return {
    id: String(message.id),
    role: message.role,
    content: message.content || "",
    reasoning: message.reasoningContent || "",
    createdAt: api.parseTime(message.createTime),
    dirty: false,
  };
}

export function fromRemoteSession(session, messages = []) {
  const mapped = messages.map(fromRemoteMessage);
  return {
    id: String(session.id),
    title: session.title || "新对话",
    createdAt: api.parseTime(session.createTime),
    updatedAt: api.parseTime(session.updateTime),
    messageCount: session.messageCount ?? mapped.length,
    messages: mapped,
  };
}

function localSession({ title = "新对话", messages = [] } = {}) {
  const now = Date.now();
  return {
    id: uid(),
    title,
    createdAt: now,
    updatedAt: now,
    messageCount: messages.length,
    messages,
  };
}

function normalizeSession(session) {
  const messages = Array.isArray(session.messages) ? session.messages : [];
  return {
    id: String(session.id),
    title: session.title || "新对话",
    createdAt: session.createdAt || Date.now(),
    updatedAt: session.updatedAt || session.createdAt || Date.now(),
    messageCount: session.messageCount ?? messages.length,
    messages,
  };
}

export function migrateStore(stored) {
  if (!stored || typeof stored !== "object") return emptyStore();
  if (Array.isArray(stored.sessions)) {
    const sessions = stored.sessions.map(normalizeSession);
    const currentId = stored.currentSessionId != null ? String(stored.currentSessionId) : null;
    const current = sessions.some((item) => item.id === currentId) ? currentId : sessions[0]?.id || null;
    return { currentSessionId: current, sessions };
  }
  if (Array.isArray(stored.messages) && stored.messages.length) {
    const firstUser = stored.messages.find((item) => item.role === "user");
    const session = localSession({
      title: clipTitle(firstUser?.content) || "历史对话",
      messages: stored.messages,
    });
    session.createdAt = stored.messages[0]?.createdAt || session.createdAt;
    session.updatedAt = stored.messages.at(-1)?.createdAt || session.updatedAt;
    return { currentSessionId: session.id, sessions: [session] };
  }
  return emptyStore();
}

export async function loadChat() {
  const data = await chrome.storage.local.get(CHAT_KEY);
  return migrateStore(data[CHAT_KEY]);
}

export async function saveChat(store) {
  await chrome.storage.local.set({ [CHAT_KEY]: store });
}

function findSession(store, sessionId) {
  return store.sessions.find((item) => String(item.id) === String(sessionId)) || null;
}

function sortedSessions(store) {
  return [...store.sessions].sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));
}

export function sessionMatchesQuery(session, keyword) {
  const query = String(keyword || "").trim().toLowerCase();
  if (!query) return true;
  if (String(session?.title || "").toLowerCase().includes(query)) return true;
  return (session?.messages || []).some((message) =>
    `${message?.content || ""}\n${message?.reasoning || ""}`.toLowerCase().includes(query)
  );
}

function currentSession(store) {
  return findSession(store, store.currentSessionId);
}

function upsertSession(store, session) {
  const index = store.sessions.findIndex((item) => String(item.id) === String(session.id));
  if (index >= 0) store.sessions[index] = session;
  else store.sessions.unshift(session);
}

export function buildChatCompletionsMessages(sessionMessages) {
  const history = Array.isArray(sessionMessages) ? sessionMessages : [];
  let currentIndex = -1;
  for (let index = history.length - 1; index >= 0; index -= 1) {
    if (history[index]?.role === "user" && String(history[index].content || "").trim()) {
      currentIndex = index;
      break;
    }
  }
  if (currentIndex < 0) throw new Error("没有可发送的问题");
  const current = history[currentIndex];
  const prior = history.slice(0, currentIndex).filter((message) => {
    const role = message?.role;
    return (role === "user" || role === "assistant") && String(message.content || "").trim();
  });
  let keptUsers = 0;
  let start = prior.length;
  for (let index = prior.length - 1; index >= 0; index -= 1) {
    if (prior[index].role !== "user") continue;
    keptUsers += 1;
    start = index;
    if (keptUsers >= PRIOR_USER_CONTEXT) break;
  }
  const context = keptUsers ? prior.slice(start) : [];
  return [
    { role: "system", content: SYSTEM_PROMPT },
    ...context.map((message) => ({ role: message.role, content: String(message.content) })),
    { role: "user", content: current.content },
  ];
}

async function ensureRemoteSession(store, session) {
  if (!(await api.shouldUseBackend()) || api.isRemoteId(session.id)) return session;
  const remote = await api.createChatSession({ title: session.title || "新对话" });
  const oldId = session.id;
  const next = {
    ...session,
    id: String(remote.id),
    title: session.title || remote.title || "新对话",
  };
  store.sessions = store.sessions.filter((item) => String(item.id) !== String(oldId));
  upsertSession(store, next);
  if (String(store.currentSessionId) === String(oldId)) store.currentSessionId = next.id;
  await saveChat(store);
  return next;
}

async function pushSessionMessages(session, store) {
  if (!api.isRemoteId(session.id)) return session;
  for (const message of session.messages) {
    if (!String(message.content || "").trim()) continue;
    if (!message.dirty && api.isRemoteId(message.id)) continue;
    const saved = await api.addChatMessage(session.id, {
      role: message.role,
      content: message.content,
      reasoningContent: message.reasoning || "",
    });
    Object.assign(message, fromRemoteMessage(saved));
    if (store) await saveChat(store);
  }
  session.messageCount = session.messages.length;
  return session;
}

export function mergeSession(localSession, remoteSession) {
  const remote = fromRemoteSession(remoteSession, remoteSession.messages || []);
  if (!localSession) return remote;
  const extra = localSession.messages.filter((item) => item.dirty || !api.isRemoteId(item.id));
  if (extra.length) remote.messages = [...remote.messages, ...extra];
  if (localSession.dirty || (localSession.title && localSession.title !== "新对话" && extra.length)) {
    remote.title = localSession.title || remote.title;
  }
  remote.messageCount = remote.messages.length || remote.messageCount;
  return remote;
}

export async function refreshSessionsFromBackend() {
  const local = await loadChat();
  if (!(await api.shouldUseBackend(true))) return local;
  const store = await withBackend("加载对话失败", async () => {
    const remoteList = await api.listChatSessionsFull();
    const leftover = local.sessions.filter((item) => !api.isRemoteId(item.id));
    const leftoverIds = new Set(leftover.map((item) => String(item.id)));
    const sessions = [
      ...leftover,
      ...(remoteList || [])
        .filter((item) => !leftoverIds.has(String(item.id)))
        .map((item) => mergeSession(findSession(local, item.id), item)),
    ];
    const next = { currentSessionId: local.currentSessionId, sessions };
    if (!findSession(next, next.currentSessionId)) {
      next.currentSessionId = sortedSessions(next)[0]?.id || null;
    }
    await saveChat(next);
    return next;
  });
  return store || local;
}

export async function pushLocalSessions() {
  if (!(await api.shouldUseBackend(true))) return false;
  const store = await loadChat();
  const pending = store.sessions.filter((session) =>
    (!api.isRemoteId(session.id) && session.messages.length) ||
    session.messages.some((message) => message.dirty || !api.isRemoteId(message.id))
  );
  if (!pending.length) return true;
  return Boolean(await withBackend("同步对话失败", async () => {
    for (const session of pending) {
      const current = findSession(store, session.id) || session;
      if (!current.messages.length && !api.isRemoteId(current.id)) continue;
      const remoteSession = await ensureRemoteSession(store, current);
      await pushSessionMessages(remoteSession, store);
    }
    await saveChat(store);
    return true;
  }));
}

export async function createSession(title = "新对话") {
  const label = String(title || "新对话").trim() || "新对话";
  const store = await loadChat();
  if (label === "新对话") {
    const current = currentSession(store);
    if (current && !current.messages.length && api.isRemoteId(current.id)) return current;
  }
  const remote = await api.createChatSession({ title: label });
  const session = fromRemoteSession(remote, []);
  const latest = await loadChat(); latest.sessions.unshift(session); latest.currentSessionId = session.id; await saveChat(latest);
  return session;
}

export async function loadSession(sessionId) {
  const store = await loadChat();
  let session = findSession(store, sessionId);
  if (!session) throw new Error("对话不存在");
  if (await api.shouldUseBackend()) {
    const hydrated = await withBackend("加载对话失败", async () => {
      let next = await ensureRemoteSession(store, session);
      await pushSessionMessages(next, store);
      if (api.isRemoteId(next.id)) {
        next = mergeSession(next, await api.getChatSession(next.id));
      }
      upsertSession(store, next);
      return next;
    });
    if (hydrated) session = hydrated;
  }
  store.currentSessionId = session.id;
  await saveChat(store);
  return session;
}

export async function deleteSession(sessionId) {
  const store = await loadChat(); const session = findSession(store, sessionId);
  if (!session) return store;
  if (!api.isRemoteId(session.id)) throw new Error("旧本地对话尚未同步到后端");
  await api.deleteChatSession(session.id);
  const latest = await loadChat(); latest.sessions = latest.sessions.filter((item) => String(item.id) !== String(session.id));
  if (String(latest.currentSessionId) === String(session.id)) latest.currentSessionId = sortedSessions(latest)[0]?.id || null;
  await saveChat(latest); return latest;
}

export async function renameSession(sessionId, title) {
  const name = String(title || "").trim().replace(/\s+/g, " ");
  if (!name) throw new Error("标题不能为空");
  const store = await loadChat();
  const session = findSession(store, sessionId);
  if (!session) throw new Error("对话不存在");
  if (!api.isRemoteId(session.id)) throw new Error("旧本地对话尚未同步到后端");
  const saved = await api.updateChatSession(session.id, { title: api.clip(name, 80) });
  session.title = saved.title || api.clip(name, 80);
  session.updatedAt = api.parseTime(saved.updateTime) || Date.now();
  await saveChat(store);
  return session;
}

export async function appendMessage(partial) {
  let store = await loadChat(); let session = currentSession(store);
  if (!session) { await createSession(); store = await loadChat(); session = currentSession(store); }
  if (!session || !api.isRemoteId(session.id)) throw new Error("旧本地对话尚未同步到后端");
  const saved = await api.addChatMessage(session.id, { role: partial.role, content: partial.content || "", reasoningContent: partial.reasoning || "" });
  const message = fromRemoteMessage(saved);
  store = await loadChat(); session = findSession(store, session.id);
  if (!session) throw new Error("对话已删除，但消息已保存到后端");
  session.messages.push(message); session.messageCount = session.messages.length; session.updatedAt = Date.now();
  if (message.role === "user" && (session.title === "新对话" || !session.title)) session.title = clipTitle(message.content) || "新对话";
  await saveChat(store); return { session, message };
}

const CHAT_TIMEOUT_MS = 180000;

export function consumeSseBuffer(buffer, onData) {
  const parts = String(buffer).split(/\r?\n\r?\n/);
  const rest = parts.pop() ?? "";
  for (const block of parts) {
    const data = [];
    for (const line of block.split(/\r?\n/)) {
      if (!line.startsWith("data:")) continue;
      data.push(line.slice(5).replace(/^ /, ""));
    }
    if (data.length) onData(data.join("\n"));
  }
  return rest;
}

export function appendCompletionDelta(state, payload) {
  const delta = payload?.choices?.[0]?.delta || {};
  const content = typeof delta.content === "string" ? delta.content : "";
  const reasoning = typeof delta.reasoning_content === "string" ? delta.reasoning_content : "";
  return {
    content: `${state?.content || ""}${content}`,
    reasoning: `${state?.reasoning || ""}${reasoning}`,
  };
}

function chatAbortSignals(userSignal) {
  const timeout = AbortSignal.timeout(CHAT_TIMEOUT_MS);
  if (!userSignal) return { signal: timeout, timeout };
  return { signal: AbortSignal.any([timeout, userSignal]), timeout };
}

function completionError(payload, status) {
  const message = payload?.error?.message || payload?.message || (status ? `HTTP ${status}` : "回复失败");
  return new Error(typeof message === "string" ? message : "回复失败");
}

export async function requestChatCompletions(messages, { onDelta, signal: userSignal } = {}) {
  const { signal, timeout } = chatAbortSignals(userSignal);
  let response;
  try {
    response = await fetch('/api/workspace-chat', { method: 'POST', signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messages }) });
  } catch (error) {
    if (timeout.aborted) throw new Error('回复超时');
    throw error;
  }
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw completionError(payload, response.status);
  const result = { content: String(payload.content || '').trim() || '(空回复)', reasoning: '' };
  if (typeof onDelta === 'function') onDelta(result);
  return result;
}

export async function completeChat(sessionMessages, options) {
  return requestChatCompletions(buildChatCompletionsMessages(sessionMessages), options);
}

export async function completePrompt({ system, user }) {
  return requestChatCompletions([
    { role: "system", content: system },
    { role: "user", content: user },
  ]);
}

export function renderChatPage({ toast, confirmDelete, openSheet, closeSheet }) {
  const page = el("div", { class: "notes-page chat-page" });
  const titleEl = el("button", { class: "chat-session-title ellipsis", type: "button", title: "重命名对话" }, "新对话");
  const thread = el("div", { class: "notes-thread chat-thread" });
  const input = el("textarea", {
    class: "notes-input",
    rows: "1",
    placeholder: "输入消息，回车发送",
  });
  const sendBtn = el("button", { class: "btn primary icon", type: "button", title: "发送", "aria-label": "发送" }, icon("send"));
  const newBtn = el("button", { class: "btn ghost", type: "button", title: "新建对话" }, icon("plus"), "新建");
  const historyBtn = el("button", { class: "btn ghost", type: "button", title: "查看对话历史" }, icon("clock"), "历史");
  let activeSession = null;

  async function reload() {
    const store = await refreshSessionsFromBackend();
    renderStore(store);
  }

  function renderStore(store, extra = null) {
    if (chatSending && !extra) return;
    const session = currentSession(store);
    activeSession = session;
    titleEl.textContent = session?.title || "新对话";
    titleEl.title = "重命名对话";
    const messages = extra ? [...(session?.messages || []), extra] : session?.messages || [];
    if (!messages.length) {
      thread.replaceChildren(
        el("div", { class: "notes-empty" }, "和 DeepSeek 对话。新建开始一轮，历史里可加载之前的会话。")
      );
      return;
    }
    thread.replaceChildren(...messages.map((message) => bubble(message)));
    thread.scrollTop = thread.scrollHeight;
  }

  function bubble(message) {
    const node = el("article", { class: `note-bubble chat-bubble ${message.role}${message.pending ? " pending" : ""}` });
    if (message.reasoning) {
      node.append(
        el(
          "details",
          { class: "chat-thinking" },
          el("summary", {}, "思考过程"),
          el("div", { class: "note-text" }, message.reasoning)
        )
      );
    }
    node.append(
      el(
        "div",
        { class: message.pending ? "note-text chat-stream" : "note-text" },
        message.content || (message.pending ? "正在回复…" : "")
      )
    );
    return node;
  }

  function paintSendButton() {
    const active = chatBusy;
    sendBtn.classList.toggle("primary", !active);
    sendBtn.classList.toggle("danger", active);
    sendBtn.replaceChildren(icon(active ? "stop" : "send"));
    const label = active ? "停止" : "发送";
    sendBtn.title = label;
    sendBtn.setAttribute("aria-label", label);
  }

  function paintStream(next) {
    const node = document.querySelector("#content .chat-bubble.pending .chat-stream");
    if (!node) return;
    node.textContent = next.content || "正在回复…";
    const threadNode = node.closest(".chat-thread");
    if (threadNode) threadNode.scrollTop = threadNode.scrollHeight;
  }

  async function publishChat() {
    if (thread.isConnected) {
      await reload();
      return;
    }
    if (refreshLiveChat) await refreshLiveChat();
  }

  async function send() {
    if (chatBusy) return;
    const text = input.value.trim();
    if (!text) return;
    const controller = new AbortController();
    stopController = controller;
    chatBusy = true;
    chatSending = true;
    paintLiveSend?.();
    let partial = { content: "", reasoning: "" };
    try {
      await appendMessage({ role: "user", content: text });
      input.value = "";
      input.style.height = "auto";
      const store = await loadChat();
      renderStore(store, { role: "assistant", content: "", pending: true });
      const session = currentSession(store);
      if (!session) throw new Error("请先新建对话");
      const reply = await completeChat(session.messages, {
        signal: controller.signal,
        onDelta(next) {
          partial = next;
          paintStream(next);
        },
      });
      if (controller.signal.aborted) {
        const content = partial.content.trim();
        if (content) {
          await appendMessage({ role: "assistant", content, reasoning: String(partial.reasoning || "").trim() });
        }
        toast("已停止");
      } else {
        await appendMessage({ role: "assistant", content: reply.content, reasoning: reply.reasoning });
      }
    } catch (error) {
      if (controller.signal.aborted) {
        const content = partial.content.trim();
        try {
          if (content) {
            await appendMessage({ role: "assistant", content, reasoning: String(partial.reasoning || "").trim() });
          }
          toast("已停止");
        } catch (saveError) {
          toast(saveError instanceof Error ? saveError.message : "发送失败");
        }
      } else {
        toast(error instanceof Error ? error.message : "发送失败");
      }
    } finally {
      if (stopController === controller) stopController = null;
      chatSending = false;
      try {
        await publishChat();
      } catch (error) {
        toast(error instanceof Error ? error.message : "发送失败");
      }
      chatBusy = false;
      paintLiveSend?.();
      if (input.isConnected) input.focus();
    }
  }

  function openRename(session, returnToHistory) {
    const input = el("input", { class: "input", id: "session-title", value: session.title || "", placeholder: "对话标题" });
    openSheet(
      "重命名对话",
      el("div", { class: "field" }, el("label", {}, "标题"), input),
      [
        el(
          "button",
          {
            class: "btn ghost",
            type: "button",
            onClick: () => {
              closeSheet();
              if (returnToHistory) openHistory();
            },
          },
          "取消"
        ),
        el(
          "button",
          {
            class: "btn primary",
            type: "button",
            onClick: async () => {
              try {
                await renameSession(session.id, input.value);
                closeSheet();
                await reload();
                if (returnToHistory) await openHistory();
              } catch (error) {
                toast(error instanceof Error ? error.message : "重命名失败");
              }
            },
          },
          "保存"
        ),
      ]
    );
    queueMicrotask(() => {
      input.focus();
      input.select();
    });
  }

  titleEl.addEventListener("click", () => {
    if (!activeSession) {
      toast("请先新建对话");
      return;
    }
    openRename(activeSession, false);
  });

  async function openHistory() {
    const body = el("div", { class: "chat-history-list" });
    let sessions = [];
    let currentSessionId = null;

    function visibleSessions() {
      return sessions.filter((session) => sessionMatchesQuery(session, historyQuery));
    }

    async function loadOne(session) {
      try {
        await loadSession(session.id);
        closeSheet();
        await reload();
      } catch (error) {
        toast(error instanceof Error ? error.message : "加载对话失败");
      }
    }

    function paint() {
      const keyword = historyQuery.trim();
      const visible = visibleSessions();
      if (!visible.length) {
        body.replaceChildren(el("div", { class: "notes-empty" }, keyword ? "没有匹配的对话" : "还没有对话历史"));
        return;
      }
      body.replaceChildren(
        ...visible.map((session) => {
          const active = String(session.id) === String(currentSessionId);
          const row = el("div", { class: `chat-history-item${active ? " active" : ""}` });
          const main = el(
            "button",
            { class: "chat-history-main", type: "button" },
            el("strong", { class: "ellipsis" }, session.title || "新对话"),
            el(
              "small",
              { class: "muted" },
              `${formatDateTime(session.updatedAt || session.createdAt)} · ${session.messageCount ?? session.messages.length ?? 0} 条消息${active ? " · 当前" : ""}`
            )
          );
          const rename = el("button", { class: "btn ghost icon", type: "button", title: "重命名对话" }, icon("pencil"));
          const del = el("button", { class: "btn ghost icon", type: "button", title: "删除对话" }, icon("trash"));
          main.addEventListener("click", () => loadOne(session));
          rename.addEventListener("click", (event) => {
            event.stopPropagation();
            openRename(session, true);
          });
          del.addEventListener("click", async (event) => {
            event.stopPropagation();
            const ok = await confirmDelete(`确定删除对话「${session.title || "新对话"}」吗？此操作不可恢复。`);
            if (!ok) {
              openHistory();
              return;
            }
            await deleteSession(session.id);
            await reload();
            openHistory();
          });
          row.append(main, rename, del);
          return row;
        })
      );
    }

    const queryInput = el("input", {
      class: "input",
      id: "history-search",
      placeholder: "搜索标题或内容",
      value: historyQuery,
      onInput: (event) => {
        historyQuery = event.target.value;
        paint();
        restoreFocus("history-search", event.target.selectionStart, event.target.selectionEnd);
      },
      onKeydown: (event) => {
        if (event.key !== "Enter") return;
        const matches = visibleSessions();
        if (matches.length !== 1) return;
        event.preventDefault();
        loadOne(matches[0]);
      },
    });

    openSheet(
      "对话历史",
      el("div", { class: "chat-history" }, queryInput, body),
      [el("button", { class: "btn ghost", type: "button", onClick: closeSheet }, "关闭")]
    );
    const store = await refreshSessionsFromBackend();
    sessions = sortedSessions(store);
    currentSessionId = store.currentSessionId;
    paint();
    restoreFocus("history-search");
  }

  newBtn.addEventListener("click", async () => {
    await createSession();
    await reload();
    input.focus();
  });
  historyBtn.addEventListener("click", openHistory);
  sendBtn.addEventListener("click", () => {
    if (chatSending) {
      stopController?.abort();
      return;
    }
    if (chatBusy) return;
    send();
  });
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!chatBusy) send();
    }
  });
  input.addEventListener("input", () => {
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, 120)}px`;
  });

  page.append(
    el("div", { class: "chat-toolbar" }, titleEl, newBtn, historyBtn),
    thread,
    el("div", { class: "notes-composer" }, input, sendBtn)
  );
  paintLiveSend = paintSendButton;
  refreshLiveChat = reload;
  if (chatBusy) paintSendButton();
  reload();
  queueMicrotask(() => input.focus());
  return page;
}
