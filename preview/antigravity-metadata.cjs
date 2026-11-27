const http = require('node:http');
const { timingSafeEqual } = require('node:crypto');
const fs = require('node:fs');

const key = process.env.HACKGPT_ANTIGRAVITY_KEY;
const token = fs.readFileSync(process.argv[2], 'utf8').trim();
if (!key || !token) throw new Error('Antigravity API key and Codex connection token are required.');
const expected = Buffer.from('Bearer ' + token);

async function read(path) {
  const response = await fetch('http://127.0.0.1:8045' + path, {
    headers: {Authorization: 'Bearer ' + key},
    redirect: 'error',
    signal: AbortSignal.timeout(5000)
  });
  if (!response.ok) throw new Error('Antigravity returned HTTP ' + response.status);
  return response.json();
}

http.createServer(async (request, response) => {
  const reply = (status, body) => {
    response.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'});
    response.end(JSON.stringify(body));
  };
  if (request.method !== 'GET' || request.url !== '/') return reply(404, {});
  const supplied = Buffer.from(request.headers.authorization || '');
  if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) return reply(401, {});
  try {
    const [catalog, account] = await Promise.all([
      read('/v1/models'),
      read('/api/accounts/current').catch(() => null)
    ]);
    reply(200, {
      models: (catalog.data ?? []).filter(item => typeof item.id === 'string').map(item => ({
        id: item.id, name: item.display_name || item.id,
        thinking: item.supports_thinking === true, budget: item.thinking_budget
      })),
      quota: (account?.quota?.models ?? []).filter(item => typeof item.name === 'string'
        && Number.isFinite(item.percentage)).map(item => ({
        id: item.name, remaining: item.percentage, resetsAt: item.reset_time
      }))
    });
  } catch {
    reply(502, {error: 'Antigravity metadata is unavailable.'});
  }
}).listen(4502, '127.0.0.1');
