# Article Feature Builder

PyCharm에서 별도로 실행했던 뉴스 ML 스크립트를 앱 실행 코드와 분리해 정리한 폴더입니다.

이 폴더에 포함된 스크립트:

- `run_rss.py`: 뉴스 수집
- `oracle_csv.py`: Oracle 데이터를 CSV로 추출
- `feature_builder.py`: 일별 학습 피처 생성
- `train_model.py`: 일별 모델 학습
- `article_feature_builder.py`: 기사 단위 학습 피처 생성
- `article_train_model.py`: 기사 단위 모델 학습

제외한 파일:

- `predict.py`
- `article_predict.py`

위 두 파일은 현재 프로젝트 안에 이미 동작 중인 버전이 있어 의도적으로 넣지 않았습니다.

## 폴더 구조

- `data/`: CSV, SQLite, 피처 CSV 같은 로컬 데이터 보관
- `models/`: 학습 모델과 로컬 보조 모델 보관
- `.env`: 로컬 실행용 환경변수 파일
- `.env.example`: 환경변수 예시 파일

`data/`, `models/`, `.env` 안의 실제 실행 자원은 GitHub에 올리지 않도록 `.gitignore`로 정리해두었습니다.

## 준비

1. 프로젝트 루트에서 가상환경을 준비합니다.
2. 프로젝트 루트에서 `pip install -r requirements.txt`를 실행합니다.
3. 이 폴더에서 `.env.example`를 복사해 `.env`를 만듭니다.
4. 필요한 입력 파일을 `data/`, `models/` 안에 넣습니다.

## 필요한 파일

기본 입력 데이터:

- `data/news_data.db`
- `data/oracle_company.csv`
- `data/oracle_sector.csv`

뉴스 수집에 필요한 로컬 모델:

- `models/clickbait_model.joblib`
- `models/tfidf_vectorizer.joblib`
- `models/model.pt`

## 실행 순서

Oracle 데이터를 CSV로 다시 만들고 싶을 때:

```bash
python oracle_csv.py
```

일별 피처와 모델을 만들 때:

```bash
python feature_builder.py
python train_model.py
```

기사 단위 피처와 모델을 만들 때:

```bash
python article_feature_builder.py
python article_train_model.py
```

뉴스를 다시 수집할 때:

```bash
python run_rss.py
```

## 자주 쓰는 경로 규칙

스크립트 기본 경로는 이미 코드에 정리해두었습니다.

- 입력 데이터 기본 위치: `data/`
- 출력 CSV 기본 위치: `data/`
- 학습 모델 기본 위치: `models/`

필요하면 옵션으로 다른 경로를 직접 넘길 수 있습니다. 예:

```bash
python feature_builder.py --sqlite data/news_data.db --out data/features.csv
python train_model.py --features data/features.csv --model models/lgbm_model.pkl
python article_train_model.py --features data/article_features.csv --model models/article_lgbm_model.pkl
```

## 환경변수

`run_rss.py`와 `oracle_csv.py`를 쓰려면 `.env` 또는 실행 환경에 아래 값이 필요합니다.

- `GROQ_API_KEY`
- `GROQ_API_KEY_1`
- `GROQ_API_KEY_2`
- `GROQ_API_KEY_3`
- `ARTICLE_BUILDER_ORACLE_USER`
- `ARTICLE_BUILDER_ORACLE_PASSWORD`
- `ARTICLE_BUILDER_ORACLE_DSN`
- `ARTICLE_BUILDER_ORACLE_CONFIG_DIR`
- `ARTICLE_BUILDER_ORACLE_WALLET_LOCATION`
- `ARTICLE_BUILDER_ORACLE_WALLET_PASSWORD`

## 메모

- 이 폴더는 IntelliJ/Spring 실행 경로와 직접 연결하지 않았습니다.
- 로직은 가능한 유지하고, 경로와 계정정보만 로컬 실행하기 편한 형태로 정리했습니다.
