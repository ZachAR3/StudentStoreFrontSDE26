(function registerBuilderInspector() {
    window.Storefront.builder.updateProp = function updateProp(element, key, value) {
        return {
            ...element,
            props: {
                ...(element.props || {}),
                [key]: value
            }
        };
    };
})();
