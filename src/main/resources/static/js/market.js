// ════════════════════════════════════════════════════════
//  market.js — 종목 리스트 + 차트 패널 + 알림 공통
// ════════════════════════════════════════════════════════

// Highcharts 전역 로컬 시간 설정 (툴팁/축 모두 KST로 표시)
if (typeof Highcharts !== 'undefined') {
    Highcharts.setOptions({ time: { useUTC: false } });
}

// ── 전역 상태 ────────────────────────────────────────────

let currentType       = 'trade';   // 현재 필터 (trade | fluctuation)
let selectedCode      = '';        // 선택된 종목코드
let selectedName      = '';        // 선택된 종목명
let panelChart        = null;      // 패널 차트 인스턴스
let panelTab          = 'daily';   // 패널 차트 탭
let watchingSet       = new Set(); // 관심종목 코드 집합
let pricePollingTimer = null;      // 현재가 폴링 타이머
let allStocks         = [];        // 현재 로드된 전체 종목 (검색 필터용)
const MARKET_LIST_LIMIT = 30;      // 시장 화면 랭킹 최대 노출 개수

// ════════════════════════════════════════════════════════
//  종목 리스트 로딩
// ════════════════════════════════════════════════════════
let marketListPollingTimer = null;
let detailPricePollingTimer = null;
let detailChartPollingTimer = null;
let chartPollingTimer = null;
let marketLoadToken = 0;

function createPollingTask(task, { pauseWhenHidden = true } = {}) {
    let running = false;

    return async () => {
        if (running || (pauseWhenHidden && document.hidden)) {
            return;
        }

        running = true;
        try {
            await task();
        } finally {
            running = false;
        }
    };
}

async function loadMarketData(type) {
    currentType = type || 'trade';
    const requestToken = ++marketLoadToken;
    const body     = document.getElementById('stockListBody');
    const error    = document.getElementById('listError');
    const noResult = document.getElementById('listNoResult');
    if (!body) return;

    error.style.display = 'none';
    if (noResult) noResult.style.display = 'none';
    renderSkeletons(body);

    const endpoint = currentType === 'fluctuation'
        ? '/api/stock/top-fluctuation-full'
        : '/api/stock/top-trade';

    try {
        const res    = await fetch(endpoint);
        const stocks = await res.json();
        if (requestToken !== marketLoadToken) return;

        if (!stocks || stocks.length === 0) {
            body.innerHTML = '';
            error.style.display = 'block';
            return;
        }

        // 클라이언트 측 정렬 (API 정렬 신뢰 불가)
        if (currentType === 'trade') {
            stocks.sort((a, b) => parseFloat(b.tradeAmount || 0) - parseFloat(a.tradeAmount || 0));
        } else {
            // 등락률순: 상승률 높은 순 → 하락률 높은 순
            stocks.sort((a, b) => parseFloat(b.changeRate || 0) - parseFloat(a.changeRate || 0));
        }

        allStocks = stocks;

        // 검색 중이면 검색 필터 적용
        const keyword = document.getElementById('stockSearchInput')?.value.trim();
        if (keyword) {
            filterStockList(keyword);
        } else {
            // 화면에는 최대 30개만 노출
            const visibleStocks = stocks.slice(0, MARKET_LIST_LIMIT);
            renderStockList(body, visibleStocks);
            syncDefaultPanelSelection(visibleStocks);
        }

    } catch (e) {
        if (requestToken !== marketLoadToken) return;
        console.error('종목 로딩 실패:', e);
        body.innerHTML = '';
        error.style.display = 'block';
    }
}

// 목록 로딩 중 표시할 스켈레톤 행들을 렌더링
function renderSkeletons(container) {
    container.innerHTML = Array.from({ length: MARKET_LIST_LIMIT }, () => `
        <div class="stockRowSkeleton">
            <div class="skelBlock skelRank"></div>
            <div class="skelBlock skelName"></div>
            <div class="skelBlock skelPrice"></div>
            <div class="skelBlock skelRate"></div>
            <div class="skelBlock skelTrade"></div>
        </div>`).join('');
}

/**
 * 거래대금 억/조 단위 변환
 * KIS acml_tr_pbmn = 원 단위 (만원 아님)
 */
function formatTradeAmount(raw) {
    if (!raw || raw === '0' || raw === '') return '-';
    const won = parseFloat(raw);
    if (isNaN(won) || won === 0) return '-';
    if (won >= 1_000_000_000_000) return (won / 1_000_000_000_000).toFixed(1) + '조';
    if (won >= 100_000_000)       return Math.floor(won / 100_000_000) + '억';
    if (won >= 10_000)            return Math.floor(won / 10_000) + '만';
    return won.toLocaleString() + '원';
}

/**
 * 거래량 만/억 단위 변환 (등락률 탭용)
 */
function formatVolume(raw) {
    if (!raw || raw === '0' || raw === '') return '-';
    const v = parseFloat(raw);
    if (isNaN(v) || v === 0) return '-';
    if (v >= 100_000_000) return (v / 100_000_000).toFixed(1) + '억주';
    if (v >= 10_000)      return Math.floor(v / 10_000) + '만주';
    return v.toLocaleString() + '주';
}

// 종목 목록 배열을 좌측 리스트 UI로 렌더링하고 클릭 이벤트를 연결한다.
function renderStockList(container, stocks) {
    // 최종 렌더링 단계에서 한 번 더 30개로 제한
    const visibleStocks = (stocks || []).slice(0, MARKET_LIST_LIMIT);

    if (!visibleStocks.length) {
        container.innerHTML = '';
        const noResult = document.getElementById('listNoResult');
        if (noResult) noResult.style.display = 'block';
        return;
    }

    const noResult = document.getElementById('listNoResult');
    if (noResult) noResult.style.display = 'none';

    // 컬럼 헤더 동적 변경
    const colTradeHeader = document.getElementById('colTradeHeader');
    if (colTradeHeader) {
        colTradeHeader.textContent = currentType === 'trade' ? '거래대금' : '거래량';
    }

    container.innerHTML = visibleStocks.map((s, i) => {
        const code       = s.stockCode || '';
        const name       = s.stockName || '-';
        const price      = s.currentPrice ? Number(s.currentPrice).toLocaleString() + '원' : '-';
        const rate       = parseFloat(s.changeRate || '0');
        const cls        = rate > 0 ? 'up' : rate < 0 ? 'down' : 'flat';
        const arrow      = rate > 0 ? '▲' : rate < 0 ? '▼' : '-';
        // 거래대금순: tradeAmount, 등락률순/검색결과: volume 표시
        const lastCol    = currentType === 'trade'
            ? formatTradeAmount(s.tradeAmount)
            : formatVolume(s.volume);
        const isWatching = watchingSet.has(code);
        const rank       = i + 1;

        return `
        <div class="stockRow ${selectedCode === code ? 'selected' : ''}"
             data-code="${code}" data-name="${name}">
            <div class="colRank">
                <span class="rankNum">${s._fromDb ? '' : rank}</span>
                <button class="heartBtn ${isWatching ? 'watching' : ''}"
                        data-code="${code}" data-name="${name}"
                        title="${isWatching ? '관심종목 해제' : '관심종목 + 알림 등록'}">
                    ${isWatching ? '♥' : '♡'}
                </button>
            </div>
            <div class="colName">
                <div class="rowStockName">${name}</div>
                <div class="rowStockCode">${code}</div>
            </div>
            <div class="colPrice">${price}</div>
            <div class="colRate ${cls}">${rate !== 0 ? arrow + ' ' + Math.abs(rate).toFixed(2) + '%' : '-'}</div>
            <div class="colTrade">${lastCol}</div>
        </div>`;
    }).join('');

    // 행 클릭
    container.querySelectorAll('.stockRow').forEach(row => {
        row.addEventListener('click', (e) => {
            if (e.target.closest('.heartBtn')) return;
            const code = row.dataset.code;
            const name = row.dataset.name;
            if (code) selectStock(code, name, row);
        });
    });

    // 하트 클릭
    container.querySelectorAll('.heartBtn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            toggleWatch(btn.dataset.code, btn.dataset.name, btn);
        });
    });
}

function syncDefaultPanelSelection(stocks) {
    if (!Array.isArray(stocks) || stocks.length === 0) {
        return;
    }

    const selectedRow = selectedCode
        ? document.querySelector(`.stockRow[data-code="${selectedCode}"]`)
        : null;

    if (selectedRow) {
        selectedRow.classList.add('selected');
        return;
    }

    const firstStock = stocks[0];
    const code = String(firstStock?.stockCode || firstStock?.code || '').trim();
    const name = String(firstStock?.stockName || firstStock?.name || code).trim();
    const firstRow = code
        ? document.querySelector(`.stockRow[data-code="${code}"]`)
        : document.querySelector('.stockRow');

    if (!code || !firstRow) {
        return;
    }

    selectStock(code, name, firstRow);
}

// ── 검색 드롭다운 ────────────────────────────────────────

let searchDebounce = null;

// 검색 자동완성 드롭다운을 숨긴다.
function hideSearchDropdown() {
    const dd = document.getElementById('searchDropdown');
    if (dd) dd.style.display = 'none';
}

// 검색 자동완성 후보 목록을 드롭다운으로 렌더링한다.
function showSearchDropdown(items) {
    const dd = document.getElementById('searchDropdown');
    if (!dd) return;

    if (!items || items.length === 0) {
        dd.innerHTML = '<div class="searchDropdownEmpty">검색 결과가 없습니다</div>';
        dd.style.display = 'block';
        return;
    }

    dd.innerHTML = items.map(item => {
        const code   = item.code   || item.stockCode || '';
        const name   = item.name   || item.stockName || '';
        const market = item.market || '';
        return `
        <div class="searchDropdownItem" data-code="${code}" data-name="${name}">
            <div class="searchDropdownLeft">
                <span class="searchDropdownName">${name}</span>
                ${market ? `<span class="searchMarketBadge">${market}</span>` : ''}
            </div>
            <span class="searchDropdownCode">${code}</span>
        </div>`;
    }).join('');

    // 드롭다운 항목 클릭
    dd.querySelectorAll('.searchDropdownItem').forEach(el => {
        el.addEventListener('click', () => {
            const code = el.dataset.code;
            const name = el.dataset.name;
            document.getElementById('stockSearchInput').value = name;
            document.getElementById('searchClearBtn').style.display = '';
            hideSearchDropdown();
            // 종목 선택 → 차트 패널 표시
            const matchedRow = document.querySelector(`.stockRow[data-code="${code}"]`);
            selectStock(code, name, matchedRow);
        });
    });

    dd.style.display = 'block';
}

// 현재 목록과 전체 종목 DB를 함께 활용해 검색 결과를 만든다.
async function runSearch(keyword) {
    if (!keyword) {
        hideSearchDropdown();
        return;
    }

    const kw = keyword.toLowerCase();

    // 1차: 현재 로드된 전체 목록에서 검색
    const localHits = allStocks.filter(s =>
        (s.stockName || '').toLowerCase().includes(kw) ||
        (s.stockCode || '').includes(kw)
    );

    if (localHits.length >= 3) {
        showSearchDropdown(localHits.slice(0, 10));
        return;
    }

    // 2차: DB 전체 검색
    try {
        const res   = await fetch(`/api/stock/search?keyword=${encodeURIComponent(keyword)}`);
        const items = await res.json();
        // 로컬 결과 + DB 결과 합치되 중복 제거
        const merged = [...localHits];
        const localCodes = new Set(localHits.map(s => s.stockCode || s.code));
        (items || []).forEach(item => {
            if (!localCodes.has(item.code)) merged.push(item);
        });
        showSearchDropdown(merged.slice(0, 10));
    } catch (e) {
        if (localHits.length > 0) showSearchDropdown(localHits.slice(0, 10));
        else hideSearchDropdown();
    }
}

// 검색 결과를 좌측 리스트에 반영한다.
function filterStockList(keyword) {
    const body     = document.getElementById('stockListBody');
    const noResult = document.getElementById('listNoResult');
    if (!body) return;

    const kw = String(keyword || '').trim().toLowerCase();

    if (!kw) {
        const visibleStocks = allStocks.slice(0, MARKET_LIST_LIMIT);
        renderStockList(body, visibleStocks);
        syncDefaultPanelSelection(visibleStocks);
        return;
    }

    const filtered = allStocks.filter(s =>
        (s.stockName || '').toLowerCase().includes(kw) ||
        (s.stockCode || '').includes(kw)
    ).slice(0, MARKET_LIST_LIMIT);

    if (!filtered.length) {
        body.innerHTML = '';
        if (noResult) noResult.style.display = 'block';
        return;
    }

    if (noResult) noResult.style.display = 'none';
    renderStockList(body, filtered);
    syncDefaultPanelSelection(filtered);
}

// ════════════════════════════════════════════════════════
//  종목 선택 → 차트 패널 표시
// ════════════════════════════════════════════════════════

// 종목을 선택하고 상세 패널과 차트를 해당 종목으로 전환한다.
async function selectStock(code, name, rowEl) {
    // 선택 하이라이트
    document.querySelectorAll('.stockRow').forEach(r => r.classList.remove('selected'));
    if (rowEl) rowEl.classList.add('selected');

    selectedCode = code;
    selectedName = name;

    // 패널 표시
    document.getElementById('chartPanelEmpty').style.display = 'none';
    document.getElementById('chartPanelContent').style.display = 'block';

    // 종목 정보 세팅
    document.getElementById('panelStockName').textContent = name;
    document.getElementById('panelStockCode').textContent = code;
    document.getElementById('panelDetailLink').href = `/market/${code}`;

    // 관심종목 버튼 상태
    const watchBtn = document.getElementById('panelWatchBtn');
    const isWatching = watchingSet.has(code);
    setPanelWatchBtn(watchBtn, isWatching);

    // 알림 안내 표시
    updateAlertNote(isWatching);

    // 패널 차트 탭 초기화
    panelTab = 'daily';
    document.querySelectorAll('.panelChartTab').forEach(b =>
        b.classList.toggle('active', b.dataset.tab === 'daily'));

    // 현재가 + 차트 로딩
    await updatePanelPrice();
    await initPanelChart();

    // 기존 폴링 정리 후 새로 시작
    if (pricePollingTimer) clearInterval(pricePollingTimer);
    const panelPriceTask = createPollingTask(updatePanelPrice);
    pricePollingTimer = setInterval(panelPriceTask, 5000);

    if (chartPollingTimer) clearInterval(chartPollingTimer);

    const panelChartTask = createPollingTask(async () => {
        if (isMarketOpen()) {
            await updatePanelChart();
        }
    });

    chartPollingTimer = setInterval(panelChartTask, 10000);
}

// ── 패널 현재가 업데이트 ─────────────────────────────────

// 좌측 목록에서 선택한 종목의 현재 시세를 상세 패널에 반영한다.
async function updatePanelPrice() {
    if (!selectedCode) return;
    try {
        const res  = await fetch(`/api/stock/${selectedCode}`);
        const data = await res.json();

        const price = Number(data.currentPrice || 0);
        const rate  = parseFloat(data.changeRate) || 0;
        // priceChange가 0이거나 없으면 rate로 역산
        let change  = parseFloat(data.priceChange);
        if (!change || isNaN(change)) {
            change = (price > 0 && rate !== 0)
                ? Math.round(price * (rate / 100) / (1 + rate / 100))
                : 0;
        }
        const dir  = rate !== 0 ? rate : change;
        const sign = dir >= 0 ? '+' : '';
        const arrow = dir >= 0 ? '▲' : '▼';
        const cls   = dir >= 0 ? 'up' : 'down';

        setEl('panelCurrentPrice', `${price.toLocaleString()}원`);
        const rateEl = document.getElementById('panelChangeRate');
        if (rateEl) {
            rateEl.textContent = `${sign}${change.toLocaleString()} (${sign}${rate.toFixed(2)}%) ${arrow}`;
            rateEl.className   = `panelChangeRate ${cls}`;
        }
        setEl('panelOpen', Number(data.openPrice  || 0).toLocaleString());
        setEl('panelHigh', Number(data.highPrice  || 0).toLocaleString());
        setEl('panelLow',  Number(data.lowPrice   || 0).toLocaleString());
        setEl('panelVol',  Number(data.volume     || 0).toLocaleString());
    } catch (e) {}
}

// ── Highcharts 공통 유틸 ────────────────────────────────

/** "YYYYMMDD" → UTC timestamp */
/** "YYYYMMDD" 또는 "HH:mm" → 로컬 타임스탬프 */
// 날짜 문자열을 Highcharts에서 쓰는 타임스탬프로 변환한다.
function dateStrToTs(s) {
    if (!s) return 0;

    // 시간 형식 (예: "14:10") 처리
    if (s.includes(':')) {
        const [h, m] = s.split(':').map(Number);
        const d = new Date();
        // 핵심: 수동 계산(-9시간) 없이 로컬 시/분으로 생성
        return new Date(d.getFullYear(), d.getMonth(), d.getDate(), h, m, 0, 0).getTime();
    }

    // 일별 형식 (예: "20260409") 처리
    if (s.length === 8) {
        const y = +s.slice(0, 4);
        const m = +s.slice(4, 6) - 1;
        const d = +s.slice(6, 8);
        return new Date(y, m, d, 0, 0, 0, 0).getTime();
    }
    return 0;
}

/** OHLCV 배열 빌드 */
// 서버 차트 응답을 OHLC/거래량 시리즈 구조로 변환한다.
function buildOhlcv(data) {
    const ohlc = [], vol = [];
    const labels = data.labels || [];
    const hasOhlc = data.openPrices && data.openPrices.length > 0 && data.openPrices[0] !== '0';
    for (let i = 0; i < labels.length; i++) {
        const ts = dateStrToTs(labels[i]);
        if (hasOhlc) {
            ohlc.push([ts, +data.openPrices[i], +data.highPrices[i], +data.lowPrices[i], +data.closePrices[i]]);
        } else {
            ohlc.push([ts, +data.closePrices[i]]);
        }
        vol.push([ts, +(data.volumes || [])[i] || 0]);
    }
    return { ohlc, vol, hasOhlc };
}

const getPriceColorFromUI = () => {
    const el =
        document.getElementById('panelChangeRate') ||
        document.getElementById('detailPriceChange');

    if (!el) return '#3b82f6';

    if (el.classList.contains('up')) return '#ef4444';
    if (el.classList.contains('down')) return '#3b82f6';
    return '#9ca3af'; // flat
};

/** Highcharts 공통 옵션
 * @param tab 'daily' | 'time' | 'minute'
 */
// 시장 화면 차트에서 공통으로 쓰는 Highcharts 옵션을 생성한다.
function hcBaseOptions(name, ohlc, vol, hasOhlc, compact, tab) {
    const isIntraday = (tab === 'time' || tab === 'minute');
    const timeFmt    = isIntraday ? '%H:%M' : '%Y-%m-%d';

    // 1. 현재 차트의 고유 식별자 (종목코드 등)
    // 참고: currentChartSource, getSelectedCurrency, currentCode, currentTab 등은 전역 변수로 존재한다고 가정
    const chartId = (typeof currentChartSource !== 'undefined' && currentChartSource === 'exchange')
        ? 'fx_' + (typeof getSelectedCurrency === 'function' ? getSelectedCurrency() : 'KRW')
        : (typeof currentCode !== 'undefined' ? currentCode : 'defaultId');
    const storageKey = `mainChart_${chartId}_${typeof currentTab !== 'undefined' ? currentTab : tab}`;

    // 2. 다른 종목/소스의 흔적 지우기
    Object.keys(sessionStorage).forEach(key => {
        if (key.startsWith('mainChart_') && !key.includes(`_${chartId}_`)) {
            sessionStorage.removeItem(key);
        }
    });

    // 3. 현재 종목/탭의 범위만 불러오기
    const savedMin = sessionStorage.getItem(storageKey + '_min');
    const savedMax = sessionStorage.getItem(storageKey + '_max');
    const pMin = savedMin ? parseFloat(savedMin) : undefined;
    const pMax = savedMax ? parseFloat(savedMax) : undefined;

    setInterval(() => {
        const min = sessionStorage.getItem(storageKey + '_min');
        const max = sessionStorage.getItem(storageKey + '_max');

        if (min || max) {
            sessionStorage.setItem(storageKey + '_ts', Date.now());
        }
    }, 5000);

    // 시가 plotLine 값
    const openPriceVal = hasOhlc && ohlc.length > 0
        ? (isIntraday ? ohlc[0][1] : ohlc[ohlc.length - 1][1])
        : null;

    // 시간별·분별 라인 방향 색상 (오르면 빨강, 내리면 파랑)
    const minuteLineColor = getPriceColorFromUI();

    // 모든 탭 캔들스틱 (OHLCV 있을 때) / 라인 (없을 때)
    const mainSeries = hasOhlc ? {
        type: 'candlestick', name,
        data: ohlc,
        color: '#3b82f6', upColor: '#ef4444',
        lineColor: '#3b82f6', upLineColor: '#ef4444',
        animation: false,
        lineWidth: 5,

        // 캔들 몸통 너비 (굵기)
        pointWidth: isIntraday ? (tab === 'time' ? 12 : 6) : undefined,
        dataGrouping: { enabled: false }
    } : {
        type: 'line', name,
        data: ohlc,
        color: minuteLineColor,
        lineWidth: tab === 'minute' ? 3 : 2,
        marker: { enabled: false },
        dataGrouping: { enabled: false }
    };

    return {
        time: { useUTC: false },
        chart: {
            backgroundColor: '#fff',
            style: { fontFamily: 'inherit' },
            animation: false,
            height: compact ? 220 : 340,
            events: {
                load: function () {
                    const savedMin = sessionStorage.getItem(storageKey + '_min');
                    const savedMax = sessionStorage.getItem(storageKey + '_max');

                    if (savedMin || savedMax) {
                        this.xAxis[0].setExtremes(
                            savedMin ? parseFloat(savedMin) : undefined,
                            savedMax ? parseFloat(savedMax) : undefined,
                            true,
                            false
                        );
                    }
                }
            }
        },
        plotOptions: {
            series: {
                animation: false
            }
        },
        credits: { enabled: false },
        rangeSelector: (compact || isIntraday) ? { enabled: false } : {
            selected: 1,
            inputEnabled: false,
            buttons: [
                { type: 'month', count: 1, text: '1개월' },
                { type: 'month', count: 3, text: '3개월' },
                { type: 'all',              text: '전체'   }
            ],
            buttonTheme: {
                fill: '#f9fafb', stroke: '#e5e7eb', r: 6,
                style: { color: '#374151', fontWeight: '600', fontSize: '11px' },
                states: { select: { fill: '#0E0F37', style: { color: '#fff' } } }
            }
        },
        navigator: { enabled: !compact && !isIntraday },
        scrollbar: { enabled: !compact && !isIntraday },
        tooltip: {
            split: false, shared: true, valueDecimals: 0,
            formatter: function () {
                const pts = this.points || [];
                let s = `<b>${Highcharts.dateFormat(timeFmt, this.x)}</b><br/>`;
                pts.forEach(p => {
                    if (p.series.type === 'candlestick') {
                        s += `시가 ${p.point.open?.toLocaleString()} · 고가 ${p.point.high?.toLocaleString()} · 저가 ${p.point.low?.toLocaleString()} · 종가 <b>${p.point.close?.toLocaleString()}</b>원<br/>`;
                    } else if (p.series.type === 'column') {
                        s += `거래량 ${p.y?.toLocaleString()}<br/>`;
                    } else {
                        s += `${p.y?.toLocaleString()}원<br/>`;
                    }
                });
                return s;
            }
        },
        xAxis: (() => {
            // 현재 종목/소스의 저장된 범위만 불러오기 (키 형식 통일)
            const savedMin = sessionStorage.getItem(storageKey + '_min');
            const savedMax = sessionStorage.getItem(storageKey + '_max');
            const savedTs  = sessionStorage.getItem(storageKey + '_ts');

            const TTL = 10000; // 10초

            let pMin, pMax;

            if (savedTs && (Date.now() - parseInt(savedTs, 10) < TTL)) {
                // ✅ 유효한 경우만 사용
                pMin = savedMin ? parseFloat(savedMin) : undefined;
                pMax = savedMax ? parseFloat(savedMax) : undefined;
            } else {
                // ❌ 만료 → 삭제
                sessionStorage.removeItem(storageKey + '_min');
                sessionStorage.removeItem(storageKey + '_max');
                sessionStorage.removeItem(storageKey + '_ts');
            }

            const firstDataTs = ohlc.length > 0 ? ohlc[0][0] : new Date().getTime();
            const d = new Date(firstDataTs);

            const base = {
                type: 'datetime',
                lineColor: '#e5e7eb',
                tickColor: '#e5e7eb',
                // 저장된 값이 있을 때만 적용, 없으면 undefined (기본값 사용)
                min: savedMin ? parseFloat(savedMin) : undefined,
                max: savedMax ? parseFloat(savedMax) : undefined,
                events: {
                    afterSetExtremes: function(e) {
                        if (e.trigger !== undefined) {
                            const now = Date.now();

                            sessionStorage.setItem(storageKey + '_min', e.min);
                            sessionStorage.setItem(storageKey + '_max', e.max);
                            sessionStorage.setItem(storageKey + '_ts', now);

                        }
                    }
                }
            };
            if (tab === 'daily') {
                return { ...base,min: pMin, max: pMax, ordinal: true,
                    dateTimeLabelFormats: { day: '%m/%d', week: '%m/%d', month: '%y/%m' } };
            }
            if (tab === 'time') {
                // 09:00 KST ~ 현재시간 (장마감 후에는 15:30) 표시
                const _n = new Date();
                const _at9    = Date.UTC(_n.getFullYear(), _n.getMonth(), _n.getDate(),  9,  0) - 9 * 3600000;
                const _at1530 = Date.UTC(_n.getFullYear(), _n.getMonth(), _n.getDate(), 15, 30) - 9 * 3600000;
                const _nowTs  = Date.UTC(_n.getFullYear(), _n.getMonth(), _n.getDate(), _n.getHours(), _n.getMinutes()) - 9 * 3600000;
                const _xMax   = _nowTs < _at1530 ? _nowTs : _at1530;
                return { ...base, ordinal: false,
                    tickInterval: 3600000,
                    min: pMin || _at9,  // 저장된 값이 있으면 우선 사용, 없으면 9시
                    max: pMax || _xMax,
                    dateTimeLabelFormats: { millisecond: '%H:%M', second: '%H:%M', minute: '%H:%M', hour: '%H:%M' } };
            }
            // minute: 09:00 KST ~ 현재시간
            const _nm     = new Date();
            const _at9m   = new Date(_nm.getFullYear(), _nm.getMonth(), _nm.getDate(),  9,  0, 0, 0).getTime();
            const _at1530m= new Date(_nm.getFullYear(), _nm.getMonth(), _nm.getDate(), 15, 30, 0, 0).getTime();
            const _nowTsm = new Date(_nm.getFullYear(), _nm.getMonth(), _nm.getDate(), _nm.getHours(), _nm.getMinutes(), 0, 0).getTime();
            const _xMaxm  = _nowTsm < _at1530m ? _nowTsm : _at1530m;
            return { ...base, ordinal: false,
                min: pMin || _at9m,
                max: pMax || _xMaxm,
                dateTimeLabelFormats: { millisecond: '%H:%M', second: '%H:%M', minute: '%H:%M', hour: '%H:%M' } };
        })(),
        yAxis: [{
            // 메인 차트 (위)
            labels: { align: 'left', style: { color: '#374151', fontSize: '10px' },
                formatter: function() { return this.value.toLocaleString(); } },
            height: '72%', gridLineColor: '#f3f4f6',

            resize: { enabled: !compact },
            plotLines: []
        }, {
            // 거래량 차트 (아래)
            labels: { align: 'left', style: { color: '#9ca3af', fontSize: '10px' } },
            top: '72%', height: '28%', offset: 0,
            gridLineColor: '#f9fafb'
        }],
        series: [
            mainSeries,
            {
                type: 'column', name: '거래량',
                data: vol, yAxis: 1,
                color: '#e5e7eb',
                dataGrouping: { enabled: false },
                animation: false
            }
        ],
        responsive: {
            rules: [{ condition: { maxWidth: 500 },
                chartOptions: { rangeSelector: { inputEnabled: false } } }]
        }
    };
    if (savedMin || savedMax) {
        const min = savedMin ? parseFloat(savedMin) : undefined;
        const max = savedMax ? parseFloat(savedMax) : undefined;

        // 데이터가 완전히 렌더링된 후 실행되도록 0ms 타임아웃 부여
        setTimeout(() => {
            if (mainChart && mainChart.xAxis[0]) {
                mainChart.xAxis[0].setExtremes(min, max, true, false);
            }
        }, 0);
    }
}
// ── 패널 차트 (market 페이지 오른쪽 패널) ────────────────

// 상세 패널용 차트를 최초 생성한다.
async function initPanelChart() {
    await renderPanelChart();
}

async function renderPanelChart() {
    const data = await fetchPanelChartData();
    if (panelChart) { panelChart.destroy(); panelChart = null; }
    const el = document.getElementById('panelChart');
    if (!el) return;
    const { ohlc, vol, hasOhlc } = buildOhlcv(data);
    panelChart = Highcharts.stockChart('panelChart',
        hcBaseOptions(selectedName, ohlc, vol, hasOhlc, true, panelTab));
}

// 상세 패널용 차트 데이터를 다시 불러와 갱신한다.
async function updatePanelChart() {
    await renderPanelChart();
}
async function startPolling() {
    if (pollingStarted) {
        return;
    }
    pollingStarted = true;

    const tickerTask = createPollingTask(async () => {
        const tickerData = await updateTicker();

        if (currentChartSource === 'stock') {
            await updateStockInfo();
            return;
        }
    });
    const topStocksTask = createPollingTask(updateTopStocks);
    const chartTask = createPollingTask(async () => {
        if (currentChartSource === 'exchange') {
            return;
        }

        if (isMarketOpen()) {
            await updateMainChart();
        }
    });
    const exchangeTask = createPollingTask(async () => {
        const exchangeData = await updateExchangeCard();
        if (currentChartSource === 'exchange') {
            renderExchangeSummary(exchangeData);
        }
    });

    await Promise.all([
        updateTicker(),
        updateExchangeCard(),
        updateTopStocks()
    ]);
    await refreshActiveView();

    setInterval(tickerTask, 5000);
    setInterval(topStocksTask, 30000);
    setInterval(chartTask, 10000);
    setInterval(exchangeTask, 60000);
}
// 현재 선택 종목과 탭에 맞는 상세 패널 차트 데이터를 조회한다.
async function fetchPanelChartData() {
    let tab = panelTab;
    if ((tab === 'time' || tab === 'minute') && !isMarketOpen()) tab = 'daily';

    const ep = {
        daily:  `/api/stock/${selectedCode}/chart`,
        time:   `/api/stock/${selectedCode}/time`,
        minute: `/api/stock/${selectedCode}/minute`
    };
    try {
        const res  = await fetch(ep[tab]);
        const data = await res.json();
        if (!data.labels || data.labels.length === 0) {
            const fb = await fetch(`/api/stock/${selectedCode}/chart`);
            return await fb.json();
        }
        return data;
    } catch (e) {
        return { labels: [], closePrices: [] };
    }
}

// ════════════════════════════════════════════════════════
//  관심종목 + 알림 토글
// ════════════════════════════════════════════════════════

// 관심종목 등록 상태를 토글하고 관련 버튼 상태를 갱신한다.
async function toggleWatch(code, name, triggerEl) {
    if (!code) return;
    const isWatching = watchingSet.has(code);

    if (isWatching) {
        await fetch(`/api/watchlist/${code}`, { method: 'DELETE' });
        watchingSet.delete(code);
    } else {
        await fetch('/api/watchlist', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ stockCode: code, stockName: name })
        });
        watchingSet.add(code);
    }

    const nowWatching = watchingSet.has(code);

    // 리스트 내 하트 버튼 갱신
    document.querySelectorAll(`.heartBtn[data-code="${code}"]`).forEach(btn => {
        btn.classList.toggle('watching', nowWatching);
        btn.textContent = nowWatching ? '♥' : '♡';
        btn.title = nowWatching ? '관심종목 해제' : '관심종목 + 알림 등록';
    });

    // 패널 버튼도 갱신
    if (code === selectedCode) {
        const panelBtn = document.getElementById('panelWatchBtn');
        if (panelBtn) setPanelWatchBtn(panelBtn, nowWatching);
        updateAlertNote(nowWatching);
    }

    // 뱃지 갱신
    refreshBadge();
}

// 상세 패널의 관심종목 버튼 텍스트와 스타일을 갱신한다.
function setPanelWatchBtn(btn, watching) {
    btn.textContent = watching ? '♥' : '♡';
    btn.classList.toggle('watching', watching);
    btn.title = watching ? '관심종목 해제' : '관심종목 + 알림 등록';
}

// 관심종목 등록 상태에 따라 알림 안내 문구를 바꾼다.
function updateAlertNote(watching) {
    const note = document.getElementById('panelAlertNote');
    if (!note) return;
    if (watching) {
        note.textContent = '✓ 관심종목 등록됨 · ±3% 변동 시 알림을 받습니다';
        note.className = 'panelAlertNote watching-note';
    } else {
        note.textContent = '♡ 관심종목 등록 시 ±3% 변동 알림을 받습니다';
        note.className = 'panelAlertNote show';
    }
}

// ════════════════════════════════════════════════════════
//  헤더 알림 패널 → bell.js 에서 처리 (모든 페이지 공통)
// ════════════════════════════════════════════════════════
// initAlertPanel, refreshBadge, loadAlertPanel, loadWatchlistPanel,
// removeFromPanel, formatAlertTime 은 bell.js 에 정의됨

// ════════════════════════════════════════════════════════
//  종목 상세 페이지 (marketDetail.html)
// ════════════════════════════════════════════════════════

let detailChart = null;
let detailTab   = 'daily';

// 개별 종목 상세 페이지 진입 시 필요한 초기 상태를 설정한다.
function initDetailPage(code) {
    document.querySelectorAll('.detailChartTab').forEach(btn => {
        btn.addEventListener('click', async () => {
            document.querySelectorAll('.detailChartTab')
                .forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            detailTab = btn.dataset.tab;
            if ((detailTab === 'time' || detailTab === 'minute') && !isMarketOpen()) {
                alert('시간별/분별 차트는 장 운영시간(09:00~15:30)에만 제공됩니다.');
                detailTab = 'daily';
                document.querySelectorAll('.detailChartTab')
                    .forEach(b => b.classList.toggle('active', b.dataset.tab === 'daily'));
            }
            await updateDetailChart(code);
        });
    });

    initDetailChart(code);

    const detailPriceTask = createPollingTask(() => updateDetailPrice(code));
    const detailChartTask = createPollingTask(async () => {
        if (isMarketOpen()) {
            await updateDetailChart(code);
        }
    });

    if (detailPricePollingTimer) clearInterval(detailPricePollingTimer);
    if (detailChartPollingTimer) clearInterval(detailChartPollingTimer);
    detailPricePollingTimer = setInterval(detailPriceTask, 5000);
    detailChartPollingTimer = setInterval(detailChartTask, 10000);
}

// 개별 종목 상세 페이지 차트를 최초 생성한다.
async function initDetailChart(code) {
    await renderDetailChart(code);
}

async function renderDetailChart(code) {
    const data = await fetchDetailChartData(code);
    if (detailChart) { detailChart.destroy(); detailChart = null; }
    const el = document.getElementById('detailChart');
    if (!el) return;
    const name = el.dataset.name || code;
    const { ohlc, vol, hasOhlc } = buildOhlcv(data);
    detailChart = Highcharts.stockChart('detailChart',
        hcBaseOptions(name, ohlc, vol, hasOhlc, false, detailTab));
}

// 개별 종목 상세 페이지 차트를 다시 갱신한다.
async function updateDetailChart(code) {
    await renderDetailChart(code);
}

// 개별 종목 상세 페이지에서 사용할 차트 데이터를 조회한다.
async function fetchDetailChartData(code) {
    let tab = detailTab;
    if ((tab === 'time' || tab === 'minute') && !isMarketOpen()) tab = 'daily';
    const ep = {
        daily:  `/api/stock/${code}/chart`,
        time:   `/api/stock/${code}/time`,
        minute: `/api/stock/${code}/minute`
    };
    try {
        const res  = await fetch(ep[tab]);
        const data = await res.json();
        if (!data.labels || data.labels.length === 0) {
            const fb = await fetch(`/api/stock/${code}/chart`);
            return await fb.json();
        }
        return data;
    } catch (e) {
        return { labels: [], closePrices: [] };
    }
}

// 개별 종목 상세 페이지의 현재 시세 정보를 갱신한다.
async function updateDetailPrice(code) {
    try {
        const res  = await fetch(`/api/stock/${code}`);
        const data = await res.json();
        const price = Number(data.currentPrice || 0);
        const rate  = parseFloat(data.changeRate || '0');
        let change  = parseFloat(data.priceChange);
        if (!change || isNaN(change)) {
            change = (price > 0 && rate !== 0)
                ? Math.round(price * (rate / 100) / (1 + rate / 100))
                : 0;
        }
        const dir   = rate !== 0 ? rate : change;
        const sign  = dir >= 0 ? '+' : '';
        const arrow = dir >= 0 ? '▲' : '▼';
        setEl('detailCurrentPrice', `${price.toLocaleString()} KRW`);
        const cel = document.getElementById('detailPriceChange');
        if (cel) {
            cel.textContent = `${sign}${change.toLocaleString()} (${sign}${rate.toFixed(2)}%) ${arrow}`;
            cel.className   = 'detailPriceChange ' + (dir >= 0 ? 'up' : 'down');
        }
        setEl('detailOpenPrice', Number(data.openPrice || 0).toLocaleString());
        setEl('detailHighPrice', Number(data.highPrice || 0).toLocaleString());
        setEl('detailLowPrice',  Number(data.lowPrice  || 0).toLocaleString());
        setEl('detailVolume',    Number(data.volume    || 0).toLocaleString());
    } catch (e) {}
}

// 상세 페이지의 관심종목 버튼 클릭 이벤트를 연결한다.
function initWatchBtn(code, stockName) {
    const btn = document.getElementById('watchBtn');
    if (!btn) return;
    btn.addEventListener('click', async () => {
        const watching = btn.classList.contains('watching');
        if (watching) {
            await fetch(`/api/watchlist/${code}`, { method: 'DELETE' });
            btn.classList.remove('watching');
            btn.innerHTML = '☆ 관심종목 추가';
        } else {
            await fetch('/api/watchlist', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ stockCode: code, stockName })
            });
            btn.classList.add('watching');
            btn.innerHTML = '★ 관심종목 등록됨';
        }
    });
}

// ════════════════════════════════════════════════════════
//  공통 유틸
// ════════════════════════════════════════════════════════

// 현재 시간이 국내 주식 정규장 시간인지 판단한다.
function isMarketOpen() {
    const now = new Date();
    if (now.getDay() === 0 || now.getDay() === 6) return false;
    const t = now.getHours() * 100 + now.getMinutes();
    return t >= 900 && t <= 1530;
}

// 요소 ID 기준으로 텍스트를 안전하게 갱신한다.
function setEl(id, text) {
    const el = document.getElementById(id);
    if (el) el.textContent = text;
}

// ════════════════════════════════════════════════════════
//  DOMContentLoaded
// ════════════════════════════════════════════════════════

// 시장 화면 진입 시 목록, 검색, 차트, 상세 페이지 초기화를 한 번에 수행한다.
document.addEventListener('DOMContentLoaded', async () => {
    // 알림 패널은 bell.js 에서 자동 초기화됨

    // ── 종목 리스트 페이지 ──
    if (document.getElementById('stockListBody')) {

        // 관심종목 목록 먼저 로드 (하트 상태 표시용)
        try {
            const res  = await fetch('/api/watchlist');
            const list = await res.json();
            list.forEach(w => watchingSet.add(w.stockCode));
        } catch (e) {}

        // 필터 버튼 (리스트 내부)
        document.querySelectorAll('.listFilterBtn').forEach(btn => {
            btn.addEventListener('click', () => {
                document.querySelectorAll('.listFilterBtn').forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                // 검색어 초기화
                const input = document.getElementById('stockSearchInput');
                const clearBtn = document.getElementById('searchClearBtn');
                if (input) input.value = '';
                if (clearBtn) clearBtn.style.display = 'none';
                loadMarketData(btn.dataset.type);
            });
        });

        // 검색 입력 → 드롭다운 자동완성
        const searchInput = document.getElementById('stockSearchInput');
        const clearBtn    = document.getElementById('searchClearBtn');

        searchInput?.addEventListener('input', () => {
            const kw = searchInput.value.trim();
            clearBtn.style.display = kw ? '' : 'none';
            clearTimeout(searchDebounce);
            if (kw.length === 0) { hideSearchDropdown(); return; }
            searchDebounce = setTimeout(() => runSearch(kw), 250);
        });

        // ESC 키로 드롭다운 닫기
        searchInput?.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                hideSearchDropdown();
                searchInput.blur();
            }
        });

        clearBtn?.addEventListener('click', () => {
            searchInput.value = '';
            clearBtn.style.display = 'none';
            hideSearchDropdown();
            searchInput.focus();
        });

        // 외부 클릭 시 드롭다운 닫기
        document.addEventListener('click', (e) => {
            if (!e.target.closest('.listSearchBarWrapper')) {
                hideSearchDropdown();
            }
        });

        // 패널 차트 탭
        document.querySelectorAll('.panelChartTab').forEach(btn => {
            btn.addEventListener('click', async () => {
                if (!selectedCode) return;
                document.querySelectorAll('.panelChartTab')
                    .forEach(b => b.classList.remove('active'));
                btn.classList.add('active');
                panelTab = btn.dataset.tab;
                if ((panelTab === 'time' || panelTab === 'minute') && !isMarketOpen()) {
                    alert('시간별/분별 차트는 장 운영시간(09:00~15:30)에만 제공됩니다.');
                    panelTab = 'daily';
                    document.querySelectorAll('.panelChartTab')
                        .forEach(b => b.classList.toggle('active', b.dataset.tab === 'daily'));
                }
                await updatePanelChart();
            });
        });

        // 패널 관심종목 버튼
        document.getElementById('panelWatchBtn')?.addEventListener('click', () => {
            if (selectedCode) toggleWatch(selectedCode, selectedName, null);
        });

        // 데이터 로딩
        const marketListTask = createPollingTask(() => loadMarketData(currentType));
        await loadMarketData('trade');

        // 30초마다 리스트 가격 갱신
        if (marketListPollingTimer) clearInterval(marketListPollingTimer);
        marketListPollingTimer = setInterval(marketListTask, 30000);
    }

    // ── 종목 상세 페이지 ──
    const detailCanvas = document.getElementById('detailChart');
    if (detailCanvas) {
        const code = detailCanvas.dataset.code;
        const name = detailCanvas.dataset.name;
        initDetailPage(code);
        initWatchBtn(code, name);
        updateDetailPrice(code);
    }
    await startPolling();
});
