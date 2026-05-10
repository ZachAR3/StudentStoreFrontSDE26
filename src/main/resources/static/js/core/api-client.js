(function registerApiClient() {
    async function readJson(response) {
        const text = await response.text().catch(() => "");
        if (!text) return {};
        try {
            return JSON.parse(text);
        } catch (_error) {
            return { message: text };
        }
    }

    async function normalizeError(response, fallbackMessage) {
        const details = await readJson(response);
        const messages = [];
        if (details.message) messages.push(details.message);
        if (details.error && details.error !== details.message) messages.push(details.error);
        if (Array.isArray(details.errors)) {
            details.errors.forEach((item) => {
                if (typeof item === "string") messages.push(item);
                else if (item?.message) messages.push(item.message);
                else if (item?.defaultMessage) messages.push(item.defaultMessage);
            });
        } else if (details.errors && typeof details.errors === "object") {
            Object.entries(details.errors).forEach(([field, value]) => {
                const text = Array.isArray(value) ? value.join(", ") : String(value);
                messages.push(`${field}: ${text}`);
            });
        }
        if (details.detail) messages.push(`Detail: ${details.detail}`);
        if (details.code) messages.push(`Code: ${details.code}`);

        const message = messages.length > 0
            ? messages.join(" ")
            : `${fallbackMessage || "Request failed"} (${response.status} ${response.statusText || "HTTP error"})`;
        const error = new Error(message);
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
            try {
                return await fetch(endpoint, { ...options, headers });
            } catch (error) {
                throw new Error(`Network request failed for ${endpoint}: ${error.message}`);
            }
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
