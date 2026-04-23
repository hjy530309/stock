// Analytics.js - 소비 분석 페이지 초기화

document.addEventListener('DOMContentLoaded', function() {
    // 도넛 차트 그리기
    drawCategoryDonut();

    // 캘린더 초기화
    initializeCalendar();

    // 범례 색상 생성
    createLegendColors();
});


/**
 * 도넛 차트가 12시부터 한 바퀴 쭉~ 채워지는 애니메이션
 */
// function drawCategoryDonut() {
//     const canvas = document.getElementById('categoryDonut');
//     if (!canvas || !window.categoryData || window.categoryData.length === 0) return;
//
//     const ctx = canvas.getContext('2d');
//     const centerX = canvas.width / 2;
//     const centerY = canvas.height / 2;
//     const radius = 120;
//     const innerRadius = 70;
//
//     const total = window.categoryData.reduce((sum, cat) => sum + Number(cat.value), 0);
//
//     let currentPercent = 0; // 0에서 1까지 증가하는 전체 진행률
//     const speed = 0.02;     // 채워지는 속도 (낮을수록 느림)
//
//     function animate() {
//         ctx.clearRect(0, 0, canvas.width, canvas.height);
//
//         // 전체 원 중에서 현재 진행률만큼만 그릴 '총 각도'
//         const totalDrawAngle = currentPercent * (Math.PI * 2);
//         let startAngle = -Math.PI / 2; // 시작점: 12시 방향
//
//         // 누적된 각도를 추적하면서 각 카테고리를 그립니다.
//         let accumulatedAngle = 0;
//
//         window.categoryData.forEach(category => {
//             const categoryAngle = (Number(category.value) / total) * (Math.PI * 2);
//
//             // 현재까지 그려야 할 총 각도(totalDrawAngle) 내에 이 카테고리가 포함되는지 계산
//             if (accumulatedAngle < totalDrawAngle) {
//                 // 이 조각이 그려질 실제 각도 (남은 각도와 카테고리 각도 중 작은 값)
//                 const drawAngle = Math.min(categoryAngle, totalDrawAngle - accumulatedAngle);
//
//                 ctx.beginPath();
//                 ctx.arc(centerX, centerY, radius, startAngle, startAngle + drawAngle);
//                 ctx.arc(centerX, centerY, innerRadius, startAngle + drawAngle, startAngle, true);
//                 ctx.closePath();
//
//                 ctx.fillStyle = category.color;
//                 ctx.fill();
//
//                 startAngle += categoryAngle;
//                 accumulatedAngle += categoryAngle;
//             }
//         });
//
//         if (currentPercent < 1) {
//             currentPercent += speed;
//             requestAnimationFrame(animate);
//         }
//     }
//
//     setTimeout(animate, 300);
// }

// color.css 에서 실제 색상값을 읽어오는 유틸 함수
function getThemeColor(variableName, fallback) {
    const value = getComputedStyle(document.documentElement)
        .getPropertyValue(variableName)
        .trim();

    return value || fallback;
}

function drawCategoryDonut() {
    const canvas = document.getElementById('categoryDonut');
    if (!canvas || !window.categoryData || window.categoryData.length === 0) return;

    const ctx = canvas.getContext('2d');

    canvas.width = 220;
    canvas.height = 220;

    const centerX = canvas.width / 2;
    const centerY = canvas.height / 2;
    const radius = 90;
    const innerRadius = 55;

    const total = window.categoryData.reduce((sum, cat) => sum + Number(cat.value), 0);

    let currentPercent = 0;
    const speed = 0.02;

    function animate() {
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        const totalDrawAngle = currentPercent * (Math.PI * 2);
        let startAngle = -Math.PI / 2;
        let accumulatedAngle = 0;

        window.categoryData.forEach(category => {
            const categoryAngle = (Number(category.value) / total) * (Math.PI * 2);

            if (accumulatedAngle < totalDrawAngle) {
                const drawAngle = Math.min(categoryAngle, totalDrawAngle - accumulatedAngle);

                ctx.beginPath();
                ctx.lineCap = 'round';

                ctx.arc(centerX, centerY, radius, startAngle, startAngle + drawAngle);
                ctx.arc(centerX, centerY, innerRadius, startAngle + drawAngle, startAngle, true);
                ctx.closePath();

                ctx.fillStyle = category.color;
                ctx.fill();

                startAngle += categoryAngle;
                accumulatedAngle += categoryAngle;
            }
        });

        if (currentPercent < 1) {
            currentPercent += speed;
            requestAnimationFrame(animate);
        }
    }

    setTimeout(animate, 300);
}

/**
 * 캘린더 초기화 (색상 및 툴팁)
 */
function initializeCalendar() {
    const calendarDays = document.querySelectorAll('.calendar-day[data-amount]');

    calendarDays.forEach(day => {
        const amount = parseInt(day.getAttribute('data-amount'));
        const date = day.getAttribute('data-date');

        const level = getSpendingLevel(amount);
        const color = getColorByLevel(level);
        const textColor = level > 2 ? '#ffffff' : getThemeColor('--text-main', '#111827');

        day.style.backgroundColor = color;
        day.style.color = textColor;
        day.textContent = date;

        const tooltip = document.createElement('div');
        tooltip.className = 'calendar-day-tooltip';
        tooltip.textContent = amount.toLocaleString('ko-KR') + '원';
        day.appendChild(tooltip);
    });
}

/**
 * 지출액에 따른 레벨 계산
 */
function getSpendingLevel(amount) {
    if (amount < 20000) return 0;
    if (amount < 50000) return 1;
    if (amount < 100000) return 2;
    if (amount < 200000) return 3;
    if (amount < 500000) return 4;
    return 5;
}

/**
 * 레벨에 따른 색상 반환
 */
function getColorByLevel(level) {
    const colors = [
        getThemeColor('--bg-soft', '#eef2f7'),          // 0: 매우 적음
        '#dbeafe',                                      // 1: 적음
        getThemeColor('--primary-main-soft', '#eef4ff'),// 2: 보통
        getThemeColor('--primary-main', '#3182F6'),     // 3: 많음
        getThemeColor('--primary-dark', '#15164D'),     // 4: 매우 많음
        getThemeColor('--danger-main', '#d74b5a')       // 5: 경고
    ];
    return colors[level];
}

/**
 * 범례 색상 생성
 */
function createLegendColors() {
    const legendContainer = document.getElementById('legendColors');
    if (!legendContainer) return;

    for (let i = 0; i <= 5; i++) {
        const colorBox = document.createElement('div');
        colorBox.className = 'legend-color';
        colorBox.style.backgroundColor = getColorByLevel(i);
        legendContainer.appendChild(colorBox);
    }
}


/**
 * 바 차트 애니메이션 (부드러운 버전)
 */
function animateProgressBars() {
    const bars = document.querySelectorAll('.horizontal-bar-fill');

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                const bar = entry.target;
                // HTML의 data-percent 값을 읽어옵니다.
                const targetWidth = bar.dataset.percent || 0;

                // 아주 잠깐의 지연 후 너비 할당 (애니메이션 효과 극대화)
                requestAnimationFrame(() => {
                    bar.style.width = targetWidth + '%';
                });

                observer.unobserve(bar); // 한 번만 실행
            }
        });
    }, { threshold: 0.1 });

    bars.forEach(bar => observer.observe(bar));
}

// 페이지 로드 시 애니메이션 실행
window.addEventListener('load', animateProgressBars);


/**
 * 카드 애니메이션
 */
function animateCards() {
    const cards = document.querySelectorAll('.card');

    const observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, {
        threshold: 0.1
    });

    cards.forEach(card => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        card.style.transition = 'opacity 0.5s, transform 0.5s';
        observer.observe(card);
    });
}

// 페이지 로드 후 애니메이션 실행
window.addEventListener('load', animateCards);

// 사이드 바에 사용자 성이름 아이콘 가져오기
document.addEventListener('DOMContentLoaded', () => {
    const userNameEl = document.querySelector('.user-name');
    const userAvatarEl = document.querySelector('.user-avatar');

    if (userNameEl && userAvatarEl) {
        const fullName = userNameEl.textContent.trim();
        userAvatarEl.textContent = fullName.charAt(0) || '';
    }
});