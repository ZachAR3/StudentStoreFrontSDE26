(function registerPostService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.posts = {
        async list(category = "") {
            const endpoint = category ? `/api/posts/category/${category}` : "/api/posts";
            return api.page(endpoint);
        },
        bySeller(sellerId) {
            return api.page(`/api/posts/seller/${sellerId}`);
        },
        create(formData, token) {
            return api.json("/api/posts/upload", { method: "POST", body: formData }, token, "Failed to create item.");
        },
        update(postId, formData, token) {
            return api.json(`/api/posts/${postId}/upload`, { method: "PUT", body: formData }, token, "Failed to update listing");
        },
        delete(postId, token) {
            return api.empty(`/api/posts/${postId}`, { method: "DELETE" }, token, "Failed to delete listing");
        },
        markSold(postId, buyerId, token) {
            return api.json(`/api/posts/${postId}/mark-sold`, {
                method: "PATCH",
                body: JSON.stringify({ buyerId })
            }, token, "Failed to mark as sold");
        }
    };
})();
