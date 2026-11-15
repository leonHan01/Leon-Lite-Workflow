import { chrome } from "../shim.js";
import * as api from "./api.js";
import { groupByCreatedDate, normalizeFoldKeys } from "./day-groups.js";
import { el, icon, restoreFocus } from "./dom.js";
import { linkLabel, openSavedUrl, splitTextLinks } from "./text-links.js";
import { notifyTodoCompleted, notifyTodoCreated } from "./feishu.js";
import { mergeDirtyWithRemote, pushDirtyRecords, withBackend } from "./persist.js";
import { TODOS_KEY } from "./settings.js";
import { renderAnalysisBar } from "./work-analysis.js";

function emptyTodos() {
  return { todos: [] };
}

export async function loadTodos() {
  const data = await chrome.storage.local.get(TODOS_KEY);
  const stored = data[TODOS_KEY];
  if (!stored || !Array.isArray(stored.todos)) return emptyTodos();
  return stored;
}

export async function saveTodos(state) {
  await chrome.storage.local.set({ [TODOS_KEY]: state });
}

function fromRemote(item) {
  return {
    id: String(item.id),
    content: item.content || "",
    done: Boolean(item.done),
    sortOrder: item.sortOrder ?? 0,
    createdAt: api.parseTime(item.createTime),
    updatedAt: api.parseTime(item.updateTime),
    dirty: false,
  };
}

function sortTodos(todos) {
  return [...todos].sort((a, b) => Number(a.done) - Number(b.done) || (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
}

function sortTodoItems(items) {
  items.sort((a, b) => Number(a.done) - Number(b.done) || (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || (b.createdAt ?? 0) - (a.createdAt ?? 0));
  return items;
}

export function groupTodosByCreatedDate(todos, now = Date.now()) {
  const grouped = groupByCreatedDate(todos, now);
  for (const group of grouped.recent) sortTodoItems(group.items);
  if (grouped.earlier) {
    for (const group of grouped.earlier.groups) sortTodoItems(group.items);
  }
  return grouped;
}

export const TODO_FOLDS_KEY = "myWorkflow.todoFolds";

export function normalizeTodoFolds(stored) {
  return normalizeFoldKeys(stored, ["earlier"]);
}

let todoFolds = null;
let todoQuery = "";

async function loadTodoFolds() {
  if (todoFolds) return todoFolds;
  const data = await chrome.storage.local.get(TODO_FOLDS_KEY);
  todoFolds = new Set(normalizeTodoFolds(data[TODO_FOLDS_KEY]));
  return todoFolds;
}

function rememberTodoFold(key, collapsed) {
  if (collapsed) todoFolds.add(key);
  else todoFolds.delete(key);
  return chrome.storage.local.set({ [TODO_FOLDS_KEY]: [...todoFolds] });
}

export function matchingTodos(todos, keyword) {
  const query = String(keyword || "").trim().toLowerCase();
  if (!query) return todos;
  return todos.filter((item) => String(item.content || "").toLowerCase().includes(query));
}

export function splitTodoText(content) {
  return splitTextLinks(content);
}

export const TODO_OPEN_ONLY_KEY = "myWorkflow.todoOpenOnly";

export function normalizeTodoOpenOnly(stored) {
  return stored === true;
}

let todoOpenOnly = false;
let todoOpenOnlyTouched = false;

async function loadTodoOpenOnly() {
  if (todoOpenOnlyTouched) return todoOpenOnly;
  const data = await chrome.storage.local.get(TODO_OPEN_ONLY_KEY);
  if (todoOpenOnlyTouched) return todoOpenOnly;
  todoOpenOnly = normalizeTodoOpenOnly(data[TODO_OPEN_ONLY_KEY]);
  todoOpenOnlyTouched = true;
  return todoOpenOnly;
}

function rememberTodoOpenOnly(value) {
  todoOpenOnlyTouched = true;
  todoOpenOnly = value === true;
  return chrome.storage.local.set({ [TODO_OPEN_ONLY_KEY]: todoOpenOnly });
}

export async function refreshTodosFromBackend() {
  const local = await loadTodos();
  if (!(await api.shouldUseBackend(true))) return local;
  const next = await withBackend("加载待办失败", async () => {
    const remote = (await api.listTodos() || []).map(fromRemote);
    const merged = { todos: sortTodos(mergeDirtyWithRemote(local.todos, remote)) };
    await saveTodos(merged);
    return merged;
  });
  return next || local;
}

export async function createTodo(content) {
  const text = String(content || "").trim();
  if (!text) throw new Error("待办内容不能为空");
  const item = fromRemote(await api.createTodo({ content: text, done: false }));
  const state = await loadTodos(); state.todos.push(item); state.todos = sortTodos(state.todos); await saveTodos(state);
  notifyTodoCreated(text);
  return item;
}

export async function updateTodo(todoId, patch) {
  const state = await loadTodos(); const item = state.todos.find((todo) => String(todo.id) === String(todoId));
  if (!item) throw new Error("待办不存在");
  if (!api.isRemoteId(todoId)) throw new Error("旧本地待办尚未同步到后端");
  const content = patch.content != null ? String(patch.content).trim() : item.content;
  if (!content) throw new Error("待办内容不能为空");
  const updated = { ...item, content, done: patch.done != null ? Boolean(patch.done) : item.done, updatedAt: Date.now(), dirty: false };
  await api.updateTodo(todoId, { content: updated.content, done: updated.done });
  const latest = await loadTodos(); const index = latest.todos.findIndex((todo) => String(todo.id) === String(todoId));
  if (index >= 0) latest.todos[index] = updated;
  latest.todos = sortTodos(latest.todos); await saveTodos(latest);
  if (!item.done && updated.done) notifyTodoCompleted(updated.content);
  return updated;
}

export async function deleteTodo(todoId) {
  if (!api.isRemoteId(todoId)) throw new Error("旧本地待办尚未同步到后端");
  await api.deleteTodo(todoId);
  const state = await loadTodos(); state.todos = state.todos.filter((item) => String(item.id) !== String(todoId)); await saveTodos(state);
}

export async function pushLocalTodos() {
  if (!(await api.shouldUseBackend(true))) return false;
  const state = await loadTodos();
  const checkpoint = async (item, oldId) => {
    const latest = await loadTodos();
    const index = latest.todos.findIndex((entry) => String(entry.id) === String(oldId));
    if (index >= 0) { latest.todos[index] = item; await saveTodos(latest); }
  };
  const ok = await pushDirtyRecords(state.todos, {
    create: async (item, oldId) => {
      const remote = await api.createTodo({ content: item.content });
      item.id = String(remote.id);
      await checkpoint(item, oldId);
      if (item.done) await api.updateTodo(String(remote.id), { content: item.content, done: true });
      return remote;
    },
    update: (item) => api.updateTodo(item.id, { content: item.content, done: item.done }),
    checkpoint: (item, oldId) => checkpoint(item, api.isRemoteId(oldId) ? oldId : item.id),
  });
  return ok;
}

export function renderTodosPage({ toast, confirmDelete, openSheet, closeSheet }) {
  const page = el("div", { class: "todos-page" });
  const list = el("div", { class: "todo-list" });
  const input = el("input", { class: "input", placeholder: "添加待办，回车保存", id: "todo-input" });
  const addBtn = el("button", { class: "btn primary icon", type: "button", title: "发送" }, icon("send"));
  const search = el("input", {
    class: "input",
    id: "todo-search",
    placeholder: "搜索待办",
    value: todoQuery,
    onInput: (event) => {
      todoQuery = event.target.value;
      renderList(currentTodos);
      restoreFocus("todo-search", event.target.selectionStart, event.target.selectionEnd);
    },
  });
  const openOnlyBtn = el(
    "button",
    {
      class: `btn todo-open-only${todoOpenOnly ? " primary" : ""}`,
      type: "button",
      "aria-pressed": String(todoOpenOnly),
      onClick: () => {
        rememberTodoOpenOnly(!todoOpenOnly);
        paintOpenOnly();
        renderList(currentTodos);
      },
    },
    "只看未完成"
  );
  let currentTodos = [];

  function paintOpenOnly() {
    openOnlyBtn.classList.toggle("primary", todoOpenOnly);
    openOnlyBtn.setAttribute("aria-pressed", String(todoOpenOnly));
  }

  async function reload() {
    const [state] = await Promise.all([refreshTodosFromBackend(), loadTodoFolds(), loadTodoOpenOnly()]);
    paintOpenOnly();
    currentTodos = state.todos;
    renderList(currentTodos);
  }

  function renderList(todos) {
    const searching = Boolean(todoQuery.trim());
    const pool = searching || todoOpenOnly ? todos.filter((item) => !item.done) : todos;
    const visible = matchingTodos(pool, todoQuery);
    if (!todos.length) {
      list.replaceChildren(el("div", { class: "empty" }, "还没有待办事项"));
      return;
    }
    if (!pool.length) {
      list.replaceChildren(el("div", { class: "empty" }, "没有未完成的待办"));
      return;
    }
    if (!visible.length) {
      list.replaceChildren(el("div", { class: "empty" }, "没有匹配的待办"));
      return;
    }
    const grouped = groupTodosByCreatedDate(visible);
    const sections = grouped.recent.map((group) => daySection(group, false, searching));
    if (grouped.earlier) {
      sections.push(
        foldSection(
          grouped.earlier,
          grouped.earlier.groups.map((group) => daySection(group, true, searching)),
          false,
          searching,
          grouped.earlier.groups.reduce((sum, group) => sum + group.items.length, 0)
        )
      );
    }
    list.replaceChildren(...sections);
  }

  function daySection(group, nested = false, searching = false) {
    return foldSection(group, group.items.map((item) => todoRow(item)), nested, searching, group.items.length);
  }

  function foldSection(group, bodyChildren, nested = false, searching = false, count = bodyChildren.length) {
    const open = searching || !todoFolds.has(group.key);
    const mark = icon("chevronLeft");
    mark.classList.add("todo-fold");
    const header = el(
      "button",
      {
        class: "todo-day",
        type: "button",
        "aria-expanded": String(open),
        onClick: () => {
          const next = todoFolds.has(group.key);
          rememberTodoFold(group.key, !next);
          section.classList.toggle("collapsed", !next);
          header.setAttribute("aria-expanded", String(next));
        },
      },
      mark,
      el("span", { class: "todo-day-date" }, group.ymd),
      group.weekday ? el("span", {}, group.weekday) : null,
      el("span", { class: "badge todo-count" }, String(count)),
      group.distance ? el("span", { class: "todo-day-distance" }, group.distance) : null
    );
    const section = el(
      "section",
      { class: `todo-group${open ? "" : " collapsed"}${nested ? " nested" : ""}` },
      header,
      el("div", { class: "todo-group-body" }, ...bodyChildren)
    );
    return section;
  }

  function todoRow(item) {
    const box = el("input", { type: "checkbox", checked: item.done });
    box.addEventListener("change", async () => {
      try {
        await updateTodo(item.id, { done: box.checked });
        await reload();
      } catch (error) {
        toast(error instanceof Error ? error.message : "更新失败");
      }
    });
    return el(
      "article",
      { class: `todo-item ${item.done ? "done" : ""}` },
      box,
      todoLabel(item),
      el(
        "div",
        { class: "todo-actions" },
        el("button", { class: "btn ghost icon", type: "button", title: "编辑", onClick: () => openEdit(item) }, icon("pencil")),
        el("button", { class: "btn ghost icon", type: "button", title: "删除", onClick: () => removeTodo(item) }, icon("trash"))
      )
    );
  }

  function todoLabel(item) {
    const parts = splitTodoText(item.content);
    if (!parts.some((part) => part.url)) {
      return el("button", { class: "todo-text", type: "button", onClick: () => openEdit(item) }, item.content);
    }
    return el(
      "div",
      { class: "todo-text" },
      ...parts.map((part) =>
        part.url
          ? el(
              "button",
              {
                class: "todo-link",
                type: "button",
                title: part.url,
                onClick: async (event) => {
                  event.stopPropagation();
                  try {
                    await openSavedUrl(part.url);
                  } catch (error) {
                    toast(error instanceof Error ? error.message : "无法打开链接");
                  }
                },
              },
              linkLabel(part.url)
            )
          : el("button", { class: "todo-body", type: "button", onClick: () => openEdit(item) }, part.text)
      )
    );
  }

  function openEdit(item) {
    const editor = el("textarea", { class: "textarea", text: item.content });
    openSheet(
      "编辑待办",
      el("div", { class: "field" }, el("label", {}, "内容"), editor),
      [
        el("button", { class: "btn ghost", type: "button", onClick: closeSheet }, "取消"),
        el(
          "button",
          {
            class: "btn primary",
            type: "button",
            onClick: async () => {
              try {
                await updateTodo(item.id, { content: editor.value });
                closeSheet();
                await reload();
              } catch (error) {
                toast(error instanceof Error ? error.message : "保存失败");
              }
            },
          },
          "保存"
        ),
      ]
    );
    editor.focus();
  }

  async function removeTodo(item) {
    const ok = await confirmDelete(`确定删除待办「${item.content}」吗？此操作不可恢复。`);
    if (!ok) return;
    await deleteTodo(item.id);
    await reload();
  }

  async function add() {
    const text = input.value.trim();
    if (!text) return;
    addBtn.disabled = true;
    try {
      await createTodo(text);
      input.value = "";
      await reload();
    } catch (error) {
      toast(error instanceof Error ? error.message : "添加失败");
    } finally {
      addBtn.disabled = false;
      input.focus();
    }
  }

  addBtn.addEventListener("click", add);
  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter") {
      event.preventDefault();
      add();
    }
  });

  page.append(
    renderAnalysisBar({ toast, openSheet, closeSheet }),
    el("div", { class: "list-search" }, search, openOnlyBtn),
    list,
    el("div", { class: "notes-composer" }, input, addBtn)
  );
  reload();
  queueMicrotask(() => input.focus());
  return page;
}
