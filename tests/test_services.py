import copy
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from unittest.mock import patch
from grocery.core import *
from grocery.demo import make_demo

NOW=datetime(2026,11,2,12,0,tzinfo=timezone(timedelta(hours=9)))


class ServicesTest(unittest.TestCase):
    def setUp(self):
        test_root=Path(__file__).resolve().parent.parent/'userdata'/'test-temp';test_root.mkdir(parents=True,exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=test_root);self.path=Path(self.temp.name)/'ledger.json'
        self.repo=Repository(self.path);saved=self.repo.saveAtomic(make_demo(lambda:NOW))
        self.assertEqual(saved['status'],'OK',saved)
        self.ledger=LedgerService(self.repo);self.budget=BudgetService(self.repo,lambda:NOW)
        self.prices=PriceService(self.repo,lambda:NOW);self.targets=TargetService(self.repo,lambda:NOW)
        self.products=ProductService(self.repo,clock=lambda:NOW)

    def tearDown(self):self.temp.cleanup()

    def request(self,**kwargs):return dict(action='CREATE',date='2026-11-02',kind='지출',category='식료품',amount=2500,**kwargs)
    def target(self,**kwargs):return self.targets.managePurchaseTargets(dict(action='EVALUATE',product_ids=['milk'],**kwargs))
    def current(self,pid='milk'):return copy.deepcopy(next(o for o in self.repo.state.offers if o['product_id']==pid and o['checked_date']=='2026-11-02'))
    def test_actual_payment_updates_month(self):
        old=monthly(self.repo.state,'2026-11')['food'];r=self.ledger.manageLedger(self.request())
        self.assertEqual(r['months']['2026-11']['food'],old+2500)
        self.assertEqual(Repository(self.path).state.records[-1]['amount'],2500)
    def test_date_move_recomputes_both_months(self):
        r=self.ledger.manageLedger(dict(self.request(),action='UPDATE',recordId='demo-1',date='2026-10-31'))
        self.assertEqual(set(r['months']),{'2026-10','2026-11'});self.assertEqual(r['months']['2026-10']['food'],2500)
    def test_cancel_delete_and_not_found(self):
        before=copy.deepcopy(self.repo.state)
        self.assertEqual(self.ledger.manageLedger({'action':'DELETE','recordId':'demo-1','confirmed':False})['status'],'CANCELLED')
        self.assertEqual(self.repo.state,before)
        self.assertEqual(self.ledger.manageLedger({'action':'DELETE','recordId':'missing','confirmed':True})['status'],'NOT_FOUND')
    def test_delete_commits(self):
        self.assertEqual(self.ledger.manageLedger({'action':'DELETE','recordId':'demo-1','confirmed':True})['status'],'OK')
        self.assertNotIn('demo-1',[r['id'] for r in Repository(self.path).state.records])
    def test_invalid_amounts_do_not_write(self):
        before=self.path.read_bytes()
        for amount in ('NaN','Infinity',-1,0,'1.5','abc'):
            with self.subTest(amount=amount):self.assertEqual(self.ledger.manageLedger(dict(self.request(),amount=amount))['status'],'INVALID_INPUT')
        self.assertEqual(self.path.read_bytes(),before)
    def test_atomic_replace_failure_preserves_file_memory_draft(self):
        before=self.path.read_bytes();state=copy.deepcopy(self.repo.state)
        with patch('grocery.core.os.replace',side_effect=OSError('disk failure')):
            result=self.ledger.manageLedger(self.request())
        self.assertEqual(result['status'],'SAVE_FAILED');self.assertEqual(result['draft']['amount'],2500)
        self.assertEqual(self.repo.state,state);self.assertEqual(self.path.read_bytes(),before)
    def test_corrupt_file_never_overwritten(self):
        self.path.write_text('{broken',encoding='utf-8');repo=Repository(self.path)
        self.assertEqual(LedgerService(repo).manageLedger(self.request())['status'],'READ_FAILED')
        self.assertEqual(repo.saveAtomic(AppState())['status'],'SAVE_FAILED');self.assertEqual(self.path.read_text(),'{broken')
    def test_malformed_nested_file_fails_closed(self):
        data=asdict(self.repo.state);data['products']['milk']=None;self.path.write_text(json.dumps(data),encoding='utf-8')
        self.assertTrue(Repository(self.path).read_error)
    def test_search_filters_do_not_change_month_summary(self):
        r=self.ledger.manageLedger({'action':'SEARCH','month':'2026-11','category':'식료품','keyword':'우유'})
        self.assertEqual(len(r['records']),1);self.assertEqual(r['summary']['income'],600000)
    def test_plan_draft_does_not_create_expense_or_save(self):
        before=self.path.read_bytes()
        r=self.budget.manageBudgetPlan({'action':'UPSERT_ITEM','month':'2026-11','draftPlan':[],'product_id':'milk','quantity':2})
        self.assertEqual(r['totalEstimate'],5200);self.assertEqual(self.path.read_bytes(),before)
        self.assertEqual(self.budget.manageBudgetPlan({'action':'SAVE_PLAN','month':'2026-11','draftPlan':r['draftPlan']})['status'],'OK')
        self.assertEqual(len(self.repo.state.plans['2026-11']),1)
    def test_missing_price_is_not_zero(self):
        for o in self.repo.state.offers:
            if o['product_id']=='milk':o['failed']=True
        r=self.budget.manageBudgetPlan({'action':'GET','month':'2026-11'})
        self.assertEqual(r['unknownCount'],1);self.assertIsNone(r['totalEstimate']);self.assertIsNone(r['excess'])
    def test_unset_zero_and_exceeded_budget(self):
        self.repo.state.budgets={}
        r=self.budget.manageBudgetPlan({'action':'GET','month':'2026-11'});self.assertIsNone(r['projectedBalance'])
        r=self.budget.manageBudgetPlan({'action':'SET_BUDGET','month':'2026-11','budget':0})
        self.assertGreater(r['excess'],0);self.assertEqual(self.repo.state.budgets['2026-11'],0)
    def test_plan_quantity_validation(self):
        for qty in (0,-1,'1.2'):
            self.assertEqual(self.budget.manageBudgetPlan({'action':'UPSERT_ITEM','month':'2026-11','product_id':'milk','quantity':qty})['status'],'INVALID_INPUT')
    def test_unit_normalization_all_categories(self):
        expected={'milk':Decimal('260'),'eggs':Decimal('400'),'ramen':Decimal('720'),'rice':Decimal('390'),'tofu':Decimal(1600)/3}
        for pid,want in expected.items():
            with self.subTest(pid=pid):self.assertEqual(normalizeUnitPrice(self.current(pid),self.repo.state.products[pid]),want)
    def test_stale_boundary_and_no_timestamp(self):
        offer=self.current();offer['checked_at']=(NOW-timedelta(hours=24)).isoformat()
        self.assertEqual(price_state(offer,NOW),'오래됨')
        offer['checked_at']='';self.assertEqual(price_state(offer,NOW),'확인 시각 없음')
    def test_compare_excludes_failed_and_expired(self):
        for o in self.repo.state.offers:
            if o['store']=='이마트':o['failed']=True
            elif o['store']=='홈플러스':o['end']='2026-11-01'
        r=self.prices.analyzePrices({'mode':'COMPARE','product_id':'milk'})
        self.assertEqual(len(r['ranked']),1);self.assertEqual(len(r['excluded']),2)
    def test_compare_separates_conditions(self):
        for o in self.repo.state.offers:o['condition']='회원'
        r=self.prices.analyzePrices({'mode':'COMPARE','product_id':'milk','condition':'일반'})
        self.assertEqual(r['status'],'NO_DATA')
    def test_history_missing_and_reversed_dates(self):
        req={'mode':'HISTORY','product_id':'milk','store':'이마트','branch':'시연 지점','start':'2027-01-01','end':'2027-01-02'}
        r=self.prices.analyzePrices(req);self.assertEqual(r['status'],'NO_DATA');self.assertIsNone(r['mean'])
        self.assertEqual(self.prices.analyzePrices(dict(req,start='2027-02-01'))['status'],'INVALID_INPUT')
    def test_history_one_last_observation_per_day(self):
        offer=self.current();offer['checked_at']=NOW.isoformat();offer['price']='3000';self.repo.state.offers.append(offer)
        r=self.prices.analyzePrices({'mode':'HISTORY','product_id':'milk','store':'이마트','branch':'시연 지점','start':'2026-11-02','end':'2026-11-02'})
        self.assertEqual(r['count'],1);self.assertEqual(r['mean'],300)
    def test_four_week_basis_excludes_today(self):
        before=self.targets.basis('milk');self.assertEqual(before['days'],8)
        for o in self.repo.state.offers:
            if o['checked_date']=='2026-11-02':o['price']='999999'
        self.assertEqual(self.targets.basis('milk'),before)
    def test_basis_minimum_observation_days(self):
        self.repo.state.offers=[o for o in self.repo.state.offers if o['checked_date']>='2026-10-25']
        self.assertEqual(self.targets.basis('milk')['days'],3);self.assertIsNone(self.targets.basis('milk')['basis'])
    def test_basis_equal_day_weight(self):
        offers=[]
        for day,prices in [(1,[1000]),(2,[2000,4000,6000]),(3,[1000]),(4,[1000])]:
            for idx,price in enumerate(prices):
                o=self.current();o.update(store=STORES[idx],price=str(price),checked_date=(NOW-timedelta(days=day)).date().isoformat(),checked_at=(NOW-timedelta(days=day)).isoformat());offers.append(o)
        self.repo.state.offers=offers
        self.assertEqual(self.targets.basis('milk')['basis'],Decimal('157.5'))
    def test_personal_target_priority(self):
        self.repo.state.offers=[o for o in self.repo.state.offers if o['checked_date']=='2026-11-02']
        r=self.target();self.assertEqual(r['items'][0]['applied'],270);self.assertEqual(r['items'][0]['notificationStatus'],'SENT')
    def test_notification_once_per_day_across_restart(self):
        self.assertEqual(self.target()['items'][0]['notificationStatus'],'SENT')
        restarted=TargetService(Repository(self.path),lambda:NOW)
        r=restarted.managePurchaseTargets({'action':'EVALUATE','product_id':'milk'})
        self.assertEqual(r['items'][0]['notificationStatus'],'ALREADY_SENT');self.assertEqual(r['notifications'],[])
    def test_notification_not_emitted_on_save_failure(self):
        with patch.object(self.repo,'saveAtomic',return_value={'status':'SAVE_FAILED','message':'test'}):r=self.target()
        self.assertEqual(r['notifications'],[]);self.assertEqual(r['items'][0]['notificationStatus'],'SAVE_FAILED');self.assertNotIn('milk',self.repo.state.alerts)
    def test_view_and_empty_favorites_do_not_notify(self):
        r=self.targets.managePurchaseTargets({'action':'VIEW','product_ids':['milk']});self.assertEqual(r['notifications'],[])
        self.assertEqual(self.targets.managePurchaseTargets({'action':'VIEW','product_ids':[]})['items'],[])
    def test_manual_future_timestamp_and_invalid_url(self):
        offer=self.current();offer['checked_at']=(NOW+timedelta(minutes=1)).isoformat()
        req={'action':'UPSERT_MANUAL','product':self.repo.state.products['milk'],'offer':offer}
        self.assertEqual(self.products.manageProducts(req)['status'],'INVALID_INPUT')
        offer['checked_at']=NOW.isoformat();offer['url']='file:///etc/passwd'
        self.assertEqual(self.products.manageProducts(req)['status'],'INVALID_INPUT')
    def test_manual_date_only_preserved_but_not_current(self):
        offer=self.current();offer['checked_at']='';offer['price']='1234'
        r=self.products.manageProducts({'action':'UPSERT_MANUAL','product':self.repo.state.products['milk'],'offer':offer})
        self.assertEqual(r['status'],'OK');self.assertEqual(r['updated'],[])
    def test_partial_refresh_and_last_success_preserved(self):
        old=self.repo.state.sources['홈플러스']['last_success']
        class Adapter:
            def fetchOffers(inner,store):
                if store!='이마트':raise OSError('offline')
                return {'products':[self.repo.state.products['milk']],'offers':[self.current()]}
        self.products.adapter=Adapter();r=self.products.manageProducts({'action':'REFRESH'})
        self.assertEqual(r['status'],'PARTIAL_FAILURE');self.assertEqual(self.repo.state.sources['홈플러스']['last_success'],old)
        self.assertTrue(all(o['failed'] for o in self.repo.state.offers if o['store']=='홈플러스'))
    def test_automatic_refresh_guard_survives_restart(self):
        self.products.manageProducts({'action':'AUTO_REFRESH'})
        p=ProductService(Repository(self.path),clock=lambda:NOW)
        self.assertTrue(p.manageProducts({'action':'AUTO_REFRESH'})['skipped'])
    def test_price_event_after_commit_only(self):
        received=[];self.products.on_price_updated=lambda ids:received.append((ids,len(self.repo.state.offers)))
        offer=self.current();offer['checked_at']=NOW.isoformat()
        req={'action':'UPSERT_MANUAL','product':self.repo.state.products['milk'],'offer':offer}
        with patch.object(self.repo,'saveAtomic',return_value={'status':'SAVE_FAILED'}):self.products.manageProducts(req)
        self.assertEqual(received,[])
        self.products.manageProducts(req);self.assertEqual(received[0][0],['milk'])


if __name__=='__main__':unittest.main()
