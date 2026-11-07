package id.kasirku.minimarket

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.content.Context
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.util.Locale

data class Product(val id:Long,val name:String,val barcode:String,val costPrice:Long,val price:Long,val stock:Int)
class StoreDb(c:Context):SQLiteOpenHelper(c,"kasirku.db",null,3){
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,barcode TEXT UNIQUE,cost_price INTEGER NOT NULL DEFAULT 0,price INTEGER NOT NULL,stock INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE sales(id INTEGER PRIMARY KEY AUTOINCREMENT,total INTEGER,payment_method TEXT NOT NULL DEFAULT 'TUNAI',paid_amount INTEGER NOT NULL DEFAULT 0,change_amount INTEGER NOT NULL DEFAULT 0,created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
  db.execSQL("CREATE TABLE sale_items(id INTEGER PRIMARY KEY AUTOINCREMENT,sale_id INTEGER NOT NULL,product_id INTEGER NOT NULL,product_name TEXT NOT NULL,quantity INTEGER NOT NULL,cost_price INTEGER NOT NULL,sale_price INTEGER NOT NULL,line_total INTEGER NOT NULL)")
  db.execSQL("INSERT INTO products(name,barcode,cost_price,price,stock) VALUES('Air Mineral 600ml','899000000001',2500,4000,30),('Mie Instan','899000000002',2200,3500,50),('Roti Tawar','899000000003',11000,15000,12)")
 }
 override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int){if(oldVersion<2){db.execSQL("ALTER TABLE products ADD COLUMN cost_price INTEGER NOT NULL DEFAULT 0");db.execSQL("CREATE TABLE IF NOT EXISTS sale_items(id INTEGER PRIMARY KEY AUTOINCREMENT,sale_id INTEGER NOT NULL,product_id INTEGER NOT NULL,product_name TEXT NOT NULL,quantity INTEGER NOT NULL,cost_price INTEGER NOT NULL,sale_price INTEGER NOT NULL,line_total INTEGER NOT NULL)")};if(oldVersion<3){db.execSQL("ALTER TABLE sales ADD COLUMN payment_method TEXT NOT NULL DEFAULT 'TUNAI'");db.execSQL("ALTER TABLE sales ADD COLUMN paid_amount INTEGER NOT NULL DEFAULT 0");db.execSQL("ALTER TABLE sales ADD COLUMN change_amount INTEGER NOT NULL DEFAULT 0")}}
 fun all():List<Product>{val a=mutableListOf<Product>();readableDatabase.rawQuery("SELECT id,name,barcode,cost_price,price,stock FROM products ORDER BY name",null).use{c->while(c.moveToNext())a.add(Product(c.getLong(0),c.getString(1),c.getString(2)?: "",c.getLong(3),c.getLong(4),c.getInt(5)))};return a}
 fun save(n:String,b:String,cost:Long,p:Long,s:Int){val v=ContentValues().apply{put("name",n);put("barcode",b);put("cost_price",cost);put("price",p);put("stock",s)};writableDatabase.insertWithOnConflict("products",null,v,SQLiteDatabase.CONFLICT_REPLACE)}
 fun completeSale(cart:Map<Long,Int>,products:List<Product>,paymentMethod:String,paidAmount:Long):Boolean{val db=writableDatabase;return try{db.beginTransaction();val lines=cart.mapNotNull{(id,q)->products.find{it.id==id}?.let{it to q}};if(lines.isEmpty()||lines.any{(p,q)->q<=0||q>p.stock})throw IllegalStateException("Stok tidak cukup");val total=lines.sumOf{(p,q)->p.price*q};if(paidAmount<total)throw IllegalStateException("Pembayaran kurang");val sale=ContentValues().apply{put("total",total);put("payment_method",paymentMethod);put("paid_amount",paidAmount);put("change_amount",paidAmount-total)};val saleId=db.insertOrThrow("sales",null,sale);lines.forEach{(p,q)->val st=db.compileStatement("UPDATE products SET stock=stock-? WHERE id=? AND stock>=?");st.bindLong(1,q.toLong());st.bindLong(2,p.id);st.bindLong(3,q.toLong());if(st.executeUpdateDelete()!=1)throw IllegalStateException("Stok berubah");val item=ContentValues().apply{put("sale_id",saleId);put("product_id",p.id);put("product_name",p.name);put("quantity",q);put("cost_price",p.costPrice);put("sale_price",p.price);put("line_total",p.price*q)};db.insertOrThrow("sale_items",null,item)};db.setTransactionSuccessful();true}catch(e:Exception){false}finally{db.endTransaction()}}
}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{KasirKuApp()}}}
fun money(v:Long)=NumberFormat.getCurrencyInstance(Locale("in","ID")).format(v)
fun pairedPrinters():List<BluetoothDevice>{val adapter=BluetoothAdapter.getDefaultAdapter()?:return emptyList();return try{adapter.bondedDevices?.toList()?:emptyList()}catch(e:SecurityException){emptyList()}}
fun printReceipt(device:BluetoothDevice,shopName:String,lines:List<Pair<Product,Int>>,total:Long,paymentMethod:String,paidAmount:Long,changeAmount:Long):String{
 var socket:BluetoothSocket?=null
 return try{
  socket=device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
  socket.connect()
  val out=socket.outputStream
  val enc=java.nio.charset.Charset.forName("CP437")
  fun send(t:String){out.write(t.toByteArray(enc))}
  fun rupiah(v:Long)="Rp"+NumberFormat.getNumberInstance(Locale("id","ID")).format(v)
  send("\u001B@")
  send("\u001Ba\u0001")
  send(shopName.take(32)+"\n")
  send("STRUK PEMBELIAN\n")
  send("\u001Ba\u0000")
  send("--------------------------------\n")
  lines.forEach{(p,q)->
   val name=p.name.take(32)
   send(name+"\n")
   send("  $q x "+rupiah(p.price)+" = "+rupiah(p.price*q)+"\n")
  }
  send("--------------------------------\n")
  send("TOTAL: "+rupiah(total)+"\n")
  send("Pembayaran: "+paymentMethod+"\n")
  if(paymentMethod=="TUNAI"){send("Tunai: "+rupiah(paidAmount)+"\n");send("Kembali: "+rupiah(changeAmount)+"\n")}
  send("Terima kasih\n")
  send("\n\n\n")
  out.flush()
  "Struk berhasil dikirim ke printer"
 }catch(e:Exception){
  "Gagal mencetak: "+(e.message?: "Periksa printer dan koneksi Bluetooth")
 }finally{
  try{socket?.close()}catch(_:Exception){}
 }
}
fun csvEscape(v:String) = "\"" + v.replace("\"", "\"\"") + "\""
fun parseCsvLine(line:String):List<String>{val out=mutableListOf<String>();val cell=StringBuilder();val quote=34.toChar();val comma=44.toChar();var quoted=false;var i=0;while(i<line.length){val ch=line[i];if(ch==quote){if(quoted&&i+1<line.length&&line[i+1]==quote){cell.append(quote);i++}else quoted=!quoted}else if(ch==comma&&!quoted){out.add(cell.toString());cell.setLength(0)}else cell.append(ch);i++};out.add(cell.toString());return out}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun KasirKuApp(){
 val ctx=LocalContext.current;val db=remember{StoreDb(ctx)}
 var products by remember{mutableStateOf(db.all())};var tab by remember{mutableIntStateOf(0)}
 var search by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
 var cart by remember{mutableStateOf(mapOf<Long,Int>())};var note by remember{mutableStateOf("")};var add by remember{mutableStateOf(false)}
 var chosenPrinter by remember{mutableStateOf<BluetoothDevice?>(null)}
 var printerList by remember{mutableStateOf<List<BluetoothDevice>>(emptyList())}
 var showPrinters by remember{mutableStateOf(false)}
 var receiptLines by remember{mutableStateOf<List<Pair<Product,Int>>>(emptyList())}
 var receiptTotal by remember{mutableLongStateOf(0L)}
 var receiptPayment by remember{mutableStateOf("TUNAI")};var receiptPaid by remember{mutableLongStateOf(0L)};var receiptChange by remember{mutableLongStateOf(0L)}
 var showPayment by remember{mutableStateOf(false)};var paymentMethod by remember{mutableStateOf("TUNAI")};var cashInput by remember{mutableStateOf("")}
 val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->if(granted){printerList=pairedPrinters();showPrinters=true}else note="Izin Bluetooth diperlukan untuk memilih printer"}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){u:Uri?->if(u!=null)try{ctx.contentResolver.openOutputStream(u)?.bufferedWriter()?.use{w->w.appendLine("nama,barcode,harga_modal,harga_jual,stok");products.forEach{p->w.appendLine(listOf(csvEscape(p.name),csvEscape(p.barcode),p.costPrice.toString(),p.price.toString(),p.stock.toString()).joinToString(","))}};note="Ekspor CSV berhasil"}catch(e:Exception){note="Ekspor gagal: ${e.message}"}}
 val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->if(u!=null)try{ctx.contentResolver.openInputStream(u)?.use{s->BufferedReader(InputStreamReader(s)).lineSequence().drop(1).forEach{line->val a=parseCsvLine(line);if(a.size>=5)db.save(a[0].trim(),a[1].trim(),a[2].trim().toLongOrNull()?:0,a[3].trim().toLongOrNull()?:0,a[4].trim().toIntOrNull()?:0) else if(a.size>=4)db.save(a[0].trim(),a[1].trim(),0,a[2].trim().toLongOrNull()?:0,a[3].trim().toIntOrNull()?:0)}};products=db.all();note="Impor CSV selesai"}catch(e:Exception){note="Impor gagal: ${e.message}"}}
 val total=cart.entries.sumOf{(id,n)->(products.find{it.id==id}?.price?:0)*n}
 fun addScannedBarcode(raw:String){
  val scanned=raw.trim().filter{it.isLetterOrDigit()||it=='-'||it=='_'}
  if(scanned.isBlank()){note="Barcode kosong";code="";return}
  val p=products.find{it.barcode.trim().equals(scanned,true)}
  if(p==null){note="Barcode $scanned belum terdaftar. Tambahkan melalui menu Barang.";code=""}
  else if(p.stock<=0){note="Stok ${p.name} habis";code=""}
  else if((cart[p.id]?:0)>=p.stock){note="Stok ${p.name} tidak cukup";code=""}
  else{cart=cart+(p.id to ((cart[p.id]?:0)+1));note="${p.name} berhasil ditambahkan";code=""}
 }
 Scaffold(topBar={TopAppBar(title={Text("SeaMart")})},bottomBar={NavigationBar{NavigationBarItem(selected=tab==0,onClick={tab=0},icon={},label={Text("Kasir")});NavigationBarItem(selected=tab==1,onClick={tab=1},icon={},label={Text("Barang")});NavigationBarItem(selected=tab==2,onClick={tab=2},icon={},label={Text("CSV")})}}){padding->
 Column(Modifier.padding(padding).padding(14.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  if(note.isNotBlank())Text(note,color=MaterialTheme.colorScheme.primary)
  when(tab){
   0->{OutlinedTextField(search,{search=it},label={Text("Cari barang")},modifier=Modifier.fillMaxWidth());OutlinedTextField(value=code,onValueChange={v->if(v.contains("\\n")||v.contains("\\r"))addScannedBarcode(v)else code=v},label={Text("Scan barcode di sini")},placeholder={Text("Hubungkan scanner Bluetooth / USB OTG")},modifier=Modifier.fillMaxWidth(),singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={addScannedBarcode(code)}))
    Button(onClick={addScannedBarcode(code)},enabled=code.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Tambah barcode")}
    Text("Daftar barang",style=MaterialTheme.typography.titleMedium)
    LazyVerticalGrid(columns=GridCells.Fixed(2),modifier=Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=8.dp)){items(products.filter{it.name.contains(search,true)||it.barcode.contains(search)}){p->
 Card(modifier=Modifier.fillMaxWidth().heightIn(min=142.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)){
  Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
   Box(Modifier.size(34.dp),contentAlignment=androidx.compose.ui.Alignment.Center){
    Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.primaryContainer){Box(Modifier.fillMaxSize().padding(5.dp),contentAlignment=androidx.compose.ui.Alignment.Center){Text(p.name.take(1).uppercase(),fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onPrimaryContainer)}}
   }
   Text(p.name,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,minLines=2)
   Text(money(p.price),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.primary)
   Text("Stok: ${p.stock}",style=MaterialTheme.typography.bodySmall,color=if(p.stock<=5)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
   Button(onClick={if(p.stock>0&&(cart[p.id]?:0)<p.stock)cart=cart+(p.id to ((cart[p.id]?:0)+1))else note=if(p.stock<=0)"Stok habis" else "Stok tidak cukup"},enabled=p.stock>0,modifier=Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=8.dp,vertical=4.dp)){Text(if(p.stock>0)"+ Tambah" else "Stok habis")}
  }
 }
}}
    Text("Total: ${money(total)}",style=MaterialTheme.typography.headlineSmall)
    if(cart.isNotEmpty()){Text(cart.entries.joinToString{(id,n)->"${products.find{it.id==id}?.name} x$n"});Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={paymentMethod="TUNAI";cashInput=total.toString();showPayment=true},modifier=Modifier.weight(1f)){Text("Bayar")};OutlinedButton(onClick={cart=emptyMap()}){Text("Kosongkan")}}}
    if(receiptLines.isNotEmpty()){Text("Printer: ${chosenPrinter?.name?: "Belum dipilih"}");Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton(onClick={if(Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)}else{printerList=pairedPrinters();showPrinters=true}}){Text("Pilih printer")};Button(enabled=chosenPrinter!=null,onClick={note=printReceipt(chosenPrinter!!,"SEAMART",receiptLines,receiptTotal,receiptPayment,receiptPaid,receiptChange)}){Text("Cetak struk")}}}
   }
   1->{Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Manajemen barang",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Button(onClick={add=true}){Text("+ Barang")}};LazyColumn{items(products){p->ListItem(headlineContent={Text(p.name)},supportingContent={Text("Barcode ${p.barcode} • Modal ${money(p.costPrice)} • Jual ${money(p.price)} • Stok ${p.stock} • Laba/unit ${money(p.price-p.costPrice)}")})}}}
   else->{Text("Impor / Ekspor CSV",style=MaterialTheme.typography.titleLarge);Text("Format baru: nama,barcode,harga_modal,harga_jual,stok. Format lama 4 kolom juga didukung.");Button(onClick={export.launch("barang-kasirku.csv")},modifier=Modifier.fillMaxWidth()){Text("Ekspor barang ke CSV")};OutlinedButton(onClick={import.launch(arrayOf("text/*","application/csv","application/vnd.ms-excel"))},modifier=Modifier.fillMaxWidth()){Text("Impor barang dari CSV")};Text("Data tersimpan di HP dan bekerja tanpa internet.")}
  }
 }
}
if(showPayment){
 AlertDialog(
  onDismissRequest={showPayment=false},
  title={Text("Pembayaran")},
  text={
   Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
    Text("Total belanja: \${money(total)}",style=MaterialTheme.typography.titleMedium)
    Text("Pilih metode pembayaran")
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
     RadioButton(selected=paymentMethod=="TUNAI",onClick={paymentMethod="TUNAI"})
     Text("Tunai")
     Spacer(Modifier.width(18.dp))
     RadioButton(selected=paymentMethod=="QRIS",onClick={paymentMethod="QRIS"})
     Text("QRIS")
    }
    if(paymentMethod=="TUNAI"){
     OutlinedTextField(value=cashInput,onValueChange={cashInput=it.filter{ch->ch.isDigit()}},label={Text("Uang diterima (Rp)")},singleLine=true,modifier=Modifier.fillMaxWidth())
     val paid=cashInput.toLongOrNull()?:0L
     Text("Kembalian: \${money((paid-total).coerceAtLeast(0))}")
     if(paid<total)Text("Uang diterima masih kurang",color=MaterialTheme.colorScheme.error)
    }else{
     Text("Minta pelanggan membayar melalui aplikasi bank/e-wallet ke QRIS toko. Setelah pembayaran masuk, konfirmasi di bawah.")
     Text("Catatan: konfirmasi QRIS ini manual; aplikasi belum terhubung ke penyedia QRIS untuk verifikasi otomatis.")
    }
   }
  },
  confirmButton={
   TextButton(onClick={
    val paid=if(paymentMethod=="TUNAI")cashInput.toLongOrNull()?:0L else total
    if(paid<total){note="Uang tunai kurang dari total belanja"}
    else if(paymentMethod=="QRIS"){
     receiptLines=cart.mapNotNull{(id,q)->products.find{it.id==id}?.let{it to q}}
     if(db.completeSale(cart,products,"QRIS",total)){
      receiptTotal=total;receiptPayment="QRIS";receiptPaid=total;receiptChange=0L;cart=emptyMap();products=db.all();showPayment=false;note="Pembayaran QRIS dikonfirmasi. Transaksi tersimpan."
     }else note="Transaksi gagal. Periksa stok."
    }else{
     receiptLines=cart.mapNotNull{(id,q)->products.find{it.id==id}?.let{it to q}}
     if(db.completeSale(cart,products,"TUNAI",paid)){
      receiptTotal=total;receiptPayment="TUNAI";receiptPaid=paid;receiptChange=paid-total;cart=emptyMap();products=db.all();showPayment=false;note="Pembayaran tunai tersimpan. Kembalian ${money(paid-total)}."
     }else note="Transaksi gagal. Periksa stok."
    }
   }){Text(if(paymentMethod=="QRIS")"Konfirmasi QRIS diterima" else "Simpan pembayaran")}
  },
  dismissButton={TextButton(onClick={showPayment=false}){Text("Batal")}}
 )
}
if(add){var name by remember{mutableStateOf("")};var barcode by remember{mutableStateOf("")};var cost by remember{mutableStateOf("")};var price by remember{mutableStateOf("")};var stock by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest={add=false},title={Text("Tambah barang")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(name,{name=it},label={Text("Nama barang")});OutlinedTextField(barcode,{barcode=it},label={Text("Barcode")});OutlinedTextField(cost,{cost=it},label={Text("Harga modal (Rp)")});OutlinedTextField(price,{price=it},label={Text("Harga jual (Rp)")});OutlinedTextField(stock,{stock=it},label={Text("Stok awal")})}},confirmButton={TextButton(onClick={val c=cost.toLongOrNull();val p=price.toLongOrNull();if(name.isNotBlank()&&c!=null&&p!=null&&c>=0&&p>=c){db.save(name,barcode,c,p,stock.toIntOrNull()?:0);products=db.all();add=false;note="Barang disimpan"}else note="Periksa nama, modal, dan harga jual (minimal sama dengan modal)"}){Text("Simpan")}},dismissButton={TextButton(onClick={add=false}){Text("Batal")}})}

 if(showPrinters){
  AlertDialog(
   onDismissRequest={showPrinters=false},
   title={Text("Pilih printer Bluetooth")},
   text={
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
     if(printerList.isEmpty()){
      Text("Belum ada printer Bluetooth yang dipasangkan ke HP. Pasangkan printer melalui Pengaturan > Koneksi > Bluetooth, lalu tekan Muat ulang.")
     }else{
      Text("Pilih printer yang sudah dipasangkan:")
      LazyColumn(Modifier.heightIn(max=280.dp)){
       items(printerList){device->
        TextButton(
         onClick={
          if(Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
           showPrinters=false
           permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
          }else{
           chosenPrinter=device
           showPrinters=false
           note="Printer dipilih: ${device.name?:device.address}"
          }
         },
         modifier=Modifier.fillMaxWidth()
        ){
         Column(Modifier.fillMaxWidth()){
          Text(device.name?: "Printer tanpa nama")
          Text(device.address,style=MaterialTheme.typography.bodySmall)
         }
        }
       }
      }
     }
    }
   },
   confirmButton={
    TextButton(onClick={
     if(Build.VERSION.SDK_INT>=31&&ContextCompat.checkSelfPermission(ctx,Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
      permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
     }else{
      printerList=pairedPrinters()
      note=if(printerList.isEmpty()) "Belum ada perangkat Bluetooth yang dipasangkan" else "Daftar printer diperbarui"
     }
    }){Text("Muat ulang")}
   },
   dismissButton={TextButton(onClick={showPrinters=false}){Text("Tutup")}}
  )
 }

}
