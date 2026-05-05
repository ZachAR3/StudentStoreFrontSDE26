(function () {
    const THEME_STORAGE_KEY = "student-storefront.theme-lab";

    function setTheme(themeName) {
        document.documentElement.dataset.theme = themeName;
        document.querySelectorAll("[data-theme-target]").forEach((button) => {
            button.classList.toggle("is-active", button.dataset.themeTarget === themeName);
        });
        window.localStorage.setItem(THEME_STORAGE_KEY, themeName);
    }

    document.addEventListener("DOMContentLoaded", () => {
        const savedTheme = window.localStorage.getItem(THEME_STORAGE_KEY) || "campus";
        setTheme(savedTheme);

        document.querySelectorAll("[data-theme-target]").forEach((button) => {
            button.addEventListener("click", () => setTheme(button.dataset.themeTarget));
        });
    });
})();
