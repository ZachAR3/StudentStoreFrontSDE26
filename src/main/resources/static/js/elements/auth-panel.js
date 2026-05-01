(function registerAuthPanel() {
    window.Storefront.core.elementRegistry.register({
        type: "auth.panel",
        label: "Auth Panel",
        category: "Forms",
        accepts: [],
        defaultProps: {
            mode: "login"
        },
        editor: {
            icon: "user",
            controls: [{ key: "mode", kind: "select", options: ["login", "register", "forgotPassword", "resetPassword", "whatsappLogin", "emailVerification"] }]
        },
        component: "authPanel"
    });
})();
