import { bindActions } from './events.js';
export function mount(root, route) {
const byId = id => root.querySelector('#' + CSS.escape(id));
let currentUrl = new URL(route.url);

        let allKVData = [];
        let allIcons = [];
        let pendingDeleteId = null;

        function loadIcons() {
            fetch('/api/simple-kv/icons')
                .then(r => r.json())
                .then(icons => {
                    allIcons = icons;
                    ['add', 'edit'].forEach(prefix => {
                        buildIconPicker(prefix);
                    });
                })
                .catch(err => console.error('load icons error:', err));
        }

        function buildIconPicker(prefix) {
            const dropdown = byId(prefix + 'IconDropdown');
            dropdown.innerHTML = '';
            const searchWrap = document.createElement('div');
            searchWrap.className = 'icon-picker-search';
            const searchInput = document.createElement('input');
            searchInput.type = 'text';
            searchInput.id = prefix + 'IconSearch';
            searchInput.placeholder = '搜索图标...';
            searchInput.oninput = function() { filterIcons(prefix); };
            searchInput.onclick = function(event) { event.stopPropagation(); };
            searchWrap.appendChild(searchInput);
            dropdown.appendChild(searchWrap);
            // 默认选项
            const defaultItem = document.createElement('div');
            defaultItem.className = 'icon-picker-item' + (!byId(prefix + 'Icon').value ? ' selected' : '');
            defaultItem.dataset.iconName = '';
            defaultItem.innerHTML = '<span style="width:20px;text-align:center;color:var(--lw-muted);font-size:12px;">—</span> <span>默认</span>';
            defaultItem.onclick = function() { selectIcon(prefix, ''); };
            dropdown.appendChild(defaultItem);
            // 图标选项
            allIcons.forEach(name => {
                const item = document.createElement('div');
                item.className = 'icon-picker-item' + (byId(prefix + 'Icon').value === name ? ' selected' : '');
                item.dataset.iconName = name.replace(/\.png$/i, '').toLowerCase();
                item.innerHTML = '<img src="/api/simple-kv/icon-img/' + encodeURIComponent(name) + '"> <span>' + escapeHtml(name.replace(/\.png$/i, '')) + '</span>';
                item.onclick = function() { selectIcon(prefix, name); };
                dropdown.appendChild(item);
            });
            updateIconPickerLabel(prefix);
        }

        function filterIcons(prefix) {
            const keyword = (byId(prefix + 'IconSearch').value || '').trim().toLowerCase();
            const items = byId(prefix + 'IconDropdown').querySelectorAll('.icon-picker-item');
            items.forEach(item => {
                const iconName = item.dataset.iconName || '';
                item.style.display = !keyword || iconName.includes(keyword) ? '' : 'none';
            });
        }

        function toggleIconPicker(prefix) {
            const dropdown = byId(prefix + 'IconDropdown');
            // 关闭其他
            root.querySelectorAll('.icon-picker-dropdown.open').forEach(d => {
                if (d !== dropdown) d.classList.remove('open');
            });
            dropdown.classList.toggle('open');
            if (dropdown.classList.contains('open')) {
                const search = byId(prefix + 'IconSearch');
                if (search) {
                    search.value = '';
                    filterIcons(prefix);
                    setTimeout(() => {
                        search.focus({ preventScroll: true });
                        if (dropdown.closest('.modal')) dropdown.scrollIntoView({ block: 'nearest' });
                    }, 0);
                }
            }
        }

        function selectIcon(prefix, value) {
            byId(prefix + 'Icon').value = value;
            byId(prefix + 'IconDropdown').classList.remove('open');
            updateIconPickerLabel(prefix);
            // 更新选中状态
            const items = byId(prefix + 'IconDropdown').querySelectorAll('.icon-picker-item');
            items.forEach(item => item.classList.remove('selected'));
            const currentVal = value;
            items.forEach(item => {
                const img = item.querySelector('img');
                const itemVal = img ? decodeURIComponent(img.src.split('/').pop()) : '';
                if (currentVal === '' && !img) item.classList.add('selected');
                if (currentVal !== '' && itemVal === currentVal) item.classList.add('selected');
            });
        }

        function updateIconPickerLabel(prefix) {
            const value = byId(prefix + 'Icon').value;
            const label = byId(prefix + 'IconPicker').querySelector('.icon-picker-label');
            if (value) {
                label.innerHTML = '<img src="/api/simple-kv/icon-img/' + encodeURIComponent(value) + '" style="width:20px;height:20px;border-radius:3px;vertical-align:middle;"> ' + escapeHtml(value.replace(/\.png$/i, ''));
            } else {
                label.textContent = '默认';
            }
        }

        // 点击外部关闭下拉
        root.addEventListener('click', function(e) {
            if (!e.target.closest('.icon-picker')) {
                root.querySelectorAll('.icon-picker-dropdown.open').forEach(d => d.classList.remove('open'));
            }
        });

        function loadList() {
            fetch('/api/simple-kv/list')
                .then(r => r.json())
                .then(data => {
                    allKVData = data;
                    filterList();
                })
                .catch(err => console.error('load error:', err));
        }

        function filterList() {
            const keyword = (byId('searchInput').value || '').trim().toLowerCase();
            const data = keyword ? allKVData.filter(item => {
                return (item.kvKey || '').toLowerCase().includes(keyword)
                    || (item.kvValue || '').toLowerCase().includes(keyword)
                    || (item.kvTags || '').toLowerCase().includes(keyword)
                    || (item.kvCategory || '').toLowerCase().includes(keyword)
                    || (item.kvAction || '').toLowerCase().includes(keyword);
            }) : allKVData;

            const grid = byId('kvCategoryGrid');
            byId('countLabel').textContent = data.length + ' 条';
            if (data.length === 0) {
                grid.innerHTML = '<div class="empty-state">' + (keyword ? '无匹配结果' : '暂无数据，请添加') + '</div>';
                return;
            }
            const textItems = data.filter(isTextItem);
            const categoryItems = data.filter(item => !isTextItem(item));
            const groupedData = groupByCategory(categoryItems);
            const categoryHtml = Array.from(groupedData.entries())
                .sort((a, b) => b[1].length - a[1].length || a[0].localeCompare(b[0], 'zh-CN'))
                .map(([category, items]) => renderCategoryPanel(category, items))
                .join('');
            grid.innerHTML = categoryHtml + renderTextSection(textItems);
        }

        function exportKVJson() {
            const exportData = allKVData.map(toPortableKVItem);
            const json = JSON.stringify(exportData, null, 2);
            const blob = new Blob([json], { type: 'application/json;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = 'simple-kv-' + formatDateForFileName(new Date()) + '.json';
            root.appendChild(link);
            link.click();
            link.remove();
            URL.revokeObjectURL(url);
        }

        function triggerImportJson() {
            byId('importJsonInput').click();
        }

        async function importKVJson(event) {
            const file = event.target.files && event.target.files[0];
            event.target.value = '';
            if (!file) return;

            try {
                const text = await file.text();
                const parsed = JSON.parse(text);
                const items = extractKVItems(parsed).map(normalizeImportKVItem).filter(item => item.kvKey);
                if (items.length === 0) {
                    alert('JSON 中没有可导入的数据');
                    return;
                }
                if (!confirm('确认导入 ' + items.length + ' 条 KV？同名 Key 会更新现有数据。')) {
                    return;
                }
                const result = await saveImportedKVItems(items);
                loadList();
                alert('导入完成：新增 ' + result.created + ' 条，更新 ' + result.updated + ' 条');
            } catch (err) {
                console.error('import json error:', err);
                alert('导入失败：' + (err.message || 'JSON 格式错误'));
            }
        }

        function toPortableKVItem(item) {
            return {
                kvKey: item.kvKey || '',
                kvValue: item.kvValue || '',
                kvType: item.kvType || 'text',
                kvTags: item.kvTags || '',
                kvCategory: item.kvCategory || '',
                kvAction: item.kvAction || '',
                kvIcon: item.kvIcon || ''
            };
        }

        function extractKVItems(parsed) {
            if (Array.isArray(parsed)) return parsed;
            if (parsed && Array.isArray(parsed.items)) return parsed.items;
            if (parsed && Array.isArray(parsed.data)) return parsed.data;
            if (parsed && Array.isArray(parsed.simpleKV)) return parsed.simpleKV;
            if (parsed && Array.isArray(parsed.simpleKv)) return parsed.simpleKv;
            throw new Error('JSON 须为数组，或包含 items/data/simpleKV 数组');
        }

        function normalizeImportKVItem(item) {
            item = item || {};
            const kvValue = valueToString(item.kvValue ?? item.value);
            const kvType = valueToString(item.kvType ?? item.type).toLowerCase();
            return {
                kvKey: valueToString(item.kvKey ?? item.key).trim(),
                kvValue: kvValue.trim(),
                kvType: kvType === 'url' || kvType === 'text' ? kvType : (looksLikeUrl(kvValue) ? 'url' : 'text'),
                kvTags: listValueToString(item.kvTags ?? item.tags),
                kvCategory: valueToString(item.kvCategory ?? item.category).trim(),
                kvAction: listValueToString(item.kvAction ?? item.action),
                kvIcon: valueToString(item.kvIcon ?? item.icon).trim()
            };
        }

        async function saveImportedKVItems(items) {
            let created = 0;
            let updated = 0;
            for (const item of items) {
                const existing = allKVData.find(row => (row.kvKey || '') === item.kvKey);
                const payload = existing && existing.id ? { id: existing.id, ...item } : item;
                const response = await fetch('/api/simple-kv/save', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                const data = await response.json();
                if (!response.ok || data.status !== 'success') {
                    throw new Error('保存 "' + item.kvKey + '" 失败：' + (data.message || response.status));
                }
                if (existing && existing.id) {
                    updated += 1;
                } else {
                    created += 1;
                }
            }
            return { created, updated };
        }

        function listValueToString(value) {
            if (Array.isArray(value)) {
                return value.map(v => valueToString(v).trim()).filter(Boolean).join(',');
            }
            return valueToString(value).trim();
        }

        function valueToString(value) {
            return value === undefined || value === null ? '' : String(value);
        }

        function looksLikeUrl(value) {
            const v = valueToString(value).trim().toLowerCase();
            return v.startsWith('http://') || v.startsWith('https://') || v.startsWith('ftp://');
        }

        function formatDateForFileName(date) {
            const yyyy = date.getFullYear();
            const mm = String(date.getMonth() + 1).padStart(2, '0');
            const dd = String(date.getDate()).padStart(2, '0');
            const hh = String(date.getHours()).padStart(2, '0');
            const mi = String(date.getMinutes()).padStart(2, '0');
            const ss = String(date.getSeconds()).padStart(2, '0');
            return yyyy + mm + dd + '-' + hh + mi + ss;
        }

        function groupByCategory(data) {
            const groups = new Map();
            data.forEach(item => {
                const category = (item.kvCategory || '').trim() || '未分类';
                if (!groups.has(category)) {
                    groups.set(category, []);
                }
                groups.get(category).push(item);
            });
            return groups;
        }

        function isTextItem(item) {
            return item.kvType !== 'url';
        }

        function renderCategoryPanel(category, items) {
            return '<section class="category-panel">' +
                '<div class="category-panel-header">' +
                    '<div class="category-title">' + escapeHtml(category) + '</div>' +
                    '<div class="category-count">' + items.length + ' 条</div>' +
                '</div>' +
                '<div class="category-items">' + items.map(item => renderKVItem(item)).join('') + '</div>' +
            '</section>';
        }

        function renderTextSection(items) {
            if (items.length === 0) {
                return '';
            }
            const sortedItems = items.slice().sort((a, b) => (a.kvKey || '').localeCompare(b.kvKey || '', 'zh-CN'));
            return '<section class="text-section">' +
                '<div class="text-section-header">' +
                    '<div>文本类型</div>' +
                    '<div class="category-count">' + items.length + ' 条</div>' +
                '</div>' +
                '<div class="text-item-grid">' + sortedItems.map(item => renderKVItem(item)).join('') + '</div>' +
            '</section>';
        }

        function renderKVItem(item) {
                const typeClass = item.kvType === 'url' ? 'url' : 'text';
                const typeLabel = item.kvType === 'url' ? '网址' : '文本';
                let valueHtml = escapeHtml(item.kvValue || '');
                if (item.kvType === 'url' && /^https?:\/\//i.test(item.kvValue || '')) {
                    valueHtml = '<a href="' + escapeHtml(item.kvValue) + '" target="_blank" rel="noopener noreferrer">' + escapeHtml(item.kvValue) + '</a>';
                }
                let tagsHtml = '';
                if (item.kvTags) {
                    tagsHtml = item.kvTags.split(',').map(t => t.trim()).filter(t => t).map(t =>
                        '<span class="tag-badge">' + escapeHtml(t) + '</span>'
                    ).join('');
                }
                let actionHtml = '';
                if (item.kvAction) {
                    actionHtml = item.kvAction.split(',').map(t => t.trim()).filter(t => t).map(t =>
                        '<span class="tag-badge" style="background:#eadfcf;color:#8a5718;border-color:#d2b98f;">' + escapeHtml(t) + '</span>'
                    ).join('');
                }
                let iconHtml = '';
                if (item.kvIcon) {
                    iconHtml = '<img class="kv-item-icon" src="/api/simple-kv/icon-img/' + encodeURIComponent(item.kvIcon) + '" title="' + escapeHtml(item.kvIcon) + '">';
                }
                return '<div class="kv-item">' +
                    '<div class="kv-item-main">' +
                        '<div>' +
                            '<div class="kv-item-title">' +
                                iconHtml +
                                '<strong>' + escapeHtml(item.kvKey) + '</strong>' +
                                '<span class="type-badge ' + typeClass + '">' + typeLabel + '</span>' +
                            '</div>' +
                            '<div class="kv-item-value">' + valueHtml + '</div>' +
                            '<div class="kv-item-meta">' + tagsHtml + actionHtml + '</div>' +
                        '</div>' +
                        '<div class="actions-cell">' +
                        '<button class="btn" style="padding:4px 12px;font-size:12px;" data-ws-click="openEditModal" data-ws-arg="' + item.id + '">编辑</button>' +
                        '<button class="btn-delete" data-ws-click="deleteKV" data-ws-arg="' + item.id + '">删除</button>' +
                        '</div>' +
                    '</div>' +
                '</div>';
        }

        function getActionValues(prefix) {
            const actions = [];
            if (byId(prefix + 'ActionTop').checked) actions.push('置顶');
            if (byId(prefix + 'ActionHome').checked) actions.push('首页');
            return actions.join(',');
        }

        function addKV() {
            const kvKey = byId('addKey').value.trim();
            const kvValue = byId('addValue').value.trim();
            const kvType = byId('addType').value;
            const kvTags = byId('addTags').value.trim();
            const kvCategory = byId('addCategory').value.trim();
            const kvAction = getActionValues('add');
            const kvIcon = byId('addIcon').value;

            if (!kvKey) { alert('请输入 Key'); return; }

            fetch('/api/simple-kv/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ kvKey, kvValue, kvType, kvTags, kvCategory, kvAction, kvIcon })
            })
            .then(r => r.json())
            .then(data => {
                if (data.status === 'success') {
                    byId('addKey').value = '';
                    byId('addValue').value = '';
                    byId('addType').value = 'text';
                    byId('addTags').value = '';
                    byId('addCategory').value = '';
                    byId('addActionTop').checked = false;
                    byId('addActionHome').checked = false;
                    selectIcon('add', '');
                    loadList();
                } else {
                    alert('保存失败: ' + (data.message || ''));
                }
            })
            .catch(err => console.error('save error:', err));
        }

        function openEditModal(id) {
            const item = allKVData.find(d => d.id === id);
            if (!item) return;
            byId('editId').value = item.id;
            byId('editKey').value = item.kvKey || '';
            byId('editValue').value = item.kvValue || '';
            byId('editType').value = item.kvType || 'text';
            byId('editTags').value = item.kvTags || '';
            byId('editCategory').value = item.kvCategory || '';
            byId('editActionTop').checked = item.kvAction && item.kvAction.includes('置顶');
            byId('editActionHome').checked = item.kvAction && item.kvAction.includes('首页');
            selectIcon('edit', item.kvIcon || '');
            byId('editModal').classList.add('active');
        }

        function closeEditModal() {
            byId('editModal').classList.remove('active');
        }

        function saveEditKV() {
            const id = byId('editId').value;
            const kvKey = byId('editKey').value.trim();
            const kvValue = byId('editValue').value.trim();
            const kvType = byId('editType').value;
            const kvTags = byId('editTags').value.trim();
            const kvCategory = byId('editCategory').value.trim();
            const kvAction = getActionValues('edit');
            const kvIcon = byId('editIcon').value;

            if (!kvKey) { alert('请输入 Key'); return; }

            fetch('/api/simple-kv/save', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id: parseInt(id), kvKey, kvValue, kvType, kvTags, kvCategory, kvAction, kvIcon })
            })
            .then(r => r.json())
            .then(data => {
                if (data.status === 'success') {
                    closeEditModal();
                    loadList();
                } else {
                    alert('保存失败: ' + (data.message || ''));
                }
            })
            .catch(err => console.error('save error:', err));
        }

        function deleteKV(id) {
            const item = allKVData.find(d => d.id === id);
            pendingDeleteId = id;
            byId('deleteTargetName').textContent = item ? (item.kvKey || '') : ('ID: ' + id);
            byId('deleteModal').classList.add('active');
        }

        function closeDeleteModal() {
            pendingDeleteId = null;
            byId('deleteModal').classList.remove('active');
        }

        function confirmDeleteKV() {
            if (!pendingDeleteId) return;
            const id = pendingDeleteId;
            fetch('/api/simple-kv/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ id })
            })
            .then(r => r.json())
            .then(data => {
                if (data.status === 'success') {
                    closeDeleteModal();
                    loadList();
                } else {
                    alert('删除失败: ' + (data.message || ''));
                }
            })
            .catch(err => console.error('delete error:', err));
        }

        function escapeHtml(str) {
            return String(str || '').replace(/[&<>"']/g, c => ({
                '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
            }[c]));
        }

        // Close modal on overlay click
        byId('editModal').addEventListener('click', function(e) {
            if (e.target === this) closeEditModal();
        });

        byId('deleteModal').addEventListener('click', function(e) {
            if (e.target === this) closeDeleteModal();
        });

        // Close modal on Escape
        root.addEventListener('keydown', function(e) {
            if (e.key === 'Escape') {
                closeEditModal();
                closeDeleteModal();
            }
        });



bindActions(root, { loadIcons, buildIconPicker, filterIcons, toggleIconPicker, selectIcon, updateIconPickerLabel, loadList, filterList, exportKVJson, triggerImportJson, importKVJson, toPortableKVItem, extractKVItems, normalizeImportKVItem, saveImportedKVItems, listValueToString, valueToString, looksLikeUrl, formatDateForFileName, groupByCategory, isTextItem, renderCategoryPanel, renderTextSection, renderKVItem, getActionValues, addKV, openEditModal, closeEditModal, saveEditKV, deleteKV, closeDeleteModal, confirmDeleteKV, escapeHtml });
loadIcons(); loadList();
return {};
}
