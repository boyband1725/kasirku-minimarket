-- SeaMart POS unified checkout
-- Additive migration: preserves existing products, sales stored in browsers, and cash-flow rows.
create extension if not exists pgcrypto;

create table if not exists public.seamart_pos_sales (
  id uuid primary key default gen_random_uuid(),
  store_id uuid not null,
  sale_code text not null,
  cashier_name text,
  shift_ref text,
  payment_method text not null check (payment_method in ('Tunai','QRIS')),
  total numeric(14,2) not null check (total >= 0),
  amount_paid numeric(14,2) not null check (amount_paid >= total),
  change_amount numeric(14,2) not null default 0,
  created_by uuid,
  created_at timestamptz not null default now(),
  unique(store_id, sale_code)
);

create table if not exists public.seamart_pos_sale_items (
  id uuid primary key default gen_random_uuid(),
  sale_id uuid not null references public.seamart_pos_sales(id) on delete cascade,
  product_id uuid not null,
  product_name text not null,
  barcode text,
  unit_price numeric(14,2) not null,
  quantity integer not null check (quantity > 0),
  line_total numeric(14,2) not null
);

create table if not exists public.seamart_stock_movements (
  id uuid primary key default gen_random_uuid(),
  store_id uuid not null,
  product_id uuid not null,
  sale_id uuid references public.seamart_pos_sales(id) on delete set null,
  movement_type text not null default 'Keluar / Terjual',
  quantity integer not null check (quantity > 0),
  quantity_delta integer not null,
  note text,
  created_by uuid,
  created_at timestamptz not null default now()
);

create index if not exists seamart_pos_sales_store_created_idx
  on public.seamart_pos_sales(store_id, created_at desc);
create index if not exists seamart_pos_items_sale_idx
  on public.seamart_pos_sale_items(sale_id);
create index if not exists seamart_stock_moves_store_created_idx
  on public.seamart_stock_movements(store_id, created_at desc);

alter table public.seamart_pos_sales enable row level security;
alter table public.seamart_pos_sale_items enable row level security;
alter table public.seamart_stock_movements enable row level security;

drop policy if exists "SeaMart members read POS sales" on public.seamart_pos_sales;
create policy "SeaMart members read POS sales" on public.seamart_pos_sales
for select to authenticated using (exists (
  select 1 from public.store_members sm
  where sm.store_id = seamart_pos_sales.store_id and sm.user_id = auth.uid()
));
drop policy if exists "SeaMart members read POS sale items" on public.seamart_pos_sale_items;
create policy "SeaMart members read POS sale items" on public.seamart_pos_sale_items
for select to authenticated using (exists (
  select 1 from public.seamart_pos_sales s
  join public.store_members sm on sm.store_id = s.store_id
  where s.id = seamart_pos_sale_items.sale_id and sm.user_id = auth.uid()
));
drop policy if exists "SeaMart members read stock movements" on public.seamart_stock_movements;
create policy "SeaMart members read stock movements" on public.seamart_stock_movements
for select to authenticated using (exists (
  select 1 from public.store_members sm
  where sm.store_id = seamart_stock_movements.store_id and sm.user_id = auth.uid()
));

create or replace function public.seamart_complete_pos_sale(
  p_store_id uuid,
  p_sale_code text,
  p_cashier_name text,
  p_shift_ref text,
  p_payment_method text,
  p_amount_paid numeric,
  p_items jsonb
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_sale_id uuid := gen_random_uuid();
  v_total numeric(14,2) := 0;
  v_item jsonb;
  v_product record;
  v_qty integer;
  v_line numeric(14,2);
  v_existing uuid;
begin
  if auth.uid() is null or not exists (
    select 1 from public.store_members sm
    where sm.store_id = p_store_id and sm.user_id = auth.uid()
  ) then
    raise exception 'Akun tidak memiliki akses ke toko ini';
  end if;
  if nullif(trim(coalesce(p_sale_code,'')),'') is null
     or p_payment_method not in ('Tunai','QRIS')
     or jsonb_typeof(p_items) <> 'array'
     or jsonb_array_length(p_items) < 1
     or jsonb_array_length(p_items) > 100 then
    raise exception 'Data transaksi tidak valid';
  end if;

  select id into v_existing from public.seamart_pos_sales
   where store_id = p_store_id and sale_code = p_sale_code;
  if v_existing is not null then
    return jsonb_build_object('id',v_existing,'duplicate',true);
  end if;

  -- Lock products in a deterministic order to prevent concurrent overselling.
  for v_item in
    select value from jsonb_array_elements(p_items)
    order by (value->>'product_id')
  loop
    v_qty := coalesce((v_item->>'quantity')::integer,0);
    if v_qty < 1 then raise exception 'Jumlah barang tidak valid'; end if;
    select id,name,barcode,price,stock into v_product
      from public.products
     where id = (v_item->>'product_id')::uuid
       and store_id = p_store_id
       and active = true
     for update;
    if not found then raise exception 'Barang tidak ditemukan atau tidak aktif'; end if;
    if coalesce(v_product.stock,0) < v_qty then
      raise exception 'Stok % tidak cukup. Tersedia: %',v_product.name,coalesce(v_product.stock,0);
    end if;
    v_total := v_total + (coalesce(v_product.price,0) * v_qty);
  end loop;

  if p_amount_paid < v_total then raise exception 'Pembayaran kurang'; end if;

  insert into public.seamart_pos_sales(
    id,store_id,sale_code,cashier_name,shift_ref,payment_method,total,
    amount_paid,change_amount,created_by
  ) values (
    v_sale_id,p_store_id,p_sale_code,p_cashier_name,p_shift_ref,p_payment_method,
    v_total,p_amount_paid,p_amount_paid-v_total,auth.uid()
  );

  for v_item in select value from jsonb_array_elements(p_items)
  loop
    v_qty := (v_item->>'quantity')::integer;
    select id,name,barcode,price,stock into v_product
      from public.products
     where id = (v_item->>'product_id')::uuid
       and store_id = p_store_id and active = true
     for update;
    v_line := coalesce(v_product.price,0) * v_qty;
    update public.products set stock = stock - v_qty
     where id = v_product.id and store_id = p_store_id;
    insert into public.seamart_pos_sale_items(
      sale_id,product_id,product_name,barcode,unit_price,quantity,line_total
    ) values (
      v_sale_id,v_product.id,v_product.name,v_product.barcode,
      coalesce(v_product.price,0),v_qty,v_line
    );
    insert into public.seamart_stock_movements(
      store_id,product_id,sale_id,movement_type,quantity,quantity_delta,note,created_by
    ) values (
      p_store_id,v_product.id,v_sale_id,'Keluar / Terjual',v_qty,-v_qty,
      'Transaksi '||p_sale_code,auth.uid()
    );
  end loop;

  insert into public.cash_flows(
    id,store_id,created_by,direction,category,description,amount,
    transaction_date,source,source_ref
  ) values (
    gen_random_uuid(),p_store_id,auth.uid(),'Masuk','Penjualan '||p_payment_method,
    'Transaksi '||p_sale_code,v_total,current_date,'sale',p_sale_code
  );

  return jsonb_build_object('id',v_sale_id,'sale_code',p_sale_code,'total',v_total,'duplicate',false);
end;
$$;

revoke all on function public.seamart_complete_pos_sale(uuid,text,text,text,text,numeric,jsonb) from public, anon;
grant execute on function public.seamart_complete_pos_sale(uuid,text,text,text,text,numeric,jsonb) to authenticated;
