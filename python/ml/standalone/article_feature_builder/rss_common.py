"""
rss_common.py — 공통 클래스 모음 (RSS + 로컬 SQLite 버전)
  - ModelManager     : BERT / TF-IDF 모델 싱글톤 로딩       ← 원본 그대로
  - ArticleProcessor : 본문 비동기 수집, 텍스트 정제, BERT 추론, 필터링  ← 원본 그대로
  - GroqAnalyzer     : 비동기 요약 + 감성 분석               ← 원본 그대로
  - DatabaseManager  : Oracle → 로컬 SQLite 단건 삽입        ← 변경
  - run_pipeline_async: 네이버 API → RSS 피드 수집           ← 변경
"""

import os
import re
import json
import asyncio
import joblib
import sqlite3
import threading
import feedparser
import aiohttp
import torch
import torch.nn as nn
import torch.nn.functional as F
from concurrent.futures import ThreadPoolExecutor
from collections import Counter
from bs4 import BeautifulSoup
from konlpy.tag import Okt
from datetime import datetime, timedelta
from openai import AsyncOpenAI
from transformers import BertModel, BertTokenizerFast
import time
import random
from GoogleNews import GoogleNews
from pathlib import Path

from datetime import datetime

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR.mkdir(parents=True, exist_ok=True)
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# PyCharm에서 별도로 보관/실행할 때 사용할 기본 경로입니다.
DB_PATH = str(DATA_DIR / "news_data.db")
CLICKBAIT_MODEL_PATH = MODEL_DIR / "clickbait_model.joblib"
TFIDF_VECTORIZER_PATH = MODEL_DIR / "tfidf_vectorizer.joblib"
BERT_MODEL_PATH = MODEL_DIR / "model.pt"


# ================================================================
# [SQLite DB 경로 설정]
# ================================================================

from gnews import GNews
from datetime import datetime

# 섹터별 검색 키워드
GNEWS_QUERIES = {
    "반도체/AI":      "삼성전자 SK하이닉스 반도체",
    "2차전지":        "LG에너지솔루션 배터리 전기차",
    "바이오":         "삼성바이오로직스 신약 임상",
    "금융/밸류업":    "KB금융 밸류업 배당",
    "방산":           "한화에어로스페이스 방산 수출",
    "IT/플랫폼":      "네이버 카카오 플랫폼",
    "엔터/미디어":    "하이브 BTS 엔터",
    "자동차/모빌리티":"현대차 기아 전기차"
}


RSS_FEEDS = [
    ("한국경제_증권", "https://www.hankyung.com/feed/finance"),
    ("한국경제_경제", "https://www.hankyung.com/feed/economy"),
    ("한국경제_IT",   "https://www.hankyung.com/feed/it"),
    ("한국경제_전체", "https://www.hankyung.com/feed/all-news"),
    ("매일경제",      "https://www.mk.co.kr/rss/40300001/"),
    ("연합뉴스",      "https://www.yna.co.kr/rss/economy.xml"),
]


def fetch_gnews_articles(max_results=100):
    from GoogleNews import GoogleNews
    from datetime import datetime

    # 월별 날짜 범위
    date_ranges = [
        # ('01/01/2025', '01/31/2025'),
        # ('02/01/2025', '02/28/2025'),
        # ('03/01/2025', '03/31/2025'),
        # ('04/01/2025', '04/30/2025'),
        # ('05/01/2025', '05/31/2025'),
        # ('06/01/2025', '06/30/2025'),
        # ('07/01/2025', '07/31/2025'),
        # ('08/01/2025', '08/31/2025'),
        # ('09/01/2025', '09/30/2025'),
        # ('10/01/2025', '10/31/2025'),
        # ('11/01/2025', '11/30/2025'),
        # ('12/01/2025', '12/31/2025'),
        # ('01/01/2026', '01/31/2026'),
        # ('02/01/2026', '02/28/2026'),
        # ('03/01/2026', '03/31/2026'),
        ('04/18/2026', '04/21/2026')
    ]

    QUERIES = [
        # 반도체/AI
        "삼성전자 반도체", "SK하이닉스 HBM", "AI 반도체", "파운드리 TSMC",
        # 2차전지
        "LG에너지솔루션 배터리", "에코프로 양극재", "전기차 배터리", "삼성SDI",
        # 바이오
        "삼성바이오로직스", "신약 임상 FDA", "셀트리온 바이오", "알테오젠",
        # 금융
        "KB금융 배당", "은행주 밸류업", "자사주 소각", "신한지주",
        # 방산
        "한화에어로스페이스", "방산 수출", "K9 자주포", "현대로템",
        # IT/플랫폼
        "네이버 AI", "카카오 실적", "크래프톤 게임", "카카오페이",
        # 엔터
        "하이브 BTS", "JYP 아이돌", "K팝 빌보드", "SM 에스파",
        # 자동차
        "현대차 전기차", "기아 아이오닉", "제네시스 수출", "현대차 실적",
    ]

    # 쿼리 → 섹터 매핑
    QUERY_SECTOR_MAP = {
        "삼성전자 반도체": "반도체/AI", "SK하이닉스 HBM": "반도체/AI",
        "AI 반도체": "반도체/AI", "파운드리 TSMC": "반도체/AI",
        "LG에너지솔루션 배터리": "2차전지", "에코프로 양극재": "2차전지",
        "전기차 배터리": "2차전지", "삼성SDI": "2차전지",
        "삼성바이오로직스": "바이오", "신약 임상 FDA": "바이오",
        "셀트리온 바이오": "바이오", "알테오젠": "바이오",
        "KB금융 배당": "금융/밸류업", "은행주 밸류업": "금융/밸류업",
        "자사주 소각": "금융/밸류업", "신한지주": "금융/밸류업",
        "한화에어로스페이스": "방산", "방산 수출": "방산",
        "K9 자주포": "방산", "현대로템": "방산",
        "네이버 AI": "IT/플랫폼", "카카오 실적": "IT/플랫폼",
        "크래프톤 게임": "IT/플랫폼", "카카오페이": "IT/플랫폼",
        "하이브 BTS": "엔터/미디어", "JYP 아이돌": "엔터/미디어",
        "K팝 빌보드": "엔터/미디어", "SM 에스파": "엔터/미디어",
        "현대차 전기차": "자동차/모빌리티", "기아 아이오닉": "자동차/모빌리티",
        "제네시스 수출": "자동차/모빌리티", "현대차 실적": "자동차/모빌리티",
    }

    all_articles = []
    seen_links   = set()
    total = 0

    for start, end in date_ranges:
        print(f"\n  📅 {start} ~ {end} 수집 중...")
        period_count = 0

        for query in QUERIES:
            try:
                gn = GoogleNews(lang='ko', region='KR')
                gn.set_time_range(start, end)
                gn.search(query)
                gn.getpage(2)  # 2페이지까지
                results = gn.result()

                sector = QUERY_SECTOR_MAP.get(query, "기타")

                for r in results:
                    if not r or not isinstance(r, dict):
                        continue
                    link = r.get('link', '')
                    if not link or link in seen_links:
                        continue

                    title = r.get('title', '')
                    if not title:
                        continue

                    all_articles.append({
                        'title':    title,
                        'link':     link,
                        'date':     r.get('date', ''),
                        'summary':  r.get('desc', '') or '',
                        'source':   r.get('media', ''),
                        'category': sector,
                    })
                    seen_links.add(link)
                    period_count += 1

            except Exception as e:
                if '429' in str(e):
                    print(f"  429 감지 - 30초 대기...")
                    time.sleep(30)  # 429면 30초 대기
                pass

            # 쿼리마다 1~3초 랜덤 딜레이
            time.sleep(random.uniform(1, 3))
        print(f"  → {period_count}개 수집")
        total += period_count

    # RSS 병행
    print("\n📡 RSS 병행 수집 중...")
    rss_articles = fetch_rss_articles(days_back=30)
    for a in rss_articles:
        if a['link'] not in seen_links:
            all_articles.append(a)
            seen_links.add(a['link'])

    print(f"GoogleNews+RSS 총 수집: {len(all_articles)}건")
    return all_articles

# ================================================================
# [공통 필터 단어] — 원본 그대로
# ================================================================
EXCLUDE_WORDS = [
    "사형", "징역", "검찰", "구속", "당대표", "정당", "비서관", "정치", "의원",
    "대통령", "국회", "여당", "야당", "선거", "총선", "대선", "경찰", "수사",
    "장관", "공직자", "고위직", "재산", "윤리위원회",
    "다주택", "청약", "아파트", "전세", "부동산", "분양"
]

TITLE_EXCLUDE_WORDS = [
    "시황", "마감", "특징주", "인사이트", "증시", "코스피", "코스닥", "종합",
    "순매수", "순매도", "동반 상승", "동반 하락", "외인", "기관", "뉴욕증시",
    "관련주", "테마주", "수혜주", "급등주", "종목추천"
]


# ================================================================
# [BERT 모델 구조] — 원본 그대로
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
# [ModelManager — 싱글톤] — 원본 그대로
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

        # ← 속성 먼저 None으로 초기화 (AttributeError 방지)
        self.lr_model = None
        self.lr_vectorizer = None
        self.bert_model = None
        self.tokenizer = None

        if torch.cuda.is_available():
            print(f"✅ GPU 사용: {torch.cuda.get_device_name(0)}")
        else:
            print(f"장치: cpu")

        self._load_logistic()
        self._load_bert()

    def _load_logistic(self):
        print("로지스틱 회귀 모델 로딩 중...")
        try:
            self.lr_model      = joblib.load(CLICKBAIT_MODEL_PATH)
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
# [ArticleProcessor] — 원본 그대로
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
                content = soup.select_one(
                    "#dic_area, #newsct_article, #articeBody, .article-body, .news-content"
                )
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

    # ------ BERT 기사 유형 분류 ------
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
        vote_ratio = len(win_confs) / len(predicted_labels)
        final_prob = (sum(win_confs) / len(win_confs)) * vote_ratio * 100
        return final_label, round(final_prob, 1)

    # ------ 과장성 점수 ------
    def predict_clickbait(self, title: str, text: str) -> float:
        mm = self.mm
        if not mm.lr_model or not mm.lr_vectorizer:
            return 0.0
        vec   = mm.lr_vectorizer.transform([title + " " + text])
        probs = mm.lr_model.predict_proba(vec)[0]
        return round(probs[0] * 100, 1)

    # ------ 섹터 필터 (CrawlingNaverApi용) ------
    def verify_sector(self, title: str, text: str, keywords: list) -> bool:
        if not text:
            return False
        for bad in TITLE_EXCLUDE_WORDS:
            if bad in title:
                return False
        for bad in EXCLUDE_WORDS:
            if bad in title or bad in text:
                return False
        if any(kw in title for kw in keywords):
            return True
        with self._okt_lock:
            nouns = self.okt.nouns(text)
        return sum(1 for n in nouns if n in keywords) >= 2

    # ------ 종목 필터 (CrawlingNaverTOP7용) ------
    def verify_company(
        self, title: str, text: str, core_keywords: list, sub_keywords: list
    ) -> bool:
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
# [GroqAnalyzer — 비동기] — 원본 그대로
# ================================================================
class GroqAnalyzer:
    def __init__(self, api_keys: str | list = None):
        if api_keys is None:
            api_keys = [os.getenv("GROQ_API_KEY")]
        elif isinstance(api_keys, str):
            api_keys = [api_keys]
        self._clients = [
            AsyncOpenAI(base_url="https://api.groq.com/openai/v1", api_key=k)
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

        if not self._clients:
            return {"summary": "API키 없음", "sentiment": "중립"}

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
                response_format={"type": "json_object"}
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
# [DatabaseManager — 로컬 SQLite] ← Oracle에서 변경
# ================================================================
class DatabaseManager:
    """Oracle Cloud → 로컬 SQLite DB로 변경"""

    def __init__(self, db_path: str = DB_PATH):
        self.db_path = db_path
        self._init_db()

    def _init_db(self):
        """사용할 모든 테이블 미리 생성"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()

        # 섹터용 + 기업용 테이블 둘 다 생성
        for table in ["news_data", "test_db_sector", "test_db_company"]:
            cursor.execute(f"""
                CREATE TABLE IF NOT EXISTS {table} (
                    link           TEXT PRIMARY KEY,
                    category       TEXT,
                    title          TEXT,
                    summary        TEXT,
                    sentiment      TEXT,
                    pub_date       TEXT,
                    clickbait_prob TEXT,
                    article_type   TEXT,
                    type_prob      TEXT,
                    created_at     TEXT DEFAULT (datetime('now', 'localtime'))
                )
            """)

        conn.commit()
        conn.close()
        print(f"✅ SQLite DB 초기화 완료: {self.db_path}")

    def connect(self):
        """SQLite 연결 반환 (원본 Oracle connect()와 동일한 인터페이스)"""
        conn = sqlite3.connect(self.db_path, check_same_thread=False)
        return conn

    def save_one(self, news: dict, cursor, connection, table: str) -> bool:
        """처리 완료된 기사 1건을 즉시 삽입 (원본과 동일한 인터페이스)"""
        insert_sql = f"""
            INSERT OR IGNORE INTO {table}
                (link, category, title, summary, sentiment,
                 pub_date, clickbait_prob, article_type, type_prob)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """
        try:
            if news['summary'] != '알수없음':
                result = cursor.execute(insert_sql, (
                    news['link'],    news['category'],  news['title'],
                    news['summary'], news['sentiment'], news['date'],
                    str(news['clickbait_prob']),
                    news['article_type'], str(news['type_prob'])
                ))
                connection.commit()
                return result.rowcount > 0  # 신규 삽입이면 True
            return False
        except Exception as e:
            print(f"  삽입 에러 [{news['title'][:10]}]: {e}")
            return False

    def get_stats(self) -> None:
        """섹터별 저장 현황 출력"""
        conn = sqlite3.connect(self.db_path)
        cursor = conn.cursor()
        cursor.execute("""
            SELECT category, COUNT(*) as cnt
            FROM news_data
            GROUP BY category
            ORDER BY cnt DESC
        """)
        rows = cursor.fetchall()
        conn.close()
        print("\n📊 DB 저장 현황:")
        for row in rows:
            print(f"  {row[0]}: {row[1]}건")


# ================================================================
# [RSS 피드 수집] ← 네이버 API 대체
# ================================================================
def fetch_rss_articles(days_back: int = 90) -> list[dict]:
    """
    RSS 피드에서 기사 수집
    - 네이버 API(최신만) 대신 RSS로 과거 데이터 수집 가능
    - days_back: 며칠 전까지 수집 (기본 90일)
    """
    cutoff    = datetime.now() - timedelta(days=days_back)
    articles  = []
    seen_links = set()

    for source_name, rss_url in RSS_FEEDS:
        try:
            feed  = feedparser.parse(rss_url)
            count = 0
            for entry in feed.entries:
                link = entry.get('link', '')
                if not link or link in seen_links:
                    continue

                # 날짜 파싱
                pub = entry.get('published_parsed')
                if pub:
                    pub_date = datetime(*pub[:6])
                    if pub_date < cutoff:
                        continue
                    date_str = pub_date.strftime("%Y-%m-%d %H:%M")
                else:
                    date_str = datetime.now().strftime("%Y-%m-%d %H:%M")

                # HTML 태그 제거
                title   = re.sub(r'<[^>]+>', '', entry.get('title',   '').strip())
                summary = re.sub(r'<[^>]+>', '', entry.get('summary', '').strip())

                articles.append({
                    'title':   title,
                    'link':    link,
                    'date':    date_str,
                    'summary': summary,
                    'source':  source_name,
                })
                seen_links.add(link)
                count += 1

            print(f"  [{source_name}] {count}개 수집")

        except Exception as e:
            print(f"  RSS 오류 [{source_name}]: {e}")

    print(f"RSS 총 수집: {len(articles)}건")
    return articles


# ================================================================
# [비동기 파이프라인] ← 네이버 API → RSS로 변경
# ================================================================
async def run_pipeline_async(
    sector_config: dict,
    verify_fn,                      # 미사용 (하위 호환용으로 유지)
    db_table: str       = "news_data",
    days_back: int      = 90,
    max_per_sector: int = 500,
    fetch_concurrency: int = 20,
    groq_concurrency: int  = 10,
    groq_api_keys: str | list = None,
) -> int:
    """
    전체 뉴스 수집 파이프라인 (B안: category 직접 사용)
    - fetch_gnews_articles()가 이미 QUERY_SECTOR_MAP으로 category를 붙여서 반환
    - 섹터 루프 없이 기사의 category 그대로 저장
    - seen_links로 중복 제거 (링크당 1회만 처리)
    반환값: DB 신규 삽입 건수
    """
    mm   = ModelManager()
    proc = ArticleProcessor(mm)
    groq = GroqAnalyzer(api_keys=groq_api_keys)
    db   = DatabaseManager()

    db_executor  = ThreadPoolExecutor(max_workers=1)
    cpu_executor = ThreadPoolExecutor(max_workers=4)

    fetch_sem    = asyncio.Semaphore(fetch_concurrency)
    groq_sem     = asyncio.Semaphore(groq_concurrency)
    result_queue = asyncio.Queue()

    seen_links    = set()
    seen_lock     = asyncio.Lock()
    # 섹터별 카운트 (max_per_sector 제한용)
    sector_counts = {}
    sector_lock   = asyncio.Lock()
    loop          = asyncio.get_event_loop()

    # ── DB writer ──
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

    # ── 기사 처리 (섹터 루프 없음 — category는 article에서 직접 가져옴) ──
    async def process_article(article: dict, session):
        link     = article['link']
        title    = article['title']
        category = article.get('category', '기타')

        # 링크 중복 체크
        async with seen_lock:
            if link in seen_links:
                return
            seen_links.add(link)

        # 섹터별 최대 건수 초과 체크
        async with sector_lock:
            cnt = sector_counts.get(category, 0)
            if cnt >= max_per_sector:
                return
            sector_counts[category] = cnt + 1

        # 본문 수집
        async with fetch_sem:
            full_text = await proc.fetch_content(session, link)
        if not full_text:
            full_text = article.get('summary', '')
        if not full_text:
            return

        # 공통 필터 (EXCLUDE_WORDS / TITLE_EXCLUDE_WORDS) — okt 없이 빠르게
        for bad in TITLE_EXCLUDE_WORDS:
            if bad in title:
                return
        for bad in EXCLUDE_WORDS:
            if bad in title or bad in full_text:
                return

        # 과장성 (TF-IDF)
        clickbait_prob = proc.predict_clickbait(title, full_text)

        # BERT 유형 분류
        article_type, type_prob = "알 수 없음", 0.0
        if mm.bert_model:
            chunks = proc.clean_and_split(full_text)
            article_type, type_prob = await loop.run_in_executor(
                cpu_executor, proc.predict_type, chunks
            )

        # Groq 요약/감성
        async with groq_sem:
            await asyncio.sleep(0.3)
            groq_result = await groq.analyze(title, full_text)

        news_obj = {
            "category":       category,
            "title":          title,
            "summary":        groq_result.get("summary",   "요약 없음"),
            "sentiment":      groq_result.get("sentiment", "중립"),
            "link":           link,
            "date":           article['date'],
            "clickbait_prob": clickbait_prob,
            "article_type":   article_type,
            "type_prob":      type_prob,
        }

        print(
            f"   ✅ [{article['date']}] {title[:20]}...\n"
            f"      [{category}] 감성: {news_obj['sentiment']} | "
            f"유형: {article_type}({type_prob:.1f}%) | "
            f"과장성: {clickbait_prob:.1f}%\n"
            f"      요약: {news_obj['summary']}\n"
        )

        await result_queue.put(news_obj)

    # ── 실행 ──
    try:
        connection = db.connect()
        cursor     = connection.cursor()
        print(f"✅ SQLite DB 연결 성공! (테이블: {db_table})")
    except Exception as e:
        print(f"❌ DB 연결 실패: {e}")
        return 0

    print(f"\n📡 GoogleNews + RSS 수집 중...")
    all_articles = fetch_gnews_articles(max_results=100)
    print(f"   총 후보: {len(all_articles)}건")

    async with aiohttp.ClientSession() as session:
        writer_task = asyncio.create_task(db_writer(connection, cursor))

        # 섹터 루프 없이 기사 단위로 바로 처리
        all_tasks = [process_article(article, session) for article in all_articles]
        await asyncio.gather(*all_tasks)

        await result_queue.put(None)
        success, duplicate = await writer_task

    cursor.close()
    connection.close()
    db_executor.shutdown(wait=False)
    cpu_executor.shutdown(wait=False)

    db.get_stats()

    total = sum(sector_counts.values())
    print(f"\n✅ [{db_table}] 완료 — 처리: {total}개 | 신규: {success}개 | 중복: {duplicate}개")
    return success
