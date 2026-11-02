"""Local, single-user application services. Python 3.10+, standard library only.

Public service methods accept request dictionaries and return result dictionaries.
Prices use Decimal; JSON stores decimal values as strings. No retail scraping is
claimed: MartAdapter consumes a configured JSON feed exported by a supported source.
"""
from __future__ import annotations

import copy
import json
import os
import tempfile
import uuid
import webbrowser
from dataclasses import dataclass, field, asdict
from datetime import date, datetime, timedelta
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import urlparse

STORES = ('이마트', '홈플러스', '코스트코')
CATEGORIES = ('우유', '계란', '라면', '쌀', '두부')
UNITS = {'우유': ('ml', Decimal(100)), '계란': ('개', Decimal(1)),
         '라면': ('봉', Decimal(1)), '쌀': ('g', Decimal(100)), '두부': ('g', Decimal(100))}


def now_local():
    return datetime.now().astimezone()


def number(value, *, integer=False, zero=False):
    try:
        n = Decimal(str(value))
    except (InvalidOperation, ValueError):
        raise ValueError('숫자를 입력하세요.')
    if not n.is_finite() or n < 0 or (not zero and n == 0):
        raise ValueError('0 이상 값을 입력하세요.' if zero else '0보다 큰 값을 입력하세요.')
    if integer and n != n.to_integral_value():
        raise ValueError('정수를 입력하세요.')
    return int(n) if integer else n


def month_key(value):
    value = str(value)
    if len(value) != 7 or date.fromisoformat(value + '-01').strftime('%Y-%m') != value:
        raise ValueError('월 형식은 YYYY-MM입니다.')
    return value


def valid_url(value):
    parts = urlparse(str(value))
    if parts.scheme not in ('http', 'https') or not parts.hostname or parts.username:
        raise ValueError('http 또는 https 출처 주소를 입력하세요.')
    return str(value)


@dataclass
class AppState:
    """Persisted records, products, offers, settings and deduplication state."""
    version: int = 1
    records: list = field(default_factory=list)
    budgets: dict = field(default_factory=dict)
    plans: dict = field(default_factory=dict)
    products: dict = field(default_factory=dict)
    offers: list = field(default_factory=list)
    favorites: list = field(default_factory=list)
    targets: dict = field(default_factory=dict)
    alerts: dict = field(default_factory=dict)
    sources: dict = field(default_factory=dict)
    auto_attempt: str = ''
    demo: bool = False


class Repository:
    """Load a JSON state and atomically replace it; failure never commits memory."""
    def __init__(self, path):
        self.path = Path(path)
        self.state = AppState()
        self.read_error = ''
        self.loadState()

    def loadState(self):
        try:
            if not self.path.exists():
                self.state = AppState()
                self.read_error = ''
                return {'status': 'OK', 'state': self.state}
            payload = json.loads(self.path.read_text(encoding='utf-8'))
            state = AppState(**payload)
            if state.version != 1:
                raise ValueError('지원하지 않는 데이터 버전입니다.')
            for name in ('records', 'offers', 'favorites'):
                if not isinstance(getattr(state, name), list):
                    raise ValueError(f'{name} 목록 형식이 잘못됐습니다.')
            for name in ('budgets','plans','products','targets','alerts','sources'):
                if not isinstance(getattr(state, name), dict):
                    raise ValueError(f'{name} 객체 형식이 잘못됐습니다.')
            # Validate values consumed by services before allowing any write.
            for r in state.records:
                date.fromisoformat(r['date']); number(r['amount'], integer=True)
                if not isinstance(r['amount'],int) or any(not isinstance(r[k],str) for k in ('memo','store','product_id')):
                    raise ValueError('내역의 금액 또는 설명 자료형 오류')
                number(r['quantity'],integer=True)
                if r['kind'] not in ('수입','지출') or not r['category'] or not r['id']:
                    raise ValueError('내역 형식이 잘못됐습니다.')
            for key, value in state.budgets.items():
                month_key(key); number(value, integer=True, zero=True)
                if not isinstance(value,int):raise ValueError('예산은 정수로 저장해야 합니다.')
            for pid,p in state.products.items():
                if not isinstance(p,dict) or p.get('id')!=pid:raise ValueError('상품 ID 형식 오류')
                validate_product(p)
            for o in state.offers:
                validate_offer(o, state.products[o['product_id']])
            for month,plan in state.plans.items():
                month_key(month)
                if not isinstance(plan,list):raise ValueError('계획 목록 형식 오류')
                for item in plan:
                    number(item['quantity'], integer=True)
                    if item['product_id'] not in state.products:
                        raise ValueError('계획의 상품을 찾을 수 없습니다.')
            for target in state.targets.values():
                if target['price'] is not None: number(target['price'])
                if not isinstance(target['enabled'],bool):raise ValueError('알림 설정 형식 오류')
            for day in state.alerts.values():date.fromisoformat(day)
            if any(pid not in state.products for pid in state.favorites):raise ValueError('즐겨찾기 상품 ID 오류')
            if state.auto_attempt:date.fromisoformat(state.auto_attempt)
            if not isinstance(state.demo,bool):raise ValueError('데이터 모드 형식 오류')
            self.state, self.read_error = state, ''
            return {'status': 'OK', 'state': self.state}
        except (OSError, ValueError, TypeError, KeyError, AttributeError, InvalidOperation) as exc:
            self.read_error = str(exc)
            return {'status': 'READ_FAILED', 'message': self.read_error, 'path': str(self.path)}

    def saveAtomic(self, candidate):
        if self.read_error:
            return {'status': 'SAVE_FAILED', 'message': '읽기 오류가 있는 파일은 덮어쓰지 않습니다. 정상 파일을 선택하세요.'}
        temporary = None
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix=self.path.stem + '-', suffix='.tmp', dir=self.path.parent)
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(asdict(candidate), stream, ensure_ascii=False, indent=2)
                stream.flush(); os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            self.state = copy.deepcopy(candidate)
            return {'status': 'OK'}
        except (OSError, ValueError, TypeError) as exc:
            return {'status': 'SAVE_FAILED', 'message': str(exc)}
        finally:
            if temporary and os.path.exists(temporary):
                try: os.unlink(temporary)
                except OSError: pass


def validate_product(p):
    if p['category'] not in CATEGORIES or not str(p['name']).strip() or not str(p['brand']).strip():
        raise ValueError('품목·상품명·브랜드를 확인하세요.')
    if p['unit'] != UNITS[p['category']][0]:
        raise ValueError('우유 ml, 계란 개, 라면 봉, 쌀·두부 g 단위를 사용하세요.')
    number(p['size']); number(p['pack_count'], integer=True)
    if not str(p['spec']).strip():raise ValueError('비교 규격을 입력하세요.')


def validate_offer(o, p):
    validate_product(p)
    if o['store'] not in STORES or o['channel'] not in ('온라인','오프라인'):
        raise ValueError('마트 또는 판매 경로를 확인하세요.')
    number(o['price']); valid_url(o['url'])
    day = date.fromisoformat(o['checked_date'])
    if o.get('checked_at'):
        stamp = datetime.fromisoformat(o['checked_at'])
        if stamp.tzinfo is None or stamp.date() != day:
            raise ValueError('확인 시각에 시간대를 포함하고 확인 날짜와 맞춰 주세요.')
    start, end = o.get('start',''), o.get('end','')
    if start:date.fromisoformat(start)
    if end:date.fromisoformat(end)
    if start and end and start > end:raise ValueError('행사 종료일이 시작일보다 빠릅니다.')


def normalizeUnitPrice(offer, product):
    """Return exact Decimal price per 100 ml, 100 g, one egg or ramen bag."""
    validate_product(product)
    return number(offer['price']) / (number(product['size']) * number(product['pack_count'])) * UNITS[product['category']][1]


def price_state(offer, now):
    if offer.get('failed'):return '갱신 실패'
    if not offer.get('checked_at'):return '확인 시각 없음'
    stamp = datetime.fromisoformat(offer['checked_at'])
    age = now - stamp
    if age.total_seconds() < 0:return '미래 확인 시각'
    if age >= timedelta(hours=24):return '오래됨'
    today = now.date().isoformat()
    if offer.get('start') and today < offer['start']:return '행사 시작 전'
    if offer.get('end') and today > offer['end']:return '만료'
    return '현재 유효'


def offer_key(o):
    return (o['product_id'],o['store'],o['channel'],o.get('branch',''),o.get('condition','일반'))


def historical_normal(o):
    """Ignore present refresh failures; the event must apply on observation day."""
    day=o['checked_date']
    return (not o.get('start') or o['start']<=day) and (not o.get('end') or day<=o['end'])


def latest_offers(state):
    result = {}
    for o in state.offers:
        key = offer_key(o)
        if key not in result or (o.get('checked_at') or o['checked_date']) >= (result[key].get('checked_at') or result[key]['checked_date']):
            result[key] = o
    return list(result.values())


def monthly(state, month):
    rows = [r for r in state.records if r['date'].startswith(month)]
    income = sum(r['amount'] for r in rows if r['kind']=='수입')
    expense = sum(r['amount'] for r in rows if r['kind']=='지출')
    food = sum(r['amount'] for r in rows if r['kind']=='지출' and r['category']=='식료품')
    budget = state.budgets.get(month)
    categories = {}
    for r in rows:
        if r['kind']=='지출':categories[r['category']] = categories.get(r['category'],0) + r['amount']
    return {'income':income,'expense':expense,'balance':income-expense,'food':food,
            'budget':budget,'remaining':None if budget is None else budget-food,'categories':categories}


def service_method(function):
    def invoke(self, req):
        if self.repo.read_error:return {'status':'READ_FAILED','message':self.repo.read_error}
        try:return function(self, req)
        except (ValueError, KeyError, TypeError, IndexError, AttributeError, InvalidOperation) as exc:
            return {'status':'INVALID_INPUT','message':str(exc),'draft':req}
    invoke.__name__, invoke.__doc__ = function.__name__, function.__doc__
    return invoke


class LedgerService:
    def __init__(self, repo):self.repo=repo

    @service_method
    def manageLedger(self, req):
        """CREATE/UPDATE/DELETE/SEARCH. Actual payments only; commit before totals."""
        action=req['action']; state=copy.deepcopy(self.repo.state)
        if req.get('cancelled'):return {'status':'CANCELLED'}
        month=month_key(req.get('month',now_local().strftime('%Y-%m')))
        if action=='SEARCH':
            rows=[r for r in state.records if r['date'].startswith(month)]
            if req.get('category'):rows=[r for r in rows if r['category']==req['category']]
            if req.get('keyword'):rows=[r for r in rows if req['keyword'].casefold() in json.dumps(r,ensure_ascii=False).casefold()]
            return {'status':'OK','records':sorted(rows,key=lambda r:r['date'],reverse=True),'summary':monthly(state,month)}
        old=next((r for r in state.records if r['id']==req.get('recordId')),None)
        if action in ('UPDATE','DELETE') and old is None:return {'status':'NOT_FOUND'}
        if action=='DELETE':
            if not req.get('confirmed'):return {'status':'CANCELLED'}
            state.records.remove(old); affected={old['date'][:7]}
        elif action in ('CREATE','UPDATE'):
            day=date.fromisoformat(req['date']).isoformat()
            if req['kind'] not in ('수입','지출') or not str(req['category']).strip():raise ValueError('구분·분류를 확인하세요.')
            row={'id':old['id'] if old else uuid.uuid4().hex,'date':day,'kind':req['kind'],
                 'category':str(req['category']).strip(),'amount':number(req['amount'],integer=True),
                 'memo':req.get('memo',''),'store':req.get('store',''),'product_id':req.get('product_id',''),
                 'quantity':number(req.get('quantity',1),integer=True)}
            if row['product_id']:
                if row['product_id'] not in state.products:raise ValueError('상품을 찾을 수 없습니다.')
                row['kind'],row['category']='지출','식료품'
            if old:state.records.remove(old)
            state.records.append(row); affected={day[:7]} | ({old['date'][:7]} if old else set())
        else:raise ValueError('지원하지 않는 내역 action입니다.')
        saved=self.repo.saveAtomic(state)
        if saved['status']!='OK':return dict(saved,draft=req)
        return {'status':'OK','months':{m:monthly(state,m) for m in affected}}


class BudgetService:
    def __init__(self,repo,clock=now_local):self.repo,self.clock=repo,clock

    @service_method
    def manageBudgetPlan(self,req):
        """GET/SET_BUDGET/UPSERT_ITEM/REMOVE_ITEM/SAVE_PLAN; edits stay in draft."""
        month=month_key(req['month']); action=req['action']; state=copy.deepcopy(self.repo.state)
        draft=copy.deepcopy(req.get('draftPlan',state.plans.get(month,[])))
        if action=='UPSERT_ITEM':
            pid=req['product_id']
            if pid not in state.products:raise ValueError('상품을 선택하세요.')
            item={'itemId':req.get('itemId') or uuid.uuid4().hex,'product_id':pid,
                  'quantity':number(req['quantity'],integer=True),'store':req.get('store',''),
                  'channel':req.get('channel','온라인'),'condition':req.get('condition','일반')}
            draft=[x for x in draft if x['itemId']!=item['itemId']]+[item]
        elif action=='REMOVE_ITEM':draft=[x for x in draft if x['itemId']!=req['itemId']]
        elif action=='SET_BUDGET':state.budgets[month]=number(req['budget'],integer=True,zero=True)
        elif action not in ('GET','SAVE_PLAN'):raise ValueError('지원하지 않는 계획 action입니다.')
        for item in draft:
            number(item['quantity'],integer=True)
            if item['product_id'] not in state.products:raise ValueError('계획 상품을 찾을 수 없습니다.')
        if action=='SAVE_PLAN':state.plans[month]=draft
        if action in ('SET_BUDGET','SAVE_PLAN'):
            saved=self.repo.saveAtomic(state)
            if saved['status']!='OK':return dict(saved,draftPlan=draft)
        subtotal=Decimal(0); unknown=0; items=[]; now=self.clock()
        for item in draft:
            choices=[o for o in latest_offers(state) if o['product_id']==item['product_id'] and price_state(o,now)=='현재 유효'
                     and (not item.get('store') or o['store']==item['store']) and o['channel']==item.get('channel','온라인')
                     and o.get('condition','일반')==item.get('condition','일반')]
            price=min((number(o['price']) for o in choices),default=None)
            cost=None if price is None else price*item['quantity']
            if cost is None:unknown+=1
            else:subtotal+=cost
            items.append(dict(item,name=state.products[item['product_id']]['name'],price=price,estimate=cost))
        summary=monthly(state,month); total=None if unknown else subtotal
        balance=None if total is None or summary['remaining'] is None else Decimal(summary['remaining'])-total
        return {'status':'OK','summary':summary,'draftPlan':draft,'items':items,'validSubtotal':subtotal,
                'unknownCount':unknown,'totalEstimate':total,'projectedBalance':balance,
                'excess':None if balance is None else max(Decimal(0),-balance)}


class MartAdapter:
    """Read a configured, UTF-8 JSON source snapshot (products + offers).

    A feed carries its real observation dates; refresh never fabricates freshness.
    Retailer HTML crawling is deliberately not included in beta 0.4.0.
    """
    def __init__(self,paths=None):self.paths=paths or {}

    def fetchOffers(self,store):
        if store not in self.paths:raise ValueError('지원 피드가 설정되지 않았습니다. 직접 입력 또는 JSON 피드를 사용하세요.')
        path=Path(self.paths[store])
        if path.stat().st_size > 5_000_000:raise ValueError('피드 파일은 5MB 이하로 준비하세요.')
        data=json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(data.get('products'),list) or not isinstance(data.get('offers'),list):raise ValueError('products와 offers 목록이 필요합니다.')
        return data


class ProductService:
    def __init__(self,repo,adapter=None,clock=now_local):
        self.repo,self.adapter,self.clock=repo,adapter or MartAdapter(),clock
        self.on_price_updated=None

    def _import(self,state,product,offer,method):
        p=copy.deepcopy(product); p['id']=p.get('id') or uuid.uuid4().hex
        p['size']=str(number(p['size']));p['pack_count']=number(p['pack_count'],integer=True);validate_product(p)
        if p['id'] in state.products and state.products[p['id']]!=p:
            raise ValueError('기존 상품의 규격은 변경할 수 없습니다. 새 상품 ID로 등록하세요.')
        o=copy.deepcopy(offer);o.update(product_id=p['id'],method=method,failed=False)
        o['price']=str(number(o['price']));o.setdefault('condition','일반');o.setdefault('branch','전체')
        o.setdefault('checked_at','');o.setdefault('start','');o.setdefault('end','')
        validate_offer(o,p)
        if date.fromisoformat(o['checked_date'])>self.clock().date():raise ValueError('미래 확인일을 입력할 수 없습니다.')
        if o.get('checked_at') and datetime.fromisoformat(o['checked_at'])>self.clock():raise ValueError('미래 확인 시각을 입력할 수 없습니다.')
        o['id']=uuid.uuid4().hex
        duplicate=next((x for x in state.offers if offer_key(x)==offer_key(o) and x.get('checked_at')==o.get('checked_at') and x['checked_date']==o['checked_date']),None)
        if duplicate:state.offers.remove(duplicate)
        state.products[p['id']]=p;state.offers.append(o)
        return p['id'] if price_state(o,self.clock())=='현재 유효' else None

    @service_method
    def manageProducts(self,req):
        """QUERY/REFRESH/AUTO_REFRESH/UPSERT_MANUAL/SET_FAVORITE/OPEN_LINK."""
        action=req['action'];state=copy.deepcopy(self.repo.state);new=[];failures={};successes=[];now=self.clock()
        if action=='QUERY':
            rows=[]
            for o in latest_offers(state):
                p=state.products[o['product_id']]
                if any(req.get(k) and v!=req[k] for k,v in [('category',p['category']),('store',o['store']),('channel',o['channel'])]):continue
                rows.append(dict(o,product=p,unit_price=normalizeUnitPrice(o,p),validity=price_state(o,now),favorite=p['id'] in state.favorites))
            return {'status':'OK','offers':rows,'sources':state.sources,'products':state.products}
        if action=='OPEN_LINK':
            url=valid_url(req['url'])
            return {'status':'OK' if webbrowser.open(url) else 'LINK_FAILED','url':url}
        if action=='SET_FAVORITE':
            pid=req['product_id'];enabled=req['isFavorite']
            if pid not in state.products or not isinstance(enabled,bool):raise ValueError('상품·등록 여부를 확인하세요.')
            state.favorites=[x for x in state.favorites if x!=pid]+([pid] if enabled else [])
        elif action=='UPSERT_MANUAL':
            pid=self._import(state,req['product'],req['offer'],'직접 입력')
            if pid:new.append(pid)
        elif action in ('REFRESH','AUTO_REFRESH'):
            if action=='AUTO_REFRESH':
                if state.auto_attempt==now.date().isoformat():return {'status':'OK','skipped':True}
                state.auto_attempt=now.date().isoformat()
            stores=[req['store']] if req.get('store') else list(STORES)
            for store in stores:
                try:
                    data=self.adapter.fetchOffers(store);by_id={p['id']:p for p in data['products']}
                    candidate=copy.deepcopy(state);ids=[]
                    for offer in data['offers']:
                        if offer['store']!=store:continue
                        pid=self._import(candidate,by_id[offer['product_id']],offer,'JSON 피드')
                        if pid:ids.append(pid)
                    state=candidate;new+=ids;successes.append(store)
                    state.sources[store]={'last_success':now.isoformat(),'failed':False,'error':''}
                except (OSError,ValueError,KeyError,TypeError) as exc:
                    failures[store]=str(exc)
                    status=state.sources.setdefault(store,{});status.update(failed=True,error=str(exc))
                    # Current exclusion is separate from historical normal observations.
                    for offer in state.offers:
                        if offer['store']==store:offer['failed']=True
        else:raise ValueError('지원하지 않는 상품 action입니다.')
        saved=self.repo.saveAtomic(state)
        if saved['status']!='OK':return dict(saved,draft=req)
        target_result=None
        if new and self.on_price_updated:target_result=self.on_price_updated(sorted(set(new)))
        status='PARTIAL_FAILURE' if failures and successes else 'REFRESH_FAILED' if failures else 'OK'
        return {'status':status,'failures':failures,'updated':new,'target_result':target_result}


def comparison_group(p):
    return (p['category'],p['brand'],p['spec'],p['unit'],p['size'])


class PriceService:
    def __init__(self,repo,clock=now_local):self.repo,self.clock=repo,clock

    @service_method
    def analyzePrices(self,req):
        """COMPARE/HISTORY with matching product, channel and discount conditions."""
        state=self.repo.state;p=state.products[req['product_id']];mode=req['mode'];channel=req.get('channel','온라인');condition=req.get('condition','일반')
        if mode=='COMPARE':
            ranked=[];excluded=[]
            for o in latest_offers(state):
                op=state.products[o['product_id']]
                if comparison_group(op)!=comparison_group(p) or o['channel']!=channel or o.get('condition','일반')!=condition:continue
                row=dict(o,name=op['name'],unit_price=normalizeUnitPrice(o,op),validity=price_state(o,self.clock()))
                (ranked if row['validity']=='현재 유효' else excluded).append(row)
            return {'status':'OK' if ranked else 'NO_DATA','ranked':sorted(ranked,key=lambda o:o['unit_price']),'excluded':excluded}
        if mode!='HISTORY':raise ValueError('조회 모드를 확인하세요.')
        start=date.fromisoformat(req['start']);end=date.fromisoformat(req['end'])
        if start>end:raise ValueError('조회 시작일이 종료일보다 늦습니다.')
        daily={}
        for o in state.offers:
            if not historical_normal(o):continue
            if o['product_id']!=p['id'] or o['store']!=req['store'] or o['channel']!=channel or o.get('branch','전체')!=req.get('branch','전체') or o.get('condition','일반')!=condition:continue
            day=o['checked_date']
            if not start<=date.fromisoformat(day)<=end:continue
            if day not in daily or (o.get('checked_at') or day)>=(daily[day].get('checked_at') or day):daily[day]=o
        rows=[{'date':day,'unit_price':normalizeUnitPrice(o,p)} for day,o in sorted(daily.items())]
        values=[r['unit_price'] for r in rows]
        return {'status':'OK' if rows else 'NO_DATA','rows':rows,'count':len(rows),
                'min':min(values) if values else None,'max':max(values) if values else None,
                'mean':sum(values)/len(values) if values else None}


class TargetService:
    def __init__(self,repo,clock=now_local):self.repo,self.clock=repo,clock

    def basis(self,pid,channel='온라인',condition='일반'):
        state=self.repo.state;now=self.clock();today=now.date();begin=today-timedelta(days=28)
        p=state.products[pid];latest={}
        for o in state.offers:
            if not historical_normal(o):continue
            op=state.products[o['product_id']]
            if comparison_group(op)!=comparison_group(p) or o['channel']!=channel or o.get('condition','일반')!=condition:continue
            if not begin<=date.fromisoformat(o['checked_date'])<today:continue
            key=(o['checked_date'],o['store'],o.get('branch','전체'))
            if key not in latest or (o.get('checked_at') or o['checked_date']) >= (latest[key].get('checked_at') or latest[key]['checked_date']):latest[key]=o
        daily={}
        for (day,_,__),o in latest.items():daily.setdefault(day,[]).append(normalizeUnitPrice(o,state.products[o['product_id']]))
        means=[sum(v)/len(v) for v in daily.values()]
        mean=sum(means)/len(means) if means else None
        return {'start':begin.isoformat(),'end':(today-timedelta(days=1)).isoformat(),'days':len(means),'mean':mean,'basis':mean*Decimal('.9') if len(means)>=4 else None}

    @service_method
    def managePurchaseTargets(self,req):
        """VIEW/SET_TARGET/CLEAR_TARGET/EVALUATE; persist daily guard before display."""
        action=req['action'];state=copy.deepcopy(self.repo.state);today=self.clock().date().isoformat()
        if action not in ('VIEW','SET_TARGET','CLEAR_TARGET','EVALUATE'):raise ValueError('목표 action을 확인하세요.')
        ids=req['product_ids'] if 'product_ids' in req else [req['product_id']]
        if action in ('SET_TARGET','CLEAR_TARGET'):
            pid=ids[0]
            if pid not in state.favorites:raise ValueError('즐겨찾기 상품을 먼저 등록하세요.')
            price=req.get('price') if action=='SET_TARGET' else None
            if not isinstance(req['enabled'],bool):raise ValueError('알림 여부를 확인하세요.')
            state.targets[pid]={'price':str(number(price)) if price not in ('',None) else None,
                                'enabled':req['enabled'],'channel':req.get('channel','온라인'),'condition':req.get('condition','일반')}
            saved=self.repo.saveAtomic(state)
            if saved['status']!='OK':return saved
        items=[];notifications=[]
        for pid in ids:
            if pid not in state.favorites:continue
            target=state.targets.get(pid,{'price':None,'enabled':False,'channel':'온라인','condition':'일반'})
            calc=self.basis(pid,target.get('channel','온라인'),target.get('condition','일반'))
            threshold=number(target['price']) if target.get('price') else calc['basis']
            offers=[o for o in latest_offers(state) if o['product_id']==pid and o['channel']==target.get('channel','온라인') and o.get('condition','일반')==target.get('condition','일반') and price_state(o,self.clock())=='현재 유효']
            matches=[dict(o,unit_price=normalizeUnitPrice(o,state.products[pid])) for o in offers if threshold is not None and normalizeUnitPrice(o,state.products[pid])<=threshold]
            status='NOT_EVALUATED'
            if action=='EVALUATE':
                status=('DISABLED' if not target['enabled'] else 'NO_BASIS' if threshold is None else
                        'ALREADY_SENT' if state.alerts.get(pid)==today else 'NOT_REACHED' if not matches else 'SENT')
                if status=='SENT':
                    state.alerts[pid]=today
                    notifications.append({'product_id':pid,'name':state.products[pid]['name'],'matches':matches})
            items.append(dict(calc,product_id=pid,name=state.products[pid]['name'],target=target,applied=threshold,
                              matches=matches,notificationStatus=status,last_alert=state.alerts.get(pid)))
        if notifications:
            saved=self.repo.saveAtomic(state)
            if saved['status']!='OK':
                for item in items:
                    if item['notificationStatus']=='SENT':item['notificationStatus']='SAVE_FAILED'
                return dict(saved,items=items,notifications=[])
        return {'status':'OK','items':items,'notifications':notifications}
