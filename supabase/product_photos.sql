-- SeaMart product photo sync
-- Run once in Supabase SQL Editor.
alter table public.products add column if not exists image_url text;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('seamart-product-photos', 'seamart-product-photos', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public = true, file_size_limit = 5242880, allowed_mime_types = array['image/jpeg','image/png','image/webp'];

drop policy if exists "SeaMart public read product photos" on storage.objects;
create policy "SeaMart public read product photos" on storage.objects
for select to anon, authenticated
using (bucket_id = 'seamart-product-photos');

drop policy if exists "SeaMart members upload product photos" on storage.objects;
create policy "SeaMart members upload product photos" on storage.objects
for insert to authenticated
with check (
  bucket_id = 'seamart-product-photos'
  and exists (
    select 1 from public.store_members sm
    where sm.user_id = auth.uid()
      and sm.store_id::text = (storage.foldername(name))[1]
      and sm.role in ('owner','admin')
  )
);

drop policy if exists "SeaMart members update product photos" on storage.objects;
create policy "SeaMart members update product photos" on storage.objects
for update to authenticated
using (
  bucket_id = 'seamart-product-photos'
  and exists (
    select 1 from public.store_members sm
    where sm.user_id = auth.uid()
      and sm.store_id::text = (storage.foldername(name))[1]
      and sm.role in ('owner','admin')
  )
)
with check (
  bucket_id = 'seamart-product-photos'
  and exists (
    select 1 from public.store_members sm
    where sm.user_id = auth.uid()
      and sm.store_id::text = (storage.foldername(name))[1]
      and sm.role in ('owner','admin')
  )
);
