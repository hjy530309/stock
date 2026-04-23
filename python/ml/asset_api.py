import os
import math
import pickle
from pathlib import Path
import pandas as pd

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
import uvicorn

SCRIPT_DIR = Path(__file__).resolve().parent


def resolve_base_dir() -> Path:
    candidates = []
    env_base = os.environ.get("ML_PROJECT_DIR")
    if env_base:
        candidates.append(Path(env_base))
    candidates.extend([SCRIPT_DIR, Path.cwd()])

    for candidate in candidates:
        if (candidate / "models").exists():
            return candidate.resolve()

    return SCRIPT_DIR


BASE_DIR = resolve_base_dir()
MODEL_DIR = BASE_DIR / "models"
DATA_DIR = BASE_DIR / "data"

MODEL_PATH = MODEL_DIR / "asset_goal_model.pkl"
ENCODER_PATH = MODEL_DIR / "label_encoders.pkl"
SCALER_PATH = MODEL_DIR / "scaler.pkl"
FEATURE_COLUMNS_PATH = MODEL_DIR / "feature_columns.pkl"

def load_pickle_file(path: Path):
    if not path.exists():
        raise FileNotFoundError(f"파일이 존재하지 않습니다: {path}")
    with path.open("rb") as f:
        return pickle.load(f)

try:
    model = load_pickle_file(MODEL_PATH)
    label_encoders = load_pickle_file(ENCODER_PATH)
    scaler = load_pickle_file(SCALER_PATH)
    feature_columns = load_pickle_file(FEATURE_COLUMNS_PATH)
except Exception as e:
    model = None
    label_encoders = None
    scaler = None
    feature_columns = None
    load_error = str(e)
else:
    load_error = None

# FastAPI 생성
app = FastAPI(title="Asset Goal Prediction API")

# 요청 데이터 형식 정의
class AssetPredictionRequest(BaseModel):
    # ... = 기본값 없음 ge = 0 이상, gt = 0 초과
    current_asset: int = Field(..., ge=0, description="현재 자산")
    monthly_income: int = Field(..., ge=0, description="월 소득")
    monthly_expense: int = Field(..., ge=0, description="월 지출")
    goal_amount: int = Field(..., gt=0, description="목표 금액")
    goal_months: int = Field(..., gt=0, description="목표 기간(개월)")
    expected_return: float = Field(..., ge=0, description="예상 수익률(연, 예: 0.05)")
    age: int = Field(..., ge=0, description="나이")
    job_type: str = Field(..., description="직업 유형")
    risk_preference: str = Field(..., description="투자 성향")

# 문자열 값을 학습 때와 똑같은 숫자로 변환
def encode_value(column_name: str, value: str):
    encoder = label_encoders.get(column_name)
    if encoder is None:
        raise ValueError(f"{column_name} 인코더를 찾을 수 없습니다.")

    if value not in encoder.classes_:
        raise ValueError(
            f"{column_name} 값 '{value}' 은(는) 학습되지 않은 값입니다. "
            f"허용값: {list(encoder.classes_)}"
        )

    return int(encoder.transform([value])[0])

# 목표를 달성하려면 월 저축액이 최소 얼마여야 하는지 계산
def calculate_required_monthly_cashflow_details(current_asset, goal_amount, goal_months, annual_return):
    monthly_rate = annual_return / 12.0   # 월 수익률 = 연 수익률 / 12

    # 수익률이 0원일 때 따로 처리
    if monthly_rate == 0:
        future_value_current = int(current_asset)
        # 현재 자산을 제외하고 얼마가 더 필요한지
        remain = goal_amount - current_asset
        if remain <= 0:  # 목표 달성 및 초과한 경우
            return {
                "required_monthly_cashflow": 0,
                "future_value_current": future_value_current,
                "remain_needed": 0,
                "surplus_amount": int(abs(remain)),
                "is_achieved": True
            }
        # 수익률이 0일 때 필요한 월 저축액 # 나머지는 올림 처리 -> 부족하지 않게
        required = int((remain + goal_months - 1) // goal_months)

        return {
            "required_monthly_cashflow": required,
            "future_value_current": future_value_current,
            "remain_needed": int(remain),
            "surplus_amount": 0,
            "is_achieved": False
        }

    # 수익률이 0이 아닐 때 현재 자산의 미래 가치 계산
    # 현재 자산만 가만히 놔둬도 미래에 얼마가 되는지 계산
    future_value_current = current_asset * ((1 + monthly_rate) ** goal_months)
    # 앞으로 추가로 얼마나 더 필요한지 계산
    remain_needed = goal_amount - future_value_current

    # 이미 현재 자산만으로 목표에 도달한 경우
    if remain_needed <= 0:
        return {
            "required_monthly_cashflow": 0,
            "future_value_current": int(future_value_current),
            "remain_needed": 0,
            "surplus_amount": int(abs(remain_needed)),
            "is_achieved": True
        }

    # 월 저축액의 미래가치 계수 계산
    factor = (((1 + monthly_rate) ** goal_months) - 1) / monthly_rate
    # 매달 얼마를 넣어야 하는지
    required = remain_needed / factor
    # 월 저축액을 올림하여 반환
    required = math.ceil(required)

    return {
        "required_monthly_cashflow": required,
        "future_value_current": int(future_value_current),
        "remain_needed": int(remain_needed),
        "surplus_amount": 0,
        "is_achieved": False
    }

# 현재 월 저축액으로 가면 최종 자산이 얼마가 되는지
def calculate_estimated_final_asset(current_asset, monthly_cashflow, goal_months, annual_return):
    monthly_rate = annual_return / 12.0
    total = float(current_asset)

    # 개월 수만큼 반복
    for _ in range(goal_months):
        # 기존 자산에 수익률 반영 + 월
        total = total * (1 + monthly_rate) + monthly_cashflow

        # 자산이 0 밑으로 내려가면 0으로 처리
        if total < 0:
            total = 0

    return int(total)

# 모델의 예측값, 실제 계산값 비교 후 보여줄 결과(예측값, 현금 흐름, 최소~)
def determine_final_label(prediction, monthly_cashflow, required_monthly_cashflow):
    if monthly_cashflow <= 0: # 매달 남는 돈이 없음(또는 적자)
        return 0, "조금 더 준비가 필요해요"

    if monthly_cashflow >= required_monthly_cashflow: # 이상인 경우
        return 1, "목표에 도달할 가능성이 있어요"

    return prediction, "목표에 도달할 가능성이 있어요" if prediction == 1 else "조금 더 준비가 필요해요"

# 화면에 나올 짧은 제목 (UI/UX용)
def create_tone_title(final_prediction, monthly_cashflow, required_monthly_cashflow, surplus_amount):
    if surplus_amount > 0:
        return "이미 목표 범위를 넘기고 있어요"

    if monthly_cashflow <= 0:
        return "지금은 지출 점검이 먼저예요"

    if final_prediction == 1 and monthly_cashflow >= required_monthly_cashflow:
        return "현재 흐름이 꽤 좋은 편이에요"

    if final_prediction == 1:
        return "가능성은 충분히 있어 보여요"

    if required_monthly_cashflow > monthly_cashflow:
        return "조금만 조정하면 더 좋아질 수 있어요"

    return "천천히 점검해보면 좋아요"

# 제목보단 긴 설명
def create_message(
    prediction: int,
    probability: float,
    monthly_cashflow: int,
    required_monthly_cashflow: int,
    estimated_final_asset: int,
    goal_amount: int,
    surplus_amount: int,
    remain_needed: int
):
    # 목표 금액과 예상 최종 자산의 차이
    goal_gap = goal_amount - estimated_final_asset

    if surplus_amount > 0:
        if probability >= 0.8:
            return (
                f"현재 자산 흐름이라면 이미 목표 범위를 넘기고 있어요. "
                f"계산 기준으로는 목표 금액보다 약 {surplus_amount:,}원 초과한 상태예요."
            )
        return (
            f"계산 기준으로는 이미 목표를 달성했고, "
            f"목표 금액보다 약 {surplus_amount:,}원 여유가 있어요."
        )

    if monthly_cashflow <= 0:
        return (
            "현재 기준으로는 월 현금흐름이 부족해서 목표까지 가는 데 시간이 더 필요해 보여요. "
            "먼저 월 지출을 가볍게 점검해보는 걸 추천드려요."
        )

    if goal_gap == 0:
        return (
            "계산 기준으로는 목표 금액에 정확히 도달해요. "
            "지금 페이스를 꾸준히 유지하면 좋겠어요."
        )

    if prediction == 1:
        shortage_per_month = max(required_monthly_cashflow - monthly_cashflow, 0)

        if shortage_per_month == 0:
            return (
                "현재 흐름이라면 목표에 도달할 가능성이 있어요. "
                "지금 수준을 유지하면 좋아요."
            )

        return (
            f"예측 모델은 가능성이 있다고 보고 있어요. "
            f"다만 계산상으로는 월 현금흐름이 약 {shortage_per_month:,}원 정도 더 확보되면 훨씬 안정적이에요. "
            "조금만 보완하면 더 좋은 흐름으로 갈 수 있어요."
        )

    return (
        f"지금도 충분히 계획을 잘 세우고 계시지만, 현재 조건으로는 목표까지 조금 더 준비가 필요해 보여요. "
        f"월 현금흐름을 약 {max(required_monthly_cashflow - monthly_cashflow, 0):,}원 정도 더 확보하거나 "
        f"목표 기간을 조금 여유 있게 잡으면 훨씬 좋아질 수 있어요. "
        f"현재 계산 기준으로는 약 {remain_needed:,}원 정도 더 필요해요."
    )

# 서버 상태 확인용 API
@app.get("/api/health")
def health_check():
    if load_error:
        return {
            "status": "error",
            "message": load_error
        }

    return {
        "status": "ok",
        "message": "API is running"
    }

# 메인 예측 API
# 사용자가 입력한 자산 정보를 받아 예측, 계산 결과, 메시지 한 번에 반환
@app.post("/api/asset/predict")
def predict_asset_goal(request: AssetPredictionRequest):
    if load_error:
        raise HTTPException(status_code=500, detail=f"모델 로드 실패: {load_error}")

    try:
        # 흑자면 양수, 적자면 음수
        monthly_cashflow = request.monthly_income - request.monthly_expense

        cashflow_analysis = calculate_required_monthly_cashflow_details(
            current_asset=request.current_asset,
            goal_amount=request.goal_amount,
            goal_months=request.goal_months,
            annual_return=request.expected_return
        )

        # 딕셔너리에서 값 꺼내기
        required_monthly_cashflow = cashflow_analysis["required_monthly_cashflow"]
        future_value_current = cashflow_analysis["future_value_current"]
        remain_needed = cashflow_analysis["remain_needed"]
        surplus_amount = cashflow_analysis["surplus_amount"]
        is_achieved = cashflow_analysis["is_achieved"]

        # 최종 예상 자산 계산
        estimated_final_asset = calculate_estimated_final_asset(
            current_asset=request.current_asset,
            monthly_cashflow=monthly_cashflow,
            goal_months=request.goal_months,
            annual_return=request.expected_return
        )

        # 모델 입력 딕셔너리 (학습된 모델에 넣을 입력값)
        input_dict = {
            "current_asset": request.current_asset,
            "monthly_income": request.monthly_income,
            "monthly_expense": request.monthly_expense,
            "monthly_cashflow": monthly_cashflow,
            "goal_amount": request.goal_amount,
            "goal_months": request.goal_months,
            "expected_return": request.expected_return,
            "age": request.age,
            "job_type": encode_value("job_type", request.job_type),
            "risk_preference": encode_value("risk_preference", request.risk_preference)
        }

        # DataFrame 변환 + 컬럼 체크
        input_df = pd.DataFrame([input_dict])

        missing_columns = [col for col in feature_columns if col not in input_df.columns]
        if missing_columns:
            raise ValueError(f"입력 데이터에 필요한 컬럼이 없습니다: {missing_columns}")

        # 컬럼 순서 맞추기 + 스케일링
        input_df = input_df[feature_columns]
        input_scaled = scaler.transform(input_df)

        prediction = int(model.predict(input_scaled)[0])
        probability = float(model.predict_proba(input_scaled)[0][1])

        final_prediction, final_prediction_label = determine_final_label(
            prediction=prediction,
            monthly_cashflow=monthly_cashflow,
            required_monthly_cashflow=required_monthly_cashflow
        )

        tone_title = create_tone_title(
            final_prediction=final_prediction,
            monthly_cashflow=monthly_cashflow,
            required_monthly_cashflow=required_monthly_cashflow,
            surplus_amount=surplus_amount
        )

        message = create_message(
            prediction=prediction,
            probability=probability,
            monthly_cashflow=monthly_cashflow,
            required_monthly_cashflow=required_monthly_cashflow,
            estimated_final_asset=estimated_final_asset,
            goal_amount=request.goal_amount,
            surplus_amount=surplus_amount,
            remain_needed=remain_needed
        )

        goal_gap = request.goal_amount - estimated_final_asset

        return {
            "success": True,

            "model_prediction": prediction,
            "model_prediction_label": "달성 가능" if prediction == 1 else "달성 어려움",
            "model_probability": round(probability, 4),

            "prediction": final_prediction,
            "prediction_label": final_prediction_label,
            "tone_title": tone_title,

            "input_summary": {
                "current_asset": request.current_asset,
                "monthly_income": request.monthly_income,
                "monthly_expense": request.monthly_expense,
                "monthly_cashflow": monthly_cashflow,
                "goal_amount": request.goal_amount,
                "goal_months": request.goal_months,
                "expected_return": request.expected_return,
                "age": request.age,
                "job_type": request.job_type,
                "risk_preference": request.risk_preference
            },
            "analysis": {
                "required_monthly_cashflow": required_monthly_cashflow,
                "estimated_final_asset": estimated_final_asset,
                "goal_gap": goal_gap,
                "future_value_current": future_value_current,
                "remain_needed": remain_needed,
                "surplus_amount": surplus_amount,
                "is_achieved_by_current_asset_growth": is_achieved,
                "message": message
            }
        }

    # 예외처리
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"예측 처리 중 오류 발생: {str(e)}")

if __name__ == "__main__":
    api_host = os.environ.get("ASSET_API_HOST", "127.0.0.1")
    api_port = int(os.environ.get("ASSET_API_PORT", "9000"))
    uvicorn.run(
        app,
        host=api_host,
        port=api_port,
        reload=False
    )
