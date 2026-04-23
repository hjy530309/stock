# Stoxle

Spring Boot + Thymeleaf + Python 기반의 주식/뉴스/커뮤니티/자산관리 프로젝트입니다.

## 현재 리포지토리 포함 상태

아래 파일은 리포지토리에 포함되어 있습니다.

- `python/ml/data/news_data.db`
- `python/ml/data/oracle_company.csv`
- `python/ml/data/oracle_sector.csv`
- `python/ml/models/*.pkl` (예측/자산 모델)

즉, AI 예측에 필요한 기본 로컬 데이터/모델은 이미 포함된 상태입니다.

## 바로 실행 가능 여부

`DB/CSV`를 포함해도, 다른 환경에서 **완전 즉시 실행**하려면 아래 조건이 추가로 필요합니다.

- Oracle DB 접속 가능
- Oracle Wallet(또는 해당 연결 방식) 준비
- `application.properties` 실값 채우기
- KIS API 키 설정
- Python 가상환경 + `requirements.txt` 설치

특히 `spring.jpa.hibernate.ddl-auto=none` 이므로, 빈 DB에 연결만 해서 자동 테이블 생성은 되지 않습니다.
실행 대상 DB에 필요한 스키마/테이블이 이미 있어야 합니다.

## 메인 웹앱 실행 (권장 흐름)

1. 가상환경 생성 및 패키지 설치

```powershell
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
```

2. Spring 설정 파일 생성

```powershell
Copy-Item src/main/resources/application-example.properties src/main/resources/application.properties
```

3. `application.properties`에 필수 값 입력

- `spring.datasource.url`
- `spring.datasource.username`
- `spring.datasource.password`
- `kis.api.app-key`
- `kis.api.app-secret`
- `python.path` (`.venv`의 python 경로)

4. 애플리케이션 실행

```powershell
.\mvnw.cmd spring-boot:run
```

## 뉴스 수집 파이프라인 안내

메인 웹앱 실행과 별개로, 뉴스 수집 스크립트는 추가 준비가 필요합니다.

- 루트 수집 스크립트: `python/run_all.py`
- 환경변수: `python/.env` (예시는 `python/.env.example`)
- 로컬 모델 파일: `python/clickbait_model.joblib`, `python/tfidf_vectorizer.joblib`, `python/model.pt`

`model.pt`는 대용량 파일이라 GitHub 기본 업로드에 부적합할 수 있습니다.
별도 전달(클라우드 저장소/LFS 등)을 권장합니다.

별도 보관형 파이프라인 문서는 아래 참고:

- [Standalone Overview](python/ml/standalone/README.md)

## 보안/업로드 원칙

아래 파일은 Git에 올리지 않습니다.

- `src/main/resources/application.properties`
- `python/.env`
- `python/ml/standalone/**/.env`
- `python/ml/standalone/**/models/*` (예외: `.gitkeep`)
- `python/ml/standalone/**/data/*` (예외: `.gitkeep`)

실제 배포/공유는 예시 파일(`*.example`) 기준으로 설정하세요.
