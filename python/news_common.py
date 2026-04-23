"""
news_common.py ??怨듯넻 ?대옒??紐⑥쓬
  - ModelManager     : BERT / TF-IDF 紐⑤뜽 ?깃???濡쒕뵫
  - ArticleProcessor : 蹂몃Ц 鍮꾨룞湲??섏쭛, ?띿뒪???뺤젣, BERT 異붾줎, ?꾪꽣留?
  - GroqAnalyzer     : 鍮꾨룞湲??붿빟 + 媛먯꽦 遺꾩꽍 (API ??蹂꾨룄 吏??媛??
  - DatabaseManager  : Oracle DB 利됱떆 ?④굔 ?쎌엯
  - run_pipeline_async: ?꾩껜 ?섏쭛 ?뚯씠?꾨씪??
"""

import os
import re
import json
import asyncio
import joblib
import threading
import aiohttp
import oracledb
import torch
import torch.nn as nn
import torch.nn.functional as F
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from bs4 import BeautifulSoup
from konlpy.tag import Okt
from datetime import datetime
from openai import AsyncOpenAI
from transformers import BertModel, BertTokenizerFast


# ================================================================
# [怨듯넻 ?꾪꽣 ?⑥뼱]
# ================================================================
EXCLUDE_WORDS = [
    "?ы삎"

]

TITLE_EXCLUDE_WORDS = [
    "?쒗솴"
]


# ================================================================
# [BERT 紐⑤뜽 援ъ“]
# ================================================================
class BertBaseModel(nn.Module):
    def __init__(self):
        super().__init__()
        self.bert = BertModel.from_pretrained("kykim/bert-kor-base")
        self.cls  = nn.Linear(768, 4)

    def forward(self, input_ids, attention_mask):
        return self.cls(
            self.bert(input_ids=input_ids, attention_mask=attention_mask)[1]
        )


# ================================================================
# [ModelManager ???깃???
# ================================================================
class ModelManager:
    """BERT + TF-IDF 紐⑤뜽????踰덈쭔 濡쒕뱶. ???뚯씠?꾨씪?몄씠 怨듭쑀."""

    _instance = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._instance._initialized = False
        return cls._instance

    def __init__(self):
        if self._initialized:
            return
        self._initialized = True
        self.device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
        print(f"?μ튂: {self.device}")
        self._load_logistic()
        self._load_bert()

    def _load_logistic(self):
        print("濡쒖??ㅽ떛 ?뚭? 紐⑤뜽 濡쒕뵫 以?..")
        try:
            self.lr_model      = joblib.load('clickbait_model.joblib')
            self.lr_vectorizer = joblib.load('tfidf_vectorizer.joblib')
            print("??濡쒖??ㅽ떛 ?뚭? 濡쒕뵫 ?꾨즺!")
        except Exception as e:
            print(f"??濡쒕뵫 ?ㅽ뙣: {e}")
            self.lr_model      = None
            self.lr_vectorizer = None

    def _load_bert(self):
        try:
            import __main__
            __main__.BertBaseModel = BertBaseModel  # pickle??__main__?먯꽌 李얠쑝誘濡?二쇱엯
            self.bert_model = torch.load(
                './model.pt', map_location=self.device, weights_only=False
            )
            self.bert_model.to(self.device)
            self.bert_model.eval()
            self.tokenizer = BertTokenizerFast.from_pretrained("kykim/bert-kor-base")
            print("??BERT 濡쒕뵫 ?꾨즺!")
        except Exception as e:
            print(f"??BERT 濡쒕뵫 ?ㅽ뙣: {e}")
            self.bert_model = None
            self.tokenizer  = None


# ================================================================
# [ArticleProcessor]
# ================================================================
class ArticleProcessor:
    """蹂몃Ц 鍮꾨룞湲??섏쭛 / ?띿뒪???뺤젣 / BERT 異붾줎 / ?꾪꽣留?"""

    def __init__(self, model_manager: ModelManager):
        self.mm        = model_manager
        self.okt       = Okt()
        self._okt_lock = threading.Lock()  # KoNLPy ?ㅻ젅??鍮꾩븞??????

    # ------ 鍮꾨룞湲?蹂몃Ц ?섏쭛 ------
    async def fetch_content(self, session: aiohttp.ClientSession, url: str) -> str | None:
        try:
            headers = {"User-Agent": "Mozilla/5.0"}
            timeout = aiohttp.ClientTimeout(total=5)
            async with session.get(url, headers=headers, timeout=timeout) as resp:
                if resp.status != 200:
                    return None
                html    = await resp.text()
                soup    = BeautifulSoup(html, "html.parser")
                content = soup.select_one("#dic_area, #newsct_article, #articeBody")
                if content:
                    text = content.get_text(" ", strip=True)
                    return text if text else None
                return None
        except Exception:
            return None

    # ------ ?띿뒪???뺤젣 + 泥?겕 遺꾨━ ------
    def clean_and_split(self, full_text: str, max_chars: int = 100) -> list:
        if not full_text:
            return []
        text = re.sub(r'[a-zA-Z0-9+-_.]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+', '', full_text)
        text = re.sub(r'짤.*|Copyright.*|臾대떒\s*?꾩옱.*|諛고룷\s*湲덉?.*', '', text)
        text = re.sub(r'\S+\s*湲곗옄', '', text)
        text = re.sub(r'https?://\S+', '', text)
        text = re.sub(r'\[.*?\]|\(.*??ъ쭊.*?\)|\(.*??쒓났.*?\)', '', text)
        text = re.sub(r'[ \t]+', ' ', text)
        text = re.sub(r'\n{2,}', '\n', text)
        text = text.strip()

        sentences_raw = re.split(r'(?<=[?ㅼ슂?뚯엫])\.\s+|\n', text)
        sentences     = [s.strip() for s in sentences_raw if len(s.strip()) > 20]
        if not sentences:
            return []

        chunks, current_chunk = [], ""
        for sentence in sentences:
            if len(current_chunk) + len(sentence) <= max_chars:
                current_chunk += (" " + sentence) if current_chunk else sentence
            else:
                if current_chunk:
                    chunks.append(current_chunk.strip())
                current_chunk = sentence
        if current_chunk:
            chunks.append(current_chunk.strip())
        return chunks

    # ------ BERT 湲곗궗 ?좏삎 遺꾨쪟 (?숆린 ??run_in_executor濡??몄텧) ------
    def predict_type(self, paragraphs: list) -> tuple[str, float]:
        mm = self.mm
        if not paragraphs or mm.bert_model is None:
            return "?????놁쓬", 0.0

        class_names         = ["?ъ떎??, "?덉륫??, "??뷀삎", "異붾줎??]
        predicted_labels    = []
        chunk_confidences   = []
        all_probs_per_chunk = []

        mm.bert_model.eval()
        with torch.no_grad():
            for p in paragraphs:
                inputs = mm.tokenizer(
                    p, return_tensors="pt",
                    max_length=128, truncation=True, padding="max_length"
                )
                input_ids      = inputs['input_ids'].to(mm.device)
                attention_mask = inputs['attention_mask'].to(mm.device)
                outputs        = mm.bert_model(input_ids, attention_mask)
                probs          = F.softmax(outputs, dim=1).squeeze().tolist()
                max_idx        = probs.index(max(probs))
                predicted_labels.append(class_names[max_idx])
                chunk_confidences.append(max(probs))
                all_probs_per_chunk.append(probs)

        label_counts = Counter(predicted_labels)
        top_count    = label_counts.most_common(1)[0][1]
        top_labels   = [l for l, c in label_counts.items() if c == top_count]

        if len(top_labels) == 1:
            final_label = top_labels[0]
        else:
            avg_probs   = [sum(col) / len(col) for col in zip(*all_probs_per_chunk)]
            final_label = class_names[avg_probs.index(max(avg_probs))]

        win_confs  = [c for l, c in zip(predicted_labels, chunk_confidences)
                      if l == final_label]

        if not win_confs:  # 留뚯빟 寃곌낵 由ъ뒪?멸? 鍮꾩뼱?덈떎硫?
            return final_label, 0.0

        vote_ratio = len(win_confs) / len(predicted_labels)
        final_prob = (sum(win_confs) / len(win_confs)) * vote_ratio * 100
        return final_label, round(final_prob, 1)

    # ------ 怨쇱옣???먯닔 ------
    def predict_clickbait(self, title: str, text: str) -> float:
        mm = self.mm
        if not mm.lr_model or not mm.lr_vectorizer:
            return 0.0
        vec   = mm.lr_vectorizer.transform([title + " " + text])
        probs = mm.lr_model.predict_proba(vec)[0]
        return round(probs[0] * 100, 1)

    # ------ ?뱁꽣 ?꾪꽣 (?숆린 ??run_in_executor濡??몄텧) ------
    def verify_sector(self, title: str, text: str, keywords: list) -> bool:
        """CrawlingNaverApi?? keywords ?⑥씪 由ъ뒪??""
        if not text:
            return False
        for bad in TITLE_EXCLUDE_WORDS:
            if bad in title:
                return False
        for bad in EXCLUDE_WORDS:
            if bad in title or bad in text:
                return False
        if any((kw in title) for kw in keywords):
            return True
        with self._okt_lock:
            nouns = self.okt.nouns(text)
        return sum(1 for n in nouns if n in keywords) >= 2

    def verify_company(
        self, title: str, text: str, core_keywords: list, sub_keywords: list
    ) -> bool:
        """CrawlingNaverTOP7?? core + sub keywords"""
        if not text:
            return False
        for bad in TITLE_EXCLUDE_WORDS:
            if bad in title:
                return False
        for bad in EXCLUDE_WORDS:
            if bad in title or bad in text:
                return False
        if not any(core in title for core in core_keywords):
            return False
        with self._okt_lock:
            nouns = self.okt.nouns(text)
        return sum(1 for n in nouns if n in sub_keywords) >= 1


# ================================================================
# [GroqAnalyzer ??鍮꾨룞湲?
# ================================================================
class GroqAnalyzer:
    def __init__(self, api_keys: str):
        # 臾몄옄??or 由ъ뒪??紐⑤몢 ?섏슜, ?쇱슫?쒕줈鍮덉쑝濡????쒗솚
        if api_keys is None:
            api_keys = [os.getenv("GROQ_API_KEY")]
        elif isinstance(api_keys, str):
            api_keys = [api_keys]
        self._clients = [
            AsyncOpenAI(base_url="https://api.groq.com/openai/v1", api_key=k, timeout=20.0)
            for k in api_keys if k
        ]
        self._idx = 0
        self._system_prompt = (
            "?덈뒗 二쇱떇 ?쒖옣 ?댁뒪 遺꾩꽍 ?꾨Ц媛??\n"
            "1. 諛섎뱶???좏슚??JSON ?뺤떇?쇰줈留??묐떟?쒕떎.\n"
            "2. 'sentiment'??[?몄옱, ?낆옱, 以묐┰] 以??섎굹濡쒕쭔 ?좏깮?쒕떎.\n"
            "   - ?몄옱: ?ㅼ쟻 ?곸듅, ?섏＜ ?깃났, ?좎젣???ν뻾, 紐⑺몴二쇨? ?곹뼢 ??n"
            "   - ?낆옱: ?ㅼ쟻 ?섎씫, ?뚯넚, 由ъ퐳, 洹쒖젣, 紐⑺몴二쇨? ?섑뼢 ??n"
            "   - 以묐┰: ?⑥닚 ?ъ떎 ?꾨떖, ?곹뼢 誘몃?, ?몄븙???쇱옱 ??n"
            "3. 'summary'??1臾몄옣?쇰줈 ?듭떖留??붿빟?쒕떎.\n"
            "4. ?ㅻ뒗 'summary'? 'sentiment'留??ъ슜?쒕떎."
        )

    async def analyze(self, title: str, full_text: str) -> dict:
        if not full_text:
            return {"summary": "蹂몃Ц ?놁쓬", "sentiment": "以묐┰"}
        # ?쇱슫?쒕줈鍮? ?붿껌留덈떎 ?ㅼ쓬 ???ъ슜
        client = self._clients[self._idx % len(self._clients)]
        self._idx += 1
        try:
            response = await client.chat.completions.create(
                model="llama-3.1-8b-instant",
                messages=[
                    {"role": "system", "content": self._system_prompt},
                    {"role": "user",
                     "content": f"?쒕ぉ: {title}\n蹂몃Ц: {full_text[:800]}"}
                ],
                temperature=0.5,
                response_format={"type": "json_object"},
                max_tokens=2048
            )
            result = json.loads(response.choices[0].message.content.strip())
            if result.get("sentiment") not in ["?몄옱", "?낆옱", "以묐┰"]:
                mapping = {"湲띿젙": "?몄옱", "遺??: "?낆옱"}
                result["sentiment"] = mapping.get(result.get("sentiment"), "以묐┰")
            return result
        except Exception as e:
            print(f"Groq ?ㅻ쪟 (??#{self._idx % len(self._clients)}): {e}")
            return {"summary": "?붿빟 ?ㅻ쪟", "sentiment": "以묐┰"}


# ================================================================
# [DatabaseManager ??利됱떆 ?④굔 ?쎌엯]
# ================================================================
class DatabaseManager:
    LIB_DIR = os.getenv("NEWS_ORACLE_CLIENT_PATH", "").strip()
    WALLET_PATH = os.getenv("NEWS_ORACLE_WALLET_PATH", "").strip()
    USER = os.getenv("NEWS_ORACLE_USER", "").strip()
    PASSWORD = os.getenv("NEWS_ORACLE_PASSWORD", "").strip()
    DSN = os.getenv("NEWS_ORACLE_DSN", "").strip()

    def connect(self):
        if not self.USER or not self.PASSWORD or not self.DSN:
            raise RuntimeError("Set NEWS_ORACLE_USER, NEWS_ORACLE_PASSWORD, and NEWS_ORACLE_DSN before running the collector.")

        if self.WALLET_PATH:
            os.environ['TNS_ADMIN'] = self.WALLET_PATH
        if self.LIB_DIR:
            oracledb.init_oracle_client(lib_dir=self.LIB_DIR)

        return oracledb.connect(
            user=self.USER,
            password=self.PASSWORD,
            dsn=self.DSN
        )
    def save_one(self, news: dict, cursor, connection, table: str) -> bool:

        """泥섎━ ?꾨즺??湲곗궗 1嫄댁쓣 利됱떆 ?쎌엯."""
        insert_sql = f"""
            INSERT INTO {table}
                (link, category, title, summary, sentiment,
                 pub_date, clickbait_prob, article_type, type_prob)
            VALUES (:1, :2, :3, :4, :5, :6, :7, :8, :9)
        """
        try:
            if news['summary'] == '?뚯닔?놁쓬' or news['article_type'] == '?뚯닔?놁쓬':
                return False

            cursor.execute(insert_sql, [
                news['link'], news['category'], news['title'],
                news['summary'], news['sentiment'], news['date'],
                str(news['clickbait_prob']),
                news['article_type'], str(news['type_prob'])
            ])
            connection.commit()
            return True
        except oracledb.IntegrityError:
            return False  # 以묐났
        except Exception as e:
            print(f"  ?쎌엯 ?먮윭 [{news['title'][:10]}]: {e}")
            return False


# ================================================================
# [鍮꾨룞湲??뚯씠?꾨씪??
# ================================================================
async def run_pipeline_async(
    sector_config: dict,
    verify_fn,                 # callable(proc, title, text, info) -> bool  [?숆린]
    db_table: str = "news_data",
    max_per_sector: int = 100,
    fetch_concurrency: int = 20,
    groq_concurrency: int = 5,
    groq_api_keys: str | list = None,  # ?⑥씪 ???먮뒗 ??由ъ뒪??(?쇱슫?쒕줈鍮?


) -> int:
    """
    ?꾩껜 ?댁뒪 ?섏쭛 ?뚯씠?꾨씪??
    - 紐⑤뱺 ?뱁꽣 ?ㅼ씠踰?API ?숈떆 ?몄텧
    - 湲곗궗 蹂몃Ц Semaphore(fetch_concurrency) 蹂묐젹 ?섏쭛
    - BERT / okt 釉붾줈????ThreadPoolExecutor ?ㅽ봽濡쒕뱶
    - Groq Semaphore(groq_concurrency) 蹂묐젹 + sleep ?띾룄 ?쒗븳
    - 泥섎━ ?꾨즺 利됱떆 asyncio.Queue ??DB writer ?④굔 ?쎌엯
    諛섑솚媛? DB ?좉퇋 ?쎌엯 嫄댁닔
    """
    mm   = ModelManager()
    proc = ArticleProcessor(mm)

    groq = GroqAnalyzer(api_keys=groq_api_keys)
    db   = DatabaseManager()

    db_executor  = ThreadPoolExecutor(max_workers=1)  # DB ?⑥씪 ?ㅻ젅??
    cpu_executor = ThreadPoolExecutor(max_workers=4)  # BERT / okt

    naver_headers = {
        "X-Naver-Client-Id":     os.getenv("NAVER_CLIENT_ID"),
        "X-Naver-Client-Secret": os.getenv("NAVER_CLIENT_SECRET")
    }

    fetch_sem    = asyncio.Semaphore(fetch_concurrency)
    groq_sem     = asyncio.Semaphore(groq_concurrency)
    result_queue = asyncio.Queue()

    seen_links    = set()
    seen_lock     = asyncio.Lock()
    sector_counts = {name: 0 for name in sector_config}
    loop          = asyncio.get_event_loop()

    # ?? DB writer: ?먯뿉??爰쇰궡 利됱떆 ?쎌엯, None ?섏떊 ??醫낅즺 ??????????
    async def db_writer(connection, cursor):
        success = duplicate = 0
        while True:
            item = await result_queue.get()
            if item is None:
                break
            ok = await loop.run_in_executor(
                db_executor, db.save_one, item, cursor, connection, db_table
            )
            if ok:
                success += 1
                print(f"   ?뮶 [{item['category']}] {item['title'][:20]}... ???)
            else:
                duplicate += 1
            result_queue.task_done()
        return success, duplicate

    # ?? ?ㅼ씠踰?API ?몄텧 ??????????????????????????????????????????????
    async def fetch_naver_items(sector_name: str, info: dict, session):
        api_url = (
            "https://openapi.naver.com/v1/search/news.json"
            f"?query={info['search_query']}&display=100&sort=date"
        )
        try:
            timeout = aiohttp.ClientTimeout(total=10)
            async with session.get(api_url, headers=naver_headers, timeout=timeout) as resp:
                if resp.status != 200:
                    print(f"   ?ㅼ씠踰?API ?ㅻ쪟 ({sector_name}): {resp.status}")
                    return sector_name, []
                data = await resp.json()

                return sector_name, data.get("items", [])
        except Exception as e:
            print(f"   ?먮윭 ({sector_name}): {e}")
            return sector_name, []

    # ?? 湲곗궗 泥섎━ ????????????????????????????????????????????????????
    async def process_article(sector_name: str, info: dict, item: dict, session):
        link = item["link"]

        async with seen_lock:
            if link in seen_links or sector_counts[sector_name] >= max_per_sector:
                return
            seen_links.add(link)

        # 蹂몃Ц ?섏쭛
        async with fetch_sem:
            full_text = await proc.fetch_content(session, link)
        if not full_text:
            full_text = (
                item.get("description", "")
                .replace("<b>", "").replace("</b>", "")
            )

        clean_title = (
            item["title"]
            .replace("<b>", "").replace("</b>", "")
            .replace("&quot;", '"')
        )

        # 愿?⑤룄 ?꾪꽣 (okt ?ы븿 ???ㅻ젅?쒗?)
        passed = await loop.run_in_executor(
            cpu_executor, verify_fn, proc, clean_title, full_text, info
        )
        if not passed:
            return

        async with seen_lock:
            if sector_counts[sector_name] >= max_per_sector:
                return
            sector_counts[sector_name] += 1

        # ?좎쭨 ?뚯떛
        raw_date = item.get("pubDate", "")
        try:
            formatted_date = datetime.strptime(
                raw_date, "%a, %d %b %Y %H:%M:%S +0900"
            ).strftime("%Y-%m-%d %H:%M")
        except Exception:
            formatted_date = raw_date

        # 怨쇱옣??(TF-IDF, 鍮좊쫫)
        clickbait_prob = proc.predict_clickbait(clean_title, full_text)

        # BERT ?좏삎 遺꾨쪟 (釉붾줈?????ㅻ젅?쒗?)
        article_type, type_prob = "?????놁쓬", 0.0
        if mm.bert_model:
            chunks = proc.clean_and_split(full_text)
            article_type, type_prob = await loop.run_in_executor(
                cpu_executor, proc.predict_type, chunks
            )

        # Groq ?붿빟/媛먯꽦 (鍮꾨룞湲?+ ?띾룄 ?쒗븳)
        async with groq_sem:
            # await asyncio.sleep(5)

            groq_result = await groq.analyze(clean_title, full_text)

        news_obj = {
            "category":       sector_name,
            "title":          clean_title,
            "summary":        groq_result.get("summary",   "?붿빟 ?놁쓬"),
            "sentiment":      groq_result.get("sentiment", "以묐┰"),
            "link":           link,
            "date":           formatted_date,
            "clickbait_prob": clickbait_prob,
            "article_type":   article_type,
            "type_prob":      type_prob
        }

        print(
            f"   ??[{formatted_date}] {clean_title[:20]}...\n"
            f"      媛먯꽦: {news_obj['sentiment']} | "
            f"?좏삎: {article_type}({type_prob:.1f}%) | "
            f"怨쇱옣?? {clickbait_prob:.1f}%\n"
            f"      ?붿빟: {news_obj['summary']}\n"
        )

        await result_queue.put(news_obj)

    # ?? ?ㅽ뻾 ?????????????????????????????????????????????????????????
    try:
        connection = db.connect()
        cursor     = connection.cursor()
        print(f"??DB ?곌껐 ?깃났! (?뚯씠釉? {db_table})")
    except Exception as e:
        print(f"??DB ?곌껐 ?ㅽ뙣: {e}")
        return 0

    sector_results = []
    async with aiohttp.ClientSession() as session:
        for name, info in sector_config.items():
            # (1) ???뱁꽣 ?몄텧
            result = await fetch_naver_items(name, info, session)
            sector_results.append(result)

            # (2) ?몄텧 ??1.5珥덇컙 吏꾩쭨濡??ш린 (?ㅼ쓬 ?뱁꽣濡??섏뼱媛湲???
            print(f"   ??{name} ?꾨즺, ?좎떆 ?湲?以?..")
            await asyncio.sleep(0.5)
        # DB writer ?쒖옉
        writer_task = asyncio.create_task(db_writer(connection, cursor))

        # ???뱁꽣 湲곗궗 ?숈떆 泥섎━
        all_tasks = []
        for sector_name, items in sector_results:
            info = sector_config[sector_name]
            print(f"\n?뱻 {sector_name} ({len(items)}媛??꾨낫)")
            for item in items:
                if "naver.com" in item.get("link", ""):
                    all_tasks.append(process_article(sector_name, info, item, session))

        await asyncio.gather(*all_tasks)

        # DB writer 醫낅즺
        await result_queue.put(None)
        success, duplicate = await writer_task

    cursor.close()
    connection.close()
    db_executor.shutdown(wait=False)
    cpu_executor.shutdown(wait=False)

    total = sum(sector_counts.values())
    print(f"\n??[{db_table}] ?꾨즺 ??泥섎━: {total}媛?| ?좉퇋: {success}媛?| 以묐났: {duplicate}媛?)
    return success

