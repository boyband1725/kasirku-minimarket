-- SeaMart: lock payment status once an online order is marked PAID.
-- Run this patch in Supabase SQL Editor. It only replaces the payment-status RPC.

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
begin
  if auth.uid() is null then
    raise exception 'Silakan login terlebih dahulu';
  end if;

  if p_payment_status not in ('unpaid','pending_verification','paid','failed') then
    raise exception 'Status pembayaran tidak valid';
  end if;

  select * into v_order
    from public.online_orders
   where id = p_order_id
   for update;

  if not found then
    raise exception 'Pesanan tidak ditemukan';
  end if;

  if not exists (
    select 1
      from public.store_members sm
     where sm.store_id = v_order.store_id
       and sm.user_id = auth.uid()
       and sm.role in ('owner','admin')
  ) then
    raise exception 'Hanya admin toko yang boleh mengubah status pembayaran';
  end if;

  if v_order.order_status in ('cancelled','completed') then
    raise exception 'Pesanan sudah final; status pembayaran dikunci';
  end if;

  -- Once marked paid, payment status cannot be changed back.
  if v_order.payment_status = 'paid' and p_payment_status <> 'paid' then
    raise exception 'Pembayaran sudah LUNAS dan dikunci. Perubahan memerlukan koreksi admin khusus.';
  end if;

  update public.online_orders
     set payment_status = p_payment_status,
         updated_at = now()
   where id = v_order.id;

  update public.seamart_pos_sales
     set payment_status = p_payment_status,
         amount_paid = case when p_payment_status = 'paid' then total else 0 end
   where online_order_id = v_order.id;

  perform public.seamart_post_online_order_cash(v_order.id);

  return jsonb_build_object(
    'id', v_order.id,
    'payment_status', p_payment_status,
    'cash_posted', (v_order.order_status = 'completed' and p_payment_status = 'paid')
  );
end;
$$;

revoke all on function public.seamart_update_online_order_payment_status(uuid,text) from public, anon;
grant execute on function public.seamart_update_online_order_payment_status(uuid,text) to authenticated;

notify pgrst, 'reload schema';
