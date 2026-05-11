(function registerFavouritesStore() {
    const storage = window.Storefront.core.storage;
    const FAVOURITES_KEY = "storefront.favourites.v1";
    const LEGACY_FAVOURITES_KEY = "favourites";

    function loadFavouriteIds() {
        const saved = storage.getJson(FAVOURITES_KEY, null);
        if (Array.isArray(saved)) {
            return saved;
        }
        return storage.getJson(LEGACY_FAVOURITES_KEY, []);
    }

    window.Storefront.stores.createFavouritesStore = function createFavouritesStore() {
        return {
            favouriteIds: new Set(loadFavouriteIds()),
            persist() {
                storage.setJson(FAVOURITES_KEY, [...this.favouriteIds]);
                storage.remove(LEGACY_FAVOURITES_KEY);
            }
        };
    };
})();
