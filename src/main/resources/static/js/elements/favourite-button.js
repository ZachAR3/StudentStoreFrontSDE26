(function registerFavouriteButton() {
    window.Storefront.core.elementRegistry.register({
        type: "marketplace.favouriteButton",
        label: "Favourite Button",
        category: "Marketplace",
        accepts: ["catalogItem"],
        defaultProps: {},
        editor: { icon: "heart", controls: [] },
        component: "favouriteButton"
    });
})();
