const fs = require("fs")
const path = require("path")
const { execFileSync } = require("child_process")

const FIXTURE_DIR = path.join(__dirname, "fixtures")

const FIXTURES = [
    {
        name: "laptop-sale.png",
        background: "#f4efe6",
        accent: "#274c77",
        lines: ["MACBOOK AIR M1", "8GB / 256GB SSD", "750 EUR", "charger included"],
        footer: "Student sale"
    },
    {
        name: "textbooks-sale.png",
        background: "#f7f3ea",
        accent: "#8c5e3c",
        lines: ["CALCULUS + PHYSICS", "2 textbooks", "35 EUR", "clean inside"],
        footer: "Pick up on campus"
    },
    {
        name: "chair-sale.png",
        background: "#eef4f7",
        accent: "#4f6d7a",
        lines: ["DESK CHAIR", "mesh back", "25 EUR", "good condition"],
        footer: "Near dorm C"
    },
    {
        name: "multi-board.png",
        background: "#fff8e8",
        accent: "#335c67",
        lines: ["MONITOR 70 EUR", "KEYBOARD 10 EUR", "MOUSE 5 EUR", "all tested"],
        footer: "Moving out sale"
    },
    {
        name: "headphones-sale.png",
        background: "#f1f0f7",
        accent: "#3d405b",
        lines: ["SONY HEADPHONES", "WH-CH720N", "65 EUR", "case included"],
        footer: "Works perfectly"
    },
    {
        name: "minifridge-sale.png",
        background: "#edf6f0",
        accent: "#386641",
        lines: ["MINI FRIDGE", "quiet motor", "80 EUR", "clean inside"],
        footer: "Pickup only"
    },
    {
        name: "bike-no-price.png",
        background: "#f6efe7",
        accent: "#bc6c25",
        lines: ["ROAD BIKE", "good condition", "pickup this week", "message if keen"],
        footer: "No price shown"
    },
    {
        name: "calculator-sale.png",
        background: "#f2f5f8",
        accent: "#1d3557",
        lines: ["TI-84 PLUS CE", "graphing calculator", "45 EUR", "exam approved"],
        footer: "Batteries included"
    }
]

function ensureFixtureDirectory() {
    fs.mkdirSync(FIXTURE_DIR, { recursive: true })
}

function fixturePath(name) {
    return path.join(FIXTURE_DIR, name)
}

function buildMagickArgs(spec) {
    const args = [
        "-size", "1280x960",
        `xc:${spec.background}`,
        "-fill", spec.accent,
        "-draw", "roundrectangle 80,80 1200,880 36,36",
        "-fill", "#ffffff",
        "-draw", "roundrectangle 130,150 1150,810 28,28",
        "-fill", spec.accent,
        "-font", "DejaVu-Sans-Bold",
        "-pointsize", "78",
        "-gravity", "North",
        "-annotate", "+0+170", spec.lines[0],
        "-font", "DejaVu-Sans",
        "-pointsize", "52",
        "-annotate", "+0+280", spec.lines[1],
        "-pointsize", "92",
        "-annotate", "+0+390", spec.lines[2],
        "-pointsize", "46",
        "-annotate", "+0+500", spec.lines[3],
        "-pointsize", "34",
        "-annotate", "+0+615", spec.footer,
        fixturePath(spec.name)
    ]

    return args
}

function ensureSyntheticFixtures() {
    ensureFixtureDirectory()

    for (const spec of FIXTURES) {
        const outputPath = fixturePath(spec.name)
        if (fs.existsSync(outputPath)) continue
        execFileSync("magick", buildMagickArgs(spec), { stdio: "ignore" })
    }

    return FIXTURES.map(spec => ({
        name: spec.name,
        path: fixturePath(spec.name)
    }))
}

module.exports = {
    FIXTURE_DIR,
    ensureSyntheticFixtures
}
