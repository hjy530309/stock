document.addEventListener("DOMContentLoaded", function () {
    const accordionButtons = document.querySelectorAll(".activityPage .accordionButton");

    accordionButtons.forEach((button) => {
        button.addEventListener("click", function () {
            const item = button.closest(".accordionItem");
            if (!item) return;
            item.classList.toggle("open");
        });
    });

    document.addEventListener("click", function (event) {
        document.querySelectorAll(".commentMoreMenuWrap").forEach((wrap) => {
            if (!wrap.contains(event.target)) {
                const menu = wrap.querySelector(".commentMoreMenuDropdown");
                if (menu) {
                    menu.classList.remove("show");
                }
            }
        });
    });
});

function toggleActivityCommentMenu(commentId) {
    const targetMenu = document.getElementById(`activityCommentMenu-${commentId}`);
    if (!targetMenu) return;

    document.querySelectorAll(".commentMoreMenuDropdown").forEach((menu) => {
        if (menu !== targetMenu) {
            menu.classList.remove("show");
        }
    });

    targetMenu.classList.toggle("show");
}