import os
from pathlib import Path

import oracledb
import pandas as pd
from dotenv import load_dotenv

SCRIPT_DIR = Path(__file__).resolve().parent
DATA_DIR = SCRIPT_DIR / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)
load_dotenv(SCRIPT_DIR / ".env")

# TODO: PyCharm에서 따로 실행할 때 아래 값을 본인 환경에 맞게 바꾸거나,
# 같은 이름의 환경변수를 설정해서 사용하세요.
ORACLE_USER = os.getenv("ARTICLE_BUILDER_ORACLE_USER", "YOUR_ORACLE_USER")
ORACLE_PASSWORD = os.getenv("ARTICLE_BUILDER_ORACLE_PASSWORD", "YOUR_ORACLE_PASSWORD")
ORACLE_DSN = os.getenv("ARTICLE_BUILDER_ORACLE_DSN", "YOUR_ORACLE_DSN")
ORACLE_CONFIG_DIR = os.getenv("ARTICLE_BUILDER_ORACLE_CONFIG_DIR", r"C:\path\to\wallet")
ORACLE_WALLET_LOCATION = os.getenv("ARTICLE_BUILDER_ORACLE_WALLET_LOCATION", ORACLE_CONFIG_DIR)
ORACLE_WALLET_PASSWORD = os.getenv("ARTICLE_BUILDER_ORACLE_WALLET_PASSWORD", "YOUR_ORACLE_WALLET_PASSWORD")

# 별도 보관용 스크립트이므로 출력 CSV도 이 폴더 하위 data/ 에 저장합니다.
COMPANY_OUTPUT_CSV = DATA_DIR / "oracle_company.csv"
SECTOR_OUTPUT_CSV = DATA_DIR / "oracle_sector.csv"

conn = oracledb.connect(
    user=ORACLE_USER,
    password=ORACLE_PASSWORD,
    dsn=ORACLE_DSN,
    config_dir=ORACLE_CONFIG_DIR,
    wallet_location=ORACLE_WALLET_LOCATION,
    wallet_password=ORACLE_WALLET_PASSWORD,
)

df1 = pd.read_sql("SELECT * FROM news_data", conn)
df2 = pd.read_sql("SELECT * FROM news_data_sec", conn)
df1.to_csv(COMPANY_OUTPUT_CSV, index=False, encoding="utf-8-sig")
df2.to_csv(SECTOR_OUTPUT_CSV, index=False, encoding="utf-8-sig")
