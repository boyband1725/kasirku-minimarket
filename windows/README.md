# SeaMart POS Windows
Aplikasi kasir desktop Windows offline berbasis Python/Tkinter dan SQLite.

Fitur: pencarian nama/barcode, scanner keyboard USB/Bluetooth, keranjang, pembayaran Tunai/QRIS (QRIS diverifikasi manual oleh kasir), penyimpanan transaksi lokal, impor CSV, cetak ulang struk melalui printer Windows default.

Format CSV: barcode,nama,kategori,harga_modal,harga_jual,stok,stok_minimum.
Database lokal: %LOCALAPPDATA%\SeaMartPOS\seamart.db.

Build EXE otomatis melalui GitHub Actions workflow "Build SeaMart Windows EXE". Artifact akan bernama SeaMartPOS-Windows. Ini build awal; pengujian scanner dan printer thermal 58mm tetap perlu dilakukan pada perangkat nyata.
