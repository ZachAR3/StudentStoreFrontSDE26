(function registerSaleService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.sales = {
        pendingConfirmations(token) {
            return api.json("/api/sales/pending-confirmations", {}, token, "Failed to load pending purchases");
        },
        confirm(postId, token) {
            return api.empty(
                `/api/sales/posts/${postId}/confirm`,
                { method: "POST" },
                token,
                "Failed to confirm purchase"
            );
        },
        decline(postId, token) {
            return api.empty(`/api/sales/posts/${postId}/decline`, { method: "POST" }, token, "Failed to decline purchase");
        },
        cancel(postId, token) {
            return api.empty(`/api/sales/posts/${postId}`, { method: "DELETE" }, token, "Failed to undo sale");
        }
    };
})();
