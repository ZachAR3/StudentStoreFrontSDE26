(function registerCartStore() {
    function normalizeSeller(item) {
        const user = item?.user || {};
        return {
            userId: user.userId || item?.userId || "",
            name: user.name || item?.kitchen || "User",
            phoneNumber: user.phoneNumber || item?.phoneNumber || item?.actions?.whatsappPhone || ""
        };
    }

    function normalizeItem(item) {
        const user = normalizeSeller(item);
        return {
            id: item?.id,
            title: item?.title || "Untitled item",
            description: item?.description || "",
            price: Number(item?.price) || 0,
            quantity: 1,
            user,
            raw: item
        };
    }

    function formatCartPrice(value) {
        const amount = Number(value) || 0;
        return `$${amount % 1 === 0 ? amount.toFixed(0) : amount.toFixed(2)}`;
    }

    window.Storefront.stores.createCartStore = function createCartStore() {
        return {
            items: [],
            user: null,

            get count() {
                return this.items.reduce((total, item) => total + (Number(item.quantity) || 1), 0);
            },
            get total() {
                return this.items.reduce((total, item) => total + item.price * (Number(item.quantity) || 1), 0);
            },
            get isEmpty() {
                return this.items.length === 0;
            },

            sellerKeyFor(item) {
                const user = item?.user || {};
                return String(user.userId || user.phoneNumber || user.name || "");
            },
            canAdd(item) {
                const normalized = normalizeItem(item);
                const key = this.sellerKeyFor(normalized);
                if (!key) {
                    return { ok: false, message: "This item is missing user information." };
                }
                if (this.items.length && key !== this.sellerKeyFor({ user: this.user })) {
                    return { ok: false, message: "Selected items must come from the same user." };
                }
                return { ok: true };
            },
            isSelected(itemId) {
                return this.items.some((item) => String(item.id) === String(itemId));
            },
            add(item) {
                const normalized = normalizeItem(item);
                if (!normalized.id) {
                    return { ok: false, message: "This item cannot be selected." };
                }
                if (this.isSelected(normalized.id)) {
                    return { ok: true };
                }
                const result = this.canAdd(normalized);
                if (!result.ok) return result;
                this.items.push(normalized);
                this.user = normalized.user;
                return { ok: true };
            },
            remove(itemId) {
                this.items = this.items.filter((item) => String(item.id) !== String(itemId));
                if (!this.items.length) {
                    this.user = null;
                }
            },
            clear() {
                this.items = [];
                this.user = null;
            },
            buildMessage() {
                if (!this.items.length) return "";
                const lines = [
                    "Hi, I would like to order:",
                    "",
                    ...this.items.map((item, index) => `${index + 1}. ${item.title} - ${formatCartPrice(item.price)}`),
                    "",
                    `Total: ${formatCartPrice(this.total)}`
                ];
                return lines.join("\n");
            },
            whatsappHref() {
                if (!this.user?.phoneNumber) return "";
                const digits = this.user.phoneNumber.replace(/[^0-9]/g, "");
                if (!digits) return "";
                return `https://wa.me/${digits}?text=${encodeURIComponent(this.buildMessage())}`;
            }
        };
    };
})();
