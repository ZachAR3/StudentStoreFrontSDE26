(function registerValidators() {
    const KNOWN_TOKENS = new Set([
        "xs", "sm", "md", "lg", "xl",
        "compact", "comfortable", "spacious",
        "1 / 1", "4 / 3", "16 / 9",
        "campus", "success", "danger",
        "2col", "3col", "card", "row", "stack", "responsive-grid"
    ]);

    const KNOWN_DATA_SOURCES = new Set([
        "marketplace.posts",
        "marketplace.filteredPosts",
        "marketplace.favouritePosts",
        "marketplace.filters",
        "profile.seller",
        "profile.posts",
        "builder.sampleCatalog",
        "builder.sampleLayout",
        "restaurant.sampleMenu"
    ]);

    function validateLayout(layout, registry) {
        const errors = [];
        if (!layout || typeof layout !== "object") {
            return { isValid: false, errors: ["Layout must be an object."] };
        }
        if (!Number.isInteger(layout.version)) {
            errors.push("Layout version must be an integer.");
        }
        if (!layout.id || typeof layout.id !== "string") {
            errors.push("Layout id is required.");
        }
        if (!Array.isArray(layout.regions)) {
            errors.push("Layout regions must be an array.");
        }
        (layout.regions || []).forEach((region) => {
            if (!region.id) {
                errors.push("Every region needs an id.");
            }
            if (region.layout?.kind && !KNOWN_TOKENS.has(region.layout.kind)) {
                errors.push(`Unsupported region layout kind: ${region.layout.kind}`);
            }
            (region.elements || []).forEach((element) => {
                if (!registry.get(element.type)) {
                    errors.push(`Unknown element type: ${element.type}`);
                }
                if (element.dataSource && !KNOWN_DATA_SOURCES.has(element.dataSource)) {
                    errors.push(`Unknown data source: ${element.dataSource}`);
                }
            });
        });
        return { isValid: errors.length === 0, errors };
    }

    function validatePropToken(value) {
        return typeof value !== "string" || KNOWN_TOKENS.has(value) || value.endsWith("rem") || value.endsWith("%");
    }

    window.Storefront.core.validators = {
        KNOWN_DATA_SOURCES,
        KNOWN_TOKENS,
        validateLayout,
        validatePropToken
    };
})();
