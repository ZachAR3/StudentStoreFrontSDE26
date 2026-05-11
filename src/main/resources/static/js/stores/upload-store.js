(function registerUploadStore() {
    window.Storefront.stores.createUploadStore = function createUploadStore() {
        return {
            newPost: { title: "", price: null, description: "", category: "OTHER" },
            mediaItems: [],
            dragStartIndex: null,
            uploadDragActive: false,
            editingPostId: null,
            editorContext: {
                view: "listings",
                listingId: null,
                previousView: "listings",
                sellerId: null
            }
        };
    };
})();
