import http from 'node:http';

const port = Number(process.env.PORT) || 3000;
const ttlMs = Math.min(300_000, Math.max(30_000, Number(process.env.PRESENCE_TTL_MS) || 60_000));
const allowedOrigins = String(process.env.ALLOWED_ORIGINS || '*')
    .split(',')
    .map(origin => origin.trim())
    .filter(Boolean);
const clients = new Map();

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
            const { clientId } = await readJson(request);
            if (!/^[a-zA-Z0-9_-]{16,128}$/.test(String(clientId || ''))) {
                return sendJson(response, 400, { error: 'Invalid clientId' }, corsOrigin);
            }
            removeExpired();
            clients.set(clientId, Date.now() + ttlMs);
            return sendJson(response, 200, { online: clients.size, expiresIn: ttlMs }, corsOrigin);
        } catch {
            return sendJson(response, 400, { error: 'Invalid request' }, corsOrigin);
        }
    }

    return sendJson(response, 404, { error: 'Not found' }, corsOrigin);
});

const cleanupTimer = setInterval(removeExpired, ttlMs);
cleanupTimer.unref();

server.listen(port, '0.0.0.0', () => {
    console.log(`RP-Hub presence service listening on ${port}`);
});

const shutdown = () => server.close(() => process.exit(0));
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);

export { server };
