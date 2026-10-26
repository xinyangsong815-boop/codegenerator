const {createApp}=Vue;
createApp({mixins:[extensionMixin,regressionMixin,workflowMixin,repairMixin,usabilityMixin,...(typeof repositoryMixin==='undefined'?[]:[repositoryMixin])],
 data(){return {config:{},tab:'home',busy:false,message:'',messageTab:'',error:false,
 tabs:[{id:'home',label:'开始使用'},{id:'generate',label:'需求与代码生成'},{id:'contracts',label:'接口文档与版本'},{id:'compare',label:'接口变更检查'},{id:'sample',label:'响应样例校验'},{id:'regenerate',label:'代码更新与冲突'},{id:'frontend',label:'Vue 接口关联分析'},{id:'ddl',label:'数据库 DDL 导入'},{id:'mock',label:'离线 Mock 与测试'},{id:'regression',label:'业务场景回归'},{id:'repositories',label:'代码版本库'},{id:'history',label:'历史记录与下载'}],
 types:['String','Long','Integer','BigDecimal','LocalDate','LocalDateTime','Boolean'],methods:['GET','POST','PUT','DELETE','PATCH','HEAD','OPTIONS'],
 queries:[{code:'NONE',label:'不查询'},{code:'EQ',label:'精确匹配'},{code:'CONTAINS',label:'包含查询'},{code:'RANGE',label:'区间查询'},{code:'IN',label:'多值列表'}],
 operations:[{code:'CREATE',label:'新增'},{code:'UPDATE',label:'修改'},{code:'DETAIL',label:'详情'},{code:'PAGE',label:'分页查询'},{code:'DELETE',label:'删除（按配置）'}],
 spec:{packageName:'com.bank.contract',artifactId:'contract-demo',entityName:'Contract',tableName:'biz_contract',description:'合同管理',basePath:'/contracts',operations:['CREATE','UPDATE','DETAIL','PAGE'],fields:[]},
 specBaseline:'',specText:'',editingFieldName:'',indexDraft:null,indexDraftError:'',defaultDrafts:{},defaultDraftErrors:{},generation:null,productionCheck:null,previewPath:'',history:[],document:null,documentId:'',selectedOp:null,contractLabel:'合同接口-v1',contractMode:'scan',contractFile:null,
 baselineId:'',targetId:'',bindings:[],comparison:null,severity:'',
 sample:{contractId:'',method:'GET',path:'/contracts/{id}',status:'200',media:'application/json',bodyText:''},sampleResult:null,
 updateSpecFile:null,currentProjectFile:null,regeneration:null};},
 computed:{contractHistory(){return this.contractVersions;},docOps(){return this.operationRows(this.document);},filteredFindings(){return (this.comparison?.report.findings||[]).filter(f=>!this.severity||f.severity===this.severity);}},
 async mounted(){try{this.config=await this.request('/api/config');await this.loadExample();await this.refresh();}catch(e){this.notify(e.message,true);}},
 methods:{pretty:x=>JSON.stringify(x,null,2),time:s=>new Date(s).toLocaleString(),download:(id,f)=>`/api/artifacts/${id}/${f}`,
 notify(m,error=false){this.message=m;this.messageTab=this.tab;this.error=error;},
 async request(url,options={}){return usabilityMixin.methods.request.call(this,url,options);},
 async run(fn){if(this.busy)return;this.busy=true;this.message='';try{await fn();}catch(e){this.notify(e.message,true);}finally{this.busy=false;}},
 async refreshLegacy(){this.history=await this.request('/api/history');},
 async loadSafeExample(){if(!this.canReplaceSpec())return;const r=await fetch("/examples/production-safe-spec.json");if(!r.ok)throw new Error("生产接入示例未找到");this.spec=await r.json();this.resetDrafts();this.notify("已载入权限、机构范围、业务钩子和删除版本示例；缺少生产适配Bean时不会启动。");},
 async checkProduction(){await this.run(async()=>{const snapshot=this.pretty(this.normalized());const report=await this.request("/api/production/check",{method:"POST",headers:{"Content-Type":"application/json"},body:snapshot});if(snapshot===this.pretty(this.normalized()))this.productionCheck=report;});},
 applyProductionDefaults(){this.spec.schemaVersion=2;const o=this.generationOptions();Object.assign(o,{wireFormat:"STRING_NUMBERS",jsonMode:"STRICT",optimisticLock:true,deleteVersionCheck:this.spec.operations.includes("DELETE"),accessGuard:true,recordAccessCheck:true,transactionalAudit:this.spec.operations.some(op=>["CREATE","UPDATE","DELETE"].includes(op)),hooks:true});if(o.transactionalAudit&&!Array.isArray(o.auditFields))o.auditFields=[];this.notify("已启用字符串数值、严格JSON、更新／删除版本和权限接入。协议有变化；AccessGuard须实现记录／分页授权；独立工程审计同事务落库，宿主需实现AuditWriter，机构范围和审计白名单仍需配置。");},
 optionsChanged(){const o=this.generationOptions();if(!o.optimisticLock)o.deleteVersionCheck=false;if(!o.accessGuard)o.recordAccessCheck=false;if(!o.accessGuard||(!o.optimisticLock&&this.spec.operations.some(op=>['UPDATE','DELETE'].includes(op))))o.transactionalAudit=false;if(!o.transactionalAudit)delete o.auditFields;},
 specFingerprint(){const s=JSON.parse(JSON.stringify(this.spec));if(!s.integration||s.integration.mode==='STANDALONE')delete s.integration;if(s.endpoints){s.endpoints=Object.fromEntries(Object.entries(s.endpoints).filter(([k,v])=>Object.values(v).some(x=>x!==undefined)));if(!Object.keys(s.endpoints).length)delete s.endpoints;}if(s.options&&!Object.keys(s.options).length)delete s.options;const sort=x=>Array.isArray(x)?x.map(sort):x&&typeof x==='object'?Object.fromEntries(Object.keys(x).sort().map(k=>[k,sort(x[k])])):x;return JSON.stringify(sort(s));},
 canReplaceSpec(){return !this.specBaseline||this.specFingerprint()===this.specBaseline||confirm('当前规格有修改尚未生成或导出，舍弃并载入其他规格？');},
 async loadProductionExample(){if(!this.canReplaceSpec())return;const r=await fetch('/examples/production-spec.json');if(!r.ok)throw new Error('生产基础示例未找到');this.spec=await r.json();this.resetDrafts();this.notify('已载入生产基础示例；生成后需要实现可信上下文和权限审计接口');},
 async loadExample(){if(!this.canReplaceSpec())return;const r=await fetch('/examples/contract-v2-spec.json');if(!r.ok)throw new Error('示例规格未找到');this.spec=await r.json();this.resetDrafts();},
 addField(){this.spec.fields.push({name:'',column:'',javaType:'String',description:'',required:false,editable:true,exposed:true,query:'NONE',maxLength:100,precision:null,scale:null});},
 typeChanged(f){if(f.javaType!=='LocalDateTime')delete f.temporalPrecision;if(f.javaType==='String'){f.maxLength=f.maxLength||100;f.precision=null;f.scale=null;}else if(f.javaType==='BigDecimal'){f.maxLength=null;f.precision=18;f.scale=2;}else{f.maxLength=null;f.precision=null;f.scale=null;}},
 engineeringConfig(){
   if(!this.spec.integration)this.spec.integration={mode:"STANDALONE"};
   return this.spec.integration;
 },
 pageTypeChanged(){const config=this.engineeringConfig();if(!config.pageClass)for(const key of ["pageFactoryMethod","recordsProperty","totalProperty"])delete config[key];},
 exportEngineeringProfile(){
   const integration=this.normalized().integration;if(!integration||integration.mode!=="EXISTING")throw new Error("请先选择接入现有工程模式");
   const url=URL.createObjectURL(new Blob([this.pretty({formatVersion:1,integration})],{type:"application/json"}));
   const a=document.createElement("a");a.href=url;a.download="engineering-profile.json";a.click();URL.revokeObjectURL(url);
 },
 applyEngineeringProfile(data){
   if(data?.formatVersion!==1||!data.integration||Array.isArray(data.integration)||data.integration.mode!=="EXISTING")throw new Error("请选择 formatVersion=1 的工程适配配置");
   const keys=["mode","resultClass","successMethod","dataProperty","codeProperty","codeType","successCode","messageProperty","pageClass","pageFactoryMethod","recordsProperty","totalProperty","exceptionClass","notFoundMethod"];
   for(const [key,value] of Object.entries(data.integration))if(!keys.includes(key)||(value!==null&&typeof value!=="string"))throw new Error("工程适配配置字段不合法："+key);
   this.spec.integration=JSON.parse(JSON.stringify(data.integration));
 },
 async importEngineeringProfile(e){const file=e.target.files[0];if(!file)return;await this.run(async()=>{if(file.size>32768)throw new Error("工程适配配置不能超过32KB");this.applyEngineeringProfile(this.parseEditorJson(await file.text()));this.notify("工程适配配置已载入，请核对类名和方法后生成。");});e.target.value="";},
 async loadEngineeringExample(){if(!this.canReplaceSpec())return;const r=await fetch("/examples/engineering-spec.json");if(!r.ok)throw new Error("工程适配示例未找到");this.spec=await r.json();this.resetDrafts();},
 endpointFor(op){
   if(!this.spec.endpoints)this.spec.endpoints={};
   if(!this.spec.endpoints[op])this.spec.endpoints[op]={};
   return this.spec.endpoints[op];
 },
 endpointDefault(op,key){
   const bank=this.spec.apiProfile==='BANK_POST',rest=this.spec.apiProfile==='REST';
   const hasId=['UPDATE','DETAIL','DELETE'].includes(op),dto=['CREATE','UPDATE','PAGE'].includes(op);
   const method=bank?'POST':op==='DETAIL'?'GET':op==='DELETE'?'DELETE':op==='UPDATE'?(this.spec.updateMode==='PARTIAL'?'PATCH':'PUT'):op==='PAGE'&&rest?'GET':'POST';
   const e=this.endpointFor(op),effectiveMethod=e.method||method;
   return {method,path:bank?'/'+op.toLowerCase():hasId?'/{id}':op==='PAGE'?(rest?'':'/page'):'',requestIn:dto?(effectiveMethod==='GET'?'QUERY':'BODY'):'NONE',idIn:hasId?(bank?'BODY':'PATH'):'NONE'}[key];
 },
 endpointFields(op){return this.spec.fields.filter(f=>(op==='CREATE'&&(f.source||'CLIENT')==='CLIENT')||(op==='UPDATE'&&f.editable&&(f.source||'CLIENT')==='CLIENT')||(op==='PAGE'&&f.query!=='NONE'));},
 customizeFields(op){this.endpointFor(op).fields=this.endpointFields(op).map(f=>f.name);},
 customizeRequired(op){this.endpointFor(op).requiredFields=this.endpointFields(op).filter(f=>f.required&&op!=='PAGE'&&!(op==='UPDATE'&&this.spec.updateMode==='PARTIAL')).map(f=>f.name);},
 resetInterfaceConfig(){this.spec.endpoints={};},
 async loadInterfaceExample(){if(!this.canReplaceSpec())return;const r=await fetch('/examples/interface-config-spec.json');if(!r.ok)throw new Error('接口配置示例未找到');this.spec=await r.json();this.resetDrafts();},
   applyIndexes(value){this.indexDraft=value;try{const indexes=this.parseEditorJson(value);if(!Array.isArray(indexes))throw new Error();this.spec.indexes=indexes;this.indexDraftError='';}catch(error){this.indexDraftError="索引配置必须为合法 JSON 数组";this.notify(this.indexDraftError,true);}},
  resetDrafts(){this.indexDraft=null;this.indexDraftError='';this.defaultDrafts={};this.defaultDraftErrors={};this.specBaseline=this.specFingerprint();},
  setRequired(field){field.nullable=!field.required;},
  assertDrafts(){if(this.indexDraftError||Object.values(this.defaultDraftErrors).some(Boolean))throw new Error('请先修正索引或默认值 JSON 草稿，当前配置不能生成或导出');},
 generationOptions(){if(!this.spec.options)this.spec.options={};return this.spec.options;},
 sourceChanged(field){if(field.source!=='SERVER')field.serverUpdate=false;if((field.source||'CLIENT')!=='CLIENT'){field.editable=false;for(const operation of ['CREATE','UPDATE']){const endpoint=this.spec.endpoints?.[operation];for(const key of ['fields','requiredFields'])if(endpoint?.[key])endpoint[key]=endpoint[key].filter(name=>name!==field.name);}this.notify('服务端/数据库字段已退出客户端写入字段，请核对来源与默认值。');}},
 setDefaultValue(field,value){this.defaultDrafts[field.name]=value;if(value.trim()===''){delete field.defaultValue;delete this.defaultDraftErrors[field.name];return;}try{field.defaultValue=this.parseEditorJson(value);delete this.defaultDraftErrors[field.name];}catch(error){this.defaultDraftErrors[field.name]=error.message;this.notify(error.message,true);}},
 renameReferences(oldName,newName){if(!oldName||oldName===newName)return;for(const endpoint of Object.values(this.spec.endpoints||{}))for(const key of ['fields','requiredFields'])if(endpoint[key])endpoint[key]=endpoint[key].map(name=>name===oldName?newName:name);for(const index of this.spec.indexes||[])index.fields=index.fields.map(name=>name===oldName?newName:name);if(this.spec.options?.scopeField===oldName)this.spec.options.scopeField=newName;if(this.spec.options?.auditFields)this.spec.options.auditFields=this.spec.options.auditFields.map(x=>x===oldName?newName:x);this.notify('字段引用已同步，请核对接口和索引配置。');},
 removeField(position){const name=this.spec.fields[position].name;delete this.defaultDrafts[name];delete this.defaultDraftErrors[name];this.spec.fields.splice(position,1);for(const endpoint of Object.values(this.spec.endpoints||{}))for(const key of ['fields','requiredFields'])if(endpoint[key])endpoint[key]=endpoint[key].filter(value=>value!==name);this.spec.indexes=(this.spec.indexes||[]).filter(index=>!index.fields.includes(name));if(this.spec.options?.scopeField===name)delete this.spec.options.scopeField;if(this.spec.options?.auditFields)this.spec.options.auditFields=this.spec.options.auditFields.filter(x=>x!==name);this.notify('字段及引用已移除；涉及该字段的索引已移除，请核对。');},
 async importSpecText(){if(!this.canReplaceSpec())return;await this.run(async()=>{this.spec=await this.request('/api/requirements/text',{method:'POST',headers:{'Content-Type':'application/json'},body:this.specText});this.resetDrafts();this.notify('文本规格已载入，请核对解析结果。');});},
 copySpecText(){try{this.specText=this.pretty(this.normalized());}catch(e){this.notify(e.message,true);}},
 normalized(){
   this.assertDrafts();
   const s=JSON.parse(JSON.stringify(this.spec));
   if(!s.integration||!s.integration.mode||s.integration.mode==='STANDALONE')delete s.integration;
   if(s.endpoints)s.endpoints=Object.fromEntries(Object.entries(s.endpoints).filter(([op])=>s.operations.includes(op)));
   if(!s.operations.includes('UPDATE')&&s.updateMode==='PARTIAL')delete s.updateMode;
   for(const f of s.fields)for(const key of ['maxLength','precision','scale'])f[key]=f[key]===''||f[key]===undefined?null:f[key];
   return s;
 },
 exportSpec(){try{this.assertDrafts();}catch(e){this.notify(e.message,true);return;}const url=URL.createObjectURL(new Blob([this.pretty(this.normalized())],{type:'application/json'}));const a=document.createElement('a');a.href=url;a.download='generation-spec.json';a.click();URL.revokeObjectURL(url);},
 async importSpec(e){const f=e.target.files[0];if(!f)return;if(!this.canReplaceSpec()){e.target.value='';return;}await this.run(async()=>{const data=new FormData();data.append('file',f);this.spec=await this.request('/api/requirements/import',{method:'POST',body:data});this.resetDrafts();this.notify('需求已导入，请核对字段和操作。');});e.target.value='';},
 async generateLegacy(){await this.run(async()=>{this.generation=await this.request('/api/generate',{method:'POST',headers:{'Content-Type':'application/json'},body:this.pretty(this.normalized())});this.previewPath=Object.keys(this.generation.files).find(x=>x.endsWith('Controller.java'));await this.refresh();this.notify(this.spec.integration?.mode==='EXISTING'?'模块已生成，请按 README 合入宿主并完成接入验收。':'代码已生成。请下载后执行离线编译和测试。');});},
 async createContractFile(){await this.run(async()=>{if(!this.contractFile)throw new Error('请选择源码ZIP或接口JSON');const d=new FormData();d.append('file',this.contractFile);d.append('label',this.contractLabel);const r=await this.request('/api/contracts/'+this.contractMode,{method:'POST',body:d});this.document=r.document;this.documentId=r.metadata.id;this.selectedOp=null;await this.refresh();this.notify('接口版本已保存，请查看覆盖范围。');});},
 async viewContractLegacy(h){await this.run(async()=>{this.document=await this.request('/api/contracts/'+h.id);this.documentId=h.id;this.selectedOp=null;});},
 async compareLegacy(){await this.run(async()=>{if(!this.baselineId||!this.targetId)throw new Error('请选择基线和目标版本');if(this.baselineId===this.targetId)throw new Error('请选择不同的接口版本');this.comparison=await this.request('/api/compare',{method:'POST',headers:{'Content-Type':'application/json'},body:this.pretty({baselineId:this.baselineId,targetId:this.targetId,bindings:this.bindings})});await this.refresh();this.notify('比较已完成，请检查结论、风险项和覆盖缺口。');});},
 async validateSample(){await this.run(async()=>{if(!this.sample.contractId)throw new Error('请选择接口版本');const sent=this.pretty(this.sample);const result=await this.request('/api/validate-response',{method:'POST',headers:{'Content-Type':'application/json'},body:sent});if(this.pretty(this.sample)===sent)this.sampleResult=result;});},
 async regenerateLegacy(){await this.run(async()=>{if(!this.updateSpecFile||!this.currentProjectFile)throw new Error('请选择新规格和当前项目ZIP');const d=new FormData();d.append('spec',this.updateSpecFile);d.append('current',this.currentProjectFile);this.regeneration=await this.request('/api/regenerate',{method:'POST',body:d});await this.refresh();this.notify('候选代码与冲突清单已生成，请人工合并。');});},
 conflictLabel(s){return ({UNCHANGED:'无变化',NEW:'新文件',SAFE_REPLACE:'原文件未被手改，可替换',KEEP_LOCAL:'保留人工修改',LOCAL_MODIFIED:'双方都修改，需合并',LOCAL_FILE_COLLISION:'同名人工文件，需复核',DELETED_LOCALLY:'文件已被人工删除，需复核',DELETE_SUGGESTION:'建议删除，需复核'})[s]||s},
 kindLabel(s){return ({generation:'代码生成',contract:'接口版本',comparison:'接口比较',regeneration:'更新候选',frontend:'前端接口关联',mock:'Mock与测试用例',sourcecomparison:'前后源码变更',merge:'三方合并'})[s]||s}
 }
}).mount('#app');
