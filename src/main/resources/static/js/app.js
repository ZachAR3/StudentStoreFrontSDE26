document.addEventListener('alpine:init', () => {
    Alpine.data('storefrontData', () => ({
        // App State (SPA Navigation)
        currentView: 'listings', // 'listings', 'login', 'register', 'createListing', 'whatsappLogin', 'profile', 'favourites'
        isLoggedIn: !!localStorage.getItem('token'), 
        user: JSON.parse(localStorage.getItem('user') || 'null'),
        token: localStorage.getItem('token') || '',
        
        // Data State
        posts: [],
        isLoading: true,
        errorMessage: '',
        successMessage: '',
        
        // Filter/Search/Sort state
        searchQuery: '',
        selectedCategory: '',
        sortBy: 'newest', 
        
        // Forms State
        newPost: { title: '', price: null, description: '', category: 'OTHER' },
        selectedImages: [],
        imagePreviews: [],
        dragStartIndex: null,
        loginForm: { email: '', password: '' },
        registerForm: { name: '', email: '', phoneNumber: '', password: '', confirmPassword: '' },
        
        // WhatsApp Integration State
        whatsappLogin: { token: '', status: 'PENDING', interval: null },

        // Profile State
        profileSeller: null,
        profilePosts: [],
        profileLoading: false,
        isOwnProfile: false,
        showDeleteAccountModal: false,
        deleteAccountConfirmPassword: '',

        // Favourites State (localStorage-backed, ready for backend sync)
        favouriteIds: new Set(JSON.parse(localStorage.getItem('favourites') || '[]')),

        init() {
            this.fetchPosts();
            // If logged in but no user data, try to fetch it or clear token
            if (this.isLoggedIn && !this.user) {
                this.handleLogout();
            }
            // Sync favourites on init if logged in
            if (this.isLoggedIn) { 
                this.syncFavourites(); 
            }
        },

        // Navigation Method
        navigateTo(view) {
            this.currentView = view;
            window.scrollTo(0, 0);
            this.errorMessage = '';
            this.successMessage = '';
            
            // Clean up intervals if leaving whatsappLogin
            if (view !== 'whatsappLogin' && this.whatsappLogin.interval) {
                clearInterval(this.whatsappLogin.interval);
                this.whatsappLogin.interval = null;
            }

            // Reset profile state if leaving profile
            if (view !== 'profile') {
                this.profileSeller = null;
                this.profilePosts = [];
                this.isOwnProfile = false;
                this.showDeleteAccountModal = false;
                this.deleteAccountConfirmPassword = '';
            }
        },

        // API Helper
        async apiFetch(endpoint, options = {}) {
            const headers = { ...options.headers };
            if (!(options.body instanceof FormData) && !headers['Content-Type']) {
                headers['Content-Type'] = 'application/json';
            }
            if (this.token) {
                headers['Authorization'] = `Bearer ${this.token}`;
            }
            const response = await fetch(endpoint, { ...options, headers });
            if (response.status === 401) {
                this.handleLogout();
                throw new Error('Session expired. Please log in again.');
            }
            return response;
        },

        // Auth Methods
        async handleLogin() {
            this.isLoading = true;
            this.errorMessage = '';
            try {
                const response = await fetch('/api/auth/login', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.loginForm)
                });
                
                if (!response.ok) {
                    const error = await response.json();
                    throw new Error(error.message || 'Login failed');
                }
                
                const data = await response.json();
                this.saveAuth(data);
                this.loginForm = { email: '', password: '' };
                this.syncFavourites(); // Sync favourites after login
                this.navigateTo('listings');
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
        },
        
        // Password & Form Validation Helpers
        isPasswordValid(password) {
            return /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[@$!%*?&])[A-Za-z\d@$!%*?&]{8,}$/.test(password);
        },

        passwordStrength(password) {
            let score = 0;
            if (/[a-z]/.test(password)) score++;
            if (/[A-Z]/.test(password)) score++;
            if (/\d/.test(password)) score++;
            if (/[@$!%*?&]/.test(password)) score++;
            if (password.length >= 8) score++;
            return score;
        },

        isRegisterFormValid() {
            const f = this.registerForm;
            return f.name && f.name.length >= 2 && f.name.length <= 100
                && f.email && f.email.endsWith('@constructor.university')
                && f.phoneNumber && /^\+?[0-9]{10,15}$/.test(f.phoneNumber)
                && f.password && this.isPasswordValid(f.password)
                && f.confirmPassword && f.confirmPassword === f.password;
        },

        async handleRegister() {
            this.isLoading = true;
            this.errorMessage = '';
            this.successMessage = '';

            // Client-side validation
            if (!this.isRegisterFormValid()) {
                const errors = [];
                const f = this.registerForm;
                if (!f.name || f.name.length < 2) errors.push('Name must be at least 2 characters');
                if (!f.email || !f.email.endsWith('@constructor.university')) errors.push('Must use a @constructor.university email');
                if (!f.phoneNumber || !/^\+?[0-9]{10,15}$/.test(f.phoneNumber)) errors.push('Phone must be 10-15 digits, optionally starting with +');
                if (!f.password || !this.isPasswordValid(f.password)) errors.push('Password must have 8+ chars with uppercase, lowercase, digit, and special character');
                if (f.confirmPassword !== f.password) errors.push('Passwords do not match');
                this.errorMessage = errors.join('. ');
                this.isLoading = false;
                return;
            }

            try {
                // Send only the fields the backend expects (exclude confirmPassword)
                const { confirmPassword, ...registrationData } = this.registerForm;
                const response = await fetch('/api/auth/register', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(registrationData)
                });
                
                if (!response.ok) {
                    const error = await response.json();
                    throw new Error(error.message || 'Registration failed');
                }
                
                const data = await response.json();
                this.saveAuth(data);
                this.registerForm = { name: '', email: '', phoneNumber: '', password: '', confirmPassword: '' };
                this.navigateTo('listings');
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
        },

        saveAuth(data) {
            this.token = data.token;
            this.user = data.seller;
            this.isLoggedIn = true;
            localStorage.setItem('token', data.token);
            localStorage.setItem('user', JSON.stringify(data.seller));
        },

        handleLogout() {
            this.isLoggedIn = false;
            this.user = null;
            this.token = '';
            localStorage.removeItem('token');
            localStorage.removeItem('user');
            
            // Clear local cache on logout
            this.favouriteIds = new Set();
            localStorage.removeItem('favourites');
            this.navigateTo('listings');
        },

        // WhatsApp Phase 3: Login
        async initWhatsappLogin() {
            this.isLoading = true;
            this.navigateTo('whatsappLogin');
            try {
                const response = await fetch('/api/auth/whatsapp/session', { method: 'POST' });
                if (!response.ok) throw new Error('Could not initialize session');
                const data = await response.json();
                this.whatsappLogin.sessionId = data.sessionId;
                this.whatsappLogin.qrContent = data.qrContent;
                this.whatsappLogin.status = 'PENDING';
                this.startWhatsappLoginPolling();
            } catch (error) {
                this.errorMessage = 'Could not initialize WhatsApp login.';
                this.navigateTo('login');
            } finally {
                this.isLoading = false;
            }
        },

        startWhatsappLoginPolling() {
            if (this.whatsappLogin.interval) clearInterval(this.whatsappLogin.interval);
            
            this.whatsappLogin.interval = setInterval(async () => {
                try {
                    const response = await fetch(`/api/auth/whatsapp/session/${this.whatsappLogin.sessionId}`);
                    if (response.status === 200) {
                        const data = await response.json();
                        this.whatsappLogin.status = data.status;
                        
                        if (data.status === 'COMPLETED' && data.claimToken) {
                            clearInterval(this.whatsappLogin.interval);
                            await this.claimWhatsappLogin(data.claimToken);
                        } else if (data.status === 'EXPIRED') {
                            clearInterval(this.whatsappLogin.interval);
                            this.errorMessage = 'Login session expired.';
                            this.navigateTo('login');
                        }
                    }
                } catch (e) {
                    console.error("Polling error", e);
                }
            }, 3000);
        },

        async claimWhatsappLogin(claimToken) {
            try {
                const response = await fetch('/api/auth/whatsapp/claim', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ claimToken })
                });
                if (!response.ok) throw new Error('Failed to claim login');
                const data = await response.json();
                this.saveAuth(data);
                this.navigateTo('listings');
            } catch (error) {
                this.errorMessage = error.message;
                this.navigateTo('login');
            }
        },

        // Computed property for filtering and sorting
        get filteredPosts() {
            let result = [...this.posts];

            // 1. Text Search Filtering
            if (this.searchQuery.trim() !== '') {
                const query = this.searchQuery.toLowerCase();
                result = result.filter(post => 
                    post.title.toLowerCase().includes(query) || 
                    post.description.toLowerCase().includes(query)
                );
            }

            // 2. Sorting
            switch (this.sortBy) {
                case 'newest': result.sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt)); break;
                case 'oldest': result.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt)); break;
                case 'price-asc': result.sort((a, b) => a.price - b.price); break;
                case 'price-desc': result.sort((a, b) => b.price - a.price); break;
            }

            return result;
        },

        validatePostForm() {
            if (!this.newPost.title || this.newPost.title.trim() === '') return ['Title is required'];
            if (this.newPost.title.length > 100) return ['Title must be up to 100 characters'];
            if (!this.newPost.price) return ['Price is required'];
            if (this.newPost.price < 0.01) return ['Price must be greater than 0'];
            if (!this.newPost.category) return ['Category is required'];
            if (this.selectedImages.length === 0) return ['At least one image is required'];
            if (!this.newPost.description || this.newPost.description.trim() === '') return ['Description is required'];
            return [];
        },

        async fetchPosts() {
            this.isLoading = true;
            this.errorMessage = '';
            try {
                const url = this.selectedCategory ? `/api/posts/category/${this.selectedCategory}` : '/api/posts';
                const response = await fetch(url);
                if (!response.ok) throw new Error('Failed to fetch data from the server.');
                const data = await response.json();
                this.posts = data.content || [];
            } catch (error) {
                console.error("Fetch error:", error);
                this.errorMessage = "Could not load items. " + error.message;
            } finally {
                this.isLoading = false;
            }
        },

        async createPost() {
            this.errorMessage = '';
            const validationErrors = this.validatePostForm();
            if (validationErrors.length > 0) {
                this.errorMessage = validationErrors.join(', ');
                return;
            }

            if (this.selectedImages.length === 0) {
                this.errorMessage = "Please upload at least one image.";
                return;
            }

            this.isLoading = true;
            try {
                const formData = new FormData();
                
                const postData = { 
                    title: this.newPost.title,
                    price: this.newPost.price,
                    description: this.newPost.description,
                    category: this.newPost.category,
                    sellerId: this.user.sellerId 
                };

                formData.append("post", new Blob([JSON.stringify(postData)], {
                    type: "application/json"
                }));

                this.selectedImages.forEach(file => {
                    formData.append("images", file);
                });
                
                formData.append("coverIndex", 0);

                const response = await this.apiFetch('/api/posts/upload', {
                    method: 'POST',
                    body: formData
                });

                if (!response.ok) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || 'Failed to create item.');
                }

                const createdPost = await response.json();
                this.posts.unshift(createdPost);
                
                this.newPost = { title: '', price: null, description: '', category: 'OTHER' };
                this.selectedImages = [];
                this.imagePreviews.forEach(URL.revokeObjectURL);
                this.imagePreviews = [];
                this.dragStartIndex = null;
                
                this.navigateTo('listings');
                alert("Successfully published!");
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
        },

        // Image Handling Methods
        handleFileSelect(event) {
            const files = Array.from(event.target.files);
            this.addFiles(files);
            event.target.value = ''; // Reset input
        },

        handleDrop(event) {
            const files = Array.from(event.dataTransfer.files);
            this.addFiles(files);
        },

        addFiles(files) {
            const imageFiles = files.filter(f => f.type.startsWith('image/'));
            
            if (this.selectedImages.length + imageFiles.length > 10) {
                alert("You can only upload up to 10 images.");
                return;
            }

            imageFiles.forEach(file => {
                this.selectedImages.push(file);
                this.imagePreviews.push(URL.createObjectURL(file));
            });
        },

        removeImage(index) {
            URL.revokeObjectURL(this.imagePreviews[index]);
            this.selectedImages.splice(index, 1);
            this.imagePreviews.splice(index, 1);
        },

        handleImageDrop(dropIndex) {
            if (this.dragStartIndex === null || this.dragStartIndex === dropIndex) return;

            const file = this.selectedImages.splice(this.dragStartIndex, 1)[0];
            const preview = this.imagePreviews.splice(this.dragStartIndex, 1)[0];

            this.selectedImages.splice(dropIndex, 0, file);
            this.imagePreviews.splice(dropIndex, 0, preview);
            this.dragStartIndex = null;
        },

        clearFilters() {
            this.searchQuery = '';
            this.selectedCategory = '';
            this.sortBy = 'newest';
            this.fetchPosts();
        },

        // ==========================================
        // Profile Methods
        // ==========================================

        async viewProfile(sellerId) {
            this.navigateTo('profile');
            this.profileLoading = true;
            this.isOwnProfile = this.isLoggedIn && this.user && this.user.sellerId === sellerId;

            try {
                // Fetch seller details
                const sellerRes = await this.apiFetch(`/api/sellers/${sellerId}`);
                if (!sellerRes.ok) throw new Error('Failed to load profile');
                this.profileSeller = await sellerRes.json();

                // Fetch seller's posts
                const postsRes = await fetch(`/api/posts/seller/${sellerId}`);
                if (!postsRes.ok) throw new Error('Failed to load listings');
                const postsData = await postsRes.json();
                this.profilePosts = postsData.content || [];
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.profileLoading = false;
            }
        },

        async deleteProfilePost(postId) {
            if (!confirm('Are you sure you want to delete this listing? This cannot be undone.')) return;
            try {
                const response = await this.apiFetch(`/api/posts/${postId}`, { method: 'DELETE' });
                if (!response.ok) throw new Error('Failed to delete listing');
                this.profilePosts = this.profilePosts.filter(p => p.postId !== postId);
                // Also remove from the main posts array if present
                this.posts = this.posts.filter(p => p.postId !== postId);
                this.successMessage = 'Listing deleted successfully.';
            } catch (error) {
                this.errorMessage = error.message;
            }
        },

        async markProfilePostSold(postId) {
            try {
                const response = await this.apiFetch(`/api/posts/${postId}/mark-sold`, { method: 'PATCH' });
                if (!response.ok) throw new Error('Failed to mark as sold');
                const updated = await response.json();
                this.profilePosts = this.profilePosts.map(p => p.postId === postId ? updated : p);
                // Also update in the main posts array
                this.posts = this.posts.map(p => p.postId === postId ? updated : p);
                this.successMessage = 'Listing marked as sold!';
            } catch (error) {
                this.errorMessage = error.message;
            }
        },

        async deleteAccount() {
            if (!this.profileSeller) return;
            if (!this.deleteAccountConfirmPassword) {
                this.errorMessage = 'Please enter your password to confirm.';
                return;
            }
            try {
                const response = await this.apiFetch('/api/sellers/me', {
                    method: 'DELETE',
                    body: JSON.stringify({ password: this.deleteAccountConfirmPassword })
                });
                if (!response.ok) {
                    const errData = await response.json().catch(() => ({}));
                    throw new Error(errData.message || 'Failed to delete account.');
                }
                alert('Your account has been deleted.');
                this.handleLogout();
            } catch (error) {
                this.errorMessage = error.message;
                this.showDeleteAccountModal = false;
            }
        },

        getInitials(name) {
            if (!name) return '?';
            return name.split(' ').map(n => n[0]).join('').toUpperCase().substring(0, 2);
        },

        getProfileAvatarColor(sellerId) {
            const colors = [
                '#6366f1', '#8b5cf6', '#a855f7', '#d946ef',
                '#ec4899', '#f43f5e', '#ef4444', '#f97316',
                '#eab308', '#22c55e', '#14b8a6', '#06b6d4',
                '#3b82f6', '#6366f1'
            ];
            return colors[(sellerId || 0) % colors.length];
        },

        // ==========================================
        // Favourites Methods
        // ==========================================

        /**
         * Toggle a post's favourite status.
         * Updates UI immediately for snappiness, then syncs with API.
         */
        async toggleFavourite(postId) {
            if (!this.isLoggedIn) {
                this.navigateTo('login');
                this.errorMessage = "Please log in to save favourites.";
                return;
            }
            
            const isRemoving = this.favouriteIds.has(postId);
            
            // Optimistic update
            if (isRemoving) {
                this.favouriteIds.delete(postId);
            } else {
                this.favouriteIds.add(postId);
            }
            this._persistFavourites();

            // API Sync
            try {
                const method = isRemoving ? 'DELETE' : 'POST';
                const res = await this.apiFetch(`/api/favourites/${postId}`, { method });
                if (!res.ok) throw new Error('Sync failed');
            } catch (e) {
                console.error('Failed to sync favourite with server', e);
                // Revert on failure
                if (isRemoving) this.favouriteIds.add(postId);
                else this.favouriteIds.delete(postId);
                this._persistFavourites();
            }
        },

        /** Check if a post is favourited. */
        isFavourite(postId) {
            return this.favouriteIds.has(postId);
        },

        /** Favourites count for badge display. */
        get favouriteCount() {
            return this.favouriteIds.size;
        },

        /** Get favourited posts from the loaded posts array. */
        get favouritePosts() {
            return this.posts.filter(p => this.favouriteIds.has(p.postId));
        },

        /** Remove a favourite (used from the favourites view). */
        async removeFavourite(postId) {
            this.favouriteIds.delete(postId);
            this._persistFavourites();
            
            try {
                await this.apiFetch(`/api/favourites/${postId}`, { method: 'DELETE' });
            } catch (e) {
                console.error('Failed to remove favourite on server', e);
                // Revert on failure
                this.favouriteIds.add(postId);
                this._persistFavourites();
            }
        },

        /** Persist favourites to localStorage. */
        _persistFavourites() {
            localStorage.setItem('favourites', JSON.stringify([...this.favouriteIds]));
        },

        /**
         * Sync local favourites with the backend.
         */
        async syncFavourites() {
            if (!this.isLoggedIn) return;
            try {
                const res = await this.apiFetch('/api/favourites');
                if (res.ok) {
                    const postIds = await res.json();
                    this.favouriteIds = new Set(postIds);
                    this._persistFavourites();
                }
            } catch (e) {
                console.warn('Favourites sync failed, using local data', e);
            }
        }
    }));
});