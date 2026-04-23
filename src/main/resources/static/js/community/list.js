window.addEventListener("DOMContentLoaded", function () {

    // 메인 3단 레이아웃 컨테이너
    const communityMain = document.querySelector(".communityMain");

    // 오른쪽 사이드 전체 영역
    const communityRight = document.getElementById("communityRight") || document.querySelector(".communityRight");

    // 인기 카테고리 패널
    // id가 있으면 그걸 쓰고, 없으면 오른쪽 첫 번째 카드로 대체
    const popularPanel = document.getElementById("popularPanel") || document.querySelector(".communityRight .sideCard:first-child");

    // 토글 스위치와 실제 패널을 연결
    const panelConfigs = [
        { toggleId: "togglePopular", panel: popularPanel, key: "popular" },
        { toggleId: "toggleGuide", panel: document.getElementById("guidePanel"), key: "guide" },
        { toggleId: "toggleAnalysis", panel: document.getElementById("analysisPanel"), key: "analysis" },
        { toggleId: "togglePrice", panel: document.getElementById("pricePanel"), key: "price" }
    ];

    // 오른쪽 패널 토글 상태를 저장할 localStorage
    const TOGGLE_STORAGE_KEY = "communityRightPanelSettingsV4";

    // 아코디언 열림/닫힘 상태를 저장할 localStorage
    const ACCORDION_STORAGE_KEY = "communityAccordionStateV2";

    // 필수 레이아웃 요소가 없으면 실행을 멈춤
    if (!communityMain || !communityRight) {
        return;
    }

    // toggleId로 체크박스
    function getCheckbox(toggleId) {
        return document.getElementById(toggleId);
    }

    // 현재 토글 체크 상태를 localStorage에 저장
    function saveToggleSettings() {
        const settings = {};

        panelConfigs.forEach(function (config) {
            const checkbox = getCheckbox(config.toggleId);
            settings[config.key] = checkbox ? checkbox.checked : true;
        });

        localStorage.setItem(TOGGLE_STORAGE_KEY, JSON.stringify(settings));
    }

    // 저장된 토글 상태를 localStorage에서
    function loadToggleSettings() {
        const saved = localStorage.getItem(TOGGLE_STORAGE_KEY);
        if (!saved) return null;

        try {
            return JSON.parse(saved);
        } catch (e) {
            localStorage.removeItem(TOGGLE_STORAGE_KEY);
            return null;
        }
    }

    // 저장된 토글 상태를 실제 체크박스/패널에 반영
    function applyToggleSettings() {
        const settings = loadToggleSettings();

        panelConfigs.forEach(function (config) {
            const checkbox = getCheckbox(config.toggleId);
            const panel = config.panel;

            if (!checkbox || !panel) return;

            const isChecked = settings && typeof settings[config.key] === "boolean"
                ? settings[config.key]
                : checkbox.checked;

            checkbox.checked = isChecked;

            // 체크 해제된 패널은 CSS 클래스 is-hidden
            panel.classList.toggle("is-hidden", !isChecked);
        });

        // 현재 보이는 패널 개수를 다시 계산
        const visiblePanels = panelConfigs.filter(function (config) {
            return config.panel && !config.panel.classList.contains("is-hidden");
        });

        // 모두 숨겨졌으면 오른쪽 전체도 숨기고 레이아웃을 2열처럼
        if (visiblePanels.length === 0) {
            communityRight.classList.add("is-empty");
            communityMain.classList.add("right-empty");
        } else {
            // 하나라도 보이면 오른쪽 전체를 다시
            communityRight.classList.remove("is-empty");
            communityMain.classList.remove("right-empty");
        }
    }

    // 토글 체크박스 change 이벤트를 연결
    function bindToggles() {
        panelConfigs.forEach(function (config) {
            const checkbox = getCheckbox(config.toggleId);
            if (!checkbox) return;

            checkbox.addEventListener("change", function () {
                saveToggleSettings();
                applyToggleSettings();
            });
        });
    }

    // 현재 아코디언 상태(open 여부)를 localStorage에 저장
    function saveAccordionState() {
        const accordionItems = document.querySelectorAll(".accordionItem");
        const state = [];

        accordionItems.forEach(function (item, index) {
            state[index] = item.classList.contains("open");
        });

        localStorage.setItem(ACCORDION_STORAGE_KEY, JSON.stringify(state));
    }

    // 저장된 아코디언 상태를 불러옵
    function loadAccordionState() {
        const saved = localStorage.getItem(ACCORDION_STORAGE_KEY);
        if (!saved) return null;

        try {
            return JSON.parse(saved);
        } catch (e) {
            localStorage.removeItem(ACCORDION_STORAGE_KEY);
            return null;
        }
    }

    // 저장된 아코디언 상태를 실제 DOM에 반영
    function applyAccordionState() {
        const state = loadAccordionState();
        if (!state) return;

        const accordionItems = document.querySelectorAll(".accordionItem");

        accordionItems.forEach(function (item, index) {
            item.classList.toggle("open", !!state[index]);
        });
    }

    // 아코디언 버튼 클릭 이벤트를 연결
    function bindAccordion() {
        const accordionButtons = document.querySelectorAll(".accordionButton");

        accordionButtons.forEach(function (button) {
            button.addEventListener("click", function () {
                const item = button.closest(".accordionItem");
                if (!item) return;

                // 현재 아코디언을 열거나 닫
                item.classList.toggle("open");

                // 바뀐 상태를 저장
                saveAccordionState();
            });
        });
    }

    // 저장된 아코디언 상태를 먼저 적용
    applyAccordionState();

    // 저장된 토글 상태를 적용
    applyToggleSettings();

    // 이후 사용자 조작을 감지하도록 이벤트를 연결
    bindAccordion();
    bindToggles();
});
