const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(require('node:path').join(__dirname, '../preview/index.html'), 'utf8');
const start = html.indexOf('  window.hackGptCodexEvent = async (kind, data) => {');
const end = html.indexOf('    let message;', start);
const handler = html.slice(start, end) + '\n};';
async function run({fail = false, stale = false} = {}) {
  const calls = [];
  const context = {
    window: {hackGptCodexMode: {}}, connectionAttempt: 1,
    connected: false, connecting: true, savedEndpoint: '', endpoint: 'wss://test/', retryCount: 3,
    threadId: 'saved-large-thread', threadModel: '', threadEffort: '', outcome: '',
    bridge: {send() {}, disconnect() { calls.push('disconnect'); }},
    rpc: async method => { calls.push(method); if (fail) throw Error('rejected'); },
    refresh: async () => { calls.push('list'); if (stale) context.connectionAttempt++; },
    updateSavedTokenHint() {}, loadModelPreferences() {},
    loadModelCatalog: async () => {}, loadQuota() {}, saveDraft() { calls.push('save'); },
    log: {replaceChildren() {}}, replies: new Map(), fileChanges: new Map(),
    dialog: {close() {}}, enterMode() {}, swapAttachments() {}, threadAttachments() {},
    setDraft() {}, readDraft() {}, sessionDetail: {}, sync() {},
    pick: {onclick() { calls.push('picker'); }},
    openThread: () => { calls.push('resume'); return new Promise(() => {}); },
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
  assert(ok.calls.includes('picker'));
  assert(!ok.calls.includes('resume'), 'saved history must not gate reconnection');
  assert(ok.calls.indexOf('save') < ok.calls.indexOf('picker'));
  const failure = await run({fail: true});
  assert(failure.calls.includes('disconnect'));
  assert(!failure.calls.includes('picker'));
  const stale = await run({stale: true});
  assert(!stale.calls.includes('picker'));
  assert.equal(stale.context.connected, false);
  for (const script of html.matchAll(/<script>([\s\S]*?)<\/script>/g)) new vm.Script(script[1]);
  console.log('PASS: full PC paths required; app-server threads included; thread pages loaded; reconnect, stale/error handling, and script syntax pass.');
})().catch(error => { console.error(error); process.exitCode = 1; });
