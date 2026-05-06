(function registerReviewService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.reviews = {
        create(payload, token) {
            return api.json("/api/reviews", {
                method: "POST",
                body: JSON.stringify(payload)
            }, token, "Failed to submit review");
        },
        context(postId, token) {
            return api.json(`/api/reviews/context/${postId}`, {}, token, "Failed to load review");
        },
        pending(token) {
            return api.json("/api/reviews/pending", {}, token, "Failed to load pending reviews");
        },
        byProfile(sellerId, token) {
            return api.json(`/api/reviews/profile/${sellerId}`, {}, token, "Failed to load profile reviews");
        }
    };
})();
