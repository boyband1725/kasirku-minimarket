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
  self.root=root; root.title("SeaMart POS"); root.geometry("1200x760"); root.minsize(980,620); root.configure(bg=BG)
  self.db=sqlite3.connect(DB); self.db.row_factory=sqlite3.Row
  self.db.executescript("""
  CREATE TABLE IF NOT EXISTS users(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, role TEXT NOT NULL);
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
  if self.current_role=="Admin": tk.Button(h,text="IMPOR CSV",command=self.import_csv,bg=GREEN,fg=WHITE,relief="flat",font=("Segoe UI",10,"bold"),padx=18,pady=9).pack(side="right",padx=18)
  body=tk.Frame(self.root,bg=BG); body.pack(fill="both",expand=True,padx=15,pady=15)
  left=tk.Frame(body,bg=WHITE,highlightbackground="#D7E4DB",highlightthickness=1); left.pack(side="left",fill="both",expand=True,padx=(0,10))
  right=tk.Frame(body,bg=WHITE,width=420,highlightbackground="#D7E4DB",highlightthickness=1); right.pack(side="right",fill="y"); right.pack_propagate(False)
  tk.Label(left,text="CARI / SCAN BARCODE ATAU KETIK NAMA",bg=WHITE,fg=DARK,font=("Segoe UI",10,"bold")).pack(anchor="w",padx=14,pady=(14,4))
  row=tk.Frame(left,bg=WHITE); row.pack(fill="x",padx=14,pady=(0,12))
  self.q=tk.StringVar(); self.search=tk.Entry(row,textvariable=self.q,font=("Segoe UI",15),relief="solid",bd=1); self.search.pack(side="left",fill="x",expand=True,ipady=8)
  self.search.bind("<KeyRelease>",lambda e:self.refresh()); self.search.bind("<Return>",self.scan)
  tk.Button(row,text="CARI",command=self.refresh,bg=GREEN,fg=WHITE,relief="flat",padx=18,pady=9).pack(side="left",padx=8)
  tk.Label(left,text="DAFTAR BARANG  •  klik dua kali untuk menambahkan",bg=WHITE,fg=DARK,font=("Segoe UI",11,"bold")).pack(anchor="w",padx=14,pady=(0,7))
  cols=("barcode","name","price","stock"); self.products=ttk.Treeview(left,columns=cols,show="headings")
  for c,t,w,a in [("barcode","Barcode",150,"center"),("name","Nama Barang",360,"w"),("price","Harga Jual",120,"e"),("stock","Stok",70,"center")]:
   self.products.heading(c,text=t); self.products.column(c,width=w,anchor=a)
  self.products.pack(fill="both",expand=True,padx=14,pady=(0,14)); self.products.bind("<Double-1>",self.add_selected)
  tk.Label(right,text="KERANJANG BELANJA",bg=GREEN,fg=WHITE,font=("Segoe UI",14,"bold"),anchor="w",padx=14,pady=14).pack(fill="x")
  self.cartview=ttk.Treeview(right,columns=("name","qty","sub"),show="headings",height=12)
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
  tk.Button(right,text="CETAK ULANG STRUK",command=self.reprint,bg=DARK,fg=WHITE,relief="flat",font=("Segoe UI",10,"bold"),pady=9).pack(fill="x",padx=12)
  tk.Label(self.root,text="SeaMart POS  •  Database lokal di komputer ini  •  Scanner USB/Bluetooth mode keyboard",bg="#E4EEE7",fg="#53675B",anchor="w",padx=15,pady=7).pack(fill="x",side="bottom")
 def rp(self,n): return "Rp "+format(int(round(n or 0)),",").replace(",",".")
 def refresh(self):
  if not hasattr(self,"products"): return
  self.products.delete(*self.products.get_children()); q=self.q.get().strip()
  if q: rows=self.db.execute("SELECT * FROM products WHERE name LIKE ? OR barcode LIKE ? ORDER BY name LIMIT 500",(f"%{q}%",f"%{q}%")).fetchall()
  else: rows=self.db.execute("SELECT * FROM products ORDER BY name LIMIT 500").fetchall()
  for r in rows:self.products.insert("","end",iid=str(r["id"]),values=(r["barcode"] or "",r["name"],self.rp(r["price"]),r["stock"]))
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
