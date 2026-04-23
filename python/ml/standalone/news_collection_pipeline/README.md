# News Collection Pipeline

PyCharm에서 별도로 실행했던 원본 뉴스 수집 파이프라인입니다.
Spring 웹앱 실행과는 독립적으로 동작합니다.

## 포함 스크립트

- `run_all.py`: 실행 진입점
- `news_common.py`: 수집/필터링/요약/DB 저장 공통 로직

## 로컬 필요 파일

- `models/clickbait_model.joblib`
- `models/tfidf_vectorizer.joblib`
- `models/model.pt`
- `.env`

현재 `.env`와 실제 모델 파일은 Git에서 제외됩니다.

## 실행 준비

1. 프로젝트 루트에서 패키지 설치

```powershell
pip install -r requirements.txt
```

2. `.env.example`를 복사해 `.env` 생성
3. `models/`에 로컬 모델 파일 배치

## 실행

```powershell
python run_all.py
```

## 환경변수

- `NAVER_CLIENT_ID`
- `NAVER_CLIENT_SECRET`
- `GROQ_API_KEY_1`
- `GROQ_API_KEY_2`
- `GROQ_API_KEY_3`
- `NEWS_ORACLE_CLIENT_LIB_DIR`
- `NEWS_ORACLE_WALLET_PATH`
- `NEWS_ORACLE_DB_USER`
- `NEWS_ORACLE_DB_PASSWORD`
- `NEWS_ORACLE_DB_DSN`

## 중요 메모

- 이 파이프라인은 Oracle DB에 직접 쓰기 때문에 DB 연결이 반드시 필요합니다.
- `model.pt`는 대용량이라 일반 GitHub 업로드에 적합하지 않습니다.
