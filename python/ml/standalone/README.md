# Standalone Python Scripts

이 폴더는 IntelliJ/Spring 메인 실행 경로와 분리한 PyCharm용 파이썬 스크립트 모음입니다.

## 폴더 구성

- `article_feature_builder/`
기사 단위/일별 피처 생성, 모델 학습, CSV 추출 보조 스크립트
- `news_collection_pipeline/`
원본 뉴스 수집 파이프라인(Oracle DB 직접 저장)

## 공통 실행 준비

1. 프로젝트 루트에서 가상환경 준비
2. 프로젝트 루트에서 패키지 설치

```powershell
pip install -r requirements.txt
```

3. 각 폴더의 `.env.example`를 복사해 `.env` 생성
4. 각 폴더 README 기준으로 `data/` / `models/` 파일 준비

## GitHub 업로드 기준

아래 리소스는 로컬 실행용으로만 유지합니다.

- `python/ml/standalone/**/.env`
- `python/ml/standalone/**/models/*`
- `python/ml/standalone/**/data/*`

예외:

- `.gitkeep`
- `.env.example`
- 코드 파일
- README 파일

## 빠른 진입점

기사/일별 학습 파이프라인:

```powershell
cd python/ml/standalone/article_feature_builder
python feature_builder.py
python train_model.py
```

원본 뉴스 수집 파이프라인:

```powershell
cd python/ml/standalone/news_collection_pipeline
python run_all.py
```
