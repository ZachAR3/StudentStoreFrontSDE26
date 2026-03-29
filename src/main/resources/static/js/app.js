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
            this.isLoading = true;
            this.errorMessage = '';
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
