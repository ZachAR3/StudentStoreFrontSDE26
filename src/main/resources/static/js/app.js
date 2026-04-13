document.addEventListener('alpine:init', () => {
    Alpine.data('storefrontData', () => ({
        // App State (SPA Navigation)
        currentView: 'listings', // 'listings', 'login', 'register', 'createListing'
        isLoggedIn: false, 
        
        // Data State
        posts: [],
        isLoading: true,
        errorMessage: '',
        
        // Filter/Search/Sort state
        searchQuery: '',
        selectedCategory: '',
        sortBy: 'newest', 
        
        // Forms State
        newPost: { title: '', price: null, description: '', imageUrl: '', category: 'OTHER', sellerId: 1 },
        loginForm: { email: '', password: '' },
        registerForm: { name: '', email: '', phone: '', password: '' },

        init() {
            this.fetchPosts();
        },

        // Navigation Method
        navigateTo(view) {
            this.currentView = view;
            window.scrollTo(0, 0);
            this.errorMessage = ''; // Clear any active errors
        },

        // Auth Methods (UI Only implementation for now)
        handleLogin() {
            console.log("Login attempt with:", this.loginForm.email);
            // TODO: Integrate actual backend POST /api/auth/login
            this.isLoggedIn = true;
            this.loginForm = { email: '', password: '' }; // reset form
            this.navigateTo('listings');
        },
        
        handleRegister() {
            console.log("Register attempt for:", this.registerForm.email);
            // TODO: Integrate actual backend POST /api/auth/register
            this.isLoggedIn = true;
            this.registerForm = { name: '', email: '', phone: '', password: '' }; // reset form
            this.navigateTo('listings');
        },

        handleLogout() {
            // TODO: Clear local storage JWT tokens later
            this.isLoggedIn = false;
            this.navigateTo('listings');
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
            if (!this.newPost.imageUrl || this.newPost.imageUrl.trim() === '') return ['Image URL is required'];
            try { new URL(this.newPost.imageUrl); } catch (e) { return ['Image URL must be valid']; }
            if (!this.newPost.description || this.newPost.description.trim() === '') return ['Description is required'];
            return [];
        },

        async fetchPosts() {
            this.isLoading = true;
            this.errorMessage = '';
            try {
                // Server-side category filtering
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

            this.isLoading = true;
            try {
                // Map local single image URL to the API's array requirement
                const postData = { ...this.newPost, imageUrlList: [this.newPost.imageUrl] };
                delete postData.imageUrl;

                const response = await fetch('/api/posts', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(postData)
                });

                if (!response.ok) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || 'Failed to create item.');
                }

                const createdPost = await response.json();
                this.posts.unshift(createdPost);
                
                // Reset and navigate back to listings
                this.newPost = { title: '', price: null, description: '', imageUrl: '', category: 'OTHER', sellerId: 1 };
                this.navigateTo('listings');
                alert("Successfully published!");
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
        },

        clearFilters() {
            this.searchQuery = '';
            this.selectedCategory = '';
            this.sortBy = 'newest';
            this.fetchPosts();
        }
    }));
});