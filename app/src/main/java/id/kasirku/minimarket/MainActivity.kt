package id.kasirku.minimarket

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.NumberFormat
import java.util.Locale

data class Product(val id:Long,val name:String,val barcode:String,val price:Long,val stock:Int)
class StoreDb(c:Context):SQLiteOpenHelper(c,"kasirku.db",null,1){
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,barcode TEXT UNIQUE,price INTEGER NOT NULL,stock INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE sales(id INTEGER PRIMARY KEY AUTOINCREMENT,total INTEGER,created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
  db.execSQL("INSERT INTO products(name,barcode,price,stock) VALUES('Air Mineral 600ml','899000000001',4000,30),('Mie Instan','899000000002',3500,50),('Roti Tawar','899000000003',15000,12)")
 }
 override fun onUpgrade(db:SQLiteDatabase,o:Int,n:Int){}
 fun all():List<Product>{val a=mutableListOf<Product>();readableDatabase.rawQuery("SELECT id,name,barcode,price,stock FROM products ORDER BY name",null).use{c->while(c.moveToNext())a.add(Product(c.getLong(0),c.getString(1),c.getString(2)?: "",c.getLong(3),c.getInt(4)))};return a}
 fun save(n:String,b:String,p:Long,s:Int){val v=ContentValues().apply{put("name",n);put("barcode",b);put("price",p);put("stock",s)};writableDatabase.insertWithOnConflict("products",null,v,SQLiteDatabase.CONFLICT_REPLACE)}
 fun sale(t:Long){writableDatabase.execSQL("INSERT INTO sales(total) VALUES(?)",arrayOf(t))}
}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{KasirKuApp()}}}
fun money(v:Long)=NumberFormat.getCurrencyInstance(Locale("in","ID")).format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun KasirKuApp(){
 val ctx= LocalContext.current;val db= remember{StoreDb(ctx)}
 var products by remember{mutableStateOf(db.all())};var tab by remember{mutableIntStateOf(0)}
 var search by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
 var cart by remember{mutableStateOf(mapOf<Long,Int>())};var note by remember{mutableStateOf("")}
 var add by remember{mutableStateOf(false)}
 val export= rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){u:Uri?->if(u!=null)try{ctx.contentResolver.openOutputStream(u)?.bufferedWriter()?.use{w->w.appendLine("nama,barcode,harga,stok");products.forEach{p->w.appendLine("\"${p.name.replace("\"","")}\",${p.barcode},${p.price},${p.stock}")}};note="Ekspor CSV berhasil"}catch(e:Exception){note="Ekspor gagal: ${e.message}"}}
 val import= rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->if(u!=null)try{ctx.contentResolver.openInputStream(u)?.use{s->BufferedReader(InputStreamReader(s)).lineSequence().drop(1).forEach{line->val a=line.split(",");if(a.size>=4)db.save(a[0].trim().trim('"'),a[1].trim(),a[2].trim().toLongOrNull()?:0,a[3].trim().toIntOrNull()?:0)}};products=db.all();note="Impor CSV selesai"}catch(e:Exception){note="Impor gagal: ${e.message}"}}
 val total=cart.entries.sumOf{(id,n)->(products.find{it.id==id}?.price?:0)*n}
 Scaffold(topBar={TopAppBar(title={Text("KasirKu Minimarket")})},bottomBar={NavigationBar{NavigationBarItem(tab==0,{tab=0},icon={},label={Text("Kasir")});NavigationBarItem(tab==1,{tab=1},icon={},label={Text("Barang")});NavigationBarItem(tab==2,{tab=2},icon={},label={Text("CSV")})}}){padding->
 Column(Modifier.padding(padding).padding(14.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  if(note.isNotBlank())Text(note,color=MaterialTheme.colorScheme.primary)
  when(tab){
   0->{OutlinedTextField(search,{search=it},label={Text("Cari barang")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(code,{code=it},label={Text("Barcode / kode barang")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Button(onClick={val p=products.find{it.barcode==code};if(p!=null)cart=cart+(p.id to ((cart[p.id]?:0)+1))else note="Barcode tidak ditemukan";code=""},modifier=Modifier.fillMaxWidth()){Text("Tambah barcode")}
    Text("Daftar barang",style=MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.weight(1f)){items(products.filter{it.name.contains(search,true)||it.barcode.contains(search)}){p->ListItem(headlineContent={Text(p.name)},supportingContent={Text("${p.barcode} • Stok ${p.stock} • ${money(p.price)}")},trailingContent={TextButton(onClick={cart=cart+(p.id to ((cart[p.id]?:0)+1))}){Text("+ Tambah")}})}}
    Text("Total: ${money(total)}",style=MaterialTheme.typography.headlineSmall)
    if(cart.isNotEmpty()){Text(cart.entries.joinToString{(id,n)->"${products.find{it.id==id}?.name} x$n"});Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={db.sale(total);cart=emptyMap();note="Transaksi tersimpan"},modifier=Modifier.weight(1f)){Text("Bayar / Simpan")};OutlinedButton(onClick={cart=emptyMap()}){Text("Kosongkan")}}}
   }
   1->{Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text("Manajemen barang",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Button(onClick={add=true}){Text("+ Barang")}}
    LazyColumn{items(products){p->ListItem(headlineContent={Text(p.name)},supportingContent={Text("Kode ${p.barcode} • ${money(p.price)} • Stok ${p.stock}")})}}
   }
   else->{Text("Impor / Ekspor CSV",style=MaterialTheme.typography.titleLarge);Text("Format: nama,barcode,harga,stok. Harga tanpa titik ribuan.")
    Button(onClick={export.launch("barang-kasirku.csv")},modifier=Modifier.fillMaxWidth()){Text("Ekspor barang ke CSV")}
    OutlinedButton(onClick={import.launch(arrayOf("text/*","application/csv","application/vnd.ms-excel"))},modifier=Modifier.fillMaxWidth()){Text("Impor barang dari CSV")}
    Text("Data disimpan di HP dan bisa digunakan tanpa internet.")
   }
  }
 }
}
 if(add){var name by remember{mutableStateOf("")};var barcode by remember{mutableStateOf("")};var price by remember{mutableStateOf("")};var stock by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest={add=false},title={Text("Tambah barang")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(name,{name=it},label={Text("Nama")});OutlinedTextField(barcode,{barcode=it},label={Text("Barcode")});OutlinedTextField(price,{price=it},label={Text("Harga")});OutlinedTextField(stock,{stock=it},label={Text("Stok")})}},confirmButton={TextButton(onClick={if(name.isNotBlank()&&price.toLongOrNull()!=null){db.save(name,barcode,price.toLong(),stock.toIntOrNull()?:0);products=db.all();add=false;note="Barang disimpan"}else note="Periksa nama dan harga"}){Text("Simpan")}},dismissButton={TextButton(onClick={add=false}){Text("Batal")}})
 }
}
