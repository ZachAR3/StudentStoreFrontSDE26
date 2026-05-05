(function registerShellHeader() {
    window.Storefront.core.elementRegistry.register({
        type: "shell.header",
        label: "Shell Header",
        category: "Common",
        accepts: [],
        defaultProps: {
            showSearch: true
        },
        editor: {
            icon: "layout",
            controls: [
                { key: "showSearch", kind: "toggle" }
            ]
        },
        component: "shellHeader"
    });
})();
