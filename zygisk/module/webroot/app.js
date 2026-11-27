'use strict';
const $ = id => document.getElementById(id);
const labels = {
 'com.quark.browser':'夸克','com.android.chrome':'Chrome','com.chrome.beta':'Chrome Beta','com.chrome.dev':'Chrome Dev','com.chrome.canary':'Chrome Canary','com.brave.browser':'Brave','com.brave.browser_beta':'Brave Beta','com.brave.browser_nightly':'Brave Nightly','com.kiwibrowser.browser':'Kiwi Browser','com.mi.globalbrowser':'小米浏览器','com.android.browser':'系统浏览器','com.qihoo.contents':'360 浏览器','com.naver.whale':'Whale','com.vivaldi.browser':'Vivaldi','com.vivaldi.browser.snapshot':'Vivaldi Snapshot','org.cromite.cromite':'Cromite','org.chromium.chrome':'Chromium','org.chromium.thorium':'Thorium','org.bromite.bromite':'Bromite','app.vanadium.browser':'Vanadium','com.herond.android.browser':'Herond','com.coccoc.trinhduyet':'Cốc Cốc','com.coccoc.trinhduyet_beta':'Cốc Cốc Beta','io.github.jqssun.helium':'Helium','org.axpos.aosmium':'Aosmium','org.greatfire.freebrowser':'FreeBrowser','org.triple.banana':'Banana','us.spotco.mulch':'Mulch'
};
let rows=[], selected=new Set(), saved=new Set(), busy=false, loaded=false, serial=0;
const moduleCommand='sh /data/adb/modules/chromext_zygisk/scope.sh ';
function exec(command) {
  return new Promise((resolve,reject)=>{
    if (!window.ksu || typeof window.ksu.exec!=='function') return reject(new Error('请从 KernelSU / APatch 模块 WebUI 入口打开'));
    const name='cxScopeCallback'+Date.now()+'_'+(++serial);
    const cleanup=()=>{ clearTimeout(timer); delete window[name]; };
    const timer=setTimeout(()=>{cleanup();reject(new Error('Root 管理器执行超时，请刷新重试'));},20000);
    window[name]=(code,stdout,stderr)=>{cleanup(); if(Number(code)===0) resolve(String(stdout||'')); else reject(new Error(String(stderr||stdout||'模块命令执行失败').trim()));};
    try { window.ksu.exec(command,'{}',name); }
    catch(e) { try { window.ksu.exec(command,name); } catch(e2){cleanup();reject(e2);} }
  });
}
function dirty(){return selected.size!==saved.size || [...selected].some(p=>!saved.has(p));}
function controls(){
 $('reload').disabled=busy; $('all').disabled=$('none').disabled=!loaded||busy;
 $('save').disabled=!loaded||busy||!dirty(); $('count').textContent='已选择 '+selected.size+' 个'+(dirty()?' · 未保存':'');
 document.querySelectorAll('.toggle').forEach(el=>{el.disabled=!loaded||busy;});
}
function render(){
 const q=$('query').value.toLowerCase().trim(); $('list').replaceChildren();
 const filtered=rows.filter(r=>(r.installed||selected.has(r.pkg)||saved.has(r.pkg)) && (r.pkg+' '+(labels[r.pkg]||'')).toLowerCase().includes(q));
 for(const r of filtered){
   const item=document.createElement('label');item.className='browser';
   const logo=document.createElement('span');logo.className='logo';logo.textContent=(labels[r.pkg]||r.pkg.split('.').pop()).slice(0,1).toUpperCase();
   const details=document.createElement('div');details.className='detail';
   const title=document.createElement('strong');title.textContent=labels[r.pkg]||r.pkg;
   const subtitle=document.createElement('div');subtitle.className='pkg';subtitle.textContent=r.pkg+(r.installed?'':' · 未安装');details.append(title,subtitle);
   const toggle=document.createElement('input');toggle.type='checkbox';toggle.className='toggle';toggle.checked=selected.has(r.pkg);
   toggle.setAttribute('aria-label','注入 '+title.textContent);toggle.addEventListener('change',()=>{if(toggle.checked)selected.add(r.pkg);else selected.delete(r.pkg);controls();});
   item.append(logo,details,toggle);$('list').append(item);
 }
 $('empty').hidden=!loaded||filtered.length!==0;controls();
}
async function load(){
 if(busy)return; busy=true;loaded=false;controls();$('status').textContent='正在读取模块配置…';
 try{
   const lines=(await exec(moduleCommand+'read')).trim().split(/\r?\n/);
   if(lines.shift()!=='CX_SCOPE_V1')throw new Error('模块配置返回异常，请确认已刷入最新 ZIP');
   const next=lines.map(line=>{const m=/^([A-Za-z0-9_.]+)\t([01])\t([01])$/.exec(line);if(!m)throw new Error('模块配置格式异常');return {pkg:m[1],installed:m[2]==='1',enabled:m[3]==='1'};});
   rows=next;selected=new Set(rows.filter(r=>r.enabled).map(r=>r.pkg));saved=new Set(selected);loaded=true;
   $('status').textContent='模块已连接 · '+rows.filter(r=>r.installed).length+' 个已安装的受支持浏览器';
 }catch(e){$('status').textContent='读取失败：'+e.message;}
 finally{busy=false;render();}
}
async function save(){
 if(!loaded||busy||!dirty())return;const values=[...selected].sort();
 if(values.some(p=>!rows.some(r=>r.pkg===p)||!/^[A-Za-z0-9_.]+$/.test(p)))return;
 busy=true;controls();$('status').textContent='正在保存…';
 try{
   const reply=await exec(moduleCommand+'save'+values.map(p=>" '"+p+"'").join(''));
   if(reply.trim()!=='CX_SAVED')throw new Error('模块未确认保存');
   saved=new Set(values);$('status').textContent='保存成功，请强制停止并重新打开对应浏览器';
 }catch(e){$('status').textContent='保存失败：'+e.message;}
 finally{busy=false;render();}
}
$('reload').addEventListener('click',()=>{if(dirty()&&!confirm('放弃未保存的修改？'))return;load();});
$('query').addEventListener('input',render);
$('all').addEventListener('click',()=>{rows.filter(r=>r.installed).forEach(r=>selected.add(r.pkg));render();});
$('none').addEventListener('click',()=>{selected.clear();render();});
$('save').addEventListener('click',save);
load();
