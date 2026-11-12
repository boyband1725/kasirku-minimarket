-- SeaMart Online: migration untuk foto produk dan katalog publik.
-- Jalankan di Supabase SQL Editor setelah meninjau kebijakan keamanan.
alter table public.products add column if not exists image_url text;

-- Katalog hanya menampilkan produk aktif. Jangan aktifkan akses tulis publik ke products.
alter table public.products enable row level security;
drop policy if exists "Public can view active SeaMart products" on public.products;
create policy "Public can view active SeaMart products"
on public.products for select to anon, authenticated
using (active = true);

alter table public.categories enable row level security;
drop policy if exists "Public can view SeaMart categories" on public.categories;
create policy "Public can view SeaMart categories"
on public.categories for select to anon, authenticated
using (true);
