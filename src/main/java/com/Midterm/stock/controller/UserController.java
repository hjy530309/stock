package com.Midterm.stock.controller;

import com.Midterm.stock.dto.UserDto;
import com.Midterm.stock.repository.UserDao;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

// 로그인/회원가입 처리
@Controller
public class UserController {

    @Autowired
    private UserDao userDao;

    // 프로그램 공통 상수
    private static final String REMEMBER_EMAIL_COOKIE = "REMEMBER_EMAIL_TOKEN";
    private static final int REMEMBER_EMAIL_COOKIE_AGE = 60 * 60 * 24 * 7; // 7일
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^\\d{10,11}$");

    // 로그인 처리
    @PostMapping("/login")
    public String login(
            @RequestParam(value = "userEmail", required = false) String email,
            @RequestParam(value = "userPassword", required = false) String pw,
            @RequestParam(value = "saveId", required = false) String saveId,
            HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (email == null || pw == null) {
            return "redirect:/login?error=1";
        }

        email = normalizeEmailDomain(email);
        pw = pw.trim();

        if (email.equals("") || pw.equals("")) {
            return "redirect:/login?error=1";
        }

        if (!isValidEmail(email)) {
            return "redirect:/login?error=1";
        }

        // 로그인 성공 여부 확인과 사용자 정보 조회를 한번에 처리 -> 속도 줄임
        UserDto loginUserInfo = userDao.login(email, pw);

        if (loginUserInfo != null) {
            session.setAttribute("loginNum", loginUserInfo.getNum());
            session.setAttribute("loginUser", loginUserInfo.getEmail());
            session.setAttribute("userName", loginUserInfo.getName());
            session.setAttribute("userEmail", loginUserInfo.getEmail());
            session.setAttribute("userRole", loginUserInfo.getRole());

            String oldToken = getCookieValue(request, REMEMBER_EMAIL_COOKIE);
            if (oldToken != null && !oldToken.trim().equals("")) {
                userDao.deleteSavedEmailToken(oldToken);
            }

            if (saveId != null) {
                String newToken = UUID.randomUUID().toString();
                int saveResult = userDao.insertSavedEmailToken(newToken, email);

                if (saveResult > 0) {
                    Cookie cookie = new Cookie(REMEMBER_EMAIL_COOKIE, newToken);
                    cookie.setPath("/");
                    cookie.setHttpOnly(true);
                    cookie.setMaxAge(REMEMBER_EMAIL_COOKIE_AGE);
                    response.addCookie(cookie);
                }
            } else {
                removeSavedEmailCookie(request, response);
            }

            return "redirect:/stock";
        } else {
            if (userDao.existsWithDifferentEmailCase(email, pw)) {
                return "redirect:/login?caseError=1";
            }
            return "redirect:/login?error=1";
        }
    }

    // 회원가입 처리
    @PostMapping("/register")
    public String register(
            @RequestParam(value = "userName", required = false) String name,
            @RequestParam(value = "userEmail", required = false) String email,
            @RequestParam(value = "userPassword", required = false) String pw,
            @RequestParam(value = "reUserPassword", required = false) String repw,
            @RequestParam(value = "userPhone", required = false) String phone
    ) {
        if (name == null || email == null || pw == null || repw == null || phone == null) {
            return "redirect:/register?error=1";
        }

        name = name.trim();
        email = normalizeEmailDomain(email);
        pw = pw.trim();
        repw = repw.trim();
        phone = phone.trim().replaceAll("[^0-9]", "");

        if (name.equals("") || email.equals("") || pw.equals("") || repw.equals("") || phone.equals("")) {
            return "redirect:/register?error=1";
        }

        if (!isValidEmail(email) || !isValidPhone(phone)) {
            return "redirect:/register?error=1";
        }

        if (pw.length() < 5 || pw.length() > 8) {
            return "redirect:/register?error=1";
        }

        if (repw.length() < 5 || repw.length() > 8) {
            return "redirect:/register?error=1";
        }

        try {
            Integer.parseInt(pw);
            Integer.parseInt(repw);
        } catch (Exception e) {
            return "redirect:/register?error=1";
        }

        if (!pw.equals(repw)) {
            return "redirect:/register?error=1";
        }

        UserDto dto = new UserDto();
        dto.setName(name);
        dto.setEmail(email);
        dto.setPassword(pw);
        dto.setRole("user");
        dto.setPhone(phone);

        int result = userDao.insertUser(dto);

        if (result > 0) {
            return "redirect:/login?register=1";
        } else {
            return "redirect:/register?error=1";
        }
    }

    // 마이페이지 이름, 비번 변경
    @PostMapping("/user/update-profile")
    @ResponseBody
    public String updateProfile(@RequestParam int num, @RequestParam String type, @RequestParam String value, HttpSession session) {
        // 보안 체크: 세션의 유저와 수정하려는 유저가 같은지 확인하면 더 좋습니다.
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null || loginNum != num) {
            return "error";
        }

        if ("name".equals(type)) {
            // 세션에 이름 저장 -> 같이 갱신
            value = value.trim();
            if (value.equals("")) {
                return "invalid";
            }

            int result = userDao.updateName(num, value);
            if (result > 0) {
                session.setAttribute("userName", value);
                return "success";
            }
            return "fail";

        } else if ("phone".equals(type)) {
            value = value.trim().replaceAll("[^0-9]", "");
            if (!isValidPhone(value)) {
                return "invalid";
            }
            int result = userDao.updatePhone(num, value);
            return result > 0 ? "success" : "fail";

        } else if ("password".equals(type)) {
            value = value.trim();

            // 5 ~ 8자리
            if (value.length() < 5 || value.length() > 8) {
                return "invalid";
            }

            try {
                Integer.parseInt(value);
            } catch (Exception e) {
                return "invalid";
            }

            int result = userDao.updatePassword(num, value);
            return result > 0 ? "success" : "fail";
        }

        return "fail";
    }

    // 알림 동의
    @GetMapping("/mypage_settings") // 혹은 설정 페이지 경로
    public String myPage(HttpSession session, Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        if (loginNum == null) {
            return "redirect:/login";
        }

        UserDto user = userDao.getUserInfo(loginNum);

        // 모델에 유저 정보를 담아서 보냄
        model.addAttribute("user", user);
        return "mypage";
    }

    // 마이페이지 - 회원탈퇴
    // UserController.java
    @PostMapping("/user/withdraw")
    public String withdraw(HttpSession session) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");

        // 세션 체크
        if (loginNum == null) {
            return "redirect:/login";
        }

        // DB 삭제 및 세션 제거
        userDao.deleteUser(loginNum);
        session.invalidate();


        // 3. 메인 화면이나 로그인 화면으로 이동
        return "redirect:/login?withdraw=1";
    }

    // UserController.java
    @GetMapping("/logout")
    public String logout(HttpSession session) {
        // 1. 현재 세션을 완전히 무효화 (안의 모든 데이터 삭제)
        session.invalidate();

        // 2. 로그아웃 후 로그인 페이지로 이동 (알림용 파라미터 추가 가능)
        return "redirect:/login?logout=1";
    }


    // 저장된 이메일 조회
    @GetMapping("/login/saved-email")
    @ResponseBody
    public Map<String, String> getSavedEmail(HttpServletRequest request) {
        Map<String, String> result = new HashMap<>();

        String token = getCookieValue(request, REMEMBER_EMAIL_COOKIE);
        String savedEmail = "";

        if (token != null && !token.trim().equals("")){
            String dbEmail = userDao.getSavedEmailByToken(token);
            if (dbEmail != null){
                savedEmail = dbEmail;
            }
        }

        result.put("savedEmail", savedEmail);
        return result;
    }

    // 저장된 이메일 삭제
    @DeleteMapping("/login/saved-email")
    @ResponseBody
    public Map<String, String> deleteSavedEmail(HttpServletRequest request, HttpServletResponse response) {
        removeSavedEmailCookie(request, response);

        Map<String, String> result = new HashMap<>();
        result.put("message", "saved email deleted");
        return result;
    }

    // 이메일 찾기
    @PostMapping("/findEmail")
    public String findEmail(@RequestParam(value = "userName", required = false) String name,
                            @RequestParam(value = "userPhone", required = false) String phone,
                            Model model) {
        if (name == null || phone == null) {
            model.addAttribute("errorMessage", "이름과 전화번호를 입력해주세요");
            return "findEmail";
        }

        name = name.trim();
        phone = phone.trim().replaceAll("[^0-9]", "");

        if (name.equals("") || phone.equals("")) {
            model.addAttribute("errorMessage", "이름과 전화번호를 입력해주세요");
            return "findEmail";
        }

        String email = userDao.findEmailByNameAndPhone(name, phone);

        if (email == null) {
            model.addAttribute("errorMessage", "일치하는 회원 정보가 없습니다");
        } else {
            model.addAttribute("maskedEmail", maskEmail(email));
        }

        return "findEmail";
    }

    // 비밀번호 찾기   // 본인 확인 후 바로 새 비밀번호 입력
    @PostMapping("/findPassword")
    public String findPassword(@RequestParam(value = "userName", required = false) String name,
                               @RequestParam(value = "userEmail", required = false) String email,
                               @RequestParam(value = "userPhone", required = false) String phone,
                               @RequestParam(value = "newPassword", required = false) String newPassword,
                               @RequestParam(value = "reNewPassword", required = false) String reNewPassword,
                               Model model) {
        if (name == null || email == null || phone == null || newPassword == null || reNewPassword == null) {
            model.addAttribute("errorMessage", "모든 값을 입력해주세요");
            return "findPassword";
        }

        name = name.trim();
        email = normalizeEmailDomain(email);
        phone = phone.trim().replaceAll("[^0-9]", "");
        newPassword = newPassword.trim();
        reNewPassword = reNewPassword.trim();

        if (name.equals("") || email.equals("") || phone.equals("") || newPassword.equals("") || reNewPassword.equals("")) {
            model.addAttribute("errorMessage", "모든 값을 입력해주세요");
            return "findPassword";
        }

        if (!isValidEmail(email)) {
            model.addAttribute("errorMessage", "이메일 형식이 올바르지 않습니다.");
            return "findPassword";
        }

        if (!isValidPhone(phone)) {
            model.addAttribute("errorMessage", "전화번호는 숫자 10~11자리로 입력해주세요.");
            return "findPassword";
        }

        if (!newPassword.equals(reNewPassword)) {
            model.addAttribute("errorMessage", "새 비밀번호가 일치하지 않습니다.");
            return "findPassword";
        }

        if (newPassword.length() < 5 || newPassword.length() > 8) {
            model.addAttribute("errorMessage", "비밀번호는 5자 이상 8자 이하로 입력해주세요.");
            return "findPassword";
        }

        try {
            Integer.parseInt(newPassword);
        } catch (Exception e) {
            model.addAttribute("errorMessage", "비밀번호는 숫자만 입력해주세요.");
            return "findPassword";
        }

        int result = userDao.resetPasswordByUserInfo(name, email, phone, newPassword);

        if (result > 0) {
            return "redirect:/login?reset=1";
        } else {
            model.addAttribute("errorMessage", "일치하는 회원 정보가 없거나 비밀번호 변경에 실패했습니다.");
            return "findPassword";
        }
    }

    // 이메일의 도메인 소문자
    private String normalizeEmailDomain(String email) {
        if (email == null) {
            return null;
        }

        String trimmedEmail = email.trim();
        int atIndex = trimmedEmail.indexOf("@");

        if (atIndex < 0 || atIndex == trimmedEmail.length() - 1) {
            return trimmedEmail;
        }

        String localPart = trimmedEmail.substring(0, atIndex);
        String domainPart = trimmedEmail.substring(atIndex + 1).toLowerCase();

        return localPart + "@" + domainPart;
    }

    private String maskEmail(String email) {
        if (email == null || email.trim().equals("") || !email.contains("@")) {
            return "";
        }

        String[] parts = email.split("@");
        String local = parts[0];    // ex. ya
        String domain = parts[1];   // ex. naver.com

        if (local.length() == 1) {
            return local + "***@" + domain;
        }

        if (local.length() == 2) {  // y***@naver.com
            return local.substring(0, 1) + "***@" + domain;
        }

        return local.substring(0, 3) + "***@" + domain;
    }

    private boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    private boolean isValidPhone(String phone) {
        return phone != null && PHONE_PATTERN.matcher(phone).matches();
    }


    private void removeSavedEmailCookie(HttpServletRequest request, HttpServletResponse response) {
        String token = getCookieValue(request, REMEMBER_EMAIL_COOKIE);

        if (token != null && !token.trim().equals("")) {
            userDao.deleteSavedEmailToken(token);
        }

        // 삭제용 쿠키 생성
        Cookie cookie = new Cookie(REMEMBER_EMAIL_COOKIE, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);   // 0 = 즉시 삭제
        response.addCookie(cookie);
    }

    // 쿠키 찾는
    private String getCookieValue(HttpServletRequest request, String cookieName) {
        Cookie[] cookies = request.getCookies();

        if (cookies == null) {
            return null;
        }

        for (Cookie cookie : cookies) {
            if (cookieName.equals(cookie.getName())) {
                return cookie.getValue(); // 값만 반환
            }
        }

        return null;  // 못 찾음
    }
}