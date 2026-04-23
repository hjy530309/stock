"""
StockTrend.py
───────────────────────────────────────────────────────────────
【역할】 학습된 lgbm_model.pkl을 로드하여 특정 종목의
        내일 주가 방향(상승/하락)을 예측하고 JSON으로 출력.

【입력】
  - lgbm_model.pkl       : train_model.py가 저장한 모델
  - news_data.db         : SQLite 뉴스 DB (최근 뉴스 피처 계산용)
  - oracle_company.csv   : Oracle 기업별 뉴스 CSV (선택)
  - oracle_sector.csv    : Oracle 섹터별 뉴스 CSV (선택)

【출력】
  - stdout: JSON 형식의 예측 결과
    {
      "stock_code": "005930",
      "stock_name": "삼성전자",
      "prediction": "상승",
      "probability": 0.6231,
      "confidence": "중간",
      ...
    }

【왜 JSON stdout인가?】
  Spring 백엔드(Java)에서 ProcessBuilder로 Python 스크립트를 실행하고
  stdout 출력을 파싱하는 방식으로 연동하기 위함.

【사용법】
python StockTrend.py --code 005930
python StockTrend.py --code 005930 --date 2026-04-09
python StockTrend.py --code 005930 --model lgbm_model.pkl

【Spring에서 호출 예시 (Java)】
ProcessBuilder pb = new ProcessBuilder(pythonPath, "StockTrend.py", "--code", stockCode);
    // stdout에서 JSON 파싱
"""

import argparse
import json
import pickle
import sys
import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

try:
    import FinanceDataReader as fdr
except ImportError:
    fdr = None

SCRIPT_DIR = Path(__file__).resolve().parent
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR = SCRIPT_DIR / "data"
DEFAULT_MODEL_PATH = str(MODEL_DIR / "lgbm_model.pkl")
DEFAULT_SQLITE_PATH = str(DATA_DIR / "news_data.db")
DEFAULT_ORACLE_COMPANY_PATH = str(DATA_DIR / "oracle_company.csv")
DEFAULT_ORACLE_SECTOR_PATH = str(DATA_DIR / "oracle_sector.csv")


# ── 카테고리 → 종목코드 매핑 ─────────────────────────────────
# feature_builder.py와 동일한 매핑 유지 (일관성 보장)
CATEGORY_TO_CODE = {
    "반도체/AI":             "005930",
    "IT/반도체":             "005930",
    "삼성전자 (IT/반도체)":  "005930",
    "2차전지":               "373220",
    "LG에너지솔루션 (2차전지)": "373220",
    "바이오":                "207940",
    "제약/바이오":           "207940",
    "삼성바이오로직스 (제약/바이오)": "207940",
    "IT/플랫폼":             "035420",
    "네이버 (IT/플랫폼)":   "035420",
    "자동차/모빌리티":       "005380",
    "현대차 (자동차/모빌리티)": "005380",
    "방산":                  "012450",
    "방산/우주항공":         "012450",
    "한화에어로스페이스 (방산/우주항공)": "012450",
    "엔터/미디어":           "352820",
    "하이브 (엔터/미디어)": "352820",
    "금융/밸류업":           "105560",
    "KB금융 (금융/밸류업)": "105560",
}

# 종목코드 → 한국어 이름 (JSON 응답에서 사람이 읽기 좋도록)
CODE_TO_NAME = {
    "005930": "삼성전자",
    "373220": "LG에너지솔루션",
    "207940": "삼성바이오로직스",
    "035420": "네이버",
    "005380": "현대차",
    "012450": "한화에어로스페이스",
    "352820": "하이브",
    "105560": "KB금융",
}


# ================================================================
# 【1단계】 모델 로드
#
# 역할: pickle 파일에서 모델 + 인코더 + 메타 정보를 불러온다.
#       train_model.py의 save_model()이 저장한 dict 구조.
#
# 반환값 (dict):
#   - model         : LightGBM 모델 객체
#   - label_encoder : 종목코드 인코더
#   - feature_cols  : 피처 순서 목록
#   - meta          : CV AUC, 학습 종목 목록 등
# ================================================================
def load_model(model_path: str) -> dict:
    with open(model_path, "rb") as f:
        return pickle.load(f)


# ================================================================
# 【2단계】 최근 뉴스 피처 생성
#
# 역할: 예측 기준일로부터 최근 days_back일 이내의 뉴스를
#       집계하여 피처를 생성한다.
#
# 왜 필요한가?
#   모델은 학습 시 "오늘 뉴스 집계 피처"를 입력으로 받았으므로,
#   예측 시에도 같은 방식으로 뉴스를 집계해서 입력해야 함.
#
# 입력 소스:
#   - oracle_company.csv / oracle_sector.csv : 과거 뉴스
#   - news_data.db : Spring 백엔드가 실시간 수집한 최신 뉴스
#
# 반환값: 피처 이름 → 수치 딕셔너리
#   없으면 빈 dict {} 반환 → predict()에서 "뉴스 없음" 처리
# ================================================================
def get_news_features(
        stock_code:      str,
        target_date:     str,
        sqlite_db:       str = DEFAULT_SQLITE_PATH,
        oracle_company:  str = DEFAULT_ORACLE_COMPANY_PATH,
        oracle_sector:   str = DEFAULT_ORACLE_SECTOR_PATH,
        days_back:       int = 3,
) -> dict:
    date_end   = pd.to_datetime(target_date)
    date_start = date_end - timedelta(days=days_back)

    # 해당 종목에 매핑되는 카테고리 목록 추출
    target_cats = [cat for cat, code in CATEGORY_TO_CODE.items() if code == stock_code]

    dfs = []
    # Oracle CSV 파일 로드
    for path in [oracle_company, oracle_sector]:
        if Path(path).exists():
            df = pd.read_csv(path)
            df.columns = [c.lower() for c in df.columns]
            dfs.append(df)

    # SQLite 뉴스 DB 로드
    if Path(sqlite_db).exists():
        conn = sqlite3.connect(sqlite_db)
        df_sq = pd.read_sql("SELECT * FROM news_data", conn)
        conn.close()
        df_sq = df_sq.drop(columns=["created_at"], errors="ignore")
        dfs.append(df_sq)

    if not dfs:
        return {}  # 데이터 소스 없음

    df_all = pd.concat(dfs, ignore_index=True).drop_duplicates(subset="link")
    df_all["pub_date"] = pd.to_datetime(df_all["pub_date"], errors="coerce")

    # 해당 종목 + 날짜 범위 필터링
    df_filtered = df_all[
        (df_all["category"].isin(target_cats)) &
        (df_all["pub_date"] >= date_start) &
        (df_all["pub_date"] <= date_end)
        ].copy()

    if len(df_filtered) == 0:
        return {}  # 뉴스 없음

    # 감성 수치화
    sentiment_map = {"호재": 1, "중립": 0, "악재": -1}
    df_filtered["sentiment_score"] = df_filtered["sentiment"].map(sentiment_map).fillna(0)
    df_filtered["clickbait_prob"]  = pd.to_numeric(df_filtered["clickbait_prob"], errors="coerce").fillna(0)
    df_filtered["type_prob"]       = pd.to_numeric(df_filtered["type_prob"],      errors="coerce").fillna(0)
    df_filtered["is_fact"]         = (df_filtered["article_type"] == "사실형").astype(int)
    df_filtered["is_predict"]      = (df_filtered["article_type"] == "예측형").astype(int)
    df_filtered["is_reasoning"]    = (df_filtered["article_type"] == "추론형").astype(int)

    n     = len(df_filtered)
    pos_n = (df_filtered["sentiment_score"] == 1).sum()
    neg_n = (df_filtered["sentiment_score"] == -1).sum()

    return {
        "article_count":       n,
        "sentiment_mean":      df_filtered["sentiment_score"].mean(),
        "sentiment_sum":       df_filtered["sentiment_score"].sum(),
        "positive_count":      int(pos_n),
        "negative_count":      int(neg_n),
        "neutral_count":       int(n - pos_n - neg_n),
        "positive_ratio":      pos_n / n,
        "negative_ratio":      neg_n / n,
        "sentiment_intensity": (pos_n - neg_n) / n,    # 감성 강도
        "clickbait_mean":      df_filtered["clickbait_prob"].mean(),
        "type_prob_mean":      df_filtered["type_prob"].mean(),
        "fact_ratio":          df_filtered["is_fact"].mean(),
        "predict_ratio":       df_filtered["is_predict"].mean(),
        "reasoning_ratio":     df_filtered["is_reasoning"].mean(),
        # 최근 5건 기사 목록 (JSON 응답에 포함하여 사용자에게 근거 제시)
        "recent_articles":     df_filtered[["title", "sentiment", "pub_date"]]
        .sort_values("pub_date", ascending=False)
        .head(5)
        .to_dict("records"),
    }


# ================================================================
# 【3단계】 주가 기술적 지표 계산
#
# 역할: FinanceDataReader로 주가 데이터를 수집하고
#       기술적 지표를 계산하여 피처 딕셔너리로 반환.
#
# feature_builder.py와 동일한 로직 사용 (일관성 필수).
# 학습 시 사용한 것과 다른 방식으로 계산하면 성능 저하.
#
# MA60 워밍업을 위해 target_date보다 120일 앞서 수집.
# ================================================================
def _rsi(series, period=14):
    """RSI(상대강도지수) = 100 - 100/(1+RS). RS = 평균상승/평균하락."""
    delta = series.diff()
    gain  = delta.clip(lower=0).rolling(period).mean()
    loss  = (-delta.clip(upper=0)).rolling(period).mean()
    rs    = gain / loss.replace(0, np.nan)
    return 100 - (100 / (1 + rs))

def _macd_hist(series, fast=12, slow=26, signal=9):
    """MACD Histogram = MACD 라인 - Signal 라인. 모멘텀 방향 표시."""
    macd = series.ewm(span=fast, adjust=False).mean() - series.ewm(span=slow, adjust=False).mean()
    return macd - macd.ewm(span=signal, adjust=False).mean()

def _bb_pctb(series, period=20, std_k=2):
    """볼린저밴드 %B: 현재가가 밴드 내 어느 위치인지 (0=하단, 0.5=중앙, 1=상단)."""
    ma  = series.rolling(period).mean()
    std = series.rolling(period).std()
    return (series - (ma - std_k * std)) / (2 * std_k * std).replace(0, np.nan)


def _empty_price_features() -> dict:
    # 로컬 환경에 가격 수집 라이브러리가 없더라도 뉴스 기반 예측 자체는 계속 보여주기 위한 기본값이다.
    return {
        "close": 0.0,
        "close_to_ma5": 0.0,
        "close_to_ma20": 0.0,
        "ma_cross": 0.0,
        "rsi_14": 0.0,
        "rsi_zone": 0.0,
        "macd_hist": 0.0,
        "macd_cross": 0.0,
        "bb_pctb": 0.0,
        "volatility_5d": 0.0,
        "volatility_20d": 0.0,
        "volume_ratio": 1.0,
        "return_before_1d": 0.0,
        "return_before_3d": 0.0,
        "return_before_5d": 0.0,
    }


def _normalize_percent_ratio(value: float) -> float:
    if value is None or np.isnan(float(value)):
        return 0.0
    value = float(value)
    return value / 100.0 if value > 1.0 else value


def _confidence_label(prob: float) -> str:
    conf = abs(prob - 0.5) * 2
    if conf > 0.6:
        return "높음"
    if conf > 0.3:
        return "중간"
    return "낮음 (참고만)"


def _heuristic_probability(news_feat: dict, price_feat: dict) -> float:
    sentiment_intensity = float(news_feat.get("sentiment_intensity", 0.0))
    positive_ratio = float(news_feat.get("positive_ratio", 0.0))
    negative_ratio = float(news_feat.get("negative_ratio", 0.0))
    article_count = float(news_feat.get("article_count", 0.0))
    clickbait_ratio = _normalize_percent_ratio(news_feat.get("clickbait_mean", 0.0))
    type_ratio = _normalize_percent_ratio(news_feat.get("type_prob_mean", 0.0))
    fact_ratio = _normalize_percent_ratio(news_feat.get("fact_ratio", 0.0))
    volatility = float(price_feat.get("volatility_20d", 0.0))
    return_1d = float(price_feat.get("return_before_1d", 0.0))
    return_3d = float(price_feat.get("return_before_3d", 0.0))

    score = 0.0
    score += sentiment_intensity * 2.1
    score += (positive_ratio - negative_ratio) * 0.8
    score += (type_ratio - 0.5) * 0.5
    score += (fact_ratio - 0.5) * 0.25
    score -= (clickbait_ratio - 0.3) * 0.45
    score += np.tanh(article_count / 40.0) * 0.2
    score -= volatility * 2.5
    score += return_1d * 1.8
    score += return_3d * 1.1

    probability = 1.0 / (1.0 + np.exp(-score))
    return float(np.clip(probability, 0.05, 0.95))


def _build_prediction_result(
        stock_code: str,
        target_date: str,
        probability: float,
        news_feat: dict,
        price_feat: dict,
        meta: dict | None = None,
        warning_message: str = "",
) -> dict:
    meta = meta or {}
    pred = int(probability > 0.5)
    model_warning = meta.get("warning", "") if isinstance(meta, dict) else ""
    if warning_message:
        model_warning = f"{model_warning} / {warning_message}" if model_warning else warning_message

    return {
        "stock_code":          stock_code,
        "stock_name":          CODE_TO_NAME.get(stock_code, stock_code),
        "date":                target_date,
        "prediction":          "상승" if pred == 1 else "하락",
        "prediction_int":      pred,
        "probability":         round(float(probability), 4),
        "confidence":          _confidence_label(float(probability)),
        "article_count":       news_feat.get("article_count", 0),
        "sentiment_mean":      round(news_feat.get("sentiment_mean", 0), 3),
        "sentiment_intensity": round(news_feat.get("sentiment_intensity", 0), 3),
        "clickbait_mean":      round(news_feat.get("clickbait_mean", 0), 2),
        "type_prob_mean":      round(news_feat.get("type_prob_mean", 0), 2),
        "fact_ratio":          round(news_feat.get("fact_ratio", 0), 3),
        "volatility_20d":      round(price_feat.get("volatility_20d", 0), 4),
        "recent_articles": [
            {
                "title":     a["title"],
                "sentiment": a["sentiment"],
                "date":      str(a["pub_date"])[:10],
            }
            for a in news_feat.get("recent_articles", [])
        ],
        "model_meta": {
            "cv_auc":  round(meta.get("cv_auc_mean", meta.get("cv_auc", 0)), 4),
            "n_train": meta.get("n_samples", meta.get("n_train", 0)),
            "warning": model_warning,
        },
    }

def get_price_features(stock_code: str, target_date: str) -> dict:
    """
    target_date 기준 주가 기술적 지표 계산.
    반환: 피처명 → 수치 딕셔너리
    오류 발생 시 모든 값을 0.0으로 반환 (예측은 계속 진행).
    """
    if fdr is None:
        return _empty_price_features()

    try:
        # MA60 등 지표 계산에 충분한 과거 데이터 확보
        start = (pd.to_datetime(target_date) - timedelta(days=120)).strftime("%Y-%m-%d")
        raw   = fdr.DataReader(stock_code, start, target_date)
        raw.index = pd.to_datetime(raw.index)

        close  = raw["Close"]
        volume = raw["Volume"] if "Volume" in raw.columns else pd.Series(dtype=float)

        ma5  = close.rolling(5).mean()
        ma20 = close.rolling(20).mean()
        ma60 = close.rolling(60).mean()

        def safe(val):
            """NaN/무한대를 0.0으로 변환."""
            return float(val) if (val is not None and not np.isnan(float(val))) else 0.0

        last    = close.iloc[-1]   # 가장 최근 종가
        vol_ma5 = volume.rolling(5).mean().iloc[-1] if len(volume) > 0 else np.nan

        return {
            "close":            safe(last),
            # 현재가 vs 이동평균 괴리율 (양수=MA 위에 있음)
            "close_to_ma5":     safe((last - ma5.iloc[-1])  / ma5.iloc[-1])  if not np.isnan(ma5.iloc[-1])  else 0.0,
            "close_to_ma20":    safe((last - ma20.iloc[-1]) / ma20.iloc[-1]) if not np.isnan(ma20.iloc[-1]) else 0.0,
            # MA 크로스: +1=골든크로스, -1=데드크로스
            "ma_cross":         safe(np.sign(ma5.iloc[-1] - ma20.iloc[-1])),
            "rsi_14":           safe(_rsi(close, 14).iloc[-1]),
            "rsi_zone":         safe(np.sign(_rsi(close, 14).iloc[-1] - 50)),  # +1=과매수, -1=과매도
            "macd_hist":        safe(_macd_hist(close).iloc[-1]),
            "macd_cross":       safe(np.sign(_macd_hist(close).iloc[-1])),
            "bb_pctb":          safe(_bb_pctb(close).iloc[-1]),
            "volatility_5d":    safe(close.pct_change().rolling(5).std().iloc[-1]),
            "volatility_20d":   safe(close.pct_change().rolling(20).std().iloc[-1]),
            "volume_ratio":     safe(volume.iloc[-1] / vol_ma5) if len(volume) > 0 and not np.isnan(vol_ma5) else 1.0,
            "return_before_1d": safe(close.pct_change().iloc[-1]),
            "return_before_3d": safe(close.pct_change(3).iloc[-1]),
            "return_before_5d": safe(close.pct_change(5).iloc[-1]),
        }
    except Exception:
        # 주가 수집/계산 실패 → 0으로 채워서 예측 계속 진행
        return _empty_price_features()


# ================================================================
# 【4단계】 예측 실행
#
# 역할: 뉴스 피처 + 주가 피처를 모델에 입력하여 예측 결과 반환.
#
# 처리 흐름:
#   1. 종목코드 → 숫자 인코딩 (학습 시 LabelEncoder 재사용)
#   2. 뉴스 피처 집계 (get_news_features)
#   3. 주가 기술 지표 계산 (get_price_features)
#   4. feature_cols 순서에 맞게 피처 벡터 구성
#   5. 모델 예측 → 상승 확률 → 상승/하락 이진 판정
#   6. 확신도 계산 (확률이 0.5에서 얼마나 멀리 있는지)
#
# 확신도:
#   확률이 0.5에서 멀수록 모델이 확신함.
#   abs(prob - 0.5) * 2 → 0~1 범위로 정규화
#   > 0.6: 높음 / > 0.3: 중간 / 이하: 낮음
# ================================================================
def predict(
        payload:        dict | None,
        stock_code:     str,
        target_date:    str,
        sqlite_db:      str = DEFAULT_SQLITE_PATH,
        oracle_company: str = DEFAULT_ORACLE_COMPANY_PATH,
        oracle_sector:  str = DEFAULT_ORACLE_SECTOR_PATH,
        model_warning:  str = "",
) -> dict:
    meta = payload.get("meta", {}) if isinstance(payload, dict) else {}

    # 뉴스 피처 생성
    news_feat  = get_news_features(
        stock_code,
        target_date,
        sqlite_db=sqlite_db,
        oracle_company=oracle_company,
        oracle_sector=oracle_sector,
    )
    # 주가 기술 지표 피처 생성
    price_feat = get_price_features(stock_code, target_date)

    # 뉴스 데이터 없으면 예측 불가 → 조기 반환
    if not news_feat:
        return {
            "stock_code":  stock_code,
            "stock_name":  CODE_TO_NAME.get(stock_code, stock_code),
            "date":        target_date,
            "prediction":  None,
            "probability": None,
            "confidence":  "데이터 없음",
            "message":     f"최근 3일 내 뉴스 없음 (종목코드: {stock_code})",
            "articles":    [],
        }

    prob = None
    warning_message = model_warning

    if isinstance(payload, dict):
        try:
            model_obj = payload["model"]
            le = payload["label_encoder"]
            feature_cols = payload["feature_cols"]

            if stock_code in le.classes_:
                stock_code_enc = int(le.transform([stock_code])[0])
            else:
                stock_code_enc = -1

            feature_map = {**news_feat, **price_feat, "stock_code_enc": stock_code_enc}
            X = np.array([[feature_map.get(col, 0.0) for col in feature_cols]])
            prob = float(model_obj.predict(X)[0])
        except Exception as e:
            warning_message = f"모델 예측 실패로 뉴스 기반 보조 확률 사용 ({type(e).__name__})"

    if prob is None:
        prob = _heuristic_probability(news_feat, price_feat)

    if meta.get("n_samples", 0) < 200:
        extra_warning = "데이터 부족으로 신뢰도 낮을 수 있음"
        warning_message = f"{warning_message} / {extra_warning}" if warning_message else extra_warning

    return _build_prediction_result(
        stock_code=stock_code,
        target_date=target_date,
        probability=prob,
        news_feat=news_feat,
        price_feat=price_feat,
        meta=meta,
        warning_message=warning_message,
    )


# ================================================================
# 【메인 실행 함수】
# ================================================================
def main(args):
    # 모델 파일 존재 확인
    if not Path(args.model).exists():
        result = {"error": f"모델 파일 없음: {args.model}. train_model.py 먼저 실행하세요."}
        print(json.dumps(result, ensure_ascii=False))
        sys.exit(1)

    # 날짜 기본값: 오늘 (--date 생략 시)
    target_date = args.date or datetime.now().strftime("%Y-%m-%d")
    payload = None
    model_warning = ""

    try:
        payload = load_model(args.model)
    except Exception as e:
        model_warning = f"모델 로드 실패로 뉴스 기반 보조 확률 사용 ({type(e).__name__})"

    result = predict(
        payload,
        args.code,
        target_date,
        sqlite_db=args.sqlite,
        oracle_company=args.oracle_company,
        oracle_sector=args.oracle_sector,
        model_warning=model_warning,
    )
    # JSON 출력 (Spring에서 파싱)
    print(json.dumps(result, ensure_ascii=False, default=str))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--code",  required=True,              help="종목코드 (예: 005930)")
    parser.add_argument("--date",  default=None,               help="예측 기준일 (기본: 오늘, 형식: YYYY-MM-DD)")
    parser.add_argument("--model", default="lgbm_model.pkl",   help="모델 파일 경로")
    parser.add_argument("--sqlite",default="news_data.db",     help="SQLite DB 경로")
    parser.add_argument("--oracle-company", default="oracle_company.csv", help="기업별 뉴스 CSV")
    parser.add_argument("--oracle-sector",  default="oracle_sector.csv",  help="섹터별 뉴스 CSV")
    args = parser.parse_args()
    if args.model == "lgbm_model.pkl":
        args.model = DEFAULT_MODEL_PATH
    if args.sqlite == "news_data.db":
        args.sqlite = DEFAULT_SQLITE_PATH
    if args.oracle_company == "oracle_company.csv":
        args.oracle_company = DEFAULT_ORACLE_COMPANY_PATH
    if args.oracle_sector == "oracle_sector.csv":
        args.oracle_sector = DEFAULT_ORACLE_SECTOR_PATH
    main(args)
