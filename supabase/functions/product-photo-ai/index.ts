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

function normalize(value: string) {
  return value.toLowerCase().normalize("NFD").replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9. ]/g, " ").replace(/\s+/g, " ").trim();
}

function tokens(value: string) {
  return normalize(value).split(" ")
    .filter((part) => part.length >= 3 && !["produk", "barang", "pack", "pcs", "piece", "gram", "gr"].includes(part));
}

function variants(value: string) {
  const n = normalize(value), result: string[] = [];
  const re = /(\d+(?:\.\d+)?)\s*(ml|milliliter|l|liter|gr|g|kg|kilogram)\b/g;
  let match: RegExpExecArray | null;
  while ((match = re.exec(n)) !== null) {
    let unit = match[2];
    if (unit === "milliliter") unit = "ml";
    if (unit === "liter") unit = "l";
    if (unit === "kilogram") unit = "kg";
    if (unit === "gr") unit = "g";
    result.push(Number(match[1]) + " " + unit);
  }
  if (/\b(galon|gal\.?|dispenser)\b/.test(n)) result.push("kemasan-galon");
  if (/\b(botol|btl)\b/.test(n)) result.push("kemasan-botol");
  if (/\b(kaleng|can)\b/.test(n)) result.push("kemasan-kaleng");
  if (/\b(sachet|saset)\b/.test(n)) result.push("kemasan-sachet");
  return [...new Set(result)];
}

function imageDimensions(bytes: Uint8Array, mime: string) {
  if (mime === "image/png" && bytes.length >= 24) {
    const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
    return { width: view.getUint32(16), height: view.getUint32(20) };
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

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return reply({ error: "Gunakan metode POST." }, 405);

  try {
    const authorization = req.headers.get("Authorization") || "";
    if (!authorization.startsWith("Bearer ")) {
      return reply({ error: "Sesi SeaMart tidak ditemukan. Silakan login ulang." }, 401);
    }

    const supabaseUrl = Deno.env.get("SUPABASE_URL");
    const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
    if (!supabaseUrl || !anonKey) return reply({ error: "Konfigurasi Supabase belum lengkap." }, 500);

    const supabase = createClient(supabaseUrl, anonKey, {
      global: { headers: { Authorization: authorization } },
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const { data: authData, error: authError } = await supabase.auth.getUser();
    if (authError || !authData.user) return reply({ error: "Sesi login tidak valid. Silakan login ulang." }, 401);

    const body = await req.json();
    const productName = String(body?.productName || "").trim().slice(0, 160);
    const barcode = String(body?.barcode || "").trim().replace(/\D/g, "").slice(0, 80);
    const imageUrl = String(body?.imageUrl || "").trim();
    const sourceName = String(body?.sourceName || "").trim().slice(0, 200);
    if (!productName || !barcode || !imageUrl || !sourceName) {
      return reply({ error: "Nama produk, barcode, foto, dan nama produk katalog wajib tersedia." }, 400);
    }

    const { data: member, error: memberError } = await supabase.from("store_members")
      .select("store_id").eq("user_id", authData.user.id).limit(1).maybeSingle();
    if (memberError || !member) return reply({ error: "Akun ini belum terdaftar sebagai anggota toko SeaMart." }, 403);

    // Verifikasi katalog gratis berdasarkan barcode; tidak memakai OpenAI atau API berbayar.
    const catalogUrl = "https://world.openfoodfacts.org/api/v2/product/" +
      encodeURIComponent(barcode) + ".json?fields=code,product_name,image_front_url,selected_images";
    const catalogResponse = await fetch(catalogUrl, {
      headers: { "User-Agent": "SeaMart/1.0 (free product photo verification)" },
    });
    if (!catalogResponse.ok) return reply({ error: "Katalog Open Food Facts sedang tidak tersedia (HTTP " + catalogResponse.status + ")." }, 502);
    const catalogData = await catalogResponse.json();
    const catalog = catalogData?.product;
    if (catalogData?.status !== 1 || !catalog?.product_name) {
      return reply({ match: false, confidence: 0, reason: "Barcode belum ditemukan di katalog Open Food Facts.", productName, sourceName }, 200);
    }

    const officialName = String(catalog.product_name);
    if (normalize(officialName) !== normalize(sourceName)) {
      return reply({ match: false, confidence: 0, reason: "Nama katalog tidak sama dengan sumber foto yang diajukan.", productName, sourceName: officialName }, 200);
    }

    const wanted = tokens(productName), found = tokens(officialName);
    const shared = wanted.filter((token) => found.includes(token));
    if (!shared.length) {
      return reply({ match: false, confidence: 0, reason: "Nama produk SeaMart tidak cocok dengan nama pada katalog.", productName, sourceName: officialName }, 200);
    }

    const wantedVariants = variants(productName), foundVariants = variants(officialName);
    const wantedSizes = wantedVariants.filter((item) => /^\d/.test(item));
    const foundSizes = foundVariants.filter((item) => /^\d/.test(item));
    const wantedPacks = wantedVariants.filter((item) => item.startsWith("kemasan-"));
    const foundPacks = foundVariants.filter((item) => item.startsWith("kemasan-"));
    if ((wantedSizes.length && foundSizes.length && !wantedSizes.some((item) => foundSizes.includes(item))) ||
        (wantedPacks.length && foundPacks.length && !wantedPacks.some((item) => foundPacks.includes(item)))) {
      return reply({ match: false, confidence: 0, reason: "Ukuran atau jenis kemasan berbeda; foto ditolak.", productName, sourceName: officialName }, 200);
    }

    const front = catalog.selected_images?.front;
    const display = front?.display || {};
    const catalogImages = [
      catalog.image_front_url,
      typeof display === "string" ? display : display?.en,
      ...(typeof display === "object" && display ? Object.values(display) : []),
    ].filter((value) => typeof value === "string") as string[];
    const parsed = new URL(imageUrl);
    if (parsed.protocol !== "https:" || !/(^|\.)openfoodfacts\.org$/i.test(parsed.hostname)) {
      return reply({ match: false, confidence: 0, reason: "Alamat foto bukan berasal dari katalog Open Food Facts.", productName, sourceName: officialName }, 200);
    }
    if (!catalogImages.some((candidate) => candidate === imageUrl)) {
      return reply({ match: false, confidence: 0, reason: "Foto tidak cocok dengan foto depan yang tercatat pada barcode katalog.", productName, sourceName: officialName }, 200);
    }

    const imageResponse = await fetch(imageUrl, {
      headers: { "User-Agent": "SeaMart/1.0 (free product photo verification)" },
    });
    if (!imageResponse.ok) return reply({ error: "Foto katalog tidak dapat diunduh (HTTP " + imageResponse.status + ")." }, 502);
    const mime = (imageResponse.headers.get("content-type") || "").split(";")[0].toLowerCase();
    if (!["image/jpeg", "image/png"].includes(mime)) return reply({ match: false, confidence: 0, reason: "Format foto katalog tidak didukung.", productName, sourceName: officialName }, 200);
    const bytes = new Uint8Array(await imageResponse.arrayBuffer());
    if (bytes.length < 5000 || bytes.length > 8 * 1024 * 1024) {
      return reply({ match: false, confidence: 0, reason: "Ukuran file foto tidak layak untuk katalog.", productName, sourceName: officialName }, 200);
    }
    const dimensions = imageDimensions(bytes, mime);
    if (!dimensions || dimensions.width < 350 || dimensions.height < 350) {
      return reply({ match: false, confidence: 0, reason: "Foto terlalu kecil atau ukuran piksel tidak dapat diverifikasi.", productName, sourceName: officialName }, 200);
    }
    const ratio = dimensions.width / dimensions.height;
    if (ratio < 0.3 || ratio > 2.5) {
      return reply({ match: false, confidence: 0, reason: "Proporsi foto tidak layak untuk katalog.", productName, sourceName: officialName }, 200);
    }

    return reply({
      match: true,
      confidence: 95,
      reason: "Barcode, nama katalog, ukuran, sumber foto, dan kualitas gambar cocok. Pemeriksaan dilakukan tanpa OpenAI API.",
      productName,
      sourceName: officialName,
      storeId: member.store_id,
      width: dimensions.width,
      height: dimensions.height,
    });
  } catch (error) {
    console.error("product-photo-ai free verification error:", error);
    return reply({
      error: error instanceof Error ? error.message : "Terjadi kesalahan saat memeriksa foto produk.",
    }, 500);
  }
});
