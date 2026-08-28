// RP-Hub runtime services: API transport, message rendering and application composables.

// --- API client ---
(function () {
    const {
        extractApiErrorMessage,
        formatApiErrorMessage,
        getApiUsagePayload
    } = window.RPHubUtils;
    const { extractNativeReasoning } = window.RPHubCardUtils;

    const throwApiError = (message) => {
        const error = new Error(message);
        error.isApiError = true;
        throw error;
    };

    const parsePayload = (rawText, status) => {
        const data = JSON.parse(rawText);
        const apiError = extractApiErrorMessage(data, status);
        if (apiError) throwApiError(apiError);
        return data;
    };

    const readFailedResponse = async (response) => {
        let detail = '';
        try {
            const rawText = await response.text();
            if (rawText) {
                try {
                    detail = parsePayload(rawText, response.status);
                } catch (error) {
                    if (error.isApiError) throw error;
                    detail = rawText;
                }
            }
        } catch (error) {
            if (error.isApiError) throw error;
        }
        throw new Error(formatApiErrorMessage(response.status, detail));
    };

    const parseSsePayload = (text, status) => {
        const data = JSON.parse(text);
        const apiError = extractApiErrorMessage(data, status);
        if (apiError) throwApiError(apiError);
        const choice = data.choices?.[0];
        if (!choice) return { data, content: '', reasoning: '' };
        const delta = choice.delta || choice.message || {};
        return {
            data,
            content: delta.content || '',
            reasoning: extractNativeReasoning(delta) || extractNativeReasoning(choice)
        };
    };

    const STREAM_RENDER_INTERVAL = 60;
    // 渲染慢于出字时的退避上限：一次 flush 花了多久，下一次就至少等这么久，
    // 但不超过这个值，否则弱机上会显得「一段一段地蹦」。
    const STREAM_RENDER_MAX_INTERVAL = 500;

    const readStreamingResponse = async (response, onDelta) => {
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let usage = null;
        let pendingContent = '';
        let pendingReasoning = '';

        // 背压。原来是 setInterval 无条件触发 + flushPromise.then 串行排队：
        // 一旦 onDelta（整条消息重渲染）超过 STREAM_RENDER_INTERVAL，
        // Promise 链只会越排越长，UI 越落后越多，直到 finally 里 await 把积压
        // 一次性放出来 —— 用户看到的「突然卡住」就是这个。
        // 改成自调度：上一帧没渲染完就不排新的（内容继续累积，下一次一起渲染，
        // 天然合并），并按实测耗时退避，让频率自动适配设备性能。
        //
        // 没用 requestAnimationFrame：页面切到后台时 rAF 完全停摆，流式内容会
        // 一直堆到回前台才渲染，对套壳 App 是更糟的表现。
        let activeFlush = null;
        let flushError = null;
        let flushTimer = null;
        let stopped = false;

        const runFlush = () => {
            if (!pendingContent && !pendingReasoning) return null;
            const delta = { content: pendingContent, reasoning: pendingReasoning };
            pendingContent = '';
            pendingReasoning = '';
            const startedAt = Date.now();
            return Promise.resolve()
                .then(() => onDelta?.(delta))
                .then(() => Date.now() - startedAt);
        };

        const scheduleFlush = (delay) => {
            if (stopped || flushTimer !== null) return;
            flushTimer = setTimeout(onFlushTick, delay);
        };

        const onFlushTick = () => {
            flushTimer = null;
            if (stopped) return;
            // 上一帧还在渲染 —— 直接跳过，不排队
            if (activeFlush) {
                scheduleFlush(STREAM_RENDER_INTERVAL);
                return;
            }
            const pending = runFlush();
            if (!pending) {
                scheduleFlush(STREAM_RENDER_INTERVAL);
                return;
            }
            activeFlush = pending.then(
                (elapsed) => {
                    activeFlush = null;
                    scheduleFlush(Math.min(
                        Math.max(STREAM_RENDER_INTERVAL, elapsed),
                        STREAM_RENDER_MAX_INTERVAL
                    ));
                },
                (error) => {
                    activeFlush = null;
                    // 保留第一个错误，语义与原来 await flushPromise 抛出一致
                    if (!flushError) flushError = error;
                    scheduleFlush(STREAM_RENDER_INTERVAL);
                }
            );
        };

        scheduleFlush(STREAM_RENDER_INTERVAL);

        try {
            while (true) {
                const { done, value } = await reader.read();
                if (done) break;
                buffer += decoder.decode(value, { stream: true });
                const lines = buffer.split('\n');
                buffer = lines.pop();
                for (const line of lines) {
                    const trimmedLine = line.trim();
                    if (!trimmedLine.startsWith('data: ')) continue;
                    const payload = trimmedLine.slice(6);
                    if (payload === '[DONE]') continue;
                    try {
                        const chunk = parseSsePayload(payload, response.status);
                        usage = getApiUsagePayload(chunk.data) || usage;
                        pendingContent += chunk.content;
                        pendingReasoning += chunk.reasoning;
                    } catch (error) {
                        if (error.isApiError) throw error;
                        if (/error/i.test(payload)) throw new Error(formatApiErrorMessage(response.status, payload));
                        console.warn('Error parsing stream chunk:', error);
                    }
                }
            }
            return { content: '', reasoning: '', usage };
        } finally {
            stopped = true;
            if (flushTimer !== null) {
                clearTimeout(flushTimer);
                flushTimer = null;
            }
            if (activeFlush) await activeFlush;
            // 收尾：把剩下的一次性渲染完。此时不再有后续 chunk，不存在积压问题。
            const finalFlush = runFlush();
            if (finalFlush) await finalFlush;
            if (flushError) throw flushError;
        }
    };

    const readNonStreamingResponse = async (response) => {
        const rawText = await response.text();
        try {
            const data = parsePayload(rawText, response.status);
            const choice = data.choices?.[0] || {};
            const message = choice.message || {};
            return {
                content: message.content || '',
                reasoning: extractNativeReasoning(message) || extractNativeReasoning(choice),
                usage: getApiUsagePayload(data)
            };
        } catch (error) {
            if (error.isApiError) throw error;
        }

        let content = '';
        let reasoning = '';
        let usage = null;
        for (const line of rawText.split('\n')) {
            const trimmedLine = line.trim();
            if (!trimmedLine.startsWith('data:')) continue;
            const payload = trimmedLine.replace(/^data:\s*/, '');
            if (payload === '[DONE]') continue;
            try {
                const chunk = parseSsePayload(payload, response.status);
                usage = getApiUsagePayload(chunk.data) || usage;
                content += chunk.content;
                reasoning += chunk.reasoning;
            } catch (error) {
                if (error.isApiError) throw error;
                if (/error/i.test(payload)) throw new Error(formatApiErrorMessage(response.status, payload));
            }
        }
        return { content, reasoning, usage };
    };

    const requestChatCompletion = async (options) => {
        const response = await fetch(options.url, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${options.apiKey}`
            },
            body: JSON.stringify({
                model: options.model,
                messages: options.messages,
                temperature: options.temperature,
                ...(options.reasoningEffort ? { reasoning_effort: options.reasoningEffort } : {}),
                stream: options.stream,
                ...(options.stream ? { stream_options: { include_usage: true } } : {})
            }),
            signal: options.signal
        });
        if (!response.ok) await readFailedResponse(response);

        const contentType = response.headers.get('content-type');
        const isStream = !!(options.stream && contentType?.includes('text/event-stream'));
        const result = isStream
            ? await readStreamingResponse(response, options.onDelta)
            : await readNonStreamingResponse(response);
        return { ...result, isStream };
    };

    window.RPHubApiClient = Object.freeze({ requestChatCompletion });
})();

// --- Message renderer ---
(function () {
    // 缓存 key 是整条正文，所以只按「条数」淘汰是不安全的：
    // 流式期间 msg.content 每 STREAM_RENDER_INTERVAL 变一次，每帧都是一个全新 key，
    // 旧上限 2000 条会让缓存稳定攥着 2000 条最长的中间快照（实测 30~50MB），
    // 在 Android WebView 里直接把渲染进程的堆压垮 —— 表现就是先卡死再闪退。
    // 改成同时受「条数」和「字符预算」约束，并且流式消息根本不写缓存。
    const MAX_CACHE_ENTRIES = 400;
    const MAX_CACHE_CHARS = 2 * 1024 * 1024; // ≈4MB UTF-16

    const createBoundedCache = () => {
        const map = new Map();
        let chars = 0;
        const weigh = (key, value) => key.length + (typeof value === 'string' ? value.length : 8);
        return {
            has: (key) => map.has(key),
            get: (key) => map.get(key),
            set(key, value) {
                if (map.has(key)) chars -= weigh(key, map.get(key));
                map.set(key, value);
                chars += weigh(key, value);
                while (map.size > 1 && (map.size > MAX_CACHE_ENTRIES || chars > MAX_CACHE_CHARS)) {
                    const oldestKey = map.keys().next().value;
                    // 刚写入的那条不淘汰，否则单条超预算时缓存会永远空转
                    if (oldestKey === key) break;
                    chars -= weigh(oldestKey, map.get(oldestKey));
                    map.delete(oldestKey);
                }
                return value;
            },
            clear() {
                map.clear();
                chars = 0;
            },
            get size() { return map.size; },
            get chars() { return chars; }
        };
    };

    const createMessageRenderer = ({ processRegex, replaceUserPlaceholder, createExecutableHtmlIframe, marked, DOMPurify }) => {
        const renderedCache = createBoundedCache();
        const frameDetectionCache = createBoundedCache();

        const cacheValue = (cache, key, value) => (key === null ? value : cache.set(key, value));

        const clearCaches = () => {
            renderedCache.clear();
            frameDetectionCache.clear();
        };

        const applyDisplayRegex = (text, role, skipRegex) => {
            const replaced = replaceUserPlaceholder(text);
            return skipRegex ? replaced : processRegex(replaced, { isDisplay: true, role });
        };

        const contentUsesHtmlFrame = (text, role = 'assistant', skipRegex = false, { cache = true } = {}) => {
            if (!text) return false;
            const cacheKey = cache ? `${role}_${skipRegex}_${text}` : null;
            if (cacheKey !== null && frameDetectionCache.has(cacheKey)) return frameDetectionCache.get(cacheKey);

            const trimmed = applyDisplayRegex(text, role, skipRegex).trim();
            let usesFrame = false;
            const codeFencePattern = /```([^\n`]*)\n?([\s\S]*?)```/g;
            let codeMatch;
            while ((codeMatch = codeFencePattern.exec(trimmed)) !== null) {
                const language = codeMatch[1] || '';
                const content = codeMatch[2] || '';
                if (/\b(html|xml)\b/i.test(language)
                    || /^\s*<(!doctype|html|head|body|div|span|style|script|table|img)/i.test(content)) {
                    usesFrame = true;
                    break;
                }
            }
            if (!usesFrame && !trimmed.includes('```')) {
                usesFrame = /(<!doctype html>|<html\b[^>]*>|^\s*<(style|script)\b)/i.test(trimmed);
            }
            return cacheValue(frameDetectionCache, cacheKey, usesFrame);
        };

        const cleanConfig = {
            ADD_TAGS: ['details', 'summary', 'iframe', 'svg', 'path', 'g', 'circle', 'rect', 'defs', 'linearGradient', 'stop', 'style', 'div', 'span', 'script', 'button', 'input'],
            ADD_ATTR: ['style', 'open', 'srcdoc', 'sandbox', 'frameborder', 'allow', 'allowfullscreen', 'class', 'id', 'viewBox', 'fill', 'stroke', 'stroke-width', 'd', 'stroke-linecap', 'stroke-linejoin', 'x1', 'y1', 'x2', 'y2', 'offset', 'stop-color', 'stop-opacity', 'width', 'height', 'onclick', 'type', 'value', 'checked', 'data-slash', 'data-imagegen-prompt', 'data-imagegen-state', 'data-image-request', 'data-imagegen-mode', 'aria-live', 'aria-label', 'aria-hidden', 'title'],
            FORBID_ATTR: ['onmouseover', 'onload'],
            FORCE_BODY: true
        };

        const sanitizeMarkdown = (text) => DOMPurify.sanitize(marked.parse(text), cleanConfig);
        const createIframe = (html) => createExecutableHtmlIframe(html, 'border-t border-gray-200 shadow-sm');

        const replaceHtmlCodeBlocks = (documentNode) => {
            let modified = false;
            documentNode.querySelectorAll('pre code').forEach(block => {
                const rawHtml = block.textContent;
                const hasHtmlLanguage = block.classList.contains('language-html') || block.classList.contains('language-xml');
                const looksLikeHtml = /^\s*<(!doctype|html|head|body|div|span|style|script|table|img)/i.test(rawHtml);
                if (!hasHtmlLanguage && !looksLikeHtml) return;
                const pre = block.parentElement;
                if (!pre?.parentNode) return;
                pre.parentNode.replaceChild(createIframe(rawHtml), pre);
                modified = true;
            });
            return modified;
        };

        const replaceEscapedHtmlParagraphs = (documentNode) => {
            let modified = false;
            documentNode.querySelectorAll('p').forEach(paragraph => {
                if (!/^\s*</.test(paragraph.innerHTML)) return;
                const rawHtml = paragraph.textContent;
                if (!/^\s*<(!doctype|html|head|body|div|span|style|script|table|img)/i.test(rawHtml)) return;
                if (!paragraph.parentNode) return;
                paragraph.parentNode.replaceChild(createIframe(rawHtml), paragraph);
                modified = true;
            });
            return modified;
        };

        const replaceScriptedPanels = (documentNode) => {
            let modified = false;
            documentNode.querySelectorAll('div[style*="position"], div[style*="background"], div[class*="panel"]').forEach(panel => {
                if (!panel.querySelector('script') || !panel.parentNode) return;
                panel.parentNode.replaceChild(createIframe(panel.outerHTML), panel);
                modified = true;
            });
            return modified;
        };

        const renderMarkdown = (text, role = 'assistant', skipRegex = false, { cache = true } = {}) => {
            if (!text) return '';
            const cacheKey = cache ? `${role}_${skipRegex}_${text}` : null;
            if (cacheKey !== null && renderedCache.has(cacheKey)) return renderedCache.get(cacheKey);

            let processed = applyDisplayRegex(text, role, skipRegex);
            const trimmed = processed.trim();
            const htmlMatch = trimmed.match(/(<!doctype html>|<html\b[^>]*>)/i);

            if (htmlMatch && !trimmed.includes('```')) {
                const startIndex = htmlMatch.index;
                const closeTag = '</html>';
                const closeIndex = trimmed.toLowerCase().lastIndexOf(closeTag);
                const hasCloseTag = closeIndex !== -1 && closeIndex > startIndex;
                const endIndex = hasCloseTag ? closeIndex + closeTag.length : trimmed.length;
                const htmlContent = trimmed.substring(startIndex, endIndex);
                const preText = trimmed.substring(0, startIndex);
                const postText = hasCloseTag ? trimmed.substring(endIndex) : '';
                const container = document.createElement('div');
                container.className = 'html-card-container';
                container.style.margin = '0';
                container.style.paddingBottom = '0';
                container.style.marginBottom = '-1px';
                container.appendChild(createIframe(htmlContent));
                const result = [
                    preText.trim() ? sanitizeMarkdown(preText) : '',
                    container.outerHTML,
                    postText.trim() ? sanitizeMarkdown(postText) : ''
                ].join('');
                return cacheValue(renderedCache, cacheKey, result);
            }

            if (/^\s*<(div|table|section|article|aside|header|footer|style|script)/i.test(trimmed)
                && !trimmed.includes('```')) {
                return cacheValue(renderedCache, cacheKey, DOMPurify.sanitize(processed, cleanConfig));
            }

            const lowerTrimmed = trimmed.toLowerCase();
            if (lowerTrimmed.includes('<html') || lowerTrimmed.includes('<!doctype')) {
                processed = processed
                    .replace(/<!DOCTYPE html>/gi, '')
                    .replace(/<\/?html[^>]*>/gi, '')
                    .replace(/<\/?head[^>]*>/gi, '')
                    .replace(/<\/?body[^>]*>/gi, '');
            }

            const html = sanitizeMarkdown(processed);
            try {
                const documentNode = new DOMParser().parseFromString(html, 'text/html');
                const codeBlocksChanged = replaceHtmlCodeBlocks(documentNode);
                const paragraphsChanged = replaceEscapedHtmlParagraphs(documentNode);
                const panelsChanged = replaceScriptedPanels(documentNode);
                const modified = codeBlocksChanged || paragraphsChanged || panelsChanged;
                if (modified) return cacheValue(renderedCache, cacheKey, documentNode.body.innerHTML);
            } catch (error) {
                console.error('Error rendering HTML preview:', error);
            }
            return cacheValue(renderedCache, cacheKey, html);
        };

        return { clearCaches, contentUsesHtmlFrame, renderMarkdown };
    };

    window.RPHubMessageRenderer = Object.freeze({ createMessageRenderer });
})();

// --- Application composables ---
(function () {
    const { computed, reactive, ref, watch } = Vue;

    const useTokenUsage = ({
        pageSize = 10,
        cloneForStorage,
        confirm,
        ensureStorage,
        generateUUID,
        getApiKey,
        getApiUrl,
        normalizeApiUsage,
        saveStoredValue,
        toast
    }) => {
        const tokenUsageHistory = ref([]);
        const tokenUsagePage = ref(1);
        const tokenUsageFilter = ref('all');
        const tokenUsageTimeFilter = ref('all');
        const showTokenUsageTimeFilter = ref(false);
        const tokenUsageTimeFilterOptions = Object.freeze([
            { value: 'all', label: '全部' },
            { value: '24h', label: '24小时' },
            { value: '7d', label: '7天' },
            { value: '30d', label: '30天' }
        ]);
        const tokenUsageTimeRanges = Object.freeze({
            '24h': 24 * 60 * 60 * 1000,
            '7d': 7 * 24 * 60 * 60 * 1000,
            '30d': 30 * 24 * 60 * 60 * 1000
        });
        const tokenUsageTimeFilterLabel = computed(() => (
            tokenUsageTimeFilterOptions.find(option => option.value === tokenUsageTimeFilter.value)?.label || '全部'
        ));
        const getTokenUsageCategory = (type) => {
            if (['summary', 'embedding'].includes(type)) return 'memory';
            if (type === 'ui_template') return 'variables';
            return 'chat';
        };
        const filteredTokenUsageHistory = computed(() => {
            const timeRange = tokenUsageTimeRanges[tokenUsageTimeFilter.value];
            const cutoff = timeRange ? Date.now() - timeRange : 0;
            return tokenUsageHistory.value.filter(record => {
                const matchesType = tokenUsageFilter.value === 'all'
                    || getTokenUsageCategory(record.type) === tokenUsageFilter.value;
                if (!matchesType || !timeRange) return matchesType;
                const timestamp = Number(record.timestamp);
                return Number.isFinite(timestamp) && timestamp >= cutoff;
            });
        });
        const getUncachedInputTokens = (record) => {
            if (!Number.isFinite(record?.inputTokens)) return null;
            const cached = Number.isFinite(record.cacheReadTokens) ? record.cacheReadTokens : 0;
            return Math.max(0, record.inputTokens - cached);
        };
        const tokenUsageStats = computed(() => filteredTokenUsageHistory.value.reduce((stats, record) => {
            const inputTokens = getUncachedInputTokens(record);
            if (inputTokens !== null) {
                stats.inputTokens += inputTokens;
                stats.inputTokensReports++;
            }
            ['outputTokens', 'cacheReadTokens'].forEach(key => {
                if (!Number.isFinite(record[key])) return;
                stats[key] += record[key];
                stats[`${key}Reports`]++;
            });
            return stats;
        }, {
            inputTokens: 0,
            inputTokensReports: 0,
            outputTokens: 0,
            outputTokensReports: 0,
            cacheReadTokens: 0,
            cacheReadTokensReports: 0
        }));
        const tokenUsagePageCount = computed(() => Math.max(
            1,
            Math.ceil(filteredTokenUsageHistory.value.length / pageSize)
        ));
        const displayedTokenUsageHistory = computed(() => {
            const start = (tokenUsagePage.value - 1) * pageSize;
            return filteredTokenUsageHistory.value.slice(start, start + pageSize);
        });
        const latestMainTokenUsage = computed(() => tokenUsageHistory.value.find(
            record => record.type === 'chat' || record.type === 'tool_continuation'
        ) || null);
        const formatLatestTokenCount = value => `${(Number(value || 0) / 1000).toFixed(2)}k`;
        const formatLatestUsageCost = quota => Number.isFinite(quota)
            ? `¥${(Math.trunc(quota / 500000 * 10000) / 10000).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 4 })}`
            : '--';

        let saveQueue = Promise.resolve();
        const saveTokenUsageHistoryNow = () => {
            const snapshot = cloneForStorage(tokenUsageHistory.value);
            const saveTask = async () => {
                await ensureStorage();
                await saveStoredValue('token_usage_history', snapshot, { clone: false });
            };
            saveQueue = saveQueue.then(saveTask, saveTask);
            return saveQueue;
        };
        const fetchLatestQuota = async (record, apiKey) => {
            try {
                const getLogKey = log => String(log?.request_id || [log?.created_at, log?.model_name, log?.prompt_tokens, log?.completion_tokens].join('|'));
                for (const delay of [500, 5000]) {
                    await new Promise(resolve => setTimeout(resolve, delay));
                    const apiRoot = record.apiUrl.replace(/\/+$/, '').replace(/\/v1$/i, '');
                    const response = await fetch(`${apiRoot}/api/log/token`, {
                        headers: { Authorization: `Bearer ${apiKey}` }
                    });
                    if (!response.ok) throw new Error(`HTTP ${response.status}`);
                    const payload = await response.json();
                    const logs = Array.isArray(payload?.data) ? payload.data : (payload?.data?.items || []);
                    const claimedLogs = new Set(tokenUsageHistory.value.map(item => item.usageLogKey).filter(Boolean));
                    const log = logs.filter(item => !claimedLogs.has(getLogKey(item))
                        && Number(item?.type) === 2
                        && String(item?.model_name || '') === record.model
                        && Math.abs(Number(item?.created_at) * 1000 - record.timestamp) < 120000
                        && (!Number.isFinite(record.inputTokens) || Number(item?.prompt_tokens) === record.inputTokens)
                        && (!Number.isFinite(record.outputTokens) || Number(item?.completion_tokens) === record.outputTokens))
                        .sort((a, b) => Math.abs(Number(a.created_at) * 1000 - record.timestamp) - Math.abs(Number(b.created_at) * 1000 - record.timestamp))[0];
                    if (!log || !Number.isFinite(Number(log.quota))) continue;
                    record.actualQuota = Number(log.quota);
                    record.usageGroup = String(log.group || '');
                    record.usageLogKey = getLogKey(log);
                    saveTokenUsageHistoryNow().catch(error => console.error('Token usage history save failed:', error));
                    return;
                }
            } catch (error) {
                console.warn('New API usage log fetch failed:', error);
            }
        };
        const recordApiUsage = (usage, meta = {}) => {
            const record = reactive({
                id: generateUUID(),
                timestamp: Date.now(),
                type: meta.type || 'chat',
                model: String(meta.model || ''),
                apiUrl: String(getApiUrl?.() || ''),
                durationMs: Number.isFinite(meta.durationMs) ? Math.max(0, meta.durationMs) : null,
                outputCharacters: Number.isFinite(meta.outputCharacters) ? Math.max(0, meta.outputCharacters) : null,
                ...normalizeApiUsage(usage)
            });
            tokenUsageHistory.value.unshift(record);
            const apiKey = String(getApiKey?.() || '').trim();
            if (record.apiUrl && apiKey) fetchLatestQuota(record, apiKey);
            saveTokenUsageHistoryNow().catch(error => console.error('Token usage history save failed:', error));
        };
        const clearTokenUsageHistory = () => {
            confirm('确定要清空全部 Token 用量记录吗？此操作无法撤销。', async () => {
                tokenUsageHistory.value = [];
                tokenUsagePage.value = 1;
                await saveTokenUsageHistoryNow();
                toast('Token 用量记录已清空', 'success');
            });
        };

        watch([tokenUsageFilter, tokenUsageTimeFilter], () => { tokenUsagePage.value = 1; });
        watch(tokenUsagePageCount, count => { tokenUsagePage.value = Math.min(tokenUsagePage.value, count); });

        return {
            clearTokenUsageHistory,
            displayedTokenUsageHistory,
            filteredTokenUsageHistory,
            formatLatestTokenCount,
            formatLatestUsageCost,
            formatTokenAggregate: (value, reports) => {
                if (reports <= 0 || value <= 0) return '0';
                if (value >= 100000000) return `${Number((value / 100000000).toFixed(2))}亿`;
                if (value >= 10000) return `${Number((value / 10000).toFixed(2))}万`;
                return value.toLocaleString();
            },
            formatTokenCount: (value) => Number.isFinite(value) ? value.toLocaleString() : '0',
            formatTokenUsageTime: (timestamp) => new Date(timestamp).toLocaleString('zh-CN', { hour12: false }),
            getTokenUsageTypeLabel: (type) => ({ chat: '主对话', memory: '记忆系统', variables: '变量分析' })[getTokenUsageCategory(type)],
            getUncachedInputTokens,
            latestMainTokenUsage,
            recordApiUsage,
            saveTokenUsageHistoryNow,
            showTokenUsageTimeFilter,
            tokenUsageFilter,
            tokenUsageHistory,
            tokenUsagePage,
            tokenUsagePageCount,
            tokenUsageStats,
            tokenUsageTimeFilter,
            tokenUsageTimeFilterLabel,
            tokenUsageTimeFilterOptions
        };
    };

    const useStorageManagement = ({
        characters,
        confirm,
        deleteStorageKeys,
        ensureStorage,
        getBranchOwnerId,
        getLegacyDb,
        getMainDb,
        getStorageLogicalKey,
        globalUiTemplates,
        memorySettings,
        pruneChatImages,
        readStorageKeys,
        saveMemorySettings,
        saveStoredValue,
        scanStorageEntries,
        scopedStorageNames,
        toast
    }) => {
        const categories = Object.freeze([
            { key: 'characters', label: '角色卡', color: '#2563eb' },
            { key: 'chat', label: '聊天记录', color: '#3b82f6' },
            { key: 'vector', label: '向量记忆', color: '#0ea5e9' },
            { key: 'classic', label: '总结记忆', color: '#38bdf8' },
            { key: 'other', label: '其他', color: '#94a3b8' }
        ]);
        const storageStats = reactive({
            loading: false,
            cleaning: false,
            hasMeasured: false,
            error: '',
            usage: 0,
            quota: 0,
            orphanedBytes: 0,
            orphanedItems: 0,
            categories: []
        });
        let unusedSnapshot = { mainKeys: [], legacyKeys: [], emptyTurnKeys: [], templateRuntimeKeys: [] };

        const formatStorageSize = (bytes) => {
            const size = Math.max(0, Number(bytes) || 0);
            if (size < 1024) return `${Math.round(size)} B`;
            const units = ['KB', 'MB', 'GB'];
            let value = size / 1024;
            let unit = units[0];
            for (let index = 1; index < units.length && value >= 1024; index++) {
                value /= 1024;
                unit = units[index];
            }
            return `${value >= 10 ? value.toFixed(1) : value.toFixed(2)} ${unit}`;
        };
        const getScopedStorageInfo = (logicalKey) => {
            for (const name of scopedStorageNames) {
                const prefix = `${name}_`;
                if (logicalKey.startsWith(prefix)) return { name, id: logicalKey.slice(prefix.length) };
            }
            return null;
        };
        const getStorageCategory = (logicalKey) => {
            if (logicalKey === 'characters') return 'characters';
            if (logicalKey.startsWith('chat_')) return 'chat';
            // 外置的聊天图片也算聊天记录的一部分，否则用户会在「其他」里
            // 看到一大坨说不清来源的占用。
            if (logicalKey.startsWith('chatimg_')) return 'chat';
            if (logicalKey.startsWith('memories_')) return 'vector';
            if (logicalKey.startsWith('classic_memories_')) return 'classic';
            return 'other';
        };
        const estimateStorageValueSize = (value, seen = new WeakSet()) => {
            if (value == null) return 0;
            if (typeof value === 'string') return value.length * 2;
            if (typeof value === 'number' || typeof value === 'bigint') return 8;
            if (typeof value === 'boolean') return 4;
            if (typeof value !== 'object') return 0;
            if (value instanceof Blob) return value.size;
            if (value instanceof ArrayBuffer) return value.byteLength;
            if (ArrayBuffer.isView(value)) return value.byteLength;
            if (seen.has(value)) return 0;
            seen.add(value);

            let bytes = 0;
            if (Array.isArray(value)) {
                if (value.length && typeof value[0] === 'number') return value.length * 8;
                value.forEach(item => { bytes += estimateStorageValueSize(item, seen); });
            } else {
                Object.keys(value).forEach(key => {
                    bytes += key.length * 2 + estimateStorageValueSize(value[key], seen);
                });
            }
            return bytes;
        };
        const estimateStorageEntrySize = (key, value) => String(key).length * 2 + estimateStorageValueSize(value);

        const refreshStorageStats = async () => {
            if (storageStats.loading) return;
            storageStats.loading = true;
            storageStats.error = '';
            try {
                await ensureStorage();
                const [mainKeys, legacyKeys, estimate] = await Promise.all([
                    readStorageKeys(getMainDb()),
                    readStorageKeys(getLegacyDb()),
                    navigator.storage?.estimate?.().catch(() => ({})) || Promise.resolve({})
                ]);
                const mainLogicalKeys = new Set(mainKeys.map(getStorageLogicalKey));
                const scopedLogicalKeys = new Set([...mainKeys, ...legacyKeys]
                    .map(getStorageLogicalKey)
                    .filter(logicalKey => getScopedStorageInfo(logicalKey)));
                const liveCharacterIds = new Set(characters.value.map(character => character?.uuid).filter(Boolean));
                const isOrphanedEntry = (source, logicalKey) => {
                    if (source === 'legacy' && mainLogicalKeys.has(logicalKey)) return true;
                    const scoped = getScopedStorageInfo(logicalKey);
                    if (!scoped || liveCharacterIds.has(getBranchOwnerId(scoped.id))) return false;
                    if (scoped.name !== 'chat' || !/^\d+$/.test(scoped.id)) return true;
                    const character = characters.value[Number(scoped.id)];
                    return !character || (character.uuid && scopedLogicalKeys.has(`chat_${character.uuid}`));
                };

                const categoryBytes = new Map(categories.map(category => [category.key, 0]));
                const orphanedKeys = { main: [], legacy: [] };
                let orphanedEntryBytes = 0;
                const inspectEntry = (source, key, value) => {
                    const logicalKey = getStorageLogicalKey(key);
                    const bytes = estimateStorageEntrySize(key, value);
                    const category = getStorageCategory(logicalKey);
                    categoryBytes.set(category, categoryBytes.get(category) + bytes);
                    if (isOrphanedEntry(source, logicalKey)) {
                        orphanedKeys[source].push(key);
                        orphanedEntryBytes += bytes;
                    }
                };
                await scanStorageEntries(getMainDb(), 'main', inspectEntry);
                await scanStorageEntries(getLegacyDb(), 'legacy', inspectEntry);

                const emptyTurnKeys = Object.keys(memorySettings.emptyTurns || {})
                    .filter(key => key.endsWith(':vector') && !liveCharacterIds.has(getBranchOwnerId(key.slice(0, -7))));
                const templateRuntimeKeys = [];
                globalUiTemplates.value.forEach((template, templateIndex) => {
                    Object.keys(template.runtimeByCharacter || {}).forEach(characterId => {
                        if (!liveCharacterIds.has(getBranchOwnerId(characterId))) {
                            templateRuntimeKeys.push({ templateIndex, characterId });
                        }
                    });
                });
                const embeddedOrphanBytes = emptyTurnKeys.reduce((total, key) => (
                    total + estimateStorageEntrySize(key, memorySettings.emptyTurns[key])
                ), 0) + templateRuntimeKeys.reduce((total, item) => (
                    total + estimateStorageEntrySize(
                        item.characterId,
                        globalUiTemplates.value[item.templateIndex]?.runtimeByCharacter?.[item.characterId]
                    )
                ), 0);

                try {
                    for (let index = 0; index < localStorage.length; index++) {
                        const key = localStorage.key(index);
                        const bytes = estimateStorageEntrySize(key || '', localStorage.getItem(key) || '');
                        const category = getStorageCategory(key || '');
                        categoryBytes.set(category, categoryBytes.get(category) + bytes);
                    }
                } catch (_) { }

                const accountedBytes = [...categoryBytes.values()].reduce((total, bytes) => total + bytes, 0);
                const measuredUsage = Number(estimate.usage) || accountedBytes;
                const sizeScale = accountedBytes > 0 ? measuredUsage / accountedBytes : 1;
                storageStats.usage = measuredUsage;
                storageStats.quota = Number(estimate.quota) || 0;
                storageStats.orphanedBytes = (orphanedEntryBytes + embeddedOrphanBytes) * sizeScale;
                storageStats.orphanedItems = orphanedKeys.main.length + orphanedKeys.legacy.length
                    + emptyTurnKeys.length + templateRuntimeKeys.length;
                storageStats.categories = categories
                    .map(category => ({ ...category, bytes: (categoryBytes.get(category.key) || 0) * sizeScale }))
                    .filter(category => category.bytes > 0);
                unusedSnapshot = {
                    mainKeys: orphanedKeys.main,
                    legacyKeys: orphanedKeys.legacy,
                    emptyTurnKeys,
                    templateRuntimeKeys
                };
                storageStats.hasMeasured = true;
            } catch (error) {
                console.error('Failed to inspect storage:', error);
                storageStats.error = '读取存储信息失败，请稍后重试';
                storageStats.orphanedBytes = 0;
                storageStats.orphanedItems = 0;
                storageStats.categories = [];
                unusedSnapshot = { mainKeys: [], legacyKeys: [], emptyTurnKeys: [], templateRuntimeKeys: [] };
            } finally {
                storageStats.loading = false;
            }
        };

        const cleanupUnusedStorage = async () => {
            await refreshStorageStats();
            if (storageStats.error) return;
            if (storageStats.orphanedItems === 0) {
                toast('没有发现无用残留', 'info');
                return;
            }
            const snapshot = {
                mainKeys: [...unusedSnapshot.mainKeys],
                legacyKeys: [...unusedSnapshot.legacyKeys],
                emptyTurnKeys: [...unusedSnapshot.emptyTurnKeys],
                templateRuntimeKeys: unusedSnapshot.templateRuntimeKeys.map(item => ({ ...item }))
            };
            const orphanedBytes = storageStats.orphanedBytes;
            const orphanedItems = storageStats.orphanedItems;
            confirm(
                `将清理 ${orphanedItems} 项无用残留（约 ${formatStorageSize(orphanedBytes)}）。现有角色的数据不会受到影响。`,
                async () => {
                    storageStats.cleaning = true;
                    try {
                        await Promise.all([
                            deleteStorageKeys(getMainDb(), snapshot.mainKeys),
                            deleteStorageKeys(getLegacyDb(), snapshot.legacyKeys)
                        ]);
                        snapshot.emptyTurnKeys.forEach(key => delete memorySettings.emptyTurns?.[key]);
                        snapshot.templateRuntimeKeys.forEach(({ templateIndex, characterId }) => {
                            const runtime = globalUiTemplates.value[templateIndex]?.runtimeByCharacter;
                            if (runtime) delete runtime[characterId];
                        });
                        await Promise.all([
                            saveMemorySettings(),
                            saveStoredValue('global_ui_templates', globalUiTemplates.value)
                        ]);
                        // chatimg_ 键不在 scopedStorageNames 里（故意的：图片按内容
                        // 哈希跨会话共享，不能按 scope 判定孤儿），所以通用清理路径
                        // 不会碰它，必须显式扫一遍引用回收。
                        await pruneChatImages?.().catch(error => console.warn('Prune chat images failed:', error));
                        await refreshStorageStats();
                        toast(`已清理 ${orphanedItems} 项无用残留，约 ${formatStorageSize(orphanedBytes)}`, 'success');
                    } catch (error) {
                        console.error('Failed to clean unused storage:', error);
                        toast('清理失败，请稍后重试', 'error');
                    } finally {
                        storageStats.cleaning = false;
                    }
                }
            );
        };

        return { cleanupUnusedStorage, formatStorageSize, refreshStorageStats, storageStats };
    };

    // --- Connection Test ---
    const CONNECTION_TEST_PROMPT = '1girl, simple background, upper body';
    const CONNECTION_TEST_PROMPT_OPENAI = 'a red panda sitting on a tree branch, digital art';
    const CONNECTION_TEST_TIMEOUT = 60000;

    const describeTestError = (error) => {
        const raw = String((error && error.message) || error || '未知错误').trim();
        if (/abort/i.test(raw)) return `请求超时（${Math.round(CONNECTION_TEST_TIMEOUT / 1000)}s 无响应）`;
        if (/Failed to fetch|NetworkError|Load failed|ERR_/i.test(raw)) {
            return `${raw} —— 地址写错、服务不可达或被跨域拦截`;
        }
        return raw;
    };

    const withTestTimeout = async (task) => {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), CONNECTION_TEST_TIMEOUT);
        try {
            return await task(controller.signal);
        } finally {
            clearTimeout(timer);
        }
    };

    const IMAGE_GEN_ERROR_CONTENT_TYPES = /svg|html|json|text\/plain/i;

    const extractSvgMessage = (svgText) => {
        const parts = [];
        const re = /<(?:text|tspan|title)\b[^>]*>([\s\S]*?)<\/(?:text|tspan|title)>/gi;
        let match;
        while ((match = re.exec(svgText)) !== null) {
            const text = match[1].replace(/<[^>]*>/g, '').trim();
            if (text && !parts.includes(text)) parts.push(text);
        }
        return parts.join(' / ').slice(0, 200);
    };

    const probeImageGenSrc = async (src) => {
        let response;
        try {
            response = await withTestTimeout(signal => fetch(src, { signal }));
        } catch (error) {
            const size = await loadImageForTest(src);
            return { width: size.width, height: size.height, weak: true };
        }
        const contentType = (response.headers.get('content-type') || '').toLowerCase();
        if (!response.ok) {
            const body = await response.text().catch(() => '');
            const detail = /svg/i.test(contentType) ? extractSvgMessage(body) : body.slice(0, 200);
            throw new Error(`HTTP ${response.status}${detail ? ` · ${detail}` : ''}`);
        }
        if (IMAGE_GEN_ERROR_CONTENT_TYPES.test(contentType)) {
            const body = await response.text().catch(() => '');
            const detail = /svg/i.test(contentType) ? extractSvgMessage(body) : body.replace(/\s+/g, ' ').slice(0, 200);
            throw new Error(
                `服务返回的不是真实图片（${contentType || '未知类型'}）${detail ? `：${detail}` : ''}`
            );
        }
        if (!/^image\//.test(contentType)) {
            throw new Error(`响应类型异常：${contentType || '未知'}`);
        }
        const blob = await response.blob();
        if (!blob.size) throw new Error('服务返回了空响应');
        const objectUrl = URL.createObjectURL(blob);
        try {
            const size = await loadImageForTest(objectUrl);
            return { width: size.width, height: size.height, weak: false, bytes: blob.size };
        } finally {
            URL.revokeObjectURL(objectUrl);
        }
    };

    const loadImageForTest = (src) => new Promise((resolve, reject) => {
        const img = new Image();
        let settled = false;
        const finish = (fn, arg) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            fn(arg);
        };
        const timer = setTimeout(() => {
            img.src = '';
            finish(reject, new Error(`超时（${Math.round(CONNECTION_TEST_TIMEOUT / 1000)}s 内没返回图片）`));
        }, CONNECTION_TEST_TIMEOUT);
        img.onload = () => {
            if (!img.naturalWidth) {
                finish(reject, new Error('返回的内容不是有效图片（多半是错误信息或 HTML 页面）'));
                return;
            }
            finish(resolve, { width: img.naturalWidth, height: img.naturalHeight });
        };
        img.onerror = () => finish(reject, new Error('图片加载失败：地址不可达、密钥无效或返回了非图片内容'));
        img.src = src;
    });

    window.RPHubConnectionTest = Object.freeze({
        CONNECTION_TEST_PROMPT,
        CONNECTION_TEST_PROMPT_OPENAI,
        CONNECTION_TEST_TIMEOUT,
        describeTestError,
        withTestTimeout,
        probeImageGenSrc,
        loadImageForTest
    });

    window.RPHubComposables = Object.freeze({ useStorageManagement, useTokenUsage });
})();
