-- SeaMart: import historical browser-only POS sales into the shared cloud.
-- Safe/idempotent: does not change product stock and never duplicates a sale_code.
create or replace function public.seamart_import_legacy_pos_sale(
 p_store_id uuid,p_sale_code text,p_cashier_name text,p_shift_ref text,
 p_payment_method text,p_total numeric,p_amount_paid numeric,p_change_amount numeric,
 p_created_at timestamptz,p_items jsonb
) returns jsonb language plpgsql security definer set search_path=public as $$
declare
 v_sale_id uuid; v_item jsonb; v_product record; v_qty integer;
 v_price numeric(14,2); v_line numeric(14,2); v_sum numeric(14,2):=0;
 v_barcode text; v_name text;
begin
 if auth.uid() is null or not exists(select 1 from public.store_members sm where sm.store_id=p_store_id and sm.user_id=auth.uid()) then
  raise exception 'Akun tidak memiliki akses ke toko ini';
 end if;
 if nullif(trim(coalesce(p_sale_code,'')),'') is null or p_payment_method not in ('Tunai','QRIS')
 or p_total<0 or p_amount_paid<p_total or jsonb_typeof(p_items)<>'array'
 or jsonb_array_length(p_items)<1 or jsonb_array_length(p_items)>100 then
  raise exception 'Data transaksi lama tidak valid';
 end if;
 select id into v_sale_id from public.seamart_pos_sales where store_id=p_store_id and sale_code=p_sale_code;
 if v_sale_id is not null then return jsonb_build_object('id',v_sale_id,'duplicate',true); end if;
 for v_item in select value from jsonb_array_elements(p_items) loop
  v_qty:=coalesce((v_item->>'qty')::integer,0); v_price:=coalesce((v_item->>'harga')::numeric,0);
  v_line:=coalesce((v_item->>'subtotal')::numeric,v_price*v_qty);
  v_barcode:=nullif(trim(coalesce(v_item->>'barcode','')),''); v_name:=trim(coalesce(v_item->>'nama',''));
  if v_qty<1 or v_price<0 or v_line<0 then raise exception 'Rincian barang transaksi lama tidak valid'; end if;
  select id,name,barcode into v_product from public.products where store_id=p_store_id and
   ((v_barcode is not null and barcode=v_barcode) or (v_barcode is null and lower(trim(name))=lower(v_name)))
   order by case when barcode=v_barcode then 0 else 1 end limit 1;
  if not found then raise exception 'Barang % tidak ditemukan di master cloud',coalesce(v_name,v_barcode); end if;
  v_sum:=v_sum+v_line;
 end loop;
 if abs(v_sum-p_total)>0.01 then raise exception 'Total rincian (%) tidak sama dengan total transaksi (%)',v_sum,p_total; end if;
 insert into public.seamart_pos_sales(store_id,sale_code,cashier_name,shift_ref,payment_method,total,amount_paid,change_amount,created_by,created_at)
 values(p_store_id,p_sale_code,p_cashier_name,p_shift_ref,p_payment_method,p_total,p_amount_paid,p_change_amount,auth.uid(),coalesce(p_created_at,now()))
 returning id into v_sale_id;
 for v_item in select value from jsonb_array_elements(p_items) loop
  v_qty:=(v_item->>'qty')::integer; v_price:=coalesce((v_item->>'harga')::numeric,0);
  v_line:=coalesce((v_item->>'subtotal')::numeric,v_price*v_qty);
  v_barcode:=nullif(trim(coalesce(v_item->>'barcode','')),''); v_name:=trim(coalesce(v_item->>'nama',''));
  select id,name,barcode into v_product from public.products where store_id=p_store_id and
   ((v_barcode is not null and barcode=v_barcode) or (v_barcode is null and lower(trim(name))=lower(v_name)))
   order by case when barcode=v_barcode then 0 else 1 end limit 1;
  insert into public.seamart_pos_sale_items(sale_id,product_id,product_name,barcode,unit_price,quantity,line_total)
  values(v_sale_id,v_product.id,v_name,v_barcode,v_price,v_qty,v_line);
 end loop;
 insert into public.cash_flows(store_id,created_by,direction,category,description,amount,transaction_date,source,source_ref)
 values(p_store_id,auth.uid(),'Masuk','Penjualan '||p_payment_method,'Impor riwayat transaksi lama '||p_sale_code,
 p_total,coalesce(p_created_at::date,current_date),'sale',p_sale_code) on conflict do nothing;
 return jsonb_build_object('id',v_sale_id,'sale_code',p_sale_code,'duplicate',false,'stock_changed',false);
end; $$;
revoke all on function public.seamart_import_legacy_pos_sale(uuid,text,text,text,text,numeric,numeric,numeric,timestamptz,jsonb) from public,anon;
grant execute on function public.seamart_import_legacy_pos_sale(uuid,text,text,text,text,numeric,numeric,numeric,timestamptz,jsonb) to authenticated;
