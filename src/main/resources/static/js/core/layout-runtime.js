(function registerLayoutRuntime() {
    const SPACE_TOKENS = new Set(["2xs", "xs", "sm", "md", "lg", "xl", "2xl", "3xl"]);

    function resolveSpace(value, fallback = "md") {
        const token = SPACE_TOKENS.has(value) ? value : fallback;
        return `var(--sf-space-${token})`;
    }

    function resolveDataSource(key, state, options = {}) {
        const useSampleData = Boolean(options.useSampleData);
        switch (key) {
            case "marketplace.posts":
                return useSampleData ? window.Storefront.data.samplePosts : state.marketplace.posts;
            case "marketplace.filteredPosts":
                return useSampleData ? window.Storefront.data.sampleCatalogItems : state.filteredCatalogItems;
            case "marketplace.favouritePosts":
                return useSampleData ? window.Storefront.data.sampleCatalogItems : state.favouriteCatalogItems;
            case "marketplace.filters":
                return {
                    categories: window.Storefront.data.categories,
                    selectedCategory: state.marketplace.selectedCategory,
                    sortBy: state.marketplace.sortBy,
                    searchQuery: state.marketplace.searchQuery
                };
            case "profile.seller":
                return useSampleData ? window.Storefront.data.sampleProfileSeller : state.profile.profileSeller;
            case "profile.posts":
                return useSampleData ? window.Storefront.data.sampleProfileCatalogItems : state.profile.profileCatalogItems;
            case "builder.sampleCatalog":
                return window.Storefront.data.sampleCatalogItems;
            case "builder.sampleLayout":
                return options.builderLayout || null;
            case "restaurant.sampleMenu":
                return window.Storefront.data.restaurantCatalogItems;
            default:
                return null;
        }
    }

    function renderLayout(layout, state, options = {}) {
        const validation = window.Storefront.core.layoutRegistry.validate(layout);
        if (!validation.isValid) {
            return {
                id: layout?.id || "invalid.layout",
                route: layout?.route || "",
                errors: validation.errors,
                regions: [{
                    id: "layout-error",
                    role: "main",
                    layout: { kind: "stack", gap: "md" },
                    elements: [{
                        id: "layout-error",
                        type: "common.emptyState",
                        props: {
                            icon: "alert",
                            title: "Layout unavailable",
                            message: validation.errors.join(" ")
                        },
                        data: null,
                        definition: window.Storefront.core.elementRegistry.get("common.emptyState")
                    }]
                }]
            };
        }

        return {
            id: layout.id,
            route: layout.route,
            label: layout.label,
            theme: layout.theme || {},
            regions: (layout.regions || []).map((region) => ({
                ...region,
                runtimeStyle: {
                    "--region-min-item-width": region.layout?.minItemWidth || "18rem",
                    "--region-gap": resolveSpace(region.layout?.gap)
                },
                elements: (region.elements || []).map((element) => {
                    const definition = window.Storefront.core.elementRegistry.get(element.type);
                    const props = {
                        ...(definition?.defaultProps || {}),
                        ...(element.props || {})
                    };
                    const data = element.dataSource ? resolveDataSource(element.dataSource, state, options) : null;
                    const propValidation = window.Storefront.core.elementRegistry.validateProps(element.type, props);
                    return {
                        ...element,
                        props,
                        data,
                        definition,
                        propErrors: propValidation.errors
                    };
                })
            })),
            errors: []
        };
    }

    window.Storefront.core.layoutRuntime = {
        resolveDataSource,
        renderLayout
    };
})();
