# KasirKu Minimarket

Aplikasi kasir Android offline versi awal.

## Fitur
- Kasir sederhana dengan keranjang dan total transaksi.
- Database lokal SQLite.
- Tambah dan lihat barang, harga, barcode, stok.
- Cari barang dan input barcode (scanner Bluetooth/USB yang berfungsi sebagai keyboard).
- Impor dan ekspor daftar barang CSV.

## Build APK
Buka tab **Actions**, pilih workflow **Build APK Android**, lalu unduh artifact **KasirKu-Minimarket-APK** setelah status build sukses.

## Format CSV
Header: `nama,barcode,harga,stok`

Contoh:
```csv
nama,barcode,harga,stok
Air Mineral,899000000001,4000,30
Mie Instan,899000000002,3500,50
```

Aplikasi menyimpan data pada perangkat dan fungsi inti bekerja tanpa internet. Versi awal ini belum mencakup kamera pemindai barcode, cetak struk, laporan laba-rugi, sinkronisasi cloud, maupun PIN kasir.
