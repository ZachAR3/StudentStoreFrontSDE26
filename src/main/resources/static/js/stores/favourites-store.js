(function registerFavouritesStore() {
    const storage = window.Storefront.core.storage;

    window.Storefront.stores.createFavouritesStore = function createFavouritesStore() {
        return {
            favouriteIds: new Set(storage.getJson("favourites", [])),
            persist() {
                storage.setJson("favourites", [...this.favouriteIds]);
            }
        };
    };
})();
