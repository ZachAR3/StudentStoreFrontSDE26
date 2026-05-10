const fs = require("fs")
const path = require("path")
const { execFileSync } = require("child_process")

function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"))
}

function formatPercent(value) {
    return `${(value * 100).toFixed(1)}%`
}

function escapeXml(value) {
    return String(value)
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&apos;")
}

function wrapText(text, maxChars) {
    const words = String(text || "").split(/\s+/).filter(Boolean)
    const lines = []
    let current = ""

    for (const word of words) {
        const candidate = current ? `${current} ${word}` : word
        if (candidate.length <= maxChars) {
            current = candidate
            continue
        }

        if (current) lines.push(current)
        current = word
    }

    if (current) lines.push(current)
    return lines
}

function renderWrappedText({ x, y, lines, fontSize, lineHeight, fill, fontWeight = 400 }) {
    return lines
        .map((line, index) => `<text x="${x}" y="${y + index * lineHeight}" font-size="${fontSize}" fill="${fill}" font-weight="${fontWeight}">${escapeXml(line)}</text>`)
        .join("")
}

function imageOnlyStats(results, model) {
    const cases = results.filter(result => result.bucket === "image-only")
    return {
        total: cases.length,
        classifyYes: cases.filter(result => result[model].classification === "YES").length,
        parseOk: cases.filter(result => result[model].parse.parsingSuccess === true).length
    }
}

function trollFalsePositiveIds(results, model) {
    return results
        .filter(result => ["troll", "irrelevant"].includes(result.bucket))
        .filter(result => result[model].accepted)
        .map(result => result.id)
}

function shorthandMisses(results, model) {
    return results
        .filter(result => ["valid-ambiguous", "missing-field", "edge-case"].includes(result.bucket))
        .filter(result => result[model].issues.some(issue => issue.includes("acceptance expected accept") || issue.includes("classification expected YES got NO")))
        .filter(result => {
            const summary = String(result.inputSummary || "").toLowerCase()
            return /\b\d+[a-z]?\b/.test(summary) || summary.includes("dm") || summary.includes("ono") || summary.includes("spkr")
        })
        .slice(0, 4)
        .map(result => result.id)
}

function renderBars(metrics, summaries) {
    const labelX = 1088
    const barX = 1368
    const width = 220
    const baseY = 170
    const rowGap = 118

    return metrics.map((metric, index) => {
        const y = baseY + index * rowGap
        const gemini = metric.gemini(summaries)
        const gpt = metric.gpt(summaries)
        const geminiWidth = Math.round(gemini * width)
        const gptWidth = Math.round(gpt * width)

        return `
  ${renderWrappedText({ x: labelX, y: y + 4, lines: wrapText(metric.label, 16), fontSize: 26, lineHeight: 28, fill: "#f5f7ff", fontWeight: 700 })}
  <rect x="${barX}" y="${y}" width="${width}" height="18" rx="9" fill="rgba(255,255,255,0.16)" />
  <rect x="${barX}" y="${y}" width="${geminiWidth}" height="18" rx="9" fill="#66d9ff" />
  <text x="${barX + width + 14}" y="${y + 16}" font-size="24" fill="#f5f7ff" font-weight="700">${formatPercent(gemini)}</text>
  <rect x="${barX}" y="${y + 36}" width="${width}" height="18" rx="9" fill="rgba(255,255,255,0.16)" />
  <rect x="${barX}" y="${y + 36}" width="${gptWidth}" height="18" rx="9" fill="#ff9f43" />
  <text x="${barX + width + 14}" y="${y + 52}" font-size="24" fill="#f5f7ff" font-weight="700">${formatPercent(gpt)}</text>`
    }).join("\n")
}

function failureCard({ x, y, title, body, accent, footer }) {
    const bodyLines = wrapText(body, 24).slice(0, 2)
    const footerLines = wrapText(footer, 30).slice(0, 2)

    return `
  <rect x="${x}" y="${y}" width="12" height="224" rx="6" fill="${accent}" />
  <line x1="${x + 28}" y1="${y + 64}" x2="${x + 510}" y2="${y + 64}" stroke="rgba(255,255,255,0.22)" stroke-width="2" />
  <text x="${x + 28}" y="${y + 46}" font-size="34" font-weight="700" fill="#ffffff">${escapeXml(title)}</text>
  ${renderWrappedText({ x: x + 28, y: y + 98, lines: bodyLines, fontSize: 28, lineHeight: 36, fill: "#e6ecff", fontWeight: 600 })}
  ${renderWrappedText({ x: x + 28, y: y + 176, lines: footerLines, fontSize: 23, lineHeight: 30, fill: "#b8c3e6" })}`
}

function buildAssets({ summaries, results, runId }) {
    const gemini = summaries.gemini
    const gpt = summaries.gpt
    const imageGemini = imageOnlyStats(results, "gemini")
    const imageGpt = imageOnlyStats(results, "gpt")
    const geminiTrollFp = trollFalsePositiveIds(results, "gemini")
    const gptTrollFp = trollFalsePositiveIds(results, "gpt")
    const shorthand = [...new Set([...shorthandMisses(results, "gemini"), ...shorthandMisses(results, "gpt")])].slice(0, 4)

    const metrics = [
        {
            label: "Overall robustness",
            gemini: data => data.gemini.overallRobustness,
            gpt: data => data.gpt.overallRobustness
        },
        {
            label: "Classification accuracy",
            gemini: data => data.gemini.classificationAccuracy,
            gpt: data => data.gpt.classificationAccuracy
        },
        {
            label: "Missing-field recovery",
            gemini: data => data.gemini.missingFieldRecovery,
            gpt: data => data.gpt.missingFieldRecovery
        }
    ]

    const chartSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="1780" height="520" viewBox="0 0 1780 520">
  <text x="42" y="62" font-size="42" font-weight="700" fill="#ffffff">Marketplace AI Evaluation</text>
  <text x="42" y="118" font-size="32" font-weight="700" fill="#ffffff">Gemini leads overall</text>
  ${renderWrappedText({ x: 42, y: 164, lines: wrapText("Better recall on weak listings and stronger field recovery.", 38), fontSize: 30, lineHeight: 40, fill: "#e6ecff" })}
  <text x="42" y="304" font-size="96" font-weight="800" fill="#66d9ff">${formatPercent(gemini.overallRobustness)}</text>
  <text x="42" y="346" font-size="30" fill="#cdd6f4">Gemini robustness</text>
  <text x="42" y="394" font-size="34" font-weight="700" fill="#f5f7ff">GPT: ${formatPercent(gpt.overallRobustness)}</text>
  <line x1="0" y1="430" x2="980" y2="430" stroke="rgba(255,255,255,0.22)" stroke-width="2" />

  <text x="1080" y="62" font-size="40" font-weight="700" fill="#ffffff">Comparison</text>
  <rect x="1080" y="98" width="18" height="18" rx="9" fill="#66d9ff" />
  <text x="1110" y="113" font-size="24" fill="#f5f7ff" font-weight="700">Gemini</text>
  <rect x="1238" y="98" width="18" height="18" rx="9" fill="#ff9f43" />
  <text x="1268" y="113" font-size="24" fill="#f5f7ff" font-weight="700">GPT</text>
  <line x1="1040" y1="136" x2="1780" y2="136" stroke="rgba(255,255,255,0.22)" stroke-width="2" />
  ${renderBars(metrics, summaries)}
</svg>`

    const failuresSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="1832" height="360" viewBox="0 0 1832 360">
  <text x="0" y="42" font-size="40" font-weight="700" fill="#ffffff">Remaining failure modes</text>
  ${failureCard({
        x: 0,
        y: 86,
        title: "Image-only gating",
        body: `Parsers recover images well.`,
        accent: "#66d9ff",
        footer: `Classifier hits: ${imageGemini.classifyYes}/${imageGemini.total} and ${imageGpt.classifyYes}/${imageGpt.total}.`
    })}
  ${failureCard({
        x: 646,
        y: 86,
        title: "Troll listings",
        body: `Some joke posts still pass.`,
        accent: "#ff9f43",
        footer: `Accepted: ${geminiTrollFp.length} Gemini, ${gptTrollFp.length} GPT.`
    })}
  ${failureCard({
        x: 1292,
        y: 86,
        title: "Short slang posts",
        body: `Very short pricing slang still fails.`,
        accent: "#8bffb0",
        footer: `${shorthand.slice(0, 3).join(", ")}`
    })}
</svg>`

    const combinedSvg = `<svg xmlns="http://www.w3.org/2000/svg" width="1832" height="930" viewBox="0 0 1832 930">
  ${chartSvg.replace(/^<svg[^>]*>/, "").replace(/<\/svg>$/, "")}
  <g transform="translate(0,548)">
    ${failuresSvg.replace(/^<svg[^>]*>/, "").replace(/<\/svg>$/, "")}
  </g>
  <text x="0" y="912" font-size="24" fill="#b8c3e6">Parsers are stronger on images than the current text-only classifier score suggests.</text>
</svg>`

    return { chartSvg, failuresSvg, combinedSvg }
}

function renderTransparentPng(svgPath, pngPath) {
    execFileSync("magick", ["-background", "none", svgPath, pngPath], { stdio: "ignore" })
}

function writeSlide(reportDir) {
    const summary = readJson(path.join(reportDir, "summary.json"))
    const results = readJson(path.join(reportDir, "results.json"))
    const { chartSvg, failuresSvg, combinedSvg } = buildAssets({
        summaries: summary.summaries,
        results: results.results,
        runId: summary.runId
    })

    const outputs = {
        chartSvgPath: path.join(reportDir, "evaluation-chart-transparent.svg"),
        chartPngPath: path.join(reportDir, "evaluation-chart-transparent.png"),
        failuresSvgPath: path.join(reportDir, "evaluation-failures-transparent.svg"),
        failuresPngPath: path.join(reportDir, "evaluation-failures-transparent.png"),
        combinedSvgPath: path.join(reportDir, "evaluation-slide-transparent.svg"),
        combinedPngPath: path.join(reportDir, "evaluation-slide-transparent.png")
    }

    fs.writeFileSync(outputs.chartSvgPath, chartSvg)
    fs.writeFileSync(outputs.failuresSvgPath, failuresSvg)
    fs.writeFileSync(outputs.combinedSvgPath, combinedSvg)

    renderTransparentPng(outputs.chartSvgPath, outputs.chartPngPath)
    renderTransparentPng(outputs.failuresSvgPath, outputs.failuresPngPath)
    renderTransparentPng(outputs.combinedSvgPath, outputs.combinedPngPath)

    return outputs
}

function main() {
    const reportDir = process.argv[2]
        ? path.resolve(process.argv[2])
        : path.resolve(__dirname, "../../../reports/marketplace-evaluation/marketplace-eval-2026-05-10T12-06-18-711Z")

    const outputs = writeSlide(reportDir)
    console.log(JSON.stringify(outputs, null, 2))
}

main()
