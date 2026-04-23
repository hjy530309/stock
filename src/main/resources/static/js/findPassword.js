document.addEventListener("DOMContentLoaded", function () {
    const verifyPanel = document.getElementById("verifyPanel");
    const resetPanel = document.getElementById("resetPanel");

    const verifyEmail = document.getElementById("verifyEmail");
    const verifyName = document.getElementById("verifyName");
    const verifyPhone = document.getElementById("verifyPhone");

    const hiddenUserEmail = document.getElementById("hiddenUserEmail");
    const hiddenUserName = document.getElementById("hiddenUserName");
    const hiddenUserPhone = document.getElementById("hiddenUserPhone");

    const verifyMessage = document.getElementById("verifyMessage");
    const resetMessage = document.getElementById("resetMessage");
    const summaryEmail = document.getElementById("summaryEmail");

    const moveResetStepBtn = document.getElementById("moveResetStepBtn");
    const goPrevBtn = document.getElementById("goPrevBtn");

    if (verifyPhone) {
        verifyPhone.addEventListener("input", function (event) {
            event.target.value = formatPhoneNumber(event.target.value);
        });
    }

    [verifyEmail, verifyName, verifyPhone].forEach(function (element) {
        if (!element) {
            return;
        }

        element.addEventListener("input", function () {
            if (verifyMessage) {
                verifyMessage.textContent = "";
            }
        });
    });

    if (moveResetStepBtn) {
        moveResetStepBtn.addEventListener("click", function () {
            const emailValue = verifyEmail ? verifyEmail.value.trim() : "";
            const nameValue = verifyName ? verifyName.value.trim() : "";
            const phoneValue = verifyPhone ? verifyPhone.value.trim() : "";
            const phoneOnly = phoneValue.replace(/[^0-9]/g, "");

            if (verifyMessage) {
                verifyMessage.textContent = "";
            }

            if (emailValue === "" || nameValue === "" || phoneValue === "") {
                if (verifyMessage) {
                    verifyMessage.textContent = "이메일, 이름, 휴대폰 번호를 모두 입력해 주세요.";
                }
                return;
            }

            if (!isValidEmail(emailValue)) {
                if (verifyMessage) {
                    verifyMessage.textContent = "이메일 형식을 확인해 주세요.";
                }
                return;
            }

            if (!isValidPhone(phoneOnly)) {
                if (verifyMessage) {
                    verifyMessage.textContent = "휴대폰 번호는 숫자 10~11자리로 입력해 주세요.";
                }
                return;
            }

            if (hiddenUserEmail) {
                hiddenUserEmail.value = emailValue;
            }

            if (hiddenUserName) {
                hiddenUserName.value = nameValue;
            }

            if (hiddenUserPhone) {
                hiddenUserPhone.value = phoneOnly;
            }

            if (summaryEmail) {
                summaryEmail.textContent = emailValue;
            }

            if (verifyPanel) {
                verifyPanel.classList.add("hidden");
            }

            if (resetPanel) {
                resetPanel.classList.remove("hidden");
            }

            const newPasswordInput = document.getElementById("newPassword");
            if (newPasswordInput) {
                newPasswordInput.focus();
            }
        });
    }

    if (goPrevBtn) {
        goPrevBtn.addEventListener("click", function () {
            if (resetMessage) {
                resetMessage.textContent = "";
            }

            if (resetPanel) {
                resetPanel.classList.add("hidden");
            }

            if (verifyPanel) {
                verifyPanel.classList.remove("hidden");
            }
        });
    }
});

function validateResetPasswordForm() {
    const newPassword = document.getElementById("newPassword");
    const reNewPassword = document.getElementById("reNewPassword");
    const resetMessage = document.getElementById("resetMessage");

    if (!newPassword || !reNewPassword || !resetMessage) {
        return true;
    }

    const newPasswordValue = newPassword.value.trim();
    const reNewPasswordValue = reNewPassword.value.trim();

    resetMessage.textContent = "";

    if (newPasswordValue === "" || reNewPasswordValue === "") {
        resetMessage.textContent = "새 비밀번호를 모두 입력해 주세요.";
        return false;
    }

    if (newPasswordValue.length < 5 || newPasswordValue.length > 8) {
        resetMessage.textContent = "비밀번호는 5자 이상 8자 이하로 입력해 주세요.";
        return false;
    }

    if (!/^\d+$/.test(newPasswordValue)) {
        resetMessage.textContent = "비밀번호는 숫자만 입력해 주세요.";
        return false;
    }

    if (newPasswordValue !== reNewPasswordValue) {
        resetMessage.textContent = "새 비밀번호가 서로 일치하지 않습니다.";
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

function isValidEmail(email) {
    const emailPattern = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
    return emailPattern.test(email);
}

function isValidPhone(phone) {
    const phonePattern = /^\d{10,11}$/;
    return phonePattern.test(phone);
}
