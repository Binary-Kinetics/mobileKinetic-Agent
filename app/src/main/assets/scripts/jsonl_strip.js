#!/usr/bin/env node
/**
 * jsonl_strip.js - Structural bloat removal from JSONL conversation files.
 *
 * Usage: node jsonl_strip.js <input.jsonl> [output.tmp]
 * Exit code 0 = success, 1 = error
 * Stats printed to stderr as JSON
 */

const fs = require('fs');
const readline = require('readline');
const crypto = require('crypto');
const path = require('path');

const inputPath = process.argv[2];
const outputPath = process.argv[3] || inputPath.replace('.jsonl', '.stripped.tmp');

if (!inputPath) {
    console.error('Usage: node jsonl_strip.js <input.jsonl> [output.tmp]');
    process.exit(1);
}

// Fields to strip from any block
const STRIP_FIELDS = ['usage', 'costUSD', 'cacheCreationInputTokens', 'cacheReadInputTokens', 'sessionId'];
const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-/;

// Track content hashes for deduplication
const toolResultHashes = new Set();

let linesIn = 0;
let linesOut = 0;
let bytesIn = 0;
let bytesOut = 0;
let healthCheckBuffer = null;  // Track health check pairs

function isHealthCheckToolUse(parsed) {
    if (parsed.type === 'tool_use' || parsed.type === 'tool-use') {
        const name = parsed.name || '';
        if (name.toLowerCase().includes('health')) return true;
    }
    // Check nested content blocks
    if (Array.isArray(parsed.content)) {
        for (const block of parsed.content) {
            if (block.type === 'tool_use' && block.name && block.name.toLowerCase().includes('health')) {
                return true;
            }
        }
    }
    return false;
}

function isHealthCheckResult(parsed) {
    const content = JSON.stringify(parsed).toLowerCase();
    return content.includes('"healthy"') || content.includes('"status":"healthy"');
}

function stripFields(obj) {
    if (typeof obj !== 'object' || obj === null) return obj;
    if (Array.isArray(obj)) return obj.map(stripFields);
    const result = {};
    for (const [key, value] of Object.entries(obj)) {
        if (STRIP_FIELDS.includes(key)) continue;
        if (key === 'uuid' && typeof value === 'string' && UUID_REGEX.test(value)) continue;
        result[key] = stripFields(value);
    }
    return result;
}

function getContentHash(content) {
    const text = typeof content === 'string' ? content : JSON.stringify(content);
    return crypto.createHash('sha256').update(text).digest('hex');
}

function shouldDrop(parsed) {
    // Drop queue-operation lines
    if (parsed.type === 'queue-operation') return true;
    return false;
}

function isDuplicate(parsed) {
    // Check toolUseResult deduplication
    if (parsed.type === 'toolUseResult' || parsed.type === 'tool_result') {
        const content = parsed.content || parsed.output || '';
        const hash = getContentHash(content);
        if (toolResultHashes.has(hash)) return true;
        toolResultHashes.add(hash);
    }
    // Also track tool_result for forward-dedup
    if (parsed.type === 'tool_result') {
        const content = parsed.content || parsed.output || '';
        toolResultHashes.add(getContentHash(content));
    }
    return false;
}

async function processFile() {
    const input = fs.createReadStream(inputPath, { encoding: 'utf8' });
    const output = fs.createWriteStream(outputPath, { encoding: 'utf8' });
    const rl = readline.createInterface({ input, crlfDelay: Infinity });

    for await (const line of rl) {
        linesIn++;
        bytesIn += Buffer.byteLength(line, 'utf8') + 1; // +1 for newline

        const trimmed = line.trim();
        if (!trimmed) continue;

        let parsed;
        try {
            parsed = JSON.parse(trimmed);
        } catch (e) {
            // Keep unparseable lines as-is
            output.write(trimmed + '\n');
            linesOut++;
            bytesOut += Buffer.byteLength(trimmed, 'utf8') + 1;
            continue;
        }

        // Drop rules
        if (shouldDrop(parsed)) continue;

        // Health check pair detection
        if (isHealthCheckToolUse(parsed)) {
            healthCheckBuffer = parsed;
            continue;
        }
        if (healthCheckBuffer && isHealthCheckResult(parsed)) {
            healthCheckBuffer = null;
            continue;
        }
        if (healthCheckBuffer) {
            // Previous wasn't actually a health check pair - flush it
            const flushed = JSON.stringify(stripFields(healthCheckBuffer));
            output.write(flushed + '\n');
            linesOut++;
            bytesOut += Buffer.byteLength(flushed, 'utf8') + 1;
            healthCheckBuffer = null;
        }

        // Deduplication
        if (isDuplicate(parsed)) continue;

        // Strip fields
        const stripped = stripFields(parsed);
        const outputLine = JSON.stringify(stripped);
        output.write(outputLine + '\n');
        linesOut++;
        bytesOut += Buffer.byteLength(outputLine, 'utf8') + 1;
    }

    // Flush any remaining health check buffer
    if (healthCheckBuffer) {
        const flushed = JSON.stringify(stripFields(healthCheckBuffer));
        output.write(flushed + '\n');
        linesOut++;
        bytesOut += Buffer.byteLength(flushed, 'utf8') + 1;
    }

    output.end();

    // Wait for output to finish writing
    await new Promise((resolve, reject) => {
        output.on('finish', resolve);
        output.on('error', reject);
    });
}

async function validate() {
    const input = fs.createReadStream(outputPath, { encoding: 'utf8' });
    const rl = readline.createInterface({ input, crlfDelay: Infinity });
    let lineNum = 0;
    for await (const line of rl) {
        lineNum++;
        const trimmed = line.trim();
        if (!trimmed) continue;
        try {
            JSON.parse(trimmed);
        } catch (e) {
            throw new Error(`Validation failed: line ${lineNum} is not valid JSON`);
        }
    }
    if (lineNum === 0) {
        throw new Error('Validation failed: output file is empty');
    }
}

async function main() {
    try {
        await processFile();
        await validate();
        const ratio = bytesIn > 0 ? ((1 - bytesOut / bytesIn) * 100).toFixed(1) : 0;
        const stats = { linesIn, linesOut, bytesIn, bytesOut, compressionRatio: `${ratio}%` };
        console.error(JSON.stringify(stats));
        process.exit(0);
    } catch (e) {
        console.error(JSON.stringify({ error: e.message }));
        // Clean up output on failure
        try { fs.unlinkSync(outputPath); } catch (_) {}
        process.exit(1);
    }
}

main();
