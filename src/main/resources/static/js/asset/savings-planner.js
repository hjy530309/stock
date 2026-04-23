/**
 * DOM에서 사용 가능한 금액 가져오기
 */
function getAvailableAmount() {
    const element = document.getElementById('availableAmount');
    if (!element) return 0;

    const text = element.textContent;
    return parseInt(text.replace(/[^0-9]/g, '')) || 0;
}

/**
 * 입력값 유효성 검사
 */
function validateInputs() {
    const goalAmountInput = document.getElementById('goalAmount');
    const goalMonthsInput = document.getElementById('goalMonths');

    if (goalAmountInput) {
        let value = parseInt(goalAmountInput.value.replace(/,/g, '')) || 0;
        value = Math.max(0, value);
        goalAmountInput.value = value.toLocaleString('ko-KR');
    }

    if (goalMonthsInput) {
        let value = parseInt(goalMonthsInput.value.replace(/,/g, '')) || 1;
        value = Math.max(1, value);
        goalMonthsInput.value = value.toLocaleString('ko-KR');
    }
}

/**
 * 숫자 포맷팅
 */
function formatNumber(number) {
    return number.toLocaleString('ko-KR');
}

/**
 * 저축 계산 및 UI 업데이트
 */
function calculateSavings() {
    const goalAmountInput = document.getElementById('goalAmount');
    const goalMonthsInput = document.getElementById('goalMonths');

    if (!goalAmountInput || !goalMonthsInput) return;

    const goalAmount = parseInt(goalAmountInput.value.replace(/,/g, '')) || 0;
    const goalMonths = parseInt(goalMonthsInput.value.replace(/,/g, '')) || 1;

    const monthlyRequired = Math.ceil(goalAmount / goalMonths);
    const availableAmount = getAvailableAmount();
    const gap = monthlyRequired - availableAmount;

    updateMonthlyRequired(monthlyRequired);
    updateGapAnalysis(monthlyRequired, gap, availableAmount);
    updateAlertBanner(gap);
}

/**
 * 월 필요 저축액 업데이트
 */
function updateMonthlyRequired(amount) {
    const monthlyRequiredEl = document.getElementById('monthlyRequired');
    const requiredEl = document.getElementById('requiredAmount');

    if (monthlyRequiredEl) monthlyRequiredEl.textContent = formatNumber(amount);
    if (requiredEl) requiredEl.textContent = formatNumber(amount) + '원';
}

/**
 * Gap Analysis 섹션 업데이트
 */
function updateGapAnalysis(monthlyRequired, gap, availableAmount) {
    const availableEl = document.getElementById('availableAmount');
    const gapItem = document.getElementById('gapItem');
    const gapValue = document.getElementById('gapValue');

    if (availableEl) availableEl.textContent = formatNumber(availableAmount) + '원';

    if (!gapItem || !gapValue) return;

    const labelEl = gapItem.querySelector('.analysis-label');

    if (gap > 0) {
        gapItem.classList.add('warning');
        gapItem.classList.remove('success');
        if (labelEl) labelEl.textContent = '부족 금액';
        gapValue.textContent = '-' + formatNumber(gap) + '원';
        gapValue.classList.remove('primary');
        gapValue.classList.add('accent');
    } else {
        gapItem.classList.remove('warning');
        gapItem.classList.add('success');
        if (labelEl) labelEl.textContent = '여유 금액';
        gapValue.textContent = '+' + formatNumber(Math.abs(gap)) + '원';
        gapValue.classList.remove('accent');
        gapValue.classList.add('primary');
    }
}

/**
 * 경고/성공 배너 업데이트
 */
function updateAlertBanner(gap) {
    const alertBanner = document.getElementById('alertBanner');
    if (!alertBanner) return;

    const alertTitle = alertBanner.querySelector('.alert-title');
    const alertDescription = alertBanner.querySelector('.alert-description');
    const alertIcon = alertBanner.querySelector('.alert-icon');
    if (!alertTitle || !alertDescription || !alertIcon) return;

    if (gap > 0) {
        alertBanner.classList.remove('success');
        alertBanner.classList.add('warning');
        alertIcon.classList.remove('success');
        alertIcon.classList.add('warning');
        alertTitle.classList.remove('success');
        alertTitle.classList.add('warning');

        alertTitle.textContent = '저축 목표 달성 어려움';
        alertDescription.innerHTML = `현재 수입과 지출 패턴으로는 월 <span style="font-weight: 600;">${formatNumber(gap)}원</span>이 부족합니다. 지출을 줄이거나 수입을 늘려보세요.`;

        alertIcon.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/>
                <line x1="12" y1="9" x2="12" y2="13"/>
                <line x1="12" y1="17" x2="12.01" y2="17"/>
            </svg>
        `;
    } else {
        alertBanner.classList.remove('warning');
        alertBanner.classList.add('success');
        alertIcon.classList.remove('warning');
        alertIcon.classList.add('success');
        alertTitle.classList.remove('warning');
        alertTitle.classList.add('success');

        alertTitle.textContent = '목표 달성 가능';
        alertDescription.innerHTML = `현재 재무 상태로 목표를 달성할 수 있습니다. 월 <span style="font-weight: 600;">${formatNumber(Math.abs(gap))}원</span>의 여유가 있습니다.`;

        alertIcon.innerHTML = `
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <polyline points="20 6 9 17 4 12"/>
            </svg>
        `;
    }
}

/**
 * 카드 스크롤 애니메이션
 */
function animateCards() {
    const cards = document.querySelectorAll('.card');

    const observer = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
            if (entry.isIntersecting) {
                entry.target.style.opacity = '1';
                entry.target.style.transform = 'translateY(0)';
            }
        });
    }, { threshold: 0.2 });

    cards.forEach((card) => {
        card.style.opacity = '0';
        card.style.transform = 'translateY(20px)';
        card.style.transition = 'opacity 0.5s, transform 0.5s';
        observer.observe(card);
    });
}

/**
 * 입력값 콤마 포맷팅
 */
function setupMoneyInputs() {
    document.querySelectorAll('.money').forEach((input) => {
        if (input.value) {
            input.value = input.value.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        }

        input.addEventListener('input', (e) => {
            let value = e.target.value.replace(/[^0-9]/g, '');
            e.target.value = value.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
        });
    });

    const form = document.querySelector('form');
    if (form) {
        form.addEventListener('submit', () => {
            document.querySelectorAll('.money').forEach((input) => {
                input.value = input.value.replace(/,/g, '');
            });
        });
    }
}

/**
 * 최근 분석 이력 더보기/접기
 */
function toggleHistory() {
    const list = document.getElementById('historyList');
    const button = document.querySelector('.history-more-btn');

    if (!list || !button) return;

    list.classList.toggle('collapsed');
    button.textContent = list.classList.contains('collapsed') ? '더보기' : '접기';
}

/**
 * 초기화
 */
document.addEventListener('DOMContentLoaded', () => {
    const goalAmountInput = document.getElementById('goalAmount');
    const goalMonthsInput = document.getElementById('goalMonths');

    if (goalAmountInput) goalAmountInput.addEventListener('input', calculateSavings);
    if (goalMonthsInput) goalMonthsInput.addEventListener('input', calculateSavings);

    if (goalAmountInput) goalAmountInput.addEventListener('blur', validateInputs);
    if (goalMonthsInput) goalMonthsInput.addEventListener('blur', validateInputs);

    setupMoneyInputs();
    calculateSavings();

    const userNameEl = document.querySelector('.user-name');
    const userAvatarEl = document.querySelector('.user-avatar');

    if (userNameEl && userAvatarEl) {
        const fullName = userNameEl.textContent.trim();
        userAvatarEl.textContent = fullName.charAt(0) || '';
    }
});

window.addEventListener('load', animateCards);
