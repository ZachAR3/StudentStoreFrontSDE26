(function registerAuthService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.auth = {
        login(credentials) {
            return api.json("/api/auth/login", {
                method: "POST",
                body: JSON.stringify(credentials)
            }, "", "Login failed");
        },
        register(registrationData) {
            return api.json("/api/auth/register", {
                method: "POST",
                body: JSON.stringify(registrationData)
            }, "", "Registration failed");
        },
        requestPasswordReset(payload) {
            return api.empty("/api/auth/forgot-password", {
                method: "POST",
                body: JSON.stringify(payload)
            }, "", "Something went wrong");
        },
        resetPassword(payload) {
            return api.empty("/api/auth/reset-password", {
                method: "POST",
                body: JSON.stringify(payload)
            }, "", "Failed to reset password");
        }
    };
})();
