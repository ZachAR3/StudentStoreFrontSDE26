(function registerEmptyState() {
    window.Storefront.core.elementRegistry.register({
        type: "common.emptyState",
        label: "Empty State",
        category: "Common",
        accepts: [],
        defaultProps: {
            icon: "search",
            title: "Nothing here yet",
            message: "There is no content to show."
        },
        editor: {
            icon: "inbox",
            controls: [
                { key: "title", kind: "text" },
                { key: "message", kind: "textarea" }
            ]
        },
        component: "emptyState"
    });
})();
