import fs from 'node:fs/promises';
import path from 'node:path';
import vm from 'node:vm';
import assert from 'node:assert/strict';

const results = [];
const projectRoot = process.cwd();
const matrix = process.argv[2];
const output = process.argv[3];
async function test(name, fn) {
  try { await fn(); results.push({ name, passed: true }); }
  catch (error) { results.push({ name, passed: false, error: String(error) }); console.log('FAIL', name, String(error)); }
}
async function client(fixture) {
  const code = await fs.readFile(path.join(matrix, fixture, 'frontend/api.js'), 'utf8');
  const module = await import('data:text/javascript;base64,' + Buffer.from(code).toString('base64'));
  const calls = [];
  return { api: module.createContractApi({ request(config) { calls.push(config); return config; } }), calls };
}
const native = await client('native-numbers');
const strings = await client('contract-v2-spec');
const safe = await client('production-safe-spec');
const invalid = (instance, fn) => { const before = instance.calls.length; assert.throws(fn); assert.equal(instance.calls.length, before); };
await test('native-unsafe-id-rejected-before-http', () => invalid(native, () => native.api.detail(Number.MAX_SAFE_INTEGER + 1)));
await test('native-zero-id-rejected-before-http', () => invalid(native, () => native.api.detail(0)));
await test('native-unsafe-long-field-rejected', () => invalid(native, () => native.api.create({ externalId: Number.MAX_SAFE_INTEGER + 1, amount: 0.01 })));
await test('native-integer-range-enforced', () => invalid(native, () => native.api.create({ quantity: 2147483648, amount: 0.01 })));
await test('native-nonfinite-decimal-rejected', () => invalid(native, () => native.api.create({ amount: NaN })));
await test('native-invalid-list-element-rejected', () => invalid(native, () => native.api.page({ externalId: [null] })));
await test('native-query-range-enforced', () => invalid(native, () => native.api.page({ size: 101 })));
await test('string-long-overflow-rejected', () => invalid(strings, () => strings.api.detail('9223372036854775808')));
await test('string-numeric-id-rejected', () => invalid(strings, () => strings.api.detail(1)));
await test('rest-update-mismatched-id-rejected', () => invalid(strings, () => strings.api.update('1', { id: '2', contractNo: 'X' })));
await test('post-update-mismatched-id-rejected', () => invalid(safe, () => safe.api.update('1', { id: '2', expectedVersion: '0', contractNo: 'X' })));
await test('update-version-is-required', () => invalid(safe, () => safe.api.update('1', { contractNo: 'X' })));
await test('delete-version-overflow-rejected', () => invalid(safe, () => safe.api.delete('1', '9223372036854775808')));
await test('valid-native-update-preserves-data', () => {
  const config = native.api.update(1, { id: 1, expectedVersion: 0, amount: 1.23 });
  assert.equal(config.data.amount, 1.23); assert.equal(config.data.id, 1);
});
await test('valid-string-post-update-preserves-id-and-version', () => {
  const config = safe.api.update('1', { id: '1', expectedVersion: '0', contractNo: 'X' });
  assert.equal(config.data.id, '1'); assert.equal(config.data.expectedVersion, '0');
});
await test('query-array-serialization-repeats-keys', () => {
  const config = native.api.page({ externalId: [1, 2], page: 1, size: 20 });
  assert.match(config.paramsSerializer(config.params), /externalId=1&externalId=2/);
});

// Use the immutable original resource snapshot for the before run.
const resourceRoot = process.argv[4] || 'src/main/resources';
const context = vm.createContext({ console, URLSearchParams });
const uiSource = await fs.readFile(path.join(resourceRoot, 'static/workflow.js'), 'utf8');
vm.runInContext(uiSource + '; globalThis.testWorkflow = workflowMixin;', context);
const workflow = context.testWorkflow;
// JSON editor validation is shared by the actual page's usability mixin.
vm.runInContext(await fs.readFile(path.join(resourceRoot, 'static/usability.js'), 'utf8') + ';globalThis.testEditorHelpers=usabilityMixin.methods;',context);
const editorHelpers={jsonText:context.testEditorHelpers.jsonText,parseEditorJson:context.testEditorHelpers.parseEditorJson};
await test('scenario-save-does-not-mark-later-edits-saved', async () => {
  let finish;
  const submitted = { schemaVersion: 1, id: 'caseone', name: 'before', steps: [] };
  const state = {
    ...editorHelpers,
    regText: JSON.stringify(submitted), regScenarioId: 'caseone', regSavedText: '',
    run: fn => fn(), request: () => new Promise(resolve => { finish = resolve; }),
    canonicalScenario: workflow.methods.canonicalScenario, regRefresh: async () => {}, notify: () => {},
  };
  const saving = workflow.methods.regSave.call(state);
  state.regText = JSON.stringify({ ...submitted, name: 'after' }); finish({}); await saving;
  assert.equal(state.regSavedText, state.canonicalScenario(JSON.stringify(submitted)));
  assert.notEqual(state.regSavedText, state.canonicalScenario(state.regText));
});
await test('scenario-save-does-not-switch-back-from-another-selection', async () => {
  let finish;
  const state = {
    ...editorHelpers,
    regText: JSON.stringify({ id: 'caseone' }), regScenarioId: 'caseone', regSavedText: 'before',
    run: fn => fn(), request: () => new Promise(resolve => { finish = resolve; }),
    canonicalScenario: workflow.methods.canonicalScenario, regRefresh: async () => {}, notify: () => {},
  };
  const saving = workflow.methods.regSave.call(state); state.regScenarioId = 'casetwo'; state.regSavedText = 'second'; finish({}); await saving;
  assert.equal(state.regScenarioId, 'casetwo'); assert.equal(state.regSavedText, 'second');
});
await test('environment-save-preserves-later-edits', async () => {
  const baseContext = vm.createContext({ console });
  vm.runInContext(await fs.readFile(path.join(resourceRoot, 'static/regression.js'), 'utf8') + '; globalThis.testRegression = regressionMixin;', baseContext);
  const methods = { ...baseContext.testRegression.methods, ...workflow.methods };
  let finish;
  const state = {
    ...editorHelpers,
    regEnvText: JSON.stringify({ id: 'envone', name: 'before' }), regEnvId: 'envone', regEnvs: [{ id: 'envone', name: 'before' }],
    run: fn => fn(), request: () => new Promise(resolve => { finish = resolve; }), regRefresh: async () => {}, notify: () => {},
    pretty: value => JSON.stringify(value), regLoadEnvironment: methods.regLoadEnvironment,
  };
  const saving = methods.regSaveEnv.call(state); state.regEnvText = JSON.stringify({ id: 'envone', name: 'after' }); finish({}); await saving;
  assert.equal(JSON.parse(state.regEnvText).name, 'after');
});
const failed = results.filter(result => !result.passed).length;
await fs.writeFile(output, JSON.stringify({ tests: results.length, passed: results.length - failed, failed, results }, null, 2));
console.log(`Tests=${results.length} Passed=${results.length - failed} Failed=${failed}`);
if (failed) process.exitCode = 1;
