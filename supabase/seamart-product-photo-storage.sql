-- SeaMart: bucket foto produk untuk sinkronisasi katalog online.
-- Jalankan sekali di Supabase SQL Editor.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('product-images', 'product-images', true, 5242880, array['image/jpeg','image/png','image/webp'])
on conflict (id) do update set public = true, file_size_limit = 5242880,
  allowed_mime_types = array['image/jpeg','image/png','image/webp'];

drop policy if exists "SeaMart authenticated upload product photos" on storage.objects;
create policy "SeaMart authenticated upload product photos"
on storage.objects for insert to authenticated
with check (bucket_id = 'product-images');

drop policy if exists "SeaMart authenticated update product photos" on storage.objects;
create policy "SeaMart authenticated update product photos"
on storage.objects for update to authenticated
using (bucket_id = 'product-images')
with check (bucket_id = 'product-images');

drop policy if exists "SeaMart authenticated view product photos" on storage.objects;
create policy "SeaMart authenticated view product photos"
on storage.objects for select to authenticated
using (bucket_id = 'product-images');

-- Bucket bersifat public agar foto dapat ditampilkan di katalog toko online.
