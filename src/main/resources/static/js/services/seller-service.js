(function registerSellerService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.sellers = {
        get(sellerId, token) {
            return api.json(`/api/sellers/${sellerId}`, {}, token, "Failed to load profile");
        },
        search(query, token) {
            return api.page(`/api/sellers/search?q=${encodeURIComponent(query)}&size=10`, {}, token);
        },
        deleteMe(password, token) {
            return api.empty("/api/sellers/me", {
                method: "DELETE",
                body: JSON.stringify({ password })
            }, token, "Failed to delete account.");
        }
    };
})();
