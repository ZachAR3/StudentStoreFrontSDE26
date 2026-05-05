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

if (errors.length) {
    console.error("Frontend contract validation failed:");
    errors.forEach((error) => console.error(`- ${error}`));
    process.exit(1);
}

console.log(`Validated ${layoutFiles.length} frontend layout contract(s).`);
