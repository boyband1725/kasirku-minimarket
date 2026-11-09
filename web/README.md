# SeaMart Web — Odoo-inspired preview

This folder contains the first browser-based SeaMart POS interface, separated from the legacy Android and Windows builds.

Publishing is configured to use GitHub Actions through the repository's Pages settings.

## Included in this preview
- Odoo-inspired navigation and dashboard
- Product master with category, code, barcode, price, and stock
- CSV import/export (legacy header: `nama,barcode,harga,stok`)
- Cashier screen with search/barcode keyboard input, cart, cash/QRIS selection
- Stock in/out adjustments and movement history
- Sales summary and CSV export
- Printable receipt preview

## Important limitations
This is a frontend preview. Data is stored in the current browser using localStorage. It is not yet a shared online database and does not provide secure admin/cashier authentication. Do not use it as the live store system until a backend, user authentication, backups, and multi-device synchronization are configured and tested.

## Deployment
The GitHub Actions workflow `.github/workflows/deploy-web.yml` attempts to publish this folder to GitHub Pages when the `seamart-web-odoo` branch changes. GitHub Pages must be enabled for the repository with the source set to GitHub Actions. A public URL should only be shared after a successful deployment run is verified.
