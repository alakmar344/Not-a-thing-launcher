# Archived docs

Historical documentation for the **pre-v11.0 launcher**: a fully bespoke Kotlin home screen
(its own `MainActivity`, `HomeFragment`, `CellLayout` grid, drag controller, procedural
dot-matrix elements, etc). That launcher was rebuilt from scratch on stock **AOSP Launcher3**
in v11.0 (see the root [`CHANGELOG.md`](../../CHANGELOG.md)); none of the classes or UI
described in these files exist in the current codebase.

Kept for historical reference only — do not use these to understand or modify the current
launcher. Start at the root [`README.md`](../../README.md), [`../ARCHITECTURE.md`](../ARCHITECTURE.md),
and [`../FEATURES.md`](../FEATURES.md) instead.

| File | What it covers |
|---|---|
| `RELEASE_V8.md` | Release notes, v8.0 of the bespoke launcher |
| `RELEASE_V9.md` | Release notes, v9.0 — the `CellLayout` grid rebuild |
| `RELEASE_V10.md` | Release notes, v10.0 GA — zero-idle battery & performance pass |
| `RELEASE_V10_1.md` | Release notes, v10.1 — app icon entry point, stable signing |
| `ARCHITECTURE.v10.1.md` | Internals of the bespoke launcher as of v10.1 |
| `FEATURES.v10.1.md` | Feature catalog of the bespoke launcher as of v10.1 |
| `SCREENSHOTS.v10.1.md` | Screenshot showcase of the bespoke launcher as of v10.1 |
| `images/` | Screenshots referenced by the files above |
