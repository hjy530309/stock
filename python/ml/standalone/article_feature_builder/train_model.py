"""
train_model.py
───────────────────────────────────────────────────────────────
【역할】 feature_builder.py가 만든 features.csv를 읽어
        LightGBM 분류 모델을 학습하고 lgbm_model.pkl로 저장한다.

【입력】
  - features.csv : feature_builder.py가 생성한 학습 데이터
                   (뉴스 피처 + 주가 지표 + 레이블)

【출력】
  - lgbm_model.pkl      : 학습된 모델 파일 (Spring에서 로드하여 사용)
  - lgbm_model_meta.json: 모델 메타 정보 (AUC, 피처 중요도 등)

【왜 LightGBM인가?】
  - 테이블형 데이터(CSV)에 가장 성능이 좋은 ML 알고리즘 중 하나
  - 빠른 학습 속도, 불균형 클래스 처리(scale_pos_weight) 내장
  - 피처 중요도 시각화 가능 → 어떤 피처가 예측에 기여하는지 파악 용이

【왜 TimeSeriesSplit CV인가?】
  시계열 데이터는 미래 데이터가 학습에 쓰이면 안 됨.
  일반 K-Fold는 미래 데이터가 훈련셋에 섞일 수 있어 사용 불가.
  TimeSeriesSplit은 항상 과거→미래 방향으로만 분할.

【사용법】
    python train_model.py
    python train_model.py --features features.csv --model lgbm_model.pkl
    python train_model.py --cv-splits 5   # CV fold 수 조정

【다음 단계】
    python predict.py --code 005930
"""

import argparse
import json
import warnings
import pickle
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.model_selection import TimeSeriesSplit
from sklearn.metrics import (
    accuracy_score, classification_report,
    roc_auc_score, confusion_matrix,
)
from sklearn.preprocessing import LabelEncoder

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
MODEL_DIR = SCRIPT_DIR / "models"
DATA_DIR.mkdir(parents=True, exist_ok=True)
MODEL_DIR.mkdir(parents=True, exist_ok=True)

# PyCharm에서 별도로 보관/실행할 때 사용할 기본 경로입니다.
DEFAULT_FEATURES_CSV = str(DATA_DIR / "features.csv")
DEFAULT_MODEL_PATH = str(MODEL_DIR / "lgbm_model.pkl")

warnings.filterwarnings("ignore")

try:
    import lightgbm as lgb
except ImportError:
    raise SystemExit("pip install lightgbm 먼저 실행하세요.")


# ================================================================
# 【피처 컬럼 정의】
#
# 모델 학습에 사용할 입력 변수(X) 목록.
# feature_builder.py가 생성한 features.csv의 컬럼 중 선택.
#
# 크게 두 범주:
#   1. 뉴스 피처: 감성, 클릭베이트, 기사 유형 등
#   2. 기술적 지표: 이동평균, RSI, MACD, 볼린저밴드 등
#
# 아래에서 stock_code_enc (종목코드 숫자 인코딩)이 자동으로 추가됨.
# ================================================================
FEATURE_COLS = [
    # ── 뉴스 피처 ────────────────────────────────────────────────
    "article_count",         # 해당 날 해당 종목 기사 수
    "sentiment_mean",        # 감성 점수 평균 (호재=1, 중립=0, 악재=-1)
    "sentiment_sum",         # 감성 점수 합계
    "positive_count",        # 호재 기사 수
    "negative_count",        # 악재 기사 수
    "neutral_count",         # 중립 기사 수
    "positive_ratio",        # 호재 비율 (호재수/전체)
    "negative_ratio",        # 악재 비율
    "sentiment_intensity",   # 감성 강도 = 호재비율 - 악재비율
    "clickbait_mean",        # 클릭베이트 확률 평균 (낚시성 제목 여부)
    "type_prob_mean",        # 기사 유형 확률 평균
    "fact_ratio",            # 사실형 기사 비율 (객관적 정보)
    "predict_ratio",         # 예측형 기사 비율 (전망/예측 내용)
    "reasoning_ratio",       # 추론형 기사 비율 (분석/추론 내용)
    # ── 주가 기술적 지표 ──────────────────────────────────────────
    "close_to_ma5",          # 현재가 vs 5일 이동평균 괴리율
    "close_to_ma20",         # 현재가 vs 20일 이동평균 괴리율
    "ma_cross",              # 골든/데드크로스 방향 (+1/-1)
    "rsi_14",                # RSI(14일) 값 (0~100)
    "rsi_zone",              # RSI 구간 (-1=과매도, 0=중립, 1=과매수)
    "macd_hist",             # MACD 히스토그램 (모멘텀 방향/강도)
    "macd_cross",            # MACD 크로스 방향 (+1=매수신호/-1=매도신호)
    "bb_pctb",               # 볼린저밴드 내 위치 (0=하단, 0.5=중앙, 1=상단)
    "volatility_5d",         # 5일 변동성 (단기 리스크)
    "volatility_20d",        # 20일 변동성 (중기 리스크)
    "volume_ratio",          # 거래량 / 5일 평균 거래량
    "return_before_1d",      # 어제 수익률 (단기 모멘텀)
    "return_before_3d",      # 최근 3일 수익률
    "return_before_5d",      # 최근 5일 수익률
    # stock_code_enc 는 preprocess()에서 자동 추가됨
]

# 모델이 예측할 타겟(정답) 컬럼
TARGET_COL = "label_1d"   # 다음날 방향 (1=상승, 0=하락)


# ================================================================
# 【1단계】 데이터 로드 + 전처리
# ================================================================

def load_features(path: str) -> pd.DataFrame:
    """
    features.csv 로드 후 날짜 정렬.
    시계열 순서가 맞아야 TimeSeriesSplit이 올바르게 동작함.
    """
    df = pd.read_csv(path)
    df["date"] = pd.to_datetime(df["date"])
    df = df.sort_values("date").reset_index(drop=True)  # 날짜 오름차순 정렬 (필수!)

    print(f"  로드: {len(df)}행 × {len(df.columns)}열")
    print(f"  기간: {df['date'].min().date()} ~ {df['date'].max().date()}")
    print(f"  레이블 분포:\n{df[TARGET_COL].value_counts()}")
    print(f"  종목별 샘플:\n{df['stock_code'].value_counts()}")
    return df


def preprocess(df: pd.DataFrame):
    """
    종목코드(문자열) → 숫자 인코딩 후 피처 행렬(X)과 레이블 벡터(y) 반환.

    왜 인코딩이 필요한가?
    LightGBM은 숫자만 입력받으므로 "005930" 같은 문자열을 0, 1, 2... 로 변환.
    LabelEncoder는 역변환도 가능해서 예측 시 다시 종목코드로 복원 가능.
    """
    df = df.copy()

    # 종목코드 문자열 → 정수 (0, 1, 2 ...)
    le = LabelEncoder()
    df["stock_code_enc"] = le.fit_transform(df["stock_code"])

    feature_cols = FEATURE_COLS + ["stock_code_enc"]

    # 결측값(NaN) → 0으로 대체 (LightGBM은 NaN도 처리 가능하지만 명시적 처리)
    df[feature_cols] = df[feature_cols].fillna(0)

    X = df[feature_cols].values       # 피처 행렬 (numpy 2D 배열)
    y = df[TARGET_COL].values         # 레이블 벡터 (numpy 1D 배열)

    return X, y, feature_cols, le, df


# ================================================================
# 【2단계】 클래스 불균형 계산
#
# 역할: 상승(1)과 하락(0) 샘플 수 비율을 계산.
#       LightGBM의 scale_pos_weight 파라미터에 사용.
#
# 왜 필요한가?
#   주가 상승/하락 데이터는 보통 불균형함.
#   (예: 하락 데이터가 더 많을 수 있음)
#   불균형 방치 시 모델이 항상 다수 클래스만 예측하는 경향 발생.
#   → scale_pos_weight = 하락수/상승수 로 상승 샘플에 가중치 부여.
# ================================================================
def calc_pos_weight(y: np.ndarray) -> float:
    neg = (y == 0).sum()   # 하락 샘플 수
    pos = (y == 1).sum()   # 상승 샘플 수
    if pos == 0:
        return 1.0
    ratio = neg / pos
    print(f"  클래스 비율 (0:1) = {neg}:{pos} → scale_pos_weight = {ratio:.2f}")
    return ratio


# ================================================================
# 【3단계】 LightGBM 학습 (TimeSeriesSplit 교차검증)
#
# 역할:
#   1. TimeSeriesSplit으로 데이터를 과거→미래 방향으로 N등분
#   2. 각 fold에서 학습 + 검증 반복 → 평균 성능 측정
#   3. 가장 높은 AUC를 기록한 fold의 best_iteration 파악
#   4. 전체 데이터로 최종 모델 학습
#
# 반환값:
#   final_model : 전체 데이터로 학습한 최종 모델 (저장용)
#   best_model  : CV에서 가장 좋은 성능의 모델 (참고용)
#   cv_scores   : fold별 정확도 리스트
#   cv_aucs     : fold별 AUC 리스트
# ================================================================
def train_lgbm(
    X: np.ndarray,
    y: np.ndarray,
    feature_names: list,
    n_splits: int = 3,
) -> tuple:
    pos_weight = calc_pos_weight(y)

    # LightGBM 하이퍼파라미터
    params = {
        "objective":         "binary",         # 이진 분류 (상승/하락)
        "metric":            "binary_logloss",  # 손실 함수 (로그 손실)
        "boosting_type":     "gbdt",            # 경사 부스팅 결정 트리
        "num_leaves":        31,                # 트리 최대 잎 노드 수 (복잡도 제어)
        "learning_rate":     0.05,              # 학습률 (낮을수록 안정, 느림)
        "feature_fraction":  0.8,              # 각 트리에서 사용할 피처 비율
        "bagging_fraction":  0.8,              # 각 트리에서 사용할 샘플 비율
        "bagging_freq":      5,                 # bagging 적용 빈도
        "scale_pos_weight":  pos_weight,        # 클래스 불균형 보정 가중치
        "min_child_samples": 5,                 # 잎 노드 최소 샘플 수 (과적합 방지)
        "verbose":           -1,                # 로그 출력 없음
        "random_state":      42,                # 재현성을 위한 랜덤 시드
    }

    tscv       = TimeSeriesSplit(n_splits=n_splits)  # 시계열 교차검증 분할기
    cv_scores  = []   # fold별 정확도
    cv_aucs    = []   # fold별 AUC (Area Under ROC Curve)
    best_model = None
    best_auc   = 0.0

    print(f"\n  TimeSeriesSplit CV (n_splits={n_splits})")
    for fold, (train_idx, val_idx) in enumerate(tscv.split(X), 1):
        X_tr, X_val = X[train_idx], X[val_idx]
        y_tr, y_val = y[train_idx], y[val_idx]

        # 단일 클래스만 있으면 AUC 계산 불가 → 스킵
        if len(np.unique(y_tr)) < 2 or len(np.unique(y_val)) < 2:
            print(f"  Fold {fold}: 클래스 단일 → 스킵")
            continue

        ds_train = lgb.Dataset(X_tr, label=y_tr, feature_name=feature_names)
        ds_val   = lgb.Dataset(X_val, label=y_val, feature_name=feature_names)

        callbacks = [
            lgb.early_stopping(50, verbose=False),  # 검증 성능 50라운드 미개선 시 조기 종료
            lgb.log_evaluation(-1),                  # 중간 로그 출력 비활성화
        ]
        model = lgb.train(
            params,
            ds_train,
            num_boost_round=500,     # 최대 트리 수
            valid_sets=[ds_val],
            callbacks=callbacks,
        )

        preds     = model.predict(X_val)           # 상승 확률 (0~1)
        preds_bin = (preds > 0.5).astype(int)      # 0.5 기준으로 이진화
        acc       = accuracy_score(y_val, preds_bin)
        try:
            auc = roc_auc_score(y_val, preds)       # AUC: 0.5=랜덤, 1.0=완벽
        except Exception:
            auc = 0.5

        cv_scores.append(acc)
        cv_aucs.append(auc)
        print(f"  Fold {fold}: acc={acc:.4f}  auc={auc:.4f}  "
              f"(train={len(y_tr)}, val={len(y_val)})")

        if auc > best_auc:
            best_auc   = auc
            best_model = model  # 가장 좋은 fold의 모델 저장

    print(f"\n  CV 평균 acc: {np.mean(cv_scores):.4f} ± {np.std(cv_scores):.4f}")
    print(f"  CV 평균 auc: {np.mean(cv_aucs):.4f} ± {np.std(cv_aucs):.4f}")

    # ── 전체 데이터로 최종 모델 재학습 ──────────────────────────
    # best fold에서 결정된 최적 트리 수(best_iteration)로 전체 데이터 학습
    print("\n  전체 데이터로 최종 모델 학습 중...")
    ds_full = lgb.Dataset(X, label=y, feature_name=feature_names)
    final_model = lgb.train(
        params,
        ds_full,
        num_boost_round=best_model.best_iteration if best_model else 100,
        callbacks=[lgb.log_evaluation(-1)],
    )

    return final_model, best_model, cv_scores, cv_aucs


# ================================================================
# 【4단계】 피처 중요도 출력
#
# 역할: 어떤 피처가 예측에 가장 큰 영향을 미치는지 출력.
#       'gain' 기준 = 해당 피처를 분기점으로 사용할 때 얻는 정보량 합계.
#
# 왜 유용한가?
#   - 중요도 낮은 피처 제거로 모델 단순화 가능
#   - 어떤 뉴스/주가 신호가 예측에 기여하는지 도메인 인사이트 제공
# ================================================================
def print_feature_importance(model, feature_names: list):
    importance = model.feature_importance(importance_type="gain")
    fi = pd.DataFrame({
        "feature":    feature_names,
        "importance": importance,
    }).sort_values("importance", ascending=False)

    print("\n  피처 중요도 (gain 기준):")
    for _, row in fi.iterrows():
        # 최대값 기준 20칸 막대 그래프 시각화
        bar = "█" * int(row["importance"] / fi["importance"].max() * 20)
        print(f"    {row['feature']:30s} {bar} {row['importance']:.1f}")
    return fi


# ================================================================
# 【5단계】 최종 평가 리포트
#
# 역할: 전체 학습 데이터 기준의 성능 지표를 출력.
#       (주의: 학습 데이터 기준이므로 과적합 가능 → 참고용으로만 사용)
#
# CV AUC가 실제 일반화 성능의 신뢰할 수 있는 지표임.
# ================================================================
def evaluate(model, X: np.ndarray, y: np.ndarray):
    preds     = model.predict(X)
    preds_bin = (preds > 0.5).astype(int)

    print("\n  [전체 데이터 기준 — 참고용, 과적합 가능]")
    print(f"  Accuracy: {accuracy_score(y, preds_bin):.4f}")
    try:
        print(f"  AUC:      {roc_auc_score(y, preds):.4f}")
    except Exception:
        pass
    print("\n  Classification Report:")
    print(classification_report(y, preds_bin, target_names=["하락(0)", "상승(1)"]))
    print("  Confusion Matrix:")
    print(confusion_matrix(y, preds_bin))


# ================================================================
# 【6단계】 모델 저장
#
# 역할: 학습된 모델과 부속 정보를 pickle 파일로 저장.
#
# 저장되는 내용 (dict):
#   - model        : LightGBM 학습 모델
#   - label_encoder: 종목코드 인코더 (예측 시 복원에 필요)
#   - feature_cols : 피처 순서 (예측 시 동일 순서로 입력 필요)
#   - meta         : CV 성능, 피처 중요도 등 메타 정보
#
# predict.py에서 이 pkl을 로드하여 예측에 사용.
# meta.json은 Spring 백엔드나 모니터링에서 활용 가능.
# ================================================================
def save_model(model, le: LabelEncoder, feature_cols: list, out_path: str, meta: dict):
    output_path = Path(out_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "model":         model,
        "label_encoder": le,
        "feature_cols":  feature_cols,
        "meta":          meta,
    }
    with open(output_path, "wb") as f:
        pickle.dump(payload, f)
    print(f"\n✅ 모델 저장: {out_path}")

    # 메타 정보를 JSON으로도 저장 (Spring 연동, 모니터링 대시보드 등에서 활용)
    meta_path = output_path.with_name(f"{output_path.stem}_meta.json")
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
    print(f"✅ 메타 저장: {meta_path}")


# ================================================================
# 【메인 실행 함수】
# ================================================================
def main(args):
    print("=" * 60)
    print("  [1/4] Feature 로드")
    print("=" * 60)
    df = load_features(args.features)

    if len(df) < 10:
        raise SystemExit(f"데이터가 너무 적습니다: {len(df)}행. feature_builder.py 먼저 실행하세요.")

    print("\n" + "=" * 60)
    print("  [2/4] 전처리")
    print("=" * 60)
    X, y, feature_cols, le, df_proc = preprocess(df)
    print(f"  X shape: {X.shape}")  # (샘플 수, 피처 수)
    print(f"  y shape: {y.shape}")  # (샘플 수,)

    print("\n" + "=" * 60)
    print("  [3/4] LightGBM 학습")
    print("=" * 60)
    final_model, best_cv_model, cv_scores, cv_aucs = train_lgbm(
        X, y, feature_cols, n_splits=args.cv_splits
    )

    fi = print_feature_importance(final_model, feature_cols)

    print("\n" + "=" * 60)
    print("  [4/4] 평가 + 저장")
    print("=" * 60)
    evaluate(final_model, X, y)

    # 저장할 메타 정보
    meta = {
        "feature_cols":       feature_cols,
        "target":             TARGET_COL,
        "cv_acc_mean":        float(np.mean(cv_scores)) if cv_scores else 0.0,
        "cv_acc_std":         float(np.std(cv_scores))  if cv_scores else 0.0,
        "cv_auc_mean":        float(np.mean(cv_aucs))   if cv_aucs  else 0.0,
        "cv_auc_std":         float(np.std(cv_aucs))    if cv_aucs  else 0.0,
        "n_samples":          len(df),
        "stock_classes":      le.classes_.tolist(),           # 학습에 사용된 종목 목록
        "feature_importance": fi.set_index("feature")["importance"].to_dict(),
        "label_distribution": df[TARGET_COL].value_counts().to_dict(),
    }

    save_model(final_model, le, feature_cols, args.model, meta)

    print("\n" + "=" * 60)
    print("  학습 완료")
    print("=" * 60)
    print(f"  CV AUC: {np.mean(cv_aucs):.4f}  (0.5=랜덤, 1.0=완벽)")
    if np.mean(cv_aucs) < 0.55:
        print("  ⚠  AUC가 낮아요. 데이터가 더 쌓이면 재학습을 권장합니다.")
    print(f"\n  → 다음 단계: python predict.py --code 005930 --date 오늘날짜")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--features",  default=DEFAULT_FEATURES_CSV, help="feature_builder.py 출력 CSV")
    parser.add_argument("--model",     default=DEFAULT_MODEL_PATH,   help="저장할 모델 파일명")
    parser.add_argument("--cv-splits", default=3, type=int,       help="TimeSeriesSplit fold 수")
    args = parser.parse_args()
    main(args)
