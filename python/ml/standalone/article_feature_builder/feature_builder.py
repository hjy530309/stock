"""
feature_builder.py
───────────────────────────────────────────────────────────────
【역할】 머신러닝 학습에 필요한 '피처(Feature) 데이터'를 만드는 전처리 스크립트.

【입력】
  - oracle_company.csv : Oracle DB에서 추출한 기업별 뉴스 CSV
  - oracle_sector.csv  : Oracle DB에서 추출한 섹터별 뉴스 CSV
  - news_data.db       : SQLite에 저장된 뉴스 데이터 (Spring 백엔드가 수집)

【출력】
  - features.csv : 뉴스 피처 + 주가 기술적 지표 + 레이블(다음날 상승/하락)이
                   합쳐진 학습용 데이터셋. → train_model.py의 입력으로 사용됨.

【왜 필요한가?】
  ML 모델은 숫자 배열(피처 행렬)만 입력받을 수 있습니다.
  뉴스 텍스트, 감성 점수, 주가 지표 등 이질적인 데이터를
  하나의 표(CSV)로 정리해 주는 '데이터 파이프라인' 역할을 합니다.

【사용법】
    python feature_builder.py
    python feature_builder.py --sqlite news_data.db --out features.csv

【실행 순서】
  1. feature_builder.py  → features.csv 생성
  2. train_model.py      → lgbm_model.pkl 생성
  3. predict.py          → 예측 결과 JSON 출력 (Spring에서 호출)
"""

import argparse
import sqlite3
import pandas as pd
import numpy as np
from datetime import timedelta
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR.mkdir(parents=True, exist_ok=True)
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# PyCharm에서 별도로 보관/실행할 때 사용할 기본 경로입니다.
DEFAULT_ORACLE_COMPANY_CSV = str(DATA_DIR / "oracle_company.csv")
DEFAULT_ORACLE_SECTOR_CSV = str(DATA_DIR / "oracle_sector.csv")
DEFAULT_SQLITE_DB = str(DATA_DIR / "news_data.db")
DEFAULT_FEATURES_CSV = str(DATA_DIR / "features.csv")

try:
    import FinanceDataReader as fdr  # 한국 주가 데이터 수집 라이브러리
except ImportError:
    raise SystemExit("pip install finance-datareader 먼저 실행하세요.")


# ================================================================
# 【카테고리 → 종목코드 매핑 딕셔너리】
#
# 뉴스 DB에는 카테고리가 다양한 표기로 저장되어 있음.
# (예: "반도체/AI", "IT/반도체", "삼성전자 (IT/반도체)" 모두 삼성전자)
# 이를 통일된 6자리 종목코드로 변환한다.
#
# ▶ 결과: 카테고리 문자열 → "005930"(삼성전자) 같은 종목코드
# ================================================================
CATEGORY_TO_CODE = {
    # 반도체/AI 계열 → 삼성전자(005930)
    "반도체/AI":             "005930",
    "IT/반도체":             "005930",
    "삼성전자 (IT/반도체)":  "005930",
    # 2차전지 계열 → LG에너지솔루션(373220)
    "2차전지":               "373220",
    "LG에너지솔루션 (2차전지)": "373220",
    # 바이오 계열 → 삼성바이오로직스(207940)
    "바이오":                "207940",
    "제약/바이오":           "207940",
    "삼성바이오로직스 (제약/바이오)": "207940",
    # IT/플랫폼 계열 → 네이버(035420)
    "IT/플랫폼":             "035420",
    "네이버 (IT/플랫폼)":   "035420",
    # 자동차 계열 → 현대차(005380)
    "자동차/모빌리티":       "005380",
    "현대차 (자동차/모빌리티)": "005380",
    # 방산 계열 → 한화에어로스페이스(012450)
    "방산":                  "012450",
    "방산/우주항공":         "012450",
    "한화에어로스페이스 (방산/우주항공)": "012450",
    # 엔터 계열 → 하이브(352820)
    "엔터/미디어":           "352820",
    "하이브 (엔터/미디어)": "352820",
    # 금융 계열 → KB금융(105560)
    "금융/밸류업":           "105560",
    "KB금융 (금융/밸류업)": "105560",
}

# 카테고리 표기를 통일된 이름으로 정규화 (딕셔너리)
# (예: "IT/반도체" → "반도체/AI" 로 통일)
CATEGORY_NORM = {
    "반도체/AI":             "반도체/AI",
    "IT/반도체":             "반도체/AI",
    "삼성전자 (IT/반도체)":  "반도체/AI",
    "2차전지":               "2차전지",
    "LG에너지솔루션 (2차전지)": "2차전지",
    "바이오":                "바이오",
    "제약/바이오":           "바이오",
    "삼성바이오로직스 (제약/바이오)": "바이오",
    "IT/플랫폼":             "IT/플랫폼",
    "네이버 (IT/플랫폼)":   "IT/플랫폼",
    "자동차/모빌리티":       "자동차/모빌리티",
    "현대차 (자동차/모빌리티)": "자동차/모빌리티",
    "방산":                  "방산",
    "방산/우주항공":         "방산",
    "한화에어로스페이스 (방산/우주항공)": "방산",
    "엔터/미디어":           "엔터/미디어",
    "하이브 (엔터/미디어)": "엔터/미디어",
    "금융/밸류업":           "금융/밸류업",
    "KB금융 (금융/밸류업)": "금융/밸류업",
}


# ================================================================
# 【1단계】 뉴스 데이터 로드 + 통합
#
# 역할: 세 곳의 데이터 소스(Oracle CSV 2개 + SQLite 1개)에서
#       뉴스를 읽어와 하나의 DataFrame으로 합친다.
#
# 왜 여러 소스? → Oracle은 과거 데이터, SQLite는 Spring 백엔드가
#                 실시간 수집한 최신 뉴스를 담고 있어 보완 관계임.
#
# 결과: 중복 제거 + 카테고리 매핑 불가 항목 제거된 통합 뉴스 DataFrame
# ================================================================
def load_news(
    oracle_company_csv: str = DEFAULT_ORACLE_COMPANY_CSV,
    oracle_sector_csv:  str = DEFAULT_ORACLE_SECTOR_CSV,
    sqlite_db:          str = DEFAULT_SQLITE_DB,
) -> pd.DataFrame:
    dfs = []

    # Oracle CSV 파일 2개 로드 (없으면 경고 후 스킵)
    for path in [oracle_company_csv, oracle_sector_csv]:
        if Path(path).exists():
            df = pd.read_csv(path)
            df.columns = [c.lower() for c in df.columns]  # 컬럼명 소문자 통일
            dfs.append(df)
            print(f"  [{path}] {len(df)}건 로드")
        else:
            print(f"  [{path}] 파일 없음 — 스킵")

    # SQLite DB 로드 (Spring 백엔드에서 수집된 실시간 뉴스)
    if Path(sqlite_db).exists():
        conn = sqlite3.connect(sqlite_db)
        df_sq = pd.read_sql("SELECT * FROM news_data", conn)
        conn.close()
        # created_at은 Oracle CSV에 없는 컬럼이므로 제거
        df_sq = df_sq.drop(columns=["created_at"], errors="ignore")
        dfs.append(df_sq)
        print(f"  [SQLite] {len(df_sq)}건 로드")
    else:
        print(f"  [SQLite] 파일 없음 — 스킵")

    # 세 소스 합치기
    df = pd.concat(dfs, ignore_index=True)
    # 같은 기사 URL(link)이 중복 저장된 경우 제거
    df = df.drop_duplicates(subset="link")
    print(f"\n  합산 (중복제거 후): {len(df)}건")

    # 종목코드 매핑이 안 되는 카테고리(예: '기타') 제거
    before = len(df)
    df = df[df["category"].map(CATEGORY_TO_CODE).notna()].copy()
    print(f"  카테고리 매핑 불가 제거: {before - len(df)}건 → {len(df)}건 남음")

    return df


# ================================================================
# 【2단계】 뉴스 Feature 수치화
#
# 역할: 텍스트/카테고리형 뉴스 데이터를 ML이 이해할 수 있는
#       숫자(피처)로 변환한다.
#
# 변환 목록:
#   - sentiment(호재/중립/악재) → 1/0/-1 정수
#   - article_type(사실형/예측형/추론형) → 원-핫 인코딩(0 또는 1)
#   - pub_date → 종목코드, 날짜 컬럼 분리
#
# 결과: 수치형 피처가 추가된 DataFrame
# ================================================================
def build_news_features(df: pd.DataFrame) -> pd.DataFrame:
    df = df.copy()

    # 발행일시 파싱 (잘못된 형식은 NaT → 이후 제거됨)
    df["pub_date"] = pd.to_datetime(df["pub_date"], errors="coerce")
    df = df.dropna(subset=["pub_date"])  # 날짜 없는 뉴스 제거
    df["date"] = df["pub_date"].dt.date  # 날짜만 추출 (시간 제거)

    # 카테고리 → 종목코드 + 정규화된 카테고리명 매핑
    df["stock_code"]    = df["category"].map(CATEGORY_TO_CODE)
    df["category_norm"] = df["category"].map(CATEGORY_NORM)

    # 감성 수치화: "호재"→1, "중립"→0, "악재"→-1
    sentiment_map = {"호재": 1, "중립": 0, "악재": -1}
    df["sentiment_score"] = df["sentiment"].map(sentiment_map).fillna(0)

    # 클릭베이트 확률, 기사 유형 확률 → 숫자 변환 (변환 실패 시 0)
    df["clickbait_prob"] = pd.to_numeric(df["clickbait_prob"], errors="coerce").fillna(0)
    df["type_prob"]      = pd.to_numeric(df["type_prob"],      errors="coerce").fillna(0)

    # 기사 유형 → 원-핫 인코딩 (각각 0 또는 1)
    df["is_fact"]      = (df["article_type"] == "사실형").astype(int)
    df["is_predict"]   = (df["article_type"] == "예측형").astype(int)
    df["is_reasoning"] = (df["article_type"] == "추론형").astype(int)

    return df


# ================================================================
# 【3단계】 날짜별 집계
#
# 역할: 기사 단위 데이터를 (종목코드 × 날짜) 단위로 집계한다.
#       하루에 같은 종목 기사가 10개라면 → 1행으로 압축.
#
# 왜? → train_model.py는 "오늘 기사 전체"를 보고 "내일 주가"를
#        예측하는 일(日) 단위 모델을 학습하기 때문.
#
# 결과: 종목×날짜별 집계 피처 DataFrame
#   예시 컬럼: article_count, sentiment_mean, positive_ratio 등
# ================================================================
def aggregate_daily(df: pd.DataFrame) -> pd.DataFrame:
    grp = df.groupby(["stock_code", "date"])

    agg = grp.agg(
        article_count    = ("link",            "count"),          # 기사 수
        sentiment_mean   = ("sentiment_score", "mean"),           # 평균 감성
        sentiment_sum    = ("sentiment_score", "sum"),            # 감성 합계
        positive_count   = ("sentiment_score", lambda x: (x == 1).sum()),   # 호재 수
        negative_count   = ("sentiment_score", lambda x: (x == -1).sum()),  # 악재 수
        neutral_count    = ("sentiment_score", lambda x: (x == 0).sum()),   # 중립 수
        clickbait_mean   = ("clickbait_prob",  "mean"),           # 평균 클릭베이트 확률
        type_prob_mean   = ("type_prob",       "mean"),           # 평균 유형 확률
        fact_ratio       = ("is_fact",         "mean"),           # 사실형 비율
        predict_ratio    = ("is_predict",      "mean"),           # 예측형 비율
        reasoning_ratio  = ("is_reasoning",    "mean"),           # 추론형 비율
    ).reset_index()

    # 호재/악재 비율 (= 해당 기사 수 / 전체 기사 수)
    agg["positive_ratio"] = agg["positive_count"] / agg["article_count"]
    agg["negative_ratio"] = agg["negative_count"] / agg["article_count"]

    # 감성 강도 = 호재비율 - 악재비율 (양수면 긍정, 음수면 부정)
    agg["sentiment_intensity"] = agg["positive_ratio"] - agg["negative_ratio"]

    agg["date"] = pd.to_datetime(agg["date"])
    return agg


# ================================================================
# 【4단계】 주가 데이터 수집 + 기술적 지표 계산
#
# 역할: FinanceDataReader를 사용해 각 종목의 과거 주가를 수집하고
#       ML에 사용할 기술적 지표를 계산한다.
#
# 계산 지표 목록:
#   - 이동평균 (MA5/20/60): 단기·중기·장기 추세
#   - RSI: 과매수/과매도 판단 (0~100, 30이하=과매도, 70이상=과매수)
#   - MACD: 모멘텀 방향 지표
#   - 볼린저밴드 %B: 가격이 밴드 내 어디에 위치하는지
#   - 변동성: 5일/20일 표준편차
#   - 거래량 비율: 거래량 / 5일 평균 거래량
#   - 과거 수익률: 1일/3일/5일 전 대비 수익률 (미래 데이터 유출 없음)
#
# 레이블 (정답 데이터):
#   - label_1d: 내일 종가가 오늘보다 오르면 1, 내리면 0
#   - label_3d: 3일 후 기준 동일
#
# 결과: 종목별 일별 주가 + 기술 지표 DataFrame
# ================================================================
def calc_rsi(series: pd.Series, period: int = 14) -> pd.Series:
    """RSI(상대강도지수) 계산. 상승분 평균 / 하락분 평균으로 0~100 반환."""
    delta = series.diff()
    gain  = delta.clip(lower=0).rolling(period).mean()   # 상승한 날의 평균
    loss  = (-delta.clip(upper=0)).rolling(period).mean() # 하락한 날의 평균(절댓값)
    rs    = gain / loss.replace(0, np.nan)               # RS = 평균상승 / 평균하락
    return 100 - (100 / (1 + rs))                        # RSI = 100 - (100/(1+RS))


def calc_macd(series: pd.Series, fast=12, slow=26, signal=9):
    """MACD(이동평균수렴확산) 계산.
    fast EMA - slow EMA = MACD 라인
    MACD의 EMA = Signal 라인
    MACD - Signal = Histogram (매수/매도 강도)
    """
    ema_fast    = series.ewm(span=fast,   adjust=False).mean()
    ema_slow    = series.ewm(span=slow,   adjust=False).mean()
    macd_line   = ema_fast - ema_slow
    signal_line = macd_line.ewm(span=signal, adjust=False).mean()
    histogram   = macd_line - signal_line
    return macd_line, signal_line, histogram


def calc_bollinger(series: pd.Series, period=20, std_k=2):
    """볼린저밴드 계산.
    상단밴드 = MA + 2σ, 하단밴드 = MA - 2σ
    %B = (현재가 - 하단) / (상단 - 하단) → 0이면 하단, 1이면 상단
    """
    ma    = series.rolling(period).mean()
    std   = series.rolling(period).std()
    upper = ma + std_k * std
    lower = ma - std_k * std
    width = (upper - lower).replace(0, np.nan)
    pct_b = (series - lower) / width
    return ma, upper, lower, pct_b


def fetch_stock_returns(
    stock_codes: list,
    start_date:  str,
    end_date:    str,
) -> pd.DataFrame:
    """
    FinanceDataReader로 종목별 주가 수집 후 기술 지표 계산.
    MA60 워밍업을 위해 start_date보다 120일 앞서서 수집한다.
    """
    # MA60 등 지표 계산에 충분한 과거 데이터 확보 (120일 앞서 수집)
    fetch_start = (pd.to_datetime(start_date) - timedelta(days=120)).strftime("%Y-%m-%d")
    all_returns = []

    for code in stock_codes:
        print(f"  주가 수집 + 지표 계산: {code}")
        try:
            raw = fdr.DataReader(code, fetch_start, end_date)
            raw.index = pd.to_datetime(raw.index)

            price = pd.DataFrame(index=raw.index)
            price["close"]      = raw["Close"]
            price["open"]       = raw["Open"]   if "Open"   in raw.columns else np.nan
            price["high"]       = raw["High"]   if "High"   in raw.columns else np.nan
            price["low"]        = raw["Low"]    if "Low"    in raw.columns else np.nan
            price["volume"]     = raw["Volume"] if "Volume" in raw.columns else np.nan
            price["stock_code"] = code

            close = price["close"]

            # ── 이동평균선 ──────────────────────────────────────────
            price["ma_5"]          = close.rolling(5).mean()   # 5일 이동평균
            price["ma_20"]         = close.rolling(20).mean()  # 20일 이동평균
            price["ma_60"]         = close.rolling(60).mean()  # 60일 이동평균
            # 현재가가 MA 대비 몇 % 위/아래인지 (양수=MA 위, 음수=MA 아래)
            price["close_to_ma5"]  = (close - price["ma_5"])  / price["ma_5"]
            price["close_to_ma20"] = (close - price["ma_20"]) / price["ma_20"]
            # MA 크로스 방향: +1=골든크로스(단기>장기=상승), -1=데드크로스
            price["ma_cross"]      = np.sign(price["ma_5"] - price["ma_20"])

            # ── RSI ────────────────────────────────────────────────
            price["rsi_14"]   = calc_rsi(close, 14)
            # RSI 구간 분류: -1=과매도(30이하), 0=중립, 1=과매수(70이상)
            price["rsi_zone"] = pd.cut(
                price["rsi_14"],
                bins=[0, 30, 50, 70, 100],
                labels=[-1, 0, 0, 1],
                ordered=False,
            ).astype(float)

            # ── MACD ───────────────────────────────────────────────
            macd, sig, hist     = calc_macd(close)
            price["macd"]       = macd      # MACD 라인
            price["macd_sig"]   = sig       # Signal 라인
            price["macd_hist"]  = hist      # Histogram (모멘텀 강도)
            price["macd_cross"] = np.sign(hist)  # +1=매수신호, -1=매도신호

            # ── 볼린저밴드 ─────────────────────────────────────────
            _, _, _, pct_b   = calc_bollinger(close)
            price["bb_pctb"] = pct_b  # 0~1 사이: 0.5=중앙, 1=상단돌파

            # ── 변동성 ─────────────────────────────────────────────
            daily_ret               = close.pct_change()  # 일일 수익률
            price["volatility_5d"]  = daily_ret.rolling(5).std()   # 5일 변동성
            price["volatility_20d"] = daily_ret.rolling(20).std()  # 20일 변동성

            # ── 거래량 비율 ────────────────────────────────────────
            price["volume_ma5"]   = price["volume"].rolling(5).mean()
            # 오늘 거래량 / 5일 평균 거래량 (1보다 크면 평소보다 활발)
            price["volume_ratio"] = price["volume"] / price["volume_ma5"].replace(0, np.nan)

            # ── 과거 수익률 (미래 유출 없음) ──────────────────────
            # 뉴스 발행 시점의 '이미 알고 있는' 정보만 사용
            price["return_before_1d"] = daily_ret            # 어제 대비 수익률
            price["return_before_3d"] = close.pct_change(3)  # 3일 전 대비 수익률
            price["return_before_5d"] = close.pct_change(5)  # 5일 전 대비 수익률

            # ── 레이블 (정답 데이터 = 미래 수익률) ────────────────
            # shift(-N): N일 후 종가 - 현재 종가. 모델이 맞춰야 할 정답.
            price["return_1d"] = close.pct_change(1).shift(-1)   # 내일 수익률
            price["return_3d"] = close.pct_change(3).shift(-3)   # 3일 후 수익률
            price["return_5d"] = close.pct_change(5).shift(-5)   # 5일 후 수익률
            price["label_1d"]  = (price["return_1d"] > 0).astype(int)  # 1=상승, 0=하락
            price["label_3d"]  = (price["return_3d"] > 0).astype(int)

            price = price.reset_index().rename(columns={"index": "date", "Date": "date"})
            price["date"] = pd.to_datetime(price["date"])
            # 워밍업 기간(120일) 데이터 제거 → start_date 이후만 남김
            price = price[price["date"] >= pd.to_datetime(start_date)]
            all_returns.append(price)

        except Exception as e:
            print(f"    ⚠ {code} 수집 실패: {e}")

    if not all_returns:
        raise RuntimeError("주가 데이터를 하나도 수집하지 못했습니다.")

    return pd.concat(all_returns, ignore_index=True)


# ================================================================
# 【5단계】 뉴스 피처 + 주가 JOIN
#
# 역할: (종목코드 × 날짜)를 기준으로 뉴스 집계 피처와
#       주가/기술 지표를 하나의 행으로 합친다.
#
# 왜? → 모델은 "오늘 뉴스 + 오늘 주가지표"를 보고 "내일 상승여부"를
#        예측해야 하므로, 두 데이터를 날짜 기준으로 붙여야 함.
#
# 결과: 학습 가능한 최종 피처 DataFrame
#   (뉴스 피처 + 주가 지표 + 레이블이 모두 포함된 한 행)
# ================================================================
def join_news_price(
    news_agg:   pd.DataFrame,
    price_df:   pd.DataFrame,
) -> pd.DataFrame:
    price_df["date"] = pd.to_datetime(price_df["date"])
    news_agg["date"] = pd.to_datetime(news_agg["date"])

    # JOIN에 사용할 주가 관련 컬럼만 선택
    price_cols = [
        "stock_code", "date", "close",
        "ma_5", "ma_20", "ma_60",
        "close_to_ma5", "close_to_ma20", "ma_cross",
        "rsi_14", "rsi_zone",
        "macd", "macd_sig", "macd_hist", "macd_cross",
        "bb_pctb",
        "volatility_5d", "volatility_20d",
        "volume_ratio",
        "return_before_1d", "return_before_3d", "return_before_5d",
        "return_1d", "return_3d", "return_5d",
        "label_1d", "label_3d",
    ]
    # 실제 DataFrame에 존재하는 컬럼만 사용 (방어 코드)
    price_cols = [c for c in price_cols if c in price_df.columns]

    # 뉴스(종목×날짜) + 주가(종목×날짜) INNER JOIN
    # → 뉴스도 있고 주가도 있는 날짜만 남음
    merged = pd.merge(
        news_agg,
        price_df[price_cols],
        on=["stock_code", "date"],
        how="inner",
    )

    print(f"\n  JOIN 결과: {len(merged)}행")
    # 레이블(내일 수익률)이 없는 행 제거 (가장 최근 날짜는 미래 없음)
    merged = merged.dropna(subset=["label_1d", "return_1d"])
    print(f"  NaN 제거 후: {len(merged)}행")

    return merged


# ================================================================
# 【메인 실행 함수】
#
# 전체 파이프라인을 순서대로 실행:
#   1. 뉴스 로드 → 2. 수치화 → 3. 일별 집계 →
#   4. 주가 수집 → 5. JOIN → features.csv 저장
# ================================================================
def main(args):
    print("=" * 60)
    print("  [1/5] 뉴스 데이터 로드")
    print("=" * 60)
    news_raw = load_news(
        oracle_company_csv=args.oracle_company,
        oracle_sector_csv=args.oracle_sector,
        sqlite_db=args.sqlite,
    )

    print("\n" + "=" * 60)
    print("  [2/5] 뉴스 Feature 수치화")
    print("=" * 60)
    news_feat = build_news_features(news_raw)

    print("\n" + "=" * 60)
    print("  [3/5] 날짜별 집계")
    print("=" * 60)
    news_agg = aggregate_daily(news_feat)
    print(f"  집계 결과: {len(news_agg)}행 (종목×날짜)")

    # 뉴스 데이터의 날짜 범위와 종목 목록 추출
    start = str(news_agg["date"].min().date())
    end   = str(news_agg["date"].max().date())
    codes = news_agg["stock_code"].unique().tolist()
    print(f"  기간: {start} ~ {end}")
    print(f"  종목: {codes}")

    print("\n" + "=" * 60)
    print("  [4/5] 주가 데이터 수집 (FinanceDataReader)")
    print("=" * 60)
    price_df = fetch_stock_returns(codes, start, end)

    print("\n" + "=" * 60)
    print("  [5/5] JOIN + 저장")
    print("=" * 60)
    features = join_news_price(news_agg, price_df)

    # 최종 출력 컬럼 순서 지정
    all_feature_cols = [
        "stock_code", "date",
        # 뉴스 피처 (일별 집계)
        "article_count", "sentiment_mean", "sentiment_sum",
        "positive_count", "negative_count", "neutral_count",
        "positive_ratio", "negative_ratio", "sentiment_intensity",
        "clickbait_mean", "type_prob_mean",
        "fact_ratio", "predict_ratio", "reasoning_ratio",
        # 주가 기술적 지표
        "close",
        "ma_5", "ma_20", "ma_60",
        "close_to_ma5", "close_to_ma20", "ma_cross",
        "rsi_14", "rsi_zone",
        "macd", "macd_sig", "macd_hist", "macd_cross",
        "bb_pctb",
        "volatility_5d", "volatility_20d",
        "volume_ratio",
        "return_before_1d", "return_before_3d", "return_before_5d",
        # 레이블 (모델이 맞춰야 할 정답)
        "return_1d", "return_3d", "return_5d",
        "label_1d", "label_3d",
    ]
    # 실제 존재하는 컬럼만 필터링
    feature_cols = [c for c in all_feature_cols if c in features.columns]
    features = features[feature_cols]

    # CSV로 저장 (BOM 포함 UTF-8: Excel에서도 한글 정상 표시)
    output_path = Path(args.out)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    features.to_csv(output_path, index=False, encoding="utf-8-sig")
    print(f"\n✅ 저장 완료: {output_path}")
    print(f"   shape: {features.shape}")
    print(f"\n레이블 분포 (label_1d):\n{features['label_1d'].value_counts()}")
    print(f"\n종목별 샘플 수:\n{features['stock_code'].value_counts()}")
    print(f"\n피처 미리보기:")
    print(features.head(3).to_string())


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--oracle-company", default=DEFAULT_ORACLE_COMPANY_CSV, help="기업별 뉴스 CSV 경로")
    parser.add_argument("--oracle-sector",  default=DEFAULT_ORACLE_SECTOR_CSV,  help="섹터별 뉴스 CSV 경로")
    parser.add_argument("--sqlite",         default=DEFAULT_SQLITE_DB,          help="SQLite DB 경로")
    parser.add_argument("--out",            default=DEFAULT_FEATURES_CSV,       help="출력 피처 CSV 경로")
    args = parser.parse_args()
    main(args)
