-- SeaMart: persist all three selling prices in the shared products table.
-- Safe to run more than once. Existing rows remain NULL for price_2/price_3
-- until a device saves the product, so this migration does not guess or overwrite prices.
alter table public.products
  add column if not exists price_2 numeric,
  add column if not exists price_3 numeric;

comment on column public.products.price_2 is 'SeaMart selling price 2';
comment on column public.products.price_3 is 'SeaMart selling price 3';
