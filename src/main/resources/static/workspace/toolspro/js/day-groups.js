const WEEKDAYS = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

function startOfLocalDay(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return NaN;
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

function dayDistance(diff) {
  if (diff === 0) return "今天";
  if (diff > 0) return `${diff}天前`;
  return `${-diff}天后`;
}

function monthCutoff(now) {
  const today = new Date(startOfLocalDay(now));
  const day = today.getDate();
  const previous = new Date(today.getFullYear(), today.getMonth() - 1, 1);
  const lastDay = new Date(previous.getFullYear(), previous.getMonth() + 1, 0).getDate();
  previous.setDate(Math.min(day, lastDay));
  return previous.getTime();
}

function compareDayGroups(a, b) {
  if (a.day == null) return 1;
  if (b.day == null) return -1;
  return b.day - a.day;
}

export function normalizeFoldKeys(stored, fallback = []) {
  if (!Array.isArray(stored)) return [...fallback];
  return [...new Set(stored.map((key) => String(key)))];
}

export function groupByCreatedDate(items, now = Date.now()) {
  const today = startOfLocalDay(now);
  const cutoff = monthCutoff(now);
  const groups = new Map();
  for (const item of items) {
    const day = startOfLocalDay(item.createdAt);
    const key = Number.isNaN(day) ? "unknown" : String(day);
    if (!groups.has(key)) {
      if (Number.isNaN(day)) {
        groups.set(key, { key, day: null, ymd: "日期未知", weekday: "", distance: "", items: [] });
      } else {
        const date = new Date(day);
        const diff = Math.round((today - day) / 86400000);
        groups.set(key, {
          key,
          day,
          ymd: `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日`,
          weekday: WEEKDAYS[date.getDay()],
          distance: dayDistance(diff),
          items: [],
        });
      }
    }
    groups.get(key).items.push(item);
  }
  const ordered = [...groups.values()].sort(compareDayGroups);
  const recent = ordered.filter((group) => group.day == null || group.day >= cutoff);
  const earlierDays = ordered.filter((group) => group.day != null && group.day < cutoff);
  return {
    recent,
    earlier: earlierDays.length
      ? { key: "earlier", ymd: "更早", distance: "超过一个月", groups: earlierDays }
      : null,
  };
}
