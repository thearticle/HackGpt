const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(require('node:path').join(__dirname, '../preview/index.html'), 'utf8');
const start = html.indexOf('  window.hackGptCodexEvent = async (kind, data) => {');
const end = html.indexOf('    let message;', start);
const handler = html.slice(start, end) + '\n};';
async function run({fail = false, stale = false, saved = 'saved-large-thread', restoreFails = false} = {}) {
  const calls = [];
  const context = {
    window: {hackGptCodexMode: {}}, connectionAttempt: 1,
    connected: false, connecting: true, savedEndpoint: '', endpoint: 'wss://test/', retryCount: 3,
    threadId: saved, threadModel: '', threadEffort: '', outcome: '',
    bridge: {send() {}, disconnect() { calls.push('disconnect'); }},
    rpc: async method => { calls.push(method); if (fail) throw Error('rejected'); },
    refresh: async () => { calls.push('list'); if (stale) context.connectionAttempt++; },
    updateSavedTokenHint() {}, loadModelPreferences() {},
    loadModelCatalog: async () => {}, loadQuota() {}, saveDraft() { calls.push('save'); },
    log: {replaceChildren() {}}, replies: new Map(), fileChanges: new Map(),
    dialog: {close() {}}, enterMode() {}, swapAttachments() {}, threadAttachments() {},
    setDraft() {}, readDraft() {}, sessionDetail: {}, sync() {},
    pick: {onclick() { calls.push('picker'); }},
    openThread: async () => { calls.push('resume'); assert.equal(context.connecting, false); if (restoreFails) throw Error('missing thread'); return new Promise(() => {}); },
    disconnected(message) { calls.push(message); },
  };
  vm.createContext(context);
  vm.runInContext(handler, context);
  await context.window.hackGptCodexEvent('open');
  return {context, calls};
}
(async () => {
  const refreshStart = html.indexOf('  async function refresh() {', start - 20000);
  const refreshEnd = html.indexOf('  const renameThreadForm', refreshStart);
  const pages = [
    {data: [{id: 'first', name: 'First'}], nextCursor: 'page2'},
    {data: [{id: 'app-server', name: 'HackGpt'}], nextCursor: null}
  ];
  const listingCalls = [];
  const listing = {
    connectionAttempt: 1, project: 'C:\\Projects\\HackGpt', threadId: '', threadLabels: new Map(),
    updateThreadName() {}, threadDialog: {open: false}, renderThreadPicker() {},
    rpc: async (method, params) => { listingCalls.push({method, params}); return pages.shift(); }
  };
  vm.createContext(listing);
  vm.runInContext(html.slice(refreshStart, refreshEnd), listing);
  await listing.refresh();
  assert.equal(listing.threadLabels.size, 2);
  assert(listingCalls[0].params.sourceKinds.includes('appServer'));
  assert.equal(listingCalls[1].params.cursor, 'page2');
  const connectStart = html.indexOf('  function connectToPc(');
  const connectEnd = html.indexOf('    const error = supportsProfiles', connectStart);
  const pathCheck = html.slice(connectStart, connectEnd) + '\nreturn true; }';
  const validation = {connecting: false, connected: false, busy: false, reconnectAllowed: true,
    clearTimeout() {}, reconnectTimer: null, connectionError: {}, sessionDetail: {}};
  vm.createContext(validation);
  vm.runInContext(pathCheck, validation);
  assert.equal(validation.connectToPc('', 'androidllm', ''), undefined);
  assert(validation.connectionError.textContent.includes('full project folder path'));
  for (const path of ['C:\\Users\\Ojan\\Documents\\ChatGPT\\android llm', '/home/user/project', '\\\\server\\share\\project']) {
    assert.equal(validation.connectToPc('', path, ''), true);
  }
  const ok = await run();
  assert.equal(ok.context.connected, true);
  assert.equal(ok.context.connecting, false);
  assert.equal(ok.context.threadId, '');
  assert(!ok.calls.includes('picker'));
  assert(ok.calls.includes('resume'), 'restore saved history after connection becomes ready');
  assert(ok.calls.indexOf('save') < ok.calls.indexOf('resume'));
  const fresh = await run({saved: ''});
  assert(fresh.calls.includes('picker'));
  const restoreFailure = await run({restoreFails: true});
  await Promise.resolve();
  assert.equal(restoreFailure.context.connected, true);
  assert(restoreFailure.calls.includes('picker'));
  const failure = await run({fail: true});
  assert(failure.calls.includes('disconnect'));
  assert(!failure.calls.includes('picker'));
  const stale = await run({stale: true});
  assert(!stale.calls.includes('picker'));
  assert.equal(stale.context.connected, false);
  const syncStart = html.indexOf('  async function refreshWorkspace() {');
  const syncEnd = html.indexOf('  async function openThread(id)', syncStart);
  const syncCalls = [];
  const workspace = {connected: true, connecting: false, document: {hidden: false}, busy: false,
    connectionAttempt: 1, threadId: 'desktop-thread',
    refresh: async () => syncCalls.push('list'), openThread: async id => syncCalls.push(id)};
  vm.createContext(workspace);
  vm.runInContext(html.slice(syncStart, syncEnd), workspace);
  await workspace.refreshWorkspace();
  assert.deepEqual(syncCalls, ['list', 'desktop-thread']);
  for (const flag of ['busy', 'connecting']) {
    syncCalls.length = 0; workspace[flag] = true;
    await workspace.refreshWorkspace();
    assert.equal(syncCalls.length, 0);
    workspace[flag] = false;
  }
  workspace.refresh = async () => { workspace.connectionAttempt++; };
  await workspace.refreshWorkspace();
  assert.equal(syncCalls.length, 0, 'stale refresh must not restore another connection');
  for (const script of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(script[1]);
  console.log('PASS: workspace refresh, saved-thread handoff, restore failure fallback, busy/stale guards, pagination, path validation, and script syntax.');
})().catch(error => { console.error(error); process.exitCode = 1; });
