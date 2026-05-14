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
            previousView: "listings",
            selectedListingId: null,
            selectedListingImageIndex: 0,
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
                window.addEventListener("popstate", () => {
                    this.applyBrowserRoute(this.readBrowserRoute());
                });
                try {
                    await window.Storefront.data.loadDefaultLayouts();
                    this.layoutsReady = true;
                    this.loadCreatedSites();
                    await this.fetchPosts();
                    if (this.auth.isLoggedIn && !this.auth.user) {
                        this.handleLogout();
                    }
                    if (this.auth.isLoggedIn) {
                        await this.syncFavourites();
                    }
                    const resetToken = new URLSearchParams(window.location.search).get("token");
                    if (resetToken) {
                        this.auth.resetPasswordForm.token = resetToken;
                        this.navigateTo("resetPassword");
                        window.history.replaceState({}, document.title, "/");
                    }
                    this.initializeBuilder();
                    if (window.location.hash) {
                        this.applyBrowserRoute(this.readBrowserRoute(), { replaceMissing: true });
                    } else {
                        this.syncBrowserHistory(this.currentView, { replace: true });
                    }
                    this.setupBuilderPreviewObserver();
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
            get selectedListing() {
                if (!this.selectedListingId) return null;
                const post = this.marketplace.posts.find((item) => item.postId === this.selectedListingId);
                return post ? adapters.catalogItemFromPost(post) : null;
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

            readBrowserRoute() {
                const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
                const view = params.get("view") || "listings";
                const knownViews = new Set(Object.values(router.routes));
                return {
                    view: knownViews.has(view) ? view : "listings",
                    siteId: params.get("site") || ""
                };
            },
            syncBrowserHistory(view, options = {}) {
                const params = new URLSearchParams();
                if (view && view !== "listings") params.set("view", view);
                if (view === "createdSitePreview" && this.builder.activeCreatedSiteId) {
                    params.set("site", this.builder.activeCreatedSiteId);
                }
                const nextUrl = params.toString()
                    ? `${window.location.pathname}${window.location.search}#${params.toString()}`
                    : `${window.location.pathname}${window.location.search}`;
                const state = { view, siteId: this.builder.activeCreatedSiteId || "" };
                const currentUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`;
                if (nextUrl === currentUrl) return;
                if (options.replace) window.history.replaceState(state, "", nextUrl);
                else window.history.pushState(state, "", nextUrl);
            },
            applyBrowserRoute(route, options = {}) {
                if (route?.siteId) {
                    this.builder.activeCreatedSiteId = route.siteId;
                }
                this.navigateTo(route?.view || "listings", { skipHistory: true });
                if (route?.view === "profile" && this.isLoggedIn && this.user?.userId) {
                    this.viewProfile(this.user.userId, { skipHistory: true });
                }
                if (options.replaceMissing) {
                    this.syncBrowserHistory(this.currentView, { replace: true });
                }
            },

            navigateTo(view, options = {}) {
                if (view !== this.currentView) {
                    this.previousView = this.currentView;
                }
                this.currentView = view;
                this.clearMessages();
                window.scrollTo(0, 0);
                if (view !== "listingDetail") {
                    this.selectedListingId = null;
                    this.selectedListingImageIndex = 0;
                }
                if (view !== "whatsappLogin" && this.auth.whatsappLogin.interval) {
                    clearInterval(this.auth.whatsappLogin.interval);
                    this.auth.whatsappLogin.interval = null;
                }
                if (view !== "profile") {
                    this.profile.profileUser = null;
                    this.profile.profilePosts = [];
                    this.profile.profileReviews = null;
                    this.profile.pendingReviews = [];
                    this.profile.isOwnProfile = false;
                    this.profile.showDeleteAccountModal = false;
                    this.profile.deleteAccountConfirmPassword = "";
                    this.resetSoldModal();
                    this.closeReviewModal();
                    this.confirmDeletePostId = null;
                }
                if (view === "layoutBuilder") {
                    this.initializeBuilder();
                }
                if (view === "createdSites") {
                    this.loadCreatedSites();
                }
                if (!options.skipHistory) {
                    this.syncBrowserHistory(view, { replace: options.replaceHistory });
                }
                this.refreshBuilderPreviewMetrics();
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

            passwordHasSpecialCharacter(password) {
                return /[^A-Za-z\d\s]/.test(password || "");
            },
            getPasswordRequirementMessage() {
                return "Password must be at least 8 characters and include uppercase, lowercase, a number, and a special character. Spaces are not allowed.";
            },
            hasPasswordMismatch(password, confirmPassword) {
                return Boolean(confirmPassword) && password !== confirmPassword;
            },
            isPasswordValid(password) {
                return /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z\d\s])\S{8,}$/.test(password || "");
            },
            passwordStrength(password) {
                const value = password || "";
                let score = 0;
                if (/[a-z]/.test(value)) score++;
                if (/[A-Z]/.test(value)) score++;
                if (/\d/.test(value)) score++;
                if (this.passwordHasSpecialCharacter(value)) score++;
                if (value.length >= 8) score++;
                return score;
            },
            passwordMeterBarClass(password, bar) {
                const strength = this.passwordStrength(password);
                if (strength < bar) return "";
                if (strength >= 4) return "is-success";
                if (strength >= 3) return "is-warning";
                return "is-danger";
            },
            getRegisterValidationError() {
                const f = this.auth.registerForm;
                if (!f.name || f.name.length < 2 || f.name.length > 100) {
                    return "Name must be between 2 and 100 characters.";
                }
                if (!f.email || !f.email.endsWith("@constructor.university")) {
                    return "Use your Constructor University email address.";
                }
                if (!f.phoneNumber || !/^\+?[0-9]{10,15}$/.test(f.phoneNumber)) {
                    return "Phone number must contain 10 to 15 digits and may start with +.";
                }
                if (!f.password || !this.isPasswordValid(f.password)) {
                    return this.getPasswordRequirementMessage();
                }
                if (!f.confirmPassword) {
                    return "Please confirm your password.";
                }
                if (this.hasPasswordMismatch(f.password, f.confirmPassword)) {
                    return "Passwords do not match.";
                }
                return "";
            },
            isRegisterFormValid() {
                return !this.getRegisterValidationError();
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
                this.clearMessages();
                const validationError = this.getRegisterValidationError();
                if (validationError) {
                    this.setError(validationError);
                    return;
                }
                this.isLoading = true;
                try {
                    const { confirmPassword, ...registrationData } = this.auth.registerForm;
                    const data = await services.auth.register(registrationData);
                    this.auth.verificationForm.email = data.email;
                    this.auth.verificationForm.code = "";
                    this.auth.registerForm = { name: "", email: "", phoneNumber: "", password: "", confirmPassword: "" };
                    this.navigateTo("emailVerification");
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            async handleVerifyEmail() {
                this.isLoading = true;
                this.clearMessages();
                try {
                    const data = await services.auth.verifyEmail(this.auth.verificationForm);
                    this.auth.saveAuth(data);
                    this.auth.verificationForm = { email: "", code: "" };
                    await this.syncFavourites();
                    this.navigateTo("listings");
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            async handleResendVerification() {
                this.isLoading = true;
                this.clearMessages();
                try {
                    await services.auth.resendVerification({ email: this.auth.verificationForm.email });
                    this.setSuccess("A new code has been sent to your email.");
                    this.auth.verificationResendCooldown = 60;
                    const cooldownInterval = setInterval(() => {
                        this.auth.verificationResendCooldown--;
                        if (this.auth.verificationResendCooldown <= 0) {
                            clearInterval(cooldownInterval);
                        }
                    }, 1000);
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            handleLogout() {
                this.auth.clearAuth();
                this.favourites.favouriteIds = new Set();
                storage.remove("storefront.favourites.v1");
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
                this.clearMessages();
                if (!this.auth.resetPasswordForm.confirmPassword) {
                    this.setError("Please confirm your password.");
                    return;
                }
                if (this.hasPasswordMismatch(this.auth.resetPasswordForm.newPassword, this.auth.resetPasswordForm.confirmPassword)) {
                    this.setError("Passwords do not match.");
                    return;
                }
                if (!this.isPasswordValid(this.auth.resetPasswordForm.newPassword)) {
                    this.setError(this.getPasswordRequirementMessage());
                    return;
                }
                this.isLoading = true;
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

            openListing(postId) {
                this.selectedListingId = postId;
                this.selectedListingImageIndex = 0;
                this.navigateTo("listingDetail");
            },
            isListingOwner(post) {
                return this.isLoggedIn && post?.user?.userId === this.user?.userId;
            },
            defaultListingEditorContext() {
                return {
                    view: "listings",
                    listingId: null,
                    previousView: "listings",
                    userId: null
                };
            },
            setListingEditorContext(overrides = {}) {
                this.upload.editorContext = {
                    ...this.defaultListingEditorContext(),
                    ...overrides
                };
            },
            openCreateListingForm() {
                this.resetCreateListingForm();
                this.setListingEditorContext();
                this.navigateTo("createListing");
            },
            startEditingPost(postId) {
                const post = this.marketplace.posts.find((item) => item.postId === postId) ||
                    this.profile.profilePosts.find((item) => item.postId === postId);
                if (!post) {
                    this.setError("Listing not found.");
                    return;
                }
                if (!this.isListingOwner(post)) {
                    this.setError("Only the original poster can edit this listing.");
                    return;
                }

                this.resetCreateListingForm();
                this.upload.newPost = {
                    title: post.title || "",
                    price: Number(post.price) || null,
                    description: post.description || "",
                    category: post.category || "OTHER"
                };
                this.upload.mediaItems = (post.mediaUrls || []).map((url, index) => ({
                    id: `existing-${post.postId}-${index}`,
                    kind: "existing",
                    url,
                    previewUrl: url
                }));
                this.upload.editingPostId = post.postId;
                this.setListingEditorContext(
                    this.currentView === "profile"
                        ? {
                            view: "profile",
                            userId: post.user?.userId || null
                        }
                        : {
                            view: "listingDetail",
                            listingId: post.postId,
                            previousView: this.previousView
                        }
                );
                this.navigateTo("createListing");
            },
            canOpenListingDetail(element) {
                return element?.props?.itemElement !== "restaurant.menuItemCard" &&
                    ["listings", "favourites"].includes(this.currentView);
            },
            isFullWidthElement(type) {
                return [
                    "catalog.grid",
                    "restaurant.menuGrid",
                    "restaurant.menuHero",
                    "profile.summary",
                    "profile.listingList",
                    "common.salesHero",
                    "common.featureStrip",
                    "common.contactPanel",
                    "common.announcementBar",
                    "common.emptyState"
                ].includes(type);
            },
            salesFeatureItems(props = {}) {
                return [
                    { title: props.featureOneTitle, text: props.featureOneText },
                    { title: props.featureTwoTitle, text: props.featureTwoText },
                    { title: props.featureThreeTitle, text: props.featureThreeText }
                ].filter((feature) => feature.title || feature.text);
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
                if (this.upload.mediaItems.length === 0) errors.push("At least one image is required");
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
                    const editingPostId = this.upload.editingPostId;
                    const editorContext = { ...this.upload.editorContext };
                    const formData = this.buildListingFormData();
                    const savedPost = editingPostId
                        ? await services.posts.update(editingPostId, formData, this.auth.token).catch((error) => this.rethrowUnauthorized(error))
                        : await services.posts.create(formData, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.upsertListing(savedPost);
                    this.resetCreateListingForm();
                    if (editingPostId) {
                        await this.restoreListingEditorContext(editorContext, savedPost.postId);
                        this.setSuccess("Listing updated successfully.");
                    } else {
                        this.navigateTo("listings");
                        this.setSuccess("Successfully published.");
                    }
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.isLoading = false;
                }
            },

            buildListingFormData() {
                const formData = new FormData();
                const postData = {
                    title: this.upload.newPost.title,
                    price: this.upload.newPost.price,
                    description: this.upload.newPost.description,
                    category: this.upload.newPost.category,
                    userId: this.user.userId
                };
                const newFiles = [];

                if (this.upload.editingPostId) {
                    const imageOrder = [];

                    this.upload.mediaItems.forEach((item) => {
                        if (item.kind === "existing") {
                            imageOrder.push({ kind: "existing", url: item.url });
                            return;
                        }

                        const uploadIndex = newFiles.length;
                        newFiles.push(item.file);
                        imageOrder.push({ kind: "upload", uploadIndex });
                    });

                    postData.imageOrder = imageOrder;
                } else {
                    this.upload.mediaItems.forEach((item) => {
                        if (item.kind === "upload") {
                            newFiles.push(item.file);
                        }
                    });
                }

                formData.append("post", new Blob([JSON.stringify(postData)], { type: "application/json" }));
                newFiles.forEach((file) => formData.append("images", file));
                formData.append("coverIndex", 0);
                return formData;
            },

            upsertListing(savedPost) {
                const replaceOrInsert = (items) => {
                    const existingIndex = items.findIndex((post) => post.postId === savedPost.postId);
                    if (existingIndex === -1) {
                        return [savedPost, ...items];
                    }
                    return items.map((post) => post.postId === savedPost.postId ? savedPost : post);
                };

                this.marketplace.posts = replaceOrInsert(this.marketplace.posts);

                if (this.profile.profileUser?.userId === savedPost.user?.userId || this.profile.isOwnProfile) {
                    this.profile.profilePosts = replaceOrInsert(this.profile.profilePosts);
                }
            },

            async restoreListingEditorContext(context, fallbackListingId = null) {
                const nextContext = context || this.defaultListingEditorContext();

                if (nextContext.view === "listingDetail") {
                    this.selectedListingId = nextContext.listingId || fallbackListingId;
                    this.navigateTo("listingDetail");
                    this.previousView = nextContext.previousView || "listings";
                    return;
                }

                if (nextContext.view === "profile" && nextContext.userId) {
                    await this.viewProfile(nextContext.userId);
                    return;
                }

                this.navigateTo(nextContext.view || "listings");
            },

            releaseMediaItem(item) {
                if (item?.kind === "upload" && item.previewUrl?.startsWith("blob:")) {
                    URL.revokeObjectURL(item.previewUrl);
                }
            },

            resetCreateListingForm() {
                this.upload.newPost = { title: "", price: null, description: "", category: "OTHER" };
                this.upload.mediaItems.forEach((item) => this.releaseMediaItem(item));
                this.upload.mediaItems = [];
                this.upload.dragStartIndex = null;
                this.upload.editingPostId = null;
                this.setListingEditorContext();
            },

            async cancelListingEditor() {
                const editorContext = this.upload.editingPostId
                    ? { ...this.upload.editorContext }
                    : this.defaultListingEditorContext();
                this.resetCreateListingForm();
                await this.restoreListingEditorContext(editorContext);
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
                if (this.upload.mediaItems.length + imageFiles.length > 10) {
                    this.setError("You can only upload up to 10 images.");
                    return;
                }
                imageFiles.forEach((file) => {
                    this.upload.mediaItems.push({
                        id: `upload-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
                        kind: "upload",
                        file,
                        previewUrl: URL.createObjectURL(file)
                    });
                });
            },
            removeImage(index) {
                const [removedItem] = this.upload.mediaItems.splice(index, 1);
                this.releaseMediaItem(removedItem);
            },
            handleImageDrop(dropIndex) {
                if (this.upload.dragStartIndex === null || this.upload.dragStartIndex === dropIndex) return;
                const item = this.upload.mediaItems.splice(this.upload.dragStartIndex, 1)[0];
                this.upload.mediaItems.splice(dropIndex, 0, item);
                this.upload.dragStartIndex = null;
            },

            async viewProfile(userId, options = {}) {
                this.navigateTo("profile", { skipHistory: options.skipHistory });
                this.profile.profileLoading = true;
                this.profile.isOwnProfile = this.isLoggedIn && this.user?.userId === userId;
                try {
                    this.profile.profileUser = await services.users.get(userId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    const postsData = await services.posts.bySeller(userId);
                    this.profile.profilePosts = postsData.content;
                    this.profile.profileReviews = await services.reviews.byProfile(userId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.profile.pendingReviews = this.profile.isOwnProfile
                        ? await services.reviews.pending(this.auth.token).catch((error) => this.rethrowUnauthorized(error))
                        : [];
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
            promptMarkSold(postId) {
                this.profile.soldModalPostId = postId;
                this.profile.buyerSearchQuery = "";
                this.profile.buyerSearchResults = [];
                this.profile.selectedBuyer = null;
            },
            resetSoldModal() {
                if (!this.profile) return;
                this.profile.soldModalPostId = null;
                this.profile.buyerSearchQuery = "";
                this.profile.buyerSearchResults = [];
                this.profile.selectedBuyer = null;
                this.profile.buyerSearchLoading = false;
            },
            async searchBuyerCandidates() {
                const query = this.profile.buyerSearchQuery.trim();
                this.profile.selectedBuyer = null;
                if (query.length < 2) {
                    this.profile.buyerSearchResults = [];
                    return;
                }
                this.profile.buyerSearchLoading = true;
                try {
                    const data = await services.users.search(query, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.profile.buyerSearchResults = data.content;
                } catch (error) {
                    this.setError(error.message);
                } finally {
                    this.profile.buyerSearchLoading = false;
                }
            },
            selectBuyerCandidate(buyer) {
                this.profile.selectedBuyer = buyer;
                this.profile.buyerSearchQuery = `${buyer.name}`;
                this.profile.buyerSearchResults = [];
            },
            async markProfilePostSold() {
                const postId = this.profile.soldModalPostId;
                const buyerUserId = this.profile.selectedBuyer?.userId;
                if (!postId || !buyerUserId) {
                    this.setError("Choose the registered buyer before marking this listing as sold.");
                    return;
                }
                try {
                    const updated = await services.posts.markSold(postId, buyerUserId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.profile.profilePosts = this.profile.profilePosts.map((post) => post.postId === postId ? updated : post);
                    this.marketplace.posts = this.marketplace.posts.map((post) => post.postId === postId ? updated : post);
                    this.resetSoldModal();
                    this.profile.pendingReviews = await services.reviews.pending(this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.setSuccess("Listing marked as sold. The buyer has been asked for a quick review.");
                } catch (error) {
                    this.setError(error.message);
                }
            },
            async openReviewModal(postId) {
                try {
                    const context = await services.reviews.context(postId, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    if (context.alreadyReviewed) {
                        this.setError("You already reviewed this transaction.");
                        return;
                    }
                    this.profile.reviewContext = context;
                    this.profile.reviewForm = { rating: 5, comment: "" };
                    this.profile.showReviewModal = true;
                } catch (error) {
                    this.setError(error.message);
                }
            },
            closeReviewModal() {
                if (!this.profile) return;
                this.profile.showReviewModal = false;
                this.profile.reviewContext = null;
                this.profile.reviewForm = { rating: 5, comment: "" };
            },
            async submitReview() {
                const context = this.profile.reviewContext;
                if (!context) return;
                try {
                    await services.reviews.create({
                        saleId: context.saleId,
                        rating: Number(this.profile.reviewForm.rating),
                        comment: this.profile.reviewForm.comment
                    }, this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    this.closeReviewModal();
                    if (this.profile.profileUser) {
                        this.profile.profileReviews = await services.reviews.byProfile(this.profile.profileUser.userId, this.auth.token)
                            .catch((error) => this.rethrowUnauthorized(error));
                    }
                    this.profile.pendingReviews = this.profile.isOwnProfile
                        ? await services.reviews.pending(this.auth.token).catch((error) => this.rethrowUnauthorized(error))
                        : [];
                    this.setSuccess("Review submitted. Thanks for helping keep the marketplace trustworthy.");
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
                    await services.users.deleteMe(this.profile.deleteAccountConfirmPassword, this.auth.token)
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
            getProfileAvatarColor(userId) {
                const colors = [
                    "var(--sf-avatar-1)",
                    "var(--sf-avatar-2)",
                    "var(--sf-avatar-3)",
                    "var(--sf-avatar-4)",
                    "var(--sf-avatar-5)",
                    "var(--sf-avatar-6)"
                ];
                return colors[(userId || 0) % colors.length];
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
            siteInitial(site) {
                const name = (site?.name || "Site").trim();
                return name.charAt(0).toUpperCase();
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
                    this.setError("This user does not have a WhatsApp number.");
                    return;
                }
                window.open(href, "_blank", "noopener");
            },
            async syncFavourites() {
                if (!this.isLoggedIn) return;
                try {
                    const favourites = await services.favourites.list(this.auth.token).catch((error) => this.rethrowUnauthorized(error));
                    const favouriteIds = Array.isArray(favourites)
                        ? favourites
                            .map((item) => {
                                if (typeof item === "number") return item;
                                if (item && item.postId != null) return Number(item.postId);
                                if (item && item.id != null) return Number(item.id);
                                return null;
                            })
                            .filter((item) => Number.isFinite(item))
                        : [];
                    this.favourites.favouriteIds = new Set(favouriteIds);
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
                this.$nextTick(() => this.refreshBuilderPreviewMetrics());
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
                if (type === "profile.summary" || type === "restaurant.menuHero" || type.startsWith("common.")) {
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
                if (type.startsWith("profile.")) return type === "profile.summary" ? "profile.user" : "profile.posts";
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
                this.navigateTo("createdSitePreview");
                this.setSuccess(`Created site "${name}".`);
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
                this.refreshBuilderPreviewMetrics();
            },
            setupBuilderPreviewObserver() {
                this.$nextTick(() => {
                    const previewShell = this.$refs?.builderPreviewShell;
                    if (!previewShell) return;
                    if (this.builder.previewResizeObserver) {
                        this.builder.previewResizeObserver.disconnect();
                    }
                    if (typeof ResizeObserver !== "function") {
                        this.refreshBuilderPreviewMetrics();
                        return;
                    }
                    this.builder.previewResizeObserver = new ResizeObserver(() => this.refreshBuilderPreviewMetrics());
                    this.builder.previewResizeObserver.observe(previewShell);
                    this.refreshBuilderPreviewMetrics();
                });
            },
            refreshBuilderPreviewMetrics() {
                this.$nextTick(() => {
                    const previewShell = this.$refs?.builderPreviewShell;
                    if (!previewShell) return;
                    const styles = window.getComputedStyle(previewShell);
                    const paddingInline = (parseFloat(styles.paddingLeft) || 0) + (parseFloat(styles.paddingRight) || 0);
                    this.builder.previewCanvasWidth = Math.max(0, Math.floor(previewShell.clientWidth - paddingInline));
                });
            },
            parsePreviewLength(value) {
                if (typeof value === "number" && Number.isFinite(value)) {
                    return value;
                }
                const match = String(value || "").trim().match(/^([0-9]*\.?[0-9]+)\s*(px|rem)$/);
                if (!match) return null;
                const amount = Number(match[1]);
                if (!Number.isFinite(amount)) return null;
                if (match[2] === "rem") {
                    const rootFontSize = parseFloat(window.getComputedStyle(document.documentElement).fontSize) || 16;
                    return amount * rootFontSize;
                }
                return amount;
            },
            builderPreviewStyle() {
                const availableWidth = Math.max(0, Number(this.builder.previewCanvasWidth) || 0);
                const zoom = Math.min(Math.max(Number(this.builder.previewZoom) || 1, 0.7), 1.2);
                const maxPreviewWidth = this.parsePreviewLength("72rem");
                const targetWidth = this.builder.previewMode === "desktop"
                    ? availableWidth || maxPreviewWidth
                    : this.parsePreviewLength(this.builder.previewWidth) || availableWidth || maxPreviewWidth;
                const baseWidth = Math.min(targetWidth, maxPreviewWidth, availableWidth || targetWidth);
                const frameWidth = availableWidth
                    ? Math.min(Math.round(baseWidth * zoom), availableWidth)
                    : Math.round(baseWidth * zoom);
                if (!frameWidth) {
                    return {};
                }
                return {
                    width: `${Math.max(frameWidth, 0)}px`
                };
            },

            whatsappHref(item) {
                const phone = item?.actions?.whatsappPhone || item?.user?.phoneNumber || "";
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
