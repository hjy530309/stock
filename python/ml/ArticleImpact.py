"""
ArticleImpact.py
───────────────────────────────────────────────────────────────
【역할】 article_lgbm_model.pkl을 로드하여 기사 1건의 주가 영향 점수를
        계산하고 JSON으로 출력.

【StockTrend.py와의 차이점】
  StockTrend.py    : "오늘 기사 전체 집계" → 내일 종목 방향 예측
  ArticleImpact.py : "기사 1건의 내용" → 해당 기사가 주가에 미칠 영향 점수 계산
                       → 기사 발행 시 실시간으로 호출하여 영향도 즉시 표시 가능

【입력 (커맨드라인 인자)】
  --code        : 종목코드 (예: 005930)
  --date        : 기사 발행 시각 (예: "2026-04-09 08:30")
  --sentiment   : 감성 레이블 (호재/중립/악재)
  --clickbait-prob : 클릭베이트 확률 (0~1)
  --type-prob   : 기사 유형 확률 (0~1)
  --article-type: 기사 유형 (사실형/예측형/추론형)
  --title       : 기사 제목
  --summary     : 기사 요약
  --sqlite, --oracle-company, --oracle-sector : 맥락 뉴스 데이터 소스

【출력 (JSON stdout)】
  {
    "stock_code": "005930",
    "date": "2026-04-09 08:30",
    "probability": 0.6541,
    "impact_percent": 30.82,    ← 주가 영향 점수 (-100 ~ +100)
    "confidence": "중간",
    "source": "기사 영향 모델"
  }

【impact_percent 해석】
  (prob - 0.5) * 200:
    prob=0.75 → impact=+50  (긍정적 영향 강함)
    prob=0.50 → impact=0    (영향 없음)
    prob=0.25 → impact=-50  (부정적 영향 강함)

【왜 필요한가?】
  뉴스 기사를 분석한 직후 "이 기사가 주가에 얼마나 영향을 줄까?"를
  실시간으로 점수화하여 사용자에게 즉각 피드백 제공.
  Spring 백엔드에서 뉴스 저장 → ArticleImpact.py 호출 → 결과 DB 저장
  → 프론트엔드 화면에 영향도 표시 하는 파이프라인.
"""

from __future__ import annotations

import argparse
import json
import pickle
import sqlite3
import sys
from bisect import bisect_left     # 이진 탐색 (거래일 날짜 찾기)
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

try:
    import FinanceDataReader as fdr
except ImportError:
    print(json.dumps({"error": "finance-datareader 미설치"}))
    sys.exit(1)
SCRIPT_DIR = Path(__file__).resolve().parent
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR = SCRIPT_DIR / "data"
DEFAULT_MODEL_PATH = str(MODEL_DIR / "article_lgbm_model.pkl")
DEFAULT_SQLITE_PATH = str(DATA_DIR / "news_data.db")
DEFAULT_ORACLE_COMPANY_PATH = str(DATA_DIR / "oracle_company.csv")
DEFAULT_ORACLE_SECTOR_PATH = str(DATA_DIR / "oracle_sector.csv")


# ── 카테고리 → 종목코드 매핑 ─────────────────────────────────
CATEGORY_TO_CODE = {
    "반도체/AI": "005930",
    "IT/반도체": "005930",
    "삼성전자 (IT/반도체)": "005930",
    "2차전지": "373220",
    "LG에너지솔루션 (2차전지)": "373220",
    "바이오": "207940",
    "제약/바이오": "207940",
    "삼성바이오로직스 (제약/바이오)": "207940",
    "IT/플랫폼": "035420",
    "네이버 (IT/플랫폼)": "035420",
    "자동차/모빌리티": "005380",
    "현대차 (자동차/모빌리티)": "005380",
    "방산": "012450",
    "방산/우주항공": "012450",
    "한화에어로스페이스 (방산/우주항공)": "012450",
    "엔터/미디어": "352820",
    "하이브 (엔터/미디어)": "352820",
    "금융/밸류업": "105560",
    "KB금융 (금융/밸류업)": "105560",
}

# 감성 레이블 → 수치 변환
SENTIMENT_MAP = {"호재": 1, "중립": 0, "악재": -1}

# 주가 기술 지표 피처 컬럼 목록
PRICE_FEATURES = [
    "close", "ma_5", "ma_20", "ma_60",
    "close_to_ma5", "close_to_ma20", "ma_cross",
    "rsi_14", "rsi_zone",
    "macd", "macd_sig", "macd_hist", "macd_cross",
    "bb_pctb",
    "volatility_5d", "volatility_20d",
    "volume_ratio",
    "return_before_1d", "return_before_3d", "return_before_5d",
]


def load_model(path: str) -> dict:
    """pkl 파일에서 모델 payload 로드."""
    with open(path, "rb") as f:
        return pickle.load(f)


def safe_float(value: object, default: float = 0.0) -> float:
    """안전한 float 변환. NaN/무한대/변환 실패 시 default 반환."""
    try:
        parsed = float(value)
        if np.isnan(parsed) or np.isinf(parsed):
            return default
        return parsed
    except Exception:
        return default


def normalize_columns(df: pd.DataFrame) -> pd.DataFrame:
    """컬럼명을 소문자 + 공백 제거로 정규화."""
    df = df.copy()
    df.columns = [str(c).strip().lower() for c in df.columns]
    return df


# ================================================================
# 【보조 함수】 뉴스 데이터 소스 로드
#
# 역할: 세 곳의 소스에서 뉴스를 불러와 하나의 DataFrame으로 합친다.
#       이 데이터는 "최근 3일 맥락 피처" 계산에 사용됨.
#       (기사 자체는 커맨드라인으로 직접 전달받으므로 별도 로드 불필요)
# ================================================================
def load_news_sources(sqlite_db: str, oracle_company: str, oracle_sector: str) -> pd.DataFrame:
    dfs: list[pd.DataFrame] = []

    # Oracle CSV 파일 로드
    for path in [oracle_company, oracle_sector]:
        if Path(path).exists():
            df = normalize_columns(pd.read_csv(path))
            dfs.append(df)

    # SQLite DB 로드
    if Path(sqlite_db).exists():
        conn = sqlite3.connect(sqlite_db)
        try:
            df_sql = pd.read_sql("SELECT * FROM news_data", conn)
        finally:
            conn.close()
        df_sql = normalize_columns(df_sql).drop(columns=["created_at"], errors="ignore")
        dfs.append(df_sql)

    if not dfs:
        return pd.DataFrame(columns=["category", "pub_date", "sentiment", "clickbait_prob", "type_prob", "article_type"])

    df_all = pd.concat(dfs, ignore_index=True)
    if "link" in df_all.columns:
        df_all = df_all.drop_duplicates(subset="link")

    # 필수 컬럼 존재 보장
    for column in ["category", "pub_date", "sentiment", "clickbait_prob", "type_prob", "article_type"]:
        if column not in df_all.columns:
            df_all[column] = np.nan

    df_all["category"]     = df_all["category"].fillna("").astype(str).str.strip()
    df_all["stock_code"]   = df_all["category"].map(CATEGORY_TO_CODE)
    df_all["pub_date"]     = pd.to_datetime(df_all["pub_date"], errors="coerce")
    df_all["sentiment"]    = df_all["sentiment"].fillna("중립").astype(str)
    df_all["clickbait_prob"] = pd.to_numeric(df_all["clickbait_prob"], errors="coerce").fillna(0.0)
    df_all["type_prob"]    = pd.to_numeric(df_all["type_prob"], errors="coerce").fillna(0.0)
    df_all["article_type"] = df_all["article_type"].fillna("").astype(str)

    return df_all.dropna(subset=["pub_date", "stock_code"]).sort_values("pub_date").reset_index(drop=True)


# ================================================================
# 【보조 함수】 최근 3일 맥락 피처 계산
#
# 역할: 현재 기사(pub_dt) 발행 직전 3일간의 같은 종목 뉴스를
#       집계하여 맥락 피처를 반환.
#
# 왜 필요한가?
#   기사 1건의 영향은 최근 분위기에 따라 달라짐.
#   부정적인 뉴스가 연속으로 나오는 상황 vs 오랜만에 나오는 상황은
#   주가 반응이 다를 수 있음.
#
# 결과: recent_* 형태의 피처 딕셔너리
#   데이터 없으면 모든 값을 기본값(0 또는 72시간)으로 반환.
# ================================================================
def compute_recent_features(df_news: pd.DataFrame, stock_code: str, pub_dt: pd.Timestamp) -> dict:
    lookback_start = pub_dt - timedelta(days=3)
    # 현재 기사(pub_dt) 이전 3일간 같은 종목 기사만 필터
    df_scope = df_news[
        (df_news["stock_code"] == stock_code)
        & (df_news["pub_date"] >= lookback_start)
        & (df_news["pub_date"] < pub_dt)   # 현재 기사 제외 (미래 데이터 유출 방지)
        ].copy()

    if df_scope.empty:
        # 이전 기사 없음 → 기본값 반환
        return {
            "recent_article_count_3d":       0,
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
            "hours_since_prev_article":      72.0,  # 기본값: 3일(72시간)
        }

    sentiments = df_scope["sentiment"].map(SENTIMENT_MAP).fillna(0.0)
    n          = len(df_scope)
    pos_ratio  = float((sentiments == 1).sum() / n)
    neg_ratio  = float((sentiments == -1).sum() / n)
    last_pub   = df_scope["pub_date"].max()
    hours_gap  = safe_float((pub_dt - last_pub).total_seconds() / 3600.0, 72.0)

    return {
        "recent_article_count_3d":       int(n),
        "recent_sentiment_mean_3d":      safe_float(sentiments.mean()),
        "recent_sentiment_sum_3d":       safe_float(sentiments.sum()),
        "recent_positive_ratio_3d":      pos_ratio,
        "recent_negative_ratio_3d":      neg_ratio,
        "recent_sentiment_intensity_3d": pos_ratio - neg_ratio,
        "recent_clickbait_mean_3d":      safe_float(df_scope["clickbait_prob"].mean()),
        "recent_type_prob_mean_3d":      safe_float(df_scope["type_prob"].mean()),
        "recent_fact_ratio_3d":          safe_float((df_scope["article_type"] == "사실형").mean()),
        "recent_predict_ratio_3d":       safe_float((df_scope["article_type"] == "예측형").mean()),
        "recent_reasoning_ratio_3d":     safe_float((df_scope["article_type"] == "추론형").mean()),
        "hours_since_prev_article":      max(0.0, hours_gap),
    }


# ================================================================
# 【보조 함수】 기술적 지표 계산 함수들
# article_feature_builder.py와 동일한 로직 (일관성 필수)
# ================================================================
def _rsi(series: pd.Series, period: int = 14) -> pd.Series:
    """RSI 계산: 상승분 평균 / 하락분 평균으로 0~100 반환."""
    delta = series.diff()
    gain  = delta.clip(lower=0).rolling(period).mean()
    loss  = (-delta.clip(upper=0)).rolling(period).mean()
    rs    = gain / loss.replace(0, np.nan)
    return 100 - (100 / (1 + rs))


def _macd(series: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9):
    """MACD 라인, Signal 라인, Histogram 반환."""
    ema_fast    = series.ewm(span=fast, adjust=False).mean()
    ema_slow    = series.ewm(span=slow, adjust=False).mean()
    macd_line   = ema_fast - ema_slow
    signal_line = macd_line.ewm(span=signal, adjust=False).mean()
    return macd_line, signal_line, macd_line - signal_line


def _bb_pctb(series: pd.Series, period: int = 20, std_k: int = 2) -> pd.Series:
    """볼린저밴드 %B: 현재가가 밴드 내 어느 위치인지 (0=하단, 1=상단)."""
    ma  = series.rolling(period).mean()
    std = series.rolling(period).std()
    return (series - (ma - std_k * std)) / (2 * std_k * std).replace(0, np.nan)


# ================================================================
# 【보조 함수】 기사 발행 시점 주가 피처 계산
#
# 역할: 기사가 발행된 시점을 기준으로 주가 기술 지표를 계산.
#
# 핵심 로직 (article_feature_builder.resolve_trade_window와 동일):
#   - 장 중 발행  → 당일 전날 주가를 context로 사용
#   - 장 후 발행  → 당일 주가를 context로 사용
#   - 비거래일    → 다음 거래일 직전 주가를 context로 사용
#
# 왜 이렇게 하는가?
#   장 후에 발행된 뉴스는 당일 주가에 이미 반영 불가이므로
#   다음날 반영되는 것으로 보고 앵커(anchor) 날짜를 다음 거래일로 설정.
#
# 반환값: (주가 피처 딕셔너리, anchor_delay_days)
# ================================================================
def get_price_features(stock_code: str, pub_dt: pd.Timestamp) -> tuple[dict, int]:
    base = {k: 0.0 for k in PRICE_FEATURES}  # 오류 시 반환할 기본값
    try:
        start = (pub_dt - timedelta(days=160)).strftime("%Y-%m-%d")  # MA60 워밍업
        end   = (pub_dt + timedelta(days=14)).strftime("%Y-%m-%d")   # 레이블 계산용 여유
        raw   = fdr.DataReader(stock_code, start, end)
        if raw is None or raw.empty:
            return base, 0

        raw.index = pd.to_datetime(raw.index).normalize()  # 시간 제거
        close  = raw["Close"].astype(float)
        volume = raw["Volume"].astype(float) if "Volume" in raw.columns else pd.Series(index=raw.index, dtype=float)

        feat = pd.DataFrame(index=raw.index)
        feat["close"]  = close
        feat["ma_5"]   = close.rolling(5).mean()
        feat["ma_20"]  = close.rolling(20).mean()
        feat["ma_60"]  = close.rolling(60).mean()
        feat["close_to_ma5"]  = (close - feat["ma_5"])  / feat["ma_5"]
        feat["close_to_ma20"] = (close - feat["ma_20"]) / feat["ma_20"]
        feat["ma_cross"]      = np.sign(feat["ma_5"] - feat["ma_20"])

        feat["rsi_14"]  = _rsi(close, 14)
        feat["rsi_zone"]= np.sign(feat["rsi_14"] - 50.0)

        macd_line, macd_sig, macd_hist = _macd(close)
        feat["macd"]      = macd_line
        feat["macd_sig"]  = macd_sig
        feat["macd_hist"] = macd_hist
        feat["macd_cross"]= np.sign(macd_hist)

        feat["bb_pctb"]       = _bb_pctb(close)
        feat["volatility_5d"] = close.pct_change().rolling(5).std()
        feat["volatility_20d"]= close.pct_change().rolling(20).std()
        vol_ma5 = volume.rolling(5).mean()
        feat["volume_ratio"]  = volume / vol_ma5.replace(0, np.nan)
        feat["return_before_1d"] = close.pct_change()
        feat["return_before_3d"] = close.pct_change(3)
        feat["return_before_5d"] = close.pct_change(5)

        trade_dates = feat.index.tolist()
        pub_day     = pub_dt.normalize()

        # 이진 탐색으로 발행일의 거래일 위치 찾기
        pos      = bisect_left(trade_dates, pub_day)
        same_day = pos < len(trade_dates) and trade_dates[pos] == pub_day
        is_after_close = pub_dt.hour > 15 or (pub_dt.hour == 15 and pub_dt.minute >= 30)

        # anchor: 뉴스가 실제 주가에 반영되는 첫 거래일
        if same_day and not is_after_close:
            anchor_idx = pos      # 장 중 발행 → 당일 반영
        elif same_day:
            anchor_idx = pos + 1  # 장 후 발행 → 다음 거래일 반영
        else:
            anchor_idx = pos      # 비거래일 → 다음 거래일 반영

        if anchor_idx >= len(trade_dates):
            anchor_idx = len(trade_dates) - 1

        # context: anchor 바로 전날 (피처 추출 기준)
        context_idx = anchor_idx - 1
        if context_idx < 0:
            context_idx = 0

        row    = feat.iloc[context_idx]
        output = {k: safe_float(row.get(k, 0.0), 0.0) for k in PRICE_FEATURES}
        anchor_delay_days = max((trade_dates[anchor_idx] - pub_day).days, 0)
        return output, int(anchor_delay_days)
    except Exception:
        return base, 0


# ================================================================
# 【메인 로직】 피처 벡터 구성
#
# 역할: 커맨드라인으로 받은 기사 정보 + 맥락 피처 + 주가 피처를
#       모델이 요구하는 형태의 피처 딕셔너리로 조합.
#
# StockTrend.py와 다른 점:
#   여기서는 기사 1건의 원시 정보(제목, 요약, 감성 등)를
#   커맨드라인 인자로 직접 받아 피처로 변환.
# ================================================================
def build_feature_map(args: argparse.Namespace, payload: dict) -> dict:
    pub_dt = pd.to_datetime(args.date, errors="coerce")
    if pd.isna(pub_dt):
        raise ValueError("date 형식 오류")

    # 맥락 계산을 위한 과거 뉴스 데이터 로드
    news_df = load_news_sources(
        sqlite_db=args.sqlite,
        oracle_company=args.oracle_company,
        oracle_sector=args.oracle_sector,
    )

    # 최근 3일 맥락 피처
    recent = compute_recent_features(news_df, args.code, pub_dt)
    # 주가 기술 지표 피처
    price_features, anchor_delay_days = get_price_features(args.code, pub_dt)

    # 기사 기본 정보
    title        = args.title or ""
    summary      = args.summary or ""
    article_type = args.article_type or ""
    sentiment    = args.sentiment or "중립"

    # 발행 시각 파생 피처
    pub_hour     = int(pub_dt.hour)
    pub_weekday  = int(pub_dt.weekday())
    is_weekend   = int(pub_weekday >= 5)
    is_before_open  = int((pub_hour < 9) or (pub_hour == 9 and pub_dt.minute == 0))
    is_after_close  = int((pub_hour > 15) or (pub_hour == 15 and pub_dt.minute >= 30))

    # 종목코드 인코딩 (학습 시 본 종목이면 인코딩, 아니면 -1)
    encoder = payload["label_encoder"]
    if args.code in encoder.classes_:
        stock_code_enc = int(encoder.transform([args.code])[0])
    else:
        stock_code_enc = -1

    # 기사 자체 피처
    feature_map = {
        "sentiment_score":   float(SENTIMENT_MAP.get(sentiment, 0)),
        "clickbait_prob":    safe_float(args.clickbait_prob),
        "type_prob":         safe_float(args.type_prob),
        "is_fact":           int(article_type == "사실형"),
        "is_predict":        int(article_type == "예측형"),
        "is_reasoning":      int(article_type == "추론형"),
        "title_length":      len(title),           # 제목 글자 수
        "summary_length":    len(summary),         # 요약 글자 수
        "title_word_count":  len(title.split()) if title else 0,
        "summary_word_count":len(summary.split()) if summary else 0,
        "has_summary":       int(bool(summary.strip())),
        "pub_hour":          pub_hour,
        "pub_weekday":       pub_weekday,
        "is_weekend":        is_weekend,
        "is_before_open":    is_before_open,
        "is_after_close":    is_after_close,
        "anchor_delay_days": anchor_delay_days,    # 반영까지 걸리는 거래일 수
        "stock_code_enc":    stock_code_enc,
    }
    feature_map.update(recent)          # 최근 3일 맥락 피처 병합
    feature_map.update(price_features)  # 주가 기술 지표 병합
    return feature_map


# ================================================================
# 【예측 실행】
#
# 역할: 피처 벡터를 구성하고 모델에 입력하여 영향 점수 계산.
#
# impact_percent 해석:
#   +100에 가까울수록 → 강한 긍정 영향 (주가 상승 기여)
#   0에 가까울수록   → 영향 없음
#   -100에 가까울수록→ 강한 부정 영향 (주가 하락 기여)
# ================================================================
def infer(args: argparse.Namespace) -> dict:
    if not Path(args.model).exists():
        return {"error": f"모델 파일 없음: {args.model}"}

    payload      = load_model(args.model)
    model        = payload["model"]
    feature_cols = payload["feature_cols"]

    feature_map = build_feature_map(args, payload)
    X = np.array([[feature_map.get(col, 0.0) for col in feature_cols]], dtype=float)

    prob = float(model.predict(X)[0])
    prob = max(0.0, min(1.0, prob))  # 0~1 범위 클리핑

    # 영향 점수: 0.5 기준으로 -100 ~ +100 범위로 변환
    # prob=1.0 → +100 (완전 긍정), prob=0.5 → 0, prob=0.0 → -100 (완전 부정)
    impact_percent = (prob - 0.5) * 200.0
    impact_percent = max(-100.0, min(100.0, impact_percent))

    # 확신도: 0.5에서 얼마나 멀리 있는지
    conf = abs(prob - 0.5) * 2
    if conf > 0.6:
        confidence = "높음"
    elif conf > 0.3:
        confidence = "중간"
    else:
        confidence = "낮음"

    return {
        "stock_code":      args.code,
        "date":            str(pd.to_datetime(args.date).strftime("%Y-%m-%d %H:%M")),
        "probability":     round(prob, 4),           # 상승 확률 (0~1)
        "impact_percent":  round(impact_percent, 2), # 주가 영향 점수 (-100~+100)
        "confidence":      confidence,               # 확신도 (높음/중간/낮음)
        "source":          "기사 영향 모델",
    }


# ================================================================
# 【메인 실행 함수】
# ================================================================
def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--code",          required=True,  help="종목코드 (예: 005930)")
    parser.add_argument("--date",          required=True,  help="기사 발행 시각 (YYYY-MM-DD HH:mm)")
    parser.add_argument("--sentiment",     default="중립", help="감성 (호재/중립/악재)")
    parser.add_argument("--clickbait-prob",type=float, default=0.0, help="클릭베이트 확률")
    parser.add_argument("--type-prob",     type=float, default=0.0, help="기사 유형 확률")
    parser.add_argument("--article-type",  default="",     help="기사 유형 (사실형/예측형/추론형)")
    parser.add_argument("--title",         default="",     help="기사 제목")
    parser.add_argument("--summary",       default="",     help="기사 요약")
    parser.add_argument("--model",         default="article_lgbm_model.pkl", help="모델 파일 경로")
    parser.add_argument("--sqlite",        default="news_data.db",           help="SQLite DB 경로")
    parser.add_argument("--oracle-company",default="oracle_company.csv",     help="기업별 뉴스 CSV")
    parser.add_argument("--oracle-sector", default="oracle_sector.csv",      help="섹터별 뉴스 CSV")
    args = parser.parse_args()
    if args.model == "article_lgbm_model.pkl":
        args.model = DEFAULT_MODEL_PATH
    if args.sqlite == "news_data.db":
        args.sqlite = DEFAULT_SQLITE_PATH
    if args.oracle_company == "oracle_company.csv":
        args.oracle_company = DEFAULT_ORACLE_COMPANY_PATH
    if args.oracle_sector == "oracle_sector.csv":
        args.oracle_sector = DEFAULT_ORACLE_SECTOR_PATH

    try:
        result = infer(args)
    except Exception as e:
        result = {"error": str(e)}

    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
