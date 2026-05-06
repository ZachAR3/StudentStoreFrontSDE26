# Theme Map

This file is the design-system reference for the Student-Store Front frontend. Use it with `docs/REPOMAP.md`: REPOMAP explains the application structure; THEMEMAP explains the visual contract.

## Theme

**Name:** Campus Editorial

**Direction:** A polished campus marketplace with quiet editorial structure: neutral blue-gray page backgrounds, crisp white surfaces, teal primary actions, restrained terracotta accents, compact 8px-or-less radii, and soft elevation only where it clarifies interaction.

**Mode:** Light theme only. Do not add dark-mode overrides unless the full component set is reviewed for contrast and image treatment.

## Source Files

- `src/main/resources/static/css/tokens.css` is the source of truth for theme tokens and PicoCSS variable overrides.
- `src/main/resources/static/css/custom.css` imports the themed stylesheet set in order.
- `src/main/resources/static/css/shell.css` handles the app header, persistent site sidebar, support page/footer, mobile shell navigation, and top-level spacing.
- `src/main/resources/static/css/layout-system.css` handles layout regions, page primitives, loading, alerts, empty states, and listing detail.
- `src/main/resources/static/css/elements.css` handles marketplace cards, compact listing contact actions, filters, restaurant preview, selected-cart UI, reusable sales-site sections, and common element surfaces.
- `src/main/resources/static/css/forms.css` handles auth cards, form actions, upload UI, password meter, and QR login.
- `src/main/resources/static/css/profile.css` handles profile summary, rating pills, profile review panels/cards, profile listing rows, buyer search controls, danger zone, and modals.
- `src/main/resources/static/css/layout-builder.css` handles the layout builder, responsive preview frame, palette/inspector panels, and created-site library.

## Palette

Use semantic variables, not raw hex values, outside `tokens.css`.

| Purpose | Variable | Value |
| --- | --- | --- |
| App background | `--sf-color-background` | `#f6f8fb` |
| Background wash | `--sf-color-background-soft` | `#edf3f5` |
| Surface/card | `--sf-color-surface` | `#ffffff` |
| Muted surface | `--sf-color-surface-muted` | `#eef3f6` |
| Text | `--sf-color-text` | `#172026` |
| Strong text | `--sf-color-text-strong` | `#0d171c` |
| Muted text | `--sf-color-muted` | `#62707d` |
| Subtle text | `--sf-color-subtle` | `#8b98a7` |
| Border | `--sf-color-border` | `#d7e0e8` |
| Strong border | `--sf-color-border-strong` | `#bdc9d5` |
| Primary | `--sf-color-primary` | `#176b5c` |
| Primary hover | `--sf-color-primary-strong` | `#0f4a40` |
| Primary soft | `--sf-color-primary-soft` | `#e4f2ee` |
| Accent | `--sf-color-accent` | `#b84d37` |
| Info | `--sf-color-info` | `#245c7a` |
| Success | `--sf-color-success` | `#16794f` |
| Warning | `--sf-color-warning` | `#a66300` |
| Error/danger | `--sf-color-danger` | `#b42339` |
| WhatsApp | `--sf-color-whatsapp` | `#1fa855` |

## Typography

- Font family: `--sf-font-body` (`Inter`, system UI fallback).
- Body text: inherit Pico defaults unless a component needs tighter sizing.
- Page `h1`: `2rem`, line-height `1.08`.
- Page `h2`: `1.55rem`, line-height `1.15`.
- Page `h3`: `1.15rem`, line-height `1.2`.
- Letter spacing stays `0`; do not use negative tracking.
- Do not scale font sizes directly with viewport width. Use fixed rem sizes and media-query adjustments when necessary.

## Spacing

Use the `--sf-space-*` scale:

- `2xs`: `0.25rem`
- `xs`: `0.5rem`
- `sm`: `0.75rem`
- `md`: `1rem`
- `lg`: `1.5rem`
- `xl`: `2rem`
- `2xl`: `3rem`
- `3xl`: `4rem`

Layout JSON region gaps should use `xs`, `sm`, `md`, `lg`, or `xl`; the layout runtime maps them to `--region-gap`.

## Radius And Elevation

- Radius scale: `--sf-radius-xs` `0.25rem`, `--sf-radius-sm` `0.375rem`, `--sf-radius-md`/`lg` `0.5rem`, `--sf-radius-pill` `999px`.
- Cards and panels should generally use `--sf-radius-md`.
- Controls should generally use `--sf-radius-sm`.
- Use `--sf-shadow-xs` for subtle card separation, `--sf-shadow-sm` for hover/active surfaces, and `--sf-shadow-md` for modals only.
- Avoid decorative glow/orb effects.

## PicoCSS Conventions

- Pico remains the base reset/component layer.
- Override Pico through variables in `tokens.css`: `--pico-primary`, `--pico-background-color`, `--pico-card-background-color`, form variables, and `--pico-border-radius`.
- Do not fight Pico with page-specific hardcoded button/input colors. Add a semantic class or token when Pico defaults are insufficient.
- Use Pico roles (`role="button"`) for standalone action links when useful, but avoid putting `role="button"` on large composed cards because Pico applies button-like layout rules to that selector.
- Card-level click targets should use `role="link"` plus keyboard handlers when they navigate to a detail view.

## Components

### Layout Shell

- `.app-body-shell` constrains the app to a readable desktop width with a left navigation column plus a `--sf-content-max` main column.
- `.app-header` is sticky, translucent surface, with border and blur.
- `.shell-brand`, `.shell-pill-button`, `.shell-auth-actions`, and `.shell-user-actions` are the reusable header primitives.
- The title bar should stay simple: brand, global search, selected/auth/user actions. Do not add global shortcuts for restaurant sample or created sites.
- The global search is layout-runtime-aware: it must filter marketplace grids, favourites, profile listing lists, restaurant menus, and created-site/sample catalog grids. Empty states should switch to search-specific copy when a query filters all items out.
- `.site-sidebar` is the persistent left navigation surface for Marketplace, Saved Items, Layout Builder, Support, and locally created sites. On mobile it becomes a compact horizontal navigation row.
- Mobile header uses brand/actions plus a full-width search row under `880px`, then stacks actions under `560px`.

### Buttons

- Primary buttons use Pico primary variables.
- Outline buttons use white surface, strong border, teal text, and primary-soft hover.
- Secondary destructive-looking actions should use `outline secondary` unless they actually delete data.
- WhatsApp actions use `.is-whatsapp`.
- Icon buttons use `.icon-button`, `.carousel-btn`, `.fav-btn`, or listing-specific `.listing-contact-button`; do not create text-only boxes for obvious icon actions.

### Forms

- Auth and listing forms sit in `.auth-card`; wider forms add `.form-card-wide`.
- Use `.form-actions` for right-aligned grouped actions.
- Full-width auth submit buttons use `.sf-u-full-width`.
- Helper/auth links use `.auth-link-row` or `.auth-forgot-row`.
- Password meter bars use `is-danger`, `is-warning`, and `is-success` classes, not inline colors.
- Upload dropzone uses primary-soft active state.

### Cards And Panels

- Marketplace items use `.item-card` with a themed border, white surface, subtle elevation, and `--item-media-ratio`.
- Marketplace listing cards are intentionally compact: image, one-line title, price, and square icon-only contact actions. Do not add date, seller name, description, or status text back into the card footer; those belong in listing detail/profile views.
- Marketplace card footers use an internal grid lane for price plus `.listing-contact-actions`; keep the card clipped to itself and solve contact-button spacing with padding, not visible overflow. Marketplace contact actions use `.listing-contact-button` / `.listing-contact-button-whatsapp`, not Pico outline buttons.
- Restaurant menu cards reuse `.item-card` but add `.restaurant-menu-card`; they are allowed richer description/seller/add-button content and have their own responsive container-query rules.
- Sales-site sections use `.sales-hero-section`, `.feature-strip`, `.contact-panel`, and `.announcement-bar`. They are full-row layout elements and must collapse cleanly on mobile.
- Support page sections use `.support-page`, `.support-hero`, `.support-grid`, and `.support-card`; these should stay quiet and utility-focused, using normal surfaces rather than marketing-style hero treatment.
- Cards should not nest inside decorative parent cards. Use full-width sections or grids for grouping.
- Profile rows use `.profile-listing-card`.
- Profile rating summaries use `.profile-rating-strip` and `.profile-rating-pill`; keep them compact, tokenized, and close to the profile identity block.
- Pending and recent profile reviews use `.profile-review-panel` plus `.profile-review-card`, with quiet muted surfaces rather than marketplace-card styling.
- Builder panels use `.builder-panel`.

### Tables And Lists

- No dedicated data-table component exists yet. Future tables should use:
  - surface `--sf-color-surface`
  - border `--sf-color-border`
  - header background `--sf-color-surface-muted`
  - compact row padding from `--sf-space-sm` and `--sf-space-md`
  - right-aligned numeric columns

### Alerts And Messages

- Use `.feedback-banner` for neutral/info messages.
- Add `.success` or `.error` for success/error states.
- Alert colors are tokenized through `--sf-color-*-soft` backgrounds and semantic text colors.

### Empty And Loading States

- Use `.empty-state` for empty content, missing listings, and layout validation fallback.
- Use `.loading-state` for asynchronous loading.
- Empty states should be concise and include one obvious recovery action when possible.

### Modals

- `.modal-overlay` uses `--sf-color-scrim` plus backdrop blur.
- `.modal-card` uses white surface, `--sf-shadow-md`, and `--sf-radius-lg`.
- Modal actions use `.form-actions`.
- Buyer selection in the mark-sold modal uses `.buyer-search-results` and `.buyer-result-button`; results should be full-width, left-aligned, and text-forward rather than card-like.

### Navigation

- Header actions wrap instead of overflowing.
- Created sites live in the left sidebar, not the title bar.
- Support is available from both the sidebar and the footer.
- The restaurant sample is a layout-builder template, not a global navigation shortcut.
- Browser back/forward should work for SPA view changes through the hash/history integration.
- Mobile nav should remain usable without hover.

### Footer

- `.app-footer` sits at the bottom of the main app column with a simple top border, compact copyright text, and a Support CTA.
- Footer buttons follow normal outline button styling and become full-width on narrow mobile screens.

### Layout Builder

- The builder must keep the palette and inspector visible in desktop preview mode.
- Desktop preview renders inside a horizontally scrollable preview shell with a wide frame; it should not use viewport breakout widths that cover the site sidebar.
- Mobile/tablet/free-width preview modes constrain the frame to device-like widths.
- Full-row builder/live elements include `catalog.grid`, `restaurant.menuGrid`, `restaurant.menuHero`, `profile.summary`, `profile.listingList`, `common.salesHero`, `common.featureStrip`, `common.contactPanel`, `common.announcementBar`, and `common.emptyState`.

### Status Badges

- Use pill radii and semantic colors.
- `.sold-badge` uses success.
- `.fav-badge` uses danger because it represents saved/favourite count.
- Category badges on cards use overlay color on media.

### Ratings And Reviews

- Profile ratings are informational, not primary CTAs; use pill surfaces and muted labels with strong text only for the numeric rating.
- Review cards should remain compact and readable: rating/title first, optional comment second, reviewer metadata last.
- Pending review prompts belong on the authenticated user's own profile only, inside a quiet `.profile-review-panel`.
- Review and mark-sold flows use existing modal primitives and `.form-actions`; do not create a separate drawer/sheet pattern for this feature.

## Alpine.js Interaction Conventions

- Preserve existing `x-model`, `@submit.prevent`, route names, API service calls, and state object names.
- Keep profile review state under the existing profile store and review API calls in `js/services/review-service.js`; do not move review workflow logic into layout element definitions.
- Prefer class bindings over inline style strings for theme states.
- Inline style bindings are acceptable only for layout/runtime CSS variables, dynamic media ratios, builder preview dimensions, and generated avatar colors.
- Keep click handlers on nested card controls using `.stop` so card-level open behavior does not swallow contact/favourite actions.
- For clickable listing cards, keep the card as `role="link"` rather than `role="button"` so Pico does not restyle the full card as a control.
- Use `navigateTo` for SPA view changes so hash/history state stays in sync and browser back/forward keeps working.

## Responsive Rules

- Header becomes brand/actions plus a full-width search row below `880px`, and stacks more tightly below `560px`.
- The persistent site sidebar becomes a horizontal scrollable navigation strip below `880px`.
- The footer stacks naturally on small screens and makes its Support button full-width below `560px`.
- Listing detail switches from two columns to one below `760px`.
- Restaurant and sales heroes reduce spacing/type and collapse to one column below `760px`.
- Restaurant menu cards collapse through container queries when narrow. Keep those container-query selectors scoped to `.restaurant-menu-card`; broad `.item-footer` rules can break marketplace listing footers.
- Grids should use `repeat(auto-fit, minmax(min(100%, var(--grid-min-item-width)), 1fr))`.
- Avoid viewport-scaled fonts. Use media queries for intentional size changes.

## CSS Variable Naming

- All app-level tokens use the `--sf-*` prefix.
- Color tokens use `--sf-color-*`.
- Spacing uses `--sf-space-*`.
- Radius uses `--sf-radius-*`.
- Shadows use `--sf-shadow-*`.
- Component-specific variables are allowed only when they describe a true runtime input, for example `--item-media-ratio`, `--grid-min-item-width`, `--region-gap`, and `--profile-avatar-bg`.

## Audit Notes

The frontend is a static Alpine/Pico PWA in `src/main/resources/static`, not a server-template tree in this repository. The key issues found were:

- The theme lab used isolated variables and was not connected to the live app.
- The live app had hardcoded white, blue, green, gray, and status colors across component CSS.
- Auth, QR login, builder labels, favourite icons, and password meters used inline styles that bypassed the theme.
- Marketplace cards ignored `--item-media-ratio`, making layout props misleading.
- Layout JSON `gap` values were validated but not applied by the runtime.
- Marketplace cards were too compressed for a polished commerce UI (`12rem` grid columns); the default live grid now uses `18rem`.
- Marketplace card footers previously mixed price, date, seller metadata, and Pico-styled contact links. They now use a dedicated compact footer and icon-only contact buttons to avoid clipping and keep the price aligned.
- Title-bar shortcuts for restaurant sample and created sites were removed; created sites now appear in persistent sidebar navigation and restaurant sample is selected through the layout builder.
- Builder-created sites and preview surfaces now treat hero/banner/sales elements as full-row sections so headers do not collapse inside responsive grids.
- The builder palette now includes common sales-site sections: Sales Hero, Feature Strip, Contact Panel, and Announcement Bar.
- Header search now filters all catalog-like layout data sources, including restaurant menus and created-site sample grids, not only the marketplace home route.
- A Support page and app footer were added using shell-level theme primitives, with copyright text and a footer Support CTA.
- Seller/buyer ratings were added to the profile surface with compact rating pills, pending review cards, recent review cards, and buyer-search/review modals that reuse existing profile/modal tokens.
