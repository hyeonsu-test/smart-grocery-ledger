"""Tkinter desktop presentation; all calculations and writes go through services."""
import copy
import json
import tkinter as tk
from tkinter import ttk, messagebox, filedialog
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
from .core import (Repository, LedgerService, BudgetService, ProductService,
                   PriceService, TargetService, MartAdapter, STORES, CATEGORIES,
                   UNITS, now_local, monthly)
from .demo import make_demo, write_demo_feeds

BG='#f3f6f7'; INK='#173330'; GREEN='#126b5b'; MUTED='#637572'


def money(value):
    return '미확정' if value is None else f'{value:,.0f}원'


def unit_money(value):
    return '자료 부족' if value is None else f'{value:,.2f}원'


def unit_text(product):
    factor=UNITS[product['category']][1]
    return f'{factor}{product["unit"]}'


class LedgerApp(tk.Tk):
    """Five-screen controller, local file selection and in-app notification UI."""
    def __init__(self,data_dir,demo=False):
        super().__init__()
        self.title('Smart Grocery Ledger · Beta 0.4.0')
        self.geometry('1180x820');self.minsize(1080,760)
        self.configure(bg=BG);self.data_dir=Path(data_dir);self.demo=demo
        self.month=now_local().strftime('%Y-%m');self.active=0
        self.draft_plan=None;self.plan_dirty=False;self.ledger_baseline=None
        self.notification_text='목표 알림은 유효한 가격을 갱신할 때 확인합니다.'
        self.style_setup();self.connect()
        self.header=tk.Frame(self,bg=INK,padx=26,pady=18);self.header.pack(fill='x')
        tk.Label(self.header,text='Smart Grocery Ledger',font=('Segoe UI',22,'bold'),bg=INK,fg='white').pack(side='left')
        tk.Label(self.header,text='BETA 0.4.0',font=('Segoe UI',10,'bold'),bg=INK,fg='#9bd4bd',padx=16).pack(side='left')
        ttk.Button(self.header,text='API 매뉴얼',command=self.show_api).pack(side='right',padx=3)
        ttk.Button(self.header,text='데이터 파일',command=self.choose_file).pack(side='right',padx=3)
        ttk.Button(self.header,text='실사용 / 시연 전환',command=self.toggle_demo).pack(side='right',padx=3)
        self.mode_label=tk.Label(self,bg='#e4f1e9',fg=GREEN,anchor='w',padx=27,pady=9,font=('맑은 고딕',10));self.mode_label.pack(fill='x')
        self.nav=ttk.Frame(self,padding=(24,16,24,8));self.nav.pack(fill='x')
        self.nav_buttons=[]
        for i,label in enumerate(['01  가계부','02  예산 · 장보기','03  상품 · 가격','04  비교 · 이력','05  목표 · 알림']):
            b=ttk.Button(self.nav,text=label,command=lambda n=i:self.navigate(n));b.pack(side='left',fill='x',expand=True,padx=3);self.nav_buttons.append(b)
        self.content=ttk.Frame(self,padding=(28,12,28,12));self.content.pack(fill='both',expand=True)
        self.status=tk.StringVar(value='준비 완료')
        ttk.Label(self,textvariable=self.status,style='Status.TLabel',padding=(28,10)).pack(fill='x',side='bottom')
        self.protocol('WM_DELETE_WINDOW',self.close)
        self.render();self.after(1000,self.auto_refresh)

    def style_setup(self):
        style=ttk.Style(self);style.theme_use('clam')
        style.configure('.',font=('맑은 고딕',10),background=BG,foreground=INK)
        style.configure('TButton',padding=(12,8),background='#e5eeeb',borderwidth=0)
        style.map('TButton',background=[('active','#cbe3d9')])
        style.configure('Primary.TButton',background=GREEN,foreground='white')
        style.map('Primary.TButton',background=[('active','#0d5145')])
        style.configure('TEntry',padding=5,fieldbackground='white')
        style.configure('TCombobox',padding=4,fieldbackground='white')
        style.configure('Treeview',rowheight=34,background='white',fieldbackground='white',borderwidth=0)
        style.configure('Treeview.Heading',font=('맑은 고딕',10,'bold'),padding=8,background='#e6eeeb')
        style.map('Treeview',background=[('selected',GREEN)],foreground=[('selected','white')])
        style.configure('Title.TLabel',font=('맑은 고딕',17,'bold'))
        style.configure('Muted.TLabel',foreground=MUTED)
        style.configure('Status.TLabel',background='#e4ece8',foreground=GREEN)

    def connect(self,path=None):
        self.repo=Repository(path or self.data_dir/('demo.json' if self.demo else 'ledger.json'))
        if self.demo and not self.repo.path.exists():
            result=self.repo.saveAtomic(make_demo())
            if result['status']!='OK':messagebox.showerror('시연 파일 오류',result['message'])
        self.demo=self.repo.state.demo
        if self.demo:
            paths=write_demo_feeds(self.data_dir/'demo-feeds',self.repo.state)
        else:
            try:paths=json.loads((self.data_dir/'feeds.json').read_text(encoding='utf-8'))
            except (OSError,ValueError):paths={}
        self.adapter=MartAdapter(paths)
        self.ledger=LedgerService(self.repo);self.budget=BudgetService(self.repo)
        self.products=ProductService(self.repo,self.adapter);self.prices=PriceService(self.repo);self.targets=TargetService(self.repo)
        self.products.on_price_updated=lambda ids:self.targets.managePurchaseTargets({'action':'EVALUATE','product_ids':ids})

    def confirm_leave(self):
        dirty=self.plan_dirty or (self.active==0 and getattr(self,'edit_id',None) is not None)
        if self.active==0 and self.ledger_baseline is not None:
            dirty=dirty or self.form_values(self.ledger_vars)!=self.ledger_baseline
        if dirty and not messagebox.askyesno('저장 전 이동','저장하지 않은 입력을 취소하고 이동할까요?',parent=self):return False
        self.plan_dirty=False;self.draft_plan=None;return True

    def close(self):
        if self.confirm_leave():self.destroy()

    def toggle_demo(self):
        if not self.confirm_leave():return
        self.demo=not self.demo;self.connect();self.notification_text='목표 알림은 가격 갱신 시 확인합니다.';self.render()

    def choose_file(self):
        if not self.confirm_leave():return
        path=filedialog.askopenfilename(title='정상 데이터 파일 열기',filetypes=[('JSON','*.json')])
        if path:self.connect(path);self.render()

    def navigate(self,index):
        if index!=self.active and not self.confirm_leave():return
        self.active=index;self.render()

    def render(self):
        for widget in self.content.winfo_children():widget.destroy()
        for i,b in enumerate(self.nav_buttons):b.configure(style='Primary.TButton' if i==self.active else 'TButton')
        mode='시연 데이터 · 실제 판매가가 아닙니다' if self.demo else '실사용 · 사용자 입력 / 등록된 JSON 피드'
        self.mode_label.configure(text=f'{mode}     |     개인 로컬 저장     |     {self.month}')
        if self.repo.read_error:
            ttk.Label(self.content,text='데이터 파일을 읽을 수 없습니다',style='Title.TLabel').pack(anchor='w',pady=15)
            ttk.Label(self.content,text=f'{self.repo.path}\n\n{self.repo.read_error}\n\n기존 파일은 덮어쓰지 않습니다. 상단에서 정상 데이터 파일을 선택하세요.',wraplength=1000).pack(anchor='w')
            return
        [self.draw_ledger,self.draw_budget,self.draw_products,self.draw_prices,self.draw_targets][self.active]()

    def heading(self,title,note):
        ttk.Label(self.content,text=title,style='Title.TLabel').pack(anchor='w')
        ttk.Label(self.content,text=note,style='Muted.TLabel').pack(anchor='w',pady=(5,14))

    def row(self,parent=None):
        frame=ttk.Frame(parent or self.content);frame.pack(fill='x',pady=5);return frame

    def field(self,parent,label,value='',choices=None,width=14):
        box=ttk.Frame(parent);box.pack(side='left',padx=(0,12),fill='x')
        ttk.Label(box,text=label,style='Muted.TLabel').pack(anchor='w',pady=(0,4))
        var=tk.StringVar(value=value)
        control=ttk.Combobox(box,textvariable=var,values=choices,width=width,state='readonly') if choices is not None else ttk.Entry(box,textvariable=var,width=width)
        control.pack(fill='x');return var

    def cards(self,values):
        row=self.row()
        for label,value in values:
            frame=tk.Frame(row,bg='white',padx=16,pady=12,highlightbackground='#dce7e1',highlightthickness=1)
            frame.pack(side='left',fill='both',expand=True,padx=(0,10))
            tk.Label(frame,text=label,bg='white',fg=MUTED,font=('맑은 고딕',10)).pack(anchor='w')
            tk.Label(frame,text=value,bg='white',fg=GREEN,font=('맑은 고딕',19,'bold')).pack(anchor='w',pady=(5,0))

    def tree(self,columns,widths,height=8,parent=None):
        frame=ttk.Frame(parent or self.content);frame.pack(fill='both',expand=True,pady=10)
        tree=ttk.Treeview(frame,columns=list(range(len(columns))),show='headings',height=height,selectmode='browse')
        for i,(label,width) in enumerate(zip(columns,widths)):
            tree.heading(i,text=label);tree.column(i,width=width,minwidth=40,anchor='w')
        bar=ttk.Scrollbar(frame,command=tree.yview);tree.configure(yscrollcommand=bar.set)
        tree.pack(side='left',fill='both',expand=True);bar.pack(side='right',fill='y');return tree

    def form_values(self,variables):return {key:var.get().strip() for key,var in variables.items()}

    def selected(self,tree):
        ids=tree.selection()
        if not ids:messagebox.showinfo('선택 필요','먼저 목록에서 항목을 선택하세요.',parent=self);return None
        return ids[0]

    def result(self,result,success='저장했습니다.'):
        if result['status']=='OK':self.status.set(success);return True
        if result['status']=='CANCELLED':return False
        messagebox.showerror(result['status'],result.get('message') or json.dumps(result.get('failures',{}),ensure_ascii=False),parent=self)
        self.status.set('실패 · 입력값과 기존 저장 내용을 유지했습니다.');return False

    def month_bar(self,callback):
        row=self.row();var=self.field(row,'조회 월 (YYYY-MM)',self.month,width=12)
        def change():
            from .core import month_key
            try:new=month_key(var.get())
            except ValueError as exc:messagebox.showerror('월 입력',str(exc));return
            if not self.confirm_leave():return
            self.month=new;callback()
        ttk.Button(row,text='월 조회',command=change).pack(side='left',anchor='s')
        return row

    def draw_ledger(self):
        self.heading('수입과 지출을 한눈에','실제 결제 금액만 합산합니다. 장보기 계획은 내역에 포함되지 않습니다.')
        row=self.month_bar(self.render)
        self.search=self.field(row,'검색어','',width=18)
        self.search_cat=self.field(row,'분류','전체',['전체','식료품','교통','외식','생활','용돈','기타'],12)
        ttk.Button(row,text='검색',command=self.refresh_ledger_table).pack(side='left',anchor='s')
        s=monthly(self.repo.state,self.month)
        self.cards([('수입',money(s['income'])),('지출',money(s['expense'])),('잔액',money(s['balance'])),('식료품 잔여 예산',money(s['remaining']))])
        self.ledger_tree=self.tree(['날짜','구분','분류','금액','메모 / 구매처'],[115,70,95,130,550],6)
        self.refresh_ledger_table()
        row=self.row();self.ledger_vars={}
        for key,label,val,choices,width in [('date','날짜',date.today().isoformat(),None,12),('kind','구분','지출',['지출','수입'],7),('category','분류','식료품',['식료품','교통','외식','생활','용돈','기타'],10),('amount','실제 금액 (원)','',None,14),('memo','메모','',None,35)]:
            self.ledger_vars[key]=self.field(row,label,val,choices,width)
        row=self.row()
        self.ledger_vars['store']=self.field(row,'구매처 (선택)','',width=22)
        self.ledger_vars['quantity']=self.field(row,'구매 수량','1',width=10)
        self.edit_id=None;self.purchase={};self.ledger_baseline=self.form_values(self.ledger_vars)
        row=self.row()
        ttk.Button(row,text='내역 저장',style='Primary.TButton',command=self.save_record).pack(side='left',padx=(0,8))
        ttk.Button(row,text='선택 수정',command=self.edit_record).pack(side='left',padx=4)
        ttk.Button(row,text='선택 삭제',command=self.delete_record).pack(side='left',padx=4)
        ttk.Button(row,text='입력 취소',command=self.cancel_record).pack(side='left',padx=4)
        ttk.Label(row,text='수정은 선택한 내역을 불러온 뒤 저장하세요.',style='Muted.TLabel').pack(side='right')

    def refresh_ledger_table(self):
        result=self.ledger.manageLedger({'action':'SEARCH','month':self.month,'keyword':self.search.get(),'category':'' if self.search_cat.get()=='전체' else self.search_cat.get()})
        if result['status']!='OK':self.result(result);return
        self.ledger_tree.delete(*self.ledger_tree.get_children())
        for r in result['records']:self.ledger_tree.insert('', 'end',iid=r['id'],values=(r['date'],r['kind'],r['category'],money(r['amount']),r['memo']+(' / '+r['store'] if r['store'] else '')))

    def save_record(self):
        req=self.form_values(self.ledger_vars);req.update(self.purchase);req.update(action='UPDATE' if self.edit_id else 'CREATE',recordId=self.edit_id,month=self.month)
        if self.result(self.ledger.manageLedger(req)):
            self.month=req['date'][:7];self.render()

    def edit_record(self):
        rid=self.selected(self.ledger_tree)
        if not rid:return
        r=next(r for r in self.repo.state.records if r['id']==rid);self.edit_id=rid
        for key,var in self.ledger_vars.items():var.set(r[key])
        self.purchase={'product_id':r['product_id']}
        self.ledger_baseline=None;self.status.set('수정 중 · 내역 저장을 누르면 반영됩니다.')

    def cancel_record(self):
        self.render();self.status.set('입력을 취소했습니다. 저장 내용은 유지됩니다.')

    def delete_record(self):
        rid=self.selected(self.ledger_tree)
        if rid and messagebox.askyesno('내역 삭제','선택한 내역을 삭제할까요?',parent=self):
            if self.result(self.ledger.manageLedger({'action':'DELETE','recordId':rid,'confirmed':True})):self.render()

    def product_labels(self,favorites=False):
        return {f'{p["name"]} [{pid[:8]}]':pid for pid,p in self.repo.state.products.items() if not favorites or pid in self.repo.state.favorites}

    def draw_budget(self):
        self.heading('이번 달 장보기 계획','현재 유효한 가격으로 예상합니다. 가격을 확인할 수 없으면 전체 예상액은 미확정으로 표시합니다.')
        row=self.month_bar(self.render)
        self.budget_var=self.field(row,'월 식료품 예산 (원)',str(self.repo.state.budgets.get(self.month,'')),width=18)
        ttk.Button(row,text='예산 저장',command=self.save_budget).pack(side='left',anchor='s')
        result=self.budget.manageBudgetPlan({'action':'GET','month':self.month,**({'draftPlan':self.draft_plan} if self.draft_plan is not None else {})})
        if result['status']!='OK':self.result(result);return
        self.draft_plan=result['draftPlan'];self.plan_result=result
        self.cards([('남은 식료품 예산',money(result['summary']['remaining'])),('계획 예상액',money(result['totalEstimate'])),('계획 반영 후 잔액',money(result['projectedBalance'])),('초과 예상액',money(result['excess']))])
        self.plan_tree=self.tree(['상품','수량','마트 / 경로','개당 가격','예상 금액'],[290,65,240,150,150],6)
        for item in result['items']:self.plan_tree.insert('','end',iid=item['itemId'],values=(item['name'],item['quantity'],(item['store'] or '최저가')+' / '+item['channel'],money(item['price']),money(item['estimate'])))
        row=self.row();self.plan_labels=self.product_labels()
        self.plan_vars={'product':self.field(row,'상품',next(iter(self.plan_labels),''),list(self.plan_labels),30),'quantity':self.field(row,'수량','1',width=6),
            'store':self.field(row,'마트','최저가',['최저가',*STORES],12),'channel':self.field(row,'경로','온라인',['온라인','오프라인'],10),'condition':self.field(row,'할인 조건','일반',width=13)}
        self.plan_edit_id=None
        row=self.row()
        for label,command,style in [('계획에 추가 / 반영',self.upsert_plan,'TButton'),('선택 수정',self.edit_plan,'TButton'),('선택 제거',self.remove_plan,'TButton'),('계획 저장',self.save_plan,'Primary.TButton'),('변경 취소',self.cancel_plan,'TButton')]:ttk.Button(row,text=label,command=command,style=style).pack(side='left',padx=(0,7))
        ttk.Label(self.content,text=f'가격 미확인 {result["unknownCount"]}건 · 유효 가격 소계 {money(result["validSubtotal"])} · '+('저장하지 않은 변경 있음' if self.plan_dirty else '저장된 계획'),style='Muted.TLabel').pack(anchor='w',pady=5)

    def save_budget(self):
        result=self.budget.manageBudgetPlan({'action':'SET_BUDGET','month':self.month,'budget':self.budget_var.get(),'draftPlan':self.draft_plan})
        if self.result(result):self.render()

    def upsert_plan(self):
        vals=self.form_values(self.plan_vars)
        result=self.budget.manageBudgetPlan({'action':'UPSERT_ITEM','month':self.month,'draftPlan':self.draft_plan,'itemId':self.plan_edit_id,
            'product_id':self.plan_labels.get(vals['product']), 'quantity':vals['quantity'],'store':'' if vals['store']=='최저가' else vals['store'],'channel':vals['channel'],'condition':vals['condition']})
        if self.result(result,'계획에 반영했습니다. 계획 저장을 눌러 확정하세요.'):
            self.draft_plan=result['draftPlan'];self.plan_dirty=True;self.render()

    def edit_plan(self):
        iid=self.selected(self.plan_tree)
        if not iid:return
        item=next(x for x in self.draft_plan if x['itemId']==iid);self.plan_edit_id=iid
        for key in ('quantity','channel','condition'):self.plan_vars[key].set(item[key])
        self.plan_vars['store'].set(item['store'] or '최저가');self.plan_vars['product'].set(next(k for k,v in self.plan_labels.items() if v==item['product_id']))

    def remove_plan(self):
        iid=self.selected(self.plan_tree)
        if iid:
            result=self.budget.manageBudgetPlan({'action':'REMOVE_ITEM','month':self.month,'draftPlan':self.draft_plan,'itemId':iid})
            if self.result(result,'계획 저장 전 변경입니다.'):
                self.draft_plan=result['draftPlan'];self.plan_dirty=True;self.render()

    def save_plan(self):
        if self.result(self.budget.manageBudgetPlan({'action':'SAVE_PLAN','month':self.month,'draftPlan':self.draft_plan})):
            self.plan_dirty=False;self.render()

    def cancel_plan(self):self.draft_plan=None;self.plan_dirty=False;self.render()

    def draw_products(self):
        self.heading('상품과 가격을 확인하세요','가격·행사·출처를 함께 확인합니다. 베타는 직접 입력과 JSON 피드를 지원하며 마트 웹 자동 수집은 준비 중입니다.')
        row=self.row()
        self.product_filters={k:self.field(row,l,'전체',['전체',*vals],w) for k,l,vals,w in [('category','품목',CATEGORIES,10),('store','마트',STORES,12),('channel','경로',['온라인','오프라인'],11)]}
        ttk.Button(row,text='필터 적용',command=self.refresh_products_table).pack(side='left',anchor='s',padx=4)
        ttk.Button(row,text='가격 직접 입력',style='Primary.TButton',command=self.manual_dialog).pack(side='right',anchor='s')
        self.product_tree=self.tree(['관심','상품','마트','가격','단위 가격','상태','확인 날짜'],[50,240,100,100,140,120,120],10)
        self.refresh_products_table();self.product_tree.bind('<<TreeviewSelect>>',lambda e:self.product_detail())
        self.detail=tk.StringVar(value='상품을 선택하면 행사 기간·출처·지점·할인 조건이 표시됩니다.')
        ttk.Label(self.content,textvariable=self.detail,wraplength=1080,style='Muted.TLabel').pack(anchor='w',pady=5)
        row=self.row()
        for label,command in [('즐겨찾기',self.favorite),('출처 열기',self.open_source),('구매 기록',self.record_purchase),('계획에 추가',self.plan_purchase),('가격 비교',self.go_compare),('피드 갱신',self.refresh_feeds),('피드 설정',self.feed_settings)]:ttk.Button(row,text=label,command=command).pack(side='left',padx=(0,7))
        source_parts=[]
        for store,source in self.repo.state.sources.items():source_parts.append(f'{store}: '+('갱신 실패' if source.get('failed') else '성공')+' / '+source.get('last_success','없음')[:16].replace('T',' '))
        ttk.Label(self.content,text='최근 갱신  '+('  ·  '.join(source_parts) or '아직 없음'),style='Muted.TLabel').pack(anchor='w',pady=8)

    def refresh_products_table(self):
        result=self.products.manageProducts({'action':'QUERY',**{k:('' if v.get()=='전체' else v.get()) for k,v in self.product_filters.items()}})
        if result['status']!='OK':self.result(result);return
        self.product_rows={r['id']:r for r in result['offers']};self.product_tree.delete(*self.product_tree.get_children())
        for r in result['offers']:
            self.product_tree.insert('','end',iid=r['id'],values=('★' if r['favorite'] else '',r['product']['name'],r['store'],money(Decimal(r['price'])),f'{unit_money(r["unit_price"])} / {unit_text(r["product"])}',r['validity'],r['checked_date']))

    def product_detail(self):
        ids=self.product_tree.selection()
        if not ids:return
        r=self.product_rows[ids[0]]
        self.detail.set(f'{r["channel"]} · {r.get("branch", "전체")} · {r.get("condition", "일반")} · 행사 {r.get("start") or "시작일 없음"} ~ {r.get("end") or "종료일 없음"}\n출처 {r["url"]} · {r.get("method", "")} · 확인 {r.get("checked_at") or "시각 없음"}')

    def selected_offer(self):
        oid=self.selected(self.product_tree);return self.product_rows.get(oid)

    def favorite(self):
        r=self.selected_offer()
        if r and self.result(self.products.manageProducts({'action':'SET_FAVORITE','product_id':r['product_id'],'isFavorite':not r['favorite']})):self.refresh_products_table()

    def open_source(self):
        r=self.selected_offer()
        if r:self.result(self.products.manageProducts({'action':'OPEN_LINK','url':r['url']}),'출처 페이지를 열었습니다.')

    def record_purchase(self):
        r=self.selected_offer()
        if not r:return
        self.navigate(0);self.ledger_vars['memo'].set(r['product']['name']);self.ledger_vars['amount'].set(r['price'])
        self.ledger_vars['store'].set(r['store']);self.ledger_vars['quantity'].set('1')
        self.purchase={'product_id':r['product_id']}
        self.status.set('구매 기록 · 실제 결제 금액으로 고친 뒤 내역 저장을 누르세요.')

    def plan_purchase(self):
        r=self.selected_offer()
        if not r:return
        result=self.budget.manageBudgetPlan({'action':'UPSERT_ITEM','month':self.month,'product_id':r['product_id'],'quantity':1,'store':r['store'],'channel':r['channel'],'condition':r.get('condition','일반')})
        if self.result(result,'계획 초안에 추가했습니다. 계획 저장을 눌러 확정하세요.'):
            self.active=1;self.draft_plan=result['draftPlan'];self.plan_dirty=True;self.render()

    def go_compare(self):
        r=self.selected_offer()
        if r:self.preselect_product=r['product_id'];self.navigate(3)

    def refresh_feeds(self,automatic=False):
        result=self.products.manageProducts({'action':'AUTO_REFRESH' if automatic else 'REFRESH'})
        target=result.get('target_result') or {};notifications=target.get('notifications',[])
        if notifications:self.notification_text='\n'.join(n['name']+' · '+', '.join(o['store']+' '+unit_money(o['unit_price']) for o in n['matches']) for n in notifications)+' · 오늘 알림 완료'
        if result['status']=='OK':self.status.set('피드 확인 완료'+(' · '+self.notification_text if notifications else ''))
        elif result['status'] in ('PARTIAL_FAILURE','REFRESH_FAILED'):
            self.status.set('일부/전체 피드 확인 실패 · 기존 값은 보존하며 현재 계산에서 제외합니다.')
            if not automatic:messagebox.showwarning('피드 확인', '\n'.join(k+': '+v for k,v in result['failures'].items()),parent=self)
        else:self.result(result)
        if not automatic:self.render()

    def auto_refresh(self):
        # Only configured feeds; keep failed file intact and avoid interrupting edits.
        if not self.repo.read_error and self.adapter.paths:self.refresh_feeds(True)
        self.after(60000,self.auto_refresh)

    def feed_settings(self):
        dialog=tk.Toplevel(self);dialog.title('JSON 피드 설정');dialog.geometry('720x360');dialog.transient(self);dialog.grab_set()
        ttk.Label(dialog,text='마트별 JSON 파일을 연결합니다. 확인 시각은 파일 값을 그대로 사용합니다.',padding=20).pack(anchor='w')
        variables={}
        for store in STORES:
            row=ttk.Frame(dialog,padding=(20,5));row.pack(fill='x')
            ttk.Label(row,text=store,width=10).pack(side='left');var=tk.StringVar(value=self.adapter.paths.get(store,''));variables[store]=var
            ttk.Entry(row,textvariable=var,width=55).pack(side='left',padx=6)
            def choose(v=var):
                path=filedialog.askopenfilename(parent=dialog,filetypes=[('JSON','*.json')])
                if path:v.set(path)
            ttk.Button(row,text='찾기',command=choose).pack(side='left')
        def save():
            paths={k:v.get() for k,v in variables.items() if v.get()}
            try:
                if not self.demo:
                    path=self.data_dir/'feeds.json';path.parent.mkdir(parents=True,exist_ok=True)
                    temp=path.with_suffix('.tmp');temp.write_text(json.dumps(paths,ensure_ascii=False,indent=2),encoding='utf-8');temp.replace(path)
                self.adapter.paths=paths;dialog.destroy();self.status.set('피드 경로 저장 완료 · 피드 갱신을 눌러 읽어오세요.')
            except OSError as exc:messagebox.showerror('설정 저장 실패',str(exc),parent=dialog)
        ttk.Button(dialog,text='설정 저장',command=save,style='Primary.TButton').pack(pady=20)
        ttk.Label(dialog,text='시연 모드의 경로 변경은 현재 실행에만 적용됩니다.',style='Muted.TLabel').pack()

    def manual_dialog(self):
        dialog=tk.Toplevel(self);dialog.title('가격 직접 입력');dialog.geometry('920x660');dialog.transient(self);dialog.grab_set()
        body=ttk.Frame(dialog,padding=22);body.pack(fill='both',expand=True)
        ttk.Label(body,text='상품 규격과 확인한 가격을 입력하세요',style='Title.TLabel').pack(anchor='w',pady=(0,12))
        labels=self.product_labels();row=self.row(body)
        choice=self.field(row,'기존 상품 또는 새 상품','새 상품',['새 상품',*labels],45)
        variables={};product_fields=[('name','상품명','',None,24),('category','품목','우유',CATEGORIES,8),('brand','브랜드','',None,15),('spec','비교 규격','기본형',None,14),('size','1개 용량/개수','1000',None,12),('unit','단위','ml',['ml','개','봉','g'],7),('pack_count','묶음 수','1',None,8)]
        for begin in (0,4):
            row=self.row(body)
            for key,label,value,choices,width in product_fields[begin:begin+4]:variables[key]=self.field(row,label,value,choices,width)
        def load_existing(*_):
            if choice.get() in labels:
                p=self.repo.state.products[labels[choice.get()]]
                for key,_,__,___,____ in product_fields:variables[key].set(p[key])
        choice.trace_add('write',load_existing)
        offer_fields=[('store','마트',STORES[0],STORES,11),('channel','경로','온라인',['온라인','오프라인'],10),('branch','지점','전체',None,18),('condition','할인 조건','일반',None,18),('price','표시 가격 (원)','',None,14),('checked_date','확인 날짜',date.today().isoformat(),None,13),('checked_at','확인 시각 (시간대 포함)',now_local().replace(microsecond=0).isoformat(),None,29),('start','행사 시작일 (선택)','',None,14),('end','행사 종료일 (선택)','',None,14),('url','출처 URL','https://',None,60)]
        for start,end in ((0,4),(4,7),(7,9),(9,10)):
            row=self.row(body)
            for key,label,value,choices,width in offer_fields[start:end]:variables[key]=self.field(row,label,value,choices,width)
        ttk.Label(body,text='시각을 모르면 비워 두세요. 해당 가격은 현재 비교·예산·알림 계산에서 제외됩니다.\n기존 상품의 규격을 바꾸려면 새 상품으로 등록하세요. 날짜 형식: YYYY-MM-DD',style='Muted.TLabel').pack(anchor='w',pady=12)
        def save():
            values=self.form_values(variables);p={k:values[k] for k,_,__,___,____ in product_fields}
            if choice.get() in labels:p['id']=labels[choice.get()]
            o={k:values[k] for k,_,__,___,____ in offer_fields}
            result=self.products.manageProducts({'action':'UPSERT_MANUAL','product':p,'offer':o})
            if self.result(result):
                notes=(result.get('target_result') or {}).get('notifications',[])
                if notes:self.notification_text='목표 도달: '+', '.join(n['name'] for n in notes)
                dialog.destroy();self.render()
        ttk.Button(body,text='가격 저장',style='Primary.TButton',command=save).pack(anchor='e')

    def draw_prices(self):
        self.heading('같은 조건으로 비교하세요','브랜드·규격·판매 경로·할인 조건을 맞춥니다. 현재 비교와 과거 이력 통계는 구분합니다.')
        row=self.row();self.price_labels=self.product_labels();chosen=getattr(self,'preselect_product',None)
        label=next((k for k,v in self.price_labels.items() if v==chosen),next(iter(self.price_labels),''))
        self.price_vars={'product':self.field(row,'상품',label,list(self.price_labels),32),'channel':self.field(row,'경로','온라인',['온라인','오프라인'],10),'condition':self.field(row,'할인 조건','일반',width=14)}
        ttk.Button(row,text='단위 가격 비교',style='Primary.TButton',command=self.compare).pack(side='left',anchor='s')
        row=self.row()
        self.price_vars.update(store=self.field(row,'이력 조회 마트',STORES[0],STORES,12),branch=self.field(row,'지점','시연 지점' if self.demo else '전체',width=14),start=self.field(row,'시작일',(date.today()-timedelta(days=28)).isoformat(),width=14),end=self.field(row,'종료일',date.today().isoformat(),width=14))
        ttk.Button(row,text='가격 이력 조회',command=self.history).pack(side='left',anchor='s')
        self.price_info=tk.StringVar(value='상품을 선택해 조회하세요.')
        ttk.Label(self.content,textvariable=self.price_info,font=('맑은 고딕',12,'bold')).pack(anchor='w',pady=12)
        self.price_tree=self.tree(['순위 / 날짜','상품 / 마트','표시 가격','단위 가격','상태 / 확인 시각'],[130,300,140,150,280],9)
        ttk.Label(self.content,text='우유 100ml · 계란 1개 · 라면 1봉 · 쌀/두부 100g | 확인 후 24시간 이상·갱신 실패·행사 만료 가격은 현재 비교에서 제외',style='Muted.TLabel').pack(anchor='w',pady=8)
        if self.price_labels:self.compare()

    def price_request(self,mode):
        vals=self.form_values(self.price_vars);return dict(vals,product_id=self.price_labels.get(vals['product']),mode=mode)

    def compare(self):
        result=self.prices.analyzePrices(self.price_request('COMPARE'))
        if result['status'] not in ('OK','NO_DATA'):self.result(result);return
        self.price_tree.delete(*self.price_tree.get_children())
        for idx,r in enumerate(result.get('ranked',[])):
            self.price_tree.insert('','end',values=(idx+1,r['name']+' / '+r['store'],money(Decimal(r['price'])),unit_money(r['unit_price']),r['validity']))
        for r in result.get('excluded',[]):self.price_tree.insert('','end',values=('제외',r['name']+' / '+r['store'],money(Decimal(r['price'])),unit_money(r['unit_price']),r['validity']))
        self.price_info.set(f'현재 유효 {len(result.get("ranked",[]))}건 · 제외 {len(result.get("excluded",[]))}건 · 단위 가격 오름차순')

    def history(self):
        result=self.prices.analyzePrices(self.price_request('HISTORY'))
        if result['status'] not in ('OK','NO_DATA'):self.result(result);return
        self.price_tree.delete(*self.price_tree.get_children())
        for r in result.get('rows',[]):self.price_tree.insert('','end',values=(r['date'],self.price_vars['store'].get(),'—',unit_money(r['unit_price']),'해당 날짜 마지막 정상 관측'))
        self.price_info.set(f'관측 {result.get("count",0)}일 · 단위 가격 최저 {unit_money(result.get("min"))} / 최고 {unit_money(result.get("max"))} / 평균 {unit_money(result.get("mean"))}')

    def draw_targets(self):
        self.heading('구매 목표와 알림','직전 4주 중 관측일 4일 이상일 때 날짜별 평균의 90%를 계산합니다. 직접 설정한 목표가가 우선합니다.')
        result=self.targets.managePurchaseTargets({'action':'VIEW','product_ids':self.repo.state.favorites})
        if result['status']!='OK':self.result(result);return
        self.target_rows={r['product_id']:r for r in result['items']}
        self.target_tree=self.tree(['상품','관측일','프로그램 기준','개인 목표','적용 가격','현재 충족','알림 / 날짜'],[220,65,145,140,140,90,145],8)
        for r in result['items']:
            self.target_tree.insert('','end',iid=r['product_id'],values=(r['name'],f'{r["days"]}일',unit_money(r['basis']),unit_money(Decimal(r['target']['price'])) if r['target']['price'] else '미설정',unit_money(r['applied']),f'{len(r["matches"])}개 마트',('ON / ' if r['target']['enabled'] else 'OFF / ')+(r['last_alert'] or '없음')))
        self.target_tree.bind('<<TreeviewSelect>>',lambda e:self.select_target())
        row=self.row()
        self.target_price=self.field(row,'개인 목표 (단위 가격 / 빈칸은 프로그램 기준)','',width=40)
        self.target_enabled=tk.BooleanVar(value=False);ttk.Checkbutton(row,text='목표 알림 사용',variable=self.target_enabled).pack(side='left',anchor='s',padx=10,pady=8)
        ttk.Button(row,text='목표 저장',style='Primary.TButton',command=self.save_target).pack(side='left',anchor='s')
        ttk.Button(row,text='개인 목표 해제',command=lambda:self.save_target(True)).pack(side='left',anchor='s',padx=7)
        ttk.Label(self.content,text='목표 단위: 우유 100ml / 계란 1개 / 라면 1봉 / 쌀·두부 100g\n현재 화면의 목표 조건은 온라인 · 일반입니다. 알림은 앱이 실행 중일 때 상품당 하루 한 번 표시합니다.',style='Muted.TLabel').pack(anchor='w',pady=14)
        tk.Label(self.content,text=self.notification_text,bg='#e1f0e7',fg=GREEN,anchor='w',padx=18,pady=18,font=('맑은 고딕',11,'bold'),wraplength=1000).pack(fill='x',pady=6)
        ttk.Label(self.content,text='즐겨찾기는 상품·가격 화면에서 등록합니다. 갱신 실패 또는 오래된 가격은 알림 판단에서 제외합니다.',style='Muted.TLabel').pack(anchor='w',pady=8)

    def select_target(self):
        ids=self.target_tree.selection()
        if ids:
            t=self.target_rows[ids[0]]['target'];self.target_price.set(t['price'] or '');self.target_enabled.set(t['enabled'])

    def save_target(self,clear=False):
        pid=self.selected(self.target_tree)
        if not pid:return
        result=self.targets.managePurchaseTargets({'action':'CLEAR_TARGET' if clear else 'SET_TARGET','product_id':pid,'price':self.target_price.get(),'enabled':self.target_enabled.get(),'channel':'온라인','condition':'일반'})
        if self.result(result):self.render()

    def show_api(self):
        from .manual import API_TEXT
        dialog=tk.Toplevel(self);dialog.title('Smart Grocery Ledger · API Manual 0.4.0');dialog.geometry('1060x780')
        dialog.configure(bg=INK)
        tk.Label(dialog,text='API Manual  /  v0.4.0-beta',bg=INK,fg='white',font=('Segoe UI',22,'bold'),padx=25,pady=18).pack(anchor='w')
        tk.Label(dialog,text='로컬 Python 서비스 API · 입력 → 반환 → 동작',bg=INK,fg='#a9d7c5',font=('맑은 고딕',11),padx=26).pack(anchor='w',pady=(0,15))
        frame=ttk.Frame(dialog);frame.pack(fill='both',expand=True,padx=22,pady=(0,20))
        text=tk.Text(frame,font=('맑은 고딕',11),bg='white',fg=INK,wrap='word',padx=22,pady=20,spacing1=3,spacing3=5,relief='flat')
        bar=ttk.Scrollbar(frame,command=text.yview);text.configure(yscrollcommand=bar.set);text.pack(side='left',fill='both',expand=True);bar.pack(side='right',fill='y')
        text.insert('1.0',API_TEXT);text.configure(state='disabled')
