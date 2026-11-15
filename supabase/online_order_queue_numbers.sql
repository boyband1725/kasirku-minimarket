-- SeaMart: automatic customer queue numbers and tracking by queue number + phone.
-- Queue numbers increment per store (A-001, A-002, ...), so they remain unique.
alter table public.online_orders
  add column if not exists queue_number bigint;

with numbered as (
  select id, row_number() over (partition by store_id order by created_at, id) as rn
  from public.online_orders
  where queue_number is null
)
update public.online_orders o
   set queue_number = n.rn
  from numbered n
 where o.id = n.id;

create table if not exists public.seamart_order_counters (
  store_id uuid primary key,
  last_number bigint not null default 0
);

insert into public.seamart_order_counters(store_id,last_number)
select store_id, coalesce(max(queue_number),0)
from public.online_orders
group by store_id
on conflict (store_id) do update
set last_number = greatest(public.seamart_order_counters.last_number, excluded.last_number);

create unique index if not exists online_orders_store_queue_unique
  on public.online_orders(store_id, queue_number);

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
  v_queue bigint;
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

  -- Atomic counter: simultaneous checkouts receive different queue numbers.
  insert into public.seamart_order_counters(store_id,last_number)
  values (p_store_id,1)
  on conflict (store_id) do update
    set last_number = public.seamart_order_counters.last_number + 1
  returning last_number into v_queue;

  insert into public.online_orders(
    id, store_id, order_code, queue_number, customer_name, customer_phone,
    customer_address, customer_note, payment_method, payment_status,
    order_status, subtotal
  ) values (
    v_order_id, p_store_id, v_code, v_queue, trim(p_customer_name),
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

  return jsonb_build_object(
    'id',v_order_id,
    'order_code',v_code,
    'queue_number',v_queue,
    'queue_label','A-' || lpad(v_queue::text,3,'0'),
    'subtotal',v_subtotal
  );
end;
$$;

revoke all on function public.seamart_create_online_order(uuid,text,text,text,text,text,jsonb) from public;
grant execute on function public.seamart_create_online_order(uuid,text,text,text,text,text,jsonb) to anon, authenticated;

create or replace function public.seamart_track_online_order(
  p_queue_number text,
  p_customer_phone text
) returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_order public.online_orders%rowtype;
  v_phone text;
  v_number bigint;
  v_items jsonb;
begin
  v_phone := regexp_replace(coalesce(p_customer_phone,''),'[^0-9]','','g');
  v_number := nullif(regexp_replace(upper(trim(coalesce(p_queue_number,''))),'[^0-9]','','g'),'')::bigint;
  if v_number is null or length(v_phone) < 8 then
    raise exception 'Nomor antrean atau nomor HP tidak valid';
  end if;

  select * into v_order
    from public.online_orders
   where queue_number = v_number
     and regexp_replace(customer_phone,'[^0-9]','','g') = v_phone
   order by created_at desc
   limit 1;

  if not found then
    raise exception 'Pesanan tidak ditemukan. Periksa nomor antrean dan nomor HP.';
  end if;

  select coalesce(jsonb_agg(jsonb_build_object(
    'name', product_name,
    'quantity', quantity,
    'unit_price', unit_price,
    'line_total', line_total
  ) order by product_name), '[]'::jsonb)
    into v_items
    from public.online_order_items
   where order_id = v_order.id;

  return jsonb_build_object(
    'queue_number', v_order.queue_number,
    'queue_label', 'A-' || lpad(v_order.queue_number::text,3,'0'),
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

notify pgrst, 'reload schema';
