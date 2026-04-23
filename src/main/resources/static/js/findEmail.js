document.addEventListener("DOMContentLoaded", function () {
    const form = document.getElementById("findEmailForm");
    const phoneInput = document.getElementById("userPhone");
    const nameInput = document.getElementById("userName");
    const messageBox = document.getElementById("findEmailMessage");

    if (phoneInput) {
        phoneInput.addEventListener("input", function (event) {
            event.target.value = formatPhoneNumber(event.target.value);
        });
    }

    if (nameInput) {
        nameInput.addEventListener("input", clearMessage);
    }

    if (phoneInput) {
        phoneInput.addEventListener("input", clearMessage);
    }

    if (form) {
        form.addEventListener("submit", function () {
            if (messageBox) {
                messageBox.textContent = "";
            }
        });
    }

    function clearMessage() {
        if (messageBox) {
            messageBox.textContent = "";
        }
    }
});

function validateFindEmailForm() {
    const nameInput = document.getElementById("userName");
    const phoneInput = document.getElementById("userPhone");
    const messageBox = document.getElementById("findEmailMessage");

    if (!nameInput || !phoneInput || !messageBox) {
        return true;
    }

    const userName = nameInput.value.trim();
    const userPhone = phoneInput.value.replace(/[^0-9]/g, "");

    messageBox.textContent = "";

    if (userName === "" || userPhone === "") {
        messageBox.textContent = "이름과 전화번호를 모두 입력해주세요.";
        return false;
    }

    if (userPhone.length < 10) {
        messageBox.textContent = "전화번호 형식을 확인해주세요.";
        return false;
    }

    return true;
}

function formatPhoneNumber(value) {
    const onlyNumber = value.replace(/[^0-9]/g, "").slice(0, 11);

    if (onlyNumber.length < 4) {
        return onlyNumber;
    }

    if (onlyNumber.length < 8) {
        return onlyNumber.replace(/(\d{3})(\d+)/, "$1-$2");
    }

    return onlyNumber.replace(/(\d{3})(\d{4})(\d+)/, "$1-$2-$3");
}