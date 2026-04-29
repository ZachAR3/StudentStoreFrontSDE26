(function registerBuilderDragDrop() {
    window.Storefront.builder.moveItem = function moveItem(items, fromIndex, toIndex) {
        const next = [...items];
        const [item] = next.splice(fromIndex, 1);
        next.splice(toIndex, 0, item);
        return next;
    };
})();
