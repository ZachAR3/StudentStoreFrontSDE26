document.addEventListener('alpine:init', () => {
    Alpine.data('storefrontData', () => ({
        // App State (SPA Navigation)
        currentView: 'listings', // 'listings', 'login', 'register', 'createListing', 'linkWhatsapp', 'whatsappLogin'
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
        newPost: { title: '', price: null, description: '', imageUrl: '', category: 'OTHER' },
        loginForm: { email: '', password: '' },
        registerForm: { name: '', email: '', phoneNumber: '', password: '', confirmPassword: '' },
        
        // WhatsApp Integration State
        whatsappLinkForm: { phone: '', otp: '', step: 1 }, // step 1: phone, step 2: otp
        whatsappLogin: { token: '', status: 'PENDING', interval: null },

        init() {
            this.fetchPosts();
            // If logged in but no user data, try to fetch it or clear token
            if (this.isLoggedIn && !this.user) {
                this.handleLogout();
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
        },

        // API Helper
        async apiFetch(endpoint, options = {}) {
            const headers = {
                'Content-Type': 'application/json',
                ...options.headers
            };
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
            this.navigateTo('listings');
        },

        // WhatsApp Phase 2: Linking
        async initWhatsappLink() {
            this.isLoading = true;
            try {
                // TODO: Backend should handle sending OTP to this.whatsappLinkForm.phone
                const response = await this.apiFetch('/api/auth/whatsapp/link/init', {
                    method: 'POST',
                    body: JSON.stringify({ phoneNumber: this.whatsappLinkForm.phone })
                });
                if (!response.ok) throw new Error('Failed to send OTP');
                this.whatsappLinkForm.step = 2;
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
        },

        async verifyWhatsappLink() {
            this.isLoading = true;
            try {
                const response = await this.apiFetch('/api/auth/whatsapp/link/verify', {
                    method: 'POST',
                    body: JSON.stringify({ otp: this.whatsappLinkForm.otp })
                });
                if (!response.ok) throw new Error('Invalid OTP');
                
                // Refresh user data (phone is now linked)
                const userData = await response.json();
                this.user = userData;
                localStorage.setItem('user', JSON.stringify(userData));
                
                alert('WhatsApp successfully linked!');
                this.navigateTo('listings');
            } catch (error) {
                this.errorMessage = error.message;
            } finally {
                this.isLoading = false;
            }
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
            if (!this.newPost.imageUrl || this.newPost.imageUrl.trim() === '') return ['Image URL is required'];
            try { new URL(this.newPost.imageUrl); } catch (e) { return ['Image URL must be valid']; }
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

            this.isLoading = true;
            try {
                const postData = { 
                    ...this.newPost, 
                    imageUrlList: [this.newPost.imageUrl],
                    sellerId: this.user.sellerId 
                };
                delete postData.imageUrl;

                const response = await this.apiFetch('/api/posts', {
                    method: 'POST',
                    body: JSON.stringify(postData)
                });

                if (!response.ok) {
                    const errorData = await response.json().catch(() => ({}));
                    throw new Error(errorData.message || 'Failed to create item.');
                }

                const createdPost = await response.json();
                this.posts.unshift(createdPost);
                
                this.newPost = { title: '', price: null, description: '', imageUrl: '', category: 'OTHER' };
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