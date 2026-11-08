import csv, os, sqlite3, sys, hashlib
from pathlib import Path
from datetime import datetime
import tkinter as tk
from tkinter import ttk, messagebox, filedialog

GREEN="#087A45"; DARK="#073B2A"; BG="#F2F6F3"; WHITE="#FFFFFF"; INK="#173126"
DATA=Path(os.getenv("LOCALAPPDATA",str(Path.home()))) / "SeaMartPOS"
DATA.mkdir(parents=True,exist_ok=True)
DB=DATA/"seamart.db"

class SeaMart:
 def __init__(self,root):
  self.root=root; root.title("SeaMart POS"); root.geometry("1360x850"); root.minsize(1100,700); root.configure(bg=BG)
  try: root.state("zoomed")
  except: pass
  self.db=sqlite3.connect(DB); self.db.row_factory=sqlite3.Row
  self.db.executescript("""
  CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, role TEXT NOT NULL);
  CREATE TABLE IF NOT EXISTS held_transactions(id INTEGER PRIMARY KEY, label TEXT, payload TEXT, created TEXT);
  CREATE TABLE IF NOT EXISTS products(id INTEGER PRIMARY KEY, barcode TEXT UNIQUE, name TEXT NOT NULL, category TEXT DEFAULT '', cost REAL DEFAULT 0, price REAL DEFAULT 0, stock REAL DEFAULT 0);
  CREATE TABLE IF NOT EXISTS sales(id INTEGER PRIMARY KEY, invoice TEXT UNIQUE, created TEXT, method TEXT, total REAL, paid REAL, change_due REAL);
  CREATE TABLE IF NOT EXISTS sale_items(id INTEGER PRIMARY KEY, sale_id INTEGER, barcode TEXT, name TEXT, qty REAL, price REAL, subtotal REAL);
  """); self.db.commit()
  for username,password,role in [("admin","admin123","Admin"),("kasir","kasir123","Kasir")]:
   self.db.execute("INSERT OR IGNORE INTO users(username,password_hash,role) VALUES(?,?,?)",(username,hashlib.sha256(password.encode()).hexdigest(),role))
  self.db.commit(); self.cart={}; self.last_receipt=""; self.current_user=None; self.current_role=None
  self.style(); self.login_screen()
 def login_screen(self):
  self.root.withdraw()
  win=tk.Toplevel(self.root); win.title("Login SeaMart POS"); win.geometry("430x480"); win.resizable(False,False); win.configure(bg=BG)
  win.protocol("WM_DELETE_WINDOW",self.close)
  tk.Frame(win,bg=GREEN,height=12).pack(fill="x")
  panel=tk.Frame(win,bg=WHITE); panel.pack(fill="both",expand=True,padx=28,pady=28)
  tk.Label(panel,text="$",bg=GREEN,fg=WHITE,font=("Segoe UI",30,"bold"),width=3).pack(pady=(28,8))
  tk.Label(panel,text="SeaMart POS",bg=WHITE,fg=DARK,font=("Segoe UI",22,"bold")).pack()
  tk.Label(panel,text="Silakan masuk untuk mulai bekerja",bg=WHITE,fg="#64756B").pack(pady=(4,24))
  tk.Label(panel,text="USERNAME",bg=WHITE,fg=DARK,font=("Segoe UI",9,"bold")).pack(anchor="w",padx=32)
  user=tk.Entry(panel,font=("Segoe UI",12),relief="solid",bd=1); user.pack(fill="x",padx=32,pady=(5,14),ipady=8)
  tk.Label(panel,text="PASSWORD",bg=WHITE,fg=DARK,font=("Segoe UI",9,"bold")).pack(anchor="w",padx=32)
  pwd=tk.Entry(panel,font=("Segoe UI",12),show="*",relief="solid",bd=1); pwd.pack(fill="x",padx=32,pady=(5,18),ipady=8)
  def submit(event=None):
   row=self.db.execute("SELECT * FROM users WHERE username=?",(user.get().strip(),)).fetchone()
   digest=hashlib.sha256(pwd.get().encode()).hexdigest()
   if not row or row["password_hash"]!=digest:
    messagebox.showerror("Login gagal","Username atau password salah.",parent=win); pwd.delete(0,"end"); pwd.focus_set(); return
   self.current_user=row["username"]; self.current_role=row["role"]; win.destroy()
   self.root.deiconify(); self.ui(); self.refresh()
  tk.Button(panel,text="MASUK",command=submit,bg=GREEN,fg=WHITE,relief="flat",font=("Segoe UI",12,"bold"),pady=12).pack(fill="x",padx=32)
  tk.Label(panel,text="Akun awal: admin/admin123  |  kasir/kasir123",bg=WHITE,fg="#718078",font=("Segoe UI",8)).pack(pady=14)
  pwd.bind("<Return>",submit); user.bind("<Return>",lambda e:pwd.focus_set()); user.focus_set()
 def logout(self):
  if messagebox.askyesno("Keluar","Keluar dari akun saat ini?"):
   for w in self.root.winfo_children(): w.destroy()
   self.current_user=None; self.current_role=None; self.login_screen()
 def style(self):
  s=ttk.Style(); s.theme_use("clam"); s.configure("Treeview",rowheight=30,font=("Segoe UI",10),background=WHITE,fieldbackground=WHITE,foreground=INK)
  s.configure("Treeview.Heading",font=("Segoe UI",10,"bold"),background="#E4EFE8",foreground=DARK)
 def ui(self):
  h=tk.Frame(self.root,bg=DARK,height=72); h.pack(fill="x"); h.pack_propagate(False)
  tk.Label(h,text="$",bg=GREEN,fg=WHITE,font=("Segoe UI",25,"bold"),width=3).pack(side="left",padx=16,pady=10)
  tk.Label(h,text="SeaMart POS",bg=DARK,fg=WHITE,font=("Segoe UI",21,"bold")).pack(side="left")
  tk.Label(h,text=f"KASIR MINIMARKET  •  {self.current_role.upper()} : {self.current_user}",bg=DARK,fg="#C8E4D2",font=("Segoe UI",10)).pack(side="left",padx=18,pady=20)
  tk.Button(h,text="KELUAR",command=self.logout,bg="#245743",fg=WHITE,relief="flat",padx=12,pady=9).pack(side="right",padx=8)
  if self.current_role=="Admin":
   tk.Button(h,text="REKAP PENJUALAN",command=self.sales_report,bg="#245743",fg=WHITE,relief="flat",padx=12,pady=9).pack(side="right",padx=5)
   tk.Button(h,text="MASTER BARANG",command=self.master_items,bg="#245743",fg=WHITE,relief="flat",padx=12,pady=9).pack(side="right",padx=5)
   tk.Button(h,text="IMPOR CSV",command=self.import_csv,bg=GREEN,fg=WHITE,relief="flat",font=("Segoe UI",10,"bold"),padx=18,pady=9).pack(side="right",padx=8)
  body=tk.Frame(self.root,bg=BG); body.pack(fill="both",expand=True,padx=15,pady=15)
  left=tk.Frame(body,bg=WHITE,highlightbackground="#D7E4DB",highlightthickness=1); left.pack(side="left",fill="both",expand=True,padx=(0,10))
  right=tk.Frame(body,bg=WHITE,width=390,highlightbackground="#D7E4DB",highlightthickness=1); right.pack(side="right",fill="y"); right.pack_propagate(False)
  tk.Label(left,text="CARI / SCAN BARCODE ATAU KETIK NAMA",bg=WHITE,fg=DARK,font=("Segoe UI",10,"bold")).pack(anchor="w",padx=14,pady=(14,4))
  row=tk.Frame(left,bg=WHITE); row.pack(fill="x",padx=14,pady=(0,10))
  self.q=tk.StringVar(); self.search=tk.Entry(row,textvariable=self.q,font=("Segoe UI",14),relief="solid",bd=1); self.search.pack(side="left",fill="x",expand=True,ipady=8)
  self.search.bind("<KeyRelease>",lambda e:self.refresh()); self.search.bind("<Return>",self.scan)
  self.root.bind("<F2>",lambda e:self.remove()); self.root.bind("<F4>",lambda e:self.checkout()); self.root.bind("<F5>",lambda e:self.hold_cart()); self.root.bind("<F6>",lambda e:self.recall_cart()); self.root.bind("<F12>",lambda e:self.close_cashier())
  tk.Button(row,text="CARI",command=self.refresh,bg=GREEN,fg=WHITE,relief="flat",padx=18,pady=9).pack(side="left",padx=8)
  self.category=tk.StringVar(value="Semua")
  self.category_bar=tk.Frame(left,bg=WHITE); self.category_bar.pack(fill="x",padx=12,pady=(0,10))
  self.product_canvas=tk.Canvas(left,bg="#F4F8F5",highlightthickness=0)
  self.product_scroll=ttk.Scrollbar(left,orient="vertical",command=self.product_canvas.yview)
  self.product_canvas.configure(yscrollcommand=self.product_scroll.set)
  self.product_scroll.pack(side="right",fill="y",padx=(0,8),pady=(0,12))
  self.product_canvas.pack(fill="both",expand=True,padx=(14,0),pady=(0,14))
  self.product_grid=tk.Frame(self.product_canvas,bg="#F4F8F5")
  self.product_window=self.product_canvas.create_window((0,0),window=self.product_grid,anchor="nw")
  self.product_grid.bind("<Configure>",lambda e:self.product_canvas.configure(scrollregion=self.product_canvas.bbox("all")))
  self.product_canvas.bind("<Configure>",lambda e:self.product_canvas.itemconfigure(self.product_window,width=e.width))
  self.product_canvas.bind_all("<MouseWheel>",lambda e:self.product_canvas.yview_scroll(int(-1*(e.delta/120)),"units"))
  self.products=[]
  tk.Label(right,text="KERANJANG BELANJA",bg=GREEN,fg=WHITE,font=("Segoe UI",14,"bold"),anchor="w",padx=14,pady=14).pack(fill="x")
  self.cartview=ttk.Treeview(right,columns=("name","qty","sub"),show="headings",height=6)
  for c,t,w,a in [("name","Barang",205,"w"),("qty","Qty",45,"center"),("sub","Subtotal",125,"e")]:
   self.cartview.heading(c,text=t); self.cartview.column(c,width=w,anchor=a)
  self.cartview.pack(fill="both",expand=True,padx=12,pady=10)
  ar=tk.Frame(right,bg=WHITE); ar.pack(fill="x",padx=12)
  for t,cmd,bg in [("− QTY",lambda:self.qty(-1),"#E5F2E9"),("+ QTY",lambda:self.qty(1),"#E5F2E9"),("HAPUS",self.remove,"#FBE8E7"),("KOSONGKAN",self.clear,"#EEF1EF")]:
   tk.Button(ar,text=t,command=cmd,bg=bg,fg=DARK,relief="flat",padx=9,pady=7).pack(side="left",padx=2)
  box=tk.Frame(right,bg="#E7F5EC"); box.pack(fill="x",padx=12,pady=12)
  tk.Label(box,text="TOTAL BELANJA",bg="#E7F5EC",fg=DARK,font=("Segoe UI",10,"bold")).pack(anchor="w",padx=12,pady=(8,0))
  self.total=tk.Label(box,text="Rp 0",bg="#E7F5EC",fg=GREEN,font=("Segoe UI",24,"bold")); self.total.pack(anchor="e",padx=12,pady=(0,8))
  tk.Label(right,text="METODE PEMBAYARAN",bg=WHITE,fg=DARK,font=("Segoe UI",10,"bold")).pack(anchor="w",padx=14)
  self.method=tk.StringVar(value="Tunai"); opts=tk.Frame(right,bg=WHITE); opts.pack(anchor="w",padx=12,pady=5)
  tk.Radiobutton(opts,text="Tunai",variable=self.method,value="Tunai",bg=WHITE,fg=DARK).pack(side="left",padx=6)
  tk.Radiobutton(opts,text="QRIS",variable=self.method,value="QRIS",bg=WHITE,fg=DARK).pack(side="left",padx=6)
  pay=tk.Frame(right,bg=WHITE); pay.pack(fill="x",padx=14)
  tk.Label(pay,text="Uang diterima",bg=WHITE,fg=INK).pack(side="left")
  self.cash=tk.StringVar(value="0"); tk.Entry(pay,textvariable=self.cash,width=16,justify="right",font=("Segoe UI",11)).pack(side="right",ipady=5)
  tk.Button(right,text="BAYAR & SIMPAN TRANSAKSI",command=self.checkout,bg=GREEN,fg=WHITE,relief="flat",font=("Segoe UI",12,"bold"),pady=13).pack(fill="x",padx=12,pady=(12,6))
  tk.Button(right,text="CETAK ULANG STRUK",command=self.reprint,bg=DARK,fg=WHITE,relief="flat",font=("Segoe UI",10,"bold"),pady=9).pack(fill="x",padx=12,pady=(0,6))
  tk.Button(right,text="BATAL TRANSAKSI",command=self.cancel_transaction,bg="#FCE8E6",fg="#B42318",relief="flat",font=("Segoe UI",11,"bold"),pady=10).pack(fill="x",padx=12,pady=(0,10))
  tk.Label(self.root,text="SeaMart POS  •  Database lokal di komputer ini  •  Scanner USB/Bluetooth mode keyboard",bg="#E4EEE7",fg="#53675B",anchor="w",padx=15,pady=7).pack(fill="x",side="bottom")
 def sales_report(self):
  win=tk.Toplevel(self.root); win.title("Rekap Penjualan per Barang - SeaMart"); win.geometry("1050x680"); win.minsize(850,520); win.configure(bg=BG)
  top=tk.Frame(win,bg=WHITE); top.pack(fill="x",padx=14,pady=14)
  tk.Label(top,text="REKAP PENJUALAN PER BARANG",bg=WHITE,fg=DARK,font=("Segoe UI",16,"bold")).pack(anchor="w",padx=12,pady=(10,4))
  tk.Label(top,text="Filter tanggal opsional (format YYYY-MM-DD). Kosongkan untuk semua periode.",bg=WHITE,fg="#64756B").pack(anchor="w",padx=12)
  filters=tk.Frame(top,bg=WHITE); filters.pack(fill="x",padx=12,pady=10)
  tk.Label(filters,text="Dari tanggal",bg=WHITE,fg=DARK).pack(side="left")
  start=tk.Entry(filters,width=14); start.pack(side="left",padx=(6,16),ipady=5)
  tk.Label(filters,text="Sampai tanggal",bg=WHITE,fg=DARK).pack(side="left")
  end=tk.Entry(filters,width=14); end.pack(side="left",padx=6,ipady=5)
  tree=ttk.Treeview(win,columns=("barcode","name","qty","trx","revenue"),show="headings")
  for col,label,width,anchor in [("barcode","Barcode",150,"center"),("name","Nama Barang",360,"w"),("qty","Qty Terjual",100,"e"),("trx","Jumlah Transaksi",130,"e"),("revenue","Total Penjualan",170,"e")]:
   tree.heading(col,text=label); tree.column(col,width=width,anchor=anchor)
  tree.pack(fill="both",expand=True,padx=14,pady=(0,8))
  summary=tk.Label(win,text="Total barang terjual: 0  |  Omzet: Rp 0",bg="#E7F5EC",fg=DARK,font=("Segoe UI",13,"bold"),anchor="w",padx=14,pady=12)
  summary.pack(fill="x",padx=14,pady=(0,10))
  current=[]
  def load():
   a=start.get().strip(); b=end.get().strip()
   for value in (a,b):
    if value:
     try: datetime.strptime(value,"%Y-%m-%d")
     except ValueError:
      messagebox.showwarning("Format tanggal","Gunakan format YYYY-MM-DD.",parent=win); return
   if a and b and a>b:
    messagebox.showwarning("Rentang tanggal","Tanggal awal tidak boleh melewati tanggal akhir.",parent=win); return
   sql="SELECT barcode,name,SUM(qty) AS qty,COUNT(DISTINCT sale_id) AS trx,SUM(subtotal) AS revenue FROM sale_items si JOIN sales s ON s.id=si.sale_id WHERE 1=1"
   args=[]
   if a: sql+=" AND substr(s.created,1,10)>=?"; args.append(a)
   if b: sql+=" AND substr(s.created,1,10)<=?"; args.append(b)
   sql+=" GROUP BY COALESCE(barcode,''),name ORDER BY revenue DESC,name"
   rows=self.db.execute(sql,args).fetchall(); current.clear(); current.extend([dict(x) for x in rows])
   tree.delete(*tree.get_children()); total_qty=0; total_rev=0
   for i,row in enumerate(rows):
    qty=row["qty"] or 0; rev=row["revenue"] or 0; total_qty+=qty; total_rev+=rev
    tree.insert("","end",values=(row["barcode"] or "-",row["name"],f"{qty:g}",row["trx"],self.rp(rev)))
   summary.config(text=f"Total barang terjual: {total_qty:g}  |  Omzet: {self.rp(total_rev)}  |  Jenis barang: {len(rows)}")
  def export():
   if not current:
    messagebox.showinfo("Ekspor CSV","Tidak ada data untuk diekspor.",parent=win); return
   path=filedialog.asksaveasfilename(parent=win,defaultextension=".csv",initialfile="rekap_penjualan_per_barang.csv",filetypes=[("CSV","*.csv")])
   if not path:return
   try:
    with open(path,"w",encoding="utf-8-sig",newline="") as f:
     wr=csv.writer(f); wr.writerow(["barcode","nama_barang","qty_terjual","jumlah_transaksi","total_penjualan"])
     for row in current: wr.writerow([row["barcode"] or "",row["name"],row["qty"],row["trx"],row["revenue"]])
    messagebox.showinfo("Ekspor selesai","Rekap berhasil disimpan ke CSV.",parent=win)
   except Exception as ex: messagebox.showerror("Ekspor gagal",str(ex),parent=win)
  buttons=tk.Frame(win,bg=BG); buttons.pack(fill="x",padx=14,pady=(0,14))
  tk.Button(filters,text="TAMPILKAN",command=load,bg=GREEN,fg=WHITE,relief="flat",padx=16,pady=7).pack(side="left",padx=8)
  tk.Button(buttons,text="EKSPOR CSV",command=export,bg=DARK,fg=WHITE,relief="flat",padx=18,pady=9).pack(side="right")
  load()
 def master_items(self):
  win=tk.Toplevel(self.root); win.title("Master Barang - SeaMart"); win.geometry("1000x620"); win.configure(bg=BG)
  top=tk.Frame(win,bg=WHITE); top.pack(fill="x",padx=12,pady=12)
  q=tk.StringVar(); ent=tk.Entry(top,textvariable=q,font=("Segoe UI",12)); ent.pack(side="left",fill="x",expand=True,ipady=7)
  tree=ttk.Treeview(win,columns=("id","barcode","name","category","cost","price","stock"),show="headings")
  for c,t,w in [("id","ID",55),("barcode","Barcode",130),("name","Nama Barang",250),("category","Kategori",130),("cost","Harga Modal",110),("price","Harga Jual",110),("stock","Stok",75)]:
   tree.heading(c,text=t); tree.column(c,width=w)
  tree.pack(fill="both",expand=True,padx=12,pady=6)
  def load(*args):
   tree.delete(*tree.get_children()); term=q.get().strip()
   rows=self.db.execute("SELECT * FROM products WHERE name LIKE ? OR barcode LIKE ? ORDER BY name",(f"%{term}%",f"%{term}%")).fetchall()
   for r in rows: tree.insert("","end",iid=str(r["id"]),values=(r["id"],r["barcode"] or "",r["name"],r["category"] or "",self.rp(r["cost"]),self.rp(r["price"]),r["stock"]))
  def form(existing=None):
   old=self.db.execute("SELECT * FROM products WHERE id=?",(int(existing),)).fetchone() if existing else None
   dlg=tk.Toplevel(win); dlg.title("Edit Barang" if old else "Tambah Barang"); dlg.geometry("420x430"); dlg.configure(bg=WHITE)
   fields=[("Barcode","barcode"),("Nama Barang","name"),("Kategori","category"),("Harga Modal","cost"),("Harga Jual","price"),("Stok","stock")]
   entries={}
   for label,key in fields:
    tk.Label(dlg,text=label,bg=WHITE,fg=DARK).pack(anchor="w",padx=20,pady=(8,0))
    e=tk.Entry(dlg,font=("Segoe UI",11)); e.pack(fill="x",padx=20,ipady=5); entries[key]=e
    if old and old[key] is not None:e.insert(0,str(old[key]))
   def save():
    v={k:e.get().strip() for k,e in entries.items()}
    if not v["name"]: messagebox.showwarning("Validasi","Nama barang wajib diisi.",parent=dlg); return
    try:
     nums=[float(v[k].replace(".","").replace(",",".") or 0) for k in ("cost","price","stock")]
     if old:
      self.db.execute("UPDATE products SET barcode=?,name=?,category=?,cost=?,price=?,stock=? WHERE id=?",(v["barcode"] or None,v["name"],v["category"],*nums,old["id"]))
     else:
      self.db.execute("INSERT INTO products(barcode,name,category,cost,price,stock) VALUES(?,?,?,?,?,?)",(v["barcode"] or None,v["name"],v["category"],*nums))
     self.db.commit(); dlg.destroy(); load(); self.refresh()
    except Exception as ex: messagebox.showerror("Gagal menyimpan",str(ex),parent=dlg)
   tk.Button(dlg,text="SIMPAN BARANG",command=save,bg=GREEN,fg=WHITE,relief="flat",pady=10).pack(fill="x",padx=20,pady=14)
  def selected():
   sel=tree.selection(); return sel[0] if sel else None
  bar=tk.Frame(win,bg=BG); bar.pack(fill="x",padx=12,pady=10)
  tk.Button(top,text="CARI",command=load,bg=GREEN,fg=WHITE,relief="flat",padx=16).pack(side="left",padx=6)
  tk.Button(bar,text="+ TAMBAH",command=lambda:form(),bg=GREEN,fg=WHITE,relief="flat",padx=16,pady=9).pack(side="left",padx=4)
  tk.Button(bar,text="EDIT",command=lambda:form(selected()) if selected() else messagebox.showinfo("Pilih barang","Pilih barang dulu."),bg="#2563A6",fg=WHITE,relief="flat",padx=16,pady=9).pack(side="left",padx=4)
  def delete():
   key=selected()
   if key and messagebox.askyesno("Hapus barang","Yakin menghapus barang ini?"):
    self.db.execute("DELETE FROM products WHERE id=?",(int(key),)); self.db.commit(); load(); self.refresh()
  tk.Button(bar,text="HAPUS",command=delete,bg="#B42318",fg=WHITE,relief="flat",padx=16,pady=9).pack(side="left",padx=4)
  q.trace_add("write",load); load()
 def hold_cart(self):
  if not self.cart: messagebox.showinfo("Transaksi tertunda","Keranjang masih kosong."); return
  import json
  payload=json.dumps(list(self.cart.values()),ensure_ascii=False)
  cur=self.db.execute("INSERT INTO held_transactions(label,payload,created) VALUES(?,?,?)",(datetime.now().strftime("Tertunda %H:%M:%S"),payload,datetime.now().isoformat(timespec="seconds")))
  self.db.commit(); self.clear(); messagebox.showinfo("Tersimpan",f"Transaksi tertunda #{cur.lastrowid}")
 def recall_cart(self):
  import json
  rows=self.db.execute("SELECT * FROM held_transactions ORDER BY id").fetchall()
  if not rows: messagebox.showinfo("Transaksi tertunda","Tidak ada transaksi tertunda."); return
  win=tk.Toplevel(self.root); win.title("Panggil Transaksi"); win.geometry("520x350")
  tree=ttk.Treeview(win,columns=("id","label","created"),show="headings")
  for c,t in [("id","No"),("label","Transaksi"),("created","Waktu")]: tree.heading(c,text=t)
  tree.pack(fill="both",expand=True,padx=10,pady=10)
  for r in rows: tree.insert("","end",iid=str(r["id"]),values=(r["id"],r["label"],r["created"]))
  def load():
   sel=tree.selection()
   if not sel: return
   if self.cart:
    if not messagebox.askyesno("Keranjang aktif","Simpan keranjang saat ini sebagai transaksi tertunda?"): return
    self.hold_cart()
   row=self.db.execute("SELECT * FROM held_transactions WHERE id=?",(int(sel[0]),)).fetchone()
   for x in json.loads(row["payload"]): self.cart[str(x["id"])]=x
   self.db.execute("DELETE FROM held_transactions WHERE id=?",(int(sel[0]),)); self.db.commit(); self.draw_cart(); win.destroy()
  tk.Button(win,text="PANGGIL TRANSAKSI",command=load,bg=GREEN,fg=WHITE,relief="flat",pady=9).pack(fill="x",padx=10,pady=8)
 def close_cashier(self):
  if self.cart and not messagebox.askyesno("Tutup kasir","Keranjang masih berisi barang. Tutup halaman kasir?"): return
  self.clear(); self.logout()
 def rp(self,n): return "Rp "+format(int(round(n or 0)),",").replace(",",".")
 def refresh(self):
  if not hasattr(self,"product_grid"): return
  for w in self.product_grid.winfo_children(): w.destroy()
  q=self.q.get().strip()
  cats=["Semua"]+[x[0] for x in self.db.execute("SELECT DISTINCT category FROM products WHERE TRIM(COALESCE(category,''))<>'' ORDER BY category").fetchall()]
  for w in self.category_bar.winfo_children(): w.destroy()
  for cat in cats[:7]:
   active=self.category.get()==cat
   tk.Button(self.category_bar,text=cat,command=lambda value=cat:self.set_category(value),bg=GREEN if active else "#EEF2F0",fg=WHITE if active else INK,relief="flat",font=("Segoe UI",9,"bold"),padx=12,pady=7).pack(side="left",padx=3)
  sql="SELECT * FROM products WHERE (name LIKE ? OR barcode LIKE ? OR CAST(id AS TEXT) LIKE ?)"
  args=[f"%{q}%",f"%{q}%",f"%{q}%"]
  if self.category.get()!="Semua":
   sql+=" AND category=?"; args.append(self.category.get())
  rows=self.db.execute(sql+" ORDER BY name LIMIT 500",args).fetchall()
  for i,r in enumerate(rows):
   card=tk.Frame(self.product_grid,bg=WHITE,highlightbackground="#D9E6DD",highlightthickness=1,cursor="hand2")
   card.grid(row=i//2,column=i%2,sticky="nsew",padx=8,pady=8)
   self.product_grid.grid_columnconfigure(i%3,weight=1,uniform="product")
   tk.Label(card,text=r["name"],bg=WHITE,fg=DARK,font=("Segoe UI",13,"bold"),anchor="w",justify="left",wraplength=270).pack(fill="x",padx=10,pady=(10,5))
   tk.Label(card,text=f'KB{r["id"]:04d}  |  {r["barcode"] or "-"}',bg=WHITE,fg="#718078",font=("Segoe UI",9),anchor="w").pack(fill="x",padx=10)
   tk.Label(card,text=self.rp(r["price"]),bg=WHITE,fg=GREEN,font=("Segoe UI",17,"bold"),anchor="w").pack(fill="x",padx=10,pady=(9,4))
   stock_color="#B42318" if (r["stock"] or 0)<=0 else "#087A45"
   tk.Label(card,text=f'Stok {r["stock"]:g}',bg="#E5F5EB",fg=stock_color,font=("Segoe UI",9,"bold"),anchor="w").pack(anchor="w",padx=10,pady=(0,10))
   for child in card.winfo_children(): child.bind("<Button-1>",lambda e,row=r:self.add(row))
   card.bind("<Button-1>",lambda e,row=r:self.add(row))
  self.product_canvas.yview_moveto(0)
 def set_category(self,value):
  self.category.set(value); self.refresh()
 def scan(self,e=None):
  q=self.q.get().strip(); r=self.db.execute("SELECT * FROM products WHERE barcode=?",(q,)).fetchone()
  if not r:r=self.db.execute("SELECT * FROM products WHERE name LIKE ? ORDER BY name LIMIT 1",(f"%{q}%",)).fetchone()
  if r:self.add(r); self.q.set(""); self.refresh()
  elif q:messagebox.showinfo("Tidak ditemukan","Barang tidak ditemukan.")
  return "break"
 def add_selected(self,e=None):
  s=self.products.selection()
  if s:
   r=self.db.execute("SELECT * FROM products WHERE id=?",(int(s[0]),)).fetchone()
   if r:self.add(r)
 def add(self,r):
  k=str(r["id"]); self.cart.setdefault(k,{"id":r["id"],"barcode":r["barcode"] or "", "name":r["name"],"price":r["price"],"qty":0,"stock":r["stock"]}); self.cart[k]["qty"]+=1; self.draw_cart()
 def draw_cart(self):
  self.cartview.delete(*self.cartview.get_children()); total=0
  for k,x in self.cart.items():
   sub=x["qty"]*x["price"]; total+=sub; self.cartview.insert("","end",iid=k,values=(x["name"],x["qty"],self.rp(sub)))
  self.sum=total; self.total.config(text=self.rp(total))
 def selected(self): 
  s=self.cartview.selection(); return s[0] if s else None
 def qty(self,d):
  k=self.selected()
  if k:
   self.cart[k]["qty"]+=d
   if self.cart[k]["qty"]<=0:del self.cart[k]
   self.draw_cart()
 def remove(self):
  k=self.selected()
  if k and k in self.cart:del self.cart[k];self.draw_cart()
 def clear(self):
  self.cart.clear();self.draw_cart()
 def cancel_transaction(self):
  if not self.cart:
   messagebox.showinfo("Batal transaksi","Keranjang sudah kosong."); return
  if messagebox.askyesno("Batal transaksi","Batalkan transaksi yang sedang berjalan? Semua barang di keranjang akan dihapus. Riwayat penjualan tidak akan terhapus."):
   self.clear(); self.cash.set("0"); self.q.set(""); self.refresh()
 def import_csv(self):
  p=filedialog.askopenfilename(filetypes=[("CSV","*.csv")])
  if not p:return
  n=0
  try:
   with open(p,encoding="utf-8-sig",newline="") as f:
    rd=csv.DictReader(f)
    for row in rd:
     row={str(k or "").strip().lower():(v or "").strip() for k,v in row.items()}
     name=row.get("nama") or row.get("name") or row.get("nama barang")
     if not name:continue
     def num(k):
      try:return float(row.get(k,"0").replace(".","").replace(",",".") or 0)
      except:return 0
     self.db.execute("INSERT INTO products(barcode,name,category,cost,price,stock) VALUES(?,?,?,?,?,?) ON CONFLICT(barcode) DO UPDATE SET name=excluded.name,category=excluded.category,cost=excluded.cost,price=excluded.price,stock=excluded.stock",(row.get("barcode") or None,name,row.get("kategori",""),num("harga_modal"),num("harga_jual"),num("stok"))); n+=1
   self.db.commit();self.refresh();messagebox.showinfo("Impor selesai",f"{n} barang diproses.")
  except Exception as ex:messagebox.showerror("Impor gagal",str(ex))
 def checkout(self):
  if not self.cart:messagebox.showwarning("Kosong","Tambahkan barang dulu.");return
  total=sum(x["qty"]*x["price"] for x in self.cart.values()); method=self.method.get()
  if method=="Tunai":
   try:paid=float(self.cash.get().replace(".","").replace(",","") or 0)
   except:paid=0
   if paid<total:messagebox.showwarning("Pembayaran kurang","Uang diterima kurang.");return
  else:
   if not messagebox.askyesno("Verifikasi QRIS","Pastikan QRIS berhasil masuk di aplikasi merchant. Sudah dicek?"):return
   paid=total
  now=datetime.now(); inv="SM-"+now.strftime("%Y%m%d-%H%M%S")
  try:
   cur=self.db.cursor();cur.execute("INSERT INTO sales(invoice,created,method,total,paid,change_due) VALUES(?,?,?,?,?,?)",(inv,now.isoformat(timespec="seconds"),method,total,paid,max(0,paid-total))); sid=cur.lastrowid
   for x in self.cart.values():
    cur.execute("INSERT INTO sale_items(sale_id,barcode,name,qty,price,subtotal) VALUES(?,?,?,?,?,?)",(sid,x["barcode"],x["name"],x["qty"],x["price"],x["qty"]*x["price"]))
    if x["stock"]>0:cur.execute("UPDATE products SET stock=stock-? WHERE id=?",(x["qty"],x["id"]))
   self.db.commit()
  except Exception as ex:self.db.rollback();messagebox.showerror("Gagal",str(ex));return
  lines=["SEAMART MINIMARKET","STRUK PEMBELIAN","-"*30,inv,now.strftime("%d/%m/%Y %H:%M"),"-"*30]
  for x in self.cart.values():lines += [x["name"][:30],f'{x["qty"]:g} x {self.rp(x["price"])}',self.rp(x["qty"]*x["price"])]
  lines += ["-"*30,"TOTAL "+self.rp(total),"METODE "+method.upper()]
  if method=="Tunai":lines += ["BAYAR "+self.rp(paid),"KEMBALI "+self.rp(paid-total)]
  lines += ["-"*30,"Terima kasih"]
  self.last_receipt="\n".join(lines); self.cart.clear();self.draw_cart();self.refresh()
  messagebox.showinfo("Transaksi tersimpan",inv);self.reprint()
 def reprint(self):
  if not self.last_receipt:messagebox.showinfo("Struk","Belum ada struk.");return
  p=DATA/"struk_terakhir.txt";p.write_text(self.last_receipt,encoding="utf-8")
  try:os.startfile(str(p),"print")
  except:os.startfile(str(p))
 def close(self):self.db.close();self.root.destroy()

if __name__=="__main__":
 root=tk.Tk(); app=SeaMart(root); root.mainloop()
