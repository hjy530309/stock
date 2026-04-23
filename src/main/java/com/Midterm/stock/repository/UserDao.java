package com.Midterm.stock.repository;

import com.Midterm.stock.dto.UserDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

@Repository
public class UserDao {

    @Autowired
    private DataSource dataSource;

    public UserDao() {
        try {
            Class.forName("oracle.jdbc.OracleDriver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    public int insertUser(UserDto dto) {
        int cnt = -1;

        String sql = "INSERT INTO users (num, name, email, password, role, phone) "
                + "VALUES (user_seq.nextval, ?, ?, ?, ?, ?)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, dto.getName());
            pstmt.setString(2, dto.getEmail());
            pstmt.setString(3, dto.getPassword());
            pstmt.setString(4, dto.getRole());
            pstmt.setString(5, dto.getPhone());

            cnt = pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return cnt;
    }

    public UserDto login(String email, String password) {
        UserDto dto = null;

        String sql = "SELECT num, name, email, role, phone " +
                "FROM users " +
                "WHERE email = ? AND password = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, email);
            pstmt.setString(2, password);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    dto = new UserDto();
                    dto.setNum(rs.getInt("num"));
                    dto.setName(rs.getString("name"));
                    dto.setEmail(rs.getString("email"));
                    dto.setRole(rs.getString("role"));
                    dto.setPhone(rs.getString("phone"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return dto;
    }
/*
    public boolean loginCheck(String email, String password) {
        boolean result = false;

        // 로그인 여부 확인 -> select 1 사용 => 가져올 데이터가 줄어듦
        String sql = "SELECT 1 FROM users WHERE email = ? AND password = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, email);
            pstmt.setString(2, password);

            try (ResultSet rs = pstmt.executeQuery()) {
                result = rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }*/

    public boolean existsWithDifferentEmailCase(String email, String password) {
        boolean result = false;

        String sql = "SELECT email FROM users WHERE lower(email) = lower(?) AND password = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, email);
            pstmt.setString(2, password);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String dbEmail = rs.getString("email");
                    if (dbEmail != null && !dbEmail.equals(email)) {
                        result = true;
                        break;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    public String findEmailByNameAndPhone(String name, String phone) {
        String email = null;

        String sql = "SELECT email FROM users WHERE name = ? AND phone = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, name);
            pstmt.setString(2, phone);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    email = rs.getString("email");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return email;
    }

    public int resetPasswordByUserInfo(String name, String email, String phone, String newPassword) {
        int cnt = -1;

        String sql = "UPDATE users "
                + "SET password = ? "
                + "WHERE trim(name) = trim(?) "
                + "AND lower(trim(email)) = lower(trim(?)) "
                + "AND regexp_replace(phone, '[^0-9]', '') = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setQueryTimeout(5);
            pstmt.setString(1, newPassword);
            pstmt.setString(2, name);
            pstmt.setString(3, email);
            pstmt.setString(4, phone);

            cnt = pstmt.executeUpdate();
        } catch (SQLTimeoutException e) {
            e.printStackTrace();
            cnt = 0;
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return cnt;
    }

    public int insertSavedEmailToken(String token, String email) {
        int cnt = -1;

        String sql = "INSERT INTO saved_email_token (token, email, expires_at) "
                + "VALUES (?, ?, SYSTIMESTAMP + INTERVAL '7' DAY)";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, token);
            pstmt.setString(2, email);

            cnt = pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return cnt;
    }

    public String getSavedEmailByToken(String token) {
        String savedEmail = null;

        String sql = "SELECT email FROM saved_email_token "
                + "WHERE token = ? AND expires_at > SYSTIMESTAMP";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, token);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    savedEmail = rs.getString("email");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return savedEmail;
    }

    public int deleteSavedEmailToken(String token) {
        int cnt = -1;

        String sql = "DELETE FROM saved_email_token WHERE token = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, token);
            cnt = pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return cnt;
    }

    /*public UserDto getUserInfoByEmail(String email) {
        UserDto dto = null;

        String sql = "SELECT num, name, email, role, phone FROM users WHERE email = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, email);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    dto = new UserDto();
                    dto.setNum(rs.getInt("num"));
                    dto.setName(rs.getString("name"));
                    dto.setEmail(rs.getString("email"));
                    dto.setRole(rs.getString("role"));
                    dto.setPhone(rs.getString("phone"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return dto;
    }*/

    public UserDto getUserInfo(int num) {
        UserDto dto = null;

        String sql = "SELECT num, name, email, role, phone, notify_stock, notify_comment "
                + "FROM users WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, num);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    dto = new UserDto();
                    dto.setNum(rs.getInt("num"));
                    dto.setName(rs.getString("name"));
                    dto.setEmail(rs.getString("email"));
                    dto.setRole(rs.getString("role"));
                    dto.setPhone(rs.getString("phone"));
                    dto.setNotifyStock(rs.getInt("notify_stock"));
                    dto.setNotifyComment(rs.getInt("notify_comment"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return dto;
    }

    public int updateName(int num, String newName) {
        String sql = "UPDATE users SET name = ? WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newName);
            pstmt.setInt(2, num);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public int updatePassword(int num, String newPassword) {
        String sql = "UPDATE users SET password = ? WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newPassword);
            pstmt.setInt(2, num);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public int updatePhone(int num, String newPhone) {
        String sql = "UPDATE users SET phone = ? WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newPhone);
            pstmt.setInt(2, num);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public int deleteUser(int num) {
        String sql = "DELETE FROM users WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, num);
            return pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    public int findNotifyStockStatusByNum(int userNum) {
        String sql = "SELECT notify_stock FROM users WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userNum);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("notify_stock");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 0;
    }

    public int findNotifyCommentStatusByNum(int userNum) {
        String sql = "SELECT notify_comment FROM users WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, userNum);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("notify_comment");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 0;
    }

    public void updateNotifySetting(int userNum, String type, int status) {
        String columnName;

        // stock 아니면 무조건 comment로 보낸지 말고 잘못된 type는 예외 처리
        if ("stock".equals(type)) {
            columnName = "notify_stock";
        } else if ("comment".equals(type)) {
            columnName = "notify_comment";
        } else {
            throw new IllegalArgumentException("지원하지 않는 알림 타입입니다: " + type);
        }

        String sql = "UPDATE users SET " + columnName + " = ? WHERE num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, status);
            pstmt.setInt(2, userNum);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
}