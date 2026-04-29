(function registerStorage() {
    const storage = {
        getJson(key, fallback) {
            try {
                const raw = localStorage.getItem(key);
                return raw ? JSON.parse(raw) : fallback;
            } catch (_error) {
                return fallback;
            }
        },
        setJson(key, value) {
            localStorage.setItem(key, JSON.stringify(value));
        },
        getText(key, fallback = "") {
            return localStorage.getItem(key) ?? fallback;
        },
        setText(key, value) {
            localStorage.setItem(key, value);
        },
        remove(key) {
            localStorage.removeItem(key);
        }
    };

    window.Storefront.core.storage = storage;
})();
