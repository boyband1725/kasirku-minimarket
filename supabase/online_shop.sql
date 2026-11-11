-- SeaMart Online Shop: isolated order schema.
-- Safe migration: does not UPDATE, DELETE, or INSERT into existing products/categories.
create extension if not exists pgcrypto;

create table if not exists public.online_orders (
  id uuid primary key default gen_random_uuid(),
  store_id uuid not null,
  order_code text not null unique,
  customer_name text not null,
  customer_phone text not null,
  customer_address text,
  customer_note text,
  payment_method text not null check (payment_method in ('qris','bank_transfer','cod','store')),
  payment_status text not null default 'unpaid' check (payment_status in ('unpaid','pending_verification','paid','failed')),
  order_status text not null default 'new' check (order_status in ('new','confirmed','processing','ready','completed','cancelled')),
  subtotal numeric(14,2) not null check (subtotal >= 0),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.online_order_items (
  id uuid primary key default gen_random_uuid(),
  order_id uuid not null references public.online_orders(id) on delete cascade,
  product_id uuid not null,
  product_name text not null,
  barcode text,
  unit_price numeric(14,2) not null check (unit_price >= 0),
  quantity integer not null check (quantity > 0),
  line_total numeric(14,2) not null check (line_total >= 0)
);

create index if not exists online_orders_store_created_idx
  on public.online_orders(store_id, created_at desc);
create index if not exists online_orders_status_idx
  on public.online_orders(store_id, order_status, payment_status);
create index if not exists online_order_items_order_idx
  on public.online_order_items(order_id);

alter table public.online_orders enable row level security;
alter table public.online_order_items enable row level security;

drop policy if exists "SeaMart admins read online orders" on public.online_orders;
create policy "SeaMart admins read online orders" on public.online_orders
for select to authenticated
using (exists (
  select 1 from public.store_members sm
  where sm.store_id = online_orders.store_id
    and sm.user_id = auth.uid()
    and sm.role in ('owner','admin')
));

drop policy if exists "SeaMart admins update online orders" on public.online_orders;
create policy "SeaMart admins update online orders" on public.online_orders
for update to authenticated
using (exists (
  select 1 from public.store_members sm
  where sm.store_id = online_orders.store_id
    and sm.user_id = auth.uid()
    and sm.role in ('owner','admin')
))
with check (exists (
  select 1 from public.store_members sm
  where sm.store_id = online_orders.store_id
    and sm.user_id = auth.uid()
    and sm.role in ('owner','admin')
));

drop policy if exists "SeaMart admins read online order items" on public.online_order_items;
create policy "SeaMart admins read online order items" on public.online_order_items
for select to authenticated
using (exists (
  select 1 from public.online_orders o
  join public.store_members sm on sm.store_id = o.store_id
  where o.id = online_order_items.order_id
    and sm.user_id = auth.uid()
    and sm.role in ('owner','admin')
));

create or replace function public.seamart_create_online_order(
  p_store_id uuid,
  p_customer_name text,
  p_customer_phone text,
  p_customer_address text,
  p_customer_note text,
  p_payment_method text,
  p_items jsonb
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order_id uuid := gen_random_uuid();
  v_code text := 'SM-' || upper(substr(replace(gen_random_uuid()::text,'-',''),1,10));
  v_subtotal numeric(14,2) := 0;
  v_item jsonb;
  v_product record;
  v_qty integer;
begin
  if p_store_id is null
     or length(trim(coalesce(p_customer_name,''))) < 2
     or length(regexp_replace(coalesce(p_customer_phone,''),'[^0-9]','','g')) < 8
     or p_payment_method not in ('qris','bank_transfer','cod','store')
     or jsonb_typeof(p_items) <> 'array'
     or jsonb_array_length(p_items) < 1
     or jsonb_array_length(p_items) > 50 then
    raise exception 'Data pesanan tidak valid';
  end if;

  for v_item in select value from jsonb_array_elements(p_items)
  loop
    v_qty := greatest(0, least(999, coalesce((v_item->>'quantity')::integer,0)));
    if v_qty < 1 then raise exception 'Jumlah barang tidak valid'; end if;
    select id, name, barcode, price, stock, active
      into v_product
      from public.products
     where id = (v_item->>'product_id')::uuid
       and store_id = p_store_id
       and active = true;
    if not found then raise exception 'Barang tidak tersedia'; end if;
    if coalesce(v_product.stock,0) < v_qty then
      raise exception 'Stok tidak cukup untuk %', v_product.name;
    end if;
    v_subtotal := v_subtotal + (coalesce(v_product.price,0) * v_qty);
  end loop;

  insert into public.online_orders(
    id, store_id, order_code, customer_name, customer_phone,
    customer_address, customer_note, payment_method, payment_status,
    order_status, subtotal
  ) values (
    v_order_id, p_store_id, v_code, trim(p_customer_name),
    regexp_replace(p_customer_phone,'[^0-9+]','','g'),
    nullif(trim(coalesce(p_customer_address,'')),''),
    nullif(trim(coalesce(p_customer_note,'')),''),
    p_payment_method,
    case when p_payment_method in ('qris','bank_transfer') then 'pending_verification' else 'unpaid' end,
    'new', v_subtotal
  );

  for v_item in select value from jsonb_array_elements(p_items)
  loop
    v_qty := (v_item->>'quantity')::integer;
    select id, name, barcode, price into v_product
      from public.products
     where id = (v_item->>'product_id')::uuid
       and store_id = p_store_id and active = true;
    insert into public.online_order_items(
      order_id, product_id, product_name, barcode, unit_price, quantity, line_total
    ) values (
      v_order_id, v_product.id, v_product.name, v_product.barcode,
      coalesce(v_product.price,0), v_qty, coalesce(v_product.price,0) * v_qty
    );
  end loop;

  return jsonb_build_object('id',v_order_id,'order_code',v_code,'subtotal',v_subtotal);
end;
$$;

revoke all on function public.seamart_create_online_order(uuid,text,text,text,text,text,jsonb) from public;
grant execute on function public.seamart_create_online_order(uuid,text,text,text,text,text,jsonb) to anon, authenticated;
grant select on public.online_orders, public.online_order_items to authenticated;
grant update (order_status, payment_status, updated_at) on public.online_orders to authenticated;

-- Public product/catalog reads are limited to active items. Existing product rows remain untouched.
drop policy if exists "SeaMart public shop reads active products" on public.products;
create policy "SeaMart public shop reads active products" on public.products
for select to anon, authenticated using (active = true);
drop policy if exists "SeaMart public shop reads categories" on public.categories;
create policy "SeaMart public shop reads categories" on public.categories
for select to anon, authenticated using (true);
