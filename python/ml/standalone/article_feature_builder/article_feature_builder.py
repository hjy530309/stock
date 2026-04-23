"""
article_feature_builder.py
───────────────────────────────────────────────────────────────
【역할】 기사(Article) 한 건 단위로 ML 피처를 만드는 전처리 스크립트.

    feature_builder.py가 '하루치 뉴스를 집계'한다면,
    이 파일은 '기사 한 건이 주가에 미친 영향'을 추적한다.
    → 더 세밀한 '기사 영향 모델(article_train_model.py)' 학습에 사용.

【입력】
  - oracle_company.csv / oracle_sector.csv : Oracle 뉴스 CSV
  - news_data.db                           : SQLite 뉴스 DB

【출력】
  - article_features.csv : 기사 1건 = 1행. 기사 자체 피처 + 최근 3일
                           맥락 피처 + 주가 지표 + 레이블이 담긴 데이터셋.
                           → article_train_model.py 의 입력으로 사용.

【feature_builder.py와의 차이점】
  feature_builder.py  : 일(日)별 집계 → 종목×날짜 = 1행
  article_feature_builder.py : 기사 1건 = 1행 (더 세분화)
                               → 기사 발행 시각(장 전/장 중/장 후)도 반영

【왜 필요한가?】
  뉴스가 발행된 시각, 제목 길이, 최근 며칠간의 뉴스 분위기 등
  일별 집계로는 소실되는 정보를 보존하여 더 정밀한 예측 모델을 만들 수 있음.

【사용법】
    python article_feature_builder.py
    python article_feature_builder.py --sqlite news_data.db --out article_features.csv
    python article_feature_builder.py --lookback-days 3 --label-threshold 0.003
"""

from __future__ import annotations

import argparse
import sqlite3
from bisect import bisect_left       # 정렬된 리스트에서 이진 탐색 (날짜 찾기)
from datetime import timedelta
from pathlib import Path
from typing import Iterable

import numpy as np
import pandas as pd

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR.mkdir(parents=True, exist_ok=True)
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# PyCharm에서 별도로 보관/실행할 때 사용할 기본 경로입니다.
DEFAULT_ORACLE_COMPANY_CSV = str(DATA_DIR / "oracle_company.csv")
DEFAULT_ORACLE_SECTOR_CSV = str(DATA_DIR / "oracle_sector.csv")
DEFAULT_SQLITE_DB = str(DATA_DIR / "news_data.db")
DEFAULT_ARTICLE_FEATURES_CSV = str(DATA_DIR / "article_features.csv")

try:
    import FinanceDataReader as fdr  # 한국 주가 데이터 수집 라이브러리
except ImportError as exc:
    raise SystemExit(
        "FinanceDataReader is required. Install it with: pip install finance-datareader"
    ) from exc


# ── 카테고리 → 종목코드 매핑 ─────────────────────────────────
# 뉴스 카테고리의 다양한 표기를 종목코드로 통일
CATEGORY_TO_CODE = {
    "반도체/AI": "005930",                          # 삼성전자
    "IT/반도체": "005930",
    "삼성전자 (IT/반도체)": "005930",
    "2차전지": "373220",                            # LG에너지솔루션
    "LG에너지솔루션 (2차전지)": "373220",
    "바이오": "207940",                             # 삼성바이오로직스
    "제약/바이오": "207940",
    "삼성바이오로직스 (제약/바이오)": "207940",
    "IT/플랫폼": "035420",                          # 네이버
    "네이버 (IT/플랫폼)": "035420",
    "자동차/모빌리티": "005380",                    # 현대차
    "현대차 (자동차/모빌리티)": "005380",
    "방산": "012450",                               # 한화에어로스페이스
    "방산/우주항공": "012450",
    "한화에어로스페이스 (방산/우주항공)": "012450",
    "엔터/미디어": "352820",                        # 하이브
    "하이브 (엔터/미디어)": "352820",
    "금융/밸류업": "105560",                        # KB금융
    "KB금융 (금융/밸류업)": "105560",
}

# 카테고리 표기 정규화 (여러 이름 → 하나의 대표 이름)
CATEGORY_NORM = {
    "반도체/AI": "반도체/AI",
    "IT/반도체": "반도체/AI",
    "삼성전자 (IT/반도체)": "반도체/AI",
    "2차전지": "2차전지",
    "LG에너지솔루션 (2차전지)": "2차전지",
    "바이오": "제약/바이오",
    "제약/바이오": "제약/바이오",
    "삼성바이오로직스 (제약/바이오)": "제약/바이오",
    "IT/플랫폼": "IT/플랫폼",
    "네이버 (IT/플랫폼)": "IT/플랫폼",
    "자동차/모빌리티": "자동차/모빌리티",
    "현대차 (자동차/모빌리티)": "자동차/모빌리티",
    "방산": "방산/우주항공",
    "방산/우주항공": "방산/우주항공",
    "한화에어로스페이스 (방산/우주항공)": "방산/우주항공",
    "엔터/미디어": "엔터/미디어",
    "하이브 (엔터/미디어)": "엔터/미디어",
    "금융/밸류업": "금융/밸류업",
    "KB금융 (금융/밸류업)": "금융/밸류업",
}

# 감성 레이블 → 숫자 변환 (호재=1, 중립=0, 악재=-1)
SENTIMENT_MAP = {"호재": 1, "중립": 0, "악재": -1}

# 장 운영 시간 상수 (한국 주식시장 기준)
MARKET_OPEN_HOUR  = 9          # 장 시작: 오전 9시
MARKET_CLOSE_HOUR = 15         # 장 마감: 오후 3시
MARKET_CLOSE_MINUTE = 30       # 장 마감: 3시 30분

# 주가 관련 피처 컬럼 목록 (기사 기준 시점의 주가 맥락 정보)
PRICE_FEATURE_COLS = [
    "close",           # 당일 종가
    "ma_5",            # 5일 이동평균
    "ma_20",           # 20일 이동평균
    "ma_60",           # 60일 이동평균
    "close_to_ma5",    # 종가 vs MA5 괴리율
    "close_to_ma20",   # 종가 vs MA20 괴리율
    "ma_cross",        # 골든/데드크로스 방향
    "rsi_14",          # RSI(14일)
    "rsi_zone",        # RSI 구간(-1/0/1)
    "macd",            # MACD 라인
    "macd_sig",        # MACD Signal 라인
    "macd_hist",       # MACD Histogram
    "macd_cross",      # MACD 크로스 방향
    "bb_pctb",         # 볼린저밴드 %B
    "volatility_5d",   # 5일 변동성
    "volatility_20d",  # 20일 변동성
    "volume_ratio",    # 거래량 비율
    "return_before_1d", # 어제 수익률
    "return_before_3d", # 3일 전 대비 수익률
    "return_before_5d", # 5일 전 대비 수익률
]


def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    """컬럼명을 소문자 + 앞뒤 공백 제거로 정규화."""
    df = df.copy()
    df.columns = [str(col).strip().lower() for col in df.columns]
    return df


def normalize_stock_code(value: object) -> str | None:
    """
    종목코드를 6자리 문자열로 정규화.
    예) 5930 → "005930", "5930.0" → "005930"
    """
    if pd.isna(value):
        return None
    text = str(value).strip()
    if not text:
        return None
    if text.endswith(".0"):           # float 변환 부산물 제거
        text = text[:-2]
    digits = "".join(ch for ch in text if ch.isdigit())
    if digits:
        return digits.zfill(6)        # 0으로 6자리 패딩
    return text


def load_source_csv(path: str) -> pd.DataFrame:
    """CSV 파일 로드. 파일 없으면 빈 DataFrame 반환."""
    if not Path(path).exists():
        print(f"  [skip] missing file: {path}")
        return pd.DataFrame()
    df = pd.read_csv(path)
    df = normalize_columns(df)
    print(f"  [csv] {path}: {len(df)} rows")
    return df


def load_source_sqlite(path: str) -> pd.DataFrame:
    """SQLite DB에서 news_data 테이블 로드. 파일 없으면 빈 DataFrame 반환."""
    if not Path(path).exists():
        print(f"  [skip] missing file: {path}")
        return pd.DataFrame()
    with sqlite3.connect(path) as conn:
        df = pd.read_sql("SELECT * FROM news_data", conn)
    df = normalize_columns(df)
    print(f"  [sqlite] {path}: {len(df)} rows")
    return df


# ================================================================
# 【1단계】 뉴스 데이터 로드 + 통합
#
# 역할: Oracle CSV 2개 + SQLite 1개를 합쳐 하나의 뉴스 DataFrame을 만든다.
#       feature_builder.py의 load_news()와 동일 목적이지만
#       컬럼 유효성 검사 + 결측값 처리가 더 엄격하다.
#
# 결과: 기사 1건 = 1행인 뉴스 DataFrame (정렬: 발행일 오름차순)
# ================================================================
def load_news(
    oracle_company_csv: str,
    oracle_sector_csv: str,
    sqlite_db: str,
) -> pd.DataFrame:
    parts = [
        load_source_csv(oracle_company_csv),
        load_source_csv(oracle_sector_csv),
        load_source_sqlite(sqlite_db),
    ]
    parts = [part for part in parts if not part.empty]
    if not parts:
        raise RuntimeError("No input news source was found.")

    df = pd.concat(parts, ignore_index=True)
    df = df.drop_duplicates(subset="link")  # 동일 URL 중복 제거

    # 필수 컬럼이 없으면 NaN으로 채워서 추가 (방어적 처리)
    for column in [
        "link", "category", "title", "summary", "sentiment",
        "pub_date", "clickbait_prob", "article_type", "type_prob",
    ]:
        if column not in df.columns:
            df[column] = np.nan

    # 카테고리 → 종목코드 매핑
    df["category"]      = df["category"].astype(str).str.strip()
    df["stock_code"]    = df["category"].map(CATEGORY_TO_CODE)
    df["category_norm"] = df["category"].map(CATEGORY_NORM)

    # 종목코드 매핑 실패 행 제거 (ex: '기타' 카테고리)
    before = len(df)
    df = df[df["stock_code"].notna()].copy()
    print(f"  mapped categories: {len(df)} / {before} rows")

    # 날짜 파싱 + 오름차순 정렬
    df["pub_date"] = pd.to_datetime(df["pub_date"], errors="coerce")
    df = df.dropna(subset=["pub_date"]).sort_values("pub_date").reset_index(drop=True)

    # 결측값 기본값 처리
    df["stock_code"]    = df["stock_code"].map(normalize_stock_code)
    df["title"]         = df["title"].fillna("").astype(str)
    df["summary"]       = df["summary"].fillna("").astype(str)
    df["sentiment"]     = df["sentiment"].fillna("중립").astype(str)
    df["article_type"]  = df["article_type"].fillna("").astype(str)
    df["clickbait_prob"]= pd.to_numeric(df["clickbait_prob"], errors="coerce").fillna(0.0)
    df["type_prob"]     = pd.to_numeric(df["type_prob"],      errors="coerce").fillna(0.0)

    return df


# ================================================================
# 【2단계】 기사 자체 피처 추가 (enrich)
#
# 역할: 기사 한 건에서 추출할 수 있는 수치 피처를 생성한다.
#       일별 집계 없이 기사 단위에서 뽑을 수 있는 정보들.
#
# 추가되는 피처:
#   - sentiment_score : 감성 수치 (1/0/-1)
#   - is_fact/predict/reasoning : 기사 유형 원-핫
#   - title_length / summary_length : 제목/요약 글자 수
#   - title_word_count / summary_word_count : 단어 수
#   - has_summary : 요약 존재 여부
#   - pub_hour / pub_weekday : 발행 시각, 요일
#   - is_weekend : 주말 여부
#   - is_before_open : 장 시작 전 발행 여부 (09:00 이전)
#   - is_after_close : 장 마감 후 발행 여부 (15:30 이후)
#
# 왜 장 시각이 중요한가?
#   장 전 발행 뉴스는 당일 시가에 반영, 장 후는 다음날 반영.
#   이 차이가 주가 반응 타이밍에 영향을 줌.
# ================================================================
def enrich_article_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()

    # 감성 수치화
    df["sentiment_score"] = df["sentiment"].map(SENTIMENT_MAP).fillna(0).astype(float)

    # 기사 유형 → 원-핫 인코딩
    df["is_fact"]      = (df["article_type"] == "사실형").astype(int)
    df["is_predict"]   = (df["article_type"] == "예측형").astype(int)
    df["is_reasoning"] = (df["article_type"] == "추론형").astype(int)

    # 제목/요약 텍스트 길이 피처
    df["title_length"]        = df["title"].str.len()
    df["summary_length"]      = df["summary"].str.len()
    df["title_word_count"]    = df["title"].str.split().str.len().fillna(0)
    df["summary_word_count"]  = df["summary"].str.split().str.len().fillna(0)
    df["has_summary"]         = (df["summary_length"] > 0).astype(int)

    # 발행 시각 관련 피처
    df["pub_hour"]    = df["pub_date"].dt.hour
    df["pub_minute"]  = df["pub_date"].dt.minute
    df["pub_weekday"] = df["pub_date"].dt.weekday  # 0=월요일, 6=일요일
    df["is_weekend"]  = (df["pub_weekday"] >= 5).astype(int)

    # 장 시작 전 발행: 9시 이전 또는 정각 9시
    df["is_before_open"] = (
        (df["pub_hour"] < MARKET_OPEN_HOUR)
        | ((df["pub_hour"] == MARKET_OPEN_HOUR) & (df["pub_minute"] == 0))
    ).astype(int)

    # 장 마감 후 발행: 15시 30분 이후
    df["is_after_close"] = (
        (df["pub_hour"] > MARKET_CLOSE_HOUR)
        | (
            (df["pub_hour"] == MARKET_CLOSE_HOUR)
            & (df["pub_minute"] >= MARKET_CLOSE_MINUTE)
        )
    ).astype(int)

    return df


# ================================================================
# 【3단계】 최근 맥락 피처 생성 (슬라이딩 윈도우)
#
# 역할: 각 기사가 발행되기 직전 N일 동안의 뉴스 흐름을 요약한
#       '맥락 피처'를 생성한다.
#
# 왜 필요한가?
#   기사 1건만 보면 그 기사가 돌발적인지 연속적인지 알 수 없음.
#   → "최근 3일간 부정 뉴스가 쌓인 상태에서 또 부정 기사 발행"이면
#     하락 영향이 더 클 수 있음.
#   이런 맥락 정보를 수치화하는 것이 목적.
#
# 생성 피처 (접미사: _3d = 최근 3일):
#   - recent_article_count_3d     : 최근 3일 기사 수
#   - recent_sentiment_mean_3d    : 최근 감성 평균
#   - recent_sentiment_sum_3d     : 최근 감성 합계
#   - recent_positive_ratio_3d    : 최근 호재 비율
#   - recent_negative_ratio_3d    : 최근 악재 비율
#   - recent_sentiment_intensity_3d : 최근 감성 강도(호재-악재 비율)
#   - recent_clickbait_mean_3d    : 최근 클릭베이트 평균
#   - recent_type_prob_mean_3d    : 최근 유형확률 평균
#   - recent_fact/predict/reasoning_ratio_3d : 유형별 비율
#   - hours_since_prev_article    : 직전 기사와의 시간 간격(시간)
#
# 구현 방식: 이진 탐색(bisect)으로 O(N log N) 효율화
# ================================================================
def build_recent_context(df: pd.DataFrame, lookback_days: int) -> pd.DataFrame:
    lookback_ns = pd.Timedelta(days=lookback_days).value  # 나노초 단위 변환
    chunks: list[pd.DataFrame] = []

    for _, group in df.sort_values("pub_date").groupby("stock_code", sort=False):
        group = group.sort_values("pub_date").copy()

        # 이진 탐색을 위해 타임스탬프를 정수(나노초)로 변환
        timestamps   = group["pub_date"].astype("int64").to_numpy()
        sentiments   = group["sentiment_score"].to_numpy()
        clickbait    = group["clickbait_prob"].to_numpy()
        type_prob    = group["type_prob"].to_numpy()
        is_fact      = group["is_fact"].to_numpy()
        is_predict   = group["is_predict"].to_numpy()
        is_reasoning = group["is_reasoning"].to_numpy()

        rows = []
        left = 0  # 슬라이딩 윈도우의 왼쪽 경계

        for i in range(len(group)):
            cutoff = timestamps[i] - lookback_ns  # i번째 기사 기준 N일 전 시각
            # 윈도우 왼쪽 경계를 cutoff보다 오래된 기사는 제외
            while left < i and timestamps[left] < cutoff:
                left += 1

            # 현재 기사(i)는 제외하고 직전 기사들만 맥락으로 사용
            start = left
            end   = i  # i 미포함

            if end <= start:
                # 윈도우 내 이전 기사 없음 → 기본값
                rows.append({
                    "recent_article_count_3d":      0,
                    "recent_sentiment_mean_3d":      0.0,
                    "recent_sentiment_sum_3d":       0.0,
                    "recent_positive_ratio_3d":      0.0,
                    "recent_negative_ratio_3d":      0.0,
                    "recent_sentiment_intensity_3d": 0.0,
                    "recent_clickbait_mean_3d":      0.0,
                    "recent_type_prob_mean_3d":      0.0,
                    "recent_fact_ratio_3d":          0.0,
                    "recent_predict_ratio_3d":       0.0,
                    "recent_reasoning_ratio_3d":     0.0,
                    "hours_since_prev_article":      float(lookback_days * 24),
                })
            else:
                window_s = sentiments[start:end]
                n        = len(window_s)
                pos_r    = float((window_s == 1).sum()  / n)
                neg_r    = float((window_s == -1).sum() / n)

                # 직전 기사와의 시간 간격 (시간 단위)
                hours_gap = float(
                    (timestamps[i] - timestamps[end - 1]) / 1e9 / 3600
                )

                rows.append({
                    "recent_article_count_3d":      n,
                    "recent_sentiment_mean_3d":      float(window_s.mean()),
                    "recent_sentiment_sum_3d":       float(window_s.sum()),
                    "recent_positive_ratio_3d":      pos_r,
                    "recent_negative_ratio_3d":      neg_r,
                    "recent_sentiment_intensity_3d": pos_r - neg_r,
                    "recent_clickbait_mean_3d":      float(clickbait[start:end].mean()),
                    "recent_type_prob_mean_3d":      float(type_prob[start:end].mean()),
                    "recent_fact_ratio_3d":          float(is_fact[start:end].mean()),
                    "recent_predict_ratio_3d":       float(is_predict[start:end].mean()),
                    "recent_reasoning_ratio_3d":     float(is_reasoning[start:end].mean()),
                    "hours_since_prev_article":      max(0.0, hours_gap),
                })

        chunks.append(pd.DataFrame(rows, index=group.index))

    return pd.concat(chunks).sort_index()


# ================================================================
# 【4단계】 주가 프레임 수집
#
# 역할: 종목별로 주가 + 기술적 지표를 담은 DataFrame을 딕셔너리로 반환.
#       {종목코드: 해당 종목 일별 주가 DataFrame}
#
# feature_builder.py와 다른 점:
#   레이블(미래 수익률)을 여기서 계산하지 않고,
#   build_article_dataset()에서 기사 발행 시각 기준으로 정밀 계산.
#   → 장 전/후 발행 여부에 따라 '반영 기준일(anchor)'이 달라지기 때문.
# ================================================================
def fetch_price_frames(
    stock_codes: list[str],
    start_date: pd.Timestamp,
    end_date: pd.Timestamp,
) -> dict[str, pd.DataFrame]:
    """
    기사 날짜 범위의 주가 데이터를 수집한다.
    MA60 워밍업을 위해 start_date보다 160일 앞서서 수집.
    """
    fetch_start = (start_date - timedelta(days=160)).strftime("%Y-%m-%d")
    fetch_end   = (end_date   + timedelta(days=14)).strftime("%Y-%m-%d")
    frames: dict[str, pd.DataFrame] = {}

    for code in stock_codes:
        print(f"  fetching price: {code}")
        try:
            raw = fdr.DataReader(code, fetch_start, fetch_end)
            if raw is None or raw.empty:
                print(f"  [warn] no data for {code}")
                continue

            raw.index = pd.to_datetime(raw.index).normalize()  # 시간 제거
            close  = raw["Close"].astype(float)
            volume = (
                raw["Volume"].astype(float)
                if "Volume" in raw.columns
                else pd.Series(np.nan, index=raw.index)
            )

            price = pd.DataFrame(index=raw.index)
            price["stock_code"] = code
            price["close"]      = close

            # ── 이동평균 ─────────────────────────────────────────
            price["ma_5"]  = close.rolling(5).mean()
            price["ma_20"] = close.rolling(20).mean()
            price["ma_60"] = close.rolling(60).mean()
            # 현재가 vs 이동평균 괴리율 (비율)
            price["close_to_ma5"]  = (close - price["ma_5"])  / price["ma_5"]
            price["close_to_ma20"] = (close - price["ma_20"]) / price["ma_20"]
            # 골든/데드크로스: MA5 > MA20 이면 +1(상승추세), 반대 -1
            price["ma_cross"] = np.sign(price["ma_5"] - price["ma_20"])

            # ── RSI ──────────────────────────────────────────────
            delta  = close.diff()
            gain   = delta.clip(lower=0).rolling(14).mean()
            loss   = (-delta.clip(upper=0)).rolling(14).mean()
            rs     = gain / loss.replace(0, np.nan)
            price["rsi_14"]   = 100 - (100 / (1 + rs))
            price["rsi_zone"] = np.sign(price["rsi_14"] - 50.0)  # +1=과매수, -1=과매도

            # ── MACD ─────────────────────────────────────────────
            ema12  = close.ewm(span=12, adjust=False).mean()
            ema26  = close.ewm(span=26, adjust=False).mean()
            macd   = ema12 - ema26
            sig    = macd.ewm(span=9,  adjust=False).mean()
            hist   = macd - sig
            price["macd"]      = macd
            price["macd_sig"]  = sig
            price["macd_hist"] = hist
            price["macd_cross"]= np.sign(hist)  # +1=매수, -1=매도 신호

            # ── 볼린저밴드 %B ────────────────────────────────────
            ma20   = close.rolling(20).mean()
            std20  = close.rolling(20).std()
            lower  = ma20 - 2 * std20
            width  = (4 * std20).replace(0, np.nan)
            price["bb_pctb"] = (close - lower) / width

            # ── 변동성 ───────────────────────────────────────────
            pct    = close.pct_change()
            price["volatility_5d"]  = pct.rolling(5).std()
            price["volatility_20d"] = pct.rolling(20).std()

            # ── 거래량 비율 ──────────────────────────────────────
            vol_ma5 = volume.rolling(5).mean()
            price["volume_ratio"] = volume / vol_ma5.replace(0, np.nan)

            # ── 과거 수익률 (미래 데이터 유출 없음) ─────────────
            price["return_before_1d"] = pct              # 전일 대비
            price["return_before_3d"] = close.pct_change(3)
            price["return_before_5d"] = close.pct_change(5)

            price.index.name = "trade_date"
            price = price.reset_index()
            price["trade_date"] = pd.to_datetime(price["trade_date"]).dt.normalize()
            price["stock_code"] = price["stock_code"].map(normalize_stock_code)
            frames[code] = price

        except Exception as exc:
            print(f"  [warn] {code}: {exc}")

    if not frames:
        raise RuntimeError("No price data could be downloaded.")

    return frames


# ================================================================
# 【보조 함수】 기사 발행 시각 → 반영 기준 거래일 결정
#
# 역할: 기사가 발행된 시각에 따라 주가에 실제 반영되는 날짜를 결정.
#
# 로직:
#   - 장 중(9:00~15:30) 발행 → 당일이 anchor (기준일)
#   - 장 후(15:30 이후) 발행 → 다음 거래일이 anchor
#   - 주말/공휴일 발행 → 다음 거래일이 anchor
#
# 왜 필요한가?
#   장 후에 발표된 뉴스는 당일 주가에 반영되지 않으므로,
#   레이블을 다음날 기준으로 계산해야 정확함.
#
# 반환:
#   anchor_idx  : 뉴스가 실제 반영될 거래일의 인덱스
#   context_idx : anchor 전날(기사 발행 시점의 가장 최근 주가) 인덱스
# ================================================================
def resolve_trade_window(
    trade_dates: list[pd.Timestamp],
    pub_timestamp: pd.Timestamp,
    is_after_close: bool,
) -> tuple[int | None, int | None]:
    pub_day = pub_timestamp.normalize()  # 발행 날짜 (시간 제거)
    pos = bisect_left(trade_dates, pub_day)  # 이진 탐색으로 날짜 위치 찾기
    same_day = pos < len(trade_dates) and trade_dates[pos] == pub_day

    if same_day and not is_after_close:
        anchor_idx = pos       # 장 중 발행 → 당일 반영
    elif same_day:
        anchor_idx = pos + 1  # 장 후 발행 → 다음 거래일 반영
    else:
        anchor_idx = pos       # 비거래일 발행 → 다음 거래일 반영

    context_idx = anchor_idx - 1  # anchor 전날 (피처 추출에 사용할 시점)
    if anchor_idx >= len(trade_dates) or context_idx < 0:
        return None, None
    return anchor_idx, context_idx


# ================================================================
# 【5단계】 기사 × 주가 결합 → 최종 데이터셋 생성
#
# 역할: 기사 1건마다 주가 맥락(피처)과 미래 수익률(레이블)을 붙인다.
#
# 처리 흐름 (기사 1건 기준):
#   1. 종목의 주가 프레임 조회
#   2. 기사 발행 시각 → 반영 기준일(anchor) 결정
#   3. anchor 전날 주가 지표를 피처로 추출
#   4. anchor부터 1/3/5일 후 수익률 계산 → 레이블 생성
#   5. 기사 피처 + 맥락 피처 + 주가 피처 + 레이블 합치기
#
# label_threshold:
#   기본값 0.0 = 1원이라도 오르면 상승(1)
#   0.003으로 설정 시 0.3% 이상 올라야 상승(1) → 더 엄격한 레이블
#
# 결과: 기사 1건 = 1행인 학습 데이터셋
# ================================================================
def build_article_dataset(
    articles: pd.DataFrame,
    price_frames: dict[str, pd.DataFrame],
    label_threshold: float,
) -> pd.DataFrame:
    rows = []

    for article in articles.itertuples(index=False):
        # 해당 종목의 주가 프레임 조회
        price_df = price_frames.get(article.stock_code)
        if price_df is None or price_df.empty:
            continue

        trade_dates = price_df["trade_date"].tolist()

        # 기사 발행 시각 → 반영 기준일(anchor) + 피처 추출 기준일(context) 결정
        anchor_idx, context_idx = resolve_trade_window(
            trade_dates=trade_dates,
            pub_timestamp=article.pub_date,
            is_after_close=bool(article.is_after_close),
        )
        if anchor_idx is None or context_idx is None:
            continue  # 범위 밖이면 스킵

        # context 날의 주가 데이터 (= 기사 발행 당시에 알 수 있는 가장 최근 주가)
        context_row = price_df.iloc[context_idx]
        base_close  = float(context_row["close"])
        if not np.isfinite(base_close) or base_close == 0:
            continue  # 주가 데이터 이상 → 스킵

        anchor_date  = trade_dates[anchor_idx]
        context_date = trade_dates[context_idx]

        # 기사 기본 정보 + 피처 + 맥락 피처를 하나의 레코드로 합침
        record = {
            "link":              article.link,
            "stock_code":        article.stock_code,
            "category":          article.category,
            "category_norm":     article.category_norm,
            "title":             article.title,
            "summary":           article.summary,
            "pub_date":          article.pub_date,
            "context_trade_date": context_date,  # 피처 추출 기준일
            "anchor_trade_date":  anchor_date,   # 레이블 기산일
            "anchor_delay_days":  int((anchor_date - article.pub_date.normalize()).days),
            "sentiment":          article.sentiment,
            "sentiment_score":    article.sentiment_score,
            "clickbait_prob":     article.clickbait_prob,
            "article_type":       article.article_type,
            "type_prob":          article.type_prob,
            "is_fact":            article.is_fact,
            "is_predict":         article.is_predict,
            "is_reasoning":       article.is_reasoning,
            "title_length":       article.title_length,
            "summary_length":     article.summary_length,
            "title_word_count":   article.title_word_count,
            "summary_word_count": article.summary_word_count,
            "has_summary":        article.has_summary,
            "pub_hour":           article.pub_hour,
            "pub_weekday":        article.pub_weekday,
            "is_weekend":         article.is_weekend,
            "is_before_open":     article.is_before_open,
            "is_after_close":     article.is_after_close,
            # 최근 3일 맥락 피처
            "recent_article_count_3d":      article.recent_article_count_3d,
            "recent_sentiment_mean_3d":      article.recent_sentiment_mean_3d,
            "recent_sentiment_sum_3d":       article.recent_sentiment_sum_3d,
            "recent_positive_ratio_3d":      article.recent_positive_ratio_3d,
            "recent_negative_ratio_3d":      article.recent_negative_ratio_3d,
            "recent_sentiment_intensity_3d": article.recent_sentiment_intensity_3d,
            "recent_clickbait_mean_3d":      article.recent_clickbait_mean_3d,
            "recent_type_prob_mean_3d":      article.recent_type_prob_mean_3d,
            "recent_fact_ratio_3d":          article.recent_fact_ratio_3d,
            "recent_predict_ratio_3d":       article.recent_predict_ratio_3d,
            "recent_reasoning_ratio_3d":     article.recent_reasoning_ratio_3d,
            "hours_since_prev_article":      article.hours_since_prev_article,
        }

        # context 날의 주가 지표 피처 추가
        for column in PRICE_FEATURE_COLS:
            record[column] = context_row.get(column, np.nan)

        # ── 레이블 계산 (1일/3일/5일 후 수익률) ──────────────────
        for horizon in (1, 3, 5):
            # anchor 기준 horizon일 후 (anchor+0 = anchor 당일)
            target_idx = anchor_idx + (horizon - 1)
            if target_idx >= len(trade_dates):
                # 미래 데이터 없음 (가장 최근 기사)
                record[f"return_{horizon}d"] = np.nan
                record[f"label_{horizon}d"]  = np.nan
                continue

            close_after = float(price_df.iloc[target_idx]["close"])
            ret = close_after / base_close - 1.0  # 수익률
            record[f"return_{horizon}d"] = ret
            # label_threshold 이상이면 상승(1), 미만이면 하락(0)
            record[f"label_{horizon}d"]  = int(ret > label_threshold)

        rows.append(record)

    if not rows:
        raise RuntimeError("No article-level rows could be created after joining prices.")

    dataset = pd.DataFrame(rows).sort_values("pub_date").reset_index(drop=True)
    return dataset


# ================================================================
# 【메인 실행 함수】
#
# 전체 파이프라인 순서:
#   1. 뉴스 로드
#   2. 기사 피처 + 맥락 피처 생성
#   3. 주가 프레임 수집
#   4. 기사 × 주가 결합 → 최종 데이터셋
#   5. article_features.csv 저장
# ================================================================
def main(args: argparse.Namespace) -> None:
    print("=" * 60)
    print("[1/4] Load analyzed news data")
    print("=" * 60)
    articles = load_news(
        oracle_company_csv=args.oracle_company,
        oracle_sector_csv=args.oracle_sector,
        sqlite_db=args.sqlite,
    )

    print("\n" + "=" * 60)
    print("[2/4] Build article and context features")
    print("=" * 60)
    articles = enrich_article_features(articles)   # 기사 단위 피처 추가
    context  = build_recent_context(articles, lookback_days=args.lookback_days)  # 맥락 피처
    articles = pd.concat([articles, context], axis=1)  # 기사 피처 + 맥락 피처 합치기
    print(f"  article rows: {len(articles)}")

    start_date = articles["pub_date"].min().normalize()
    end_date   = articles["pub_date"].max().normalize()

    print("\n" + "=" * 60)
    print("[3/4] Fetch price context")
    print("=" * 60)
    price_frames = fetch_price_frames(
        stock_codes=articles["stock_code"].dropna().unique().tolist(),
        start_date=start_date,
        end_date=end_date,
    )

    print("\n" + "=" * 60)
    print("[4/4] Join article rows with price targets")
    print("=" * 60)
    dataset = build_article_dataset(
        articles=articles,
        price_frames=price_frames,
        label_threshold=args.label_threshold,
    )

    # 출력 컬럼 순서 지정
    ordered_columns = [
        "link", "stock_code", "category", "category_norm",
        "title", "summary", "pub_date",
        "context_trade_date", "anchor_trade_date", "anchor_delay_days",
        "sentiment", "sentiment_score", "clickbait_prob",
        "article_type", "type_prob",
        "is_fact", "is_predict", "is_reasoning",
        "title_length", "summary_length",
        "title_word_count", "summary_word_count", "has_summary",
        "pub_hour", "pub_weekday", "is_weekend",
        "is_before_open", "is_after_close",
        "recent_article_count_3d", "recent_sentiment_mean_3d",
        "recent_sentiment_sum_3d", "recent_positive_ratio_3d",
        "recent_negative_ratio_3d", "recent_sentiment_intensity_3d",
        "recent_clickbait_mean_3d", "recent_type_prob_mean_3d",
        "recent_fact_ratio_3d", "recent_predict_ratio_3d",
        "recent_reasoning_ratio_3d", "hours_since_prev_article",
        *PRICE_FEATURE_COLS,          # 주가 기술 지표 컬럼들
        "return_1d", "return_3d", "return_5d",    # 실제 수익률
        "label_1d", "label_3d", "label_5d",       # 레이블(상승=1/하락=0)
    ]
    dataset = dataset[[col for col in ordered_columns if col in dataset.columns]]
    output_path = Path(args.out)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    dataset.to_csv(output_path, index=False, encoding="utf-8-sig")

    print(f"  saved: {output_path}")
    print(f"  shape: {dataset.shape}")
    print("  label_1d distribution:")
    print(dataset["label_1d"].value_counts(dropna=False).to_string())
    print("  rows by stock:")
    print(dataset["stock_code"].value_counts().to_string())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--oracle-company", default=DEFAULT_ORACLE_COMPANY_CSV, help="기업별 뉴스 CSV 경로")
    parser.add_argument("--oracle-sector",  default=DEFAULT_ORACLE_SECTOR_CSV,  help="섹터별 뉴스 CSV 경로")
    parser.add_argument("--sqlite",         default=DEFAULT_SQLITE_DB,          help="SQLite DB 경로")
    parser.add_argument("--lookback-days",  type=int, default=3,            help="맥락 피처 룩백 기간(일)")
    parser.add_argument(
        "--label-threshold",
        type=float,
        default=0.0,
        help="상승 레이블 기준 수익률. 예: 0.003 = +0.3%% 이상이어야 상승(1)",
    )
    parser.add_argument("--out", default=DEFAULT_ARTICLE_FEATURES_CSV,      help="출력 피처 CSV 경로")
    main(parser.parse_args())
