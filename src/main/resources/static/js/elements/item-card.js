(function registerItemCard() {
    window.Storefront.core.elementRegistry.register({
        type: "marketplace.itemCard",
        label: "Marketplace Item Card",
        category: "Marketplace",
        accepts: ["catalogItem"],
        defaultProps: {
            mediaRatio: "4 / 3",
            showFavourite: true,
            showSeller: true,
            showContactActions: true,
            priceStyle: "prominent"
        },
        editor: {
            icon: "card",
            controls: [
                { key: "mediaRatio", kind: "select", options: ["1 / 1", "4 / 3", "16 / 9"] },
                { key: "showFavourite", kind: "toggle" },
                { key: "showSeller", kind: "toggle" },
                { key: "showContactActions", kind: "toggle" }
            ]
        },
        component: "itemCard"
    });

    window.Storefront.core.elementRegistry.register({
        type: "restaurant.menuHero",
        label: "Restaurant Menu Header",
        category: "Restaurant Sample",
        accepts: [],
        defaultProps: {
            title: "North Hall Kitchen",
            eyebrow: "Campus dining",
            message: "Fresh bowls, brunch plates, and quick bites prepared for pickup.",
            showStats: true
        },
        editor: {
            icon: "utensils",
            controls: [
                { key: "eyebrow", kind: "text" },
                { key: "title", kind: "text" },
                { key: "message", kind: "textarea" },
                { key: "showStats", kind: "toggle" }
            ]
        },
        component: "restaurantMenuHero"
    });

    window.Storefront.core.elementRegistry.register({
        type: "restaurant.menuItemCard",
        label: "Restaurant Menu Item Card",
        category: "Restaurant Sample",
        accepts: ["catalogItem"],
        defaultProps: {
            mediaRatio: "1 / 1",
            showFavourite: false,
            showSeller: true,
            showContactActions: false,
            showCategory: true,
            showAddButton: true
        },
        editor: {
            icon: "utensils",
            controls: [
                { key: "mediaRatio", kind: "select", options: ["1 / 1", "4 / 3", "16 / 9"] },
                { key: "showSeller", kind: "toggle" },
                { key: "showCategory", kind: "toggle" },
                { key: "showAddButton", kind: "toggle" }
            ]
        },
        component: "restaurantMenuItemCard"
    });
})();
