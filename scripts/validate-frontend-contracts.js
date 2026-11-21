#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const root = path.resolve(__dirname, "..");
const staticRoot = path.join(root, "src/main/resources/static");

const context = {
    console,
    structuredClone: global.structuredClone,
    window: {}
};
context.window.window = context.window;
vm.createContext(context);

function runScript(relativePath) {
    const absolutePath = path.join(staticRoot, relativePath);
    const source = fs.readFileSync(absolutePath, "utf8");
    vm.runInContext(source, context, { filename: relativePath });
}

function readJson(relativePath) {
    const absolutePath = path.join(staticRoot, relativePath);
    return JSON.parse(fs.readFileSync(absolutePath, "utf8"));
}

[
    "js/core/namespace.js",
    "js/core/content-adapters.js",
    "js/core/validators.js",
    "js/core/element-registry.js",
    "js/core/layout-registry.js",
    "js/elements/shell-header.js",
    "js/elements/filter-bar.js",
    "js/elements/catalog-grid.js",
    "js/elements/item-card.js",
    "js/elements/media-carousel.js",
    "js/elements/contact-actions.js",
    "js/elements/favourite-button.js",
    "js/elements/empty-state.js",
    "js/elements/sales-sections.js",
    "js/elements/auth-panel.js",
    "js/elements/create-listing-form.js",
    "js/elements/profile-summary.js",
    "js/elements/profile-listing-list.js"
].forEach(runScript);

const layoutDir = path.join(staticRoot, "config/layouts");
const layoutFiles = fs.readdirSync(layoutDir)
    .filter((file) => file.endsWith(".json"))
    .sort();

const registry = context.window.Storefront.core.layoutRegistry;
const errors = [];

layoutFiles.forEach((file) => {
    const relativePath = `config/layouts/${file}`;
    try {
        const layout = readJson(relativePath);
        const validation = registry.validate(layout);
        if (!validation.isValid) {
            errors.push(`${relativePath}: ${validation.errors.join("; ")}`);
        }
        registry.register(layout);
    } catch (error) {
        errors.push(`${relativePath}: ${error.message}`);
    }
});

const registeredIds = new Set();
registry.list().forEach((layout) => {
    if (registeredIds.has(layout.id)) {
        errors.push(`Duplicate registered layout id: ${layout.id}`);
    }
    registeredIds.add(layout.id);
});

const adapter = context.window.Storefront.core.adapters;
const sold = adapter.catalogItemFromPost({
    postId: 42,
    title: "Sold fixture",
    status: "SOLD",
    isSold: true,
    mediaUrls: []
});
if (sold.status.sold !== true) {
    errors.push("Backend sold/isSold contract was not normalized to a sold catalog item");
}

// Listing categories are defined in several places; they must agree (X-08).
function readRepoFile(relativePath) {
    return fs.readFileSync(path.join(root, relativePath), "utf8");
}

function categoriesBetween(source, startPattern, label) {
    const start = source.search(startPattern);
    if (start < 0) {
        errors.push(`${label}: category list not found`);
        return [];
    }
    const end = source.indexOf("]", start);
    return [...source.slice(start, end).matchAll(/'([A-Z_]+)'/g)].map((match) => match[1]);
}

const backendCategories = [...readRepoFile("src/main/kotlin/com/studentstorefront/enums/Category.kt")
    .matchAll(/^\s*([A-Z_]+)\(/gm)].map((match) => match[1]);
context.window.Storefront.data = context.window.Storefront.data || {};
runScript("js/data/categories.js");
const categorySources = {
    "frontend js/data/categories.js": context.window.Storefront.data.categories
        .map((category) => category.value)
        .filter(Boolean),
    "bot ListingSubmissionService.js": categoriesBetween(
        readRepoFile("bot/src/services/ListingSubmissionService.js"), /VALID_CATEGORIES\s*=/, "bot ListingSubmissionService.js"),
    "bot WhatsAppShoppingChat.js": categoriesBetween(
        readRepoFile("bot/src/shopping/WhatsAppShoppingChat.js"), /CATEGORY_NAMES\s*=/, "bot WhatsAppShoppingChat.js"),
    "bot Prompt-File.js": (readRepoFile("bot/src/services/Prompt-File.js").match(/category one of ([A-Z_,]+)/)?.[1] || "")
        .split(",")
        .filter(Boolean)
};
const expectedCategories = [...backendCategories].sort().join(",");
if (!backendCategories.length) {
    errors.push("Backend Category enum could not be parsed");
}
Object.entries(categorySources).forEach(([label, values]) => {
    const actual = [...values].sort().join(",");
    if (actual !== expectedCategories) {
        errors.push(`${label} categories [${actual}] differ from backend enum [${expectedCategories}]`);
    }
});

if (errors.length) {
    console.error("Frontend contract validation failed:");
    errors.forEach((error) => console.error(`- ${error}`));
    process.exit(1);
}

console.log(`Validated ${layoutFiles.length} frontend layout contract(s) and ${Object.keys(categorySources).length + 1} category list(s).`);
