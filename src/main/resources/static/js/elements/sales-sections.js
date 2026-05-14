(function registerSalesSections() {
    window.Storefront.core.elementRegistry.register({
        type: "common.salesHero",
        label: "Sales Hero",
        category: "Sales Site",
        accepts: [],
        defaultProps: {
            eyebrow: "Featured collection",
            title: "Everything ready for pickup",
            message: "Showcase a focused offer, menu, or product collection with clear next steps for buyers.",
            primaryAction: "Browse items",
            secondaryAction: "Customize"
        },
        editor: {
            icon: "sparkles",
            controls: [
                { key: "eyebrow", kind: "text" },
                { key: "title", kind: "text" },
                { key: "message", kind: "textarea" },
                { key: "primaryAction", kind: "text" },
                { key: "secondaryAction", kind: "text" }
            ]
        },
        component: "salesHero"
    });

    window.Storefront.core.elementRegistry.register({
        type: "common.featureStrip",
        label: "Feature Strip",
        category: "Sales Site",
        accepts: [],
        defaultProps: {
            featureOneTitle: "Fast pickup",
            featureOneText: "Coordinate collection directly with the user.",
            featureTwoTitle: "Verified campus",
            featureTwoText: "Built for Constructor University buyers and sellers.",
            featureThreeTitle: "Simple contact",
            featureThreeText: "Email or WhatsApp from each listing."
        },
        editor: {
            icon: "badge",
            controls: [
                { key: "featureOneTitle", kind: "text" },
                { key: "featureOneText", kind: "text" },
                { key: "featureTwoTitle", kind: "text" },
                { key: "featureTwoText", kind: "text" },
                { key: "featureThreeTitle", kind: "text" },
                { key: "featureThreeText", kind: "text" }
            ]
        },
        component: "featureStrip"
    });

    window.Storefront.core.elementRegistry.register({
        type: "common.contactPanel",
        label: "Contact Panel",
        category: "Sales Site",
        accepts: [],
        defaultProps: {
            eyebrow: "Questions",
            title: "Contact before pickup",
            message: "Add direct user or shop contact details for custom orders, pickup windows, and availability.",
            email: "user@constructor.university",
            whatsapp: "+491234567890"
        },
        editor: {
            icon: "message",
            controls: [
                { key: "eyebrow", kind: "text" },
                { key: "title", kind: "text" },
                { key: "message", kind: "textarea" },
                { key: "email", kind: "text" },
                { key: "whatsapp", kind: "text" }
            ]
        },
        component: "contactPanel"
    });

    window.Storefront.core.elementRegistry.register({
        type: "common.announcementBar",
        label: "Announcement Bar",
        category: "Sales Site",
        accepts: [],
        defaultProps: {
            label: "Today",
            message: "Pickup slots are open from 17:00 to 20:00."
        },
        editor: {
            icon: "megaphone",
            controls: [
                { key: "label", kind: "text" },
                { key: "message", kind: "text" }
            ]
        },
        component: "announcementBar"
    });
})();
