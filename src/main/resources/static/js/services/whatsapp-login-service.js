(function registerWhatsappLoginService() {
    const api = window.Storefront.core.apiClient;

    window.Storefront.services.whatsappLogin = {
        createSession() {
            return api.json("/api/auth/whatsapp/session", { method: "POST" }, "", "Could not initialize session");
        },
        getSession(sessionId) {
            return api.json(`/api/auth/whatsapp/session/${sessionId}`, {}, "", "Could not poll session");
        },
        claim(claimToken) {
            return api.json("/api/auth/whatsapp/claim", {
                method: "POST",
                body: JSON.stringify({ claimToken })
            }, "", "Failed to claim login");
        }
    };
})();
