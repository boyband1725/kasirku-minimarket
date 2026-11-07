package id.kasirku.minimarket

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.UUID
import java.util.UUID
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.util.Locale

private val SeaGreen = Color(0xFF145B45)
private val SeaMint = Color(0xFFE8F3EE)
private val SeaBg = Color(0xFFF5F8F6)
private val Ink = Color(0xFF1B2923)

data class Product(val id:Long,val name:String,val barcode:String,val category:String,val cost:Long,val price:Long,val stock:Int,val minStock:Int)
class StoreDb(c:Context):SQLiteOpenHelper(c,"kasirku.db",null,5){
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,barcode TEXT UNIQUE,price INTEGER NOT NULL,stock INTEGER NOT NULL,category TEXT NOT NULL DEFAULT '',cost INTEGER NOT NULL DEFAULT 0,min_stock INTEGER NOT NULL DEFAULT 5)")
  db.execSQL("CREATE TABLE sales(id INTEGER PRIMARY KEY AUTOINCREMENT,total INTEGER,created_at TEXT DEFAULT CURRENT_TIMESTAMP,method TEXT DEFAULT 'Tunai',shift_id INTEGER)")
  db.execSQL("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,username TEXT UNIQUE NOT NULL,pin_hash TEXT NOT NULL,salt TEXT NOT NULL,role TEXT NOT NULL)")
  db.execSQL("CREATE TABLE shifts(id INTEGER PRIMARY KEY AUTOINCREMENT,opened_by TEXT,opening_cash INTEGER,opened_at TEXT DEFAULT CURRENT_TIMESTAMP,closed_by TEXT,closing_cash INTEGER,expected_cash INTEGER,closed_at TEXT,status TEXT DEFAULT 'OPEN')")
 }
 override fun onUpgrade(db:SQLiteDatabase,old:Int,now:Int){
  if(old<3){
   addColumn(db,"category","TEXT NOT NULL DEFAULT ''")
   addColumn(db,"cost","INTEGER NOT NULL DEFAULT 0")
   addColumn(db,"min_stock","INTEGER NOT NULL DEFAULT 5")
   try{db.execSQL("ALTER TABLE sales ADD COLUMN method TEXT NOT NULL DEFAULT 'Tunai'")}catch(_:Exception){}
  }
  if(old<4){try{db.execSQL("ALTER TABLE sales ADD COLUMN shift_id INTEGER")}catch(_:Exception){};db.execSQL("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY AUTOINCREMENT,username TEXT UNIQUE NOT NULL,pin_hash TEXT NOT NULL,salt TEXT NOT NULL,role TEXT NOT NULL)");db.execSQL("CREATE TABLE IF NOT EXISTS shifts(id INTEGER PRIMARY KEY AUTOINCREMENT,opened_by TEXT,opening_cash INTEGER,opened_at TEXT DEFAULT CURRENT_TIMESTAMP,closed_by TEXT,closing_cash INTEGER,expected_cash INTEGER,closed_at TEXT,status TEXT DEFAULT 'OPEN')")}
 }
 private fun addColumn(db:SQLiteDatabase,name:String,type:String){try{db.execSQL("ALTER TABLE products ADD COLUMN $name $type")}catch(_:Exception){}}
 fun hasUsers():Boolean=readableDatabase.rawQuery("SELECT COUNT(*) FROM users",null).use{it.moveToFirst()&&it.getInt(0)>0}
 private fun pinHash(pin:String,salt:String)=MessageDigest.getInstance("SHA-256").digest((salt+pin).toByteArray()).joinToString(""){"%02x".format(it)}
 fun addUser(username:String,pin:String,role:String){require(username.isNotBlank()&&pin.length>=4);val salt=UUID.randomUUID().toString();val v=ContentValues().apply{put("username",username.trim());put("salt",salt);put("pin_hash",pinHash(pin,salt));put("role",role)};writableDatabase.insertOrThrow("users",null,v)}
 fun authenticate(username:String,pin:String):String?=readableDatabase.rawQuery("SELECT pin_hash,salt,role FROM users WHERE username=? COLLATE NOCASE",arrayOf(username.trim())).use{if(!it.moveToFirst())null else if(it.getString(0)==pinHash(pin,it.getString(1)))it.getString(2) else null}
 fun openShift(user:String,cash:Long):Long{require(activeShift()==null);val v=ContentValues().apply{put("opened_by",user);put("opening_cash",cash);put("status","OPEN")};return writableDatabase.insertOrThrow("shifts",null,v)}
 fun activeShift():Long?=readableDatabase.rawQuery("SELECT id FROM shifts WHERE status='OPEN' ORDER BY id DESC LIMIT 1",null).use{if(it.moveToFirst())it.getLong(0)else null}
 fun closeShift(user:String,cash:Long,shiftId:Long):Long{val expected=readableDatabase.rawQuery("SELECT opening_cash+COALESCE((SELECT SUM(total) FROM sales WHERE shift_id=? AND method='Tunai'),0) FROM shifts WHERE id=?",arrayOf(shiftId.toString(),shiftId.toString())).use{if(it.moveToFirst())it.getLong(0)else 0L};val v=ContentValues().apply{put("closed_by",user);put("closing_cash",cash);put("expected_cash",expected);put("closed_at",java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.US).format(java.util.Date()));put("status","CLOSED")};writableDatabase.update("shifts",v,"id=? AND status='OPEN'",arrayOf(shiftId.toString()));return expected}
 fun all():List<Product>{ fun all():List<Product>{val out=mutableListOf<Product>();readableDatabase.rawQuery("SELECT id,name,COALESCE(barcode,''),COALESCE(category,''),cost,price,stock,min_stock FROM products ORDER BY name COLLATE NOCASE",null).use{c->while(c.moveToNext())out.add(Product(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4),c.getLong(5),c.getInt(6),c.getInt(7)))};return out}
 fun save(p:Product){val v=ContentValues().apply{put("name",p.name);put("barcode",p.barcode.ifBlank{null});put("category",p.category);put("cost",p.cost);put("price",p.price);put("stock",p.stock);put("min_stock",p.minStock)};if(p.id>0)writableDatabase.update("products",v,"id=?",arrayOf(p.id.toString()))else writableDatabase.insertOrThrow("products",null,v)}
 fun upsert(name:String,barcode:String,category:String,cost:Long,price:Long,stock:Int,minStock:Int){val found=if(barcode.isBlank())null else readableDatabase.rawQuery("SELECT id FROM products WHERE barcode=?",arrayOf(barcode)).use{if(it.moveToFirst())it.getLong(0)else null};save(Product(found?:0,name,barcode,category,cost,price,stock,minStock))}
 fun delete(id:Long){writableDatabase.delete("products","id=?",arrayOf(id.toString()))}
 fun completeSale(total:Long,method:String,items:Map<Long,Int>,shiftId:Long){val db=writableDatabase;db.beginTransaction();try{db.execSQL("INSERT INTO sales(total,method,shift_id) VALUES(?,?,?)",arrayOf(total,method,shiftId));items.forEach{(id,qty)->db.execSQL("UPDATE products SET stock=MAX(0,stock-?) WHERE id=?",arrayOf(qty,id))};db.setTransactionSuccessful()}finally{db.endTransaction()}}
 fun sales():List<Pair<Long,String>>{val out=mutableListOf<Pair<Long,String>>();readableDatabase.rawQuery("SELECT total,COALESCE(created_at,'' ) FROM sales ORDER BY id DESC LIMIT 100",null).use{c->while(c.moveToNext())out.add(c.getLong(0) to c.getString(1))};return out}
}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{KasirKuApp()}}}
private fun money(v:Long)=NumberFormat.getCurrencyInstance(Locale("id","ID")).format(v)
private fun parseCsv(text:String):List<List<String>>{val rows=mutableListOf<List<String>>();var row=mutableListOf<String>();val cell=StringBuilder();var quoted=false;val first=text.substringBefore('\n');val delimiter=if(first.count{it==';'}>first.count{it==','})';' else ',';var i=0;while(i<text.length){val ch=text[i];if(ch=='"'){if(quoted&&i+1<text.length&&text[i+1]=='"'){cell.append('"');i++}else quoted=!quoted}else if(ch==delimiter&&!quoted){row.add(cell.toString());cell.clear()}else if((ch=='\n'||ch=='\r')&&!quoted){if(ch=='\r'&&i+1<text.length&&text[i+1]=='\n')i++;row.add(cell.toString());if(row.any{it.isNotBlank()})rows.add(row);row=mutableListOf();cell.clear()}else cell.append(ch);i++};row.add(cell.toString());if(row.any{it.isNotBlank()})rows.add(row);return rows}
private fun printBluetoothReceipt(context:Context,address:String,receipt:String,onResult:(String)->Unit){
 Thread{
  try{
   val adapter=(context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?:throw IllegalStateException("Bluetooth tidak tersedia")
   val device=adapter.getRemoteDevice(address)
   val socket=device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
   socket.connect()
   socket.outputStream.use{out->
    out.write(byteArrayOf(0x1B,0x40))
    out.write(byteArrayOf(0x1B,0x61,0x01))
    out.write(byteArrayOf(0x1B,0x45,0x01))
    out.write("SEAMART\\n".toByteArray(Charsets.US_ASCII))
    out.write(byteArrayOf(0x1B,0x45,0x00))
    out.write("MINIMARKET POS\\n".toByteArray(Charsets.US_ASCII))
    out.write(byteArrayOf(0x1B,0x61,0x00))
    out.write("--------------------------------\\n".toByteArray(Charsets.US_ASCII))
    out.write(receipt.toByteArray(Charsets.US_ASCII))
    out.write("\\n\\nTerima kasih\\n".toByteArray(Charsets.US_ASCII))
    out.write(byteArrayOf(0x1B,0x64,0x04))
    out.write(byteArrayOf(0x1D,0x56,0x00))
    out.flush()
   }
   socket.close()
   Handler(Looper.getMainLooper()).post{onResult("Struk berhasil dikirim ke printer")}
  }catch(e:Exception){Handler(Looper.getMainLooper()).post{onResult("Cetak gagal: ${e.message}")}}
 }.start()
}
private fun csvCell(v:String)="\"" + v.replace("\"", "\"\"") + "\""
private fun parseAmount(raw:String):Long{var s=raw.trim().replace("Rp","",true).replace(" ","");if(s.contains(',')&&s.contains('.')){s=if(s.lastIndexOf(',')>s.lastIndexOf('.'))s.replace(".","").replace(',','.')else s.replace(",","")}else if(s.contains(',')){s=if(s.substringAfterLast(',').length==2)s.replace(',','.')else s.replace(",","")};return s.filter{it.isDigit()||it=='.'}.substringBefore('.').toLongOrNull()?:0L}

@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable fun KasirKuApp(){
 val ctx=LocalContext.current;val db=remember{StoreDb(ctx)}
 var products by remember{mutableStateOf(db.all())};var tab by remember{mutableIntStateOf(0)}
 var search by remember{mutableStateOf("")};var barcode by remember{mutableStateOf("")};var cart by remember{mutableStateOf(mapOf<Long,Int>())}
 var message by remember{mutableStateOf("")};var editing by remember{mutableStateOf<Product?>(null)};var showEditor by remember{mutableStateOf(false)};var showPay by remember{mutableStateOf(false)}
 var paymentMethod by remember{mutableStateOf("Tunai")};var paid by remember{mutableStateOf("")}
 var loggedUser by remember{mutableStateOf<String?>(null)}
 var userRole by remember{mutableStateOf<String?>(null)}
 var loginName by remember{mutableStateOf("")}
 var loginPin by remember{mutableStateOf("")}
 var firstAdminName by remember{mutableStateOf("admin")}
 var firstAdminPin by remember{mutableStateOf("")}
 var openingCash by remember{mutableStateOf("0")}
 var closingCash by remember{mutableStateOf("")}
 var activeShiftId by remember{mutableStateOf(db.activeShift())}
 var showCreateCashier by remember{mutableStateOf(false)}
 var newCashierName by remember{mutableStateOf("")}
 var newCashierPin by remember{mutableStateOf("")}
 val prefs=remember{ctx.getSharedPreferences("kasirku_settings",Context.MODE_PRIVATE)}
 var allowZeroStock by remember{mutableStateOf(prefs.getBoolean("allow_zero_stock",true))}
 var selectedPrinter by remember{mutableStateOf(prefs.getString("printer_address",null))}
 var selectedPrinterName by remember{mutableStateOf(prefs.getString("printer_name",null))}
 var showPrinterPicker by remember{mutableStateOf(false)}
 var lastReceipt by remember{mutableStateOf(prefs.getString("last_receipt","")?: "")}
 val bluetoothPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted)showPrinterPicker=true else message="Izin Bluetooth diperlukan untuk memilih printer"}
 val openPrinterPicker={if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.S&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)else showPrinterPicker=true}

 val refresh={products=db.all()}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri:Uri?->if(uri!=null)try{ctx.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{w->w.appendLine("barcode,nama,kategori,harga_modal,harga_jual,stok,stok_minimum");products.forEach{p->w.appendLine(listOf(p.barcode,p.name,p.category,p.cost.toString(),p.price.toString(),p.stock.toString(),p.minStock.toString()).joinToString(","){csvCell(it)})}};message="Ekspor CSV berhasil"}catch(e:Exception){message="Ekspor gagal: ${e.message}"}}
 val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri:Uri?->if(uri!=null)try{var added=0;var updated=0;ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use{reader->val rows=parseCsv(reader.readText().removePrefix("\uFEFF"));if(rows.size<2)throw IllegalArgumentException("CSV kosong");val h=rows.first().map{it.trim().lowercase().replace(" ","_")};val ni=h.indexOfFirst{it in listOf("nama","name","nama_barang","nama_produk")};val bi=h.indexOfFirst{it in listOf("barcode","sku","kode","kode_barang")};val ci=h.indexOfFirst{it in listOf("kategori","category")};val coi=h.indexOfFirst{it in listOf("harga_modal","modal","harga_beli","cost")};val pi=h.indexOfFirst{it in listOf("harga_jual","harga","price")};val si=h.indexOfFirst{it in listOf("stok","stock","jumlah")};val mi=h.indexOfFirst{it in listOf("stok_minimum","min_stok","minimum_stok","min")};if(ni<0||pi<0||si<0)throw IllegalArgumentException("Kolom wajib: nama, harga_jual, stok");rows.drop(1).forEach{r->val get:(Int)->String={i->if(i>=0&&i<r.size)r[i].trim() else ""};val name=get(ni);if(name.isNotBlank()){val old=if(get(bi).isBlank())null else products.firstOrNull{it.barcode==get(bi)};db.upsert(name,get(bi),get(ci),parseAmount(get(coi)),parseAmount(get(pi)),parseAmount(get(si)).toInt(),parseAmount(get(mi).ifBlank{"5"}).toInt());if(old==null)added++ else updated++}}};refresh();message="Impor selesai: $added barang baru, $updated diperbarui"}catch(e:Exception){message="Impor gagal: ${e.message}"}}
 val addScannedBarcode={val code=barcode.trim();val p=products.firstOrNull{it.barcode==code};if(p!=null&&(allowZeroStock||p.stock>(cart[p.id]?:0))){cart=cart+(p.id to ((cart[p.id]?:0)+1));message="${p.name} ditambahkan"}else message=if(p==null)"Barcode tidak ditemukan: $code" else "Stok tidak cukup";barcode=""}
 val total=cart.entries.sumOf{(id,q)->(products.find{it.id==id}?.price?:0L)*q}
 val visible=products.filter{it.name.contains(search,true)||it.barcode.contains(search,true)||it.category.contains(search,true)}
 
 if(loggedUser==null){
  MaterialTheme(colorScheme=lightColorScheme(primary=SeaGreen,onPrimary=Color.White,background=SeaBg,surface=Color.White)){
   Surface(Modifier.fillMaxSize(),color=SeaBg){Column(Modifier.fillMaxWidth().padding(24.dp).padding(top=48.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
    Text("SeaMart",fontSize=30.sp,fontWeight=FontWeight.Bold,color=SeaGreen);Text("MINIMARKET POS",color=SeaGreen)
    if(!db.hasUsers()){
     Text("Buat Admin Pertama",fontSize=22.sp,fontWeight=FontWeight.Bold)
     OutlinedTextField(firstAdminName,{firstAdminName=it},label={Text("Nama pengguna admin")},modifier=Modifier.fillMaxWidth())
     OutlinedTextField(firstAdminPin,{firstAdminPin=it},label={Text("PIN admin (minimal 4 angka)")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
     Button(onClick={try{db.addUser(firstAdminName,firstAdminPin,"ADMIN");loggedUser=firstAdminName;userRole="ADMIN";activeShiftId=db.activeShift();message="Admin berhasil dibuat"}catch(e:Exception){message="Gagal membuat admin: ${e.message}"}},enabled=firstAdminName.isNotBlank()&&firstAdminPin.length>=4,modifier=Modifier.fillMaxWidth()){Text("Buat Admin")}
    }else{
     Text("Login",fontSize=22.sp,fontWeight=FontWeight.Bold)
     OutlinedTextField(loginName,{loginName=it},label={Text("Nama pengguna")},modifier=Modifier.fillMaxWidth())
     OutlinedTextField(loginPin,{loginPin=it},label={Text("PIN")},modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))
     Button(onClick={val role=db.authenticate(loginName,loginPin);if(role!=null){loggedUser=loginName.trim();userRole=role;activeShiftId=db.activeShift();loginPin="";message="Login berhasil"}else message="Nama pengguna atau PIN salah"},enabled=loginName.isNotBlank()&&loginPin.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Masuk")}
    }
    if(message.isNotBlank())Text(message,color=SeaGreen)
   }}
  }
  return
 }
\n MaterialTheme(colorScheme=lightColorScheme(primary=SeaGreen MaterialTheme(colorScheme=lightColorScheme(primary=SeaGreen,onPrimary=Color.White,secondary=SeaGreen,background=SeaBg,surface=Color.White,onSurface=Ink)){
 Scaffold(containerColor=SeaBg,topBar={TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){Surface(shape=RoundedCornerShape(12.dp),color=SeaGreen){Box(Modifier.size(42.dp),contentAlignment=Alignment.Center){Text("\$",fontSize=27.sp,fontWeight=FontWeight.Bold,color=Color.White)}};Column{Text("SeaMart",fontWeight=FontWeight.Bold);Text("MINIMARKET POS",fontSize=11.sp,color=SeaGreen)}}})},bottomBar={NavigationBar(containerColor=Color.White){(if(userRole=="ADMIN")listOf("Kasir","Barang","Laporan","CSV","Pengaturan")else listOf("Kasir","Pengaturan")).forEach{label->val i=if(userRole=="ADMIN")listOf("Kasir","Barang","Laporan","CSV","Pengaturan").indexOf(label)else if(label=="Kasir")0 else 4;NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("▦","▤","▥","⇅","⚙")[i],fontSize=18.sp)},label={Text(label)})}}}){pad->
 Column(Modifier.fillMaxSize().padding(pad).padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
  if(message.isNotBlank())Text(message,color=SeaGreen,style=MaterialTheme.typography.bodySmall)
  when(tab){
   0->{
    if(activeShiftId==null){
     Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Kasir belum dibuka",fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Petugas: $loggedUser");OutlinedTextField(openingCash,{openingCash=it.filter(Char::isDigit)},label={Text("Modal awal / uang di laci")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth());Button(onClick={try{activeShiftId=db.openShift(loggedUser?: "Kasir",openingCash.toLongOrNull()?:0L);message="Shift kasir berhasil dibuka"}catch(e:Exception){message="Gagal membuka kasir: ${e.message}"}},modifier=Modifier.fillMaxWidth()){Text("Buka Kasir")}}}
    }else{
     Row(verticalAlignment=Alignment.CenterVertically){Text("Kasir aktif • $loggedUser",fontWeight=FontWeight.Bold,color=SeaGreen,modifier=Modifier.weight(1f));Text("SHIFT #$activeShiftId",fontSize=11.sp,color=SeaGreen)}
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Card(Modifier.weight(1f),colors=CardDefaults.cardColors(containerColor=SeaMint)){Column(Modifier.padding(12.dp)){Text("Produk tersedia",fontSize=12.sp);Text(products.size.toString(),fontSize=22.sp,fontWeight=FontWeight.Bold,color=SeaGreen)}};Card(Modifier.weight(1f),colors=CardDefaults.cardColors(containerColor=SeaMint)){Column(Modifier.padding(12.dp)){Text("Item di keranjang",fontSize=12.sp);Text(cart.values.sum().toString(),fontSize=22.sp,fontWeight=FontWeight.Bold,color=SeaGreen)}}}
    OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Cari nama / barcode barang")})
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(barcode,{barcode=it},modifier=Modifier.weight(1f),singleLine=true,label={Text("Scan Bluetooth / USB barcode")},keyboardOptions=KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Done),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onDone={addScannedBarcode()}));Button(onClick={addScannedBarcode}){Text("Tambah")}}
    Text("Pilih Produk  •  ${visible.size} barang",fontWeight=FontWeight.Bold)
    LazyVerticalGrid(columns=GridCells.Fixed(2),modifier=Modifier.weight(1f),contentPadding=PaddingValues(bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){items(visible){p->Card(shape=RoundedCornerShape(14.dp),colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(p.category.ifBlank{"PRODUK"},fontSize=10.sp,color=SeaGreen,fontWeight=FontWeight.Bold);Text(p.name,fontWeight=FontWeight.SemiBold,minLines=2);Text(money(p.price),fontWeight=FontWeight.Bold,color=SeaGreen);Text("Stok ${p.stock}",fontSize=11.sp,color=Color.Gray);Button(onClick={if(allowZeroStock||p.stock>(cart[p.id]?:0))cart=cart+(p.id to ((cart[p.id]?:0)+1))else message="Stok tidak cukup"},enabled=allowZeroStock||p.stock>(cart[p.id]?:0),modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(4.dp)){Text("+ Tambah")}}}}}
    Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text("Keranjang",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={cart=emptyMap()}){Text("Kosongkan")}};if(cart.isEmpty())Text("Belum ada barang",color=Color.Gray)else cart.forEach{(id,q)->val p=products.firstOrNull{it.id==id};if(p!=null)Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Medium);Text(money(p.price*q),fontSize=12.sp,color=Color.Gray)};TextButton(onClick={cart=if(q<=1)cart-id else cart+(id to q-1)}){Text("−")};Text(q.toString());TextButton(onClick={if(allowZeroStock||q<p.stock)cart=cart+(id to q+1)else message="Stok maksimum"}){Text("+")}}};Divider();Row{Text("Total",fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));Text(money(total),fontWeight=FontWeight.Bold,color=SeaGreen,fontSize=20.sp)};Button(onClick={showPay=true},enabled=cart.isNotEmpty()&&activeShiftId!=null,modifier=Modifier.fillMaxWidth()){Text("Bayar  •  ${money(total)}")}}}
   }
   }
   1->{Row(verticalAlignment=Alignment.CenterVertically){Text("Master Barang",fontWeight=FontWeight.Bold,fontSize=21.sp,modifier=Modifier.weight(1f));Button(onClick={editing=null;showEditor=true}){Text("+ Barang")}};OutlinedTextField(search,{search=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("Cari barang")});LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)){items(visible){p->Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Text(p.name,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={editing=p;showEditor=true}){Text("Edit")}};Text("Barcode: ${p.barcode.ifBlank{"- "}}  •  Stok: ${p.stock}",fontSize=12.sp,color=Color.Gray);Text("Modal ${money(p.cost)}  |  Jual ${money(p.price)}",fontSize=13.sp,color=SeaGreen);Row{TextButton(onClick={db.save(p.copy(stock=(p.stock+1)));refresh()}){Text("+ Stok")};TextButton(onClick={db.save(p.copy(stock=(p.stock-1).coerceAtLeast(0)));refresh()}){Text("− Stok")};Spacer(Modifier.weight(1f));TextButton(onClick={db.delete(p.id);refresh();message="Barang dihapus"}){Text("Hapus",color=Color.Red)}}}}}}}
   2->{Text("Laporan penjualan",fontWeight=FontWeight.Bold,fontSize=21.sp);val sales=db.sales();Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(14.dp)){Text("Transaksi terakhir",fontWeight=FontWeight.Bold);if(sales.isEmpty())Text("Belum ada transaksi",color=Color.Gray)else sales.forEach{(amount,date)->Row(Modifier.fillMaxWidth().padding(vertical=6.dp)){Text(date,modifier=Modifier.weight(1f),fontSize=12.sp);Text(money(amount),fontWeight=FontWeight.SemiBold,color=SeaGreen)}}}}}
   3->{Text("Impor / Ekspor Barang",fontWeight=FontWeight.Bold,fontSize=21.sp);Text("Jumlah barang saat ini: ${products.size}");Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Kolom CSV: barcode, nama, kategori, harga_modal, harga_jual, stok, stok_minimum",fontSize=12.sp);Button(onClick={export.launch("seamart-barang.csv")},modifier=Modifier.fillMaxWidth()){Text("Ekspor CSV")};OutlinedButton(onClick={import.launch(arrayOf("text/*","application/csv","application/vnd.ms-excel"))},modifier=Modifier.fillMaxWidth()){Text("Impor CSV")};Text("Impor akan memperbarui barang dengan barcode yang sama, bukan menghapus seluruh daftar.",fontSize=12.sp,color=Color.Gray)}}}
   4->{Text("Pengaturan",fontWeight=FontWeight.Bold,fontSize=21.sp);Text("Login: $loggedUser • ${userRole?: ""}",color=SeaGreen);if(activeShiftId!=null){OutlinedTextField(closingCash,{closingCash=it.filter(Char::isDigit)},label={Text("Uang fisik saat tutup kasir")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth());Button(onClick={val expected=db.closeShift(loggedUser?: "Kasir",closingCash.toLongOrNull()?:0L,activeShiftId!!);activeShiftId=null;closingCash="";cart=emptyMap();message="Kasir ditutup. Perkiraan uang tunai: ${money(expected)}"},modifier=Modifier.fillMaxWidth()){Text("Tutup Kasir")}};TextButton(onClick={loggedUser=null;userRole=null;tab=0}){Text("Keluar / Ganti Pengguna")};if(userRole=="ADMIN")TextButton(onClick={showCreateCashier=true}){Text("Tambah Akun Kasir")};Card(colors=CardDefaults.cardColors(containerColor=Color.White)){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Izinkan penjualan saat stok 0",fontWeight=FontWeight.SemiBold);Text("Barang tetap dapat dijual meskipun stok tercatat 0. Stok tidak akan menjadi minus.",fontSize=12.sp,color=Color.Gray)};Switch(checked=allowZeroStock,onCheckedChange={enabled->allowZeroStock=enabled;prefs.edit().putBoolean("allow_zero_stock",enabled).apply();message=if(enabled)"Penjualan stok 0 diizinkan" else "Penjualan stok 0 dinonaktifkan"})};Divider();Text("Printer struk Bluetooth",fontWeight=FontWeight.SemiBold);Text(selectedPrinterName?: "Belum ada printer dipilih",fontSize=12.sp,color=Color.Gray);Button(onClick=openPrinterPicker,modifier=Modifier.fillMaxWidth()){Text("Pilih / Hubungkan Printer")};OutlinedButton(onClick={if(selectedPrinter.isNullOrBlank())message="Pilih printer Bluetooth terlebih dahulu" else if(lastReceipt.isBlank())message="Belum ada struk untuk dicetak ulang" else printBluetoothReceipt(ctx,selectedPrinter!!,lastReceipt){message=it}},enabled=selectedPrinter!=null&&lastReceipt.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Cetak Ulang Struk Terakhir")};Text("Gunakan printer thermal Bluetooth dengan mode ESC/POS (SPP), kertas 58 mm.",fontSize=12.sp,color=Color.Gray)}}}
  }
 }
}}
 if(showCreateCashier){
  AlertDialog(onDismissRequest={showCreateCashier=false},title={Text("Tambah Akun Kasir")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(newCashierName,{newCashierName=it},label={Text("Nama pengguna")});OutlinedTextField(newCashierPin,{newCashierPin=it},label={Text("PIN minimal 4 angka")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword))}},confirmButton={TextButton(onClick={try{db.addUser(newCashierName,newCashierPin,"KASIR");showCreateCashier=false;newCashierName="";newCashierPin="";message="Akun kasir dibuat"}catch(e:Exception){message="Gagal membuat akun: ${e.message}"}} ,enabled=newCashierName.isNotBlank()&&newCashierPin.length>=4){Text("Simpan")}},dismissButton={TextButton(onClick={showCreateCashier=false}){Text("Batal")}})
 }
 if(showEditor){ if(showEditor){ProductEditor(product=editing,onDismiss={showEditor=false},onSave={p->try{db.save(p);refresh();showEditor=false;message="Barang berhasil disimpan"}catch(e:Exception){message="Gagal menyimpan: barcode mungkin sudah digunakan"}})}
 if(showPay){AlertDialog(onDismissRequest={showPay=false},title={Text("Pembayaran")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("Total: ${money(total)}",fontWeight=FontWeight.Bold);Row(verticalAlignment=Alignment.CenterVertically){RadioButton(paymentMethod=="Tunai",{paymentMethod="Tunai"});Text("Tunai");Spacer(Modifier.width(16.dp));RadioButton(paymentMethod=="QRIS",{paymentMethod="QRIS"});Text("QRIS")};if(paymentMethod=="Tunai")OutlinedTextField(paid,{paid=it},label={Text("Uang diterima")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));if(paymentMethod=="Tunai"){val change=(paid.toLongOrNull()?:0L)-total;Text("Kembalian: ${money(change.coerceAtLeast(0))}")}}},confirmButton={TextButton(onClick={val cash=if(paymentMethod=="Tunai")paid.toLongOrNull()?:0L else total;if(cash<total){message="Uang pembayaran kurang"}else{val cashPaid=if(paymentMethod=="Tunai")paid.toLongOrNull()?:0L else total;val change=(cashPaid-total).coerceAtLeast(0);val receipt=buildString{appendLine("No: ${System.currentTimeMillis()}");appendLine("Tanggal: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm",Locale("id","ID")).format(java.util.Date())}");appendLine("--------------------------------");cart.forEach{(id,qty)->val item=products.firstOrNull{it.id==id};if(item!=null){appendLine(item.name);appendLine("${qty} x ${item.price} = ${item.price*qty}")}};appendLine("--------------------------------");appendLine("TOTAL: Rp$total");appendLine("Bayar: Rp$cashPaid");appendLine("Kembali: Rp$change");appendLine("Metode: $paymentMethod")};db.completeSale(total,paymentMethod,cart,activeShiftId?:0L);lastReceipt=receipt;prefs.edit().putString("last_receipt",receipt).apply();refresh();cart=emptyMap();showPay=false;paid="";if(!selectedPrinter.isNullOrBlank())printBluetoothReceipt(ctx,selectedPrinter!!,receipt){message=it}else message="Transaksi berhasil. Pilih printer di Pengaturan untuk mencetak struk."}}){Text("Selesaikan")}},dismissButton={TextButton(onClick={showPay=false}){Text("Batal")}})}
 if(showPrinterPicker){
  val pairedPrinters=remember(showPrinterPicker){try{val adapter=(ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter;adapter?.bondedDevices?.toList()?.sortedBy{it.name?:it.address}?:emptyList()}catch(_:SecurityException){emptyList()}}
  AlertDialog(onDismissRequest={showPrinterPicker=false},title={Text("Pilih Printer Bluetooth")},text={Column(verticalArrangement=Arrangement.spacedBy(4.dp)){if(pairedPrinters.isEmpty())Text("Tidak ada perangkat yang sudah dipasangkan. Pasangkan printer melalui Pengaturan Bluetooth HP terlebih dahulu.");pairedPrinters.forEach{device->TextButton(onClick={selectedPrinter=device.address;selectedPrinterName=device.name?:device.address;prefs.edit().putString("printer_address",device.address).putString("printer_name",selectedPrinterName).apply();showPrinterPicker=false;message="Printer dipilih: $selectedPrinterName"}){Column(Modifier.fillMaxWidth()){Text(device.name?: "Printer Bluetooth");Text(device.address,fontSize=11.sp,color=Color.Gray)}}}}},confirmButton={TextButton(onClick={showPrinterPicker=false}){Text("Tutup")}})
 }
}

@Composable private fun ProductEditor(product:Product?,onDismiss:()->Unit,onSave:(Product)->Unit){var name by remember(product){mutableStateOf(product?.name?:"")};var barcode by remember(product){mutableStateOf(product?.barcode?:"")};var category by remember(product){mutableStateOf(product?.category?:"")};var cost by remember(product){mutableStateOf(product?.cost?.toString()?:"0")};var price by remember(product){mutableStateOf(product?.price?.toString()?:"")};var stock by remember(product){mutableStateOf(product?.stock?.toString()?:"0")};var min by remember(product){mutableStateOf(product?.minStock?.toString()?:"5")}
 AlertDialog(onDismissRequest=onDismiss,title={Text(if(product==null)"Tambah Barang" else "Edit Barang")},text={Column(verticalArrangement=Arrangement.spacedBy(5.dp)){OutlinedTextField(name,{name=it},label={Text("Nama barang")});OutlinedTextField(barcode,{barcode=it},label={Text("Barcode")});OutlinedTextField(category,{category=it},label={Text("Kategori")});OutlinedTextField(cost,{cost=it},label={Text("Harga modal")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(price,{price=it},label={Text("Harga jual")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(stock,{stock=it},label={Text("Stok")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number));OutlinedTextField(min,{min=it},label={Text("Stok minimum")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))}},confirmButton={TextButton(onClick={val p=price.toLongOrNull();val s=stock.toIntOrNull();if(name.isNotBlank()&&p!=null&&s!=null&&s>=0)onSave(Product(product?.id?:0,name.trim(),barcode.trim(),category.trim(),cost.toLongOrNull()?:0,p,s,min.toIntOrNull()?:5))}){Text("Simpan")}},dismissButton={TextButton(onClick=onDismiss){Text("Batal")}})
}
