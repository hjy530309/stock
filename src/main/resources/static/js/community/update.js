document.addEventListener("DOMContentLoaded", function () {
    const newsKeywordInput = document.getElementById("newsKeyword");
    const newsSearchResult = document.getElementById("newsSearchResult");
    const selectedNewsBox = document.getElementById("selectedNewsBox");
    const newsLinkInput = document.getElementById("newsLink");
    const communityWriteForm = document.forms["communityWriteForm"];
    const initialNewsLink = selectedNewsBox?.dataset.initialLink?.trim()
        || newsLinkInput?.value?.trim()
        || "";
    const initialNewsTitle = selectedNewsBox?.dataset.initialTitle?.trim() || "";

    let searchTimer = null;

    function escapeHtml(value) {
        return String(value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    function renderEmptyMessage(message) {
        if (!newsSearchResult) {
            return;
        }
        newsSearchResult.innerHTML = '<div class="newsEmptyMessage">' + escapeHtml(message) + "</div>";
    }

    function clearSelectedNews() {
        if (selectedNewsBox) {
            selectedNewsBox.innerHTML = "";
        }
        if (newsLinkInput) {
            newsLinkInput.value = "";
        }
    }

    function renderSelectedNews(item) {
        if (!selectedNewsBox || !newsLinkInput) {
            return;
        }

        newsLinkInput.value = item.link || "";
        selectedNewsBox.innerHTML =
            '<div class="selectedNewsTag">' +
            '<span class="selectedNewsTagText">' + escapeHtml(item.title || item.link || "") + "</span>" +
            '<button type="button" class="selectedNewsRemove" aria-label="선택한 뉴스 제거">×</button>' +
            "</div>";

        const removeButton = selectedNewsBox.querySelector(".selectedNewsRemove");
        if (removeButton) {
            removeButton.addEventListener("click", function () {
                clearSelectedNews();
                if (newsKeywordInput) {
                    newsKeywordInput.focus();
                }
            });
        }
    }

    async function searchRelatedNews(keyword) {
        if (!newsSearchResult) {
            return;
        }

        const trimmedKeyword = String(keyword || "").trim();
        if (trimmedKeyword.length < 2) {
            renderEmptyMessage("두 글자 이상 입력하면 관련 뉴스를 찾을 수 있어요.");
            return;
        }

        try {
            const response = await fetch("/community/news/search?keyword=" + encodeURIComponent(trimmedKeyword), {
                headers: {
                    "X-Requested-With": "XMLHttpRequest"
                }
            });

            if (!response.ok) {
                throw new Error("search_failed");
            }

            const items = await response.json();
            if (!Array.isArray(items) || items.length === 0) {
                renderEmptyMessage("검색된 뉴스가 없습니다.");
                return;
            }

            newsSearchResult.innerHTML = "";

            items.forEach(function (item) {
                const button = document.createElement("button");
                button.type = "button";
                button.className = "newsSearchItem";
                button.innerHTML =
                    "<strong>" + escapeHtml(item.title || "제목 없음") + "</strong>" +
                    "<span>" + escapeHtml(item.summary || "요약 없음").slice(0, 90) + "...</span>";

                button.addEventListener("click", function () {
                    renderSelectedNews(item);
                    newsSearchResult.innerHTML = "";
                    if (newsKeywordInput) {
                        newsKeywordInput.value = "";
                    }
                });

                newsSearchResult.appendChild(button);
            });
        } catch (error) {
            renderEmptyMessage("뉴스 검색 중 오류가 발생했습니다.");
        }
    }

    if (newsKeywordInput) {
        renderEmptyMessage("두 글자 이상 입력하면 관련 뉴스를 찾을 수 있어요.");

        if (initialNewsLink) {
            renderSelectedNews({
                link: initialNewsLink,
                title: initialNewsTitle || initialNewsLink
            });
        }

        newsKeywordInput.addEventListener("input", function () {
            const keyword = newsKeywordInput.value;

            if (searchTimer) {
                clearTimeout(searchTimer);
            }

            searchTimer = setTimeout(function () {
                searchRelatedNews(keyword);
            }, 250);
        });
    }

    if (communityWriteForm) {
        ["category", "title", "content", "tagNames"].forEach(function (fieldName) {
            const field = communityWriteForm[fieldName];
            if (!field) {
                return;
            }

            field.addEventListener("input", syncCommunityWriteSubmitState);
            field.addEventListener("change", syncCommunityWriteSubmitState);
        });

        syncCommunityWriteSubmitState();
    }
});

const bannedWords = [
    "시발", "병신", "개새끼", "꺼져", "닥쳐", "닥죽", "존나", "좆같",
    "망할", "사기꾼", "개좆망", "빡대가리", "쳐먹어", "정신병자",
    "좌파", "우파", "정치충", "종북", "새끼", "개빡", "애미없",
    "ㅅㅂ", "ㅄ", "tlqkf", "염병", "씨발", "좆", "개놈", "바보"
];

function findBannedWord(...values) {
    for (const value of values) {
        const text = String(value || "").trim().toLowerCase();

        for (const bannedWord of bannedWords) {
            if (text.includes(bannedWord.toLowerCase())) {
                return bannedWord;
            }
        }
    }
    return null;
}

function getCommunityWriteForm() {
    return document.forms["communityWriteForm"] || null;
}

function isCommunityWriteFormValid(form) {
    const targetForm = form || getCommunityWriteForm();
    if (!targetForm) {
        return false;
    }

    if (!targetForm.category || targetForm.category.value === "") {
        return false;
    }

    if (!targetForm.title || targetForm.title.value.trim() === "") {
        return false;
    }

    if (!targetForm.content || targetForm.content.value.trim() === "") {
        return false;
    }

    return true;
}

function syncCommunityWriteSubmitState() {
    const form = getCommunityWriteForm();
    const submitButton = document.getElementById("cwSubmitBtn");
    if (!form || !submitButton) {
        return;
    }

    if (form.dataset.submitting === "true") {
        return;
    }

    const isReady = isCommunityWriteFormValid(form);
    submitButton.disabled = !isReady;
    submitButton.classList.toggle("isReady", isReady);
}

function showCommunityActionOverlay(message) {
    if (document.getElementById("communityActionOverlay")) {
        return;
    }

    const overlay = document.createElement("div");
    overlay.id = "communityActionOverlay";
    overlay.setAttribute("aria-live", "polite");
    overlay.style.cssText = [
        "position:fixed",
        "inset:0",
        "background:rgba(15,23,42,0.24)",
        "backdrop-filter:blur(2px)",
        "display:flex",
        "align-items:center",
        "justify-content:center",
        "z-index:9999"
    ].join(";");

    const panel = document.createElement("div");
    panel.style.cssText = [
        "min-width:220px",
        "padding:28px 36px",
        "border-radius:16px",
        "background:#ffffff",
        "box-shadow:0 18px 40px rgba(15,23,42,0.18)",
        "color:#111827",
        "font-size:14px",
        "font-weight:700",
        "text-align:center"
    ].join(";");
    panel.textContent = message;

    overlay.appendChild(panel);
    document.body.appendChild(overlay);
}

function setCommunityWriteSubmittingState(isSubmitting) {
    const form = getCommunityWriteForm();
    const submitButton = document.getElementById("cwSubmitBtn");
    if (!form || !submitButton) {
        return;
    }

    form.dataset.submitting = isSubmitting ? "true" : "false";
    submitButton.disabled = isSubmitting || !isCommunityWriteFormValid(form);
    submitButton.classList.toggle("isReady", !isSubmitting && isCommunityWriteFormValid(form));
    submitButton.classList.toggle("isSubmitting", isSubmitting);
    submitButton.textContent = isSubmitting ? "수정 중..." : "수정";

    if (isSubmitting) {
        showCommunityActionOverlay("게시글을 수정하고 있어요...");
    }
}

function check() {
    const communityWriteForm = getCommunityWriteForm();
    if (!communityWriteForm) {
        return false;
    }

    if (communityWriteForm.dataset.submitting === "true") {
        return false;
    }

    if (communityWriteForm.category.value === "") {
        syncCommunityWriteSubmitState();
        alert("카테고리를 선택해주세요.");
        return false;
    }

    if (communityWriteForm.title.value.trim() === "") {
        syncCommunityWriteSubmitState();
        alert("제목을 입력해주세요.");
        return false;
    }

    if (communityWriteForm.content.value.trim() === "") {
        syncCommunityWriteSubmitState();
        alert("내용을 입력해주세요.");
        return false;
    }

    const bannedWord = findBannedWord(
        communityWriteForm.title.value,
        communityWriteForm.content.value,
        communityWriteForm.tagNames ? communityWriteForm.tagNames.value : ""
    );

    if (bannedWord) {
        syncCommunityWriteSubmitState();
        alert("금지어가 포함되어 있습니다: " + bannedWord);
        return false;
    }

    setCommunityWriteSubmittingState(true);
    return true;
}
