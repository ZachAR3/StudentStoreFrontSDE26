(function registerSampleData() {
    const samplePosts = [
        {
            postId: 9001,
            title: "Desk Lamp",
            description: "Warm LED lamp with adjustable neck, ideal for late-night study sessions.",
            category: "FURNITURE",
            price: 18,
            mediaUrls: ["https://placehold.co/800x600?text=Desk+Lamp"],
            isSold: false,
            createdAt: "2026-03-18T10:30:00.000Z",
            seller: { sellerId: 51, name: "Mila Kramer", email: "mila@constructor.university", phoneNumber: "+491761234567" }
        },
        {
            postId: 9002,
            title: "Algorithms Textbook",
            description: "Clean copy with notes removed. Good for CS foundations and interview prep.",
            category: "BOOKS",
            price: 22,
            mediaUrls: [
                "https://placehold.co/800x600?text=Algorithms+Book",
                "https://placehold.co/800x600?text=Inside+Pages"
            ],
            isSold: false,
            createdAt: "2026-03-24T09:15:00.000Z",
            seller: { sellerId: 52, name: "Nils Weber", email: "nils@constructor.university", phoneNumber: "+491751112233" }
        },
        {
            postId: 9003,
            title: "Campus Bike",
            description: "Recently serviced city bike with lock and lights included.",
            category: "SPORTS",
            price: 95,
            mediaUrls: ["https://placehold.co/800x600?text=Campus+Bike"],
            isSold: true,
            createdAt: "2026-03-10T17:45:00.000Z",
            seller: { sellerId: 53, name: "Amira Hassan", email: "amira@constructor.university", phoneNumber: "" }
        }
    ];

    const restaurantSeller = {
        sellerId: "north-hall-kitchen",
        phoneNumber: "+491234567890"
    };

    const restaurantMenu = [
        {
            id: "ramen-miso",
            title: "Miso Ramen",
            description: "Rich broth, noodles, mushrooms, bok choy, marinated egg.",
            category: "Bowls",
            price: 12.5,
            kitchen: "North Hall Kitchen",
            ...restaurantSeller,
            imageUrl: "https://images.unsplash.com/photo-1557872943-16a5ac26437e?auto=format&fit=crop&w=900&q=80",
            availableFrom: "2026-04-01T10:00:00.000Z"
        },
        {
            id: "brioche-toast",
            title: "Brioche French Toast",
            description: "Citrus cream, berries, toasted almonds.",
            category: "Brunch",
            price: 9.5,
            kitchen: "North Hall Kitchen",
            ...restaurantSeller,
            imageUrl: "https://images.unsplash.com/photo-1484723091739-30a097e8f929?auto=format&fit=crop&w=900&q=80",
            availableFrom: "2026-04-01T08:00:00.000Z"
        },
        {
            id: "falafel-wrap",
            title: "Falafel Wrap",
            description: "Crisp falafel, pickled cucumber, herbs, tahini, warm flatbread.",
            category: "Lunch",
            price: 8.9,
            kitchen: "North Hall Kitchen",
            ...restaurantSeller,
            imageUrl: "https://images.unsplash.com/photo-1604909052743-94e838986d24?auto=format&fit=crop&w=900&q=80",
            availableFrom: "2026-04-01T11:00:00.000Z"
        },
        {
            id: "berry-yogurt",
            title: "Berry Yogurt Bowl",
            description: "Greek yogurt, berry compote, granola, pumpkin seeds, local honey.",
            category: "Breakfast",
            price: 7.4,
            kitchen: "North Hall Kitchen",
            ...restaurantSeller,
            imageUrl: "https://images.unsplash.com/photo-1511690743698-d9d85f2fbf38?auto=format&fit=crop&w=900&q=80",
            availableFrom: "2026-04-01T08:00:00.000Z"
        }
    ];

    const adapters = window.Storefront.core.adapters;

    window.Storefront.data.samplePosts = samplePosts;
    window.Storefront.data.sampleCatalogItems = samplePosts.map(adapters.catalogItemFromPost);
    window.Storefront.data.sampleProfileSeller = samplePosts[1].seller;
    window.Storefront.data.sampleProfileCatalogItems = samplePosts
        .filter((post) => post.seller.sellerId === samplePosts[1].seller.sellerId || post.postId === 9001)
        .map((post) => adapters.catalogItemFromPost({
            ...post,
            seller: samplePosts[1].seller
        }));
    window.Storefront.data.restaurantSampleMenu = restaurantMenu;
    window.Storefront.data.restaurantCatalogItems = restaurantMenu.map(adapters.restaurantMenuItemFromSample);
})();
