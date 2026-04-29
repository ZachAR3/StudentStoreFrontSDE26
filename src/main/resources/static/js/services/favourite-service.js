(function registerFavouriteService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.favourites = {
        list(token) {
            return api.json("/api/favourites", {}, token, "Failed to load favourites");
        },
        add(postId, token) {
            return api.empty(`/api/favourites/${postId}`, { method: "POST" }, token, "Failed to add favourite");
        },
        remove(postId, token) {
            return api.empty(`/api/favourites/${postId}`, { method: "DELETE" }, token, "Failed to remove favourite");
        }
    };
})();
