(function registerLayoutBuilderStore() {
    const storage = window.Storefront.core.storage;
    const DRAFT_KEY = "storefront.layoutBuilderDraft.v1";
    const CREATED_SITES_KEY = "storefront.createdSites.v1";

    window.Storefront.stores.createLayoutBuilderStore = function createLayoutBuilderStore() {
        return {
            draftKey: DRAFT_KEY,
            createdSitesKey: CREATED_SITES_KEY,
            selectedLayoutId: "restaurant.menu.sample",
            draftLayout: null,
            selectedElementId: "",
            newSiteName: "",
            createdSites: [],
            activeCreatedSiteId: "",
            previewWidth: "100%",
            previewMode: "desktop",
            previewZoom: 1,
            validationErrors: [],
            importExportText: "",
            history: [],
            future: [],
            useSampleData: true,
            warnings: [],
            loadDraft() {
                return storage.getJson(DRAFT_KEY, null);
            },
            saveDraft() {
                if (this.draftLayout) {
                    storage.setJson(DRAFT_KEY, this.draftLayout);
                }
            },
            resetDraftStorage() {
                storage.remove(DRAFT_KEY);
            },
            loadCreatedSites() {
                return storage.getJson(CREATED_SITES_KEY, []);
            },
            saveCreatedSites() {
                storage.setJson(CREATED_SITES_KEY, this.createdSites);
            }
        };
    };
})();
