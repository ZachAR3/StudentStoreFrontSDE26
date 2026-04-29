(function registerApiClient() {
    async function readJson(response) {
        return response.json().catch(() => ({}));
    }

    async function normalizeError(response, fallbackMessage) {
        const details = await readJson(response);
        const error = new Error(details.message || fallbackMessage || "Request failed");
        error.status = response.status;
        error.details = details;
        return error;
    }

    const apiClient = {
        async request(endpoint, options = {}, token = "") {
            const headers = { ...(options.headers || {}) };
            if (!(options.body instanceof FormData) && !headers["Content-Type"]) {
                headers["Content-Type"] = "application/json";
            }
            if (token) {
                headers.Authorization = `Bearer ${token}`;
            }
            const response = await fetch(endpoint, { ...options, headers });
            return response;
        },
        readJson,
        async json(endpoint, options = {}, token = "", fallbackMessage = "Request failed") {
            const response = await apiClient.request(endpoint, options, token);
            if (!response.ok) {
                throw await normalizeError(response, fallbackMessage);
            }
            return readJson(response);
        },
        async page(endpoint, options = {}, token = "") {
            const data = await apiClient.json(endpoint, options, token, "Failed to fetch data from the server.");
            return {
                content: data.content || [],
                page: data.number ?? 0,
                totalPages: data.totalPages ?? 0,
                totalElements: data.totalElements ?? 0,
                raw: data
            };
        },
        async empty(endpoint, options = {}, token = "", fallbackMessage = "Request failed") {
            const response = await apiClient.request(endpoint, options, token);
            if (!response.ok) {
                throw await normalizeError(response, fallbackMessage);
            }
            return true;
        }
    };

    window.Storefront.core.apiClient = apiClient;
})();
