"""
news_common.py — 공통 클래스 모음
  - ModelManager     : BERT / TF-IDF 모델 싱글톤 로딩
  - ArticleProcessor : 본문 비동기 수집, 텍스트 정제, BERT 추론, 필터링
  - GroqAnalyzer     : 비동기 요약 + 감성 분석 (API 키 별도 지정 가능)
  - DatabaseManager  : Oracle DB 즉시 단건 삽입
  - run_pipeline_async: 전체 수집 파이프라인
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
from pathlib import Path
from openai import AsyncOpenAI
from transformers import BertModel, BertTokenizerFast

SCRIPT_DIR = Path(__file__).resolve().parent
MODEL_DIR = SCRIPT_DIR / "models"
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# PyCharm에서 별도로 보관/실행할 때 사용할 기본 경로입니다.
CLICKBAIT_MODEL_PATH = MODEL_DIR / "clickbait_model.joblib"
TFIDF_VECTORIZER_PATH = MODEL_DIR / "tfidf_vectorizer.joblib"
BERT_MODEL_PATH = MODEL_DIR / "model.pt"

# TODO: Oracle 연결 정보는 본인 환경에 맞게 바꾸거나 동일한 이름의 환경변수를 설정하세요.
ORACLE_CLIENT_LIB_DIR = os.getenv("NEWS_ORACLE_CLIENT_LIB_DIR", r"C:\path\to\instantclient")
ORACLE_WALLET_PATH = os.getenv("NEWS_ORACLE_WALLET_PATH", r"C:\path\to\wallet")
ORACLE_DB_USER = os.getenv("NEWS_ORACLE_DB_USER", "YOUR_ORACLE_USER")
ORACLE_DB_PASSWORD = os.getenv("NEWS_ORACLE_DB_PASSWORD", "YOUR_ORACLE_PASSWORD")
ORACLE_DB_DSN = os.getenv("NEWS_ORACLE_DB_DSN", "YOUR_ORACLE_DSN")


# ================================================================
# [공통 필터 단어]
# ================================================================
EXCLUDE_WORDS = [
    "사형"

]

TITLE_EXCLUDE_WORDS = [
    "시황", "포토"
]


# ================================================================
# [BERT 모델 구조]
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
# [ModelManager — 싱글톤]
# ================================================================
class ModelManager:
    """BERT + TF-IDF 모델을 한 번만 로드. 두 파이프라인이 공유."""

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
        print(f"장치: {self.device}")
        self._load_logistic()
        self._load_bert()

    def _load_logistic(self):
        print("로지스틱 회귀 모델 로딩 중...")
        try:
            self.lr_model = joblib.load(CLICKBAIT_MODEL_PATH)
            self.lr_vectorizer = joblib.load(TFIDF_VECTORIZER_PATH)
            print("✅ 로지스틱 회귀 로딩 완료!")
        except Exception as e:
            print(f"❌ 로딩 실패: {e}")
            self.lr_model      = None
            self.lr_vectorizer = None

    def _load_bert(self):
        try:
            import __main__
            __main__.BertBaseModel = BertBaseModel  # pickle이 __main__에서 찾으므로 주입
            self.bert_model = torch.load(
                BERT_MODEL_PATH, map_location=self.device, weights_only=False
            )
            self.bert_model.to(self.device)
            self.bert_model.eval()
            self.tokenizer = BertTokenizerFast.from_pretrained("kykim/bert-kor-base")
            print("✅ BERT 로딩 완료!")
        except Exception as e:
            print(f"❌ BERT 로딩 실패: {e}")
            self.bert_model = None
            self.tokenizer  = None


# ================================================================
# [ArticleProcessor]
# ================================================================
class ArticleProcessor:
    """본문 비동기 수집 / 텍스트 정제 / BERT 추론 / 필터링."""

    def __init__(self, model_manager: ModelManager):
        self.mm        = model_manager
        self.okt       = Okt()
        self._okt_lock = threading.Lock()  # KoNLPy 스레드 비안전 → 락

    # ------ 비동기 본문 수집 ------
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

    # ------ 텍스트 정제 + 청크 분리 ------
    def clean_and_split(self, full_text: str, max_chars: int = 100) -> list:
        if not full_text:
            return []
        text = re.sub(r'[a-zA-Z0-9+-_.]+@[a-zA-Z0-9-]+\.[a-zA-Z0-9-.]+', '', full_text)
        text = re.sub(r'©.*|Copyright.*|무단\s*전재.*|배포\s*금지.*', '', text)
        text = re.sub(r'\S+\s*기자', '', text)
        text = re.sub(r'https?://\S+', '', text)
        text = re.sub(r'\[.*?\]|\(.*?사진.*?\)|\(.*?제공.*?\)', '', text)
        text = re.sub(r'[ \t]+', ' ', text)
        text = re.sub(r'\n{2,}', '\n', text)
        text = text.strip()

        sentences_raw = re.split(r'(?<=[다요음임])\.\s+|\n', text)
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

    # ------ BERT 기사 유형 분류 (동기 → run_in_executor로 호출) ------
    def predict_type(self, paragraphs: list) -> tuple[str, float]:
        mm = self.mm
        if not paragraphs or mm.bert_model is None:
            return "알 수 없음", 0.0

        class_names         = ["사실형", "예측형", "대화형", "추론형"]
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

        sentence_reliability_scores = []
        for label, conf in zip(predicted_labels, chunk_confidences):
            if label == "사실형":
                sentence_reliability_scores.append(conf)
            else:
                # 사실형이 아니면 신뢰도 기여도를 0.2*예측확률로 만듦
                sentence_reliability_scores.append((1-conf) * 0.5)

        # 2. 전체 문장 대비 사실형 점수의 평균 산출
        # 이것이 기사 전체의 '문맥적 신뢰도'가 됩니다.
        total_reliability = (sum(sentence_reliability_scores) / len(predicted_labels)) * 100

        # 3. 대표 유형(final_label)은 그대로 가장 많이 나온 것을 선택
        # (유형은 '예측형'일 수 있지만, 신뢰도는 낮게 표시되는 구조)
        label_counts = Counter(predicted_labels)

        # 1. 모든 빈도수 데이터를 가져옴
        all_counts = label_counts.most_common()
        # 결과 예시: [('대화형', 2), ('사실형', 2), ('추론형', 1)]

        # 2. 가장 높은 빈도수가 몇 개인지 확인
        max_freq = all_counts[0][1] # 위 예시에서는 2
        # 3. 그 빈도수를 가진 '공동 1등' 후보들을 리스트로 만듦
        candidates = [label for label, count in all_counts if count == max_freq]
        # 결과: ['대화형', '사실형']

        # 4. 후보 중에 '사실형'이 있다면 무조건 사실형을 선택
        if "사실형" in candidates:
            final_label = "사실형"
        else:
            # 사실형이 없다면 그냥 후보 중 첫 번째 것을 선택
            final_label = candidates[0]


        return final_label, round(total_reliability, 1)


    # ------ 과장성 점수 ------
    def predict_clickbait(self, title: str, text: str) -> float:
        mm = self.mm
        if not mm.lr_model or not mm.lr_vectorizer:
            return 0.0
        vec   = mm.lr_vectorizer.transform([title + " " + text])
        probs = mm.lr_model.predict_proba(vec)[0]
        return round(probs[0] * 100, 1)

    # ------ 섹터 필터 (동기 → run_in_executor로 호출) ------
    def verify_sector(self, title: str, text: str, keywords: list) -> bool:
        """CrawlingNaverApi용: keywords 단일 리스트"""
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
        """CrawlingNaverTOP7용: core + sub keywords"""
        if not text:
            return False
        for bad in TITLE_EXCLUDE_WORDS:
            if bad in title:
                return False
        for bad in EXCLUDE_WORDS:
            if bad in title or bad in text:
                return False
        if "KB금융" in core_keywords:
            return True
        if not any(core in title for core in core_keywords):
            return False
        with self._okt_lock:
            nouns = self.okt.nouns(text)
        return sum(1 for n in nouns if n in sub_keywords) >= 1



# ================================================================
# [GroqAnalyzer — 비동기]
# ================================================================
class GroqAnalyzer:
    def __init__(self, api_keys: str):
        # 문자열 or 리스트 모두 수용, 라운드로빈으로 키 순환
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
            "너는 주식 시장 뉴스 분석 전문가다.\n"
            "1. 반드시 유효한 JSON 형식으로만 응답한다.\n"
            "2. 'sentiment'는 [호재, 악재, 중립] 중 하나로만 선택한다.\n"
            "   - 호재: 실적 상승, 수주 성공, 신제품 흥행, 목표주가 상향 등\n"
            "   - 악재: 실적 하락, 소송, 리콜, 규제, 목표주가 하향 등\n"
            "   - 중립: 단순 사실 전달, 영향 미미, 호악재 혼재 등\n"
            "3. 'summary'는 1문장으로 핵심만 요약한다.\n"
            "4. 키는 'summary'와 'sentiment'만 사용한다."
        )

    async def analyze(self, title: str, full_text: str) -> dict:
        if not full_text:
            return {"summary": "본문 없음", "sentiment": "중립"}
        # 라운드로빈: 요청마다 다음 키 사용
        client = self._clients[self._idx % len(self._clients)]
        self._idx += 1
        try:
            response = await client.chat.completions.create(
                model="llama-3.1-8b-instant",
                messages=[
                    {"role": "system", "content": self._system_prompt},
                    {"role": "user",
                     "content": f"제목: {title}\n본문: {full_text[:800]}"}
                ],
                temperature=0.5,
                response_format={"type": "json_object"},
                max_tokens=2048
            )
            result = json.loads(response.choices[0].message.content.strip())
            if result.get("sentiment") not in ["호재", "악재", "중립"]:
                mapping = {"긍정": "호재", "부정": "악재"}
                result["sentiment"] = mapping.get(result.get("sentiment"), "중립")
            return result
        except Exception as e:
            print(f"Groq 오류 (키 #{self._idx % len(self._clients)}): {e}")
            return {"summary": "요약 오류", "sentiment": "중립"}


# ================================================================
# [DatabaseManager — 즉시 단건 삽입]
# ================================================================
class DatabaseManager:
    def connect(self):
        os.environ['TNS_ADMIN'] = ORACLE_WALLET_PATH
        oracledb.init_oracle_client(lib_dir=ORACLE_CLIENT_LIB_DIR)
        return oracledb.connect(
            user=ORACLE_DB_USER,
            password=ORACLE_DB_PASSWORD,
            dsn=ORACLE_DB_DSN,
        )

    def save_one(self, news: dict, cursor, connection, table: str) -> bool:

        insert_sql = f"""
            INSERT INTO {table}
                (link, category, title, summary, sentiment,
                 pub_date, clickbait_prob, article_type, type_prob)
            VALUES (:1, :2, :3, :4, :5, :6, :7, :8, :9)
        """
        try:
            if news['summary'] == '알 수 없음' or news['article_type'] == '알 수 없음':
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
            return False  # 중복
        except Exception as e:
            print(f"  삽입 에러 [{news['title'][:10]}]: {e}")
            return False


# ================================================================
# [비동기 파이프라인]
# ================================================================
async def run_pipeline_async(
        sector_config: dict,
        verify_fn,                 # callable(proc, title, text, info) -> bool  [동기]
        db_table: str = "news_data",
        max_per_sector: int = 100,
        fetch_concurrency: int = 20,
        groq_concurrency: int = 5,
        groq_api_keys: str | list = None,  # 단일 키 또는 키 리스트 (라운드로빈)


) -> int:
    """
    전체 뉴스 수집 파이프라인.
    - 모든 섹터 네이버 API 동시 호출
    - 기사 본문 Semaphore(fetch_concurrency) 병렬 수집
    - BERT / okt 블로킹 → ThreadPoolExecutor 오프로드
    - Groq Semaphore(groq_concurrency) 병렬 + sleep 속도 제한
    - 처리 완료 즉시 asyncio.Queue → DB writer 단건 삽입
    반환값: DB 신규 삽입 건수
    """
    mm   = ModelManager()
    proc = ArticleProcessor(mm)

    groq = GroqAnalyzer(api_keys=groq_api_keys)
    db   = DatabaseManager()

    db_executor  = ThreadPoolExecutor(max_workers=1)  # DB 단일 스레드
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

    # ── DB writer: 큐에서 꺼내 즉시 삽입, None 수신 시 종료 ──────────
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
                print(f"   💾 [{item['category']}] {item['title'][:20]}... 저장")
            else:
                duplicate += 1
            result_queue.task_done()
        return success, duplicate

    # ── 네이버 API 호출 ──────────────────────────────────────────────
    async def fetch_naver_items(sector_name: str, info: dict, session):
        api_url = (
            "https://openapi.naver.com/v1/search/news.json"
            f"?query={info['search_query']}&display=100&sort=date"
        )
        try:
            timeout = aiohttp.ClientTimeout(total=10)
            async with session.get(api_url, headers=naver_headers, timeout=timeout) as resp:
                if resp.status != 200:
                    print(f"   네이버 API 오류 ({sector_name}): {resp.status}")
                    return sector_name, []
                data = await resp.json()

                return sector_name, data.get("items", [])
        except Exception as e:
            print(f"   에러 ({sector_name}): {e}")
            return sector_name, []

    # ── 기사 처리 ────────────────────────────────────────────────────
    async def process_article(sector_name: str, info: dict, item: dict, session):
        link = item["link"]

        async with seen_lock:
            if link in seen_links or sector_counts[sector_name] >= max_per_sector:
                return
            seen_links.add(link)

        # 본문 수집
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

        # 관련도 필터 (okt 포함 → 스레드풀)
        passed = await loop.run_in_executor(
            cpu_executor, verify_fn, proc, clean_title, full_text, info
        )
        if not passed:
            return

        async with seen_lock:
            if sector_counts[sector_name] >= max_per_sector:
                return
            sector_counts[sector_name] += 1

        # 날짜 파싱
        raw_date = item.get("pubDate", "")
        try:
            formatted_date = datetime.strptime(
                raw_date, "%a, %d %b %Y %H:%M:%S +0900"
            ).strftime("%Y-%m-%d %H:%M")
        except Exception:
            formatted_date = raw_date

        # 과장성 (TF-IDF, 빠름)
        clickbait_prob = proc.predict_clickbait(clean_title, full_text)

        # BERT 유형 분류 (블로킹 → 스레드풀)
        article_type, type_prob = "알 수 없음", 0.0
        if mm.bert_model:
            chunks = proc.clean_and_split(full_text)
            article_type, type_prob = await loop.run_in_executor(
                cpu_executor, proc.predict_type, chunks
            )

        # Groq 요약/감성 (비동기 + 속도 제한)
        async with groq_sem:
            # await asyncio.sleep(5)

            groq_result = await groq.analyze(clean_title, full_text)

        news_obj = {
            "category":       sector_name,
            "title":          clean_title,
            "summary":        groq_result.get("summary",   "요약 없음"),
            "sentiment":      groq_result.get("sentiment", "중립"),
            "link":           link,
            "date":           formatted_date,
            "clickbait_prob": clickbait_prob,
            "article_type":   article_type,
            "type_prob":      type_prob
        }

        print(
            f"   ✅ [{formatted_date}] {clean_title[:20]}...\n"
            f"      감성: {news_obj['sentiment']} | "
            f"유형: {article_type}({type_prob:.1f}%) | "
            f"과장성: {clickbait_prob:.1f}%\n"
            f"      요약: {news_obj['summary']}\n"
        )

        await result_queue.put(news_obj)

    # ── 실행 ─────────────────────────────────────────────────────────
    try:
        connection = db.connect()
        cursor     = connection.cursor()
        print(f"✅ DB 연결 성공! (테이블: {db_table})")
    except Exception as e:
        print(f"❌ DB 연결 실패: {e}")
        return 0

    sector_results = []
    async with aiohttp.ClientSession() as session:
        for name, info in sector_config.items():
            # (1) 한 섹터 호출
            result = await fetch_naver_items(name, info, session)
            sector_results.append(result)

            # (2) 호출 후 1.5초간 진짜로 쉬기 (다음 섹터로 넘어가기 전)
            print(f"   ☕ {name} 완료, 잠시 대기 중...")
            await asyncio.sleep(0.5)
        # DB writer 시작
        writer_task = asyncio.create_task(db_writer(connection, cursor))

        # 전 섹터 기사 동시 처리
        all_tasks = []
        for sector_name, items in sector_results:
            info = sector_config[sector_name]
            print(f"\n📡 {sector_name} ({len(items)}개 후보)")
            for item in items:
                if "naver.com" in item.get("link", ""):
                    all_tasks.append(process_article(sector_name, info, item, session))

        await asyncio.gather(*all_tasks)

        # DB writer 종료
        await result_queue.put(None)
        success, duplicate = await writer_task

    cursor.close()
    connection.close()
    db_executor.shutdown(wait=False)
    cpu_executor.shutdown(wait=False)

    total = sum(sector_counts.values())
    print(f"\n✅ [{db_table}] 완료 — 처리: {total}개 | 신규: {success}개 | 중복: {duplicate}개")
    return success
