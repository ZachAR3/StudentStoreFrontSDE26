(function registerProfileSummary() {
    window.Storefront.core.elementRegistry.register({
        type: "profile.summary",
        label: "Profile Summary",
        category: "Profile",
        accepts: ["profile"],
        defaultProps: {},
        editor: { icon: "id-card", controls: [] },
        component: "profileSummary"
    });
})();
