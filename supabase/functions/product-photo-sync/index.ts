import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function reply(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json; charset=utf-8" },
  });
}

function tokens(value: string) {
  return value.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9 ]/g, " ").split(/\s+/)
    .filter((part) => part.length >= 3 && !["produk", "barang", "pack", "pcs", "piece", "gram"].includes(part));
}

function imageDimensions(bytes: Uint8Array, mime: string): { width: number; height: number } | null {
  if (mime === "image/png" && bytes.length >= 24) {
    return { width: new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(16), height: new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength).getUint32(20) };
  }
  if (mime === "image/jpeg") {
    let offset = 2;
    while (offset + 9 < bytes.length) {
      if (bytes[offset] !== 0xff) { offset++; continue; }
      const marker = bytes[offset + 1];
      const length = (bytes[offset + 2] << 8) | bytes[offset + 3];
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker)) {
        return { height: (bytes[offset + 5] << 8) | bytes[offset + 6], width: (bytes[offset + 7] << 8) | bytes[offset + 8] };
      }
      if (length < 2) break;
      offset += 2 + length;
    }
  }
  return null;
}

async function getCatalogProduct(barcode: string) {
  const fields = "code,product_name,image_front_url,selected_images";
  const urls = [
    "https://world.openfoodfacts.org/api/v3/product/" + encodeURIComponent(barcode) + "?product_type=all&fields=" + fields,
    "https://world.openfoodfacts.org/api/v2/product/" + encodeURIComponent(barcode) + ".json?fields=" + fields,
  ];
  const errors: string[] = [];
  for (const url of urls) {
    try {
      const response = await fetch(url, { headers: { "User-Agent": "SeaMart/1.0 (product photo sync)" } });
      if (!response.ok) { errors.push("HTTP " + response.status); continue; }
      const data = await response.json();
      const product = data.product;
      if (product?.product_name) return product;
      errors.push("Produk tidak terdaftar");
    } catch (error) {
      errors.push(error instanceof Error ? error.message : "Gagal menghubungi katalog");
    }
  }
  throw new Error("Katalog tidak menemukan produk: " + errors.join("; "));
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return reply({ error: "Gunakan metode POST." }, 405);

  try {
    const authorization = req.headers.get("Authorization") || "";
    if (!authorization.startsWith("Bearer ")) return reply({ error: "Silakan login ulang ke SeaMart." }, 401);

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    if (!supabaseUrl || !anonKey) return reply({ error: "Konfigurasi Supabase belum lengkap." }, 500);

    const supabase = createClient(supabaseUrl, anonKey, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: auth, error: authError } = await supabase.auth.getUser();
    if (authError || !auth.user) return reply({ error: "Sesi login tidak valid." }, 401);

    const body = await req.json();
    const productId = String(body?.productId || "").trim();
    if (!productId || productId.length > 100) return reply({ error: "ID produk tidak valid." }, 400);

    const { data: member, error: memberError } = await supabase.from("store_members")
      .select("store_id").eq("user_id", auth.user.id).limit(1).maybeSingle();
    if (memberError || !member) return reply({ error: "Akun belum terhubung ke toko SeaMart." }, 403);

    const { data: product, error: productError } = await supabase.from("products")
      .select("id,store_id,name,barcode,image_url").eq("id", productId)
      .eq("store_id", member.store_id).maybeSingle();
    if (productError || !product) return reply({ error: "Produk tidak ditemukan atau akses ditolak." }, 404);
    if (product.image_url) return reply({ success: true, skipped: true, productName: product.name, imageUrl: product.image_url });
    if (!product.barcode) return reply({ error: "Produk tidak memiliki barcode." }, 400);

    const catalog = await getCatalogProduct(String(product.barcode).trim());
    const sourceName = String(catalog.product_name || "");
    const expected = tokens(product.name || "");
    const actual = tokens(sourceName);
    const shared = expected.filter((token) => actual.includes(token));
    if (!shared.length) return reply({ error: "Nama katalog tidak cocok dengan nama barang SeaMart; foto ditolak.", sourceName }, 422);

    const imageUrl = catalog.image_front_url ||
      catalog.selected_images?.front?.display?.en ||
      catalog.selected_images?.front?.display?.[Object.keys(catalog.selected_images?.front?.display || {})[0]];
    if (!imageUrl) return reply({ error: "Produk ditemukan, tetapi foto depan tidak tersedia.", sourceName }, 404);

    const parsed = new URL(imageUrl);
    if (parsed.protocol !== "https:" || !/(^|\.)openfoodfacts\.org$/i.test(parsed.hostname)) {
      return reply({ error: "Alamat sumber foto tidak diizinkan." }, 400);
    }

    const imageResponse = await fetch(imageUrl, { headers: { "User-Agent": "SeaMart/1.0 (product photo sync)" } });
    if (!imageResponse.ok) return reply({ error: "Produk ditemukan, tetapi unduh foto gagal: HTTP " + imageResponse.status, sourceName }, 502);
    const mime = (imageResponse.headers.get("content-type") || "").split(";")[0].toLowerCase();
    if (!["image/jpeg", "image/png"].includes(mime)) return reply({ error: "Format foto tidak didukung: " + mime, sourceName }, 415);
    const bytes = new Uint8Array(await imageResponse.arrayBuffer());
    if (bytes.length < 5000 || bytes.length > 8 * 1024 * 1024) return reply({ error: "Ukuran file foto tidak layak untuk katalog.", sourceName }, 422);
    const dimensions = imageDimensions(bytes, mime);
    if (!dimensions) return reply({ error: "Ukuran piksel foto tidak dapat diverifikasi; foto ditolak.", sourceName }, 422);
    if (dimensions.width < 350 || dimensions.height < 350) {
      return reply({ error: "Foto terlalu kecil (" + dimensions.width + "×" + dimensions.height + " piksel).", sourceName }, 422);
    }
    const ratio = dimensions.width / dimensions.height;
    if (ratio < 0.3 || ratio > 2.5) return reply({ error: "Proporsi foto tidak layak.", sourceName }, 422);

    const path = member.store_id + "/" + product.id + (mime === "image/png" ? ".png" : ".jpg");
    const { error: uploadError } = await supabase.storage.from("seamart-product-photos")
      .upload(path, new Blob([bytes], { type: mime }), { contentType: mime, upsert: true, cacheControl: "3600" });
    if (uploadError) return reply({ error: "Gagal menyimpan foto ke Storage: " + uploadError.message, sourceName }, 502);

    const publicUrl = supabase.storage.from("seamart-product-photos").getPublicUrl(path).data.publicUrl;
    const { error: updateError } = await supabase.from("products").update({ image_url: publicUrl })
      .eq("id", product.id).eq("store_id", member.store_id);
    if (updateError) return reply({ error: "Foto terunggah tetapi gagal memperbarui data barang: " + updateError.message, sourceName }, 502);

    return reply({ success: true, productName: product.name, sourceName, imageUrl: publicUrl, width: dimensions.width, height: dimensions.height });
  } catch (error) {
    console.error("product-photo-sync error:", error);
    return reply({ error: error instanceof Error ? error.message : "Gagal memproses foto produk." }, 500);
  }
});
