(function registerMarketplaceStore() {
    function sortPosts(posts, sortBy) {
        const result = [...posts];
        switch (sortBy) {
            case "oldest":
                result.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt));
                break;
            case "price-asc":
                result.sort((a, b) => a.price - b.price);
                break;
            case "price-desc":
                result.sort((a, b) => b.price - a.price);
                break;
            default:
                result.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));
        }
        return result;
    }

    window.Storefront.stores.createMarketplaceStore = function createMarketplaceStore() {
        return {
            posts: [],
            selectedCategory: "",
            searchQuery: "",
            sortBy: "newest",
            get filteredPosts() {
                const query = this.searchQuery.trim().toLowerCase();
                const posts = query
                    ? this.posts.filter((post) =>
                        String(post.title || "").toLowerCase().includes(query) ||
                        String(post.description || "").toLowerCase().includes(query))
                    : this.posts;
                return sortPosts(posts, this.sortBy);
            }
        };
    };
})();
