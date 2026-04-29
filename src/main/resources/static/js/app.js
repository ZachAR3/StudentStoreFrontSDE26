document.addEventListener("alpine:init", () => {
    const adapters = window.Storefront.core.adapters;
    const router = window.Storefront.core.router;
    const storage = window.Storefront.core.storage;
    const services = window.Storefront.services;

    const deepClone = (value) => JSON.parse(JSON.stringify(value));

    function replaceElementInLayout(layout, elementId, updater) {
        const next = deepClone(layout);
        next.regions = next.regions.map((region) => ({
            ...region,
            elements: region.elements.map((element) =>
                element.id === elementId ? updater(element) : element
            )
        }));
        return next;
    }

    Alpine.data("storefrontData", () => {
        const auth = window.Storefront.stores.createAuthStore();
        const marketplace = window.Storefront.stores.createMarketplaceStore();
        const favourites = window.Storefront.stores.createFavouritesStore();
        const profile = window.Storefront.stores.createProfileStore();
        const upload = window.Storefront.stores.createUploadStore();
        const builder = window.Storefront.stores.createLayoutBuilderStore();
        const cart = window.Storefront.stores.createCartStore();

        const app = {
            currentView: "listings",
            isLoading: true,
            layoutsReady: false,
            errorMessage: "",
            successMessage: "",
            confirmDeletePostId: null,
            headerProps: window.Storefront.core.elementRegistry.getDefaults("shell.header"),
            auth,
            marketplace,
            favourites,
            profile,
            upload,
            builder,
            cart,
            builderDrag: { regionId: "", index: -1 },
            builderRegionDragIndex: -1,
            builderPaletteDragType: "",

            async init() {
                this.auth.onUnauthorized = () => this.handleLogout();
                try {
                    await window.Storefront.data.loadDefaultLayouts();
                    this.layoutsReady = true;
                    await this.fetchPosts();
                    if (this.auth.isLoggedIn && !this.auth.user) {
                        this.handleLogout();
                    }
                    if (this.auth.isLoggedIn) {
                        this.syncFavourites();
                    }
                    const resetToken = new URLSearchParams(window.location.search).get("token");
                    if (resetToken) {
                        this.auth.resetPasswordForm.token = resetToken;
                        this.navigateTo("resetPassword");
                        window.history.replaceState({}, document.title, "/");
                    }
                    this.initializeBuilder();
                } catch (error) {
                    this.layoutsReady = false;
                    this.setError(error.message || "Unable to initialize the storefront.");
                } finally {
                    this.isLoading = false;
                }
            },

            get isLoggedIn() {
                return this.auth.isLoggedIn;
            },
            get user() {
                return this.auth.user;
            },
            get favouriteCount() {
                return this.favourites.favouriteIds.size;
            },
            get filteredPosts() {
                return this.marketplace.filteredPosts;
            },
            get filteredCatalogItems() {
                return this.filteredPosts.map(adapters.catalogItemFromPost);
            },
            get favouritePosts() {
                return this.marketplace.posts.filter((post) => this.favourites.favouriteIds.has(post.postId));
            },
            get favouriteCatalogItems() {
                return this.favouritePosts.map(adapters.catalogItemFromPost);
            },
            get profileCatalogItems() {
                return this.profile.profilePosts.map(adapters.catalogItemFromPost);
            },
            get currentLayout() {
                if (this.currentView === "createdSitePreview") {
                    const site = this.activeCreatedSite;
                    return site ? window.Storefront.core.layoutRuntime.renderLayout(site.layout, this, { useSampleData: true }) : null;
                }
                if (!router.isLayoutRoute(this.currentView)) {
                    return null;
                }
                if (!this.layoutsReady) {
                    return null;
                }
                const layout = window.Storefront.core.layoutRegistry.getByRoute(this.currentView);
                return layout ? window.Storefront.core.layoutRuntime.renderLayout(layout, this) : null;
            },
            get activeCreatedSite() {
                return this.builder.createdSites.find((site) => site.id === this.builder.activeCreatedSiteId) || null;
            },
            get builderPreviewLayout() {
                if (!this.builder.draftLayout) return null;
                return window.Storefront.core.layoutRuntime.renderLayout(
                    this.builder.draftLayout,
                    this,
                    { builderLayout: this.builder.draftLayout, useSampleData: this.builder.useSampleData }
                );
            },
            get builderPaletteGroups() {
                return window.Storefront.builder.groupPalette();
            },
            get selectedBuilderElement() {
                if (!this.builder.draftLayout || !this.builder.selectedElementId) return null;
                for (const region of this.builder.draftLayout.regions) {
                    const match = region.elements.find((element) => element.id === this.builder.selectedElementId);
                    if (match) return match;
                }
                return null;
            },
            get selectedBuilderDefinition() {
                return this.selectedBuilderElement
                    ? window.Storefront.core.elementRegistry.get(this.selectedBuilderElement.type)
                    : null;
            },

            clearMessages() {
                this.errorMessage = "";
                this.successMessage = "";
            },
            setError(message) {
                this.errorMessage = message;
                this.successMessage = "";
            },
            setSuccess(message) {
                this.successMessage = message;
                this.errorMessage = "";
            },

            navigateTo(view) {
                this.currentView = view;
                this.clearMessages();
                window.scrollTo(0, 0);
                if (view !== "whatsappLogin" && this.auth.whatsappLogin.interval) {
                    clearInterval(this.auth.whatsappLogin.interval);
                    this.auth.whatsappLogin.interval = null;
                }
                if (view !== "profile") {
                    this.profile.profileSeller = null;
                    this.profile.profilePosts = [];
                    this.profile.isOwnProfile = false;
                    this.profile.showDeleteAccountModal = false;
                    this.profile.deleteAccountConfirmPassword = "";
                    this.confirmDeletePostId = null;
                }
                if (view === "layoutBuilder") {
                    this.initializeBuilder();
                }
                if (view === "createdSites") {
                    this.loadCreatedSites();
                }
            },

            async apiFetch(endpoint, options = {}) {
                const response = await this.auth.apiFetch(endpoint, options);
                if (response.status === 401) {
                    this.handleLogout();
                    throw new Error("Session expired. Please log in again.");
                }
                return response;
            },
            rethrowUnauthorized(error) {
                if (error.status === 401) {
                    this.handleLogout();
                    throw new Error("Session expired. Please log in again.");
                }
                throw error;
            },

            isPasswordValid(password) {
                return /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/.test(password);
            },
            passwordStrength(password) {
                let score = 0;
                if (/[a-z]/.test(password)) score++;
                if (/[A-Z]/.test(password)) score++;
                if (/\d/.test(password)) score++;
                if (/[@$!%*?&]/.test(password)) score++;
                if ((password || "").length >= 8) score++;
                return score;
            },
            isRegisterFormValid() {
                const f = this.auth.registerForm;
                return f.name && f.name.length >= 2 && f.name.length <= 100 &&
                    f.email && f.email.endsWith("@constructor.university") &&
                    f.phoneNumber && /^\+?[0-9]{10,15}$/.test(f.phoneNumber) &&
                    f.password && this.isPasswordValid(f.password) &&
                    f.confirmPassword === f.password;
            },

            async handleLogin() {
                this.isLoading = true;
                this.clearMessages();
                try {
                    const data = await services.auth.login(this.auth.loginForm);
                    this.auth.saveAuth(data);
                    this.auth.loginForm = { email: "", password: "" };
                    await this.syncFavourites();
                    this.navigateTo("listings");
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            async handleRegister() {
                this.isLoading = true;
                this.clearMessages();
                if (!this.isRegisterFormValid()) {
                    this.setError("Please check your name, Constructor University email, phone number, and password requirements.");
                    this.isLoading = false;
                    return;
                }
                try {
                    const { confirmPassword, ...registrationData } = this.auth.registerForm;
                    const data = await services.auth.register(registrationData);
                    this.auth.saveAuth(data);
                    this.auth.registerForm = { name: "", email: "", phoneNumber: "", password: "", confirmPassword: "" };
                    this.navigateTo("listings");
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            handleLogout() {
                this.auth.clearAuth();
                this.favourites.favouriteIds = new Set();
                storage.remove("favourites");
                this.navigateTo("listings");
            },

            async handleForgotPassword() {
                this.isLoading = true;
                this.clearMessages();
                try {
                    await services.auth.requestPasswordReset(this.auth.forgotPasswordForm);
                    this.setSuccess("If that email exists, a reset link has been sent. Check your inbox.");
                    this.auth.forgotPasswordForm = { email: "" };
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            async handleResetPassword() {
                if (this.auth.resetPasswordForm.newPassword !== this.auth.resetPasswordForm.confirmPassword) {
                    this.setError("Passwords do not match");
                    return;
                }
                if (!this.isPasswordValid(this.auth.resetPasswordForm.newPassword)) {
                    this.setError("Password must have 8+ chars with uppercase, lowercase, digit, and special character");
                    return;
                }
                this.isLoading = true;
                this.clearMessages();
                try {
                    await services.auth.resetPassword({
                        token: this.auth.resetPasswordForm.token,
                        newPassword: this.auth.resetPasswordForm.newPassword
                    });
                    this.setSuccess("Password reset successfully. Redirecting to login...");
                    this.auth.resetPasswordForm = { token: "", newPassword: "", confirmPassword: "" };
                    setTimeout(() => this.navigateTo("login"), 2000);
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            async initWhatsappLogin() {
                this.isLoading = true;
                this.navigateTo("whatsappLogin");
                try {
                    const data = await services.whatsappLogin.createSession();
                    this.auth.whatsappLogin.sessionId = data.sessionId;
                    this.auth.whatsappLogin.qrContent = data.qrContent;
                    this.auth.whatsappLogin.status = "PENDING";
                    this.startWhatsappLoginPolling();
                } catch (_error) {
                    this.setError("Could not initialize WhatsApp login.");
                    this.navigateTo("login");
                } finally {
                    this.isLoading = false;
                }
            },

            startWhatsappLoginPolling() {
                if (this.auth.whatsappLogin.interval) clearInterval(this.auth.whatsappLogin.interval);
                this.auth.whatsappLogin.interval = setInterval(async () => {
                    try {
                        const data = await services.whatsappLogin.getSession(this.auth.whatsappLogin.sessionId);
                        this.auth.whatsappLogin.status = data.status;
                        if (data.status === "COMPLETED" && data.claimToken) {
                            clearInterval(this.auth.whatsappLogin.interval);
                            await this.claimWhatsappLogin(data.claimToken);
                        } else if (data.status === "EXPIRED") {
                            clearInterval(this.auth.whatsappLogin.interval);
                            this.setError("Login session expired.");
                            this.navigateTo("login");
                        }
                    } catch (_error) {
                        return null;
                    }
                    return null;
                }, 3000);
            },

            async claimWhatsappLogin(claimToken) {
                try {
                    const data = await services.whatsappLogin.claim(claimToken);
                    this.auth.saveAuth(data);
                    this.navigateTo("listings");
                } catch (error) {
                    this.setError(error.message);
                    this.navigateTo("login");
                }
            },

            async fetchPosts() {
                this.isLoading = true;
                this.clearMessages();
                try {
                    const data = await services.posts.list(this.marketplace.selectedCategory);
                    this.marketplace.posts = data.content;
                } catch (error) {
                    this.setError(`Could not load items. ${error.message}`);
                } finally {
                    this.isLoading = false;
                }
            },

            clearFilters() {
                this.marketplace.searchQuery = "";
                this.marketplace.selectedCategory = "";
                this.marketplace.sortBy = "newest";
                this.fetchPosts();
            },

            validatePostForm() {
                const post = this.upload.newPost;
                const errors = [];
                if (!post.title?.trim()) errors.push("Title is required");
                if (post.title && post.title.length > 100) errors.push("Title must be up to 100 characters");
                if (!post.price) errors.push("Price is required");
                if (post.price && post.price < 0.01) errors.push("Price must be greater than 0");
                if (!post.category) errors.push("Category is required");
                if (!post.description?.trim()) errors.push("Description is required");
                if (this.upload.selectedImages.length === 0) errors.push("At least one image is required");
                return errors;
            },

            async createPost() {
                const errors = this.validatePostForm();
                if (errors.length) {
                    this.setError(errors.join(". "));
                    return;
                }
                this.isLoading = true;
                this.clearMessages();
                try {
                    const formData = new FormData();
                    const postData = {
                        title: this.upload.newPost.title,
                        price: this.upload.newPost.price,
                        description: this.upload.newPost.description,
                        category: this.upload.newPost.category,
                        sellerId: this.user.sellerId
                    };
                    formData.append("post", new Blob([JSON.stringify(postData)], { type: "application/json" }));
                    this.upload.selectedImages.forEach((file) => formData.append("images", file));
                    formData.append("coverIndex", 0);
                    const createdPost = await services.posts.create(formData, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.marketplace.posts.unshift(createdPost);
                    this.resetCreateListingForm();
                    this.navigateTo("listings");
                    this.setSuccess("Successfully published.");
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            resetCreateListingForm() {
                this.upload.newPost = { title: "", price: null, description: "", category: "OTHER" };
                this.upload.selectedImages = [];
                this.upload.imagePreviews.forEach((preview) => URL.revokeObjectURL(preview));
                this.upload.imagePreviews = [];
                this.upload.dragStartIndex = null;
            },

            handleFileSelect(event) {
                const files = Array.from(event.target.files || []);
                this.addFiles(files);
                event.target.value = "";
            },
            handleDrop(event) {
                this.upload.uploadDragActive = false;
                this.addFiles(Array.from(event.dataTransfer.files || []));
            },
            addFiles(files) {
                const imageFiles = files.filter((file) => file.type.startsWith("image/"));
                if (this.upload.selectedImages.length + imageFiles.length > 10) {
                    this.setError("You can only upload up to 10 images.");
                    return;
                }
                imageFiles.forEach((file) => {
                    this.upload.selectedImages.push(file);
                    this.upload.imagePreviews.push(URL.createObjectURL(file));
                });
            },
            removeImage(index) {
                URL.revokeObjectURL(this.upload.imagePreviews[index]);
                this.upload.selectedImages.splice(index, 1);
                this.upload.imagePreviews.splice(index, 1);
            },
            handleImageDrop(dropIndex) {
                if (this.upload.dragStartIndex === null || this.upload.dragStartIndex === dropIndex) return;
                const file = this.upload.selectedImages.splice(this.upload.dragStartIndex, 1)[0];
                const preview = this.upload.imagePreviews.splice(this.upload.dragStartIndex, 1)[0];
                this.upload.selectedImages.splice(dropIndex, 0, file);
                this.upload.imagePreviews.splice(dropIndex, 0, preview);
                this.upload.dragStartIndex = null;
            },

            async viewProfile(sellerId) {
                this.navigateTo("profile");
                this.profile.profileLoading = true;
                this.profile.isOwnProfile = this.isLoggedIn && this.user?.sellerId === sellerId;
                try {
                    this.profile.profileSeller = await services.sellers.get(sellerId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    const postsData = await services.posts.bySeller(sellerId);
                    this.profile.profilePosts = postsData.content;
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.profile.profileLoading = false;
                }
            },

            promptDeletePost(postId) {
                this.confirmDeletePostId = postId;
            },
            cancelDeletePost() {
                this.confirmDeletePostId = null;
            },
            async deleteProfilePost(postId) {
                try {
                    await services.posts.delete(postId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.profile.profilePosts = this.profile.profilePosts.filter((post) => post.postId !== postId);
                    this.marketplace.posts = this.marketplace.posts.filter((post) => post.postId !== postId);
                    this.confirmDeletePostId = null;
                    this.setSuccess("Listing deleted successfully.");
                } catch (error) {
                    this.setError(error.message);
                }
            },
            async markProfilePostSold(postId) {
                try {
                    const updated = await services.posts.markSold(postId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.profile.profilePosts = this.profile.profilePosts.map((post) => post.postId === postId ? updated : post);
                    this.marketplace.posts = this.marketplace.posts.map((post) => post.postId === postId ? updated : post);
                    this.setSuccess("Listing marked as sold.");
                } catch (error) {
                    this.setError(error.message);
                }
            },
            async deleteAccount() {
                if (!this.profile.deleteAccountConfirmPassword) {
                    this.setError("Please enter your password to confirm.");
                    return;
                }
                try {
                    await services.sellers.deleteMe(this.profile.deleteAccountConfirmPassword, this.auth.token)
                        .catch((error) => this.rethrowUnauthorized(error));
                    this.profile.showDeleteAccountModal = false;
                    this.profile.deleteAccountConfirmPassword = "";
                    this.handleLogout();
                    this.setSuccess("Your account has been deleted.");
                } catch (error) {
                    this.setError(error.message);
                }
            },
            getInitials(name) {
                if (!name) return "?";
                return name.split(" ").map((part) => part[0]).join("").slice(0, 2).toUpperCase();
            },
            getProfileAvatarColor(sellerId) {
                const colors = ["#6366f1", "#8b5cf6", "#a855f7", "#d946ef", "#ef4444", "#f97316", "#22c55e", "#06b6d4"];
                return colors[(sellerId || 0) % colors.length];
            },

            async toggleFavourite(postId) {
                if (!this.isLoggedIn) {
                    this.navigateTo("login");
                    this.setError("Please log in to save favourites.");
                    return;
                }
                const removing = this.favourites.favouriteIds.has(postId);
                if (removing) this.favourites.favouriteIds.delete(postId);
                else this.favourites.favouriteIds.add(postId);
                this.favourites.persist();
                try {
                    if (removing) await services.favourites.remove(postId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    else await services.favourites.add(postId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                } catch (_error) {
                    if (removing) this.favourites.favouriteIds.add(postId);
                    else this.favourites.favouriteIds.delete(postId);
                    this.favourites.persist();
                    this.setError("Could not sync favourites right now.");
                }
            },
            async removeFavourite(postId) {
                this.favourites.favouriteIds.delete(postId);
                this.favourites.persist();
                try {
                    await services.favourites.remove(postId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                } catch (_error) {
                    this.favourites.favouriteIds.add(postId);
                    this.favourites.persist();
                    this.setError("Could not sync favourites right now.");
                }
            },
            isFavourite(postId) {
                return this.favourites.favouriteIds.has(postId);
            },
            toggleCartItem(item) {
                if (this.cart.isSelected(item.id)) {
                    this.cart.remove(item.id);
                    return;
                }

                const result = this.cart.add(item);
                if (!result.ok) {
                    this.setError(result.message);
                }
            },
            sendCartWhatsapp() {
                const href = this.cart.whatsappHref();
                if (!href) {
                    this.setError("This seller does not have a WhatsApp number.");
                    return;
                }
                window.open(href, "_blank", "noopener");
            },
            async syncFavourites() {
                if (!this.isLoggedIn) return;
                try {
                    const postIds = await services.favourites.list(this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.favourites.favouriteIds = new Set(postIds);
                    this.favourites.persist();
                } catch (_error) {
                    return null;
                }
                return null;
            },

            initializeBuilder() {
                this.loadCreatedSites();
                const stored = this.builder.loadDraft();
                if (stored) {
                    this.builder.draftLayout = stored;
                    this.builder.selectedLayoutId = stored.id;
                } else {
                    this.resetBuilderDraft();
                }
                this.builder.previewWidth = window.Storefront.builder.previewWidths.desktop;
                this.builder.previewMode = "desktop";
                this.builder.previewZoom = 1;
                this.builder.newSiteName = this.builder.newSiteName || this.builder.draftLayout?.label || "";
                this.builder.selectedRegionId = this.builder.draftLayout?.regions?.[0]?.id || "";
                this.builder.selectedElementId = this.builder.draftLayout?.regions?.[0]?.elements?.[0]?.id || "";
                this.builder.importExportText = JSON.stringify(this.builder.draftLayout, null, 2);
                this.validateBuilderLayout(false);
            },
            loadCreatedSites() {
                this.builder.createdSites = this.builder.loadCreatedSites();
            },
            resetBuilderDraft() {
                const base = window.Storefront.core.layoutRegistry.cloneLayout(this.builder.selectedLayoutId || "marketplace.home");
                this.builder.draftLayout = base;
                this.builder.selectedRegionId = base?.regions?.[0]?.id || "";
                this.builder.selectedElementId = base?.regions?.[0]?.elements?.[0]?.id || "";
                this.builder.newSiteName = base?.label || "";
                this.builder.importExportText = JSON.stringify(base, null, 2);
                this.builder.history = [];
                this.builder.future = [];
                this.builder.saveDraft();
            },
            selectBuilderTemplate(layoutId) {
                this.builder.selectedLayoutId = layoutId;
                this.builder.activeCreatedSiteId = "";
                this.resetBuilderDraft();
                this.validateBuilderLayout(false);
            },
            pushBuilderHistory() {
                if (!this.builder.draftLayout) return;
                this.builder.history.push(deepClone(this.builder.draftLayout));
                if (this.builder.history.length > 30) this.builder.history.shift();
                this.builder.future = [];
            },
            commitBuilderLayout(nextLayout) {
                this.builder.draftLayout = nextLayout;
                this.builder.importExportText = JSON.stringify(nextLayout, null, 2);
                this.builder.saveDraft();
                this.validateBuilderLayout(false);
            },
            undoBuilder() {
                const previous = this.builder.history.pop();
                if (!previous) return;
                this.builder.future.push(deepClone(this.builder.draftLayout));
                this.commitBuilderLayout(previous);
            },
            redoBuilder() {
                const next = this.builder.future.pop();
                if (!next) return;
                this.builder.history.push(deepClone(this.builder.draftLayout));
                this.commitBuilderLayout(next);
            },
            addBuilderElement(type) {
                if (!this.builder.draftLayout) return;
                const regionId = this.preferredBuilderRegionForType(type);
                this.addBuilderElementToRegion(type, regionId);
            },
            preferredBuilderRegionForType(type) {
                const regions = this.builder.draftLayout?.regions || [];
                if (type === "catalog.grid" || type === "restaurant.menuGrid" || type === "profile.listingList") {
                    return regions.find((region) => region.role === "main")?.id ||
                        regions.find((region) => /results|grid|list|main/i.test(region.id))?.id ||
                        this.builder.selectedRegionId ||
                        regions[0]?.id;
                }
                if (type === "profile.summary") {
                    return regions.find((region) => /summary|profile|hero|header/i.test(region.id))?.id ||
                        this.builder.selectedRegionId ||
                        regions[0]?.id;
                }
                return this.builder.selectedRegionId || regions[0]?.id;
            },
            addBuilderElementToRegion(type, regionId, insertIndex = null) {
                if (!this.builder.draftLayout) return;
                if (!regionId) return;
                const defaults = window.Storefront.core.elementRegistry.getDefaults(type);
                const next = deepClone(this.builder.draftLayout);
                const region = next.regions.find((item) => item.id === regionId);
                if (!region) return;
                this.pushBuilderHistory();
                const id = `${type.replace(/\./g, "-")}-${Date.now()}`;
                const element = { id, type, props: defaults, dataSource: this.defaultDataSourceForType(type) };
                if (Number.isInteger(insertIndex)) {
                    region.elements.splice(insertIndex, 0, element);
                } else {
                    region.elements.push(element);
                }
                this.commitBuilderLayout(next);
                this.builder.selectedRegionId = regionId;
                this.builder.selectedElementId = id;
            },
            defaultDataSourceForType(type) {
                if (type === "marketplace.filterBar") return "marketplace.filters";
                if (type === "catalog.grid") return "builder.sampleCatalog";
                if (type === "restaurant.menuGrid") return "restaurant.sampleMenu";
                if (type === "restaurant.menuHero") return "";
                if (type.startsWith("profile.")) return type === "profile.summary" ? "profile.seller" : "profile.posts";
                return "";
            },
            duplicateBuilderElement(elementId) {
                this.pushBuilderHistory();
                const next = deepClone(this.builder.draftLayout);
                next.regions.forEach((region) => {
                    const index = region.elements.findIndex((element) => element.id === elementId);
                    if (index >= 0) {
                        const duplicate = deepClone(region.elements[index]);
                        duplicate.id = `${duplicate.type.replace(/\./g, "-")}-${Date.now()}`;
                        region.elements.splice(index + 1, 0, duplicate);
                        this.builder.selectedElementId = duplicate.id;
                    }
                });
                this.commitBuilderLayout(next);
            },
            deleteBuilderElement(elementId) {
                this.pushBuilderHistory();
                const next = deepClone(this.builder.draftLayout);
                next.regions.forEach((region) => {
                    region.elements = region.elements.filter((element) => element.id !== elementId);
                });
                this.commitBuilderLayout(next);
                this.builder.selectedElementId = next.regions[0]?.elements[0]?.id || "";
            },
            selectBuilderRegion(regionId) {
                this.builder.selectedRegionId = regionId;
            },
            selectBuilderElement(elementId) {
                this.builder.selectedElementId = elementId;
            },
            moveBuilderElement(regionId, fromIndex, toIndex) {
                if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0) return;
                this.pushBuilderHistory();
                const next = deepClone(this.builder.draftLayout);
                const region = next.regions.find((item) => item.id === regionId);
                if (!region) return;
                region.elements = window.Storefront.builder.moveItem(region.elements, fromIndex, toIndex);
                this.commitBuilderLayout(next);
            },
            startBuilderDrag(regionId, index) {
                this.builderDrag = { regionId, index };
                this.builderPaletteDragType = "";
            },
            dropBuilderElement(regionId, index) {
                if (this.builderPaletteDragType) {
                    this.addBuilderElementToRegion(this.builderPaletteDragType, regionId, index);
                    this.builderPaletteDragType = "";
                    return;
                }
                if (this.builderDrag.regionId && this.builderDrag.regionId !== regionId) {
                    this.moveBuilderElementAcrossRegions(this.builderDrag.regionId, this.builderDrag.index, regionId, index);
                } else {
                    this.moveBuilderElement(regionId, this.builderDrag.index, index);
                }
                this.builderDrag = { regionId: "", index: -1 };
            },
            moveBuilderElementAcrossRegions(fromRegionId, fromIndex, toRegionId, toIndex) {
                if (!fromRegionId || fromIndex < 0) return;
                this.pushBuilderHistory();
                const next = deepClone(this.builder.draftLayout);
                const fromRegion = next.regions.find((item) => item.id === fromRegionId);
                const toRegion = next.regions.find((item) => item.id === toRegionId);
                if (!fromRegion || !toRegion) return;
                const [element] = fromRegion.elements.splice(fromIndex, 1);
                if (!element) return;
                toRegion.elements.splice(toIndex, 0, element);
                this.builder.selectedRegionId = toRegionId;
                this.builder.selectedElementId = element.id;
                this.commitBuilderLayout(next);
            },
            startPaletteDrag(type) {
                this.builderPaletteDragType = type;
                this.builderDrag = { regionId: "", index: -1 };
            },
            dropBuilderElementAtEnd(regionId) {
                const region = this.builder.draftLayout?.regions?.find((item) => item.id === regionId);
                this.dropBuilderElement(regionId, region?.elements?.length || 0);
            },
            moveBuilderRegion(fromIndex, toIndex) {
                if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0) return;
                this.pushBuilderHistory();
                const next = deepClone(this.builder.draftLayout);
                next.regions = window.Storefront.builder.moveItem(next.regions, fromIndex, toIndex);
                this.commitBuilderLayout(next);
            },
            startBuilderRegionDrag(index) {
                this.builderRegionDragIndex = index;
            },
            dropBuilderRegion(index) {
                this.moveBuilderRegion(this.builderRegionDragIndex, index);
                this.builderRegionDragIndex = -1;
            },
            updateBuilderProp(key, value) {
                if (!this.builder.selectedElementId) return;
                this.pushBuilderHistory();
                const next = replaceElementInLayout(this.builder.draftLayout, this.builder.selectedElementId, (element) =>
                    window.Storefront.builder.updateProp(element, key, value)
                );
                this.commitBuilderLayout(next);
            },
            validateBuilderLayout(showSuccess = true) {
                const result = window.Storefront.core.layoutRegistry.validate(this.builder.draftLayout);
                this.builder.validationErrors = result.errors;
                this.builder.warnings = [];
                this.builder.draftLayout?.regions?.forEach((region) => {
                    if (region.layout?.minItemWidth && parseFloat(region.layout.minItemWidth) < 14) {
                        this.builder.warnings.push(`Region "${region.id}" may overflow at ${region.layout.minItemWidth}.`);
                    }
                    region.elements?.forEach((element) => {
                        if (element.props?.minItemWidth && parseFloat(element.props.minItemWidth) < 14) {
                            this.builder.warnings.push(`Element "${element.id}" may overflow at ${element.props.minItemWidth}.`);
                        }
                    });
                });
                if (showSuccess && result.isValid) this.setSuccess("Layout validation passed.");
                if (showSuccess && !result.isValid) this.setError(result.errors.join(" "));
                return result.isValid;
            },
            applyBuilderPreview() {
                if (!this.validateBuilderLayout(false)) {
                    this.setError("Fix validation errors before applying the preview.");
                    return;
                }
                window.Storefront.core.layoutRegistry.register(this.builder.draftLayout);
                this.builder.saveDraft();
                this.setSuccess(`Applied preview for ${this.builder.draftLayout.route}.`);
            },
            createSiteFromDraft() {
                if (!this.builder.draftLayout) return;
                if (!this.validateBuilderLayout(false)) {
                    this.setError("Fix validation errors before creating the site.");
                    return;
                }

                const now = new Date().toISOString();
                const name = (this.builder.newSiteName || this.builder.draftLayout.label || "Untitled Site").trim();
                const existingSite = this.builder.activeCreatedSiteId
                    ? this.builder.createdSites.find((site) => site.id === this.builder.activeCreatedSiteId)
                    : null;
                const id = existingSite?.id || `site-${Date.now()}`;
                const layout = deepClone(this.builder.draftLayout);

                layout.id = `created.${id}`;
                layout.label = name;
                layout.route = "createdSitePreview";

                const site = {
                    id,
                    name,
                    context: layout.context || "custom",
                    layout,
                    createdAt: existingSite?.createdAt || now,
                    updatedAt: now
                };

                this.builder.createdSites = [
                    site,
                    ...this.builder.createdSites.filter((item) => item.id !== id)
                ];
                this.builder.activeCreatedSiteId = id;
                this.builder.newSiteName = name;
                this.builder.saveCreatedSites();
                this.setSuccess(`Created site "${name}".`);
                this.navigateTo("createdSites");
            },
            openCreatedSite(siteId) {
                this.builder.activeCreatedSiteId = siteId;
                this.navigateTo("createdSitePreview");
            },
            editCreatedSite(siteId) {
                const site = this.builder.createdSites.find((item) => item.id === siteId);
                if (!site) return;
                this.builder.activeCreatedSiteId = siteId;
                this.builder.selectedLayoutId = site.layout.id;
                this.builder.draftLayout = deepClone(site.layout);
                this.builder.newSiteName = site.name;
                this.builder.selectedRegionId = site.layout.regions?.[0]?.id || "";
                this.builder.selectedElementId = site.layout.regions?.[0]?.elements?.[0]?.id || "";
                this.builder.importExportText = JSON.stringify(this.builder.draftLayout, null, 2);
                this.builder.saveDraft();
                this.navigateTo("layoutBuilder");
            },
            duplicateCreatedSite(siteId) {
                const site = this.builder.createdSites.find((item) => item.id === siteId);
                if (!site) return;
                const now = new Date().toISOString();
                const id = `site-${Date.now()}`;
                const layout = deepClone(site.layout);
                layout.id = `created.${id}`;
                layout.label = `${site.name} Copy`;
                const copy = {
                    id,
                    name: layout.label,
                    context: site.context,
                    layout,
                    createdAt: now,
                    updatedAt: now
                };
                this.builder.createdSites = [copy, ...this.builder.createdSites];
                this.builder.saveCreatedSites();
                this.setSuccess(`Duplicated "${site.name}".`);
            },
            deleteCreatedSite(siteId) {
                const site = this.builder.createdSites.find((item) => item.id === siteId);
                this.builder.createdSites = this.builder.createdSites.filter((item) => item.id !== siteId);
                if (this.builder.activeCreatedSiteId === siteId) {
                    this.builder.activeCreatedSiteId = "";
                }
                this.builder.saveCreatedSites();
                if (site) this.setSuccess(`Deleted "${site.name}".`);
            },
            exportBuilderLayout() {
                this.builder.importExportText = JSON.stringify(this.builder.draftLayout, null, 2);
                this.setSuccess("Layout JSON refreshed for export.");
            },
            importBuilderLayout() {
                try {
                    const parsed = JSON.parse(this.builder.importExportText);
                    const result = window.Storefront.core.layoutRegistry.validate(parsed);
                    if (!result.isValid) {
                        this.builder.validationErrors = result.errors;
                        this.setError("Imported layout was rejected.");
                        return;
                    }
                    this.pushBuilderHistory();
                    this.commitBuilderLayout(parsed);
                    this.builder.selectedLayoutId = parsed.id;
                    this.builder.selectedRegionId = parsed.regions?.[0]?.id || "";
                    this.builder.selectedElementId = parsed.regions?.[0]?.elements?.[0]?.id || "";
                    this.setSuccess("Imported layout draft.");
                } catch (_error) {
                    this.setError("Imported layout was rejected.");
                }
            },
            clearBuilderDraftStorage() {
                this.builder.resetDraftStorage();
                this.resetBuilderDraft();
                this.setSuccess("Builder draft reset.");
            },
            setBuilderPreviewMode(mode) {
                this.builder.previewMode = mode;
                this.builder.previewWidth = window.Storefront.builder.previewWidths[mode] || this.builder.previewWidth;
            },
            builderPreviewStyle() {
                return {
                    "--builder-preview-width": this.builder.previewWidth,
                    "--builder-preview-zoom": this.builder.previewZoom
                };
            },

            whatsappHref(item) {
                const phone = item?.actions?.whatsappPhone || item?.seller?.phoneNumber || "";
                if (!phone) return "";
                const digits = phone.replace(/[^0-9]/g, "");
                return `https://wa.me/${digits}?text=${encodeURIComponent(`Hi, I am interested in your listing: ${item.title}`)}`;
            },
            formatPrice(value) {
                return `$${value}`;
            }
        };
        return app;
    });
});
