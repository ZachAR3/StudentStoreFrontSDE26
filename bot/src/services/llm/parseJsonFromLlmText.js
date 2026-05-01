function collectCodeFenceCandidates(text) {
    const candidates = []
    const fenceRegex = /```(?:json)?\s*([\s\S]*?)```/gi
    let match = fenceRegex.exec(text)

    while (match) {
        const candidate = String(match[1] || "").trim()
        if (candidate) candidates.push(candidate)
        match = fenceRegex.exec(text)
    }

    return candidates
}

function collectBalancedObjectCandidates(text) {
    const candidates = []

    for (let start = 0; start < text.length; start += 1) {
        if (text[start] !== "{") continue

        let depth = 0
        let inString = false
        let escaped = false

        for (let cursor = start; cursor < text.length; cursor += 1) {
            const char = text[cursor]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char === "\\") {
                    escaped = true
                } else if (char === "\"") {
                    inString = false
                }
                continue
            }

            if (char === "\"") {
                inString = true
                continue
            }

            if (char === "{") {
                depth += 1
                continue
            }

            if (char === "}") {
                depth -= 1
                if (depth === 0) {
                    candidates.push(text.slice(start, cursor + 1))
                    start = cursor
                    break
                }
            }
        }
    }

    return candidates
}

function collectBalancedArrayCandidates(text) {
    const candidates = []

    for (let start = 0; start < text.length; start += 1) {
        if (text[start] !== "[") continue

        let depth = 0
        let inString = false
        let escaped = false

        for (let cursor = start; cursor < text.length; cursor += 1) {
            const char = text[cursor]

            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char === "\\") {
                    escaped = true
                } else if (char === "\"") {
                    inString = false
                }
                continue
            }

            if (char === "\"") {
                inString = true
                continue
            }

            if (char === "[") {
                depth += 1
                continue
            }

            if (char === "]") {
                depth -= 1
                if (depth === 0) {
                    candidates.push(text.slice(start, cursor + 1))
                    start = cursor
                    break
                }
            }
        }
    }

    return candidates
}

function uniqueNonEmpty(values) {
    const seen = new Set()
    return values.filter(value => {
        const normalized = String(value || "").trim()
        if (!normalized || seen.has(normalized)) return false
        seen.add(normalized)
        return true
    })
}

function parseJsonFromLlmText(text) {
    if (typeof text !== "string") {
        throw new Error("LLM response must be a string")
    }

    const candidates = uniqueNonEmpty([
        text.trim(),
        ...collectCodeFenceCandidates(text),
        ...collectBalancedArrayCandidates(text),
        ...collectBalancedObjectCandidates(text)
    ])

    let lastError = null

    for (const candidate of candidates) {
        try {
            return JSON.parse(candidate)
        } catch (error) {
            lastError = error
        }
    }

    throw lastError || new Error("Unable to parse JSON from LLM response")
}

module.exports = { parseJsonFromLlmText }
