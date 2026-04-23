function buildHalfDoughnutChart(canvas, achievementRate) {
    const remainingRate = Math.max(0, 100 - achievementRate);

    return new Chart(canvas.getContext('2d'), {
        type: 'doughnut',
        data: {
            datasets: [{
                data: [achievementRate, remainingRate],
                backgroundColor: ['#3f5fb8', '#e9ecef'],
                borderWidth: 0,
                circumference: 180,
                rotation: 270,
                cutout: '80%'
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { display: false },
                tooltip: { enabled: false }
            }
        }
    });
}

function drawAssetTrend() {
    const canvas = document.getElementById('assetTrendChart');
    if (!canvas || typeof Chart === 'undefined') {
        return;
    }

    const labels = canvas.dataset.labels ? canvas.dataset.labels.split(',') : [];
    const values = canvas.dataset.values ? canvas.dataset.values.split(',').map(Number) : [];

    new Chart(canvas.getContext('2d'), {
        type: 'line',
        data: {
            labels,
            datasets: [{
                data: values,
                borderColor: '#15164D',
                backgroundColor: 'rgba(0, 102, 255, 0.1)',
                fill: true,
                tension: 0.4,
                pointRadius: 5
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                y: {
                    beginAtZero: false,
                    grid: { color: '#f3f4f6' },
                    ticks: {
                        callback: (value) => `${(Number(value) / 10000).toLocaleString()} man`
                    }
                },
                x: { grid: { display: false } }
            },
            plugins: {
                legend: { display: false }
            }
        }
    });
}

function animateCards() {
    const cards = document.querySelectorAll('.card');
    const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (!entry.isIntersecting) {
                return;
            }

            entry.target.style.opacity = '1';
            entry.target.style.transform = 'translateY(0)';
        });
    }, { threshold: 0.1 });

    cards.forEach((card) => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        card.style.transition = 'opacity 0.5s, transform 0.5s';
        observer.observe(card);
    });
}

function initUserAvatar() {
    const userNameElement = document.querySelector('.user-name');
    const userAvatarElement = document.querySelector('.user-avatar');
    if (!userNameElement || !userAvatarElement) {
        return;
    }

    const fullName = userNameElement.textContent.trim();
    userAvatarElement.textContent = fullName.charAt(0) || '';
}

function openModal() {
    const modal = document.getElementById('transactionModal');
    if (modal) {
        modal.style.display = 'flex';
    }
}

function closeModal() {
    const modal = document.getElementById('transactionModal');
    if (modal) {
        modal.style.display = 'none';
    }
}

function bindTransactionForm() {
    const transactionForm = document.getElementById('transactionForm');
    if (!transactionForm) {
        return;
    }

    transactionForm.addEventListener('submit', async (event) => {
        event.preventDefault();

        const payload = {
            vendor: document.getElementById('vendor')?.value || '',
            transaction_date: document.getElementById('transaction_date')?.value || '',
            amount: parseFloat(document.getElementById('amount')?.value || '0'),
            user_id: parseInt(document.getElementById('userId')?.value || '0', 10)
        };

        try {
            const response = await fetch('http://127.0.0.1:8001/classify_transaction', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });
            const data = await response.json();

            if (data.result && data.result.saved) {
                alert(`Category saved: ${data.result.category}`);
                closeModal();
                location.reload();
                return;
            }

            alert('Unable to save the spending history.');
        } catch (error) {
            console.error('Error:', error);
            alert('Unable to reach the spending classification server.');
        }
    });
}

function initMonthlyChart() {
    // 월별 지출 차트를 그릴 canvas를 찾습니다.
    const canvas = document.getElementById('monthlyChart');
    if (!canvas || typeof Chart === 'undefined' || !canvas.dataset.trend) {
        return;
    }

    const trendData = JSON.parse(canvas.dataset.trend);
    const selectedMonth = Number(canvas.dataset.selectedMonth);

    const labels = trendData.map((item) => `${item.month}M`);
    const spendingValues = trendData.map((item) => Number(item.total) || 0);

    if (!spendingValues.length) {
        return;
    }

    // 전월 대비 증감률 계산
    // 첫 달은 비교할 전월 데이터가 없으므로 null 대신 0으로 둬서
    // 선이 끊기지 않고 "기준점"처럼 보이게 만듭니다.
    const changeRateValues = spendingValues.map((currentValue, index) => {
        if (index === 0) {
            return 0;
        }

        const previousValue = spendingValues[index - 1];

        if (!previousValue) {
            return 0;
        }

        const rate = ((currentValue - previousValue) / previousValue) * 100;
        return Number(rate.toFixed(1));
    });

    const barColors = trendData.map((item) =>
        item.month === selectedMonth ? '#5B7BEA' : '#e9ecef'
    );

    // 점 색도 증가/감소에 따라 다르게 줍니다.
    const pointColors = changeRateValues.map((value, index) => {
        if (index === 0 || value === 0) {
            return '#15164D';
        }

        return value > 0 ? '#D74B5A' : '#3182F6';
    });

    new Chart(canvas, {
        data: {
            labels,
            datasets: [
                {
                    // 실제 월별 지출 금액 막대
                    type: 'bar',
                    label: '월별 지출',
                    data: spendingValues,
                    backgroundColor: barColors,
                    borderRadius: 12,
                    borderSkipped: false,
                    barPercentage: 0.62,
                    categoryPercentage: 0.72,
                    yAxisID: 'yAmount',
                    order: 2
                },
                {
                    // 전월 대비 증감률 선
                    // 구간별로 색을 다르게 주기 위해 segment 설정을 사용합니다.
                    type: 'line',
                    label: '전월 대비 증감률',
                    data: changeRateValues,
                    borderWidth: 3,
                    pointRadius: 0,
                    pointHoverRadius: 0,
                    tension: 0,
                    fill: false,
                    yAxisID: 'yRate',
                    order: 1,
                    spanGaps: true,

                    // 선의 각 구간 색상:
                    // 다음 점(y1)이 양수면 빨강, 음수면 파랑, 0이면 네이비
                    segment: {
                        borderColor: (ctx) => {
                            const y = ctx.p1.parsed.y;

                            if (y > 0) return '#D74B5A';
                            if (y < 0) return '#3182F6';
                            return '#15164D';
                        }
                    }
                },
                {
                    // 점은 따로 scatter dataset으로 분리해서
                    // 증가/감소에 따라 점 색을 다르게 보이게 합니다.
                    type: 'scatter',
                    label: '',
                    data: changeRateValues.map((value, index) => ({
                        x: labels[index],
                        y: value
                    })),
                    yAxisID: 'yRate',
                    order: 0,
                    pointRadius: 5,
                    pointHoverRadius: 6,
                    pointBorderWidth: 3,
                    pointBorderColor: '#ffffff',
                    pointBackgroundColor: pointColors,
                    showLine: false
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: {
                mode: 'index',
                intersect: false
            },
            plugins: {
                legend: {
                    display: true,
                    position: 'top',
                    align: 'end',
                    labels: {
                        filter: (legendItem) => legendItem.text !== '',
                        usePointStyle: true,
                        pointStyle: 'circle',
                        boxWidth: 8,
                        boxHeight: 8,
                        color: '#6B7280',
                        font: {
                            size: 12,
                            weight: '600'
                        }
                    }
                },
                tooltip: {
                    padding: 10,
                    filter: (tooltipItem) => {
                        return tooltipItem.dataset.label !== '';
                    },
                    bodyFont: {
                        size: 13,
                        weight: 'bold'
                    },
                    titleFont: {
                        size: 14,
                        weight: 'bold'
                    },
                    callbacks: {
                        label: (context) => {
                            const datasetLabel = context.dataset.label || '';

                            if (context.dataset.yAxisID === 'yAmount') {
                                return `${datasetLabel}: ${Number(context.raw).toLocaleString()}원`;
                            }

                            if (context.dataset.yAxisID === 'yRate') {
                                const rateValue =
                                    typeof context.raw === 'object'
                                        ? context.raw.y
                                        : context.raw;

                                const sign = Number(rateValue) > 0 ? '+' : '';
                                return `${datasetLabel}: ${sign}${rateValue}%`;
                            }

                            return `${datasetLabel}: ${context.raw}`;
                        }
                    }
                }
            },
            scales: {
                x: {
                    ticks: {
                        color: '#9CA3AF',
                        font: {
                            size: 12,
                            weight: '600'
                        }
                    },
                    grid: {
                        display: false
                    },
                    border: {
                        display: false
                    }
                },
                yAmount: {
                    beginAtZero: true,
                    position: 'left',
                    ticks: {
                        color: '#9CA3AF',
                        font: {
                            size: 11
                        },
                        callback: (value) => `${Number(value).toLocaleString()}`
                    },
                    grid: {
                        color: '#EEF1F5',
                        drawBorder: false
                    },
                    border: {
                        display: false
                    }
                },
                yRate: {
                    position: 'right',
                    ticks: {
                        color: '#6B7280',
                        font: {
                            size: 11,
                            weight: '600'
                        },
                        callback: (value) => `${value}%`
                    },
                    grid: {
                        drawOnChartArea: false
                    },
                    border: {
                        display: false
                    }
                }
            }
        }
    });
}

document.addEventListener('DOMContentLoaded', () => {
    const savingsGauge = document.getElementById('savingsGauge');
    if (savingsGauge && typeof Chart !== 'undefined') {
        const achievementRate = parseFloat(savingsGauge.dataset.rate || '0');
        buildHalfDoughnutChart(savingsGauge, achievementRate);
    }

    drawAssetTrend();
    initUserAvatar();
    bindTransactionForm();
    initMonthlyChart();
});

window.addEventListener('load', animateCards);