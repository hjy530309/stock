"""
run_all.py — 섹터별 + 기업별 뉴스 수집을 한 번에 동시 실행
  - 섹터별 (반도체/AI, 2차전지 …) → news_data_sec  (GROQ_API_KEY_1)
  - 기업별 (삼성전자, 현대차 …)   → news_data       (GROQ_API_KEY_2)
  - ModelManager 싱글톤: BERT / TF-IDF 한 번만 로드
"""

import os
import asyncio
from dotenv import load_dotenv
from news_common import ArticleProcessor, run_pipeline_async

load_dotenv()


# ================================================================
# [섹터별 설정]
# ================================================================
SECTOR_CONFIG = {
    "IT/반도체": {
        "search_query": "반도체 AI",
        "keywords": ["삼성전자", "SK하이닉스", "하이닉스", "HBM", "반도체", "AI", "엔비디아",
                     "D램", "낸드", "파운드리", "TSMC", "NPU", "GPU", "EUV", "데이터센터",
                     "온디바이스", "패키징", "CXL", "소부장", "전공정", "후공정", "ASML", "마이크론"]
    },
    "2차전지": {
        "search_query": "2차전지 배터리",
        "keywords": ["에코프로", "에너지솔루션", "양극재", "배터리", "리튬", "전기차", "테슬라",
                     "음극재", "전고체", "LFP", "NCM", "IRA", "캐즘", "CATL", "BYD","ESS",
                     "전해액", "분리막", "포스코홀딩스", "에코프로비엠", "수산화리튬"]
    },
    "제약/바이오": {
        "search_query": "제약 바이오",
        "keywords": ["셀트리온", "바이오로직스", "신약", "임상", "제약", "바이오", "FDA",
                     "유한양행", "알테오젠", "HLB", "바이오시밀러", "항암제", "기술수출", "CDMO",
                     "비만치료제", "ADC", "마이크로바이옴", "한미약품", "학회"]
    },
    "금융/밸류업": {
        "search_query": "금융주 밸류업",
        "keywords": ["KB금융", "신한지주", "하나금융지주", "우리금융", "메리츠", "은행",
                     "주주환원", "배당", "자사주", "소각", "PBR", "ROE", "밸류업",
                     "기업밸류업프로그램", "주주환원율", "행동주의", "지주사", "배당수익률",
                     "시황", "마감", "특징주", "인사이트", "증시", "코스피", "코스닥", "종합",
                     "순매수", "순매도", "동반 상승", "동반 하락", "외인", "기관", "뉴욕증시",
                     "관련주", "테마주", "수혜주", "급등주", "종목추천"
                     ]
    },
    "방산/우주항공": {
        "search_query": "K방산 무기 수출",
        "keywords": ["한화에어로", "현대로템", "방산", "수출", "미사일", "무기", "폴란드",
                     "KAI", "LIG넥스원", "한화시스템", "K9", "자주포", "전투기", "수주잔고",
                     "루마니아", "사우디", "호주", "천궁", "다연장로켓", "우주항공청"]
    },
    "IT/플랫폼": {
        "search_query": "네이버 카카오 플랫폼",
        "keywords": ["네이버", "카카오", "크래프톤", "엔씨소프트", "플랫폼", "클라우드",
                     "게임", "웹툰", "라인", "카카오톡", "카카오페이", "넥슨",
                     "오픈AI", "생성형AI", "치지직", "트위치", "구글"]
    },
    "엔터/미디어": {
        "search_query": "엔터 아이돌 K팝",
        "keywords": ["하이브", "JYP", "에스엠", "와이지", "아이돌", "팬덤", "콘서트",
                     "K팝", "빌보드", "CJ ENM", "방탄소년단", "BTS", "뉴진스",
                     "위버스", "팬플랫폼", "버블", "음원", "스트레이키즈", "에스파"]
    },
    "자동차/모빌리티": {
        "search_query": "현대차 기아 전기차",
        "keywords": ["현대차", "현대자동차", "기아", "모비스", "현대모비스",
                     "전기차", "하이브리드", "자율주행", "제네시스", "아이오닉",
                     "SDV", "UAM", "인도", "미국공장", "보조금"]
    }
}


# ================================================================
# [기업별 설정]
# ================================================================
COMPANY_CONFIG = {
    "삼성전자 (IT/반도체)": {
        "search_query": "삼성전자",
        "core_keywords": ["삼성전자", "삼전"],
        "sub_keywords": ["이재용", "파운드리", "갤럭시", "메모리", "HBM", "반도체",
                         "엔비디아", "TSMC", "D램", "낸드", "실적", "매출", "영업이익",
                         "주가", "목표가", "배당", "외국인", "투자",
                         "전영현", "노태문", "HBM3E", "마하1", "온디바이스", "파업"]
    },
    "LG에너지솔루션 (2차전지)": {
        "search_query": "LG에너지솔루션",
        "core_keywords": ["LG에너지솔루션", "엔솔"],
        "sub_keywords": ["LG화학", "권영수", "김동명", "배터리", "원통형", "테슬라",
                         "전기차", "캐즘", "수주", "보조금", "실적", "매출", "영업이익",
                         "주가", "목표가", "배당", "외국인", "투자",
                         "JV", "미시간", "애리조나", "ESS", "폼팩터"]
    },
    "삼성바이오로직스 (제약/바이오)": {
        "search_query": "삼성바이오로직스",
        "core_keywords": ["삼성바이오로직스", "삼바"],
        "sub_keywords": ["존림", "CDMO", "위탁생산", "바이오시밀러", "에피스", "FDA",
                         "임상", "신약", "승인", "공장", "실적", "매출", "영업이익",
                         "주가", "목표가", "배당", "외국인", "투자",
                         "제4공장", "제5공장", "ADC", "바이오젠"]
    },
    "현대차 (자동차/모빌리티)": {
        "search_query": "현대차",
        "core_keywords": ["현대차", "현대자동차"],
        "sub_keywords": ["정의선", "제네시스", "아이오닉", "전기차", "하이브리드",
                         "HEV", "수출", "노조", "점유율", "판매량", "실적", "매출",
                         "영업이익", "주가", "목표가", "배당", "외국인", "투자",
                         "장재훈", "호세무뇨스", "SDV", "인도법인", "IPO", "조지아"]
    },
    "네이버 (IT/플랫폼)": {
        "search_query": "네이버",
        "core_keywords": ["네이버", "NAVER"],
        "sub_keywords": ["최수연", "이해진", "라인", "하이퍼클로바", "웹툰", "플랫폼",
                         "AI", "클라우드", "광고", "커머스", "실적", "매출", "영업이익",
                         "주가", "목표가", "배당", "외국인", "투자",
                         "치지직", "클로바X", "큐", "포시마크"]
    },
    "KB금융 (금융/밸류업)": {
        "search_query": "KB금융",
        "core_keywords": ["KB금융", "국민은행"],
        "sub_keywords": ["양종희", "리딩뱅크", "주주환원", "배당", "저PBR", "밸류업",
                         "자사주", "소각", "금리", "홍콩", "실적", "매출", "영업이익",
                         "주가", "목표가", "외국인", "투자",
                         "KB증권", "ELS", "건전성", "NPL",
                         "시황", "마감", "특징주", "인사이트", "증시", "코스피", "코스닥", "종합",
                         "순매수", "순매도", "동반 상승", "동반 하락", "외인", "기관", "뉴욕증시",
                         "관련주", "테마주", "수혜주", "급등주", "종목추천"
                         ]
    },
    "한화에어로스페이스 (방산/우주항공)": {
        "search_query": "한화에어로스페이스",
        "core_keywords": ["한화에어로스페이스", "한화에어로"],
        "sub_keywords": ["김동관", "방산", "수출", "폴란드", "자주포", "전투기", "K9",
                         "무기", "수주", "누리호", "실적", "매출", "영업이익",
                         "주가", "목표가", "배당", "외국인", "투자",
                         "천무", "레드백", "루마니아", "인적분할", "한화오션"]
    },
    "하이브 (엔터/미디어)": {
        "search_query": "하이브",
        "core_keywords": ["하이브", "HYBE"],
        "sub_keywords": ["방시혁", "민희진", "BTS", "방탄소년단", "뉴진스", "세븐틴",
                         "아이돌", "빌보드", "팬덤", "콘서트", "실적", "매출", "영업이익",
                         "주가", "목표가", "배당", "외국인", "투자",
                         "어도어", "멀티레이블", "위버스", "아일릿", "르세라핌"]
    }
}


# ================================================================
# [필터 함수]
# ================================================================
def verify_sector_fn(proc: ArticleProcessor, title: str, text: str, info: dict) -> bool:
    return proc.verify_sector(title, text, info["keywords"])


def verify_company_fn(proc: ArticleProcessor, title: str, text: str, info: dict) -> bool:
    if "KB금융" in info.get("core_keywords", []):
        return True
    return proc.verify_company(title, text, info["core_keywords"], info["sub_keywords"])


# ================================================================
# [실행]
# ================================================================
async def main():
    if not os.getenv("NAVER_CLIENT_ID") or not os.getenv("NAVER_CLIENT_SECRET"):
        print("네이버 API 키 없음.")
        return
    if not os.getenv("GROQ_API_KEY_1"):
        print("Groq API 키 없음.")
        return

    print("=" * 60)
    print("  섹터별 + 기업별 뉴스 수집 동시 시작")
    print("=" * 60)

    sec_count, comp_count = await asyncio.gather(
        run_pipeline_async(
            sector_config=SECTOR_CONFIG,
            verify_fn=verify_sector_fn,
            db_table="news_data_sec",
            max_per_sector=100,
            groq_api_keys=[
                os.getenv("GROQ_API_KEY_1"),
                os.getenv("GROQ_API_KEY_2"),
                os.getenv("GROQ_API_KEY_3"),
            ],
        ),
        run_pipeline_async(
            sector_config=COMPANY_CONFIG,
            verify_fn=verify_company_fn,
            db_table="news_data",
            max_per_sector=100,
            groq_api_keys=[
                os.getenv("GROQ_API_KEY_1"),
                os.getenv("GROQ_API_KEY_2"),
                os.getenv("GROQ_API_KEY_3"),
            ],
        ),
    )

    print("=" * 60)
    print(f"  최종 완료 — news_data_sec: {sec_count}건 | news_data: {comp_count}건")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
