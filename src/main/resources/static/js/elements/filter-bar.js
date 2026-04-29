(function registerFilterBar() {
    window.Storefront.core.elementRegistry.register({
        type: "marketplace.filterBar",
        label: "Marketplace Filter Bar",
        category: "Marketplace",
        accepts: ["filters"],
        defaultProps: {
            showCategory: true,
            showSort: true,
            showSearch: false
        },
        editor: {
            icon: "filter",
            controls: [
                { key: "showCategory", kind: "toggle" },
                { key: "showSort", kind: "toggle" },
                { key: "showSearch", kind: "toggle" }
            ]
        },
        component: "filterBar"
    });
})();
