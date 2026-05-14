(function registerLayoutRuntime() {
    const SPACE_TOKENS = new Set(["2xs", "xs", "sm", "md", "lg", "xl", "2xl", "3xl"]);

    function resolveSpace(value, fallback = "md") {
        const token = SPACE_TOKENS.has(value) ? value : fallback;
        return `var(--sf-space-${token})`;
    }

    function searchableText(value) {
        if (!value) return "";
        return [
            value.title,
            value.description,
            value.badge,
            value.category,
            value.kitchen,
            value.user?.name,
            value.raw?.title,
            value.raw?.description,
            value.raw?.category,
            value.raw?.kitchen
        ].filter(Boolean).join(" ").toLowerCase();
    }

    function filterByHeaderSearch(items, state) {
        const query = String(state?.marketplace?.searchQuery || "").trim().toLowerCase();
        if (!query || !Array.isArray(items)) return items;
        return items.filter((item) => searchableText(item).includes(query));
    }

    function resolveDataSource(key, state, options = {}) {
        const useSampleData = Boolean(options.useSampleData);
        switch (key) {
            case "marketplace.posts":
                return filterByHeaderSearch(
                    useSampleData ? window.Storefront.data.samplePosts : state.marketplace.posts,
                    state
                );
            case "marketplace.filteredPosts":
                return filterByHeaderSearch(
                    useSampleData ? window.Storefront.data.sampleCatalogItems : state.filteredCatalogItems,
                    state
                );
            case "marketplace.favouritePosts":
                return filterByHeaderSearch(
                    useSampleData ? window.Storefront.data.sampleCatalogItems : state.favouriteCatalogItems,
                    state
                );
            case "marketplace.filters":
                return {
                    categories: window.Storefront.data.categories,
                    selectedCategory: state.marketplace.selectedCategory,
                    sortBy: state.marketplace.sortBy,
                    searchQuery: state.marketplace.searchQuery
                };
            case "profile.user":
                return useSampleData ? window.Storefront.data.sampleProfileUser : state.profile.profileUser;
            case "profile.posts":
                return filterByHeaderSearch(
                    useSampleData ? window.Storefront.data.sampleProfileCatalogItems : state.profile.profileCatalogItems,
                    state
                );
            case "builder.sampleCatalog":
                return filterByHeaderSearch(window.Storefront.data.sampleCatalogItems, state);
            case "builder.sampleLayout":
                return options.builderLayout || null;
            case "restaurant.sampleMenu":
                return filterByHeaderSearch(window.Storefront.data.restaurantCatalogItems, state);
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
