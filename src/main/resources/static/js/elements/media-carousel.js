(function registerMediaCarousel() {
    window.Storefront.core.elementRegistry.register({
        type: "media.carousel",
        label: "Media Carousel",
        category: "Common",
        accepts: ["media"],
        defaultProps: {
            ratio: "4 / 3"
        },
        editor: {
            icon: "image",
            controls: [{ key: "ratio", kind: "select", options: ["1 / 1", "4 / 3", "16 / 9"] }]
        },
        component: "mediaCarousel"
    });
})();
