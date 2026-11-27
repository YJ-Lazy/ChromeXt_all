'use strict';
const libCmd='sh /data/adb/modules/chromext_zygisk/library.sh ';
const encoder=new TextEncoder(), decoder=new TextDecoder('utf-8',{fatal:true});
const limit=16777216, sourceLimit=5242880;
let libraryRecords=[], libRevision='', libDirty=false, libBusy=false, editingId=null, exportName='ScriptHub-backup.json';
function tab(name){
 for(const t of ['scope','library','logs']){ $(t+'-page').hidden=t!==name;$('tab-'+t).classList.toggle('active',t===name); }
 if(name==='library'&&!libRevision&&!libBusy)loadLibrary();
 if(name==='logs'&&!$('logs-text').value)readLogs();
}
for(const t of ['scope','library','logs'])$('tab-'+t).addEventListener('click',()=>tab(t));
function b64(bytes){let s='';for(let i=0;i<bytes.length;i+=4096)s+=String.fromCharCode(...bytes.subarray(i,i+4096));return btoa(s);}
function metadata(source){
 const block=/^[ \t]*\/\/[ \t]*==UserScript==[ \t]*\r?\n([\s\S]*?)^[ \t]*\/\/[ \t]*==\/UserScript==[ \t]*(?:\r?\n|$)/m.exec(source.replace(/^\uFEFF/,''));
 if(!block||encoder.encode(source).length>sourceLimit)throw Error('脚本无效或超过 5 MiB');
 let name='sample',ns='ChromeXt',version='',url='',matches=0;
 for(const line of block[1].split(/\r?\n/)){
   const m=/^[ \t]*\/\/[ \t]*@([\w:-]+)(?:[ \t]+(.*?))?[ \t]*$/.exec(line);if(!m)continue;
   const v=(m[2]||'').trim();if(m[1]==='name')name=v.replace(/:/g,'');if(m[1]==='namespace')ns=v;if(m[1]==='version')version=v;
   if(m[1]==='downloadURL'||m[1]==='updateURL')url=v;if((m[1]==='match'||m[1]==='include')&&v)matches++;
 }
 if(!matches||!name||!(ns+':'+name).length||(ns+':'+name).length>2048)throw Error('脚本缺少有效匹配规则或标识');
 return {id:ns+':'+name,name,version,url};
}
function validateLibrary(data){
 if(data.format!=='ChromeXt-library-v1'||!Array.isArray(data.scripts)||data.scripts.length>5000)throw Error('脚本库格式无效');
 const ids=new Set();
 for(const r of data.scripts){
   if(typeof r.id!=='string'||!r.id||r.id.length>2048||ids.has(r.id)||!Array.isArray(r.targets)||r.targets.some(p=>typeof p!=='string'||!/^[A-Za-z0-9_.]+$/.test(p)))throw Error('脚本库记录无效');
   ids.add(r.id);if(!r.deleted&&metadata(r.source).id!==r.id)throw Error('脚本 ID 不一致');
 }
 return data.scripts;
}
function libStatus(s){$('lib-status').textContent=s;}
function libControls(){
 $('lib-load').disabled=libBusy;$('lib-import').disabled=libBusy||!libRevision;$('lib-export').disabled=libBusy||!libRevision;
 $('lib-save').disabled=libBusy||!libRevision||!libDirty; $('lib-sync').disabled=libBusy||!libRevision;
 document.querySelectorAll('#lib-list button,#lib-list input').forEach(el=>el.disabled=libBusy);
}
function changed(){libDirty=true;libStatus('有未保存的修改');libControls();}
async function info(){
 const s=(await exec(libCmd+'info')).trim();
 const m=/^CX_LIBRARY_V2:([a-f0-9]{64}):(\d+)$/im.exec(s)||/^([a-f0-9]{64})\s+(\d+)$/im.exec(s);
 if(!m)throw Error('脚本库协议异常（info）：'+(s.slice(0,180)||'无返回内容'));
 const size=Number(m[2]);if(!Number.isSafeInteger(size)||size<1||size>limit)throw Error('脚本库大小异常：'+m[2]);
 return {rev:m[1].toLowerCase(),size};
}
async function loadLibrary(){
 if(libBusy)return;if(libDirty&&!confirm('放弃未保存的脚本修改？'))return;
 libBusy=true;libControls();libStatus('正在读取脚本库…');
 try{
   const first=await info(),bytes=new Uint8Array(first.size);
   let offset=0;for(let i=0;offset<bytes.length;i++){const raw=atob((await exec(libCmd+'read '+i)).trim());if(!raw.length||offset+raw.length>bytes.length)throw Error('读取分片异常');for(let j=0;j<raw.length;j++)bytes[offset++]=raw.charCodeAt(j);}
   const last=await info();if(first.rev!==last.rev)throw Error('脚本库发生变化，请重新读取');
   libraryRecords=validateLibrary(JSON.parse(decoder.decode(bytes)));libRevision=first.rev;libDirty=false;libStatus('脚本库已连接');
 }catch(e){libRevision='';libStatus('读取失败：'+e.message);}
 finally{libBusy=false;renderLibrary();}
}
async function saveLibrary(){
 if(libBusy||!libRevision||!libDirty)return;
 libBusy=true;libControls();let token='';
 try{
   const bytes=encoder.encode(JSON.stringify({format:'ChromeXt-library-v1',scripts:libraryRecords}));if(bytes.length>limit)throw Error('脚本库超过 16 MiB');
   validateLibrary(JSON.parse(decoder.decode(bytes)));
   token=Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
   await exec(libCmd+'begin '+token);
   for(let i=0;i<bytes.length;i+=16384){libStatus('保存中 '+Math.floor(i/bytes.length*100)+'%');await exec(libCmd+'chunk '+token+' '+i+' '+b64(bytes.subarray(i,i+16384)));}
   const reply=(await exec(libCmd+'commit '+token+' '+libRevision+' '+bytes.length)).trim();
   const ack=/^CX_SAVED:([a-f0-9]{64})$/im.exec(reply)||/^([a-f0-9]{64})$/im.exec(reply);
   if(!ack)throw Error('保存未确认：'+(reply.slice(0,180)||'无返回内容'));
   libRevision=ack[1].toLowerCase();libDirty=false;const count=libraryRecords.filter(r=>!r.deleted&&r.targets.length>0).length;libStatus('已保存，已分配 '+count+' 个脚本。请强制停止并重新打开目标浏览器'+(count?'':'；尚无已启用脚本，请勾选浏览器'));
 }catch(e){if(token)try{await exec(libCmd+'abort '+token);}catch(_){}libStatus('保存失败：'+e.message);}
 finally{libBusy=false;renderLibrary();}
}
async function syncLocal(){
 if(libBusy||!libRevision)return;
 if(!confirm('读取已启用浏览器的 ChromeXt 本地脚本？仅合并新增脚本，不覆盖同名脚本或恢复已删除脚本。'))return;
 const candidates=rows.filter(r=>r.installed&&saved.has(r.pkg));
 if(!candidates.length){libStatus('请先选择并保存注入范围，再重启浏览器并打开网页');return;}
 libBusy=true;libControls();let added=0,conflicts=0,skipped=0,errors=[];
 try{
  for(const r of candidates){
   let token='';
   try{
    libStatus('正在同步 '+(labels[r.pkg]||r.pkg)+'…');
    token=Array.from(crypto.getRandomValues(new Uint8Array(16)),b=>b.toString(16).padStart(2,'0')).join('');
    const size=Number((await exec(libCmd+'local-begin '+token+' '+r.pkg)).trim());
    if(!Number.isInteger(size)||size<1||size>limit)throw Error('快照大小异常');
    const bytes=new Uint8Array(size);let offset=0;
    for(let i=0;offset<size;i++){const raw=atob((await exec(libCmd+'local-read '+token+' '+i)).trim());if(!raw.length||offset+raw.length>size)throw Error('快照分片异常');for(let j=0;j<raw.length;j++)bytes[offset++]=raw.charCodeAt(j);}
    const data=JSON.parse(decoder.decode(bytes));
    if(data.format!=='ChromeXt-local-v1'||data.package!==r.pkg||!Array.isArray(data.scripts)||data.scripts.length>5000)throw Error('快照格式异常');
    for(const item of data.scripts){
     try{
      if(metadata(item.source).id!==item.id){skipped++;continue;}
      const old=libraryRecords.find(x=>x.id===item.id);
      if(old){if(!old.deleted&&old.source!==item.source)conflicts++;else skipped++;continue;}
      if(libraryRecords.length>=5000){skipped++;continue;}
      libraryRecords.push({id:item.id,source:item.source,targets:[r.pkg],deleted:false});
      if(encoder.encode(JSON.stringify({format:'ChromeXt-library-v1',scripts:libraryRecords})).length>limit){libraryRecords.pop();skipped++;continue;}
      added++;libDirty=true;
     }catch(_){skipped++;}
    }
   }catch(e){errors.push((labels[r.pkg]||r.pkg)+': '+e.message);}
   finally{if(token)try{await exec(libCmd+'local-end '+token);}catch(_){}}
  }
  libStatus('同步完成：新增 '+added+'，同名冲突 '+conflicts+'，跳过 '+skipped+(added?'。请保存脚本库。':'。')+(errors.length?' '+errors.join('；'):''));
 }finally{libBusy=false;renderLibrary();}
}
$('lib-sync').addEventListener('click',syncLocal);
function buttonFor(name,fn){const b=document.createElement('button');b.textContent=name;b.addEventListener('click',fn);return b;}
function renderLibrary(){
 const q=$('lib-query').value.trim().toLowerCase();$('lib-list').replaceChildren();
 for(const r of libraryRecords.filter(r=>!r.deleted&&r.id.toLowerCase().includes(q))){
   const m=metadata(r.source);const card=document.createElement('article');card.className='script-card';
   const title=document.createElement('h3');title.textContent=m.name+(m.version?' · v'+m.version:'');const id=document.createElement('p');id.textContent=r.id;card.append(title,id);
   const targets=rows.filter(p=>p.installed||r.targets.includes(p.pkg));
   if(!targets.length){const p=document.createElement('p');p.textContent='请先在注入页面刷新浏览器列表';card.append(p);}
   for(const p of targets){const label=document.createElement('label'),sw=document.createElement('input');sw.type='checkbox';sw.checked=r.targets.includes(p.pkg);sw.addEventListener('change',()=>{r.targets=sw.checked?[...new Set([...r.targets,p.pkg])]:r.targets.filter(x=>x!==p.pkg);changed();});label.append(sw,document.createTextNode(labels[p.pkg]||p.pkg));card.append(label);}
   const actions=document.createElement('div');actions.className='actions';
   actions.append(buttonFor('编辑',()=>openEditor(r)),buttonFor('导出',()=>showExport(r.source,'script.user.js')),buttonFor('删除',()=>{if(confirm('删除此脚本？重启浏览器后会同步删除。')){r.deleted=true;r.source='';r.targets=[];changed();renderLibrary();}}));
   if(m.url&&/^https:\/\//i.test(m.url))actions.append(buttonFor('检查更新',async()=>{openEditor(r);$('script-url').value=m.url;await fetchSource();}));
   card.append(actions);$('lib-list').append(card);
 }
 libControls();
}
function openEditor(r=null){editingId=r?r.id:null;$('editor-title').textContent=r?'编辑 '+r.id:'导入 / 新建脚本';$('source').value=r?r.source:'';$('script-url').value='';$('script-file').value='';$('editor').hidden=false;$('editor').scrollIntoView({block:'start'});}
function importSources(raw){
 const s=raw.trimStart();if(s.startsWith('{')){const j=JSON.parse(raw);if(j.format!=='ScriptHub-scripts-v1'||!Array.isArray(j.scripts)||j.scripts.length>5000)throw Error('不支持的备份格式');return j.scripts;}return [raw];
}
function applyEditor(){
 try{
   const sources=importSources($('source').value);const candidates=sources.map(s=>({source:s,id:metadata(s).id}));
   if(editingId&&(candidates.length!==1||candidates[0].id!==editingId))throw Error('编辑不能改变脚本 ID');
   if(!editingId&&candidates.some(c=>libraryRecords.some(r=>r.id===c.id&&!r.deleted))&&!confirm('覆盖同名脚本并保留分配？'))return;
   for(const c of candidates){const old=libraryRecords.find(r=>r.id===c.id);if(old){old.source=c.source;old.deleted=false;}else libraryRecords.push({id:c.id,source:c.source,targets:[],deleted:false});}
   $('editor').hidden=true;changed();renderLibrary();
 }catch(e){libStatus('导入失败：'+e.message);}
}
async function fetchSource(){
 try{const url=new URL($('script-url').value);if(url.protocol!=='https:')throw Error('仅支持 HTTPS');const response=await fetch(url.href,{credentials:'omit',referrerPolicy:'no-referrer'});if(!response.ok)throw Error('HTTP '+response.status);const text=await response.text();metadata(text);$('source').value=text;libStatus('已获取源码，确认后才导入');}
 catch(e){libStatus('链接读取失败（可能受跨域限制），可手动粘贴或导入文件：'+e.message);}
}
function showExport(text,name){exportName=name;$('export-text').value=text;$('export-panel').hidden=false;$('export-panel').scrollIntoView({block:'start'});}
async function copyText(el){try{await navigator.clipboard.writeText(el.value);}catch(_){el.focus();el.select();if(!document.execCommand('copy'))alert('请长按复制文本');}}
$('lib-load').addEventListener('click',loadLibrary);$('lib-save').addEventListener('click',saveLibrary);
$('lib-query').addEventListener('input',renderLibrary);$('lib-import').addEventListener('click',()=>openEditor());
$('lib-export').addEventListener('click',()=>showExport(JSON.stringify({format:'ScriptHub-scripts-v1',scripts:libraryRecords.filter(r=>!r.deleted).map(r=>r.source)}),'ScriptHub-backup.json'));
$('editor-cancel').addEventListener('click',()=>{$('editor').hidden=true;});$('editor-apply').addEventListener('click',applyEditor);$('fetch-url').addEventListener('click',fetchSource);
$('script-file').addEventListener('change',async()=>{try{const files=[...$('script-file').files];if(files.reduce((s,f)=>s+f.size,0)>limit)throw Error('文件超过 16 MiB');const sources=[];for(const f of files)sources.push(...importSources(await f.text()));$('source').value=sources.length===1?sources[0]:JSON.stringify({format:'ScriptHub-scripts-v1',scripts:sources});}catch(e){libStatus('文件读取失败：'+e.message);}});
$('export-close').addEventListener('click',()=>{$('export-panel').hidden=true;});$('export-copy').addEventListener('click',()=>copyText($('export-text')));
$('export-download').addEventListener('click',()=>{const url=URL.createObjectURL(new Blob([$('export-text').value],{type:'text/plain'}));const a=document.createElement('a');a.href=url;a.download=exportName;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
async function readLogs(){try{$('logs-text').value=await exec(libCmd+'logs');}catch(e){$('logs-text').value='读取失败：'+e.message;}}
$('logs-refresh').addEventListener('click',readLogs);$('logs-copy').addEventListener('click',()=>copyText($('logs-text')));$('logs-clear').addEventListener('click',()=>{$('logs-text').value='';});
tab('scope');
