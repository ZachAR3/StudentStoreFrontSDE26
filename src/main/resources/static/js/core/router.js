(function registerRouter() {
    const routes = {
        listings: "listings",
        favourites: "favourites",
        profile: "profile",
        createListing: "createListing",
        login: "login",
        register: "register",
        forgotPassword: "forgotPassword",
        resetPassword: "resetPassword",
        whatsappLogin: "whatsappLogin",
        layoutBuilder: "layoutBuilder",
        createdSites: "createdSites",
        createdSitePreview: "createdSitePreview",
        restaurantPreview: "restaurantPreview",
        selected: "selected",
        support: "support"
    };

    window.Storefront.core.router = {
        routes,
        isLayoutRoute(view) {
            return ["listings", "favourites", "profile", "restaurantPreview"].includes(view);
        }
    };
})();
