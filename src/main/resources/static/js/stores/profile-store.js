(function registerProfileStore() {
    window.Storefront.stores.createProfileStore = function createProfileStore() {
        return {
            profileUser: null,
            profilePosts: [],
            profileReviews: null,
            pendingConfirmations: [],
            pendingReviews: [],
            profileLoading: false,
            isOwnProfile: false,
            showDeleteAccountModal: false,
            deleteAccountConfirmPassword: "",
            showEditProfileModal: false,
            editProfileSaving: false,
            editProfileForm: { name: "", phoneNumber: "", email: "", password: "", confirmPassword: "", currentPassword: "" },
            emailChange: { pendingEmail: "", code: "" },
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
