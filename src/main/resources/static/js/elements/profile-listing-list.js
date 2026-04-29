(function registerProfileListingList() {
    window.Storefront.core.elementRegistry.register({
        type: "profile.listingList",
        label: "Profile Listing List",
        category: "Profile",
        accepts: ["catalogItem"],
        defaultProps: {},
        editor: { icon: "list", controls: [] },
        component: "profileListingList"
    });

    window.Storefront.core.elementRegistry.register({
        type: "builder.palette",
        label: "Builder Palette",
        category: "Builder",
        accepts: [],
        defaultProps: {},
        editor: { icon: "layout", controls: [] },
        component: "builderPalette"
    });

    window.Storefront.core.elementRegistry.register({
        type: "builder.canvas",
        label: "Builder Canvas",
        category: "Builder",
        accepts: [],
        defaultProps: {},
        editor: { icon: "monitor", controls: [] },
        component: "builderCanvas"
    });

    window.Storefront.core.elementRegistry.register({
        type: "builder.inspector",
        label: "Builder Inspector",
        category: "Builder",
        accepts: [],
        defaultProps: {},
        editor: { icon: "settings", controls: [] },
        component: "builderInspector"
    });
})();
