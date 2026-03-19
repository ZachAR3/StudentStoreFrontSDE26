document.addEventListener('alpine:init', () => {
    Alpine.data('storefrontData', () => ({
        posts: [],
        isLoading: true,
        errorMessage: '',

        // init() runs automatically when the component loads
        init() {
            this.fetchListings();
        },

        async fetchListings() {
            this.isLoading = true;
            this.errorMessage = '';
            try {
                // TODO: Uncomment the block below once the Spring Boot Postgres backend is implemented at /api/listings
                /*
                const response = await fetch('/api/listings');

                if (!response.ok) {
                    throw new Error('Network response was not ok');
                }

                this.posts = await response.json();
                */

                // Temporary Placeholder data so the site runs without the backend
                this.posts = [
                    {
                        id: 1,
                        title: "Calculus: Early Transcendentals",
                        description: "Hardcover, slightly used, perfect for MATH101.",
                        price: 55.00,
                        isSellerVerified: true,
                        sellerPhone: "491761234567"
                    },
                    {
                        id: 2,
                        title: "Ergonomic Office Chair",
                        description: "Adjustable height and lumbar support. Pickup from North Campus.",
                        price: 80.00,
                        isSellerVerified: false,
                        sellerPhone: "491769876543"
                    },
                    {
                        id: 3,
                        title: "Dell 24-inch Monitor",
                        description: "Full HD, HDMI and DisplayPort inputs. Great for a dual-monitor setup.",
                        price: 110.00,
                        isSellerVerified: true,
                        sellerPhone: "491762223334"
                    }
                ];
            } catch (error) {
                console.error("Failed to fetch listings:", error);
                this.errorMessage = "Could not load listings at this time.";
            } finally {
                this.isLoading = false;
            }
        }
    }));
});
