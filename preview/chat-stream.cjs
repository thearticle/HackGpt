const {once} = require('node:events');

const MAX_ATTACHMENT_BYTES = 6_000_000;
const MAX_TEXT_BYTES = 256_000;
const MAX_RESPONSE_BYTES = 8_000_000;
const TEXT_EXTENSIONS = /\.(txt|md|csv|tsv|json|jsonl|xml|log)$/i;
const IMAGE_TYPES = {
    'image/png': bytes => bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex')),
    'image/jpeg': bytes => bytes.subarray(0, 3).equals(Buffer.from('ffd8ff', 'hex')),
    'image/gif': bytes => /^GIF8[79]a$/.test(bytes.toString('ascii', 0, 6)),
    'image/webp': bytes => bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP'
};

function modelMetadata(model, connectionType) {
    const input = model.architecture?.input_modalities ?? model.input_modalities;
    const modalities = Array.isArray(input) ? input.filter(item => typeof item === 'string') : null;
    return {
        id: model.id,
        name: typeof model.name === 'string' ? model.name : model.id,
        input: {
            image: modalities ? modalities.includes('image') : null,
            pdf: connectionType === 'gemini' ? false : modalities
                ? modalities.includes('file') || modalities.includes('pdf')
                    || ((connectionType === 'openai' || connectionType === 'openrouter') && modalities.includes('image'))
                    || (connectionType === 'openrouter' && modalities.includes('text'))
                : null
        }
    };
}

function messageParts(messages, model, connectionType) {
    let attachmentBytes = 0;
    return messages.map(message => {
        const {role, content, files = []} = message ?? {};
        if (!['user', 'assistant'].includes(role) || typeof content !== 'string'
                || !Array.isArray(files) || files.length > 4 || (role !== 'user' && files.length)) {
            throw chatError(400, 'Invalid conversation or attachment.');
        }
        if (!files.length) return {role, content};
        const parts = [{type: 'text', text: content}];
        for (const file of files) {
            if (typeof file?.name !== 'string' || !file.name || typeof file.data !== 'string') {
                throw chatError(400, 'Invalid attachment.');
            }
            const name = file.name.replace(/[\r\n\u0000]/g, ' ').slice(0, 180);
            if (file.kind === 'text') {
                const size = Buffer.byteLength(file.data);
                if (!TEXT_EXTENSIONS.test(name) || size > MAX_TEXT_BYTES || file.data.includes('\u0000')) {
                    throw chatError(400, 'Use a supported UTF-8 text or data file under 256 KB.');
                }
                attachmentBytes += size;
                parts.push({type: 'text', text: 'Attached file: ' + name + '\n\n' + file.data});
            } else {
                if (file.data.length > 7_000_000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(file.data)) {
                    throw chatError(400, 'Invalid image or PDF attachment.');
                }
                const bytes = Buffer.from(file.data, 'base64');
                if (bytes.length > 5_000_000) throw chatError(413, 'Each image or PDF must be under 5 MB.');
                attachmentBytes += bytes.length;
                if (file.kind === 'image' && Object.hasOwn(IMAGE_TYPES, file.mime)
                        && IMAGE_TYPES[file.mime](bytes)) {
                    if (model.input?.image === false) throw chatError(422, 'This model does not accept images.');
                    parts.push({type: 'image_url', image_url: {url: 'data:' + file.mime + ';base64,' + file.data}});
                } else if (file.kind === 'pdf' && file.mime === 'application/pdf'
                        && bytes.toString('ascii', 0, 5) === '%PDF-') {
                    if (model.input?.pdf === false || connectionType === 'gemini') {
                        throw chatError(422, 'This provider and model combination does not accept PDF attachments.');
                    }
                    parts.push({type: 'file', file: {filename: name, file_data: 'data:application/pdf;base64,' + file.data}});
                } else {
                    throw chatError(400, 'Use a PNG, JPEG, GIF, WebP, or PDF file.');
                }
            }
            if (attachmentBytes > MAX_ATTACHMENT_BYTES) {
                throw chatError(413, 'Attachments exceed 6 MB for this conversation. Start a new chat or remove a file.');
            }
        }
        return {role, content: parts};
    });
}

function chatError(status, message) {
    return Object.assign(new Error(message), {status});
}

async function* sseEvents(body) {
    const decoder = new TextDecoder();
    let buffer = '';
    let bytes = 0;
    for await (const chunk of body) {
        bytes += chunk.length;
        if (bytes > MAX_RESPONSE_BYTES) throw chatError(502, 'The provider response is too large.');
        buffer += decoder.decode(chunk, {stream: true});
        let boundary;
        while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
            yield sseData(buffer.slice(0, boundary.index));
            buffer = buffer.slice(boundary.index + boundary[0].length);
        }
    }
    buffer += decoder.decode();
    if (buffer.trim()) yield sseData(buffer);
}

function sseData(event) {
    return event.split(/\r?\n/).filter(line => line.startsWith('data:'))
        .map(line => line.slice(5).trimStart()).join('\n');
}

function usageCount(value) {
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
}

async function streamReply(upstream, response, start, readJson, signal) {
    response.writeHead(200, {'Content-Type': 'application/x-ndjson; charset=utf-8'});
    const emit = async (type, data = {}) => {
        if (signal.aborted || response.destroyed) return;
        if (!response.write(JSON.stringify({type, ...data}) + '\n')) {
            await once(response, 'drain', {signal});
        }
    };
    const streaming = (upstream.headers.get('content-type') ?? '').includes('text/event-stream');
    let usage;
    let firstActivity = null;
    let firstText = null;
    let lastActivity = null;
    let thinking = false;
    let hasText = false;
    let finished = false;
    try {
        async function consume(value, content) {
            if (value?.error) throw chatError(502, 'The provider reported an error while generating.');
            if (value?.usage) usage = value.usage;
            if (!content) return;
            const text = typeof content.content === 'string' ? content.content
                : typeof content.refusal === 'string' ? content.refusal : '';
            const details = Array.isArray(content.reasoning_details) ? content.reasoning_details : [];
            const summaries = details.filter(detail => detail?.type === 'reasoning.summary'
                && typeof detail.summary === 'string').map(detail => detail.summary);
            const reasoning = Boolean(content.reasoning || content.reasoning_content || details.length);
            if (text || reasoning) {
                lastActivity = performance.now();
                firstActivity ??= lastActivity;
            }
            if (reasoning && !thinking) {
                thinking = true;
                await emit('thinking');
            }
            for (const summary of summaries) await emit('summary', {text: summary});
            if (text) {
                firstText ??= performance.now();
                hasText ||= Boolean(text.trim());
                await emit('delta', {text});
            }
        }

        if (streaming) {
            for await (const data of sseEvents(upstream.body)) {
                if (!data) continue;
                if (data.trim() === '[DONE]') {
                    finished = true;
                    break;
                }
                const value = JSON.parse(data);
                const choice = value.choices?.[0];
                await consume(value, choice?.delta);
                if (choice?.finish_reason) finished = true;
            }
            if (!finished) throw chatError(502, 'The provider stream ended before completing the reply.');
        } else {
            const value = await readJson(upstream);
            await consume(value, value.choices?.[0]?.message);
        }
        if (!hasText) throw chatError(502, 'This model did not return text. Try another text-chat model.');
        const outputTokens = usageCount(usage?.completion_tokens);
        const generationMs = lastActivity - firstActivity;
        await emit('done', {stats: {
            durationMs: Math.round(performance.now() - start),
            firstTokenMs: streaming ? Math.round(firstText - start) : null,
            inputTokens: usageCount(usage?.prompt_tokens),
            outputTokens,
            reasoningTokens: usageCount(usage?.completion_tokens_details?.reasoning_tokens),
            tokensPerSecond: streaming && outputTokens && generationMs >= 100
                ? Math.round(outputTokens * 10_000 / generationMs) / 10 : null
        }});
    } catch (error) {
        if (!signal.aborted) {
            await emit('error', {message: error.status ? error.message : 'The provider response was interrupted or unreadable.'});
        }
    } finally {
        if (!response.destroyed) response.end();
    }
}

module.exports = {messageParts, modelMetadata, streamReply};
