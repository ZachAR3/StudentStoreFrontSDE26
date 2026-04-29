(function registerElementRegistry() {
    const registry = new Map();

    window.Storefront.core.elementRegistry = {
        register(definition) {
            registry.set(definition.type, definition);
            return definition;
        },
        get(type) {
            return registry.get(type);
        },
        list() {
            return Array.from(registry.values());
        },
        getDefaults(type) {
            return { ...(registry.get(type)?.defaultProps || {}) };
        },
        validateProps(type, props = {}) {
            const definition = registry.get(type);
            if (!definition) {
                return { isValid: false, errors: [`Unknown element type: ${type}`] };
            }
            const errors = [];
            const controls = new Map((definition.editor?.controls || []).map((control) => [control.key, control]));
            Object.entries(props).forEach(([key, value]) => {
                const control = controls.get(key);
                if (typeof value === "string" && /<|javascript:|on\w+=/i.test(value)) {
                    errors.push(`Unsafe prop value for ${key}.`);
                    return;
                }
                if (control?.kind === "select" && !window.Storefront.core.validators.validatePropToken(value)) {
                    errors.push(`Unsupported token for ${key}: ${value}`);
                }
            });
            return { isValid: errors.length === 0, errors };
        }
    };
})();
