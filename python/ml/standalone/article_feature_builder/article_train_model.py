"""
article_train_model.py
───────────────────────────────────────────────────────────────
【역할】 article_feature_builder.py가 만든 article_features.csv를 읽어
        기사(Article) 단위 LightGBM 모델을 학습하고 저장한다.

【train_model.py와의 차이점】
  train_model.py          : 일(日)별 집계 피처 기반, 종목 전체 방향 예측
  article_train_model.py  : 기사 1건 단위 피처 기반, 개별 기사의 주가 영향 예측
                            → 더 많은 피처 사용 (제목 길이, 발행 시각, 맥락 등)

【입력】
  - article_features.csv : article_feature_builder.py가 생성한 기사 단위 데이터

【출력】
  - article_lgbm_model.pkl      : 학습된 기사 영향 모델
  - article_lgbm_model_meta.json: 모델 메타 정보

【사용법】
    python article_train_model.py
    python article_train_model.py --features article_features.csv --model article_lgbm_model.pkl
"""

from __future__ import annotations

import argparse
import json
import pickle
import warnings
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, roc_auc_score
from sklearn.model_selection import TimeSeriesSplit
from sklearn.preprocessing import LabelEncoder

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR.mkdir(parents=True, exist_ok=True)
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# PyCharm에서 별도로 보관/실행할 때 사용할 기본 경로입니다.
DEFAULT_ARTICLE_FEATURES_CSV = str(DATA_DIR / "article_features.csv")
DEFAULT_ARTICLE_MODEL_PATH = str(MODEL_DIR / "article_lgbm_model.pkl")

warnings.filterwarnings("ignore")

try:
    import lightgbm as lgb
except ImportError as exc:
    raise SystemExit("lightgbm is required. Install it with: pip install lightgbm") from exc


# ================================================================
# 【피처 컬럼 정의】
#
# train_model.py의 FEATURE_COLS보다 훨씬 많은 피처 사용.
# 기사 단위로만 알 수 있는 정보(제목 길이, 발행 시각 등)가 추가됨.
#
# 크게 5개 그룹:
#   1. 기사 자체 피처      : 감성, 클릭베이트, 기사 유형, 텍스트 길이
#   2. 발행 시각 피처      : 시간대, 요일, 장 전/후 여부
#   3. 최근 맥락 피처(_3d): 직전 3일간 뉴스 분위기
#   4. 주가 기술적 지표    : 이동평균, RSI, MACD 등
#   5. stock_code_enc      : 종목 인코딩 (자동 추가)
# ================================================================
DEFAULT_FEATURE_COLS = [
    # ── 기사 자체 피처 ────────────────────────────────────────────
    "sentiment_score",       # 감성 수치 (1=호재, 0=중립, -1=악재)
    "clickbait_prob",        # 클릭베이트(낚시성) 확률 (0~1)
    "type_prob",             # 기사 유형 확률 (사실/예측/추론)
    "is_fact",               # 사실형 기사 여부 (0 또는 1)
    "is_predict",            # 예측형 기사 여부 (0 또는 1)
    "is_reasoning",          # 추론형 기사 여부 (0 또는 1)
    "title_length",          # 제목 글자 수 (길수록 상세한 정보)
    "summary_length",        # 요약 글자 수
    "title_word_count",      # 제목 단어 수
    "summary_word_count",    # 요약 단어 수
    "has_summary",           # 요약 존재 여부 (0 또는 1)
    # ── 발행 시각 피처 ────────────────────────────────────────────
    "pub_hour",              # 발행 시각 (0~23)
    "pub_weekday",           # 발행 요일 (0=월~6=일)
    "is_weekend",            # 주말 발행 여부
    "is_before_open",        # 장 시작 전(9시 이전) 발행 여부
    "is_after_close",        # 장 마감 후(15:30 이후) 발행 여부
    "anchor_delay_days",     # 뉴스 발행일 → 실제 반영 거래일까지 일수 차이
    # ── 최근 3일 맥락 피처 (_3d = 직전 3일간 같은 종목 뉴스 요약) ──
    "recent_article_count_3d",      # 최근 3일 기사 수
    "recent_sentiment_mean_3d",     # 최근 3일 감성 평균
    "recent_sentiment_sum_3d",      # 최근 3일 감성 합계
    "recent_positive_ratio_3d",     # 최근 3일 호재 비율
    "recent_negative_ratio_3d",     # 최근 3일 악재 비율
    "recent_sentiment_intensity_3d",# 최근 3일 감성 강도 (호재-악재 비율)
    "recent_clickbait_mean_3d",     # 최근 3일 클릭베이트 평균
    "recent_type_prob_mean_3d",     # 최근 3일 유형확률 평균
    "recent_fact_ratio_3d",         # 최근 3일 사실형 비율
    "recent_predict_ratio_3d",      # 최근 3일 예측형 비율
    "recent_reasoning_ratio_3d",    # 최근 3일 추론형 비율
    "hours_since_prev_article",     # 직전 기사 이후 경과 시간(시간 단위)
    # ── 주가 기술적 지표 (기사 발행 시점 기준) ────────────────────
    "close",             # 당시 종가
    "ma_5",              # 5일 이동평균
    "ma_20",             # 20일 이동평균
    "ma_60",             # 60일 이동평균
    "close_to_ma5",      # 종가 vs MA5 괴리율
    "close_to_ma20",     # 종가 vs MA20 괴리율
    "ma_cross",          # 골든/데드크로스 방향
    "rsi_14",            # RSI(14일)
    "rsi_zone",          # RSI 구간 (-1/0/1)
    "macd",              # MACD 라인
    "macd_sig",          # MACD Signal 라인
    "macd_hist",         # MACD Histogram
    "macd_cross",        # MACD 크로스 방향
    "bb_pctb",           # 볼린저밴드 %B
    "volatility_5d",     # 5일 변동성
    "volatility_20d",    # 20일 변동성
    "volume_ratio",      # 거래량 비율
    "return_before_1d",  # 어제 수익률
    "return_before_3d",  # 3일 전 수익률
    "return_before_5d",  # 5일 전 수익률
]


def normalize_stock_code(value: object) -> str | None:
    """종목코드를 6자리 문자열로 정규화. 예) 5930 → '005930'"""
    if pd.isna(value):
        return None
    text = str(value).strip()
    if not text:
        return None
    if text.endswith(".0"):
        text = text[:-2]
    digits = "".join(ch for ch in text if ch.isdigit())
    if digits:
        return digits.zfill(6)
    return text


# ================================================================
# 【1단계】 데이터 로드
#
# article_features.csv를 로드하고 발행일 기준으로 정렬.
# 시계열 순서 유지가 TimeSeriesSplit 교차검증에 필수.
# ================================================================
def load_features(path: str, target_col: str) -> pd.DataFrame:
    df = pd.read_csv(path, dtype={"stock_code": str})
    df["stock_code"] = df["stock_code"].map(normalize_stock_code)
    df["pub_date"]   = pd.to_datetime(df["pub_date"], errors="coerce")
    df = df.dropna(subset=["pub_date"]).sort_values("pub_date").reset_index(drop=True)
    df = df.dropna(subset=[target_col]).reset_index(drop=True)  # 레이블 없는 행 제거
    print(f"  rows: {len(df)}")
    print(f"  period: {df['pub_date'].min()} -> {df['pub_date'].max()}")
    print(f"  target distribution:\n{df[target_col].value_counts().to_string()}")
    print(f"  stock rows:\n{df['stock_code'].value_counts().to_string()}")
    return df


# ================================================================
# 【2단계】 전처리
#
# 종목코드 → 숫자 인코딩 + 피처 행렬 / 레이블 벡터 생성.
# train_model.py의 preprocess()와 동일 역할이지만
# 더 많은 피처 컬럼을 처리함.
# ================================================================
def preprocess(
    df: pd.DataFrame,
    feature_cols: list[str],
    target_col: str,
) -> tuple[np.ndarray, np.ndarray, list[str], LabelEncoder, pd.DataFrame]:
    df = df.copy()
    encoder = LabelEncoder()
    df["stock_code_enc"] = encoder.fit_transform(df["stock_code"])
    all_feature_cols = feature_cols + ["stock_code_enc"]

    # 모든 피처를 수치형으로 변환 (오류 시 NaN → 0 대체)
    for column in all_feature_cols:
        df[column] = pd.to_numeric(df[column], errors="coerce")
    df[all_feature_cols] = df[all_feature_cols].fillna(0.0)

    X = df[all_feature_cols].to_numpy(dtype=float)
    y = pd.to_numeric(df[target_col], errors="coerce").astype(int).to_numpy()
    return X, y, all_feature_cols, encoder, df


# ================================================================
# 【3단계】 클래스 불균형 보정 가중치 계산
# (train_model.py의 calc_pos_weight와 동일 역할)
# ================================================================
def calc_pos_weight(y: np.ndarray) -> float:
    """하락(0) 샘플 수 / 상승(1) 샘플 수 → scale_pos_weight 반환."""
    neg = int((y == 0).sum())
    pos = int((y == 1).sum())
    if pos == 0:
        return 1.0
    ratio = neg / pos
    print(f"  class ratio (0:1) = {neg}:{pos} -> scale_pos_weight={ratio:.3f}")
    return ratio


# ================================================================
# 【4단계】 LightGBM 학습 (TimeSeriesSplit 교차검증)
#
# train_model.py와 동일한 구조이지만 기사(pub_date) 기준으로 분할.
# 기사 단위라 샘플 수가 더 많아 CV가 더 안정적임.
# ================================================================
def train_lgbm(
    X: np.ndarray,
    y: np.ndarray,
    feature_names: list[str],
    n_splits: int,
) -> tuple[object, object | None, list[float], list[float]]:
    params = {
        "objective":         "binary",          # 이진 분류
        "metric":            "binary_logloss",
        "boosting_type":     "gbdt",
        "num_leaves":        31,
        "learning_rate":     0.05,
        "feature_fraction":  0.8,
        "bagging_fraction":  0.8,
        "bagging_freq":      5,
        "scale_pos_weight":  calc_pos_weight(y),  # 클래스 불균형 보정
        "min_child_samples": 5,
        "verbose":           -1,
        "random_state":      42,
    }

    splitter   = TimeSeriesSplit(n_splits=n_splits)  # 시계열 교차검증
    cv_scores  : list[float] = []
    cv_aucs    : list[float] = []
    best_model = None
    best_auc   = -1.0

    print(f"\n  TimeSeriesSplit(n_splits={n_splits})")
    for fold, (train_idx, val_idx) in enumerate(splitter.split(X), start=1):
        X_train, X_val = X[train_idx], X[val_idx]
        y_train, y_val = y[train_idx], y[val_idx]

        if len(np.unique(y_train)) < 2 or len(np.unique(y_val)) < 2:
            print(f"  fold {fold}: skipped because a class is missing")
            continue

        train_set = lgb.Dataset(X_train, label=y_train, feature_name=feature_names)
        val_set   = lgb.Dataset(X_val,   label=y_val,   feature_name=feature_names)
        model = lgb.train(
            params,
            train_set,
            num_boost_round=500,
            valid_sets=[val_set],
            callbacks=[lgb.early_stopping(50, verbose=False), lgb.log_evaluation(-1)],
        )

        prob = model.predict(X_val)
        pred = (prob > 0.5).astype(int)
        acc  = accuracy_score(y_val, pred)
        try:
            auc = roc_auc_score(y_val, prob)
        except ValueError:
            auc = 0.5

        cv_scores.append(float(acc))
        cv_aucs.append(float(auc))
        print(f"  fold {fold}: acc={acc:.4f}, auc={auc:.4f}, train={len(train_idx)}, val={len(val_idx)}")

        if auc > best_auc:
            best_auc   = auc
            best_model = model

    if not cv_scores:
        raise RuntimeError("Cross-validation could not run. Check data size and label balance.")

    print(f"\n  cv acc mean={np.mean(cv_scores):.4f} std={np.std(cv_scores):.4f}")
    print(f"  cv auc mean={np.mean(cv_aucs):.4f} std={np.std(cv_aucs):.4f}")

    # 전체 데이터로 최종 모델 재학습 (best fold의 트리 수 사용)
    full_set = lgb.Dataset(X, label=y, feature_name=feature_names)
    final_model = lgb.train(
        params,
        full_set,
        num_boost_round=best_model.best_iteration if best_model is not None else 100,
        callbacks=[lgb.log_evaluation(-1)],
    )

    return final_model, best_model, cv_scores, cv_aucs


# ================================================================
# 【보조 함수】 피처 중요도 출력
# ================================================================
def print_feature_importance(model: object, feature_names: list[str]) -> pd.DataFrame:
    importance = model.feature_importance(importance_type="gain")
    fi = pd.DataFrame({"feature": feature_names, "importance": importance})
    fi = fi.sort_values("importance", ascending=False).reset_index(drop=True)
    print("\n  feature importance (gain 기준):")
    for row in fi.head(20).itertuples(index=False):
        print(f"    {row.feature:30s} {row.importance:.2f}")
    return fi


# ================================================================
# 【보조 함수】 최종 평가 (전체 데이터 기준 — 참고용)
# ================================================================
def evaluate(model: object, X: np.ndarray, y: np.ndarray) -> None:
    prob = model.predict(X)
    pred = (prob > 0.5).astype(int)
    print("\n  full-train reference metrics (과적합 가능, 참고용)")
    print(f"  accuracy: {accuracy_score(y, pred):.4f}")
    try:
        print(f"  auc:      {roc_auc_score(y, prob):.4f}")
    except ValueError:
        pass
    print("  classification report:")
    print(classification_report(y, pred, target_names=["down(0)", "up(1)"]))
    print("  confusion matrix:")
    print(confusion_matrix(y, pred))


# ================================================================
# 【보조 함수】 모델 저장
#
# pkl 파일에 저장되는 내용:
#   - model        : 학습된 LightGBM 모델
#   - label_encoder: 종목코드 인코더 (article_predict.py에서 복원)
#   - feature_cols : 피처 컬럼 순서 (예측 시 동일 순서로 입력 필요)
#   - meta         : CV 성능, 피처 중요도, 학습 종목 목록 등
# ================================================================
def save_model(
    model: object,
    encoder: LabelEncoder,
    feature_cols: list[str],
    out_path: str,
    meta: dict,
) -> None:
    output_path = Path(out_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "model":         model,
        "label_encoder": encoder,
        "feature_cols":  feature_cols,
        "meta":          meta,
    }
    with open(output_path, "wb") as handle:
        pickle.dump(payload, handle)
    print(f"\n  saved model: {output_path}")

    # JSON 메타 파일도 저장 (Spring 연동, 모델 모니터링용)
    meta_path = output_path.with_name(f"{output_path.stem}_meta.json")
    with open(meta_path, "w", encoding="utf-8") as handle:
        json.dump(meta, handle, ensure_ascii=False, indent=2)
    print(f"  saved meta: {meta_path}")


# ================================================================
# 【메인 실행 함수】
# ================================================================
def main(args: argparse.Namespace) -> None:
    print("=" * 60)
    print("[1/4] Load article features")
    print("=" * 60)
    df = load_features(args.features, target_col=args.target)
    if len(df) < 20:
        raise SystemExit(f"Not enough rows to train: {len(df)}")

    # CSV에 실제로 존재하는 피처만 사용 (누락 컬럼은 경고 후 스킵)
    feature_cols = [col for col in DEFAULT_FEATURE_COLS if col in df.columns]
    missing      = [col for col in DEFAULT_FEATURE_COLS if col not in df.columns]
    if missing:
        print(f"  missing feature columns (skipped): {missing}")

    print("\n" + "=" * 60)
    print("[2/4] Preprocess")
    print("=" * 60)
    X, y, all_feature_cols, encoder, df_proc = preprocess(
        df=df,
        feature_cols=feature_cols,
        target_col=args.target,
    )
    print(f"  X shape: {X.shape}")
    print(f"  y shape: {y.shape}")

    print("\n" + "=" * 60)
    print("[3/4] Train LightGBM")
    print("=" * 60)
    final_model, best_model, cv_scores, cv_aucs = train_lgbm(
        X=X,
        y=y,
        feature_names=all_feature_cols,
        n_splits=args.cv_splits,
    )
    fi = print_feature_importance(final_model, all_feature_cols)

    print("\n" + "=" * 60)
    print("[4/4] Evaluate and save")
    print("=" * 60)
    evaluate(final_model, X, y)

    meta = {
        "target":             args.target,
        "feature_cols":       all_feature_cols,
        "cv_acc_mean":        float(np.mean(cv_scores)),
        "cv_acc_std":         float(np.std(cv_scores)),
        "cv_auc_mean":        float(np.mean(cv_aucs)),
        "cv_auc_std":         float(np.std(cv_aucs)),
        "n_samples":          int(len(df_proc)),
        "stock_classes":      encoder.classes_.tolist(),
        "label_distribution": df_proc[args.target].value_counts().to_dict(),
        "feature_importance": fi.set_index("feature")["importance"].to_dict(),
    }
    save_model(
        model=final_model,
        encoder=encoder,
        feature_cols=all_feature_cols,
        out_path=args.model,
        meta=meta,
    )

    print("\n  training complete")
    print(f"  cv auc: {np.mean(cv_aucs):.4f}  (0.5=랜덤, 1.0=완벽)")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--features",  default=DEFAULT_ARTICLE_FEATURES_CSV, help="article_feature_builder.py 출력 CSV")
    parser.add_argument("--model",     default=DEFAULT_ARTICLE_MODEL_PATH,   help="저장할 모델 파일명")
    parser.add_argument("--target",    default="label_1d",               help="예측 대상 레이블 컬럼")
    parser.add_argument("--cv-splits", type=int, default=3,              help="TimeSeriesSplit fold 수")
    main(parser.parse_args())
