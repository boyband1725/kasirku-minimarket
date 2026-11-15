-- SeaMart: deduct online-order stock only when an admin marks the order Selesai.
-- Orders in Baru / Dikonfirmasi / Diproses / Siap do not change inventory.
-- Only an authenticated owner/admin can change status. Completed/cancelled orders are final.

alter table public.online_orders
  add column if not exists stock_deducted boolean not null default false;

alter table public.seamart_stock_movements
  add column if not exists online_order_id uuid references public.online_orders(id) on delete set null;

create index if not exists seamart_stock_moves_online_order_idx
  on public.seamart_stock_movements(online_order_id);

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
begin
  if auth.uid() is null then
    raise exception 'Silakan login terlebih dahulu';
  end if;

  if p_status not in ('new','confirmed','processing','ready','completed','cancelled') then
    raise exception 'Status pesanan tidak valid';
  end if;

  select * into v_order
    from public.online_orders
   where id = p_order_id
   for update;
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
    raise exception 'Pesanan sudah final. Perubahan lanjutan memerlukan proses persetujuan admin khusus.';
  end if;

  -- Inventory is deducted exactly once, only at the Selesai transition.
  if p_status = 'completed' and not v_order.stock_deducted then
    for v_item in
      select product_id, sum(quantity)::integer as qty
        from public.online_order_items
       where order_id = v_order.id
       group by product_id
       order by product_id
    loop
      select id,name,stock into v_product
        from public.products
       where id = v_item.product_id
         and store_id = v_order.store_id
       for update;

      if not found then
        raise exception 'Barang pesanan tidak ditemukan di master barang';
      end if;

      if coalesce(v_product.stock,0) < v_item.qty then
        raise exception 'Stok % tidak cukup. Tersedia: %, dibutuhkan: %',
          v_product.name,coalesce(v_product.stock,0),v_item.qty;
      end if;
    end loop;

    for v_item in
      select product_id, sum(quantity)::integer as qty
        from public.online_order_items
       where order_id = v_order.id
       group by product_id
       order by product_id
    loop
      select id,name into v_product
        from public.products
       where id = v_item.product_id
         and store_id = v_order.store_id
       for update;

      update public.products
         set stock = stock - v_item.qty
       where id = v_product.id
         and store_id = v_order.store_id;

      insert into public.seamart_stock_movements(
        store_id,product_id,online_order_id,movement_type,quantity,quantity_delta,note,created_by
      ) values (
        v_order.store_id,v_product.id,v_order.id,'Keluar / Pesanan Online',
        v_item.qty,-v_item.qty,'Pesanan selesai '||v_order.order_code,auth.uid()
      );
    end loop;

    update public.online_orders
       set stock_deducted = true
     where id = v_order.id;
    v_order.stock_deducted := true;

  elsif p_status = 'cancelled' and v_order.stock_deducted then
    -- Safety recovery for legacy orders that were deducted by the previous rule.
    for v_item in
      select product_id, sum(quantity)::integer as qty
        from public.online_order_items
       where order_id = v_order.id
       group by product_id
       order by product_id
    loop
      select id,name into v_product
        from public.products
       where id = v_item.product_id and store_id = v_order.store_id
       for update;
      if not found then
        raise exception 'Barang pesanan tidak ditemukan saat pengembalian stok';
      end if;

      update public.products
         set stock = coalesce(stock,0) + v_item.qty
       where id = v_product.id and store_id = v_order.store_id;

      insert into public.seamart_stock_movements(
        store_id,product_id,online_order_id,movement_type,quantity,quantity_delta,note,created_by
      ) values (
        v_order.store_id,v_product.id,v_order.id,'Masuk / Pembatalan Pesanan Online',
        v_item.qty,v_item.qty,'Pengembalian stok pesanan '||v_order.order_code,auth.uid()
      );
    end loop;

    update public.online_orders set stock_deducted = false where id = v_order.id;
    v_order.stock_deducted := false;
  end if;

  update public.online_orders
     set order_status = p_status, updated_at = now()
   where id = v_order.id;

  return jsonb_build_object(
    'id',v_order.id,
    'order_code',v_order.order_code,
    'order_status',p_status,
    'stock_deducted',v_order.stock_deducted
  );
end;
$$;

revoke all on function public.seamart_update_online_order_status(uuid,text) from public, anon;
grant execute on function public.seamart_update_online_order_status(uuid,text) to authenticated;
