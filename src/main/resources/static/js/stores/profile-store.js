(function registerProfileStore() {
    window.Storefront.stores.createProfileStore = function createProfileStore() {
        return {
            profileSeller: null,
            profilePosts: [],
            profileLoading: false,
            isOwnProfile: false,
            showDeleteAccountModal: false,
            deleteAccountConfirmPassword: ""
        };
    };
})();
