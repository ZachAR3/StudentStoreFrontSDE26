document.addEventListener('alpine:init', () => {
    console.log("Alpine init firing, registering storefrontData...");
    Alpine.data('storefrontData', () => ({
        posts: [],
        isLoading: true,
        errorMessage: '',
        newPost: {
            title: '',
            price: null,
            description: '',
            imageUrl: '',
            category: 'OTHER', // Default category
            sellerId: 1 // Default seller for prototype
        },

        init() {
            this.fetchPosts();
        },

        validateForm() {
            if (!this.newPost.title || this.newPost.title.trim() === '') {
                return ['Title is required'];
            }
            if (this.newPost.title.length > 100) {
                return ['Title must be up to 100 characters'];
            }

            if (!this.newPost.price) {
                return ['Price is required'];
            }
            if (this.newPost.price < 0.01) {
                return ['Price must be greater than 0'];
            }
            if (this.newPost.price > 999999.99) {
                return ['Price cannot exceed 999,999.99'];
            }

            if (!this.newPost.category) {
                return ['Category is required'];
            }

            if (!this.newPost.imageUrl || this.newPost.imageUrl.trim() === '') {
                return ['Image URL is required'];
            }
            try {
                new URL(this.newPost.imageUrl);
            } catch (e) {
                return ['Image URL must be valid'];
            }

            if (!this.newPost.description || this.newPost.description.trim() === '') {
                return ['Description is required'];
            }
            if (this.newPost.description.length < 10) {
                return ['Description must be at least 10 characters'];
            }
            if (this.newPost.description.length > 1000) {
                return ['Description must be up to 1000 characters'];
            }

            return [];
        },

        async fetchPosts() {
            console.log("Fetching posts...");
            this.isLoading = true;
            this.errorMessage = '';
            try {
                const response = await fetch('/api/posts');
                console.log("Fetch response status:", response.status);

                if (!response.ok) {
                    const text = await response.text();
                    console.error("Fetch failed:", text);
                    throw new Error('Failed to fetch listings');
                }

                const data = await response.json();
                console.log("Fetched data:", data);
                // Spring Data Page object returns items in the 'content' field
                this.posts = data.content || [];
            } catch (error) {
                console.error("Fetch error:", error);
                this.errorMessage = "Could not load listings. " + error.message;
            } finally {
                this.isLoading = false;
            }
        },

        async createPost() {
            console.log("Creating post with data:", JSON.stringify(this.newPost));

            this.errorMessage = '';

            const validationErrors = this.validateForm();
            if (validationErrors.length > 0) {
                this.errorMessage = validationErrors.join(', ');
                return;
            }

            this.isLoading = true;
            try {
                const response = await fetch('/api/posts', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(this.newPost)
                });

                console.log("Create response status:", response.status);

                if (!response.ok) {
                    let errorMsg = 'Failed to create listing';
                    try {
                        const errorData = await response.json();
                        errorMsg = errorData.message || errorMsg;
                    } catch (e) {
                        const text = await response.text();
                        errorMsg = text || errorMsg;
                    }
                    throw new Error(errorMsg);
                }

                const createdPost = await response.json();
                console.log("Created post successfully:", createdPost);

                // Add new post to local state and reset form
                if (Array.isArray(this.posts)) {
                    this.posts.unshift(createdPost);
                } else {
                    this.posts = [createdPost];
                }
                this.resetForm();
                alert("Listing posted successfully!");
            } catch (error) {
                console.error("Create error:", error);
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
        },

        resetForm() {
            this.newPost = {
                title: '',
                price: null,
                description: '',
                imageUrl: '',
                category: 'OTHER',
                sellerId: 1
            };
        }
    }));
});
