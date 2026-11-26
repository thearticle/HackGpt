const http = require('node:http');
const fs = require('node:fs/promises');
const path = require('node:path');
const vault = require('./provider-vault.cjs');
const {imageEndpoint, imageSource, KENARI_BASE, messageParts, modelMetadata, streamReply} = require('./chat-stream.cjs');

const port = Number(process.env.NEURAL_PREVIEW_PORT || 8082);
const hosts = new Set(['localhost:' + port, '127.0.0.1:' + port]);
const GEMINI_METADATA_BASE = 'https://generativelanguage.googleapis.com/v1beta';
const NARA_ORIGIN = 'https://router.bynara.id';
const presets = {
    openrouter: {name: 'OpenRouter', base: 'https://openrouter.ai/api/v1', listing: '/models/user'},
    openai: {name: 'OpenAI', base: 'https://api.openai.com/v1', listing: '/models'},
    gemini: {name: 'Gemini', base: 'https://generativelanguage.googleapis.com/v1beta/openai', listing: '/models'},
    custom: {name: 'Custom', listing: '/models'}
};
const connections = new Map();
let savedConnections = new Map();
let vaultAvailable = true;
let vaultQueue = Promise.resolve();

function updateVault(change) {
    const operation = vaultQueue.then(async () => {
        const next = new Map(savedConnections);
        if (change(next) === false) return;
        if (!vaultAvailable) throw problem(503, 'The Windows credential vault could not be opened.');
        try {
            await vault.save([...next.values()]);
        } catch {
            throw problem(503, 'Could not update the Windows credential vault.');
        }
        savedConnections = next;
    });
    vaultQueue = operation.catch(() => {});
    return operation;
}
const server = http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    const host = request.headers.host;
    if (!hosts.has(host)) return send(response, 403, {error: 'Open the preview on localhost.'});
    try {
        if (request.method === 'GET' && request.url === '/') {
            const page = await fs.readFile(path.join(__dirname, 'index.html'));
            response.writeHead(200, {'Content-Type': 'text/html; charset=utf-8'}).end(page);
            return;
        }
        if (request.method === 'GET' && request.url === '/markdown-it.min.js') {
            const script = await fs.readFile(path.join(__dirname, 'vendor', 'markdown-it.min.js'));
            response.writeHead(200, {'Content-Type': 'application/javascript; charset=utf-8'}).end(script);
            return;
        }
        if (request.method === 'GET' && request.url === '/api/providers') {
            return send(response, 200, [...connections.values()].map(publicProvider));
        }
        if (request.method !== 'POST' || request.headers.origin !== 'http://' + host
                || request.headers['content-type'] !== 'application/json') {
            return send(response, 403, {error: 'Use the local preview to change provider settings.'});
        }
        if (request.url === '/api/connect') {
            const input = await readBody(request);
            if (connections.size >= 64) throw problem(429, 'Disconnect an unused provider before adding another.');
            const previous = connections.get(input.connection);
            const candidate = connectionFrom(input, previous);
            const result = await upstream(candidate, candidate.listing);
            if (!Array.isArray(result.data)) throw problem(502, 'The model catalog has an unsupported format.');
            candidate.models = Array.from(new Map(result.data
                .filter(model => typeof model?.id === 'string' && model.id.length <= 300)
                .filter(model => {
                    const endpoints = Array.isArray(model.endpoints) ? model.endpoints : null;
                    if (candidate.base === KENARI_BASE && endpoints
                            && !endpoints.includes('chat') && !endpoints.includes('images')) return false;
                    const output = model.architecture?.output_modalities ?? model.output_modalities;
                    return !Array.isArray(output) || output.includes('text') || output.includes('image')
                        || imageEndpoint(candidate.type, candidate.base, model.id,
                            candidate.base === KENARI_BASE && endpoints?.includes('images') && !endpoints.includes('chat'));
                })
                .map(model => [model.id, modelMetadata(model, candidate.type, candidate.base)])).values());
            if (!candidate.models.length) throw problem(422, 'No supported models were returned.');
            connections.set(candidate.id, candidate);
            return send(response, 200, publicProvider(candidate));
        }
        if (request.url === '/api/kenari-balance') {
            const {connection: id} = await readBody(request);
            const connection = connections.get(id);
            if (connection?.base !== 'https://kenari.id/v1') throw problem(409, 'Connect a Kenari provider first.');
            const account = await upstream(connection, '/account/balance');
            return send(response, 200, {balance: account.available_micro_idr});
        }
        if (request.url === '/api/nara-balance') {
            const {connection: id} = await readBody(request);
            const connection = connections.get(id);
            if (connection?.base !== NARA_ORIGIN + '/v1') throw problem(409, 'Connect a NaraRouter provider first.');
            const {result} = await upstream({...connection, base: NARA_ORIGIN}, '/mcp', {
                jsonrpc: '2.0', id: 1, method: 'tools/call',
                params: {name: 'get_balance', arguments: {}}
            });
            if (!result || result.isError) throw problem(502, 'NaraRouter balance is unavailable.');
            return send(response, 200, {balance: naraAvailable(result)});
        }
        if (request.url === '/api/routr-quota') {
            const {connection: id, model} = await readBody(request);
            const connection = connections.get(id);
            if (connection?.base !== 'https://api.routr.cloud/v1') throw problem(409, 'Connect a Routr provider first.');
            const account = await upstream({...connection, base: 'https://api.routr.cloud'}, '/api/me/model-quotas');
            const quota = Array.isArray(account.quotas) && account.quotas.find(item => item?.model_sku === model);
            return send(response, 200, {balance: quota?.available_credits});
        }
        if (request.url === '/api/provider-details') {
            const {connection: id, model} = await readBody(request);
            const connection = connections.get(id);
            if (!connection) throw problem(409, 'Connect the provider first.');
            return send(response, 200, await providerDetails(connection, model));
        }
        if (request.url === '/api/save') {
            const input = await readBody(request);
            const connection = connections.get(input.connection);
            if (!connection) throw problem(409, 'Enter an API key before saving this provider.');
            await updateVault(next => {
                if (!connections.has(connection.id)) throw problem(409, 'The provider was disconnected.');
                if (next.has(connection.id)) return false;
                next.set(connection.id, connection);
            });
            return send(response, 200, publicProvider(connection));
        }
        if (request.url === '/api/disconnect') {
            const input = await readBody(request);
            await updateVault(next => next.delete(input.connection));
            connections.delete(input.connection);
            return send(response, 200, null);
        }
        if (request.url === '/api/chat/stream') {
            const started = performance.now();
            const input = await readBody(request, 12_000_000);
            const connection = connections.get(input.connection);
            if (!connection) throw problem(409, 'The provider is disconnected. Open Settings to connect it.');
            let model = connection.models.find(item => item.id === input.model);
            if (!model && input.model === 'beam-1-0:free' && connection.base === 'https://kenari.id/v1'
                    && savedConnections.has(connection.id)) {
                model = {id: input.model, input: {image: false, pdf: false}};
            }
            if (!model) throw problem(400, 'Select a connected model.');
            if (!Array.isArray(input.messages) || !input.messages.length) throw problem(400, 'Enter a message.');
            const {endpoint, payload, generated} = replyRequest(connection, model, input.messages, true, input.textOnly === true);
            const controller = new AbortController();
            response.once('close', () => {
                if (!response.writableEnded) controller.abort();
            });
            if (response.destroyed) return;
            const reply = await openUpstream(connection, endpoint, payload, controller.signal);
            await streamReply(reply, response, started, readUpstreamJson, controller.signal, generated);
            return;
        }

        if (request.url === '/api/chat') {
            const input = await readBody(request);
            const connection = connections.get(input.connection);
            if (!connection) {
                throw problem(409, 'The provider is disconnected. Reconnect it in Settings.');
            }
            const model = connection.models.find(model => model.id === input.model);
            if (!model) throw problem(400, 'Select a connected model.');
            if (!Array.isArray(input.messages) || !input.messages.length
                    || input.messages.some(message => !['user', 'assistant'].includes(message?.role)
                        || typeof message.content !== 'string')) throw problem(400, 'Enter a message.');
            const {endpoint, payload, generated} = replyRequest(connection, model, input.messages, false);
            const result = await upstream(connection, endpoint, payload);
            const message = result.choices?.[0]?.message;
            const images = generated ? [result.data] : [message?.images, message?.content];
            if ((generated || model.modalities) && images.some(parts => Array.isArray(parts) && parts.some(imageSource))) {
                return send(response, 200, {reply: 'Image generation succeeded.'});
            }
            const reply = result.choices?.[0]?.message?.content;
            if (typeof reply !== 'string' || !reply.trim()) throw problem(502, 'This model did not return text. Try a text-chat model.');
            return send(response, 200, {reply});
        }
        send(response, 404, {error: 'Unknown request.'});
    } catch (error) {
        if (response.destroyed) return;
        if (response.headersSent) { response.end(); return; }
        send(response, error.status || 500, {error: error.status ? error.message : 'The local preview could not complete this request.'});
    }
});

function problem(status, message) {
    return Object.assign(new Error(message), {status});
}

function send(response, status, data) {
    response.writeHead(status, {'Content-Type': 'application/json; charset=utf-8'}).end(JSON.stringify(data));
}

async function readBody(request, limit = 512_000) {
    const chunks = [];
    let size = 0;
    for await (const chunk of request.iterator({destroyOnReturn: false})) {
        size += chunk.length;
        if (size > limit) {
            request.resume();
            throw problem(413, 'This request is too large. Start a shorter chat.');
        }
        chunks.push(chunk);
    }
    try {
        const body = JSON.parse(Buffer.concat(chunks));
        if (!body || typeof body !== 'object' || Array.isArray(body)) throw new Error();
        return body;
    } catch {
        throw problem(400, 'Invalid settings or message.');
    }
}

function connectionFrom(input, previous) {
    const type = input.type;
    const preset = Object.hasOwn(presets, type) ? presets[type] : null;
    if (!preset) throw problem(400, 'Choose a provider.');
    let url;
    try {
        url = new URL(preset.base || input.base);
    } catch {
        throw problem(400, 'Enter a valid HTTPS API base URL.');
    }
    if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
        throw problem(400, 'Enter an HTTPS API base URL without credentials, query parameters, or a fragment.');
    }
    const base = url.href.replace(/\/+$/, '');
    const key = typeof input.key === 'string' ? input.key.trim() : '';
    const usableKey = key || (previous?.base === base && previous?.type === type ? previous.key : '');
    if (!usableKey || !/^[!-~]{1,4096}$/.test(usableKey)) throw problem(400, 'Enter a valid API key.');
    return {id: crypto.randomUUID(), type, base, key: usableKey, name: preset.base ? preset.name : url.hostname, listing: preset.listing};
}

function publicProvider(provider) {
    const {id, type, base, name, models} = provider;
    return {id, type, base, name, models, saved: savedConnections.has(id)};
}

async function providerDetails(connection, selectedModel) {
    const {base} = connection;
    if (base === GEMINI_METADATA_BASE + '/openai' && typeof selectedModel === 'string'
            && /^(?:models\/)?[A-Za-z0-9._-]{1,200}$/.test(selectedModel)) {
        const model = await upstream({...connection, base: GEMINI_METADATA_BASE},
            '/models/' + selectedModel.replace(/^models\//, ''));
        return {kind: 'gemini', models: [{id: selectedModel,
            inputTokenLimit: model.inputTokenLimit, outputTokenLimit: model.outputTokenLimit}]};
    }
    const kind = base === 'https://api.routr.cloud/v1' ? 'routr'
        : base === 'https://kenari.id/v1' ? 'kenari'
        : base === 'https://apimurah.com/v1' ? 'apimurah'
        : base === 'https://openrouter.ai/api/v1' ? 'openrouter' : 'generic';
    const provider = kind === 'routr' ? {...connection, base: 'https://api.routr.cloud'} : connection;
    const catalogPath = kind === 'routr' ? '/api/me/pricing' : kind === 'openrouter' ? '/models/user' : '/models';
    const catalog = await upstream(provider, catalogPath).catch(() => null);
    const data = catalog?.[kind === 'routr' ? 'models' : 'data'];
    const fields = ['context_length', 'context_limit', 'context_window', 'input_token_limit',
        'output_token_limit', 'max_output_tokens', 'top_provider', 'endpoints', 'architecture',
        'input_modalities', 'output_modalities', 'pricing_lines', 'pricing',
        'input_rate', 'cache_rate', 'output_rate', 'min_charge_credits', 'per_request_floor_credits'];
    const models = Array.isArray(data) ? data.flatMap(model => {
        const id = model?.id ?? model?.model_sku ?? model?.model;
        if (typeof id !== 'string' || (selectedModel && id !== selectedModel)) return [];
        return [{id, ...Object.fromEntries(fields.filter(field => Object.hasOwn(model, field))
            .map(field => [field, model[field]]))}];
    }) : undefined;
    return {kind, models};
}

async function upstream(connection, endpoint, payload) {
    return readUpstreamJson(await openUpstream(connection, endpoint, payload));
}

function naraAvailable(result) {
    const amount = data => [data?.available, data?.availableCredits, data?.available_credits]
        .find(value => typeof value === 'number' || typeof value === 'string');
    const available = amount(result.structuredContent);
    if (available !== undefined) return available;
    for (const part of Array.isArray(result.content) ? result.content : []) {
        if (part?.type !== 'text') continue;
        try {
            const value = amount(JSON.parse(part.text));
            if (value !== undefined) return value;
        } catch {
            // Only structured balance values are displayed.
        }
    }
}

function replyRequest(connection, model, messages, streaming, textOnly = false) {
    const endpoint = imageEndpoint(connection.type, connection.base, model.id, model.imageOnly
        || (model.modalities && !model.modalities.includes('text')));
    if (textOnly && (endpoint || model.imageOnly || (model.modalities && !model.modalities.includes('text')))) {
        throw problem(422, 'This model does not support text replies.');
    }
    if (endpoint) {
        const last = messages.at(-1);
        if (last?.role !== 'user' || typeof last.content !== 'string' || !last.content.trim()) {
            throw problem(400, 'Enter a prompt for the image.');
        }
        if (last.files?.length) throw problem(422, 'This image model accepts text prompts only.');
        return {endpoint, payload: {model: model.id, prompt: last.content}, generated: true};
    }
    const stream = streaming && !model.modalities;
    const payload = {model: model.id, messages: messageParts(messages, model, connection.type), stream};
    if (model.modalities) payload.modalities = textOnly ? ['text'] : model.modalities;
    if (stream && connection.type === 'openai') payload.stream_options = {include_usage: true};
    return {endpoint: '/chat/completions', payload, generated: false};
}

async function openUpstream(connection, endpoint, payload, cancellation) {
    const imageRequest = endpoint.startsWith('/images');
    const kenariImage = imageRequest && connection.base === KENARI_BASE;
    const timeout = AbortSignal.timeout(!payload ? 20_000 : kenariImage ? 300_000 : imageRequest ? 180_000 : 90_000);
    const signal = cancellation ? AbortSignal.any([cancellation, timeout]) : timeout;
    let response;
    try {
        response = await fetch(connection.base + endpoint, {
            method: payload ? 'POST' : 'GET',
            headers: {
                ...(connection.base === GEMINI_METADATA_BASE ? {'x-goog-api-key': connection.key}
                    : {Authorization: 'Bearer ' + connection.key}),
                Accept: endpoint === '/mcp' || payload?.stream ? 'text/event-stream, application/json' : 'application/json',
                ...(payload && {'Content-Type': 'application/json'})
            },
            body: payload && JSON.stringify(payload),
            redirect: 'error',
            signal
        });
    } catch (error) {
        throw problem(502, error.name === 'TimeoutError'
            ? 'The provider timed out.' : 'Could not reach the provider. Check the HTTPS base URL and your connection.');
    }
    if (!response.ok) {
        await response.body?.cancel();
        if (response.status === 401 || response.status === 403) throw problem(502, 'The provider rejected the API key or denied access (HTTP ' + response.status + ').');
        if (response.status === 402) throw problem(502, 'The provider requires a balance or credits for this request (HTTP 402).');
        if (response.status === 404 && !payload) throw problem(502, 'Model listing unavailable. Check the base URL and confirm the provider supports model listing.');
        if (response.status === 429) throw problem(502, 'Provider rate limit or quota reached (HTTP 429).');
        throw problem(502, 'The provider returned HTTP ' + response.status + '. Check the model, endpoint, and account access.');
    }
    return response;
}

async function readUpstreamJson(response) {
    try {
        const chunks = [];
        let size = 0;
        for await (const chunk of response.body) {
            size += chunk.length;
            if (size > 8_000_000) throw problem(502, 'The provider response is too large.');
            chunks.push(chunk);
        }
        return JSON.parse(Buffer.concat(chunks));
    } catch (error) {
        if (error.status) throw error;
        throw problem(502, 'The provider did not return usable JSON. Check the API base URL.');
    }
}

async function start() {
    try {
        const records = await vault.load();
        savedConnections = new Map(records.map(connection => [connection.id, connection]));
        for (const connection of records) connections.set(connection.id, connection);
    } catch {
        vaultAvailable = false;
        console.error('The Windows provider vault is unavailable.');
    }
    server.listen(port, '127.0.0.1', () => console.log('Neural Link preview at http://localhost:' + port));
}

start();
