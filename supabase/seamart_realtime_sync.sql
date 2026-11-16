-- SeaMart realtime sync hardening
-- Run this migration in the Supabase SQL Editor for the SeaMart project.
-- It enables the tables already watched by index.html's realtime client.
do $$
declare
  t text;
begin
  if not exists (select 1 from pg_publication where pubname = 'supabase_realtime') then
    raise exception 'Publication supabase_realtime tidak ditemukan. Pastikan Realtime aktif di project Supabase.';
  end if;

  foreach t in array array['products','categories','seamart_pos_sales','cash_flows','online_orders'] loop
    if to_regclass('public.' || t) is null then
      raise exception 'Tabel public.% belum ada. Jalankan migrasi SeaMart POS terlebih dahulu.', t;
    end if;

    if not exists (
      select 1
      from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename = t
    ) then
      execute format('alter publication supabase_realtime add table public.%I', t);
    end if;
  end loop;
end
$$;

-- Keep the browser's realtime client able to receive updates under RLS.
-- These grants do not bypass row-level security; existing table policies still apply.
grant select on public.products to authenticated;
grant select on public.categories to authenticated;
grant select on public.seamart_pos_sales to authenticated;
grant select on public.cash_flows to authenticated;
grant select on public.online_orders to authenticated;
