(function registerUploadStore() {
    window.Storefront.stores.createUploadStore = function createUploadStore() {
        return {
            newPost: { title: "", price: null, description: "", category: "OTHER" },
            selectedImages: [],
            imagePreviews: [],
            dragStartIndex: null,
            uploadDragActive: false
        };
    };
})();
