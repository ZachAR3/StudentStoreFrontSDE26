const path = require('path');
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') })
console.log('GEMINI_API_KEY loaded:', !!process.env.GEMINI_API_KEY)
const { GeminiMessageParser, GeminiContextClassifier } = require('./LLM_Services_For_Testing/botGeminiService.js');
const { createOpenAICompatibleProvider }               = require('../services/llm/providers/openaiCompatibleProvider.js');
const langfuse = require('../services/langfuseService.js');

const chatgptProvider = createOpenAICompatibleProvider({
    langfuse,
    baseURL: "https://api.openai.com/v1",
    apiKey:  process.env.OPENAI_API_KEY,
    model:   "gpt-4o-mini"
});

const VALID_CATEGORIES = ['Electronics', 'Clothing', 'Furniture', 'Books', 'Sports', 'Food', 'Services', 'Other'];

// ─── Test cases ──────────────────────────────────────────────────────────────
const TEST_CASES = [
    {
        id: 'parser-laptop-eur',
        type: 'parser',
        input: 'Selling my MacBook Pro 14" 2021, M1 Pro, 16GB RAM, 512GB SSD. Excellent condition, barely used. 1200€ firm.',
        expectedOutput:   'JSON with title containing "MacBook", price=1200, category="Electronics"',
        expectedPrice:    1200,
        expectedCategory: 'Electronics'
    },
    {
        id: 'parser-textbook',
        type: 'parser',
        input: 'Calculus Early Transcendentals 8th edition by Stewart. Like new, no highlights. 35 euros, DM me!',
        expectedOutput:   'JSON with title containing "Calculus", price=35, category="Books"',
        expectedPrice:    35,
        expectedCategory: 'Books'
    },
    {
        id: 'parser-furniture-no-currency',
        type: 'parser',
        input: 'IKEA desk in white, 120x60cm, very sturdy, leaving campus so must go. 40.',
        expectedOutput:   'JSON with title mentioning "desk", price=40, category="Furniture"',
        expectedPrice:    40,
        expectedCategory: 'Furniture'
    },
    {
        id: 'classifier-clear-listing',
        type: 'classifier',
        input: [
            'Hey everyone',
            'Selling my iPhone 13 128GB, used 1 year, no scratches',
            'Asking 450€, DM me if interested'
        ],
        expectedOutput:         'YES — clear sale intent with price',
        expectedClassification: 'YES'
    },
    {
        id: 'classifier-question',
        type: 'classifier',
        input: [
            'Does anyone know where I can find a cheap monitor?',
            'My old one just broke and I have a deadline tomorrow'
        ],
        expectedOutput:         'NO — buying inquiry / question, not a listing',
        expectedClassification: 'NO'
    },
    {
        id: 'classifier-ambiguous-dm-price',
        type: 'classifier',
        input: [
            'Selling barely used Bose headphones',
            'Price negotiable, DM me'
        ],
        expectedOutput:         'YES — clear sale intent, price discussed via DM',
        expectedClassification: 'YES'
    }
];

// ─── Helper: truncate output for console ────────────────────────────────────
function truncate(value, length = 200) {
    if (value === null || value === undefined) return String(value);
    const str = typeof value === 'string' ? value : JSON.stringify(value);
    return str.length > length ? str.slice(0, length) + '…' : str;
}

// ─── Scoring: compute scores, emit to Langfuse, and return them ──────────────
function computeAndScore(traceId, model, testCase, output) {
    const scores = {};
    const prefix = model;

    if (testCase.type === 'parser') {
        const isObj      = output !== null && typeof output === 'object' && !Array.isArray(output);
        scores.validJson      = isObj ? 1 : 0;
        scores.hasFields      = isObj && output.title && output.price != null && output.description && output.category ? 1 : 0;
        scores.validCategory  = isObj && VALID_CATEGORIES.map(c => c.toLowerCase()).includes((output.category || '').toLowerCase()) ? 1 : 0;
        scores.correctPrice   = isObj && Number(output.price) === testCase.expectedPrice ? 1 : 0;
        scores.correctCategory = isObj && (output.category || '').toLowerCase() === (testCase.expectedCategory || '').toLowerCase() ? 1 : 0;
        scores.overall        = (scores.validJson + scores.hasFields + scores.validCategory + scores.correctPrice + scores.correctCategory) / 5;

        langfuse.score({ traceId, name: `${prefix}-valid-json`,           value: scores.validJson,       comment: scores.validJson      ? 'Parsed OK' : 'Not valid JSON object' });
        langfuse.score({ traceId, name: `${prefix}-has-required-fields`,  value: scores.hasFields,       comment: scores.hasFields      ? 'All fields present' : 'Missing field(s)' });
        langfuse.score({ traceId, name: `${prefix}-valid-category`,       value: scores.validCategory,   comment: scores.validCategory  ? output?.category : `Got: ${output?.category}` });
        langfuse.score({ traceId, name: `${prefix}-correct-price`,        value: scores.correctPrice,    comment: scores.correctPrice   ? `${output?.price}` : `Expected ${testCase.expectedPrice}, got ${output?.price}` });
        langfuse.score({ traceId, name: `${prefix}-correct-category`,     value: scores.correctCategory, comment: scores.correctCategory ? output?.category : `Expected ${testCase.expectedCategory}, got ${output?.category}` });
        langfuse.score({ traceId, name: `${prefix}-overall`,              value: scores.overall });

    } else {
        const got    = typeof output === 'string' ? output.trim().toUpperCase() : '';
        scores.correct = got === testCase.expectedClassification ? 1 : 0;
        scores.overall = scores.correct;

        langfuse.score({ traceId, name: `${prefix}-correct`, value: scores.correct, comment: `Expected ${testCase.expectedClassification}, got ${got || 'ERROR'}` });
        langfuse.score({ traceId, name: `${prefix}-overall`, value: scores.overall });
    }

    return scores;
}

// ─── Run a single test case against both models ─────────────────────────────
async function runTestCase(testCase) {
    console.log(`\n─── ${testCase.id} (${testCase.type}) ───`);

    // Parent benchmark trace; both Gemini and ChatGPT generations nest under this,
    // so they appear side-by-side in the Langfuse dashboard for easy comparison.
    const benchmarkTrace = langfuse.trace({
        name: 'Benchmark',
        sessionId: 'benchmark-run',
        metadata: {
            testCaseId:     testCase.id,
            type:           testCase.type,
            expectedOutput: testCase.expectedOutput
        }
    });

    const tracingParams = {
        sessionId: 'benchmark-run',
        userId:    'benchmark-user',
        parent:    benchmarkTrace
    };

    let geminiOutput,  geminiLatencyMs,  geminiError  = null;
    let chatgptOutput, chatgptLatencyMs, chatgptError = null;

    // Gemini
    const geminiStart = Date.now();
    try {
        geminiOutput = testCase.type === 'parser'
            ? await GeminiMessageParser(testCase.input, tracingParams)
            : await GeminiContextClassifier(testCase.input, tracingParams);
    } catch (err) {
        geminiError  = err.message;
        geminiOutput = null;
    }
    geminiLatencyMs = Date.now() - geminiStart;

    // ChatGPT (gpt-4o-mini)
    const chatgptStart = Date.now();
    try {
        chatgptOutput = testCase.type === 'parser'
            ? await chatgptProvider.parseListing(testCase.input, [], tracingParams)
            : await chatgptProvider.classifyContext(testCase.input, tracingParams);
    } catch (err) {
        chatgptError  = err.message;
        chatgptOutput = null;
    }
    chatgptLatencyMs = Date.now() - chatgptStart;

    // Compute scores and emit to Langfuse
    const geminiScores  = computeAndScore(benchmarkTrace.id, 'gemini',  testCase, geminiOutput);
    const chatgptScores = computeAndScore(benchmarkTrace.id, 'chatgpt', testCase, chatgptOutput);

    benchmarkTrace.update({
        output: {
            gemini:  { output: geminiOutput,  latencyMs: geminiLatencyMs },
            chatgpt: { output: chatgptOutput, latencyMs: chatgptLatencyMs }
        }
    });

    console.log(`  Gemini  (${geminiLatencyMs}ms) score=${geminiScores.overall.toFixed(2)}: ${truncate(geminiOutput)}`);
    console.log(`  ChatGPT (${chatgptLatencyMs}ms) score=${chatgptScores.overall.toFixed(2)}: ${truncate(chatgptOutput)}`);

    return {
        testId:  testCase.id,
        type:    testCase.type,
        gemini:  { latencyMs: geminiLatencyMs,  scores: geminiScores,  error: geminiError },
        chatgpt: { latencyMs: chatgptLatencyMs, scores: chatgptScores, error: chatgptError }
    };
}

// ─── Main ────────────────────────────────────────────────────────────────────
(async () => {
    const runStart = Date.now();
    console.log(`Running benchmark on ${TEST_CASES.length} test cases...`);

    const results = [];
    for (const testCase of TEST_CASES) {
        results.push(await runTestCase(testCase));
    }

    const totalDurationMs = Date.now() - runStart;

    // Compute per-model run summary
    const summary = {};
    for (const model of ['gemini', 'chatgpt']) {
        const scores      = results.map(r => r[model].scores.overall);
        const latencies   = results.map(r => r[model].latencyMs);
        const errors      = results.filter(r => r[model].error !== null).map(r => ({ testId: r.testId, error: r[model].error }));
        const reliability = 1 - (errors.length / results.length);
        summary[model] = {
            avgScore:     scores.reduce((a, b) => a + b, 0) / scores.length,
            avgLatencyMs: Math.round(latencies.reduce((a, b) => a + b, 0) / latencies.length),
            errorCount:   errors.length,
            reliability
        };
    }

    console.log('\n── Run Summary ──────────────────────────────────────────');
    console.log(`  Total time : ${(totalDurationMs / 1000).toFixed(1)}s`);
    for (const model of ['gemini', 'chatgpt']) {
        const s = summary[model];
        console.log(`  ${model.padEnd(8)}: avg score=${s.avgScore.toFixed(2)}  reliability=${s.reliability.toFixed(2)}  avg latency=${s.avgLatencyMs}ms  errors=${s.errorCount}`);
    }

    await langfuse.flush();
    console.log('\nBenchmark complete. Check Langfuse dashboard for full traces.');
    process.exit(0);
})();
