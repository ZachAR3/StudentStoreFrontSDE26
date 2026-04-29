(function registerSellerService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.sellers = {
        get(sellerId, token) {
            return api.json(`/api/sellers/${sellerId}`, {}, token, "Failed to load profile");
        },
        deleteMe(password, token) {
            return api.empty("/api/sellers/me", {
                method: "DELETE",
                body: JSON.stringify({ password })
            }, token, "Failed to delete account.");
        }
    };
})();
