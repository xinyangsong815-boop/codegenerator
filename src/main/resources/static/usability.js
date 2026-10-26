/* Shared navigation and recoverable connection errors; drafts stay in memory. */
const usabilityMixin={
 data(){return {mobileNavOpen:false,connectionState:'connecting',showFieldDetails:false,unloadGuard:null,editorSaved:{contractText:'',ddlText:'',updateCurrentText:'{}',updateBaseText:'{}',sourceBeforeText:'{}',sourceAfterText:'{}',contractSourcesText:'{}',frontendSourcesText:'{}',pasteBody:'',specText:'',productionEvidenceText:''}};},
 computed:{
  navigationGroups(){const groups=[{label:'生成与接入',ids:['generate','ddl','regenerate','repositories']},{label:'接口与联调',ids:['contracts','frontend','compare','mock','sample']},{label:'执行与记录',ids:['regression','history']}];return groups.map(group=>({...group,items:group.ids.map(id=>this.tabs.find(tab=>tab.id===id))}));},
  connectionLabel(){return {connecting:'正在连接工具',connected:'工具连接正常',unreachable:'暂时无法连接工具'}[this.connectionState];},
  unsavedWork(){try{if(this.repoDirty)return true;if(this.specBaseline&&this.specFingerprint()!==this.specBaseline)return true;if(Object.entries(this.editorSaved).some(([key,value])=>this[key]!==undefined&&this[key]!==value))return true;return !!this.busy||!!this.regStepDirty||!!(this.regText?.trim()&&this.regScenarioDirty)||!!(this.environmentSnapshot&&this.regEnvironmentDirty);}catch(e){return true;}}
 },
 watch:{tab(){this.mobileNavOpen=false;this.$nextTick(()=>window.scrollTo({top:0,behavior:'auto'}));}},
 mounted(){this.unloadGuard=event=>{if(!this.unsavedWork)return;event.preventDefault();event.returnValue='';};window.addEventListener('beforeunload',this.unloadGuard);},
 beforeUnmount(){window.removeEventListener('beforeunload',this.unloadGuard);},
 methods:{
  // Keep numeric lexemes intact until the server parses them; never collapse duplicate keys.
  jsonText(text,omitUpdatedAt=false,editable=false){
   text=String(text);let pos=0,depth=0;const fail=message=>{throw new Error(message+'（位置 '+pos+'）');},space=()=>{while(/[\x20\t\r\n]/.test(text[pos]||'!'))pos++;};
   const string=()=>{const start=pos++;while(pos<text.length){const c=text[pos++];if(c==='"'){try{return JSON.parse(text.slice(start,pos));}catch(e){fail('JSON字符串格式错误');}}if(c==='\\')pos++;}fail('JSON字符串未结束');};
   const normalized=token=>{const m=/^(-?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(token);let digits=(m[2]+(m[3]||'')).replace(/^0+/,'');if(!digits)return '0';let exp=BigInt(m[4]||'0')-BigInt((m[3]||'').length);while(digits.endsWith('0')){digits=digits.slice(0,-1);exp++;}return m[1]+digits+'e'+exp;};
   const value=()=>{space();if(++depth>100)fail('JSON嵌套过深');let result,c=text[pos];
    if(c==='{'){pos++;space();const keys=new Set(),fields=[];if(text[pos]!=='}')while(true){space();if(text[pos]!=='"')fail('JSON对象键必须是字符串');const key=string();if(keys.has(key))fail('JSON重复键：'+key);keys.add(key);space();if(text[pos++]!==':')fail('JSON缺少冒号');const item=value();if(!(omitUpdatedAt&&depth===1&&key==='updatedAt'))fields.push(JSON.stringify(key)+':'+item);space();if(text[pos]!==',')break;pos++;}if(text[pos++]!=='}')fail('JSON对象未结束');result='{'+fields.join(',')+'}';}
    else if(c==='['){pos++;space();const items=[];if(text[pos]!==']')while(true){items.push(value());space();if(text[pos]!==',')break;pos++;}if(text[pos++]!==']')fail('JSON数组未结束');result='['+items.join(',')+']';}
    else if(c==='"')result=JSON.stringify(string());
    else{const match=/^(?:true|false|null|-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?)/.exec(text.slice(pos));if(!match)fail('JSON值格式错误');result=match[0];pos+=result.length;if(editable&&!['true','false','null'].includes(result)){const n=Number(result);if(!Number.isFinite(n)||normalized(result)!==normalized(String(n))||(Number.isInteger(n)&&!Number.isSafeInteger(n)))fail('该数值超出浏览器无损编辑范围，请保留JSON原文并直接保存或校验');}}
    depth--;return result;};
   const result=value();space();if(pos!==text.length)fail('JSON末尾存在多余内容');return result;
  },

  prettyJsonText(text){const raw=this.jsonText(text);let out='',depth=0,quoted=false,escaped=false;const pad=()=> '  '.repeat(depth);for(let i=0;i<raw.length;i++){const c=raw[i];if(quoted){out+=c;if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c==='"')quoted=false;continue;}if(c==='"'){quoted=true;out+=c;}else if(c==='{'||c==='['){out+=c;depth++;if(raw[i+1]!=='}'&&raw[i+1]!==']')out+='\n'+pad();}else if(c==='}'||c===']'){depth--;if(raw[i-1]!=='{'&&raw[i-1]!=='[')out+='\n'+pad();out+=c;}else if(c===',')out+=',\n'+pad();else if(c===':')out+=': ';else out+=c;}return out;},
  parseEditorJson(text){return JSON.parse(this.jsonText(text,false,true));},
  rawJsonEnvelope(values,rawValues){const fields=Object.entries(values).map(([key,value])=>JSON.stringify(key)+':'+JSON.stringify(value));for(const [key,text] of Object.entries(rawValues))fields.push(JSON.stringify(key)+':'+this.jsonText(text));return '{'+fields.join(',')+'}';},
  acknowledgeEditor(key,text){if(this[key]===text)this.editorSaved[key]=text;},

  async request(url,options={}){
   const {rawJson,rawText,...requestOptions}=options;const send=async()=>{const headers={...(options.headers||{})};if(options.method&&options.method.toUpperCase()!=='GET')headers['X-Tool-Token']=this.config.token;return fetch(url,{...requestOptions,headers});};
   try{
    let response=await send();this.connectionState='connected';
    if(response.status===403&&options.method&&options.method.toUpperCase()!=='GET'){
     const previous=this.config.token,probe=await fetch('/api/config');
     if(probe.ok){const current=await probe.json();if(current.token&&current.token!==previous){this.config=current;response=await send();}}
    }
    if(!response.ok){let message;try{message=(await response.json()).message;}catch(e){}throw new Error(message||`操作未完成（HTTP ${response.status}），请检查输入后重试。`);}
    if(response.status===204)return null;
    return rawText?await response.text():rawJson?this.jsonText(await response.text()):await response.json();
   }catch(error){if(error instanceof TypeError){this.connectionState='unreachable';throw new Error('无法连接本机工具。请确认程序仍在运行，再点“重新连接”；当前编辑内容会保留。');}throw error;}
  },
  async reconnect(){await this.run(async()=>{this.config=await this.request('/api/config');await this.refresh();this.notify('已重新连接工具，当前编辑内容已保留。');});},
  goToSection(id){const target=document.getElementById(id);if(!target)return;target.scrollIntoView({behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches?'auto':'smooth',block:'start'});},
  regVisibleSteps(run){return [...(run.steps||[]).map(s=>({...s,reportPhase:'main'})),...(run.cleanupSteps||[]).map(s=>({...s,reportPhase:'cleanup'}))];},
  overallRunVerdict(run){if(run.state!=='FINISHED')return 'INCOMPLETE';if(run.verdict!=='PASS')return run.verdict||'INCOMPLETE';const cleanup=run.cleanupSteps||[];if(cleanup.some(s=>s.verdict==='FAIL'))return 'FAIL';if(cleanup.some(s=>s.verdict==='ERROR'))return 'ERROR';if(cleanup.some(s=>s.state!=='FINISHED'||s.verdict!=='PASS'))return 'INCOMPLETE';if(run.overallVerdict)return run.overallVerdict;return run.cleanupStatus==='FAILED'?'FAIL':['DONE','NOT_REQUIRED','MANUALLY_CONFIRMED'].includes(run.cleanupStatus)?'PASS':'INCOMPLETE';},
  fieldSummary(field){const source=field.source||'CLIENT';let shape=field.javaType==='String'?`长度 ${field.maxLength||'未填'}`:field.javaType==='BigDecimal'?`精度 ${field.precision??'未填'}, 小数 ${field.scale??'未填'}`:field.javaType==='LocalDateTime'?`时间精度 ${field.temporalPrecision??0}`:'';return [shape,field.required?'必填':'可空',field.editable?'可修改':'不可修改',source==='SERVER'?'服务端赋值':source==='DATABASE'?'数据库赋值':'请求传入'].filter(Boolean).join(' · ');},
  clearHistoryFilters(){this.historySearch='';this.historyKind='';this.historyPage=1;this.refresh().catch(error=>this.notify(error.message,true));}
 }
};
