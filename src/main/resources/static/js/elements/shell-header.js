(function registerShellHeader() {
    window.Storefront.core.elementRegistry.register({
        type: "shell.header",
        label: "Shell Header",
        category: "Common",
        accepts: [],
        defaultProps: {
            showSearch: true,
            showBuilderShortcut: true,
            showRestaurantShortcut: true
        },
        editor: {
            icon: "layout",
            controls: [
                { key: "showSearch", kind: "toggle" },
                { key: "showBuilderShortcut", kind: "toggle" },
                { key: "showRestaurantShortcut", kind: "toggle" }
            ]
        },
        component: "shellHeader"
    });
})();
