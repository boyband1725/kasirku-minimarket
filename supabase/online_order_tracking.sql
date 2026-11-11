-- Public customer order tracking. Requires both unpredictable order code and matching phone number.
create or replace function public.seamart_track_online_order(
  p_order_code text,
  p_customer_phone text
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.online_orders%rowtype;
  v_phone text;
  v_items jsonb;
begin
  v_phone := regexp_replace(coalesce(p_customer_phone,''),'[^0-9]','','g');
  if length(trim(coalesce(p_order_code,''))) < 8 or length(v_phone) < 8 then
    raise exception 'Nomor pesanan atau WhatsApp tidak valid';
  end if;

  select * into v_order
    from public.online_orders
   where upper(order_code) = upper(trim(p_order_code))
     and regexp_replace(customer_phone,'[^0-9]','','g') = v_phone
   limit 1;

  if not found then
    raise exception 'Pesanan tidak ditemukan. Periksa nomor pesanan dan WhatsApp.';
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'name', product_name, 'quantity', quantity,
    'unit_price', unit_price, 'line_total', line_total
  ) order by product_name), '[]'::jsonb)
    into v_items
    from public.online_order_items where order_id = v_order.id;

  return jsonb_build_object(
    'order_code', v_order.order_code,
    'customer_name', v_order.customer_name,
    'order_status', v_order.order_status,
    'payment_status', v_order.payment_status,
    'payment_method', v_order.payment_method,
    'subtotal', v_order.subtotal,
    'created_at', v_order.created_at,
    'updated_at', v_order.updated_at,
    'items', v_items
  );
end;
$$;

revoke all on function public.seamart_track_online_order(text,text) from public;
grant execute on function public.seamart_track_online_order(text,text) to anon, authenticated;
