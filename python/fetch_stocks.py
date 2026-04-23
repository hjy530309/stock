import FinanceDataReader as fdr
import json
import sys
import io

# Windows 한글 깨짐 방지
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')

def fetch_stocks():
    result = []

    kospi = fdr.StockListing('KOSPI')
    for _, row in kospi.iterrows():
        result.append({
            "code": str(row.get('Code', '')),
            "name": str(row.get('Name', '')),
            "fullName": str(row.get('Name', '')),
            "market": "KOSPI"
        })

    kosdaq = fdr.StockListing('KOSDAQ')
    for _, row in kosdaq.iterrows():
        result.append({
            "code": str(row.get('Code', '')),
            "name": str(row.get('Name', '')),
            "fullName": str(row.get('Name', '')),
            "market": "KOSDAQ"
        })

    # JSON으로 출력 (Spring에서 읽음)
    print(json.dumps(result, ensure_ascii=False))

fetch_stocks()
