-- SeaMart online order accounting: stock, sales, and cash are posted at completion.
-- Run this migration in Supabase SQL Editor AFTER online_shop.sql,
-- seamart_pos_checkout.sql, and seamart_online_stock_on_completed.sql.
-- All writes below are transactional and idempotent per online order.

alter table public.online_orders
  add column if not exists stock_deducted boolean not null default false;

alter table public.seamart_stock_movements
  add column if not exists online_order_id uuid references public.online_orders(id) on delete set null;

create index if not exists seamart_stock_moves_online_order_idx
  on public.seamart_stock_movements(online_order_id);

alter table public.seamart_pos_sales
  add column if not exists online_order_id uuid references public.online_orders(id) on delete set null,
  add column if not exists source text not null default 'pos',
  add column if not exists payment_status text not null default 'paid';

alter table public.seamart_pos_sales
  drop constraint if exists seamart_pos_sales_payment_method_check,
  drop constraint if exists seamart_pos_sales_amount_paid_check;

alter table public.seamart_pos_sales
  add constraint seamart_pos_sales_payment_method_check
    check (payment_method in ('Tunai','QRIS','COD','Transfer Bank','Ambil di Toko')),
  add constraint seamart_pos_sales_amount_paid_check
    check (amount_paid >= 0),
  add constraint seamart_pos_sales_payment_status_check
    check (payment_status in ('unpaid','pending_verification','paid','failed'));

create unique index if not exists seamart_pos_sales_online_order_uidx
  on public.seamart_pos_sales(online_order_id)
  where online_order_id is not null;

create unique index if not exists cash_flows_online_order_uidx
  on public.cash_flows(store_id, source, source_ref)
  where source = 'online_order' and source_ref is not null;

create or replace function public.seamart_post_online_order_cash(
  p_order_id uuid
) returns void
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.online_orders%rowtype;
  v_method text;
begin
  select * into v_order
    from public.online_orders
   where id = p_order_id
   for update;
  if not found then raise exception 'Pesanan tidak ditemukan'; end if;

  if v_order.order_status <> 'completed' or v_order.payment_status <> 'paid' then
    return;
  end if;

  v_method := case v_order.payment_method
    when 'qris' then 'QRIS'
    when 'bank_transfer' then 'Transfer Bank'
    when 'cod' then 'COD'
    when 'store' then 'Ambil di Toko'
    else 'Pembayaran'
  end;

  insert into public.cash_flows(
    store_id,created_by,direction,category,description,amount,
    transaction_date,source,source_ref
  ) values (
    v_order.store_id,auth.uid(),'Masuk','Penjualan Online - '||v_method,
    'Pesanan '||v_order.order_code,v_order.subtotal,
    current_date,'online_order',v_order.id::text
  )
  on conflict (store_id,source,source_ref)
    where source = 'online_order' and source_ref is not null
  do nothing;
end;
$$;

create or replace function public.seamart_update_online_order_status(
  p_order_id uuid,
  p_status text
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.online_orders%rowtype;
  v_item record;
  v_product record;
  v_sale_id uuid;
  v_method text;
  v_paid numeric(14,2);
begin
  if auth.uid() is null then
    raise exception 'Silakan login terlebih dahulu';
  end if;
  if p_status not in ('new','confirmed','processing','ready','completed','cancelled') then
    raise exception 'Status pesanan tidak valid';
  end if;

  select * into v_order from public.online_orders
   where id = p_order_id for update;
  if not found then raise exception 'Pesanan tidak ditemukan'; end if;

  if not exists (
    select 1 from public.store_members sm
     where sm.store_id = v_order.store_id
       and sm.user_id = auth.uid()
       and sm.role in ('owner','admin')
  ) then
    raise exception 'Perubahan status hanya boleh dilakukan dengan persetujuan admin toko';
  end if;

  if v_order.order_status in ('completed','cancelled') then
    raise exception 'Pesanan sudah final dan dikunci. Koreksi memerlukan persetujuan admin khusus.';
  end if;

  if p_status = 'completed' and not v_order.stock_deducted then
    -- Validate all stock first; transaction rollback protects against partial deduction.
    for v_item in
      select product_id,sum(quantity)::integer as qty
        from public.online_order_items
       where order_id = v_order.id
       group by product_id order by product_id
    loop
      select id,name,stock into v_product
        from public.products
       where id = v_item.product_id and store_id = v_order.store_id
       for update;
      if not found then raise exception 'Barang pesanan tidak ditemukan di master barang'; end if;
      if coalesce(v_product.stock,0) < v_item.qty then
        raise exception 'Stok % tidak cukup. Tersedia: %, dibutuhkan: %',
          v_product.name,coalesce(v_product.stock,0),v_item.qty;
      end if;
    end loop;

    for v_item in
      select product_id,sum(quantity)::integer as qty
        from public.online_order_items
       where order_id = v_order.id
       group by product_id order by product_id
    loop
      select id,name into v_product
        from public.products
       where id = v_item.product_id and store_id = v_order.store_id
       for update;
      update public.products set stock = stock - v_item.qty
       where id = v_product.id and store_id = v_order.store_id;
      insert into public.seamart_stock_movements(
        store_id,product_id,online_order_id,movement_type,quantity,quantity_delta,note,created_by
      ) values (
        v_order.store_id,v_product.id,v_order.id,'Keluar / Pesanan Online',
        v_item.qty,-v_item.qty,'Pesanan selesai '||v_order.order_code,auth.uid()
      );
    end loop;

    update public.online_orders set stock_deducted = true where id = v_order.id;
    v_order.stock_deducted := true;
  end if;

  update public.online_orders
     set order_status = p_status,updated_at = now()
   where id = v_order.id;

  if p_status = 'completed' then
    v_method := case v_order.payment_method
      when 'qris' then 'QRIS'
      when 'bank_transfer' then 'Transfer Bank'
      when 'cod' then 'COD'
      when 'store' then 'Ambil di Toko'
      else 'Pembayaran'
    end;
    v_paid := case when v_order.payment_status = 'paid' then v_order.subtotal else 0 end;

    insert into public.seamart_pos_sales(
      store_id,sale_code,cashier_name,shift_ref,payment_method,total,
      amount_paid,change_amount,created_by,online_order_id,source,payment_status
    ) values (
      v_order.store_id,'ONLINE-'||v_order.order_code,'Pesanan Online',null,
      v_method,v_order.subtotal,v_paid,0,auth.uid(),v_order.id,'online_order',v_order.payment_status
    )
    on conflict (store_id,sale_code) do nothing;

    select id into v_sale_id from public.seamart_pos_sales
     where store_id = v_order.store_id and sale_code = 'ONLINE-'||v_order.order_code;

    if not exists (
      select 1 from public.seamart_pos_sale_items where sale_id = v_sale_id
    ) then
      insert into public.seamart_pos_sale_items(
        sale_id,product_id,product_name,barcode,unit_price,quantity,line_total
      )
      select v_sale_id,product_id,product_name,barcode,unit_price,quantity,line_total
        from public.online_order_items where order_id = v_order.id;
    end if;

    perform public.seamart_post_online_order_cash(v_order.id);
  end if;

  return jsonb_build_object(
    'id',v_order.id,'order_code',v_order.order_code,
    'order_status',p_status,'stock_deducted',v_order.stock_deducted,
    'sale_posted',(p_status='completed')
  );
end;
$$;

create or replace function public.seamart_update_online_order_payment_status(
  p_order_id uuid,
  p_payment_status text
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.online_orders%rowtype;
  v_method text;
begin
  if auth.uid() is null then raise exception 'Silakan login terlebih dahulu'; end if;
  if p_payment_status not in ('unpaid','pending_verification','paid','failed') then
    raise exception 'Status pembayaran tidak valid';
  end if;

  select * into v_order from public.online_orders
   where id = p_order_id for update;
  if not found then raise exception 'Pesanan tidak ditemukan'; end if;

  if not exists (
    select 1 from public.store_members sm
     where sm.store_id = v_order.store_id
       and sm.user_id = auth.uid()
       and sm.role in ('owner','admin')
  ) then
    raise exception 'Hanya admin toko yang boleh mengubah status pembayaran';
  end if;
  if v_order.order_status = 'cancelled' then
    raise exception 'Pesanan dibatalkan; status pembayaran dikunci';
  end if;

  update public.online_orders
     set payment_status = p_payment_status,updated_at = now()
   where id = v_order.id;

  update public.seamart_pos_sales
     set payment_status = p_payment_status,
         amount_paid = case when p_payment_status='paid' then total else 0 end
   where online_order_id = v_order.id;

  perform public.seamart_post_online_order_cash(v_order.id);

  return jsonb_build_object(
    'id',v_order.id,'payment_status',p_payment_status,
    'cash_posted',(v_order.order_status='completed' and p_payment_status='paid')
  );
end;
$$;

revoke all on function public.seamart_post_online_order_cash(uuid) from public,anon;
revoke all on function public.seamart_update_online_order_status(uuid,text) from public,anon;
revoke all on function public.seamart_update_online_order_payment_status(uuid,text) from public,anon;
grant execute on function public.seamart_update_online_order_status(uuid,text) to authenticated;
grant execute on function public.seamart_update_online_order_payment_status(uuid,text) to authenticated;
