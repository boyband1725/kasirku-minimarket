-- SeaMart full product master fields
-- Run this migration once in Supabase SQL Editor before publishing the PC master again.
-- Existing product IDs, codes, barcodes, stock, and transaction history are preserved.

alter table public.products
  add column if not exists price_2 numeric(14,2) not null default 0,
  add column if not exists price_3 numeric(14,2) not null default 0,
  add column if not exists purchase_price numeric(14,2) not null default 0,
  add column if not exists purchase_vat_rate numeric(7,3) not null default 11,
  add column if not exists sales_vat_rate numeric(7,3) not null default 11,
  add column if not exists margin_1 numeric(12,3) not null default 50,
  add column if not exists margin_2 numeric(12,3) not null default 50,
  add column if not exists margin_3 numeric(12,3) not null default 50,
  add column if not exists sale_base_1 numeric(14,2) not null default 0,
  add column if not exists sale_base_2 numeric(14,2) not null default 0,
  add column if not exists sale_base_3 numeric(14,2) not null default 0,
  add column if not exists image_url text;

comment on column public.products.price is 'Harga Jual 1 yang sudah termasuk PPN; digunakan di kasir.';
comment on column public.products.price_2 is 'Harga Jual 2 yang sudah termasuk PPN.';
comment on column public.products.price_3 is 'Harga Jual 3 yang sudah termasuk PPN.';
comment on column public.products.purchase_price is 'Harga beli sebelum PPN.';
comment on column public.products.purchase_vat_rate is 'Tarif PPN harga beli dalam persen.';
comment on column public.products.sales_vat_rate is 'Tarif PPN harga jual dalam persen.';
comment on column public.products.sale_base_1 is 'Harga Jual 1 sebelum PPN.';
comment on column public.products.sale_base_2 is 'Harga Jual 2 sebelum PPN.';
comment on column public.products.sale_base_3 is 'Harga Jual 3 sebelum PPN.';
