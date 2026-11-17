export function mount(pane) {
    const button = pane.querySelector('.advanced-download');
    const status = pane.querySelector('.advanced-status');
    button.addEventListener('click', async () => {
        button.disabled = true;
        status.textContent = '正在生成备份…';
        try {
            const response = await fetch('/api/admin/database-backup', { cache: 'no-store' });
            if (!response.ok) throw new Error('备份下载失败，请稍后重试。');
            const blob = await response.blob();
            const filename = response.headers.get('Content-Disposition')?.match(/filename="([^"]+)"/)?.[1] || 'leon-workflow-backup.sql';
            const url = URL.createObjectURL(blob);
            const link = document.createElement('a');
            link.href = url;
            link.download = filename;
            document.body.append(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            status.textContent = '备份已开始下载。';
        } catch (error) {
            status.textContent = error.message || '备份下载失败，请稍后重试。';
        } finally {
            button.disabled = false;
        }
    });
    return mountModels(pane);
}

function mountModels(pane) {
    const $=selector=>pane.querySelector(selector), form=$('[data-model-form]'), keyForm=$('[data-model-key-form]'), status=$('[data-model-status]');
    let models=[], credentials=[], editing=null, busy=false;
    const escape=value=>String(value ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
    async function api(path,body,method='GET') {
        const response=await fetch('/api/workflows'+path,{method,cache:'no-store',headers:{'Content-Type':'application/json','X-Requested-With':'XMLHttpRequest'},...(body?{body:JSON.stringify(body)}:{})});
        const value=await response.json(); if(!response.ok) throw new Error(value.message || '模型配置操作失败'); return value;
    }
    const modelChoices={
        deepseek:[['deepseek-flash','DeepSeek V4.1 Flash'],['deepseek-v4-pro','DeepSeek V4 Pro']],
        openai:[['gpt-6.1-sol','GPT-6.1 Sol'],['gpt-6-luna','GPT-6 Luna'],['gpt-6.1-terra','GPT-6.1 Terra'],['gpt-6-astra','GPT-6 Astra']],
    };
    function modelOptions(current) {
        const choices=modelChoices[form.elements.provider.value];
        form.elements.model.replaceChildren(...choices.map(([id,name])=>new Option(`${name} · ${id}`,id)));
        if(current && !choices.some(([id])=>id===current)) form.elements.model.add(new Option(`${current}（已有配置）`,current));
        form.elements.model.value=current || choices[0][0];
    }
    function providerFields() {
        const openai=form.elements.provider.value==='openai';
        $('[data-model-credential]').hidden=!openai; form.elements.credentialId.required=openai;
        $('[data-model-key-hint]').textContent=openai?'选择已加密保存的 API Key；也可在下方添加新凭据。':'DeepSeek 沿用现有 API Key 配置。';
    }
    function credentialOptions(selected=form.elements.credentialId.value) {
        const choices=credentials.filter(c=>c.kind==='openai');
        form.elements.credentialId.innerHTML='<option value="">请选择 OpenAI 凭据</option>'+choices.map(c=>`<option value="${escape(c.id)}">${escape(c.name)}</option>`).join('');
        if(selected && !choices.some(c=>c.id===selected)) form.elements.credentialId.add(new Option('原凭据已不存在，请重新选择',selected));
        form.elements.credentialId.value=selected;
    }
    function render() {
        $('[data-model-list]').innerHTML=models.length?models.map(m=>`<div class="model-row"><div><strong>${escape(m.name)}</strong><small>${m.provider==='openai'?'OpenAI':'DeepSeek'} · ${escape(m.model)} · ${m.enabled?'已启用':'已停用'}</small></div><button type="button" data-model-edit="${escape(m.id)}">编辑</button></div>`).join(''):'<p>尚未配置模型</p>';
        credentialOptions();
    }
    function reset() { editing=null; form.reset(); modelOptions(); $('[data-model-form-title]').textContent='添加模型'; providerFields(); }
    async function perform(work) {
        if(busy) return; busy=true; pane.querySelectorAll('.model-settings button').forEach(b=>b.disabled=true);
        status.textContent='正在处理…';
        try { await work(); } catch(error) { status.textContent=error.message || '模型配置操作失败'; }
        finally { busy=false; pane.querySelectorAll('.model-settings button').forEach(b=>b.disabled=false); }
    }
    form.elements.provider.addEventListener('change',()=>{
        modelOptions(); providerFields();
    });
    $('[data-model-reset]').addEventListener('click',reset);
    $('[data-model-list]').addEventListener('click',event=>{
        if(busy) return;
        const button=event.target.closest('[data-model-edit]'); if(!button) return;
        const row=models.find(m=>m.id===button.dataset.modelEdit); if(!row) return;
        editing=row.id;
        for(const field of ['name','provider']) form.elements[field].value=row[field];
        modelOptions(row.model);
        form.elements.enabled.checked=row.enabled; credentialOptions(row.credentialId);
        $('[data-model-form-title]').textContent='编辑模型'; providerFields(); form.elements.name.focus(); status.textContent='';
    });
    form.addEventListener('submit',event=>{
        event.preventDefault(); perform(async()=>{
            const body={name:form.elements.name.value.trim(),provider:form.elements.provider.value,model:form.elements.model.value.trim(),enabled:form.elements.enabled.checked};
            if(body.provider==='openai') body.credentialId=form.elements.credentialId.value;
            await api('/models'+(editing?'/'+encodeURIComponent(editing):''),body,editing?'PUT':'POST');
            models=await api('/models'); render(); reset(); status.textContent='模型已保存，工作流节点可直接选择。';
        });
    });
    keyForm.addEventListener('submit',event=>{
        event.preventDefault(); perform(async()=>{
            const created=await api('/credentials',{name:keyForm.elements.name.value.trim(),kind:'openai',value:{apiKey:keyForm.elements.apiKey.value}},'POST');
            keyForm.reset(); credentials=await api('/credentials'); credentialOptions(created.id); status.textContent='加密凭据已保存，请选择模型服务并保存模型。';
        });
    });
    modelOptions(); providerFields();
    return {activate(){return perform(async()=>{[models,credentials]=await Promise.all([api('/models'),api('/credentials')]);render();status.textContent='';});},deactivate(){keyForm.elements.apiKey.value='';}};
}
