// DOM 생성되자마자
window.addEventListener("DOMContentLoaded", function () {
    loadSavedEmail();
    bindSaveIdChangeEvent();

    // 이메일 등록 후 로그인 시 local 부분 검사
    const params = new URLSearchParams(window.location.search);

    if (params.get("caseError") === "1") {
        alert("이메일의 @ 앞부분 대소문자가 가입 정보와 다릅니다.");
    }
});

// 체크박스 상태 변경
function bindSaveIdChangeEvent() {
    const loginForm = document.forms["loginForm"];

    if (!loginForm || !loginForm.saveId) {
        return;
    }

    // 변경 시 실행
    loginForm.saveId.addEventListener("change", async function () {
        if (!this.checked) {
            await removeSavedEmail();
        }
    });
}

// 저장된 이메일 불러옴
async function loadSavedEmail() {
    const loginForm = document.forms["loginForm"];

    if (!loginForm) {
        return;
    }

    try {
        const response = await fetch("/login/saved-email", {
            method: "GET",
            // 같은 도메인 요청 -> 쿠키 함께
            credentials: "same-origin"
        });

        if (!response.ok) {
            return;
        }

        const data = await response.json();

        if (data.savedEmail && data.savedEmail.trim() !== "") {
            loginForm.userEmail.value = data.savedEmail; // 삽입

            if (loginForm.saveId) {
                loginForm.saveId.checked = true;
            }
        } else { // 저장된 데이터 X
            if (loginForm.saveId) {
                loginForm.saveId.checked = false;
            }
        }
    } catch (error) {
        console.error("저장된 이메일 불러오기 실패:", error);
    }
}

// 체크박스 해제
async function removeSavedEmail() {
    try {
        const response = await fetch("/login/saved-email", {
            method: "DELETE",
            credentials: "same-origin"
        });

        if (!response.ok) {
            console.error("저장된 이메일 삭제 실패");
        }
    } catch (error) {
        console.error("저장된 이메일 삭제 중 오류:", error);
    }
}

// 이메일
function isValidEmail(email) {
    const emailPattern = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
    return emailPattern.test(email);
}

// 이메일 소문자만
function normalizeEmailDomain(email) {
    const parts = email.split("@");

    if (parts.length !== 2) {
        return email;
    }

    // 이메일의 도메인 부분이 대문자 => 소문자로 변환하여
    return parts[0] + "@" + parts[1].toLowerCase();
}

// 유효성
function checkLogin() {
    const loginForm = document.forms["loginForm"];

    if (!loginForm) {
        return false;
    }

    const rawEmail = loginForm.userEmail.value.trim();
    const password = loginForm.userPassword.value.trim();

    if (rawEmail === "") {
            alert("이메일을 입력해주세요.");
            loginForm.userEmail.focus();
            return false;
        }

        if (!isValidEmail(rawEmail)) {
            alert("올바른 이메일 형식으로 입력해주세요.");
            loginForm.userEmail.focus();
            return false;
        }

        loginForm.userEmail.value = normalizeEmailDomain(rawEmail);

        if (password === "") {
            alert("비밀번호를 입력해주세요.");
            loginForm.userPassword.focus();
            return false;
        }

        return true;
}