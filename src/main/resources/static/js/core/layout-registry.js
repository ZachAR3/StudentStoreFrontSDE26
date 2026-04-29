(function registerLayoutRegistry() {
    const layouts = new Map();

    function clone(value) {
        return typeof structuredClone === "function"
            ? structuredClone(value)
            : JSON.parse(JSON.stringify(value));
    }

    const api = {
        register(layout) {
            layouts.set(layout.id, clone(layout));
            return api.get(layout.id);
        },
        registerMany(nextLayouts = []) {
            nextLayouts.forEach((layout) => api.register(layout));
            return api.list();
        },
        async loadFromUrls(urls = []) {
            const loadedLayouts = await Promise.all(urls.map(async (url) => {
                const response = await fetch(url, { headers: { Accept: "application/json" } });
                if (!response.ok) {
                    throw new Error(`Unable to load layout ${url}: ${response.status}`);
                }
                return response.json();
            }));
            return api.registerMany(loadedLayouts);
        },
        list() {
            return Array.from(layouts.values()).map((layout) => clone(layout));
        },
        get(id) {
            const layout = layouts.get(id);
            return layout ? clone(layout) : null;
        },
        getByRoute(route) {
            return api.list().find((layout) => layout.route === route) || null;
        },
        cloneLayout(idOrLayout) {
            if (typeof idOrLayout === "string") {
                return api.get(idOrLayout);
            }
            return clone(idOrLayout);
        },
        validate(layout) {
            return window.Storefront.core.validators.validateLayout(layout, window.Storefront.core.elementRegistry);
        }
    };

    window.Storefront.core.layoutRegistry = api;
})();
