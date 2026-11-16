-- SeaMart: persist all three selling prices in the shared products table.
-- Safe to run more than once. Existing rows remain NULL for price_2/price_3
-- until a device saves the product, so this migration does not guess or overwrite prices.
alter table public.products
  add column if not exists price_2 numeric,
  add column if not exists price_3 numeric;

comment on column public.products.price_2 is 'SeaMart selling price 2';
comment on column public.products.price_3 is 'SeaMart selling price 3';

-- SeaMart automatic purchase/sales VAT and cost-based margin pricing.
-- Run this migration in Supabase SQL Editor before using cloud sync for the new fields.
alter table public.products
  add column if not exists purchase_price numeric not null default 0,
  add column if not exists purchase_vat_rate numeric not null default 11,
  add column if not exists sales_vat_rate numeric not null default 11,
  add column if not exists margin_1 numeric not null default 50,
  add column if not exists margin_2 numeric not null default 50,
  add column if not exists margin_3 numeric not null default 50,
  add column if not exists sale_base_1 numeric not null default 0,
  add column if not exists sale_base_2 numeric not null default 0,
  add column if not exists sale_base_3 numeric not null default 0;
