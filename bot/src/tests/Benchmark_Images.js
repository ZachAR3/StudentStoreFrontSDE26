const path   = require('path')
const https  = require('https')
const http   = require('http')
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') })
console.log('GEMINI_API_KEY loaded:', !!process.env.GEMINI_API_KEY)
console.log('OPENAI_API_KEY loaded:', !!process.env.OPENAI_API_KEY)

const { createGeminiProvider }             = require('../services/llm/providers/geminiProvider.js')
const { createOpenAICompatibleProvider }   = require('../services/llm/providers/openaiCompatibleProvider.js')
const langfuse                             = require('../services/langfuseService.js')

const gemmaProvider   = createOpenAICompatibleProvider({ langfuse })
const chatgptProvider = createOpenAICompatibleProvider({
    langfuse,
    baseURL: 'https://api.openai.com/v1',
    apiKey:  process.env.OPENAI_API_KEY,
    model:   'gpt-4o-mini'
})
const geminiProvider  = createGeminiProvider({ langfuse })

// ─── Image fetcher ────────────────────────────────────────────────────────────
function fetchImage(url) {
    return new Promise((resolve, reject) => {
        const protocol = url.startsWith('https') ? https : http

        const request = (targetUrl) => {
            protocol.get(targetUrl, (res) => {
                if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                    return request(res.headers.location)
                }
                if (res.statusCode < 200 || res.statusCode >= 300) {
                    return reject(new Error(`HTTP ${res.statusCode} fetching ${targetUrl}`))
                }

                const chunks = []
                res.on('data', chunk => chunks.push(chunk))
                res.on('end', () => {
                    const buffer   = Buffer.concat(chunks)
                    const mimeType = res.headers['content-type'] || 'image/jpeg'
                    resolve({ data: buffer.toString('base64'), mimetype: mimeType.split(';')[0] })
                })
            }).on('error', reject)
        }

        request(url)
    })
}

// ─── Test cases ───────────────────────────────────────────────────────────────
// Each case has an image URL, expected keywords the description should mention,
// and an optional condition hint to check (e.g. "used", "good condition").
const TEST_CASES = [
    {
        id:               'image-macbook-laptop',
        imageUrl:         'https://upload.wikimedia.org/wikipedia/commons/thumb/1/1e/MacBook_with_Retina_Display.jpg/640px-MacBook_with_Retina_Display.jpg',
        expectedOutput:   'Description mentioning a laptop or MacBook',
        expectedKeywords: ['laptop', 'macbook', 'computer', 'screen', 'keyboard', 'apple']
    },
    {
        id:               'image-textbooks-shelf',
        imageUrl:         'https://upload.wikimedia.org/wikipedia/commons/thumb/f/f9/PhysicsBooks.jpg/640px-PhysicsBooks.jpg',
        expectedOutput:   'Description mentioning books or textbooks',
        expectedKeywords: ['book', 'textbook', 'shelf', 'spine', 'pages', 'reading']
    },
    {
        id:               'image-denim-jacket',
        imageUrl:         'https://upload.wikimedia.org/wikipedia/commons/thumb/b/b8/Jeans_jacket.jpg/480px-Jeans_jacket.jpg',
        expectedOutput:   'Description mentioning a jacket or clothing item',
        expectedKeywords: ['jacket', 'denim', 'jeans', 'clothing', 'coat', 'wear']
    },
    {
        id:               'image-wooden-desk',
        imageUrl:         'https://upload.wikimedia.org/wikipedia/commons/thumb/4/40/Desk_and_bookcase.jpg/640px-Desk_and_bookcase.jpg',
        expectedOutput:   'Description mentioning a desk or furniture',
        expectedKeywords: ['desk', 'table', 'furniture', 'wood', 'drawer', 'shelf']
    },
    {
        id:               'image-bicycle',
        imageUrl:         'https://upload.wikimedia.org/wikipedia/commons/thumb/4/43/Velosiped.jpg/640px-Velosiped.jpg',
        expectedOutput:   'Description mentioning a bicycle',
        expectedKeywords: ['bicycle', 'bike', 'wheel', 'frame', 'cycle', 'handlebar', 'pedal']
    },
    {
        id:               'image-headphones',
        imageUrl:         'https://upload.wikimedia.org/wikipedia/commons/thumb/a/a2/Sennheiser_HD_558_headphones.jpg/640px-Sennheiser_HD_558_headphones.jpg',
        expectedOutput:   'Description mentioning headphones or audio equipment',
        expectedKeywords: ['headphone', 'headset', 'audio', 'ear', 'music', 'sennheiser', 'sound']
    }
]

const CONDITION_WORDS = ['condition', 'used', 'new', 'good', 'worn', 'scratch', 'damage', 'mint', 'fair', 'excellent', 'clean']

// ─── Helper: truncate output for console ─────────────────────────────────────
function truncate(value, length = 200) {
    if (value === null || value === undefined) return String(value)
    const str = typeof value === 'string' ? value : JSON.stringify(value)
    return str.length > length ? str.slice(0, length) + '…' : str
}

// ─── Scoring ──────────────────────────────────────────────────────────────────
function computeAndScore(traceId, model, testCase, output) {
    const text = typeof output === 'string' ? output.toLowerCase() : ''

    const scores = {}
    scores.nonEmpty         = text.length > 0 ? 1 : 0
    scores.sufficientDetail = text.length >= 50 ? 1 : 0
    scores.mentionsItem     = testCase.expectedKeywords.some(kw => text.includes(kw)) ? 1 : 0
    scores.mentionsCondition = CONDITION_WORDS.some(w => text.includes(w)) ? 1 : 0
    scores.overall          = (scores.nonEmpty + scores.sufficientDetail + scores.mentionsItem + scores.mentionsCondition) / 4

    langfuse.score({ traceId, name: `${model}-non-empty`,          value: scores.nonEmpty,          comment: scores.nonEmpty          ? 'Has output'          : 'Empty output' })
    langfuse.score({ traceId, name: `${model}-sufficient-detail`,  value: scores.sufficientDetail,  comment: scores.sufficientDetail  ? `${text.length} chars` : `Too short: ${text.length} chars` })
    langfuse.score({ traceId, name: `${model}-mentions-item`,      value: scores.mentionsItem,      comment: scores.mentionsItem      ? 'Keyword found'       : `Expected one of: ${testCase.expectedKeywords.join(', ')}` })
    langfuse.score({ traceId, name: `${model}-mentions-condition`, value: scores.mentionsCondition, comment: scores.mentionsCondition ? 'Condition mentioned'  : 'No condition language' })
    langfuse.score({ traceId, name: `${model}-overall`,            value: scores.overall })

    return scores
}

// ─── Run a single test case ───────────────────────────────────────────────────
async function runTestCase(testCase, image) {
    console.log(`\n─── ${testCase.id} ───`)

    const benchmarkTrace = langfuse.trace({
        name:      'ImageBenchmark',
        sessionId: 'image-benchmark-run',
        metadata:  {
            testCaseId:     testCase.id,
            imageUrl:       testCase.imageUrl,
            expectedOutput: testCase.expectedOutput
        }
    })

    const tracingParams = {
        sessionId: 'image-benchmark-run',
        userId:    'benchmark-user',
        parent:    benchmarkTrace
    }

    let gemmaOutput,   gemmaLatencyMs,   gemmaError   = null
    let chatgptOutput, chatgptLatencyMs, chatgptError = null
    let geminiOutput,  geminiLatencyMs,  geminiError  = null

    // Gemma
    const gemmaStart = Date.now()
    try {
        gemmaOutput = await gemmaProvider.imageProcessing([image], tracingParams)
    } catch (err) {
        gemmaError  = err.message
        gemmaOutput = null
    }
    gemmaLatencyMs = Date.now() - gemmaStart

    // ChatGPT
    const chatgptStart = Date.now()
    try {
        chatgptOutput = await chatgptProvider.imageProcessing([image], tracingParams)
    } catch (err) {
        chatgptError  = err.message
        chatgptOutput = null
    }
    chatgptLatencyMs = Date.now() - chatgptStart

    // Gemini
    const geminiStart = Date.now()
    try {
        geminiOutput = await geminiProvider.imageProcessing([image], tracingParams)
    } catch (err) {
        geminiError  = err.message
        geminiOutput = null
    }
    geminiLatencyMs = Date.now() - geminiStart

    const gemmaScores   = computeAndScore(benchmarkTrace.id, 'gemma',   testCase, gemmaOutput)
    const chatgptScores = computeAndScore(benchmarkTrace.id, 'chatgpt', testCase, chatgptOutput)
    const geminiScores  = computeAndScore(benchmarkTrace.id, 'gemini',  testCase, geminiOutput)

    benchmarkTrace.update({
        output: {
            gemma:   { output: gemmaOutput,   latencyMs: gemmaLatencyMs },
            chatgpt: { output: chatgptOutput, latencyMs: chatgptLatencyMs },
            gemini:  { output: geminiOutput,  latencyMs: geminiLatencyMs }
        }
    })

    console.log(`  Gemma   (${gemmaLatencyMs}ms) score=${gemmaScores.overall.toFixed(2)}: ${truncate(gemmaOutput)}`)
    console.log(`  ChatGPT (${chatgptLatencyMs}ms) score=${chatgptScores.overall.toFixed(2)}: ${truncate(chatgptOutput)}`)
    console.log(`  Gemini  (${geminiLatencyMs}ms) score=${geminiScores.overall.toFixed(2)}: ${truncate(geminiOutput)}`)

    return {
        testId:  testCase.id,
        gemma:   { latencyMs: gemmaLatencyMs,   scores: gemmaScores,   error: gemmaError },
        chatgpt: { latencyMs: chatgptLatencyMs, scores: chatgptScores, error: chatgptError },
        gemini:  { latencyMs: geminiLatencyMs,  scores: geminiScores,  error: geminiError }
    }
}

// ─── Main ─────────────────────────────────────────────────────────────────────
;(async () => {
    const runStart = Date.now()
    console.log(`Fetching ${TEST_CASES.length} images and running benchmark...`)

    // Fetch all images up front so network time is not counted in model latency
    const images = await Promise.all(
        TEST_CASES.map(async (tc) => {
            try {
                const image = await fetchImage(tc.imageUrl)
                console.log(`  ✓ Fetched ${tc.id} (${image.mimetype}, ${Math.round(image.data.length * 0.75 / 1024)}KB)`)
                return image
            } catch (err) {
                console.error(`  ✗ Failed to fetch ${tc.id}: ${err.message}`)
                return null
            }
        })
    )

    const results = []
    for (let i = 0; i < TEST_CASES.length; i++) {
        if (!images[i]) {
            console.warn(`Skipping ${TEST_CASES[i].id} — image fetch failed`)
            continue
        }
        results.push(await runTestCase(TEST_CASES[i], images[i]))
    }

    const totalDurationMs = Date.now() - runStart

    const summary = {}
    for (const model of ['gemma', 'chatgpt', 'gemini']) {
        const modelResults = results.filter(r => r[model])
        const scores       = modelResults.map(r => r[model].scores.overall)
        const latencies    = modelResults.map(r => r[model].latencyMs)
        const errors       = modelResults.filter(r => r[model].error !== null).map(r => ({ testId: r.testId, error: r[model].error }))
        summary[model] = {
            avgScore:     scores.reduce((a, b) => a + b, 0) / (scores.length || 1),
            avgLatencyMs: Math.round(latencies.reduce((a, b) => a + b, 0) / (latencies.length || 1)),
            errorCount:   errors.length,
            reliability:  1 - (errors.length / (modelResults.length || 1))
        }
    }

    console.log('\n── Image Benchmark Summary ──────────────────────────────')
    console.log(`  Total time : ${(totalDurationMs / 1000).toFixed(1)}s`)
    for (const model of ['gemma', 'chatgpt', 'gemini']) {
        const s = summary[model]
        console.log(`  ${model.padEnd(8)}: avg score=${s.avgScore.toFixed(2)}  reliability=${s.reliability.toFixed(2)}  avg latency=${s.avgLatencyMs}ms  errors=${s.errorCount}`)
    }

    console.log('\n  Scores: non-empty + sufficient-detail + mentions-item + mentions-condition (each 0-1, avg = overall)')

    await langfuse.flush()
    console.log('\nBenchmark complete. Check Langfuse dashboard for full traces.')
    process.exit(0)
})()
