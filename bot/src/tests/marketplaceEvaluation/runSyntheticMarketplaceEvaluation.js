const fs = require("fs")
const path = require("path")
const crypto = require("crypto")

require("dotenv").config({ path: path.resolve(__dirname, "../../../.env") })

const langfuse = require("../../services/langfuseService")
const ListingSubmissionService = require("../../services/ListingSubmissionService")
const { createGeminiProvider } = require("../../services/llm/providers/geminiProvider")
const { createOpenAICompatibleProvider } = require("../../services/llm/providers/openaiCompatibleProvider")
const { DATASET_VERSION, TEST_CASES } = require("./syntheticMarketplaceDataset")
const { ensureSyntheticFixtures, FIXTURE_DIR } = require("./generateSyntheticFixtures")

const REPORT_ROOT = path.resolve(__dirname, "../../../reports/marketplace-evaluation")

function normalizeWhitespace(value) {
    return String(value || "").replace(/\s+/g, " ").trim()
}

function normalizeText(value) {
    return normalizeWhitespace(value).toLowerCase()
}

function normalizeCategory(value) {
    return String(value || "")
        .trim()
        .replace(/[\s-]+/g, "_")
        .toUpperCase()
}

function normalizePrice(value) {
    if (value === null || value === undefined || value === "") return null
    if (typeof value === "number") return Number.isFinite(value) ? value : null

    const match = String(value).replace(",", ".").match(/\d+(?:\.\d+)?/)
    if (!match) return null

    const parsed = Number(match[0])
    return Number.isFinite(parsed) ? parsed : null
}

function truncate(value, limit = 100) {
    const text = normalizeWhitespace(value)
    return text.length > limit ? `${text.slice(0, limit - 1)}…` : text
}

function summarizeInput(testCase) {
    if (testCase.messages.length === 0) {
        return `Image only (${testCase.imageRefs.join(", ")})`
    }

    return truncate(testCase.messages.join(" / "), 120)
}

function readImageFixture(imageRef) {
    const fullPath = path.join(FIXTURE_DIR, imageRef)
    const buffer = fs.readFileSync(fullPath)
    const ext = path.extname(fullPath).toLowerCase()
    const mimetype = ext === ".png" ? "image/png" : "image/jpeg"
    return {
        data: buffer.toString("base64"),
        mimetype
    }
}

function extractCandidateListings(parsedOutput) {
    if (Array.isArray(parsedOutput)) return parsedOutput
    if (Array.isArray(parsedOutput?.listings)) return parsedOutput.listings
    if (parsedOutput && typeof parsedOutput === "object") return [parsedOutput]
    return []
}

function buildNormalizer() {
    return new ListingSubmissionService({
        client: null,
        stateStore: null,
        consentedUsers: new Set(),
        persistConsentedUsers: () => {},
        uploadImage: null,
        createPost: null,
        resolveMediaUrlsByHash: null,
        getSellerByPhone: null,
        messageParser: null,
        langfuse,
        appBaseUrl: "http://localhost:8080"
    })
}

function candidateText(candidate) {
    return normalizeText(`${candidate?.title || ""} ${candidate?.description || ""}`)
}

function titleMatches(candidate, expectedItem) {
    const haystack = candidateText(candidate)
    return expectedItem.titleHints.some(hint => haystack.includes(normalizeText(hint)))
}

function categoryMatches(candidate, expectedItem) {
    const actual = normalizeCategory(candidate?.category)
    return expectedItem.categories.some(category => normalizeCategory(category) === actual)
}

function priceMatches(candidate, expectedItem) {
    return normalizePrice(candidate?.price) === normalizePrice(expectedItem.price)
}

function scoreCandidate(candidate, expectedItem) {
    const titleMatch = titleMatches(candidate, expectedItem)
    const categoryMatch = categoryMatches(candidate, expectedItem)
    const priceMatch = priceMatches(candidate, expectedItem)

    return {
        titleMatch,
        categoryMatch,
        priceMatch,
        score: (priceMatch ? 3 : 0) + (titleMatch ? 2 : 0) + (categoryMatch ? 2 : 0)
    }
}

function matchExpectedListings(expectedItems, actualCandidates) {
    const unusedIndexes = new Set(actualCandidates.map((_, index) => index))
    const matches = []

    for (const expectedItem of expectedItems) {
        let bestIndex = null
        let bestScore = null

        for (const index of unusedIndexes) {
            const candidate = actualCandidates[index]
            const scoring = scoreCandidate(candidate, expectedItem)

            if (!bestScore || scoring.score > bestScore.score) {
                bestScore = scoring
                bestIndex = index
            }
        }

        if (bestIndex === null) {
            matches.push({
                expectedItem,
                actual: null,
                matched: false,
                details: { titleMatch: false, categoryMatch: false, priceMatch: false, score: 0 }
            })
            continue
        }

        unusedIndexes.delete(bestIndex)

        const actual = actualCandidates[bestIndex]
        const matched = bestScore.priceMatch && (bestScore.titleMatch || bestScore.categoryMatch)

        matches.push({
            expectedItem,
            actual,
            matched,
            details: bestScore
        })
    }

    return matches
}

function evaluateParseOutcome(testCase, parsedOutput, parseError, normalized) {
    const candidateListings = extractCandidateListings(parsedOutput)
    const matches = matchExpectedListings(testCase.expectedListings, candidateListings)
    const matchedCount = matches.filter(match => match.matched).length
    const validJson = !parseError
    const actualCount = candidateListings.length
    const itemCountCorrect = candidateListings.length === testCase.expectedListings.length
    const acceptanceCorrect = normalized.ok === testCase.shouldAccept &&
        (testCase.shouldAccept || normalized.reason === testCase.expectedFailureReason)

    const parsingSuccess = testCase.shouldClassify
        ? validJson && itemCountCorrect && matchedCount === testCase.expectedListings.length && acceptanceCorrect
        : null

    return {
        validJson,
        actualCount,
        itemCountCorrect,
        matchedCount,
        expectedCount: testCase.expectedListings.length,
        acceptanceCorrect,
        parsingSuccess,
        matches
    }
}

function buildOutcomeLabel(testCase, evaluation) {
    if (evaluation.classificationCorrect && evaluation.acceptanceCorrect) {
        return evaluation.accepted ? "accepted-correctly" : "rejected-correctly"
    }

    if (!evaluation.classificationCorrect && evaluation.accepted === testCase.shouldAccept) {
        return "misclassified"
    }

    if (evaluation.accepted && !testCase.shouldAccept) return "false-positive"
    if (!evaluation.accepted && testCase.shouldAccept) return "false-negative"
    return "misclassified"
}

function buildIssueList(testCase, evaluation) {
    const issues = []

    if (!evaluation.classificationCorrect) {
        issues.push(`classification expected ${testCase.shouldClassify ? "YES" : "NO"} got ${evaluation.classification}`)
    }
    if (testCase.shouldClassify && evaluation.parse.parseSuccess !== true) {
        if (!evaluation.parse.validJson) {
            issues.push("invalid-json")
        } else if (!evaluation.parse.itemCountCorrect) {
            issues.push(`item-count ${evaluation.parse.actualCount}/${evaluation.parse.expectedCount}`)
        } else if (!evaluation.parse.acceptanceCorrect) {
            issues.push(`acceptance expected ${testCase.shouldAccept ? "accept" : testCase.expectedFailureReason || "reject"}`)
        } else if (evaluation.parse.matchedCount !== evaluation.parse.expectedCount) {
            issues.push(`field-match ${evaluation.parse.matchedCount}/${evaluation.parse.expectedCount}`)
        }
    }

    if (issues.length === 0) issues.push("none")
    return issues
}

async function evaluateModel({ name, provider, testCase, images, normalizer, runId }) {
    const rawText = testCase.messages.join("\n").trim()

    let classification = "NO"
    let classificationError = null
    const classificationStart = Date.now()
    try {
        classification = await provider.classifyContext(testCase.messages, {
            sessionId: runId,
            userId: `${name}-classification`,
            input: testCase.messages
        })
    } catch (error) {
        classificationError = error.message
    }
    const classificationLatencyMs = Date.now() - classificationStart

    let parsedOutput = null
    let parseError = null
    const parseStart = Date.now()
    try {
        parsedOutput = await provider.parseListing(rawText, images, {
            sessionId: runId,
            userId: `${name}-parser`,
            input: { message: rawText, imageCount: images.length }
        })
    } catch (error) {
        parseError = error.message
    }
    const parseLatencyMs = Date.now() - parseStart

    const normalized = parseError
        ? { ok: false, reason: "parse-error" }
        : normalizer.normalizeParsedListings(parsedOutput, rawText, images.length)
    const parseEvaluation = evaluateParseOutcome(testCase, parsedOutput, parseError, normalized)

    const accepted = classification === "YES" && normalized.ok === true
    const classificationCorrect = classification === (testCase.shouldClassify ? "YES" : "NO")
    const acceptanceCorrect = accepted === testCase.shouldAccept
    const recoverySuccess = testCase.recoveryCase ? parseEvaluation.parsingSuccess === true : null

    const evaluation = {
        model: name,
        classification,
        classificationCorrect,
        classificationError,
        classificationLatencyMs,
        parsedOutput,
        parseError,
        parseLatencyMs,
        normalized,
        parse: parseEvaluation,
        accepted,
        acceptanceCorrect,
        falsePositive: !testCase.shouldAccept && accepted,
        falseNegative: testCase.shouldAccept && !accepted,
        recoverySuccess,
        outcome: "",
        issues: []
    }

    evaluation.outcome = buildOutcomeLabel(testCase, evaluation)
    evaluation.issues = buildIssueList(testCase, evaluation)

    return evaluation
}

function scoreAverage(values) {
    if (values.length === 0) return 0
    return values.reduce((sum, value) => sum + value, 0) / values.length
}

function formatPercent(value) {
    return `${(value * 100).toFixed(1)}%`
}

function buildSummary(results, modelName) {
    const cases = results.map(result => result.models[modelName])
    const classificationAccuracy = scoreAverage(cases.map(item => item.classificationCorrect ? 1 : 0))
    const eligibleParseCases = results.filter(result => result.testCase.shouldClassify)
    const parsingSuccessRate = scoreAverage(eligibleParseCases.map(result => result.models[modelName].parse.parsingSuccess ? 1 : 0))
    const jsonValidity = scoreAverage(cases.map(item => item.parse.validJson ? 1 : 0))
    const recoveryCases = results.filter(result => result.testCase.recoveryCase)
    const missingFieldRecovery = scoreAverage(recoveryCases.map(result => result.models[modelName].recoverySuccess ? 1 : 0))
    const imageOnlyCases = results.filter(result => result.testCase.bucket === "image-only")
    const imageOnlyHandling = scoreAverage(imageOnlyCases.map(result => result.models[modelName].acceptanceCorrect ? 1 : 0))
    const trollCases = results.filter(result => ["troll", "irrelevant"].includes(result.testCase.bucket))
    const trollHandling = scoreAverage(trollCases.map(result => {
        const model = result.models[modelName]
        return model.classification === "NO" && model.accepted === false ? 1 : 0
    }))
    const acceptanceAccuracy = scoreAverage(cases.map(item => item.acceptanceCorrect ? 1 : 0))
    const avgClassificationLatencyMs = Math.round(scoreAverage(cases.map(item => item.classificationLatencyMs)))
    const avgParseLatencyMs = Math.round(scoreAverage(cases.map(item => item.parseLatencyMs)))
    const falsePositives = cases.filter(item => item.falsePositive).length
    const falseNegatives = cases.filter(item => item.falseNegative).length
    const invalidJsonCount = cases.filter(item => !item.parse.validJson).length
    const parseFailureCount = eligibleParseCases.filter(result => result.models[modelName].parse.parsingSuccess !== true).length
    const classificationErrorCount = cases.filter(item => item.classificationError).length
    const parseErrorCount = cases.filter(item => item.parseError).length

    const overallRobustness = (
        (classificationAccuracy * 0.30) +
        (parsingSuccessRate * 0.25) +
        (jsonValidity * 0.15) +
        (missingFieldRecovery * 0.10) +
        (trollHandling * 0.10) +
        (imageOnlyHandling * 0.10)
    )

    return {
        classificationAccuracy,
        parsingSuccessRate,
        jsonValidity,
        missingFieldRecovery,
        imageOnlyHandling,
        trollHandling,
        acceptanceAccuracy,
        overallRobustness,
        falsePositives,
        falseNegatives,
        invalidJsonCount,
        parseFailureCount,
        classificationErrorCount,
        parseErrorCount,
        avgClassificationLatencyMs,
        avgParseLatencyMs
    }
}

function renderGroupedBarChart({ title, filename, metrics, series, maxValue = 1, formatter = value => `${Math.round(value * 100)}%` }) {
    const width = 1100
    const height = 100 + metrics.length * 120
    const left = 240
    const chartWidth = 760
    const barHeight = 22
    const gap = 16
    const groupGap = 54

    const rows = metrics.map((metric, index) => {
        const y = 80 + index * (barHeight * series.length + gap * (series.length - 1) + groupGap)
        const label = metric.label
        const bars = series.map((item, seriesIndex) => {
            const value = metric.values[item.key]
            const barY = y + seriesIndex * (barHeight + gap)
            const barWidth = Math.max(0, Math.min(chartWidth, (value / maxValue) * chartWidth))
            return `
    <rect x="${left}" y="${barY}" width="${barWidth}" height="${barHeight}" rx="6" fill="${item.color}" />
    <text x="${left + barWidth + 10}" y="${barY + 16}" font-size="22" fill="#23313f">${formatter(value)}</text>`
        }).join("")

        return `
  <text x="30" y="${y + 16}" font-size="24" fill="#23313f">${label}</text>
  <rect x="${left}" y="${y}" width="${chartWidth}" height="${barHeight}" rx="6" fill="#e9eef3" />
  <rect x="${left}" y="${y + barHeight + gap}" width="${chartWidth}" height="${barHeight}" rx="6" fill="#e9eef3" />
${bars}`
    }).join("\n")

    const legend = series.map((item, index) => `
  <rect x="${left + index * 220}" y="28" width="24" height="24" rx="5" fill="${item.color}" />
  <text x="${left + 34 + index * 220}" y="47" font-size="22" fill="#23313f">${item.label}</text>`).join("\n")

    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}">
  <rect width="${width}" height="${height}" fill="#ffffff" />
  <text x="30" y="48" font-size="30" font-weight="700" fill="#14202b">${title}</text>
${legend}
${rows}
</svg>`

    fs.writeFileSync(filename, svg)
}

function renderCountBarChart({ title, filename, metrics, series }) {
    const maxValue = Math.max(1, ...metrics.flatMap(metric => series.map(item => metric.values[item.key])))
    renderGroupedBarChart({
        title,
        filename,
        metrics,
        series,
        maxValue,
        formatter: value => String(value)
    })
}

function markdownTable(headers, rows) {
    const headerRow = `| ${headers.join(" | ")} |`
    const separatorRow = `| ${headers.map(() => "---").join(" | ")} |`
    const body = rows.map(row => `| ${row.join(" | ")} |`).join("\n")
    return [headerRow, separatorRow, body].filter(Boolean).join("\n")
}

function writeArtifacts({ outputDir, datasetHash, results, summaries, runId, startedAt, finishedAt, durationMs }) {
    fs.mkdirSync(outputDir, { recursive: true })
    const chartsDir = path.join(outputDir, "charts")
    fs.mkdirSync(chartsDir, { recursive: true })

    const metricsChartPath = path.join(chartsDir, "summary-metrics.svg")
    const subsetChartPath = path.join(chartsDir, "subset-metrics.svg")
    const errorChartPath = path.join(chartsDir, "error-counts.svg")

    renderGroupedBarChart({
        title: "Core Metrics",
        filename: metricsChartPath,
        metrics: [
            { label: "Classification accuracy", values: { gemini: summaries.gemini.classificationAccuracy, gpt: summaries.gpt.classificationAccuracy } },
            { label: "Parsing success rate", values: { gemini: summaries.gemini.parsingSuccessRate, gpt: summaries.gpt.parsingSuccessRate } },
            { label: "JSON validity", values: { gemini: summaries.gemini.jsonValidity, gpt: summaries.gpt.jsonValidity } },
            { label: "Missing-field recovery", values: { gemini: summaries.gemini.missingFieldRecovery, gpt: summaries.gpt.missingFieldRecovery } },
            { label: "Overall robustness", values: { gemini: summaries.gemini.overallRobustness, gpt: summaries.gpt.overallRobustness } }
        ],
        series: [
            { key: "gemini", label: "Gemini", color: "#1f6feb" },
            { key: "gpt", label: "GPT", color: "#d97706" }
        ]
    })

    renderGroupedBarChart({
        title: "Subset Handling",
        filename: subsetChartPath,
        metrics: [
            { label: "Acceptance accuracy", values: { gemini: summaries.gemini.acceptanceAccuracy, gpt: summaries.gpt.acceptanceAccuracy } },
            { label: "Image-only handling", values: { gemini: summaries.gemini.imageOnlyHandling, gpt: summaries.gpt.imageOnlyHandling } },
            { label: "Troll/unrelated rejection", values: { gemini: summaries.gemini.trollHandling, gpt: summaries.gpt.trollHandling } }
        ],
        series: [
            { key: "gemini", label: "Gemini", color: "#1f6feb" },
            { key: "gpt", label: "GPT", color: "#d97706" }
        ]
    })

    renderCountBarChart({
        title: "Failure Counts",
        filename: errorChartPath,
        metrics: [
            { label: "False positives", values: { gemini: summaries.gemini.falsePositives, gpt: summaries.gpt.falsePositives } },
            { label: "False negatives", values: { gemini: summaries.gemini.falseNegatives, gpt: summaries.gpt.falseNegatives } },
            { label: "Invalid JSON", values: { gemini: summaries.gemini.invalidJsonCount, gpt: summaries.gpt.invalidJsonCount } },
            { label: "Parse failures", values: { gemini: summaries.gemini.parseFailureCount, gpt: summaries.gpt.parseFailureCount } }
        ],
        series: [
            { key: "gemini", label: "Gemini", color: "#1f6feb" },
            { key: "gpt", label: "GPT", color: "#d97706" }
        ]
    })

    const detailedResults = results.map(result => ({
        id: result.testCase.id,
        bucket: result.testCase.bucket,
        inputSummary: summarizeInput(result.testCase),
        shouldClassify: result.testCase.shouldClassify,
        shouldAccept: result.testCase.shouldAccept,
        images: result.testCase.imageRefs,
        notes: result.testCase.notes,
        gemini: result.models.gemini,
        gpt: result.models.gpt
    }))

    fs.writeFileSync(path.join(outputDir, "dataset.json"), JSON.stringify({
        version: DATASET_VERSION,
        hash: datasetHash,
        cases: TEST_CASES
    }, null, 2))

    fs.writeFileSync(path.join(outputDir, "results.json"), JSON.stringify({
        runId,
        startedAt,
        finishedAt,
        durationMs,
        results: detailedResults
    }, null, 2))

    fs.writeFileSync(path.join(outputDir, "summary.json"), JSON.stringify({
        runId,
        startedAt,
        finishedAt,
        durationMs,
        datasetVersion: DATASET_VERSION,
        datasetHash,
        summaries
    }, null, 2))

    const comparisonRows = [
        ["Classification accuracy", formatPercent(summaries.gemini.classificationAccuracy), formatPercent(summaries.gpt.classificationAccuracy)],
        ["Parsing success rate", formatPercent(summaries.gemini.parsingSuccessRate), formatPercent(summaries.gpt.parsingSuccessRate)],
        ["JSON validity", formatPercent(summaries.gemini.jsonValidity), formatPercent(summaries.gpt.jsonValidity)],
        ["Missing-field recovery", formatPercent(summaries.gemini.missingFieldRecovery), formatPercent(summaries.gpt.missingFieldRecovery)],
        ["Image-only handling", formatPercent(summaries.gemini.imageOnlyHandling), formatPercent(summaries.gpt.imageOnlyHandling)],
        ["Troll/unrelated rejection", formatPercent(summaries.gemini.trollHandling), formatPercent(summaries.gpt.trollHandling)],
        ["Acceptance accuracy", formatPercent(summaries.gemini.acceptanceAccuracy), formatPercent(summaries.gpt.acceptanceAccuracy)],
        ["Overall robustness", formatPercent(summaries.gemini.overallRobustness), formatPercent(summaries.gpt.overallRobustness)],
        ["False positives", String(summaries.gemini.falsePositives), String(summaries.gpt.falsePositives)],
        ["False negatives", String(summaries.gemini.falseNegatives), String(summaries.gpt.falseNegatives)],
        ["Invalid JSON", String(summaries.gemini.invalidJsonCount), String(summaries.gpt.invalidJsonCount)],
        ["Parse failures", String(summaries.gemini.parseFailureCount), String(summaries.gpt.parseFailureCount)],
        ["Classification call errors", String(summaries.gemini.classificationErrorCount), String(summaries.gpt.classificationErrorCount)],
        ["Parse call errors", String(summaries.gemini.parseErrorCount), String(summaries.gpt.parseErrorCount)],
        ["Avg classify latency", `${summaries.gemini.avgClassificationLatencyMs} ms`, `${summaries.gpt.avgClassificationLatencyMs} ms`],
        ["Avg parse latency", `${summaries.gemini.avgParseLatencyMs} ms`, `${summaries.gpt.avgParseLatencyMs} ms`]
    ]

    const caseRows = results.map(result => {
        const gemini = result.models.gemini
        const gpt = result.models.gpt

        return [
            result.testCase.id,
            result.testCase.bucket,
            result.testCase.shouldClassify ? "YES" : "NO",
            result.testCase.shouldAccept ? "ACCEPT" : "REJECT",
            summarizeInput(result.testCase).replace(/\|/g, "\\|"),
            result.testCase.imageRefs.length ? result.testCase.imageRefs.join(", ") : "none",
            `${gemini.classification}/${gemini.accepted ? "ACCEPT" : "REJECT"}`,
            `${gpt.classification}/${gpt.accepted ? "ACCEPT" : "REJECT"}`,
            gemini.issues.join(", ").replace(/\|/g, "\\|"),
            gpt.issues.join(", ").replace(/\|/g, "\\|")
        ]
    })

    const hardCases = results
        .filter(result => {
            const geminiCorrect = ["accepted-correctly", "rejected-correctly"].includes(result.models.gemini.outcome)
            const gptCorrect = ["accepted-correctly", "rejected-correctly"].includes(result.models.gpt.outcome)
            return !geminiCorrect || !gptCorrect
        })
        .sort((left, right) => {
            const leftFailures = Number(!left.models.gemini.acceptanceCorrect) + Number(!left.models.gpt.acceptanceCorrect) + Number(!left.models.gemini.classificationCorrect) + Number(!left.models.gpt.classificationCorrect)
            const rightFailures = Number(!right.models.gemini.acceptanceCorrect) + Number(!right.models.gpt.acceptanceCorrect) + Number(!right.models.gemini.classificationCorrect) + Number(!right.models.gpt.classificationCorrect)
            return rightFailures - leftFailures
        })
        .slice(0, 15)

    const hardCaseRows = hardCases.map(result => [
        result.testCase.id,
        result.testCase.bucket,
        summarizeInput(result.testCase).replace(/\|/g, "\\|"),
        `${result.models.gemini.outcome}; ${result.models.gemini.issues.join(", ")}`.replace(/\|/g, "\\|"),
        `${result.models.gpt.outcome}; ${result.models.gpt.issues.join(", ")}`.replace(/\|/g, "\\|")
    ])

    const winner = summaries.gemini.overallRobustness === summaries.gpt.overallRobustness
        ? "Tie"
        : summaries.gemini.overallRobustness > summaries.gpt.overallRobustness
            ? "Gemini"
            : "GPT"

    const report = `# Synthetic Marketplace Evaluation Report

## Run Metadata

- Run ID: \`${runId}\`
- Started: \`${startedAt}\`
- Finished: \`${finishedAt}\`
- Duration: \`${(durationMs / 1000).toFixed(1)}s\`
- Dataset version: \`${DATASET_VERSION}\`
- Dataset hash: \`${datasetHash}\`
- Cases: \`${TEST_CASES.length}\`
- Providers: \`Gemini\` via existing Gemini provider, \`GPT\` via existing OpenAI-compatible provider configured for \`gpt-4o-mini\`

## Scoring Method

- Classification accuracy: whether the model marked the case as a listing candidate (\`YES\`) or not (\`NO\`) against ground truth.
- Parsing success rate: on cases that should be listing candidates, the parser had to return valid JSON, the expected number of items, the right price/title/category signals, and the correct accept vs reject outcome after repo normalization.
- JSON validity: whether the provider returned parseable JSON for the case.
- Missing-field recovery: accuracy on cases with omitted or weak fields, including image-only inputs, missing category, minimal descriptions, and price-missing cases where the correct behavior is to keep price null.
- False positives / false negatives: end-to-end acceptance errors after combining classification with parsed output normalization.
- Image-only handling: end-to-end correctness on the image-only subset.
- Troll/unrelated handling: rejection accuracy on troll and irrelevant messages.
- Overall robustness: weighted score = 30% classification + 25% parsing success + 15% JSON validity + 10% missing-field recovery + 10% troll/unrelated rejection + 10% image-only handling.

## Summary Comparison

${markdownTable(["Metric", "Gemini", "GPT"], comparisonRows)}

## Charts

![Core metrics](./charts/summary-metrics.svg)

![Subset metrics](./charts/subset-metrics.svg)

![Failure counts](./charts/error-counts.svg)

## All 100 Test Cases

${markdownTable(
    ["ID", "Bucket", "GT Classify", "GT Accept", "Input Summary", "Images", "Gemini", "GPT", "Gemini Issues", "GPT Issues"],
    caseRows
)}

## Hardest Cases

${markdownTable(["ID", "Bucket", "Input Summary", "Gemini", "GPT"], hardCaseRows)}

## Final Conclusion

${winner === "Tie"
        ? `Both models landed on the same overall robustness score. The deciding factors should be the subset metrics and the concrete failure modes in the hard-case table above.`
        : `${winner} performed better on this suite because it achieved the higher overall robustness score. The detailed metrics above show where that lead came from, especially on classification, parsing, and the edge-case subsets.`}
`

    fs.writeFileSync(path.join(outputDir, "report.md"), report)
}

async function main() {
    ensureSyntheticFixtures()

    const datasetJson = JSON.stringify(TEST_CASES)
    const datasetHash = crypto.createHash("sha256").update(datasetJson).digest("hex")
    const runId = `marketplace-eval-${new Date().toISOString().replace(/[:.]/g, "-")}`
    const startedAt = new Date().toISOString()
    const outputDir = path.join(REPORT_ROOT, runId)

    const geminiProvider = createGeminiProvider({ langfuse })
    const gptProvider = createOpenAICompatibleProvider({
        langfuse,
        baseURL: "https://api.openai.com/v1",
        apiKey: process.env.OPENAI_API_KEY,
        model: "gpt-4o-mini"
    })

    const normalizer = buildNormalizer()
    const results = []

    console.log(`Running synthetic marketplace evaluation on ${TEST_CASES.length} cases`)
    console.log(`Dataset: ${DATASET_VERSION}`)

    for (let index = 0; index < TEST_CASES.length; index += 1) {
        const testCase = TEST_CASES[index]
        const images = testCase.imageRefs.map(readImageFixture)

        console.log(`[${String(index + 1).padStart(3, "0")}/${TEST_CASES.length}] ${testCase.id}`)

        const gemini = await evaluateModel({
            name: "gemini",
            provider: geminiProvider,
            testCase,
            images,
            normalizer,
            runId
        })

        const gpt = await evaluateModel({
            name: "gpt",
            provider: gptProvider,
            testCase,
            images,
            normalizer,
            runId
        })

        results.push({
            testCase,
            models: { gemini, gpt }
        })
    }

    const finishedAt = new Date().toISOString()
    const durationMs = new Date(finishedAt).getTime() - new Date(startedAt).getTime()
    const summaries = {
        gemini: buildSummary(results, "gemini"),
        gpt: buildSummary(results, "gpt")
    }

    if (summaries.gemini.parseErrorCount === TEST_CASES.length && summaries.gpt.parseErrorCount === TEST_CASES.length) {
        throw new Error("Every parser call failed for both providers. This run is invalid, likely due to network or credential access.")
    }

    writeArtifacts({
        outputDir,
        datasetHash,
        results,
        summaries,
        runId,
        startedAt,
        finishedAt,
        durationMs
    })

    await langfuse.flush()

    console.log(`Artifacts written to ${outputDir}`)
    console.log(`Gemini robustness: ${formatPercent(summaries.gemini.overallRobustness)}`)
    console.log(`GPT robustness: ${formatPercent(summaries.gpt.overallRobustness)}`)
}

main().catch(async error => {
    console.error("Synthetic marketplace evaluation failed:", error)
    try {
        await langfuse.flush()
    } catch (_) {}
    process.exit(1)
})
