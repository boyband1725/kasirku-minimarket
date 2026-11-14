-- Enable Supabase Realtime updates for SeaMart product stock.
-- Safe to run repeatedly; does not modify product rows or stock values.
do $$
begin
  if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
     and not exists (
       select 1 from pg_publication_tables
       where pubname = 'supabase_realtime'
         and schemaname = 'public'
         and tablename = 'products'
     ) then
    alter publication supabase_realtime add table public.products;
  end if;
end
$$;
