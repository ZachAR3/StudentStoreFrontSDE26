# Front-End Modularity, Layout Builder, and Overhaul Plan

This plan covers GitHub issues #32, #33, and #34 for the site front-end. It is based on `docs/REPOMAP.md` and the current static Alpine/Pico implementation under `src/main/resources/static`.

The implementation should avoid bot, database, and backend changes. Existing REST endpoints should remain the source of marketplace data. Any layout configuration persistence should initially be static JSON or `localStorage`; server-side layout storage is a later, explicitly separate backend feature.

## Current State

- `src/main/resources/static/index.html` is the front-end shell, view markup, and about 500 lines of inline CSS.
- `src/main/resources/static/js/app.js` is one Alpine store containing routing, auth, post fetching, image upload, profile logic, and favourites.
- `src/main/resources/static/css/custom.css` exists but is empty.
- Listings and favourites duplicate the same item-card/carousel/contact markup.
- The current "modularity" is mostly CSS class naming and comments; there is not yet a real element registry, layout schema, or reusable renderer.
- Existing backend DTOs can already support a generic catalog/listing element model: post id, title, price, description, category, media, seller, sold state, and timestamps.

## Goals By Issue

### Issue #33: Configurable Movable Elements

Create a front-end layout system where pages are assembled from typed elements. Elements must declare configurable props, supported data sources, responsive behavior, and editor metadata. The current marketplace views should migrate onto this system instead of continuing as hard-coded view-specific blocks.

### Issue #34: Layout Creation Page

Add a special front-end page for building layouts. It should support a palette of elements, drag/reorder, responsive preview widths, adaptive CSS controls, property editing, validation, reset, import/export, and `localStorage` draft persistence.

### Issue #32: Front-End Overhaul

Break up the monolithic static front-end into clean, testable front-end modules while preserving Alpine.js and Pico.css unless a separate decision is made to adopt a build system. The final shape should support reusable templates for future subapps such as restaurants without changing the backend first.

## Non-Goals

- Do not change the WhatsApp bot.
- Do not add database tables.
- Do not add backend controllers or entities for layouts in this pass.
- Do not require a JavaScript bundler for the first implementation.
- Do not allow arbitrary user-authored HTML, JavaScript, or unrestricted CSS in layout definitions.

## Target Architecture

Keep the app as a static PWA served by Spring Boot, but split it into plain browser modules loaded with ordered `defer` scripts before Alpine initializes.

```text
src/main/resources/static/
├── index.html
├── css/
│   ├── custom.css
│   ├── tokens.css
│   ├── shell.css
│   ├── layout-system.css
│   ├── elements.css
│   ├── forms.css
│   ├── profile.css
│   └── layout-builder.css
├── js/
│   ├── app.js
│   ├── core/
│   │   ├── namespace.js
│   │   ├── api-client.js
│   │   ├── router.js
│   │   ├── element-registry.js
│   │   ├── layout-registry.js
│   │   ├── layout-runtime.js
│   │   ├── content-adapters.js
│   │   ├── storage.js
│   │   └── validators.js
│   ├── stores/
│   │   ├── auth-store.js
│   │   ├── marketplace-store.js
│   │   ├── profile-store.js
│   │   ├── favourites-store.js
│   │   ├── upload-store.js
│   │   └── layout-builder-store.js
│   ├── elements/
│   │   ├── shell-header.js
│   │   ├── filter-bar.js
│   │   ├── catalog-grid.js
│   │   ├── item-card.js
│   │   ├── media-carousel.js
│   │   ├── contact-actions.js
│   │   ├── favourite-button.js
│   │   ├── empty-state.js
│   │   ├── auth-panel.js
│   │   ├── create-listing-form.js
│   │   ├── profile-summary.js
│   │   └── profile-listing-list.js
│   ├── builder/
│   │   ├── palette.js
│   │   ├── drag-drop.js
│   │   ├── inspector.js
│   │   └── preview.js
│   └── data/
│       ├── categories.js
│       ├── default-layouts.js
│       └── sample-data.js
└── config/
    └── layouts/
        ├── marketplace-home.json
        ├── marketplace-favourites.json
        ├── marketplace-profile.json
        └── restaurant-menu.sample.json
```

All files should attach to one controlled namespace:

```js
window.Storefront = window.Storefront || {
  core: {},
  stores: {},
  elements: {},
  builder: {},
  data: {}
};
```

`index.html` should load the files in dependency order, then Alpine:

```html
<script defer src="/js/core/namespace.js"></script>
<script defer src="/js/core/api-client.js"></script>
<script defer src="/js/core/element-registry.js"></script>
<script defer src="/js/core/layout-registry.js"></script>
<script defer src="/js/core/layout-runtime.js"></script>
<script defer src="/js/app.js"></script>
<script defer src="https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js"></script>
```

Classic deferred scripts preserve execution order and keep the current Alpine `alpine:init` registration model predictable.

## Layout Schema

Use a versioned JSON schema with stable ids. This lets the app render default layouts today and lets the builder safely manipulate layouts without backend storage.

```js
{
  version: 1,
  id: "marketplace.home",
  label: "Marketplace Home",
  route: "listings",
  context: "marketplace",
  theme: {
    density: "comfortable",
    accent: "campus",
    radius: "sm"
  },
  regions: [
    {
      id: "toolbar",
      role: "controls",
      layout: {
        kind: "row",
        gap: "md",
        collapse: "stack"
      },
      elements: [
        {
          id: "category-filter",
          type: "marketplace.filterBar",
          dataSource: "marketplace.filters",
          props: {
            showCategory: true,
            showSort: true,
            showSearch: false
          }
        }
      ]
    },
    {
      id: "results",
      role: "main",
      layout: {
        kind: "responsive-grid",
        minItemWidth: "18rem",
        gap: "lg"
      },
      elements: [
        {
          id: "listing-grid",
          type: "catalog.grid",
          dataSource: "marketplace.filteredPosts",
          props: {
            itemElement: "marketplace.itemCard",
            emptyElement: "common.emptyState"
          }
        }
      ]
    }
  ]
}
```

Rules:

- `version` must exist for future migrations.
- `type` must match a registered element type.
- `props` must be validated against the element definition.
- `dataSource` must be a known adapter key, not an arbitrary expression.
- Layout CSS values must come from whitelisted tokens such as `xs`, `sm`, `md`, `lg`, `xl`, `compact`, `comfortable`, `spacious`, `2col`, and `3col`.
- No layout JSON should contain raw HTML, event handlers, script URLs, or unsanitized CSS text.

## Element Registry

Each element definition should include rendering metadata, prop defaults, editor controls, and data requirements.

```js
Storefront.core.elementRegistry.register({
  type: "marketplace.itemCard",
  label: "Marketplace Item Card",
  category: "Marketplace",
  accepts: ["catalogItem"],
  defaultProps: {
    mediaRatio: "4 / 3",
    showFavourite: true,
    showSeller: true,
    showContactActions: true,
    priceStyle: "prominent"
  },
  editor: {
    icon: "card",
    controls: [
      { key: "mediaRatio", kind: "select", options: ["1 / 1", "4 / 3", "16 / 9"] },
      { key: "showFavourite", kind: "toggle" },
      { key: "showSeller", kind: "toggle" },
      { key: "showContactActions", kind: "toggle" }
    ]
  },
  component: "itemCard"
});
```

Initial element types:

- `shell.header`: brand, global search, auth actions, favourites shortcut, builder shortcut.
- `marketplace.filterBar`: category, sort, optional compact search.
- `catalog.grid`: reusable responsive grid for marketplace, favourites, and future catalog-like subapps.
- `marketplace.itemCard`: media, badge, title, description, price, seller, favourite, contact actions.
- `media.carousel`: reusable image carousel used by cards and detail previews.
- `marketplace.favouriteButton`: optimistic favourite toggle.
- `marketplace.contactActions`: email and WhatsApp links.
- `common.emptyState`: icon, title, message, optional action.
- `auth.panel`: login, register, forgot-password, reset-password, WhatsApp-login panels.
- `marketplace.createListingForm`: listing creation form and image uploader.
- `profile.summary`: seller header, avatar, metadata.
- `profile.listingList`: seller listings, sold/delete controls when owner.
- `builder.canvas`, `builder.palette`, and `builder.inspector`: used only in the layout builder view.
- `restaurant.menuGrid` and `restaurant.menuItemCard`: sample future subapp elements backed by static sample data.

## Content Adapters

Introduce adapter functions between backend DTOs and generic element data. The layout system should not know Spring DTO details.

```js
Storefront.core.adapters.catalogItemFromPost = (post) => ({
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
  seller: post.seller,
  createdAt: post.createdAt,
  actions: {
    favouriteId: post.postId,
    email: post.seller?.email,
    whatsappPhone: post.seller?.phoneNumber
  }
});
```

Primary data sources:

- `marketplace.posts`: all loaded posts from `/api/posts`.
- `marketplace.filteredPosts`: search, category, and sort applied client-side.
- `marketplace.favouritePosts`: loaded posts filtered by favourite ids.
- `marketplace.filters`: category and sort option state.
- `profile.seller`: seller details from `/api/sellers/{id}`.
- `profile.posts`: seller posts from `/api/posts/seller/{id}`.
- `builder.sampleCatalog`: static mock catalog items for layout preview.
- `restaurant.sampleMenu`: static future-subapp data.

## Adaptive CSS Plan

Move inline CSS out of `index.html` and make layout behavior token driven.

Core CSS principles:

- Use CSS custom properties for spacing, radius, borders, shadow, density, and layout scale.
- Use container queries for cards, toolbars, and builder preview widths.
- Use `grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--min-item-width)), 1fr))` for adaptive grids.
- Use stable `aspect-ratio` for media, previews, and builder canvas frames.
- Use fixed icon-button dimensions to avoid layout shift.
- Avoid viewport-width font scaling; use semantic type tokens and adjust only through density/theme classes.
- Avoid nested card surfaces; use cards only for repeated items, modals, and framed tools.
- Keep Pico.css as the base reset/form layer, with project CSS layered on top.

Recommended CSS files:

- `tokens.css`: color, spacing, type, radius, density, z-index, and shadows.
- `shell.css`: header, nav, global search, page container.
- `layout-system.css`: regions, grid, stack, split, responsive preview containers.
- `elements.css`: item cards, carousel, actions, badges, empty states.
- `forms.css`: auth and create-listing forms, validation states, uploader.
- `profile.css`: profile header/listing row/danger zone.
- `layout-builder.css`: builder page, palette, inspector, canvas, viewport controls.
- `custom.css`: import or link-level aggregator only if the app keeps a single CSS entry point.

## Implementation Phases

### Phase 1: Baseline And Guardrails

1. Record current user-facing flows before refactoring: listings load, category filter, search, sort, favourite toggle, login/register navigation, create listing form rendering, profile rendering, delete/mark-sold controls.
2. Add lightweight fixture data for front-end smoke testing under `src/main/resources/static/js/data/sample-data.js`.
3. Extend this file with manual and automated front-end checks.
4. Decide whether to add Playwright/Vitest dev dependencies. If yes, keep them front-end-only and use mocked `/api` responses.

Acceptance criteria:

- No production behavior changes.
- No backend or bot files touched.
- Existing static app still loads.

### Phase 2: Extract CSS Without Changing Behavior

1. Move the `<style>` block from `index.html` into `css/custom.css` or the split CSS files.
2. Replace repeated inline styles with named classes only when the class is directly equivalent.
3. Add CSS tokens in `tokens.css`, but keep initial values visually close to the current UI.
4. Add responsive rules for the header/search/actions so mobile layout does not overflow.
5. Keep class names stable where they already exist: `app-header`, `item-card`, `item-image`, `profile-header`, `profile-listing-card`, `fav-btn`.

Acceptance criteria:

- Visual parity with the current site on mobile and desktop.
- `index.html` no longer contains the large inline `<style>` block.
- `css/custom.css` is no longer empty.

### Phase 3: Split The Alpine Store Into Domain Stores

1. Create `core/api-client.js` for `apiFetch`, auth headers, JSON parsing, and 401 handling.
2. Create `stores/auth-store.js` for token/user persistence, login, register, password reset, WhatsApp login.
3. Create `stores/marketplace-store.js` for posts, filters, sorting, search, and create-post orchestration.
4. Create `stores/favourites-store.js` for favourite ids, optimistic updates, localStorage persistence, and backend sync.
5. Create `stores/profile-store.js` for seller/profile data, mark-sold, delete listing, delete account.
6. Create `stores/upload-store.js` for image selection, object URL lifecycle, cover index, and drag-to-reorder.
7. Make `app.js` compose those stores into the single Alpine `storefrontData` object so `index.html` can migrate gradually.

Acceptance criteria:

- `app.js` becomes an app composition entry, not the owner of all logic.
- Current Alpine bindings still work during migration.
- `URL.revokeObjectURL` remains handled when images are removed or publish succeeds.

### Phase 4: Add Registry And Layout Runtime

1. Implement `element-registry.js` with `register`, `get`, `list`, `validateProps`, and `getDefaults`.
2. Implement `layout-registry.js` with default layouts, `getByRoute`, `cloneLayout`, and schema version checks.
3. Implement `validators.js` to validate layout ids, region ids, element types, known data sources, prop types, and token values.
4. Implement `layout-runtime.js` to resolve a route to a layout, resolve each element's data source, merge default props with layout props, and expose a render model to Alpine.
5. Initially render via normal Alpine templates in `index.html`; do not introduce raw `x-html`.

Acceptance criteria:

- Layout definitions can be validated in the browser.
- Invalid element types or unsafe props fail closed with an error element.
- Layout rendering depends on registry definitions, not switch statements scattered through view code.

### Phase 5: Convert Current Marketplace Views To Layouts

1. Convert the listings page into `marketplace-home.json`.
2. Replace the hard-coded listings grid with one `catalog.grid` renderer bound to `marketplace.filteredPosts`.
3. Replace duplicated favourites markup with the same `catalog.grid`, bound to `marketplace.favouritePosts`.
4. Convert the profile header and listing rows into `profile.summary` and `profile.listingList`.
5. Keep auth and create-listing forms as page-level elements at first; make them registry elements once the catalog/profile path is stable.
6. Preserve existing endpoint behavior:
   - `/api/posts`
   - `/api/posts/category/{name}`
   - `/api/posts/upload`
   - `/api/posts/seller/{id}`
   - `/api/sellers/{id}`
   - `/api/favourites`

Acceptance criteria:

- Listings and favourites share the same card implementation.
- The main site is using layout definitions for all catalog-like pages.
- No API contracts change.
- Existing user flows still work.

### Phase 6: Build The Layout Builder Page

Add a new SPA view named `layoutBuilder`. It should be front-end-only.

Builder layout:

- Left panel: element palette grouped by `Common`, `Marketplace`, `Profile`, `Forms`, `Restaurant Sample`.
- Center: adaptive preview canvas with viewport controls for mobile, tablet, desktop, and free width.
- Right panel: selected element inspector with controls generated from the element's `editor.controls`.
- Top bar: template selector, undo, redo, validate, reset draft, export JSON, import JSON, apply preview.

Builder behavior:

1. Add element from palette to selected region.
2. Reorder regions and elements via drag/drop.
3. Duplicate and delete elements.
4. Select an element to edit whitelisted props.
5. Preview layout against sample marketplace data or live loaded data.
6. Persist draft to `localStorage` under `storefront.layoutBuilderDraft.v1`.
7. Validate before applying preview.
8. Export/import layout JSON for development handoff without backend storage.

Responsive preview:

- Use a `.builder-preview-frame` with `container-type: inline-size`.
- Change the preview frame width rather than relying on viewport-wide media queries.
- Use a zoom control for the preview surface only; do not use transform scaling in production layouts.
- Display validation warnings for overflow-prone configurations such as too-small grid item widths.

Acceptance criteria:

- Builder can create and reorder a layout from registered elements.
- Builder preview uses the same layout runtime as the live site.
- Draft survives reload through `localStorage`.
- Unsafe imported layouts are rejected.

### Phase 7: Add Template Support For Future Subapps

1. Define a generic `catalogItem` shape and keep marketplace posts as one adapter into it.
2. Add sample restaurant data under `js/data/sample-data.js`.
3. Add `restaurant-menu.sample.json` using `restaurant.menuGrid` and `restaurant.menuItemCard`.
4. Keep restaurant templates sample-only until real restaurant data endpoints exist.
5. Document the path for a future backend integration: create adapter first, then add data source, then map routes, then optionally add server persistence.

Acceptance criteria:

- The builder can preview a restaurant-like layout without backend changes.
- Marketplace-specific code does not leak into generic catalog elements except through adapters.

### Phase 8: Front-End Polish And Accessibility

1. Replace text-only tool buttons with icon buttons where obvious, using current inline SVGs or a small local icon helper.
2. Add accessible labels to icon-only buttons.
3. Convert `alert` and `confirm` flows to accessible modal/toast components where practical.
4. Ensure keyboard access for carousel controls, builder palette, drag/reorder alternatives, modals, and forms.
5. Add empty, loading, error, and offline-ish states as reusable elements.
6. Test mobile header layout, long seller names, long item titles, missing images, missing phone numbers, sold listings, and failed favourite sync.

Acceptance criteria:

- No major text overflow in header, cards, buttons, modals, or builder panels.
- Reusable status components replace one-off inline message articles.
- The app remains mobile-first and usable one-handed.

### Phase 9: Verification

Manual smoke checks:

1. Load homepage and verify posts render.
2. Search by title and description.
3. Filter by category and clear filters.
4. Sort newest, oldest, low-to-high, high-to-low.
5. Cycle a multi-image carousel.
6. Toggle favourite while logged out and verify login redirect/message.
7. Toggle favourite while logged in and verify optimistic update.
8. Open favourites and verify same card rendering.
9. Open seller profile and own profile.
10. Render create-listing form, add images, reorder images, remove images.
11. Open layout builder, add element, reorder, edit props, change preview width, reload and verify draft persistence.

Automated front-end checks if tooling is added:

- Unit-test adapters, validators, registry defaults, and layout schema migration.
- Playwright-test live static rendering with mocked API responses.
- Snapshot or screenshot test mobile and desktop home/favourites/builder views.

## Suggested Execution Order

1. CSS extraction and visual parity.
2. Store extraction while preserving the existing DOM.
3. Registry, schema, and adapters.
4. Catalog grid/card consolidation.
5. Main views migrated to layouts.
6. Layout builder.
7. Restaurant sample template.
8. Accessibility and responsive polish.
9. Test coverage and documentation.

This order keeps each step reviewable and avoids combining visual redesign, state refactoring, and builder behavior in one risky commit.

## Backend Touchpoints To Avoid

Do not touch these unless a later requirement explicitly asks for server-side layout persistence or new subapp data:

- `src/main/kotlin/com/studentstorefront/**`
- `src/test/kotlin/com/studentstorefront/**`
- `bot/**`
- database migration or JPA entity work

Possible future backend work, outside this plan:

- `GET /api/layouts/{route}` for shared layouts.
- `PUT /api/layouts/{route}` for admin layout editing.
- restaurant/menu entities and endpoints.
- role-gated layout publishing.

## Definition Of Done

- The current marketplace front-end is rendered through a typed layout/element system.
- Listings and favourites no longer duplicate item-card markup.
- A front-end-only layout builder page exists and can create, validate, preview, reorder, and persist draft layouts.
- Adaptive CSS is tokenized, container-aware, and stable across mobile/tablet/desktop preview sizes.
- Main API contracts are unchanged.
- Bot, database, and backend code are untouched.
- The implementation has smoke-test coverage, and ideally automated tests for adapters, validators, and layout runtime behavior.
