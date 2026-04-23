if (typeof Highcharts !== 'undefined') {
    Highcharts.setOptions({
        time: {
            useUTC: false
        }
    });
}

let mainChart = null;
let currentCode = '005930';
let currentStockName = '';
let currentTab = 'daily';
let currentChartSource = 'stock';
let searchTimer = null;
let pollingStarted = false;
let latestChartData = emptyChartData();
let aiRotatorItems = [];
let aiRotatorIndex = 0;
let aiRotatorTimer = null;
let aiRefreshToken = 0;
let currentChangeDirection = 0

// 환율은 같은 통화를 반복 조회하는 경우가 많아서 브라우저 메모리에 짧게 캐시
const EXCHANGE_QUOTE_TTL_MS = 60 * 1000;
const EXCHANGE_CHART_TTL_MS = 30 * 60 * 1000;
const exchangeQuoteCache = new Map();
const exchangeChartCache = new Map();
const exchangeQuoteRequests = new Map();
const exchangeChartRequests = new Map();

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

// 페이지 진입 시 게이지, 차트, 티커, AI 패널을 한 번에 초기화
document.addEventListener('DOMContentLoaded', () => {
    currentStockName = document.getElementById('chartStockName')?.textContent.trim() || '삼성전자';
    currentCode = document.getElementById('chartStockCode')?.textContent.trim() || currentCode;

    const stockSeed = window.AI_ROTATOR_DATA?.stock || {};
    currentCode = stockSeed.stockCode || currentCode;
    currentStockName = stockSeed.stockName || currentStockName || currentCode;

    animateGauges();
    bindChartTabs();
    bindSearchHandlers();
    bindTickerCards();
    bindTopStockTicker();
    bindExchangeSelector();
    bindSectorTrendCards();
    syncChartTabs();
    applyActiveTickerState();
    initAiRotator();
    startPolling();
    void refreshAiPredictionForCurrentCode();
});

// 차트 탭 클릭 이벤트를 연결하고 장중 여부에 따라 탭 사용을 제어
function bindChartTabs() {
    document.querySelectorAll('.chartTab').forEach((button) => {
        button.addEventListener('click', async () => {
            if (button.disabled) {
                return;
            }

            const nextTab = button.dataset.tab;
            if ((nextTab === 'time' || nextTab === 'minute') && !isMarketOpen() && currentChartSource !== 'exchange') {
                alert('시간별/분별 차트는 장 운영시간(09:00~15:30)에만 제공됩니다.');
                currentTab = 'daily';
                syncChartTabs();
                await updateMainChart();
                return;
            }

            currentTab = nextTab;
            syncChartTabs();
            await refreshActiveSummary();
            await updateMainChart();
        });
    });
}

// 종목 검색 입력, 자동완성 목록, 엔터 선택 동작을 연결
function bindSearchHandlers() {
    const searchInput = document.getElementById('stockSearch');
    const dropdown = document.getElementById('searchDropdown');

    if (!searchInput || !dropdown) {
        return;
    }

    searchInput.addEventListener('input', (event) => {
        const keyword = event.target.value.trim();
        clearTimeout(searchTimer);

        if (keyword.length < 1) {
            dropdown.style.display = 'none';
            dropdown.innerHTML = '';
            return;
        }

        searchTimer = setTimeout(async () => {
            try {
                const items = await fetchJson(`/api/stock/search?keyword=${encodeURIComponent(keyword)}`);
                if (!Array.isArray(items) || items.length === 0) {
                    dropdown.style.display = 'none';
                    dropdown.innerHTML = '';
                    return;
                }

                dropdown.innerHTML = items.slice(0, 8).map((item) => `
                    <div class="searchItem" data-code="${item.code}" data-name="${item.name}">
                        <div>
                            <span class="item-name">${item.name}</span>
                            <span class="item-market">${item.market || ''}</span>
                        </div>
                        <span class="item-code">${item.code}</span>
                    </div>
                `).join('');

                dropdown.querySelectorAll('.searchItem').forEach((entry) => {
                    entry.addEventListener('click', async () => {
                        await selectStockAndActivate(
                            entry.dataset.code,
                            entry.dataset.name || entry.dataset.code,
                            searchInput
                        );
                        dropdown.style.display = 'none';
                    });
                });

                dropdown.style.display = 'block';
            } catch (error) {
                // console.log('search error:', error);
            }
        }, 300);
    });

    searchInput.addEventListener('keydown', async (event) => {
        if (event.key !== 'Enter') {
            return;
        }
        event.preventDefault();

        const keyword = event.target.value.trim();
        if (!keyword) {
            return;
        }

        const selection = await resolveSearchSelection(keyword);
        await selectStockAndActivate(selection.code, selection.name, searchInput);
        dropdown.style.display = 'none';
    });

    document.addEventListener('click', (event) => {
        if (!event.target.closest('.searchWrapper')) {
            dropdown.style.display = 'none';
        }
    });
}

// 입력된 검색어를 실제 종목 코드와 이름 조합으로 해석
async function resolveSearchSelection(keyword) {
    const parsed = parseSearchKeyword(keyword);
    if (parsed?.resolved) {
        return parsed;
    }

    try {
        const lookupKeyword = parsed?.code || keyword;
        const items = await fetchJson(`/api/stock/search?keyword=${encodeURIComponent(lookupKeyword)}`);
        if (!Array.isArray(items) || items.length === 0) {
            return {
                code: parsed?.code || keyword,
                name: parsed?.name || parsed?.code || keyword
            };
        }

        const normalizedKeyword = String(lookupKeyword || '').trim().toLowerCase();
        const exact = items.find((item) => {
            const code = String(item?.code || '').trim().toLowerCase();
            const name = String(item?.name || '').trim().toLowerCase();
            return code === normalizedKeyword || name === normalizedKeyword;
        });
        const candidate = exact || items[0];
        return {
            code: candidate?.code || keyword,
            name: candidate?.name || candidate?.code || keyword
        };
    } catch (error) {
        // console.log('search resolve error:', error);
        return { code: keyword, name: keyword };
    }
}

// 현재 선택값이 이름 위주일 때 실제 종목 코드로 다시 보정
async function ensureResolvedCurrentCode() {
    const rawCode = String(currentCode || '').trim();
    if (/^\d{6}$/.test(rawCode)) {
        return;
    }

    const keyword = rawCode || String(currentStockName || '').trim();
    if (!keyword) {
        return;
    }

    const selection = await resolveSearchSelection(keyword);
    applySearchSelection(
        selection.code,
        selection.name,
        document.getElementById('stockSearch')
    );
}

// 검색창 문자열에서 종목명/종목코드 패턴을 분리
function parseSearchKeyword(keyword) {
    const value = String(keyword || '').trim();
    if (!value) {
        return null;
    }

    const labeledMatch = value.match(/^(.*?)\s*\((\d{6})\)$/);
    if (labeledMatch) {
        return {
            code: labeledMatch[2],
            name: labeledMatch[1].trim() || labeledMatch[2],
            resolved: true
        };
    }

    const codeMatch = value.match(/^(\d{6})$/);
    if (codeMatch) {
        return {
            code: codeMatch[1],
            name: codeMatch[1],
            resolved: false
        };
    }

    return null;
}

// 선택된 종목 정보를 전역 상태와 검색창 값에 반영
function applySearchSelection(code, name, searchInput) {
    currentCode = String(code || '').trim();
    currentStockName = String(name || code || '').trim();
    if (searchInput) {
        searchInput.value = currentStockName && currentCode
            ? `${currentStockName} (${currentCode})`
            : (currentStockName || currentCode);
    }
}

// 검색, 전광판 클릭 등 종목 전환 진입점을 하나로 묶어 차트와 AI 브리핑이 함께 갱신
// 검색 결과에서 종목을 고른 뒤 주식 차트 소스로 즉시 전환
async function selectStockAndActivate(code, name, searchInput = document.getElementById('stockSearch')) {
    applySearchSelection(code, name, searchInput);
    await activateStockSource();
}

// 상단 KOSPI/KOSDAQ/환율 카드 클릭 이벤트를 연결
function bindTickerCards() {
    document.querySelectorAll('.clickableTicker').forEach((card) => {
        card.addEventListener('click', async () => {
            const source = card.dataset.chartSource;
            if (!source) {
                return;
            }
            Object.keys(sessionStorage).forEach(key => {
                if (key.startsWith('mainChart_')) {
                    sessionStorage.removeItem(key);
                }
            });
            await activateChartSource(source);
        });
    });
}

// 하단 전광판 종목 클릭 시 해당 종목 차트로 전환
function bindTopStockTicker() {
    const handleClick = async (event) => {
        const item = event.target.closest('.topStockItem');
        if (!item) {
            return;
        }

        event.preventDefault();
        const fallbackName = item.dataset.name || '';
        let stockCode = item.dataset.code || '';
        let stockName = fallbackName;

        if (!/^\d{6}$/.test(stockCode)) {
            const selection = await resolveSearchSelection(fallbackName);
            stockCode = selection.code;
            stockName = selection.name;
        }

        if (!stockCode) {
            return;
        }

        await selectStockAndActivate(stockCode, stockName || stockCode);
    };

    document.getElementById('tickerContent1')?.addEventListener('click', handleClick);
    document.getElementById('tickerContent2')?.addEventListener('click', handleClick);
}

// 환율 통화 선택기의 클릭/변경 이벤트를 연결
function bindExchangeSelector() {
    const currencySelect = document.getElementById('currencySelect');
    if (!currencySelect) {
        return;
    }

    currencySelect.addEventListener('click', (event) => {
        event.stopPropagation();
    });

    currencySelect.addEventListener('change', handleExchangeCurrencyChange);
}

// 선택 통화가 바뀌면 환율 카드와 메인 차트를 다시 갱신
async function handleExchangeCurrencyChange(event) {
    event.stopPropagation();

    if (currentChartSource !== 'exchange') {
        await updateExchangeCard();
        return;
    }

    const currency = getSelectedCurrency();
    const { quote, chartData } = await loadExchangeSelectionData(currency);
    latestChartData = chartData;
    renderExchangeSummary(quote, chartData);
    await updateMainChart();
}

// 환율 화면에서는 시세를 먼저 갱신하고, 차트는 같은 통화 캐시를 재사용해서 뒤이어 반영
// 선택한 통화의 현재가와 차트 데이터를 함께 불러옴
async function loadExchangeSelectionData(currency) {
    const quotePromise = getExchangeQuote(currency);
    const chartPromise = getExchangeChart(currency);
    const quote = await quotePromise;
    await updateExchangeCard(quote, null, currency);
    const chartData = await chartPromise;
    return { quote, chartData };
}

// 섹터 카드의 시각 효과와 링크 이동 관련 동작을 연결
function bindSectorTrendCards() {
    const setCollapsedState = (box, collapsed) => {
        box.classList.toggle('is-collapsed', collapsed);
        const toggle = box.querySelector('.scTrendToggle');
        if (!toggle) {
            return;
        }

        toggle.setAttribute('aria-expanded', String(!collapsed));
        toggle.setAttribute('aria-label', collapsed ? '상승 점수 펼치기' : '상승 점수 접기');
    };

    const getRowTrendBoxes = (sourceBox) => {
        const card = sourceBox.closest('.sectorCard');
        if (!card || !card.parentElement) {
            return [sourceBox];
        }

        const rowTop = card.offsetTop;
        return Array.from(card.parentElement.querySelectorAll('.sectorCard'))
            .filter((item) => Math.abs(item.offsetTop - rowTop) <= 4)
            .map((item) => item.querySelector('.scTrendBox'))
            .filter(Boolean);
    };

    document.querySelectorAll('.scTrendBox').forEach((box) => {
        const toggle = box.querySelector('.scTrendToggle');
        if (!toggle) {
            return;
        }

        setCollapsedState(box, box.classList.contains('is-collapsed'));

        toggle.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            const nextCollapsed = !box.classList.contains('is-collapsed');
            getRowTrendBoxes(box).forEach((rowBox) => setCollapsedState(rowBox, nextCollapsed));
        });
    });
}

// 주기적으로 현재 화면에 보이는 시세와 차트를 자동 갱신
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

        if (currentChartSource === 'kospi' && tickerData.kospi) {
            renderIndexSummary('kospi', tickerData.kospi);
        }

        if (currentChartSource === 'kosdaq' && tickerData.kosdaq) {
            renderIndexSummary('kosdaq', tickerData.kosdaq);
        }
    });
    const topStocksTask = createPollingTask(updateTopStocks);
    const chartTask = createPollingTask(async () => {
        if (currentChartSource === 'exchange') {
            return;
        }

        if (isMarketOpen()) {
            await refreshActiveSummary();
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

// 현재 선택 종목을 메인 차트의 활성 소스로 전환
async function activateStockSource() {
    await ensureResolvedCurrentCode();
    currentChartSource = 'stock';
    showPendingStockAiCard(currentCode, currentStockName || currentCode);
    await refreshActiveView();
    await refreshAiPredictionForCurrentCode();
}

// 메인 차트가 어떤 데이터 소스를 볼지 전환
async function activateChartSource(source) {
    currentChartSource = source;
    if (source === 'exchange') {
        currentTab = 'daily';
    }
    await refreshActiveView();
}

// 현재 선택된 소스와 탭 기준으로 화면 전체를 다시 그림
async function refreshActiveView() {
    syncChartTabs();
    applyActiveTickerState();

    await refreshActiveSummary();
    if (mainChart) {
        await updateMainChart();
    } else {
        await initMainChart();
    }

}

// 현재 선택된 차트 탭 상태를 버튼 클래스와 비활성 상태에 반영
function syncChartTabs() {
    if (currentChartSource === 'exchange') {
        currentTab = 'daily';
    }

    if ((currentTab === 'time' || currentTab === 'minute') && !isMarketOpen()) {
        currentTab = 'daily';
    }

    document.querySelectorAll('.chartTab').forEach((button) => {
        const isExchangeDisabled = currentChartSource === 'exchange' && button.dataset.tab !== 'daily';
        button.disabled = isExchangeDisabled;
        button.classList.toggle('active', button.dataset.tab === currentTab);
    });
}

// 현재 활성화된 티커 카드에 선택 스타일을 반영
function applyActiveTickerState() {
    document.querySelectorAll('.clickableTicker').forEach((card) => {
        const isActive = currentChartSource !== 'stock' && card.dataset.chartSource === currentChartSource;
        card.classList.toggle('activeTicker', isActive);
    });
}

let chartFetchToken = 0;

async function fetchMainChartData() {
    const myToken = ++chartFetchToken;
    syncChartTabs();

    if (currentChartSource === 'exchange') {
        try {
            const data = await getExchangeChart(getSelectedCurrency());
            if (myToken !== chartFetchToken) return emptyChartData(); // ← 체크
            latestChartData = data;
            return data;
        } catch (error) {
            return emptyChartData();
        }
    }

    const endpoint = getChartEndpoint(currentChartSource, currentTab);
    if (!endpoint) {
        latestChartData = emptyChartData();
        return latestChartData;
    }

    try {
        const data = normalizeChartData(await fetchJson(endpoint)); // ← await 완료 후
        if (myToken !== chartFetchToken) return emptyChartData();   // ← 즉시 체크

        if (data.labels.length === 0 && currentTab !== 'daily' && currentChartSource !== 'exchange') {
            currentTab = 'daily';
            syncChartTabs();
            return await fetchMainChartData(); // 재귀 시 토큰은 이미 최신이므로 OK
        }

        latestChartData = data;
        return data;
    } catch (error) {
        return emptyChartData();
    }
}

// 소스와 탭 조합에 맞는 API 엔드포인트를 계산
function getChartEndpoint(source, tab) {
    const currency = encodeURIComponent(getSelectedCurrency());

    const endpoints = {
        stock: {
            daily: `/api/stock/${currentCode}/chart`,
            time: `/api/stock/${currentCode}/time`,
            minute: `/api/stock/${currentCode}/minute`
        },
        kospi: {
            daily: '/api/kospi/chart',
            time: '/api/kospi/time',
            minute: '/api/kospi/minute'
        },
        kosdaq: {
            daily: '/api/kosdaq/chart',
            time: '/api/kosdaq/time',
            minute: '/api/kosdaq/minute'
        },
        exchange: {
            daily: `/api/exchange/chart?currency=${currency}`
        }
    };

    return endpoints[source]?.[tab] || null;
}

// Highcharts 메인 차트를 최초 생성
async function initMainChart() {
    const data = await fetchMainChartData();

    if (mainChart) {
        mainChart.destroy();
    }

    // 1. 현재 차트의 고유 식별자 (종목코드 등)
    const chartId = (currentChartSource === 'exchange') ? 'fx_' + getSelectedCurrency() : (currentCode || currentChartSource);
    const storageKey = `mainChart_${chartId}_${currentTab}`;

    // 2. [추가] 다른 종목/소스의 흔적 지우기
    // sessionStorage 전체를 뒤져서 'mainChart_'로 시작하지만, 현재 chartId가 아닌 것들은 삭제
    Object.keys(sessionStorage).forEach(key => {
        if (key.startsWith('mainChart_') && !key.includes(`_${chartId}_`)) {
            sessionStorage.removeItem(key);
        }
    });

    // 3. 현재 종목/탭의 범위만 불러오기
    const savedMin = sessionStorage.getItem(storageKey + '_min');
    const savedMax = sessionStorage.getItem(storageKey + '_max');
    const savedTs  = sessionStorage.getItem(storageKey + '_ts');

    const TTL = 10000; // 10초

    let pMin, pMax;

    setInterval(() => {
        const min = sessionStorage.getItem(storageKey + '_min');
        const max = sessionStorage.getItem(storageKey + '_max');

        if (min || max) {
            sessionStorage.setItem(storageKey + '_ts', Date.now());
        }
    }, 5000);

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

    const labels = Array.isArray(data.labels) ? data.labels : [];
    const closePrices = Array.isArray(data.closePrices) ? data.closePrices : [];
    const openPrices = Array.isArray(data.openPrices) ? data.openPrices : [];
    const highPrices = Array.isArray(data.highPrices) ? data.highPrices : [];
    const lowPrices = Array.isArray(data.lowPrices) ? data.lowPrices : [];
    const volumes = Array.isArray(data.volumes) ? data.volumes : [];
    const hasOhlc = currentChartSource !== 'exchange'
        && labels.length > 0
        && openPrices.length === labels.length
        && highPrices.length === labels.length
        && lowPrices.length === labels.length
        && openPrices.some((value, index) => {
            const open = Number(value);
            const high = Number(highPrices[index]);
            const low = Number(lowPrices[index]);
            const close = Number(closePrices[index]);
            return open !== 0 || high !== 0 || low !== 0 || close !== 0;
        });
    const showCandles = hasOhlc;
    const isIntraday = currentTab === 'time' || currentTab === 'minute';
    const timeFormat = isIntraday ? '%H:%M' : '%Y-%m-%d';

    // 라인 차트(인덱스 등) 방향 색상
    const lineColor = (() => {
        if (showCandles) return '#3b82f6';

        // DOM 읽기 제거, 전역 변수로 판단
        if (currentChangeDirection > 0) return '#ef4444';
        if (currentChangeDirection < 0) return '#3b82f6';
        return '#374151';
    })();

    const priceSeries = [];
    const volumeSeries = [];

    labels.forEach((label, index) => {
        const ts = stockDateToTs(label);
        const closeValue = Number(closePrices[index]);
        const volumeValue = Number(volumes[index]);

        if (showCandles) {
            priceSeries.push([
                ts,
                Number(openPrices[index]),
                Number(highPrices[index]),
                Number(lowPrices[index]),
                closeValue
            ]);
        } else {
            priceSeries.push([ts, closeValue]);
        }

        if (Number.isFinite(volumeValue)) {
            volumeSeries.push([ts, volumeValue]);
        }
    });

    mainChart = Highcharts.stockChart('mainChart', {
        time: { useUTC: false }, // 성공 케이스와 동일하게 설정
        chart: {
            backgroundColor: '#ffffff',
            spacing: [10, 12, 8, 12],
            animation: false,
            height: 360,
            style: { fontFamily: 'inherit' }
        },
        rangeSelector: isIntraday ? { enabled: false } : {
            selected: 1,
            inputEnabled: false,
            buttons: [
                { type: 'month', count: 1, text: '1M' },
                { type: 'month', count: 3, text: '3M' },
                { type: 'all', text: 'All' }
            ],
            buttonTheme: {
                fill: '#f9fafb', stroke: '#e5e7eb', r: 6,
                style: { color: '#374151', fontWeight: '600', fontSize: '11px' },
                states: { select: { fill: '#0E0F37', style: { color: '#ffffff' } } }
            }
        },
        credits: {
            enabled: false
        },
        legend: {
            enabled: false
        },
        exporting: {
            enabled: false
        },
        title: {
            text: ''
        },
        rangeSelector: isIntraday ? {
            enabled: false
        } : {
            selected: 1,
            inputEnabled: false,
            buttons: [
                { type: 'month', count: 1, text: '1M' },
                { type: 'month', count: 3, text: '3M' },
                { type: 'all', text: 'All' }
            ],
            buttonTheme: {
                fill: '#f9fafb',
                stroke: '#e5e7eb',
                r: 6,
                style: {
                    color: '#374151',
                    fontWeight: '600',
                    fontSize: '11px'
                },
                states: {
                    select: {
                        fill: '#0E0F37',
                        style: {
                            color: '#ffffff'
                        }
                    }
                }
            }
        },
        navigator: {
            enabled: !isIntraday,
            height: 20,
            margin: 4,
            maskFill: 'rgba(99, 102, 241, 0.16)',
            outlineColor: '#cbd5e1',
            outlineWidth: 1,
            handles: {
                backgroundColor: '#ffffff',
                borderColor: '#4f46e5',
                width: 11,
                height: 18,
                lineWidth: 1
            },
            xAxis: {
                labels: {
                    style: {
                        color: '#6b7280',
                        fontSize: '10px'
                    }
                }
            }
        },
        scrollbar: {
            enabled: false
        },
        xAxis: (() => {
            // 현재 종목/소스의 저장된 범위만 불러오기 (키 형식 통일)
            const savedMin = sessionStorage.getItem(storageKey + '_min');
            const savedMax = sessionStorage.getItem(storageKey + '_max');

            const firstDataTs = priceSeries.length > 0 ? priceSeries[0][0] : null;
            const lastDataTs = priceSeries.length > 0 ? priceSeries[priceSeries.length - 1][0] : null;
            const baseDate = firstDataTs ? new Date(firstDataTs) : new Date();

            const _base = {
                type: 'datetime',
                lineColor: '#e5e7eb',
                tickColor: '#e5e7eb',
                crosshair: { color: '#cbd5e1', dashStyle: 'ShortDot' },

                // 저장된 값이 있을 때만 적용, 없으면 undefined (기본값 사용)
                min: savedMin ? parseFloat(savedMin) : undefined,
                max: savedMax ? parseFloat(savedMax) : undefined,
                events: {
                    afterSetExtremes: function(e) {
                        sessionStorage.setItem(storageKey + '_ts', Date.now());
                        if (e.trigger !== undefined) {
                            const now = Date.now();

                            sessionStorage.setItem(storageKey + '_min', e.min);
                            sessionStorage.setItem(storageKey + '_max', e.max);
                            sessionStorage.setItem(storageKey + '_ts', now);
                        }
                    }
                }
            };

            if (currentTab === 'time' || currentTab === 'minute') {
                const _n = new Date();
                const _at9 = new Date(baseDate.getFullYear(), baseDate.getMonth(), baseDate.getDate(), 9, 0, 0, 0).getTime();
                const _at1530 = new Date(baseDate.getFullYear(), baseDate.getMonth(), baseDate.getDate(), 15, 30, 0, 0).getTime();
                const _nowTs = new Date(_n.getFullYear(), _n.getMonth(), _n.getDate(), _n.getHours(), _n.getMinutes(), 0, 0).getTime();
                const _xMax = _nowTs < _at1530 ? _nowTs : _at1530;

                return {
                    ..._base,
                    ordinal: false,
                    // 저장값이 유효하면 사용, 아니면 기본 시간 범위
                    min: (() => {
                        return firstDataTs ? firstDataTs : _at9;
                    })(),
                    max: (() => {
                        // 저장값이 없으면 데이터의 마지막 지점(lastDataTs)을 사용,
                        // 데이터도 없으면 기본값(_xMax) 사용
                        return lastDataTs ? lastDataTs : _xMax;
                    })(),
                    tickInterval: currentTab === 'time' ? 3600000 : undefined,
                    dateTimeLabelFormats: { millisecond: '%H:%M', second: '%H:%M', minute: '%H:%M', hour: '%H:%M' }
                };
            }

            return { ..._base, ordinal: true, dateTimeLabelFormats: { day: '%m/%d', week: '%m/%d', month: '%y/%m' } };
        })(),

        yAxis: [{
            height: '72%',
            top: 0,
            lineWidth: 0,
            gridLineColor: '#f3f4f6',
            tickAmount: 5,
            labels: {
                align: 'left',
                x: 0,
                style: {
                    color: '#374151',
                    fontSize: '11px'
                },
                formatter: function () {
                    return Number(this.value).toLocaleString();
                }
            },
            resize: {
                enabled: true
            },
            plotLines: []
        }, {
            top: '72%',
            height: '28%',
            offset: 0,
            lineWidth: 0,
            gridLineColor: '#f9fafb',
            labels: {
                align: 'left',
                x: 0,
                style: {
                    color: '#7a6dba',
                    fontSize: '11px'
                },
                formatter: function () {
                    return Number(this.value).toLocaleString();
                }
            }
        }],
        tooltip: {
            split: false,
            shared: true,
            formatter: function () {
                const points = this.points || [];
                let content = `<b>${Highcharts.dateFormat(timeFormat, this.x)}</b><br/>`;
                points.forEach((point) => {
                    if (point.series.type === 'candlestick') {
                        content += `시가 ${point.point.open?.toLocaleString()} · 고가 ${point.point.high?.toLocaleString()} · 저가 ${point.point.low?.toLocaleString()} · 종가 <b>${point.point.close?.toLocaleString()}</b>원<br/>`;
                    } else if (point.series.type === 'column') {
                        content += `거래량 ${point.y?.toLocaleString()}<br/>`;
                    } else {
                        content += `${point.y?.toLocaleString()}원<br/>`;
                    }
                });
                return content;
            }
        },
        plotOptions: {
            series: {
                dataGrouping: {
                    enabled: false
                },
                animation: false
            },
            candlestick: {
                animation: false,
                color: '#0051ff',
                upColor: '#f22e2e',
                lineColor: '#0051ff',
                upLineColor: '#f22e2e',
                lineWidth: 2,
                pointPadding: 0.12,
                groupPadding: 0.08,
                pointWidth: isIntraday ? 10 : undefined
            },
            column: {
                animation: false,
                borderWidth: 0,
                color: '#e5e7eb',
                pointPadding: 0.08,
                groupPadding: 0.12,
                pointWidth: isIntraday ? 8 : undefined
            }
        },
        series: [{
            type: showCandles ? 'candlestick' : 'line',
            id: 'price',
            name: getDatasetLabel(),
            data: priceSeries,
            color: showCandles ? '#0051ff' : lineColor,
            upColor: showCandles ? '#f22e2e' : undefined,
            lineColor: showCandles ? '#0051ff' : undefined,
            upLineColor: showCandles ? '#f22e2e' : undefined,
            lineWidth: showCandles ? 2 : 3,
            turboThreshold: 0,
            marker: {
                enabled: !showCandles && isIntraday,
                radius: 3
            },
            tooltip: {
                valueDecimals: 2
            }
        }, {
            type: 'column',
            id: 'volume',
            name: 'Volume',
            data: volumeSeries,
            yAxis: 1,
            turboThreshold: 0,
            tooltip: {
                valueDecimals: 0
            }
        }]
    });
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

// 메인 차트의 데이터를 다시 불러와 화면에 반영
async function updateMainChart() {
    await initMainChart();
}

// 현재 차트 데이터셋의 표시 이름을 만듦
function getDatasetLabel() {
    if (currentChartSource === 'exchange') {
        return '환율';
    }
    if (currentChartSource === 'kospi' || currentChartSource === 'kosdaq') {
        return '지수';
    }
    return currentTab === 'daily' ? '종가' : '가격';
}

// 현재 차트 상단 제목에 사용할 문구를 만듦.
function getChartTitle() {
    const tabLabel = currentTab === 'daily'
        ? '일봉'
        : currentTab === 'time'
            ? '시간 차트'
            : '분 차트';

    return `${getCurrentChartLabel()} - ${tabLabel}`;
}

// 날짜 문자열을 Highcharts 타임스탬프로 변환
function stockDateToTs(value) {
    if (!value) {
        return Date.now();
    }

    const text = String(value).trim();

    if (/^\d{8}$/.test(text)) {
        const year = Number(text.slice(0, 4));
        const month = Number(text.slice(4, 6)) - 1;
        const day = Number(text.slice(6, 8));
        return new Date(year, month, day, 0, 0, 0, 0).getTime();
    }

    if (/^\d{2}:\d{2}$/.test(text)) {
        const now = new Date();
        const [hours, minutes] = text.split(':').map(Number);
        return new Date(
            now.getFullYear(),
            now.getMonth(),
            now.getDate(),
            hours,
            minutes,
            0,
            0
        ).getTime();
    }

    const parsed = new Date(text);
    return Number.isNaN(parsed.getTime()) ? Date.now() : parsed.getTime();
}

// 현재 차트 소스를 사용자용 라벨로 변환
function getCurrentChartLabel() {
    if (currentChartSource === 'kospi') {
        return 'KOSPI';
    }
    if (currentChartSource === 'kosdaq') {
        return 'KOSDAQ';
    }
    if (currentChartSource === 'exchange') {
        return getSelectedCurrencyLabel();
    }
    return currentStockName || currentCode;
}

// 현재 차트 소스에 맞는 요약 정보 패널을 갱신
async function refreshActiveSummary() {
    if (currentChartSource === 'kospi') {
        await updateIndexInfo('kospi');
        return;
    }

    if (currentChartSource === 'kosdaq') {
        await updateIndexInfo('kosdaq');
        return;
    }

    if (currentChartSource === 'exchange') {
        try {
            const exchangeData = await getExchangeQuote(getSelectedCurrency());
            await updateExchangeInfo(exchangeData, latestChartData);
        } catch (error) {
            // console.log('exchange summary refresh error:', error);
            await updateExchangeInfo(null, latestChartData);
        }
        return;
    }

    await updateStockInfo();
}

// 선택 종목의 현재 시세와 보조 정보를 요약 패널에 반영
async function updateStockInfo() {
    try {
        const data = await fetchJson(`/api/stock/${currentCode}`);
        currentStockName = currentStockName || data.stockName || currentCode;

        const currentPrice = parseNumber(data.currentPrice);
        const openPrice = parseNumber(data.openPrice);
        const highPrice = parseNumber(data.highPrice);
        const lowPrice = parseNumber(data.lowPrice);
        const volume = parseNumber(data.volume);
        const priceChange = safeNumber(currentPrice) - safeNumber(openPrice);
        const changeRate = openPrice ? (priceChange / openPrice) * 100 : 0;
        const changeDisplay = buildChangeDisplay(priceChange, changeRate, 0, '');

        renderChartSummary({
            name: currentStockName || currentCode,
            code: currentCode,
            currentPrice: `${formatNumber(currentPrice, 0)} KRW`,
            priceChangeText: changeDisplay.text,
            priceChangeClass: changeDisplay.className,
            openLabel: '시가',
            openValue: formatNumber(openPrice, 0),
            openClass: '',
            highLabel: '고가',
            highValue: formatNumber(highPrice, 0),
            highClass: 'up',
            lowLabel: '저가',
            lowValue: formatNumber(lowPrice, 0),
            lowClass: 'down',
            volumeLabel: '거래량',
            volumeValue: formatNumber(volume, 0),
            volumeClass: ''
        });
    } catch (error) {
        // console.log('stock info error:', error);
    }
}

// 선택한 지수의 현재가와 요약 정보를 패널에 반영
async function updateIndexInfo(source, existingData = null) {
    try {
        const data = existingData || await fetchJson(source === 'kospi' ? '/api/kospi' : '/api/kosdaq');
        renderIndexSummary(source, data);
    } catch (error) {
        // console.log('index info error:', error);
    }
}

// 지수 응답값을 화면용 요약 텍스트와 색상으로 변환해 출력
function renderIndexSummary(source, data) {
    const currentPrice = parseNumber(data.currentPrice);
    const openPrice = parseNumber(data.openPrice);
    const highPrice = parseNumber(data.highPrice);
    const lowPrice = parseNumber(data.lowPrice);
    const volume = parseNumber(data.volume);
    const changeDisplay = buildChangeDisplay(parseNumber(data.priceChange), parseNumber(data.changeRate), 2, '');

    renderChartSummary({
        name: source === 'kospi' ? 'KOSPI' : 'KOSDAQ',
        code: source === 'kospi' ? '0001' : '1001',
        currentPrice: `${formatNumber(currentPrice, 2)} pt`,
        priceChangeText: changeDisplay.text,
        priceChangeClass: changeDisplay.className,
        openLabel: '시가',
        openValue: formatNumber(openPrice, 2),
        openClass: '',
        highLabel: '고가',
        highValue: formatNumber(highPrice, 2),
        highClass: 'up',
        lowLabel: '저가',
        lowValue: formatNumber(lowPrice, 2),
        lowClass: 'down',
        volumeLabel: '거래량',
        volumeValue: formatNumber(volume, 0),
        volumeClass: ''
    });
}

// 선택한 통화의 환율 정보와 메타 데이터를 패널에 반영
async function updateExchangeInfo(existingData = null, existingChartData = null) {
    try {
        const currency = getSelectedCurrency();
        const data = existingData || await getExchangeQuote(currency);
        const chartData = existingChartData || (currentChartSource === 'exchange' ? latestChartData : null);
        renderExchangeSummary(data, chartData);
    } catch (error) {
        // console.log('exchange info error:', error);
    }
}

// 환율 응답과 차트 기준으로 요약 영역의 문구와 수치를 만듦
function renderExchangeSummary(data, chartData = latestChartData) {
    const normalizedChartData = normalizeChartData(chartData);
    const metrics = resolveChangeMetrics(data, normalizedChartData);
    const recentHigh = getMaxValue(normalizedChartData.closePrices);
    const recentLow = getMinValue(normalizedChartData.closePrices);
    const latestLabel = normalizedChartData.labels.length > 0
        ? normalizedChartData.labels[normalizedChartData.labels.length - 1]
        : '-';
    const previousPrice = Number.isNaN(metrics.current) || Number.isNaN(metrics.change)
        ? NaN
        : metrics.current - metrics.change;
    const changeDisplay = buildChangeDisplay(metrics.change, metrics.rate, 2, '원');

    renderChartSummary({
        name: getSelectedCurrencyLabel(),
        code: normalizeCurrency(getSelectedCurrency()),
        currentPrice: `${formatNumber(metrics.current, 2)} 원`,
        priceChangeText: changeDisplay.text,
        priceChangeClass: changeDisplay.className,
        openLabel: '전일',
        openValue: formatNumber(previousPrice, 2),
        openClass: '',
        highLabel: '최근 고가',
        highValue: formatNumber(recentHigh, 2),
        highClass: '',
        lowLabel: '최근 저가',
        lowValue: formatNumber(recentLow, 2),
        lowClass: '',
        volumeLabel: '기준일',
        volumeValue: latestLabel,
        volumeClass: ''
    });
}

// 공통 요약 DTO를 상단 메타 필드 UI에 출력
function renderChartSummary(summary) {
    setText('chartStockName', summary.name);
    setText('chartStockCode', summary.code);
    setText('chartCurrentPrice', summary.currentPrice);

    const changeEl = document.getElementById('chartPriceChange');
    if (changeEl) {
        changeEl.textContent = summary.priceChangeText;
        changeEl.className = summary.priceChangeClass || '';
    }

    if (summary.priceChangeClass === 'up') currentChangeDirection = 1;
    else if (summary.priceChangeClass === 'down') currentChangeDirection = -1;
    else currentChangeDirection = 0;

    setMetaField('chartOpenLabel', 'chartOpenPrice', summary.openLabel, summary.openValue, summary.openClass);
    setMetaField('chartHighLabel', 'chartHighPrice', summary.highLabel, summary.highValue, summary.highClass);
    setMetaField('chartLowLabel', 'chartLowPrice', summary.lowLabel, summary.lowValue, summary.lowClass);
    setMetaField('chartVolumeLabel', 'chartVolume', summary.volumeLabel, summary.volumeValue, summary.volumeClass);
}

// 라벨/값 한 쌍으로 구성된 메타 필드를 공통 방식으로 갱신
function setMetaField(labelId, valueId, labelText, valueText, valueClass = '') {
    setText(labelId, labelText);
    const valueEl = document.getElementById(valueId);
    if (!valueEl) {
        return;
    }

    valueEl.textContent = valueText;
    valueEl.className = valueClass || '';
}

// 상단 지수/환율 티커 숫자를 최신 값으로 갱신
async function updateTicker() {
    try {
        const [kospi, kosdaq] = await Promise.all([
            fetchJson('/api/kospi'),
            fetchJson('/api/kosdaq')
        ]);

        renderIndexTicker('kospiPrice', 'kospiRate', kospi);
        renderIndexTicker('kosdaqPrice', 'kosdaqRate', kosdaq);
        return { kospi, kosdaq };
    } catch (error) {
        // console.log('ticker error:', error);
        return {};
    }
}

// 지수 티커 카드의 숫자와 등락 색상을 렌더링
function renderIndexTicker(priceId, rateId, data) {
    const priceEl = document.getElementById(priceId);
    const rateEl = document.getElementById(rateId);
    const rateInfo = buildRateOnly(parseNumber(data.changeRate));

    if (priceEl) {
        priceEl.textContent = formatNumber(parseNumber(data.currentPrice), 2);
    }

    if (rateEl) {
        rateEl.textContent = rateInfo.text;
        rateEl.className = rateInfo.className;
    }
}

// 환율 카드의 현재가, 등락률, 요약 차트 정보를 함께 갱신
async function updateExchangeCard(existingData = null, existingChartData = null, currency = getSelectedCurrency()) {
    try {
        const data = existingData || await getExchangeQuote(currency);
        const metrics = await resolveExchangeMetrics(data, existingChartData, currency);
        const rateInfo = buildRateOnly(metrics.rate, metrics.direction);

        setText('exchangePrice', formatNumber(metrics.current, 2));

        const rateEl = document.getElementById('exchangeRate');
        if (rateEl) {
            rateEl.textContent = rateInfo.text;
            rateEl.className = rateInfo.className;
        }

        return data;
    } catch (error) {
        // console.log('exchange ticker error:', error);
        return null;
    }
}

// 하단 전광판에 표시할 상위 종목 목록을 다시 불러옴
async function updateTopStocks() {
    try {
        const stocks = await fetchJson('/api/stock/top-fluctuation');
        if (!Array.isArray(stocks) || stocks.length === 0) {
            return;
        }

        const tickerHtml = stocks.map((stock) => {
            const rate = parseNumber(stock.changeRate);
            const rateInfo = buildRateOnly(rate);
            const currentPrice = formatNumber(parseNumber(stock.currentPrice), 0);
            const stockCode = String(stock.stockCode || '').trim();
            const stockName = String(stock.stockName || '').trim();

            return `
                <a href="#" class="t-item topStockItem" data-code="${stockCode}" data-name="${stockName}">
                    <span>${stockName}</span>
                    <strong class="${rateInfo.className}">${currentPrice} ${rateInfo.text}</strong>
                </a>
            `;
        }).join('');

        setHtml('tickerContent1', tickerHtml);
        setHtml('tickerContent2', tickerHtml);
    } catch (error) {
        // console.log('top fluctuation error:', error);
    }
}

// AI 브리핑 로테이터 데이터를 준비하고 자동 순환을 시작
function initAiRotator() {
    bindAiRotatorControls();
    rebuildAiRotatorItems(true);
    startAiRotator();
}

// AI 브리핑 자동 순환 타이머를 중지
function stopAiRotator() {
    if (aiRotatorTimer) {
        clearInterval(aiRotatorTimer);
        aiRotatorTimer = null;
    }
}

// AI 브리핑 카드의 좌우 이동 버튼을 연결
function bindAiRotatorControls() {
    const panel = document.getElementById('aiRotatorPanel');
    const prevButton = document.getElementById('aiRotatorPrev');
    const nextButton = document.getElementById('aiRotatorNext');
    if (!panel || !prevButton || !nextButton || panel.dataset.rotatorBound === 'true') {
        return;
    }

    panel.dataset.rotatorBound = 'true';
    prevButton.addEventListener('click', () => moveAiRotator(-1));
    nextButton.addEventListener('click', () => moveAiRotator(1));
}

// 좌우 화살표 클릭으로 원하는 브리핑 카드로 즉시 이동
function moveAiRotator(step) {
    if (aiRotatorItems.length <= 1) {
        updateAiRotatorControls();
        return;
    }

    aiRotatorIndex = (aiRotatorIndex + step + aiRotatorItems.length) % aiRotatorItems.length;
    renderAiRotatorItem(aiRotatorItems[aiRotatorIndex]);
    startAiRotator();
}

// 현재 브리핑 순번과 버튼 활성 상태를 갱신
function updateAiRotatorControls() {
    const prevButton = document.getElementById('aiRotatorPrev');
    const nextButton = document.getElementById('aiRotatorNext');
    const pageLabel = document.getElementById('aiRotatorPage');
    const total = aiRotatorItems.length;
    const current = total > 0 ? aiRotatorIndex + 1 : 0;
    const disabled = total <= 1;

    if (pageLabel) {
        pageLabel.textContent = total > 0 ? `${current} / ${total}` : '- / -';
    }

    [prevButton, nextButton].forEach((button) => {
        if (!button) {
            return;
        }
        button.disabled = disabled;
        button.setAttribute('aria-disabled', String(disabled));
    });
}

// 종목을 새로 검색했을 때는 기존 섹터 로테이션을 잠깐 멈추고, 해당 종목 확률 카드를 먼저 보여준다.
// 새 종목 선택 직후 예측 대기 상태 카드를 먼저 보여준다.
function showPendingStockAiCard(stockCode, stockName) {
    if (!stockCode) {
        return;
    }

    const seed = window.AI_ROTATOR_DATA || {};
    seed.stock = createPendingStockAiSeed(stockCode, stockName || stockCode);
    window.AI_ROTATOR_DATA = seed;
    stopAiRotator();
    rebuildAiRotatorItems(true);
}

// 종목 예측과 섹터 카드를 합쳐 로테이터 아이템 목록을 다시 만듦
function rebuildAiRotatorItems(resetIndex = false) {
    const seed = window.AI_ROTATOR_DATA || {};
    const sectorItems = Array.isArray(seed.sectors)
        ? seed.sectors.map(buildSectorAiRotatorItem).filter(Boolean)
        : [];
    const items = [];
    const stockItem = buildStockAiRotatorItem(seed.stock || {}, sectorItems.length === 0);
    if (stockItem) {
        items.push(stockItem);
    }
    items.push(...sectorItems);
    aiRotatorItems = items;

    if (resetIndex || aiRotatorIndex >= aiRotatorItems.length) {
        aiRotatorIndex = 0;
    }

    renderAiRotatorItem(aiRotatorItems[aiRotatorIndex] || null);
}

// AI 브리핑 카드를 일정 주기로 순환 표시
function startAiRotator() {
    stopAiRotator();

    if (aiRotatorItems.length <= 1) {
        return;
    }

    aiRotatorTimer = setInterval(() => {
        if (aiRotatorItems.length <= 1) {
            return;
        }

        aiRotatorIndex = (aiRotatorIndex + 1) % aiRotatorItems.length;
        renderAiRotatorItem(aiRotatorItems[aiRotatorIndex]);
    }, 10000);
}

// 현재 선택 종목의 AI 예측 결과를 새로 받아 로테이터를 갱신
async function refreshAiPredictionForCurrentCode() {
    if (!currentCode) {
        return;
    }

    const requestedCode = currentCode;
    const requestedName = currentStockName || requestedCode;
    const refreshToken = ++aiRefreshToken;
    showPendingStockAiCard(requestedCode, requestedName);
    const seed = window.AI_ROTATOR_DATA || {};

    try {
        const stockAi = await fetchJson(`/api/stock/${encodeURIComponent(requestedCode)}/ai?ts=${Date.now()}`);
        if (refreshToken !== aiRefreshToken || requestedCode !== currentCode) {
            return;
        }

        seed.stock = normalizeAiPredictionResponse(stockAi, requestedCode, requestedName);
        window.AI_ROTATOR_DATA = seed;
        rebuildAiRotatorItems(true);
        startAiRotator();
    } catch (error) {
        // console.log('ai prediction refresh error:', error);
        if (refreshToken !== aiRefreshToken || requestedCode !== currentCode) {
            return;
        }
        seed.stock = {
            ...createPendingStockAiSeed(requestedCode, requestedName),
            message: 'AI 예측을 다시 불러오지 못했습니다.',
            loading: false
        };
        window.AI_ROTATOR_DATA = seed;
        rebuildAiRotatorItems(true);
        startAiRotator();
    }
}

// 종목 AI 응답을 로테이터 카드에 맞는 표시 데이터로 변환
function buildStockAiRotatorItem(stock, allowFallbackCard = false) {
    const stockCode = stock?.stockCode || currentCode;
    const stockName = stock?.stockName || currentStockName || stockCode;
    if (!stockCode && !stockName) {
        return null;
    }

    const sentimentMean = safeNumber(parseNumber(stock?.sentimentMean));
    const clickbaitMean = safeNumber(parseNumber(stock?.clickbaitMean));
    const volatility = safeNumber(parseNumber(stock?.volatility20d)) * 100;
    const articleCount = safeNumber(parseNumber(stock?.articleCount));
    const cvAuc = safeNumber(parseNumber(stock?.cvAuc));
    const nTrain = safeNumber(parseNumber(stock?.nTrain));
    const factRatio = safeNumber(parseNumber(stock?.factRatio)) * 100;

    if (stock?.loading) {
        return {
            title: 'AI 시장 흐름 브리핑',
            badge: '현재 종목',
            badgeClass: 'badge-neutral',
            subject: `${stockName} · ${stockCode}`,
            arrowClass: 'arrow-neutral',
            arrowText: '•',
            headline: '예측 데이터 불러오는 중',
            subline: '최근 뉴스와 가격 흐름을 다시 계산하고 있습니다.',
            factors: [
                createAiFactor('최근 뉴스', 0, 'fill-neutral', '계산중', 'neutral'),
                createAiFactor('모델 상태', 0, 'fill-neutral', '로딩중', 'neutral'),
                createAiFactor('기사 수', 0, 'fill-neutral', '-', 'neutral')
            ],
            meta: '잠시 후 최신 종목 예측으로 갱신됩니다.'
        };
    }

    if (!stock?.valid) {
        if (!allowFallbackCard) {
            return null;
        }

        return {
            title: 'AI 시장 흐름 브리핑',
            badge: '현재 종목',
            badgeClass: 'badge-neutral',
            subject: `${stockName} · ${stockCode}`,
            arrowClass: 'arrow-neutral',
            arrowText: '•',
            headline: '예측 데이터 부족',
            subline: stock?.message || '최근 뉴스가 부족해 참고 카드만 표시합니다.',
            factors: [
                createAiFactor('최근 뉴스', 0, 'fill-neutral', '대기', 'neutral'),
                createAiFactor('모델 상태', 0, 'fill-neutral', '준비중', 'neutral'),
                createAiFactor('데이터', 0, 'fill-neutral', '-', 'neutral')
            ],
            meta: '참고용 · 종목 뉴스가 쌓이면 자동으로 예측이 반영됩니다.'
        };
    }

    return {
        title: 'AI 시장 흐름 브리핑',
        badge: '현재 종목',
        badgeClass: 'badge-info',
        subject: `${stockName} · ${stockCode}`,
        arrowClass: stock?.up ? 'arrow-up' : 'arrow-down',
        arrowText: stock?.up ? '↑' : '↓',
        headline: `${stock?.prediction || '예측'} 예측`,
        subline: `익일 상승 확률 ${safeNumber(parseNumber(stock?.probabilityPercent)).toFixed(1)}% · 확신도 ${stock?.confidence || '참고용'}`,
        factors: [
            createAiFactor(
                `낚시성 ${describeClickbait(clickbaitMean)}`,
                clamp(100 - clickbaitMean, 0, 100),
                clickbaitMean <= 35 ? 'fill-pos' : 'fill-neg',
                clickbaitMean <= 35 ? '+강세' : '-주의',
                clickbaitMean <= 35 ? 'positive' : 'negative'
            ),
            createAiFactor(
                '변동성',
                clamp(volatility * 30, 0, 100),
                volatility <= 2.5 ? 'fill-pos' : 'fill-neg',
                volatility <= 2.5 ? '+안정' : '-주의',
                volatility <= 2.5 ? 'positive' : 'negative'
            ),
            createAiFactor(
                `기사 수 (${Math.round(articleCount)}건)`,
                clamp(articleCount / 3, 0, 100),
                articleCount >= 30 ? 'fill-pos' : 'fill-neutral',
                articleCount >= 30 ? '+강세' : '중립',
                articleCount >= 30 ? 'positive' : 'neutral'
            )
        ],
        meta: `감성 ${formatSignedDecimal(sentimentMean, 2)} · 사실형 ${formatNumber(factRatio, 0)}% · CV AUC ${formatNumber(cvAuc, 3)} · 데이터 ${formatNumber(nTrain, 0)}건 기반`
    };
}

// 예측 응답이 오기 전 임시 로딩 카드 데이터를 생성
function createPendingStockAiSeed(code, name) {
    return {
        valid: false,
        stockCode: code,
        stockName: name,
        prediction: '',
        up: false,
        probabilityPercent: 0,
        confidence: '',
        articleCount: 0,
        sentimentMean: 0,
        clickbaitMean: 0,
        typeProbMean: 0,
        factRatio: 0,
        volatility20d: 0,
        cvAuc: 0,
        nTrain: 0,
        message: '',
        loading: true
    };
}

// 서버 응답의 필드명을 화면에서 쓰기 쉬운 형태로 정규화
function normalizeAiPredictionResponse(stockAi, fallbackCode, fallbackName) {
    const recentArticles = Array.isArray(stockAi?.recentArticles)
        ? stockAi.recentArticles
        : (Array.isArray(stockAi?.recent_articles) ? stockAi.recent_articles : []);
    const probability = firstDefined(stockAi?.probabilityPercent, stockAi?.probability_percent);
    const rawProbability = firstDefined(probability, stockAi?.probability);
    const prediction = stockAi?.prediction || '';
    const predictionInt = firstDefined(stockAi?.predictionInt, stockAi?.prediction_int);
    const probabilityPercent = probability != null
        ? safeNumber(parseNumber(probability))
        : safeNumber(parseNumber(rawProbability)) * (rawProbability != null && safeNumber(parseNumber(rawProbability)) <= 1 ? 100 : 1);
    const stockCode = stockAi?.stockCode || stockAi?.stock_code || fallbackCode;
    const stockName = stockAi?.stockName || stockAi?.stock_name || fallbackName || stockCode;

    return {
        valid: firstDefined(stockAi?.valid, !!prediction),
        stockCode,
        stockName,
        prediction,
        up: firstDefined(stockAi?.up, prediction === '상승' || predictionInt === 1, false),
        probabilityPercent,
        confidence: stockAi?.confidence || '',
        articleCount: firstDefined(stockAi?.articleCount, stockAi?.article_count, recentArticles.length, 0),
        sentimentMean: firstDefined(stockAi?.sentimentMean, stockAi?.sentiment_mean, 0),
        clickbaitMean: firstDefined(stockAi?.clickbaitMean, stockAi?.clickbait_mean, 0),
        typeProbMean: firstDefined(stockAi?.typeProbMean, stockAi?.type_prob_mean, 0),
        factRatio: firstDefined(stockAi?.factRatio, stockAi?.fact_ratio, 0),
        volatility20d: firstDefined(stockAi?.volatility20d, stockAi?.volatility_20d, 0),
        cvAuc: firstDefined(stockAi?.cvAuc, stockAi?.cv_auc, stockAi?.model_meta?.cv_auc, 0),
        nTrain: firstDefined(stockAi?.nTrain, stockAi?.ntrain, stockAi?.n_train, stockAi?.model_meta?.n_train, 0),
        message: stockAi?.message || stockAi?.error || ''
    };
}

// 여러 후보 값 중 가장 먼저 정의된 값을 반환
function firstDefined(...values) {
    for (const value of values) {
        if (value !== undefined && value !== null) {
            return value;
        }
    }
    return undefined;
}

// 섹터 카드 데이터를 AI 브리핑 로테이터 아이템 형태로 변환
function buildSectorAiRotatorItem(card) {
    const articleCount = safeNumber(parseNumber(card?.articleCount));
    const trendScore = parseNumber(card?.trendScore);
    const posRatio = parseNumber(card?.posRatio);
    const avgTypeProb = parseNumber(card?.avgTypeProb);
    const avgClickbaitProb = parseNumber(card?.avgClickbaitProb);
    const positiveCount = safeNumber(parseNumber(card?.positiveCount));
    const negativeCount = safeNumber(parseNumber(card?.negativeCount));
    const neutralCount = safeNumber(parseNumber(card?.neutralCount));
    const sectorName = `${card?.sectorName || card?.sectorKey || ''}`.trim();
    const trendLabel = `${card?.trendLabel || ''}`.trim();

    if (articleCount <= 0 || !sectorName || !trendLabel) {
        return null;
    }

    if ([trendScore, posRatio, avgTypeProb, avgClickbaitProb].some((value) => Number.isNaN(value))) {
        return null;
    }

    if ([sectorName, trendLabel].some((value) => /예측\s*데이터\s*부족|데이터\s*부족|불러오지\s*못/i.test(value))) {
        return null;
    }

    const direction = card?.trendDirection || 'neutral';

    return {
        title: 'AI 시장 흐름 브리핑',
        badge: '섹터 흐름',
        badgeClass: 'badge-subtle',
        subject: `${sectorName} · 뉴스 ${Math.round(articleCount)}건`,
        arrowClass: direction === 'up' ? 'arrow-up' : direction === 'down' ? 'arrow-down' : 'arrow-neutral',
        arrowText: direction === 'up' ? '↑' : direction === 'down' ? '↓' : '•',
        headline: trendLabel,
        subline: `상승 점수 ${formatNumber(safeNumber(trendScore), 0)}점 · 신뢰도 ${formatNumber(safeNumber(avgTypeProb), 0)}%`,
        factors: [
            createAiFactor(
                '호재 비중',
                clamp(safeNumber(posRatio), 0, 100),
                safeNumber(posRatio) >= 50 ? 'fill-pos' : 'fill-neg',
                `${formatNumber(safeNumber(posRatio), 0)}%`,
                safeNumber(posRatio) >= 50 ? 'positive' : 'negative'
            ),
            createAiFactor(
                'AI 신뢰도',
                clamp(safeNumber(avgTypeProb), 0, 100),
                safeNumber(avgTypeProb) >= 60 ? 'fill-pos' : 'fill-neutral',
                `${formatNumber(safeNumber(avgTypeProb), 0)}%`,
                safeNumber(avgTypeProb) >= 60 ? 'positive' : 'neutral'
            ),
            createAiFactor(
                '노이즈 방어',
                clamp(100 - safeNumber(avgClickbaitProb), 0, 100),
                safeNumber(avgClickbaitProb) <= 30 ? 'fill-pos' : 'fill-neg',
                safeNumber(avgClickbaitProb) <= 30 ? '+안정' : '-주의',
                safeNumber(avgClickbaitProb) <= 30 ? 'positive' : 'negative'
            )
        ],
        meta: `호재 ${formatNumber(positiveCount, 0)}건 · 악재 ${formatNumber(negativeCount, 0)}건 · 중립 ${formatNumber(neutralCount, 0)}건 · 노이즈 ${card?.avgClickbaitProb || '0.0'}%`
    };
}

// 현재 로테이터 아이템을 AI 브리핑 패널 UI에 그림
function renderAiRotatorItem(item) {
    const panel = document.getElementById('aiRotatorPanel');
    if (!panel) {
        return;
    }

    updateAiRotatorControls();

    if (!item) {
        setText('aiRotatorTitle', 'AI 시장 흐름 브리핑');
        setText('aiRotatorBadge', 'INFO');
        setElementClass('aiRotatorBadge', 'aiPredictBadge badge-neutral');
        setText('aiRotatorSubject', currentCode || '-');
        setElementClass('aiRotatorSubject', 'aiPredictSubject');
        setText('aiRotatorHeadline', '표시할 데이터 없음');
        setText('aiRotatorSubline', 'AI 예측 데이터가 준비되면 자동으로 반영됩니다.');
        setElementClass('aiRotatorArrow', 'aiPredictArrow arrow-neutral');
        setText('aiRotatorArrow', '•');
        setText('aiRotatorMeta', '참고용');
        setAiFactor(1, createAiFactor('뉴스 데이터', 0, 'fill-neutral', '-', 'neutral'));
        setAiFactor(2, createAiFactor('모델 상태', 0, 'fill-neutral', '-', 'neutral'));
        setAiFactor(3, createAiFactor('신뢰도', 0, 'fill-neutral', '-', 'neutral'));
        return;
    }

    setText('aiRotatorTitle', item.title);
    setText('aiRotatorBadge', item.badge);
    setElementClass('aiRotatorBadge', `aiPredictBadge ${item.badgeClass || 'badge-info'}`);
    setText('aiRotatorSubject', item.subject);
    setElementClass('aiRotatorSubject', item.subject ? 'aiPredictSubject' : 'aiPredictSubject is-hidden');
    setElementClass('aiRotatorArrow', `aiPredictArrow ${item.arrowClass || 'arrow-neutral'}`);
    setText('aiRotatorArrow', item.arrowText || '•');
    setText('aiRotatorHeadline', item.headline);
    setText('aiRotatorSubline', item.subline);
    setAiFactor(1, item.factors?.[0] || createAiFactor('요인 1', 0, 'fill-neutral', '-', 'neutral'));
    setAiFactor(2, item.factors?.[1] || createAiFactor('요인 2', 0, 'fill-neutral', '-', 'neutral'));
    setAiFactor(3, item.factors?.[2] || createAiFactor('요인 3', 0, 'fill-neutral', '-', 'neutral'));
    setText('aiRotatorMeta', item.meta || '참고용');
}

// AI 브리핑 카드의 개별 요인 바와 텍스트를 갱신
function setAiFactor(index, factor) {
    setText(`aiFactor${index}Label`, factor.label);
    setElementClass(`aiFactor${index}Fill`, `factorFill ${factor.fillClass || 'fill-neutral'}`);
    setStyleWidth(`aiFactor${index}Fill`, `${clamp(factor.width, 0, 100)}%`);

    const valueEl = document.getElementById(`aiFactor${index}Value`);
    if (!valueEl) {
        return;
    }

    valueEl.textContent = factor.value;
    valueEl.className = `factorVal ${factor.valueClass || 'neutral'}`;
}

// AI 브리핑 카드에서 공통으로 쓰는 요인 객체를 만듦
function createAiFactor(label, width, fillClass, value, valueClass) {
    return { label, width, fillClass, value, valueClass };
}

// 낚시성 확률을 사용자용 설명 문구로 변환
function describeClickbait(clickbaitValue) {
    if (clickbaitValue <= 20) {
        return '낮음';
    }
    if (clickbaitValue <= 40) {
        return '보통';
    }
    return '주의';
}

// 메인 화면 게이지 바를 데이터 값에 맞춰 애니메이션
function animateGauges() {
    const data = window.STOCK_DATA || { typeProb: 0, noiseProb: 0, sentimentScore: 50 };

    const confidenceBar = document.getElementById('confBar');
    const noiseBar = document.getElementById('noiseBar');
    const sentimentBar = document.getElementById('sentBar');

    if (confidenceBar) {
        const value = parseFloat(confidenceBar.getAttribute('data-value') || data.typeProb) || 0;
        setTimeout(() => {
            confidenceBar.style.width = `${Math.min(value, 100)}%`;
        }, 100);
    }

    if (noiseBar) {
        const value = parseFloat(noiseBar.getAttribute('data-value') || data.noiseProb) || 0;
        setTimeout(() => {
            noiseBar.style.width = `${Math.min(value, 100)}%`;
        }, 200);
    }

    if (sentimentBar) {
        const value = parseFloat(sentimentBar.getAttribute('data-value') || data.sentimentScore) || 50;
        if (value >= 60) {
            sentimentBar.style.background = 'linear-gradient(90deg,#34d399,#10b981)';
        } else if (value <= 40) {
            sentimentBar.style.background = 'linear-gradient(90deg,#f87171,#ef4444)';
        } else {
            sentimentBar.style.background = 'linear-gradient(90deg,#fbbf24,#f59e0b)';
        }

        setTimeout(() => {
            sentimentBar.style.width = `${Math.min(value, 100)}%`;
        }, 300);
    }
}

// 메인 화면의 AI 뉴스 종합 분석 수치와 설명을 갱신
function updateAIAnalysis(data) {
    setStyleWidth('confBar', `${data.confidence}%`);
    setText('confVal', `${data.confidence}%`);
    setStyleWidth('noiseBar', `${data.noise}%`);
    setText('noiseVal', `${data.noise}%`);
    setStyleWidth('sentBar', `${data.sentiment}%`);
    setText('aiStatus', data.status);
    setText('aiDesc', data.desc);
}

// 현재 시간이 국내 주식 정규장 시간인지 판단
function isMarketOpen() {
    const now = new Date();
    const day = now.getDay();
    if (day === 0 || day === 6) {
        return false;
    }

    const time = now.getHours() * 100 + now.getMinutes();
    return time >= 900 && time <= 1530;
}

// 환율 선택기에서 현재 선택된 통화 코드를 가져옴
function getSelectedCurrency() {
    return document.getElementById('currencySelect')?.value || 'USD';
}

// 환율 선택기에서 현재 선택된 통화 표시명을 가져옴
function getSelectedCurrencyLabel() {
    const select = document.getElementById('currencySelect');
    if (!select) {
        return 'USD/KRW';
    }

    const option = select.options[select.selectedIndex];
    return option ? option.text : 'USD/KRW';
}

// 화면에서 사용하는 통화 표기를 내부 표준 통화 코드로 맞춤
function normalizeCurrency(currency) {
    return String(currency || 'USD').toUpperCase().split('(')[0].trim();
}

// 환율 응답과 차트로부터 현재가/등락/등락률 메트릭을 계산
async function resolveExchangeMetrics(data, fallbackChartData = null, currency = getSelectedCurrency()) {
    let metrics = resolveChangeMetrics(
        data,
        fallbackChartData || (currentChartSource === 'exchange' ? latestChartData : null)
    );
    if (hasResolvedMetrics(metrics)) {
        return metrics;
    }

    try {
        const chartData = await getExchangeChart(currency);
        metrics = resolveChangeMetrics(data, chartData);
    } catch (error) {
        // console.log('exchange metrics fallback error:', error);
    }

    return metrics;
}

// 일반 시세 응답에서 등락 금액과 등락률을 우선 계산
function resolveChangeMetrics(data, fallbackChartData = null) {
    const current = parseNumber(data?.currentPrice);
    let change = parseNumber(data?.priceChange);
    let rate = parseNumber(data?.changeRate);
    const fallbackMetrics = deriveMetricsFromChart(current, fallbackChartData);
    const resolvedCurrent = Number.isNaN(current) ? fallbackMetrics.current : current;

    if (Number.isNaN(change)) {
        change = fallbackMetrics.change;
    }
    if (Number.isNaN(rate)) {
        rate = fallbackMetrics.rate;
    }

    if (Number.isNaN(rate) && !Number.isNaN(change) && !Number.isNaN(resolvedCurrent)) {
        const previousPrice = resolvedCurrent - change;
        if (previousPrice !== 0) {
            rate = (change / previousPrice) * 100;
        }
    }

    if (Number.isNaN(change) && !Number.isNaN(rate) && !Number.isNaN(resolvedCurrent)) {
        const denominator = 1 + (rate / 100);
        if (denominator !== 0) {
            const previousPrice = resolvedCurrent / denominator;
            if (Number.isFinite(previousPrice)) {
                change = resolvedCurrent - previousPrice;
            }
        }
    }

    const direction = !Number.isNaN(change) && change !== 0
        ? change
        : (!Number.isNaN(rate) ? rate : 0);

    return { current: resolvedCurrent, change, rate, direction };
}

// 직접 등락 정보가 없을 때 차트 데이터로 전일 대비 메트릭을 추정
function deriveMetricsFromChart(current, fallbackChartData) {
    const priceSource = Array.isArray(fallbackChartData?.closePrices)
        ? fallbackChartData.closePrices
        : [];
    const prices = priceSource.map(parseNumber).filter((value) => !Number.isNaN(value));
    if (prices.length === 0) {
        return { current: Number.NaN, change: Number.NaN, rate: Number.NaN };
    }

    const resolvedCurrent = Number.isNaN(current) ? prices[prices.length - 1] : current;
    if (prices.length < 2) {
        return { current: resolvedCurrent, change: Number.NaN, rate: Number.NaN };
    }

    const previousPrice = prices[prices.length - 2];
    if (previousPrice === 0) {
        return { current: resolvedCurrent, change: Number.NaN, rate: Number.NaN };
    }

    const change = resolvedCurrent - previousPrice;
    const rate = (change / previousPrice) * 100;
    return { current: resolvedCurrent, change, rate };
}

// 등락 메트릭이 충분히 계산됐는지 확인
function hasResolvedMetrics(metrics) {
    return !Number.isNaN(metrics?.change) || !Number.isNaN(metrics?.rate);
}

// 금액 변화와 변화율을 한 줄 텍스트로 조합
function buildChangeDisplay(change, rate, digits, amountSuffix) {
    if (Number.isNaN(change) && Number.isNaN(rate)) {
        return { text: '-', className: '' };
    }

    const direction = !Number.isNaN(change) && change !== 0
        ? change
        : (!Number.isNaN(rate) ? rate : 0);
    const prefix = direction > 0 ? '+' : direction < 0 ? '-' : '';
    const className = direction > 0 ? 'up' : direction < 0 ? 'down' : '';

    const amountValue = Number.isNaN(change) ? 0 : Math.abs(change);
    const rateValue = Number.isNaN(rate) ? 0 : Math.abs(rate);
    const amountText = `${prefix}${formatNumber(amountValue, digits)}${amountSuffix}`;

    return {
        text: `${amountText} (${prefix}${rateValue.toFixed(2)}%)`,
        className
    };
}

// 변화율만으로 방향 화살표와 표시 문자열을 만듦
function buildRateOnly(rate, directionOverride = null) {
    if (Number.isNaN(rate)) {
        if (directionOverride == null || directionOverride === 0) {
            return { text: '-', className: '' };
        }
        return {
            text: '0.00%',
            className: directionOverride > 0 ? 'up' : 'down'
        };
    }

    const direction = directionOverride == null || directionOverride === 0 ? rate : directionOverride;
    const prefix = direction > 0 ? '+' : direction < 0 ? '-' : '';
    const className = direction > 0 ? 'up' : direction < 0 ? 'down' : '';

    return {
        text: `${prefix}${Math.abs(rate).toFixed(2)}%`,
        className
    };
}

// 값이 없는 차트 요청에 대비한 기본 데이터 구조를 만듦
function emptyChartData() {
    return {
        labels: [],
        closePrices: [],
        openPrices: [],
        highPrices: [],
        lowPrices: [],
        volumes: []
    };
}

// 서버 응답 차트 데이터를 필수 배열이 있는 형태로 정규화
function normalizeChartData(data) {
    return {
        labels: Array.isArray(data?.labels) ? data.labels : [],
        closePrices: Array.isArray(data?.closePrices) ? data.closePrices : [],
        openPrices: Array.isArray(data?.openPrices) ? data.openPrices : [],
        highPrices: Array.isArray(data?.highPrices) ? data.highPrices : [],
        lowPrices: Array.isArray(data?.lowPrices) ? data.lowPrices : [],
        volumes: Array.isArray(data?.volumes) ? data.volumes : []
    };
}

// 통화별 환율 캐시 키를 만듦
function getExchangeCacheKey(currency) {
    return normalizeCurrency(currency || getSelectedCurrency());
}

// TTL이 남아 있는 환율 캐시가 있으면 반환
function readExchangeCache(cache, key, ttlMs) {
    const entry = cache.get(key);
    if (!entry) {
        return null;
    }

    if (Date.now() - entry.timestamp > ttlMs) {
        cache.delete(key);
        return null;
    }

    return entry.data;
}

// 환율 조회 결과를 현재 시각과 함께 캐시에 저장
function writeExchangeCache(cache, key, data) {
    cache.set(key, {
        data,
        timestamp: Date.now()
    });
    return data;
}

// 환율 현재가를 캐시 우선 전략으로 조회
async function getExchangeQuote(currency = getSelectedCurrency(), forceRefresh = false) {
    const cacheKey = getExchangeCacheKey(currency);
    if (!forceRefresh) {
        const cached = readExchangeCache(exchangeQuoteCache, cacheKey, EXCHANGE_QUOTE_TTL_MS);
        if (cached) {
            return cached;
        }
    }

    const inFlight = exchangeQuoteRequests.get(cacheKey);
    if (inFlight) {
        return inFlight;
    }

    const request = fetchJson(`/api/exchange?currency=${encodeURIComponent(currency)}`)
        .then((data) => writeExchangeCache(exchangeQuoteCache, cacheKey, data))
        .finally(() => exchangeQuoteRequests.delete(cacheKey));

    exchangeQuoteRequests.set(cacheKey, request);
    return request;
}

// 환율 차트 데이터를 캐시 우선 전략으로 조회
async function getExchangeChart(currency = getSelectedCurrency(), forceRefresh = false) {
    const cacheKey = getExchangeCacheKey(currency);
    if (!forceRefresh) {
        const cached = readExchangeCache(exchangeChartCache, cacheKey, EXCHANGE_CHART_TTL_MS);
        if (cached) {
            return cached;
        }
    }

    const inFlight = exchangeChartRequests.get(cacheKey);
    if (inFlight) {
        return inFlight;
    }

    const request = fetchJson(`/api/exchange/chart?currency=${encodeURIComponent(currency)}`)
        .then((data) => writeExchangeCache(exchangeChartCache, cacheKey, normalizeChartData(data)))
        .finally(() => exchangeChartRequests.delete(cacheKey));

    exchangeChartRequests.set(cacheKey, request);
    return request;
}

// 공통 fetch 래퍼로 JSON 응답을 받아 에러를 표준화
async function fetchJson(url) {
    const response = await fetch(url);
    if (!response.ok) {
        throw new Error(`Request failed: ${response.status}`);
    }
    return response.json();
}

// 숫자처럼 보이는 문자열을 안전하게 숫자로 변환
function parseNumber(value) {
    if (value == null || value === '') {
        return Number.NaN;
    }

    const parsed = Number(String(value).replace(/,/g, '').trim());
    return Number.isFinite(parsed) ? parsed : Number.NaN;
}

// 숫자가 아니면 0으로 처리해 후속 계산 오류를 막는다
function safeNumber(value) {
    return Number.isNaN(value) ? 0 : value;
}

// 숫자를 지정한 소수점 자리수로 화면 표시 문자열로 변환
function formatNumber(value, digits) {
    if (Number.isNaN(value)) {
        return '-';
    }

    return Number(value).toLocaleString(undefined, {
        minimumFractionDigits: digits,
        maximumFractionDigits: digits
    });
}

// 배열에서 최대 숫자 값을 찾는다.
function getMaxValue(values) {
    const numbers = values.map(parseNumber).filter((value) => !Number.isNaN(value));
    return numbers.length ? Math.max(...numbers) : Number.NaN;
}

// 배열에서 최소 숫자 값을 찾는다
function getMinValue(values) {
    const numbers = values.map(parseNumber).filter((value) => !Number.isNaN(value));
    return numbers.length ? Math.min(...numbers) : Number.NaN;
}

// DOM 요소의 텍스트를 공통 방식으로 갱신
function setText(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.textContent = value;
    }
}

// DOM 요소의 HTML 내용을 공통 방식으로 갱신
function setHtml(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.innerHTML = value;
    }
}

// DOM 요소의 width 스타일을 공통 방식으로 갱신한
function setStyleWidth(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.style.width = value;
    }
}

// DOM 요소의 클래스를 한 번에 교체
function setElementClass(id, value) {
    const element = document.getElementById(id);
    if (element) {
        element.className = value;
    }
}

// 숫자를 지정 범위 안으로 제한
function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
}

// 부호를 포함한 소수점 숫자 문자열을 만듦
function formatSignedDecimal(value, digits) {
    if (Number.isNaN(value)) {
        return '-';
    }

    const prefix = value > 0 ? '+' : '';
    return `${prefix}${Number(value).toFixed(digits)}`;
}
