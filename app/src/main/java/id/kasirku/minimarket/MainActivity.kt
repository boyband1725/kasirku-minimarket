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
import androidx.compose.foundation.clickable
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
class StoreDb(c:Context):SQLiteOpenHelper(c,"kasirku.db",null,2){
 override fun onCreate(db:SQLiteDatabase){
  db.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,barcode TEXT UNIQUE,price INTEGER NOT NULL,stock INTEGER NOT NULL)")
  db.execSQL("CREATE TABLE sales(id INTEGER PRIMARY KEY AUTOINCREMENT,total INTEGER,created_at TEXT DEFAULT CURRENT_TIMESTAMP)")
  db.execSQL("INSERT INTO products(name,barcode,price,stock) VALUES('Air Mineral 600ml','899000000001',4000,30),('Mie Instan','899000000002',3500,50),('Roti Tawar','899000000003',15000,12)")
 }
 override fun onUpgrade(db:SQLiteDatabase,old:Int,now:Int){}
 fun all():List<Product>{val a=mutableListOf<Product>();readableDatabase.rawQuery("SELECT id,name,barcode,price,stock FROM products ORDER BY name",null).use{c->while(c.moveToNext())a.add(Product(c.getLong(0),c.getString(1),c.getString(2)?: "",c.getLong(3),c.getInt(4)))};return a}
 fun save(id:Long?,n:String,b:String,p:Long,s:Int){val v=ContentValues().apply{put("name",n);put("barcode",b.ifBlank{null});put("price",p);put("stock",s)};if(id==null)writableDatabase.insertWithOnConflict("products",null,v,SQLiteDatabase.CONFLICT_REPLACE) else writableDatabase.update("products",v,"id=?",arrayOf(id.toString()))}
 fun delete(id:Long){writableDatabase.delete("products","id=?",arrayOf(id.toString()))}
 fun changeStock(id:Long,amount:Int){writableDatabase.execSQL("UPDATE products SET stock=MAX(0,stock+?) WHERE id=?",arrayOf(amount,id))}
 fun sale(t:Long){writableDatabase.execSQL("INSERT INTO sales(total) VALUES(?)",arrayOf(t))}
}
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{KasirKuApp()}}}
fun money(v:Long)=NumberFormat.getCurrencyInstance(Locale("in","ID")).format(v)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun KasirKuApp(){
 val ctx=LocalContext.current;val db=remember{StoreDb(ctx)}
 var products by remember{mutableStateOf(db.all())};var tab by remember{mutableIntStateOf(0)}
 var search by remember{mutableStateOf("")};var code by remember{mutableStateOf("")}
 var cart by remember{mutableStateOf(mapOf<Long,Int>())};var note by remember{mutableStateOf("")}
 var editing by remember{mutableStateOf<Product?>(null)};var add by remember{mutableStateOf(false)}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){u:Uri?->if(u!=null)try{ctx.contentResolver.openOutputStream(u)?.bufferedWriter()?.use{w->w.appendLine("nama,barcode,harga_modal,harga_jual,stok");products.forEach{p->w.appendLine("${csv(p.name)},${csv(p.barcode)},0,${p.price},${p.stock}")}};note="Ekspor CSV berhasil"}catch(e:Exception){note="Ekspor gagal: ${e.message}"}}
 val import=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){u:Uri?->if(u!=null)try{ctx.contentResolver.openInputStream(u)?.use{s->val lines=BufferedReader(InputStreamReader(s)).readLines();if(lines.isNotEmpty()){val headers=parseCsv(lines.first()).map{it.trim().lowercase()};val ni=headers.indexOfFirst{it in listOf("nama","name","nama_barang")};val bi=headers.indexOfFirst{it in listOf("barcode","kode","kode_barang")};val pi=headers.indexOfFirst{it in listOf("harga_jual","harga","price")};val si=headers.indexOfFirst{it in listOf("stok","stock")};lines.drop(1).forEach{line->val a=parseCsv(line);if(ni>=0&&pi>=0&&a.size>maxOf(ni,pi)){val name=a[ni].trim();val bar=if(bi>=0&&bi<a.size)a[bi].trim() else "";val price=a[pi].filter{it.isDigit()}.toLongOrNull()?:0L;val stock=if(si>=0&&si<a.size)a[si].filter{it.isDigit()||it=='-'}.toIntOrNull()?:0 else 0;if(name.isNotBlank())db.save(null,name,bar,price,stock)}}}};products=db.all();note="Impor CSV selesai"}catch(e:Exception){note="Impor gagal: ${e.message}"}}
 val total=cart.entries.sumOf{(id,n)->(products.find{it.id==id}?.price?:0)*n}
 Scaffold(topBar={TopAppBar(title={Text("SeaMart")})},bottomBar={NavigationBar{NavigationBarItem(tab==0,{tab=0},icon={},label={Text("Kasir")});NavigationBarItem(tab==1,{tab=1},icon={},label={Text("Barang")});NavigationBarItem(tab==2,{tab=2},icon={},label={Text("CSV")})}}){padding->
 Column(Modifier.padding(padding).padding(14.dp).fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)){
  if(note.isNotBlank())Text(note,color=MaterialTheme.colorScheme.primary)
  when(tab){
   0->{OutlinedTextField(search,{search=it},label={Text("Cari barang")},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(code,{code=it},label={Text("Barcode / kode barang")},modifier=Modifier.fillMaxWidth(),singleLine=true)
    Button(onClick={val p=products.find{it.barcode==code};if(p!=null&&p.stock>0)cart=cart+(p.id to ((cart[p.id]?:0)+1))else note=if(p==null)"Barcode tidak ditemukan" else "Stok habis";code=""},modifier=Modifier.fillMaxWidth()){Text("Tambah barcode")}
    Text("Daftar barang",style=MaterialTheme.typography.titleMedium)
    LazyColumn(Modifier.weight(1f)){items(products.filter{it.name.contains(search,true)||it.barcode.contains(search)}){p->ListItem(headlineContent={Text(p.name)},supportingContent={Text("${p.barcode} • Stok ${p.stock} • ${money(p.price)}")},trailingContent={TextButton(enabled=p.stock>0,onClick={cart=cart+(p.id to ((cart[p.id]?:0)+1))}){Text("+ Tambah")}})}}
    Text("Total: ${money(total)}",style=MaterialTheme.typography.headlineSmall)
    if(cart.isNotEmpty()){Text(cart.entries.joinToString{(id,n)->"${products.find{it.id==id}?.name} x$n"});Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){Button(onClick={db.sale(total);cart=emptyMap();note="Transaksi tersimpan"},modifier=Modifier.weight(1f)){Text("Bayar / Simpan")};OutlinedButton(onClick={cart=emptyMap()}){Text("Kosongkan")}}}
   }
   1->{Row{Text("Master Barang",modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleLarge);Button(onClick={editing=null;add=true}){Text("+ Barang")}}
    LazyColumn{items(products){p->ListItem(modifier=Modifier.clickable{editing=p;add=true},headlineContent={Text(p.name)},supportingContent={Text("Barcode ${p.barcode} • ${money(p.price)} • Stok ${p.stock}")},trailingContent={TextButton(onClick={editing=p;add=true}){Text("Edit")}})}}
   }
   else->{Text("Impor / Ekspor CSV",style=MaterialTheme.typography.titleLarge);Text("Kolom: nama, barcode, harga_modal, harga_jual, stok")
    Button(onClick={export.launch("barang-kasirku.csv")},modifier=Modifier.fillMaxWidth()){Text("Ekspor CSV")}
    OutlinedButton(onClick={import.launch(arrayOf("text/*","application/csv","application/vnd.ms-excel"))},modifier=Modifier.fillMaxWidth()){Text("Impor CSV")}
    Text("Data disimpan di HP dan bisa digunakan tanpa internet.")
   }
  }
 }
}
 if(add){var name by remember(editing){mutableStateOf(editing?.name?:"")};var barcode by remember(editing){mutableStateOf(editing?.barcode?:"")};var price by remember(editing){mutableStateOf(editing?.price?.toString()?:"")};var stock by remember(editing){mutableStateOf(editing?.stock?.toString()?:"0")}
 AlertDialog(onDismissRequest={add=false},title={Text(if(editing==null)"Tambah barang" else "Edit barang")},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){OutlinedTextField(name,{name=it},label={Text("Nama barang")});OutlinedTextField(barcode,{barcode=it},label={Text("Barcode")});OutlinedTextField(price,{price=it},label={Text("Harga jual (angka)")});OutlinedTextField(stock,{stock=it},label={Text("Stok")})}},confirmButton={TextButton(onClick={val p=price.filter{it.isDigit()}.toLongOrNull();val s=stock.toIntOrNull();if(name.isNotBlank()&&p!=null&&s!=null&&s>=0){db.save(editing?.id,name,barcode,p,s);products=db.all();add=false;note="Barang berhasil disimpan"}else note="Periksa nama, harga, dan stok"}){Text("Simpan")}},dismissButton={Row{if(editing!=null)TextButton(onClick={db.delete(editing!!.id);products=db.all();add=false;note="Barang dihapus"}){Text("Hapus")};TextButton(onClick={add=false}){Text("Batal")}}})
 }
}
fun csv(v:String)="\"" + v.replace("\"", "\"\"") + "\""
fun parseCsv(line:String):List<String>{val out=mutableListOf<String>();val cur=StringBuilder();var quoted=false;var i=0;while(i<line.length){val c=line[i];if(c=='\"'){if(quoted&&i+1<line.length&&line[i+1]=='\"'){cur.append('\"');i++}else quoted=!quoted}else if(c==','&&!quoted){out.add(cur.toString());cur.clear()}else cur.append(c);i++};out.add(cur.toString());return out}
