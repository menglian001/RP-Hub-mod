import http from 'node:http';

const port = Number(process.env.PORT) || 3000;
const ttlMs = Math.min(300_000, Math.max(30_000, Number(process.env.PRESENCE_TTL_MS) || 60_000));
const parseVersionId = value => /^\d{5}$/.test(String(value ?? '').trim())
    ? Number(value)
    : null;
// 二改站点的公告 ID 与上游独立维护，默认读取本二改的线上内容。
// 需要改为其他来源时设置 VERSION_SOURCE_URL；设为空字符串则关闭版本心跳提醒。
const versionSourceUrl = process.env.VERSION_SOURCE_URL === undefined
    ? 'https://rp-hub-mod.pages.dev/assets/js/built-in-content.js'
    : String(process.env.VERSION_SOURCE_URL).trim();
const versionRefreshMs = 60_000;
let latestVersionId = 0;
let nextVersionRefreshAt = 0;
let versionRefreshPromise = null;
const allowedOrigins = String(process.env.ALLOWED_ORIGINS || '*')
    .split(',')
    .map(origin => origin.trim())
    .filter(Boolean);
const clients = new Map();

const refreshLatestVersionId = () => {
    if (!versionSourceUrl) return Promise.resolve();
    if (versionRefreshPromise) return versionRefreshPromise;
    if (Date.now() < nextVersionRefreshAt) return Promise.resolve();
    nextVersionRefreshAt = Date.now() + versionRefreshMs;
    versionRefreshPromise = fetch(`${versionSourceUrl}?t=${Date.now()}`, {
        cache: 'no-store',
        signal: AbortSignal.timeout(5_000)
    })
        .then(response => {
            if (!response.ok) throw new Error(`HTTP ${response.status}`);
            return response.text();
        })
        .then(source => {
            const versionId = parseVersionId(
                source.match(/window\.RPHubLatestUpdate\s*=\s*Object\.freeze\(\s*\{\s*id\s*:\s*(\d{5})\b/)?.[1]
            );
            if (versionId === null) throw new Error('Version ID not found');
            latestVersionId = Math.max(latestVersionId, versionId);
        })
        .catch(error => console.warn('Latest version check failed:', error.message))
        .finally(() => {
            versionRefreshPromise = null;
        });
    return versionRefreshPromise;
};

const removeExpired = () => {
    const now = Date.now();
    clients.forEach((expiresAt, clientId) => {
        if (expiresAt <= now) clients.delete(clientId);
    });
};

const getCorsOrigin = (origin) => {
    if (allowedOrigins.includes('*')) return '*';
    return origin && allowedOrigins.includes(origin) ? origin : '';
};

const sendJson = (response, status, body, corsOrigin = '') => {
    response.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
        ...(corsOrigin ? { 'Access-Control-Allow-Origin': corsOrigin, Vary: 'Origin' } : {})
    });
    response.end(JSON.stringify(body));
};

const readJson = (request) => new Promise((resolve, reject) => {
    let body = '';
    request.setEncoding('utf8');
    request.on('data', chunk => {
        body += chunk;
        if (body.length > 2048) request.destroy();
    });
    request.on('end', () => {
        try {
            resolve(JSON.parse(body || '{}'));
        } catch {
            reject(new Error('Invalid JSON'));
        }
    });
    request.on('error', reject);
});

const server = http.createServer(async (request, response) => {
    const origin = request.headers.origin || '';
    const corsOrigin = getCorsOrigin(origin);
    const url = new URL(request.url || '/', 'http://localhost');

    if (request.method === 'OPTIONS') {
        if (origin && !corsOrigin) return sendJson(response, 403, { error: 'Origin not allowed' });
        response.writeHead(204, {
            'Access-Control-Allow-Origin': corsOrigin || '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type',
            'Access-Control-Max-Age': '86400',
            Vary: 'Origin'
        });
        return response.end();
    }

    if (origin && !corsOrigin) return sendJson(response, 403, { error: 'Origin not allowed' });

    if (request.method === 'GET' && url.pathname === '/health') {
        return sendJson(response, 200, { ok: true }, corsOrigin);
    }

    if (request.method === 'GET' && url.pathname === '/v1/online') {
        removeExpired();
        return sendJson(response, 200, { online: clients.size }, corsOrigin);
    }

    if (request.method === 'POST' && url.pathname === '/v1/presence') {
        try {
            const { clientId, versionId } = await readJson(request);
            if (!/^[a-zA-Z0-9_-]{16,128}$/.test(String(clientId || ''))) {
                return sendJson(response, 400, { error: 'Invalid clientId' }, corsOrigin);
            }
            const currentVersionId = parseVersionId(versionId);
            await refreshLatestVersionId();
            removeExpired();
            clients.set(clientId, Date.now() + ttlMs);
            return sendJson(response, 200, {
                online: clients.size,
                expiresIn: ttlMs,
                latestVersionId,
                updateAvailable: currentVersionId !== null && latestVersionId > currentVersionId
            }, corsOrigin);
        } catch {
            return sendJson(response, 400, { error: 'Invalid request' }, corsOrigin);
        }
    }

    return sendJson(response, 404, { error: 'Not found' }, corsOrigin);
});

const cleanupTimer = setInterval(removeExpired, ttlMs);
cleanupTimer.unref();
refreshLatestVersionId();

server.listen(port, '0.0.0.0', () => {
    console.log(`RP-Hub presence service listening on ${port}`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

export { server };
