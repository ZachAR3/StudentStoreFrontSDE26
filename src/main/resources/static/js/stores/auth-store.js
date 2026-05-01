(function registerAuthStore() {
    const storage = window.Storefront.core.storage;

    function createAuthStore() {
        return {
            isLoggedIn: Boolean(storage.getText("token")),
            user: storage.getJson("user", null),
            token: storage.getText("token"),
            onUnauthorized: null,
            loginForm: { email: "", password: "" },
            registerForm: { name: "", email: "", phoneNumber: "", password: "", confirmPassword: "" },
            forgotPasswordForm: { email: "" },
            resetPasswordForm: { token: "", newPassword: "", confirmPassword: "" },
            verificationForm: { email: "", code: "" },
            verificationResendCooldown: 0,
            whatsappLogin: { token: "", status: "PENDING", interval: null, sessionId: "", qrContent: "" },

            saveAuth(data) {
                this.token = data.token;
                this.user = data.seller;
                this.isLoggedIn = true;
                storage.setText("token", data.token);
                storage.setJson("user", data.seller);
            },

            clearAuth() {
                this.isLoggedIn = false;
                this.user = null;
                this.token = "";
                storage.remove("token");
                storage.remove("user");
            },

            async apiFetch(endpoint, options = {}) {
                const response = await window.Storefront.core.apiClient.request(endpoint, options, this.token);
                if (response.status === 401) {
                    if (typeof this.onUnauthorized === "function") {
                        this.onUnauthorized();
                    }
                    throw new Error("Session expired. Please log in again.");
                }
                return response;
            }
        };
    }

    window.Storefront.stores.createAuthStore = createAuthStore;
})();
