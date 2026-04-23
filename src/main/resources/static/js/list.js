document.addEventListener('DOMContentLoaded', function () {
    initCommunityPage();
});

function initCommunityPage() {
    bindSearchForm();
    bindCategoryEffects();
    bindQuickLinks();
    setActiveCategoryFromUrl();
}

function bindSearchForm() {
    const searchForm = document.querySelector('.communitySearch');
    if (!searchForm) return;

    const searchInput = searchForm.querySelector('input[name="keyword"]');
    const searchButton = searchForm.querySelector('button[type="submit"]');

    if (!searchInput || !searchButton) return;

    searchInput.addEventListener('input', function () {
        const trimmedValue = this.value.trim();

        if (trimmedValue.length > 0) {
            searchButton.classList.add('is-ready');
        } else {
            searchButton.classList.remove('is-ready');
        }
    });

    searchForm.addEventListener('submit', function (e) {
        const keyword = searchInput.value.trim();

        if (keyword === '') {
            e.preventDefault();
            alert('검색어를 입력해주세요.');
            searchInput.focus();
            return false;
        }

        searchInput.value = keyword;
    });
}

function bindCategoryEffects() {
    const categoryLinks = document.querySelectorAll('.categoryNav .navItem');
    if (!categoryLinks.length) return;

    categoryLinks.forEach(function (link) {
        link.addEventListener('click', function () {
            categoryLinks.forEach(function (item) {
                item.classList.remove('active-click');
            });

            this.classList.add('active-click');
            document.body.classList.add('is-page-loading');
        });
    });

    const quickTagLinks = document.querySelectorAll('.quickTags a');
    quickTagLinks.forEach(function (link) {
        link.addEventListener('click', function () {
            document.body.classList.add('is-page-loading');
        });
    });

    const writeButtons = document.querySelectorAll('.writeBtn, .sidebarWriteBtn');
    writeButtons.forEach(function (button) {
        button.addEventListener('click', function () {
            document.body.classList.add('is-page-loading');
        });
    });
}

function bindQuickLinks() {
    const sideLinks = document.querySelectorAll('.sideLinks a');
    if (!sideLinks.length) return;

    sideLinks.forEach(function (link) {
        link.addEventListener('mouseenter', function () {
            this.classList.add('is-hovered');
        });

        link.addEventListener('mouseleave', function () {
            this.classList.remove('is-hovered');
        });
    });
}

function setActiveCategoryFromUrl() {
    const currentUrl = new URL(window.location.href);
    const currentCategory = currentUrl.searchParams.get('category');

    const categoryLinks = document.querySelectorAll('.categoryNav .navItem');
    if (!categoryLinks.length) return;

    categoryLinks.forEach(function (link) {
        const linkUrl = new URL(link.href, window.location.origin);
        const linkCategory = linkUrl.searchParams.get('category');

        link.classList.remove('active');

        if (!currentCategory && !linkCategory) {
            link.classList.add('active');
        }

        if (currentCategory && linkCategory === currentCategory) {
            link.classList.add('active');
        }
    });
}

function toggleNewsAccordion(button) {
    const item = button.closest(".newsAccordionItem");
    if (!item) return;

    item.classList.toggle("open");

    const arrow = button.querySelector(".newsAccordionArrow");
    if (arrow) {
        arrow.textContent = item.classList.contains("open") ? "⌃" : "⌄";
    }
}
