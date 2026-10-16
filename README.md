# MyCRM Driver (Android)

Driver app for MyCRM / Order Control (Pure Peptides Lab Trading). Kotlin + Jetpack Compose, talks to the
same Supabase project as the web core (`mycrm.center`). Android 8.0+.

## What it does (v1)

* Sign in with a MyCRM account whose profile role is `driver` and which is linked to a row in `drivers`
  (`drivers.profile_id`).
* **My deliveries** — the driver's `En Route` orders (refresh every 10 s while open), sorted by priority →
  scheduled date/time → number. Money first: *cash to collect* (Cash on delivery), *payment link* (Link on
  delivery), *nothing to collect* (paid / accounts / gift / replacement / crypto).
* **Order** — call / WhatsApp the customer, **Navigate** (the order's location link, or a map search by address;
  Waze when the link has coordinates), items, note for driver, **✓ Delivered** (confirm) and
  **✕ Could not deliver** (reason → back to the dispatcher's queue).
* **Undo** for 5 minutes after Delivered.
* **Done today** — delivered today (Dubai time) and the cash estimate.
* Self-update from the latest GitHub Release (`mycrm-driver.apk`); App Links `https://mycrm.center/driver/o/ORD-…`.

All writes go through database functions (`driver_mark_delivered`, `driver_mark_failed`,
`driver_undo_delivered` — migration `add_driver_app.sql` in the MyCRM project). The orders guard rejects any
direct UPDATE by the `driver` role.

## Releasing

1. Repository secrets (once): `KEYSTORE_B64` (base64 of `mycrm-driver.jks`) and `KEYSTORE_PASSWORD`.
2. Optional notes: `notes/vX.Y.Z.md`.
3. Push a tag `vX.Y.Z` (or GitHub → Releases → Draft a new release → new tag). The workflow runs unit tests,
   builds and signs the APK and publishes the Release with `mycrm-driver.apk` and `mycrm-driver-vX.Y.Z.apk`.
   `versionCode` = MAJOR·10000 + MINOR·100 + PATCH.

Pushes to `main` build a debug APK (artifact) as a compile check. Every run writes a short log to the
`ci-logs` branch.

**The signing key must never be lost**: an APK signed with another key cannot be installed over the old one.

## Site files (`site/`)

Copied to the `mycrm.center` repository: `driver/index.html` (download page + "open order in the app"),
`404.html` (redirects `/driver/o/ORD-…` to the driver page), `.well-known/assetlinks.json` (App Links
verification, SHA-256 of the signing certificate).
