(function registerCatalogGrid() {
    window.Storefront.core.elementRegistry.register({
        type: "catalog.grid",
        label: "Catalog Grid",
        category: "Common",
        accepts: ["catalogItem"],
        defaultProps: {
            itemElement: "marketplace.itemCard",
            emptyElement: "common.emptyState",
            minItemWidth: "18rem",
            emptyTitle: "No items found",
            emptyMessage: "Nothing matches the current filters.",
            favouriteMode: false,
            enableCart: false,
            cartButtonLabel: "Add",
            showCartSummary: false
        },
        editor: {
            icon: "grid",
            controls: [
                { key: "minItemWidth", kind: "select", options: ["14rem", "18rem", "22rem"] },
                { key: "favouriteMode", kind: "toggle" },
                { key: "enableCart", kind: "toggle" },
                { key: "showCartSummary", kind: "toggle" },
                { key: "cartButtonLabel", kind: "text" }
            ]
        },
        component: "catalogGrid"
    });

    window.Storefront.core.elementRegistry.register({
        type: "restaurant.menuGrid",
        label: "Restaurant Menu Grid",
        category: "Restaurant Sample",
        accepts: ["catalogItem"],
        defaultProps: {
            itemElement: "restaurant.menuItemCard",
            emptyElement: "common.emptyState",
            minItemWidth: "18rem",
            emptyTitle: "No dishes available",
            emptyMessage: "Restaurant sample data has not been loaded yet.",
            enableCart: true,
            cartButtonLabel: "Add",
            showCartSummary: true
        },
        editor: {
            icon: "grid",
            controls: [
                { key: "minItemWidth", kind: "select", options: ["14rem", "18rem", "22rem"] },
                { key: "enableCart", kind: "toggle" },
                { key: "showCartSummary", kind: "toggle" },
                { key: "cartButtonLabel", kind: "text" }
            ]
        },
        component: "catalogGrid"
    });
})();
