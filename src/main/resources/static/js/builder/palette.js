(function registerBuilderPalette() {
    const DIRECTLY_RENDERED_TYPES = new Set([
        "marketplace.filterBar",
        "catalog.grid",
        "restaurant.menuHero",
        "restaurant.menuGrid",
        "profile.summary",
        "profile.listingList",
        "common.emptyState"
    ]);

    window.Storefront.builder.groupPalette = function groupPalette() {
        const grouped = {};
        window.Storefront.core.elementRegistry.list().forEach((element) => {
            if (!DIRECTLY_RENDERED_TYPES.has(element.type)) return;
            const category = element.category || "Other";
            grouped[category] = grouped[category] || [];
            grouped[category].push(element);
        });
        return grouped;
    };
})();
