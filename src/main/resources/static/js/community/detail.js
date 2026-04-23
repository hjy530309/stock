document.addEventListener("DOMContentLoaded", function () {
    const likeButton = document.getElementById("likeButton");
    const likeCount = document.getElementById("likeCount");
    const bottomLikeCount = document.getElementById("bottomLikeCount");
    const commentTextarea = document.querySelector(".commentInputArea textarea");
    const commentCounter = document.querySelector(".commentWriteBottom span");
    const deleteForm = document.querySelector(".postDeleteForm");

    if (commentTextarea && commentCounter) {
        const updateCommentLength = function () {
            commentCounter.textContent = commentTextarea.value.length + "/1000";
        };

        commentTextarea.addEventListener("input", updateCommentLength);
        updateCommentLength();
    }

    if (likeButton) {
        likeButton.addEventListener("click", async function () {
            const boardId = likeButton.dataset.boardId;

            try {
                const response = await fetch("/community/like?board_id=" + boardId, {
                    method: "POST"
                });

                const data = await response.json();

                if (!data.success) {
                    alert(data.message || "좋아요 처리에 실패했습니다.");
                    return;
                }

                likeCount.textContent = data.likeCount;
                bottomLikeCount.textContent = "좋아요 " + data.likeCount;

                if (data.liked) {
                    likeButton.classList.add("active");
                } else {
                    likeButton.classList.remove("active");
                }
            } catch (error) {
                console.error(error);
                alert("좋아요 처리 중 오류가 발생했습니다.");
            }
        });
    }

    if (deleteForm) {
        deleteForm.addEventListener("submit", function (event) {
            if (event.defaultPrevented) {
                return;
            }

            if (deleteForm.dataset.submitting === "true") {
                event.preventDefault();
                return;
            }

            deleteForm.dataset.submitting = "true";
            setDeleteSubmittingState(deleteForm, true);
        });
    }
});

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

function setDeleteSubmittingState(form, isSubmitting) {
    const submitButton = form.querySelector('button[type="submit"]');
    if (!submitButton) {
        return;
    }

    const originalText = submitButton.dataset.originalText || submitButton.textContent.trim();
    submitButton.dataset.originalText = originalText;
    submitButton.disabled = isSubmitting;
    submitButton.textContent = isSubmitting ? "삭제 중..." : originalText;

    if (isSubmitting) {
        showCommunityActionOverlay("게시글을 삭제하고 있어요...");
    }
}

function toggleMoreMenu() {
    const menu = document.getElementById("moreMenuDropdown");
    if (menu) {
        menu.classList.toggle("show");
    }
}

document.addEventListener("click", function (event) {
    const wrap = document.querySelector(".moreMenuWrap");
    const menu = document.getElementById("moreMenuDropdown");

    if (!wrap || !menu) {
        return;
    }

    if (!wrap.contains(event.target)) {
        menu.classList.remove("show");
    }
});

function toggleCommentMenu(commentId) {
    const targetMenu = document.getElementById("commentMenu-" + commentId);
    if (!targetMenu) {
        return;
    }

    document.querySelectorAll(".commentMoreMenuDropdown").forEach(function (menu) {
        if (menu !== targetMenu) {
            menu.classList.remove("show");
        }
    });

    targetMenu.classList.toggle("show");
}

function toggleCommentEdit(commentId) {
    const text = document.getElementById("commentText-" + commentId);
    const form = document.getElementById("commentEditForm-" + commentId);
    const menu = document.getElementById("commentMenu-" + commentId);

    if (!text || !form) {
        return;
    }

    const isHidden = form.style.display === "none" || form.style.display === "";
    form.style.display = isHidden ? "block" : "none";
    text.style.display = isHidden ? "none" : "block";

    if (menu) {
        menu.classList.remove("show");
    }
}

document.addEventListener("click", function (event) {
    document.querySelectorAll(".commentMoreMenuWrap").forEach(function (wrap) {
        if (!wrap.contains(event.target)) {
            const menu = wrap.querySelector(".commentMoreMenuDropdown");
            if (menu) {
                menu.classList.remove("show");
            }
        }
    });
});
