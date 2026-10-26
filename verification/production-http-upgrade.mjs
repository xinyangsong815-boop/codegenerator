import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const origin=process.argv[2],output=process.argv[3];
const config=await (await fetch(origin+'/api/config')).json();
const headers={'Content-Type':'application/json','X-Tool-Token':config.token,Origin:origin};
const spec=JSON.parse(await fs.readFile('src/main/resources/static/examples/production-safe-spec.json','utf8'));
const results=[];
async function post(url,data,status=200){const r=await fetch(origin+url,{method:'POST',headers,body:typeof data==='string'?data:JSON.stringify(data)});const text=await r.text();assert.equal(r.status,status,text.slice(0,200));return JSON.parse(text);}
async function test(name,fn){try{await fn();results.push({name,passed:true});}catch(e){results.push({name,passed:false,error:e.message});}}
let generation;
await test('generated-production-artifacts-exist',async()=>{generation=await post('/api/generate',spec);assert.ok(generation.files['production-evidence-template.json']);assert.ok(Object.keys(generation.files).some(x=>x.endsWith('/MysqlCrudAcceptanceTest.java')));assert.ok(generation.files['integration/outbox-schema.sql']);});
await test('fresh-evidence-binding-matches-but-remains-incomplete',async()=>{const r=await post('/api/production/evidence/check',{spec,evidence:JSON.parse(generation.files['production-evidence-template.json'])});assert.equal(r.bindingMatched,true);assert.equal(r.deploymentReady,false);assert.equal(r.verdict,'INCOMPLETE');assert.ok(r.checks.some(x=>x.status==='NOT_RUN'));});
await test('stale-evidence-detected-over-http',async()=>{const e=JSON.parse(generation.files['production-evidence-template.json']);e.templateVersion='old';const r=await post('/api/production/evidence/check',{spec,evidence:e});assert.equal(r.bindingMatched,false);assert.ok(r.findings.includes('STALE_BINDING:templateVersion'));});
await test('migration-plan-is-read-only-review',async()=>{const r=await post('/api/production/migration',{previousSpec:spec,currentSpec:spec,previousTemplateVersion:JSON.parse(generation.files['generation-manifest.json']).templateVersion});assert.equal(r.executionStatus,'NOT_RUN');assert.equal(r.autoExecutable,false);assert.equal(r.changes.length,0);});
await test('history-diagnostics-available',async()=>{const r=await fetch(origin+'/api/history/diagnostics');assert.equal(r.status,200);const data=await r.json();assert.equal(data.quarantinedRecords,0);assert.equal(data.unquarantinedRecords,0);});
await test('regression-page-has-explicit-count',async()=>{const r=await fetch(origin+'/api/regression/scenarios/page?offset=0&limit=1');assert.equal(r.status,200);const data=await r.json();assert.ok(Array.isArray(data.items));assert.equal(typeof data.totalCount,'number');assert.equal(typeof data.hasMore,'boolean');});
await test('duplicate-keys-rejected-before-contract-import',async()=>{await post('/api/contracts/text','{"label":"duplicate","document":{"openapi":"3.0.3","paths":{"/a":{},"/a":{}},"info":{"title":"x","version":"1"}}}',400);});
const failed=results.filter(x=>!x.passed).length;
await fs.writeFile(output,JSON.stringify({version:config.version,tests:results.length,passed:results.length-failed,failed,results},null,2));
console.log(JSON.stringify({tests:results.length,failed}));if(failed)process.exitCode=1;
