(function registerCreateListingForm() {
    window.Storefront.core.elementRegistry.register({
        type: "marketplace.createListingForm",
        label: "Create Listing Form",
        category: "Forms",
        accepts: [],
        defaultProps: {},
        editor: { icon: "plus-square", controls: [] },
        component: "createListingForm"
    });
})();
