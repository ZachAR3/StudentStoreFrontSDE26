(function registerProfileStore() {
    window.Storefront.stores.createProfileStore = function createProfileStore() {
        return {
            profileUser: null,
            profilePosts: [],
            profileReviews: null,
            pendingReviews: [],
            profileLoading: false,
            isOwnProfile: false,
            showDeleteAccountModal: false,
            deleteAccountConfirmPassword: "",
            soldModalPostId: null,
            buyerSearchQuery: "",
            buyerSearchResults: [],
            selectedBuyer: null,
            buyerSearchLoading: false,
            showReviewModal: false,
            reviewContext: null,
            reviewForm: { rating: 5, comment: "" }
        };
    };
})();
