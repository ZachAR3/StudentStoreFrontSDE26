(function registerDefaultLayoutLoader() {
    const layoutUrls = [
        "/config/layouts/marketplace-home.json",
        "/config/layouts/marketplace-favourites.json",
        "/config/layouts/marketplace-profile.json",
        "/config/layouts/restaurant-menu.sample.json"
    ];

    async function loadDefaultLayouts() {
        return window.Storefront.core.layoutRegistry.loadFromUrls(layoutUrls);
    }

    window.Storefront.data.defaultLayoutUrls = layoutUrls;
    window.Storefront.data.loadDefaultLayouts = loadDefaultLayouts;
})();
