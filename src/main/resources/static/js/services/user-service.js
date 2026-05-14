(function registerUserService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.users = {
        get(userId, token) {
            return api.json(`/api/users/${userId}`, {}, token, "Failed to load profile");
        },
        search(query, token) {
            return api.page(`/api/users/search?q=${encodeURIComponent(query)}&size=10`, {}, token);
        },
        deleteMe(password, token) {
            return api.empty("/api/users/me", {
                method: "DELETE",
                body: JSON.stringify({ password })
            }, token, "Failed to delete account.");
        }
    };
})();
