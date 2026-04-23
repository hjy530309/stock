function isValidEmail(email) {
    const emailPattern = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/;
    return emailPattern.test(email);
}

function isValidPhone(phone) {
    const phonePattern = /^\d{10,11}$/;
    return phonePattern.test(phone);
}

function check() {
    const registerForm = document.forms["registerForm"];

    const userName = registerForm.userName.value.trim();
    const userEmail = registerForm.userEmail.value.trim();
    const userPassword = registerForm.userPassword.value.trim();
    const reUserPassword = registerForm.reUserPassword.value.trim();
    const userPhone = registerForm.userPhone.value.replace(/[^0-9]/g, "");

    registerForm.userPhone.value = userPhone;

    if (userName === "") {
        alert("이름 누락");
        registerForm.userName.focus();
        return false;
    }

    if (userEmail === "") {
        alert("이메일 누락");
        registerForm.userEmail.focus();
        return false;
    }

    if (!isValidEmail(userEmail)) {
        alert("이메일 형식이 올바르지 않습니다.");
        registerForm.userEmail.focus();
        return false;
    }

    if (userPassword === "") {
        alert("비밀번호 누락");
        registerForm.userPassword.focus();
        return false;
    }

    if (userPassword.length < 5 || userPassword.length > 8) {
        alert("비밀번호는 5 ~ 8 글자 사이");
        registerForm.userPassword.select();
        return false;
    }

    if (isNaN(Number(userPassword))) {
        alert("비밀번호는 숫자만 입력");
        registerForm.userPassword.select();
        return false;
    }

    if (reUserPassword === "") {
        alert("비밀번호 확인 누락");
        registerForm.reUserPassword.focus();
        return false;
    }

    if (reUserPassword.length < 5 || reUserPassword.length > 8) {
        alert("비밀번호는 5 ~ 8 글자 사이");
        registerForm.reUserPassword.select();
        return false;
    }

    if (isNaN(Number(reUserPassword))) {
        alert("비밀번호는 숫자만 입력");
        registerForm.reUserPassword.select();
        return false;
    }

    if (userPassword !== reUserPassword) {
        alert("비밀번호가 일치하지 않습니다.");
        registerForm.reUserPassword.select();
        return false;
    }

    if (userPhone === "") {
        alert("전화번호 누락");
        registerForm.userPhone.focus();
        return false;
    }

    if (!isValidPhone(userPhone)) {
        alert("전화번호는 숫자 10~11자리로 입력해주세요.");
        registerForm.userPhone.focus();
        return false;
    }

    return true;
}
