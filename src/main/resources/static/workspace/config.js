import { bindActions } from './events.js';
export function mount(root, route) {
const byId = id => root.querySelector('#' + CSS.escape(id));
let currentUrl = new URL(route.url);

        function loadDropdownOptions() {
            fetch('/api/options')
                .then(response => response.json())
                .then(data => {
                    const select = byId('optionsDropdown');
                    select.innerHTML = '';
                    data.forEach(option => {
                        const opt = document.createElement('option');
                        opt.value = option.value;
                        opt.textContent = option.text;
                        select.appendChild(opt);
                    });
                    if (select.options.length) loadTextForOption();
                    else byId('configStatus').textContent = '暂无配置，可通过现有配置接口创建。';
                })
                .catch(error => {
                    byId('configStatus').textContent = '请求失败，请检查连接后重试';
                });
        }

        function loadTextForOption() {
            const select = byId('optionsDropdown');
            const selectedValue = select.value;
            const textBox = byId('editableText');

            fetch(`/api/text?selected=${encodeURIComponent(selectedValue)}`)
                .then(response=>response.text())
                .then(text => {
                    textBox.value = text; byId('configStatus').textContent = '已加载，修改后点击保存';
                })
                .catch(error => {
                    byId('configStatus').textContent = '请求失败，请检查连接后重试';
                });
        }

        function saveText() {
            const textBox = byId('editableText');
            const textToSave = textBox.value;
                        const select = byId('optionsDropdown');
            const selectedValue = select.value;
            fetch('/api/save', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                },
                body: JSON.stringify({text: textToSave, selectedId: selectedValue})
            })
                .then(response => {
                  if (response.ok) {
                        byId('configStatus').textContent = '修改已保存'
                    } else {
                        byId('configStatus').textContent = '保存失败，请稍后重试'
                    }
                   })
                    .catch(error => {
                    byId('configStatus').textContent = '请求失败，请检查连接后重试';
                });
        }


bindActions(root, { loadDropdownOptions, loadTextForOption, saveText });
loadDropdownOptions();
return {};
}
