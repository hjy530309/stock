from fastapi import FastAPI, Depends
from pydantic import BaseModel
import re, requests, urllib.parse
from transformers import pipeline
from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker
from datetime import datetime
from fastapi.middleware.cors import CORSMiddleware
import os
import oracledb

try:
    import torch  # noqa: F401
    TORCH_AVAILABLE = True
except Exception:
    torch = None
    TORCH_AVAILABLE = False


# Runtime configuration is loaded from environment variables so local secrets do
# not need to be committed into the repository.
WALLET_DIR = os.getenv("SPENDING_ORACLE_WALLET_DIR", "").strip()
if WALLET_DIR:
    os.environ["TNS_ADMIN"] = WALLET_DIR
    oracledb.defaults.config_dir = WALLET_DIR

try:
    client_path = os.getenv("SPENDING_ORACLE_CLIENT_PATH", "").strip()
    if client_path:
        oracledb.init_oracle_client(lib_dir=client_path)
        print("Oracle thick mode enabled.")
except Exception as e:
    print(f"Oracle thick mode init failed: {e}")
    print("Continuing with python-oracledb thin mode.")
app = FastAPI(title="SpendingData Category Classification API")

# ------------------------------
# CORS ?ㅼ젙 (釉뚮씪?곗? ?듭떊 ?덉슜)
# ------------------------------
# ??遺遺??덉뼱??釉뚮씪?곗???OPTIONS ?붿껌???듦낵?쒗궡.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],     # 紐⑤뱺 ?꾨찓???덉슜
    allow_credentials=True,
    allow_methods=["*"],   # GET, POST, OPTIONS ??紐⑤뱺 硫붿꽌???덉슜
    allow_headers=["*"],   # 紐⑤뱺 ?ㅻ뜑 ?덉슜
)



# ------------------------------
# 紐⑤뜽 & 移댄뀒怨좊━ 珥덇린??
# ------------------------------
classifier = None
if TORCH_AVAILABLE:
    try:
        classifier = pipeline("zero-shot-classification", model="facebook/bart-large-mnli")
        print("Zero-shot classifier loaded: facebook/bart-large-mnli")
    except Exception as e:
        print(f"Zero-shot classifier unavailable, using fallback classification only: {e}")
else:
    print("PyTorch not available. Using rule/Naver fallback classification only.")

my_categories = [
    "援먯쑁", "援먰넻", "誘몄슜", "?앺솢", "?쇳븨", "?앸퉬",
    "?섎즺", "?ш?,痍⑤?,?덉닠", "移댄럹,媛꾩떇,?붿???, "?몄쓽?? 留덊듃", "移댄뀒怨좊━ ?놁쓬"
]

shop_rules = {
    '荑좏뙜': '?쇳븨',
    '諛곕떖?섎?議?: '?앸퉬',
    '?곗븘?쒗삎?쒕뱾': '?앸퉬',
    '諛곕?': '?앸퉬',
    '移댁뭅??: '?쇳븨',
    '?곕㉧??: '援먰넻, ?먮룞李?,
    'CGV' : '?ш?,痍⑤?,?덉닠',
    '而ㅽ뵾' : '移댄럹,媛꾩떇,?붿???,
    'CU' : '?몄쓽?? 留덊듃, ?≫솕',
    '?⑥쑀' : '?몄쓽?? 留덊듃, ?≫솕',
    'GS25' : '?몄쓽?? 留덊듃, ?≫솕',
    '?몃툙?쇰젅釉? : '?몄쓽?? 留덊듃, ?≫솕',
    '?대쭏??4' : '?몄쓽?? 留덊듃, ?≫솕',
    '?대쭏?? : '?몄쓽?? 留덊듃, ?≫솕',
    '援먰넻' : '援먰넻, ?먮룞李?
}

raw_cat_rules = {
    '?〓쪟,怨좉린?붾━': '?앸퉬',
    '?쒖떇': '?앸퉬',
    '?뚯떇?? : '?앸퉬',
    '?꾨Ц,湲곗닠?쒕퉬??: '?앺솢',
    '?⑥뀡' : '?쇳븨',
    '臾명솕,?덉닠' : '?ш?,痍⑤?,?덉닠',
    '?덇꼍?? : '?앺솢',
    '?ㅻ씫' : '?ш?,痍⑤?,?덉닠'
}

NAVER_CLIENT_ID = os.getenv("NAVER_CLIENT_ID", "").strip()
NAVER_CLIENT_SECRET = os.getenv("NAVER_CLIENT_SECRET", "").strip()


# ------------------------------
# Pydantic 紐⑤뜽 ?뺤쓽
# ------------------------------
class Transaction(BaseModel):  # ?뱀뿉??諛쏆쓣 JSON ?곗씠???뺤떇 ?뺤쓽
    vendor: str
    transaction_date: str   # "YY/MM/DD"
    amount: float
    user_id: int   # ?몚 異붽?


# ------------------------------
# Oracle DB ?곌껐
# ------------------------------

# ?먮컮??TNS_ADMIN=... 怨??숈씪???④낵瑜??낅땲??
# os.environ["TNS_ADMIN"] = "C:/oraclepw"

# ?섍꼍 蹂?섎? ?ㅼ젙?댁꽌 URL ?ㅼ쓽 臾쇱쓬???) 遺遺?吏?
DB_URL = os.getenv("SPENDING_DB_URL", "").strip()

engine = None
SessionLocal = None
if DB_URL:
    connect_args = {}
    if WALLET_DIR:
        connect_args["config_dir"] = WALLET_DIR
        connect_args["wallet_location"] = WALLET_DIR

    engine = create_engine(
        DB_URL,
        echo=True,
        connect_args=connect_args,
    )
    SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


# ------------------------------
# Helper ?⑥닔
# ------------------------------
# ?ㅼ씠踰?api濡?援щℓ泥섎? ?듯빐 ?ㅼ씠踰꾩젙??移댄뀒怨좊━ 諛쏆븘??
def get_naver_category(query: str):  # query: 援щℓ泥?
    if not NAVER_CLIENT_ID or not NAVER_CLIENT_SECRET:
        return "CATEGORY_UNAVAILABLE"
    clean_query = re.sub(r'(\s??|??|\(二?)|二쇱떇?뚯궗)', '', query)
    encText = urllib.parse.quote(clean_query)
    url = f"https://openapi.naver.com/v1/search/local.json?query={encText}&display=1"
    headers = {"X-Naver-Client-Id": NAVER_CLIENT_ID, "X-Naver-Client-Secret": NAVER_CLIENT_SECRET}
    try:
        response = requests.get(url, headers=headers)
        if response.status_code == 200:
            items = response.json().get('items')
            if items:
                return items[0]['category']
        return "移댄뀒怨좊━ ?놁쓬"
    except:
        return "移댄뀒怨좊━ ?놁쓬"

# 移댄뀒怨좊━ ?⑥닚??- ?ㅼ씠踰??뺤쓽 移댄뀒怨좊━???遺꾨쪟留?異붿텧
def simplify_category(naver_category: str):
    return naver_category.split('>')[0]

# 癒몄떊?щ떇 湲곕컲 遺꾨쪟 - zero-shot紐⑤뜽??媛???곹빀??my_categories?쇰꺼 ?좏깮(移댄뀒怨좊━ 醫낅쪟 異뺤냼)
def ml_classify(naver_cat: str):
    if naver_cat == "移댄뀒怨좊━ ?놁쓬":
        return "移댄뀒怨좊━ ?놁쓬"
    if classifier is None:
        return naver_cat
    try:
        result = classifier(naver_cat, my_categories)
        return result['labels'][0]
    except Exception as e:
        print(f"ml_classify fallback: {e}")
        return naver_cat


# 移댄뀒怨좊━ ?섎룞 蹂댁젙
def finalize_category(vendor: str, naver_raw: str, ml_cat: str):
    for key, val in shop_rules.items():
        if key in vendor:
            return val
    for key, val in raw_cat_rules.items():
        if key in naver_raw:
            return val
    return ml_cat

# 遺꾨쪟 理쒖쥌 ?ㅽ뻾
def classify_transaction(transaction: dict):
    vendor = transaction['vendor']
    naver_raw = get_naver_category(vendor)
    simple_cat = simplify_category(naver_raw)
    ml_cat = ml_classify(simple_cat)
    final_cat = finalize_category(vendor, naver_raw, ml_cat)
    transaction['naver_raw'] = naver_raw
    transaction['category'] = final_cat

    # month ?먮룞 怨꾩궛
    tx_date = datetime.strptime(transaction['transaction_date'], "%y/%m/%d")
    transaction['month'] = tx_date.month
    transaction['year'] = tx_date.year  # ?몚 ?닿굅 異붽?

    return transaction




# ------------------------------
# DB ????⑥닔
# ------------------------------

def save_transaction_to_db(tx: dict):
    if SessionLocal is None:
        print("DB connection is not configured. Set SPENDING_DB_URL first.")
        return False

    session = SessionLocal()
    try:
        tx_date = datetime.strptime(tx['transaction_date'], "%y/%m/%d").date()
        session.execute(
            text("""
                INSERT INTO SPENDING_DATA
                    (SPEND_ID, USER_ID, MONTH, YEAR, TRANSACTION_DATE, AMOUNT, VENDOR, CATEGORY)
                VALUES (SEQ_SPENDING_ID.NEXTVAL, :user_id, :month, :year, :transaction_date, :amount, :vendor, :category)
            """),
            {
                "user_id": tx['user_id'],
                "month": tx['month'],
                "year": tx['year'],
                "transaction_date": tx_date,
                "amount": tx['amount'],
                "vendor": tx['vendor'],
                "category": tx.get('category')
            }
        )
        session.commit()
        return True
    except Exception as e:
        session.rollback()
        print("DB ????ㅻ쪟:", e)
        return False
    finally:
        session.close()


# ------------------------------
# API Endpoint
# ------------------------------
@app.post("/classify_transaction")
def classify(transaction: Transaction):
    tx_dict = transaction.model_dump()  # user_id??transaction ?덉뿉 ?ㅼ뼱?덉쓬

    # 遺꾨쪟
    result = classify_transaction(tx_dict)
    print(f"--- 遺꾨쪟 寃곌낵 ---")
    print(f"援щℓ泥? {result['vendor']}")
    print(f"移댄뀒怨좊━: {result['category']}")
    print(f"湲덉븸: {result['amount']}")

    # DB ???
    success = save_transaction_to_db(result)
    result['saved'] = success

    return {"result": result}
# /classify_transaction 寃쎈줈濡?POST ?붿껌 諛쏆쓬
# ?붿껌 JSON ??Transaction 紐⑤뜽 ??Python ?뺤뀛?덈━ ??遺꾨쪟 ?⑥닔 ?몄텧
# 理쒖쥌 寃곌낵 JSON?쇰줈 諛섑솚
# ?붾퉬 ???

# 肄붾뱶 留?留덉?留?以꾩뿉 異붽?
if __name__ == "__main__":
    import uvicorn
    # port??9000踰덉쑝濡??ㅼ젙 (?먮컮 ?ㅼ젙怨?留욎땄)
    uvicorn.run(app, host="127.0.0.1", port=8001)




