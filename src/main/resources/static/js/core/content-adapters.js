(function registerContentAdapters() {
    const placeholder = "https://placehold.co/600x400?text=No+Image";

    function catalogItemFromPost(post) {
        return {
            id: post.postId,
            title: post.title,
            description: post.description,
            badge: post.category,
            price: post.price,
            media: (post.mediaUrls || []).map((url, index) => ({
                url,
                alt: `${post.title} photo ${index + 1}`
            })),
            status: { sold: Boolean(post.isSold) },
            seller: post.seller || null,
            createdAt: post.createdAt,
            actions: {
                favouriteId: post.postId,
                email: post.seller?.email || "",
                whatsappPhone: post.seller?.phoneNumber || ""
            },
            raw: post
        };
    }

    function restaurantMenuItemFromSample(item) {
        const seller = {
            sellerId: item.sellerId || "north-hall-kitchen",
            name: item.kitchen || "Campus Kitchen",
            phoneNumber: item.phoneNumber || item.whatsappPhone || "+491234567890"
        };

        return {
            id: item.id,
            title: item.title,
            description: item.description,
            badge: item.category,
            price: item.price,
            media: [{ url: item.imageUrl || placeholder, alt: item.title }],
            status: { sold: false },
            seller,
            createdAt: item.availableFrom || new Date().toISOString(),
            actions: {
                whatsappPhone: seller.phoneNumber
            },
            raw: item
        };
    }

    window.Storefront.core.adapters = {
        placeholder,
        catalogItemFromPost,
        restaurantMenuItemFromSample
    };
})();
