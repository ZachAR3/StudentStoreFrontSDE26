(function registerContactActions() {
    window.Storefront.core.elementRegistry.register({
        type: "marketplace.contactActions",
        label: "Contact Actions",
        category: "Marketplace",
        accepts: ["catalogItem"],
        defaultProps: {
            showEmail: true,
            showWhatsApp: true
        },
        editor: {
            icon: "message",
            controls: [
                { key: "showEmail", kind: "toggle" },
                { key: "showWhatsApp", kind: "toggle" }
            ]
        },
        component: "contactActions"
    });
})();
