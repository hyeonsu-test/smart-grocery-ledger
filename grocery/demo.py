"""Fictional fixtures. Never substitute these observations for real retail data."""
import json
from datetime import timedelta
from .core import AppState, STORES, now_local


def make_demo(clock=now_local):
    now=clock(); today=now.date(); month=today.strftime('%Y-%m')
    state=AppState(demo=True)
    specs=[('milk','우유','데일리 우유 1L','1000','ml',1,2800),
           ('eggs','계란','신선 계란 10구','10','개',1,4200),
           ('ramen','라면','얼큰 라면 5봉','1','봉',5,3800),
           ('rice','쌀','고운 쌀 4kg','4000','g',1,15800),
           ('tofu','두부','담백 두부 300g','300','g',1,1800)]
    for pid,cat,name,size,unit,packs,base in specs:
        state.products[pid]={'id':pid,'category':cat,'name':name,'brand':'시연식품','spec':'기본형','size':size,'unit':unit,'pack_count':packs}
        for ago in (28,24,20,16,12,8,4,1,0):
            stamp=now-timedelta(days=ago,minutes=10)
            for idx,store in enumerate(STORES):
                price=base+idx*150+(ago%7)*40 if ago else base-200+idx*120
                state.offers.append({'id':f'{pid}-{ago}-{idx}','product_id':pid,'store':store,'branch':'시연 지점',
                    'channel':'온라인','price':str(price),'condition':'일반','url':'https://example.com/',
                    'checked_date':stamp.date().isoformat(),'checked_at':stamp.isoformat(),
                    'start':'','end':'','method':'시연 데이터','failed':False})
    for idx,(day,kind,cat,amount,memo) in enumerate([
        (1,'수입','용돈',600000,'11월 생활비'),(1,'지출','식료품',25800,'주간 장보기'),
        (1,'지출','교통',30000,'교통카드 충전'),(2,'지출','식료품',12300,'우유·계란·두부'),
        (2,'지출','외식',8500,'점심 식사')]):
        state.records.append({'id':f'demo-{idx}','date':today.replace(day=min(day,today.day)).isoformat(),
          'kind':kind,'category':cat,'amount':amount,'memo':memo,'store':'','product_id':'','quantity':1})
    state.budgets[month]=180000
    state.plans[month]=[{'itemId':p,'product_id':p,'quantity':q,'store':'','channel':'온라인','condition':'일반'} for p,q in [('milk',2),('eggs',1),('rice',1)]]
    state.favorites=['milk','eggs','tofu']
    state.targets={'milk':{'price':'270','enabled':True,'channel':'온라인','condition':'일반'}}
    state.sources={store:{'last_success':now.isoformat(),'failed':False,'error':''} for store in STORES}
    return state


def write_demo_feeds(directory,state):
    directory.mkdir(parents=True,exist_ok=True)
    paths={}
    newest={}
    for offer in state.offers:
        key=(offer['product_id'],offer['store'])
        if key not in newest or offer['checked_at']>newest[key]['checked_at']:newest[key]=offer
    for idx,store in enumerate(STORES):
        path=directory/f'demo-source-{idx+1}.json'
        data={'products':list(state.products.values()),'offers':[o for o in newest.values() if o['store']==store]}
        path.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8');paths[store]=str(path)
    return paths
