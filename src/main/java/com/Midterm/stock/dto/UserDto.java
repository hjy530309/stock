package com.Midterm.stock.dto;

import java.sql.Timestamp;

public class UserDto {
    private int num;
    private String name;
    private String email;
    private String password;
    private String role;
    private String phone;
    private Integer notifyStock = 1; // 기본값은 1(알림 켬)로 설정
    private Integer notifyComment = 1; // 기본값은 1(알림 켬)로 설정

    public UserDto() {

    }

    public UserDto(int num, String name, String email, String password, String role, String phone, int notifyStock, int notifyComment) {

        super();
        this.num = num;
        this.name = name;
        this.email = email;
        this.password = password;
        this.role = role;
        this.phone = phone;
        this.notifyStock = notifyStock;
        this.notifyComment = notifyComment;

    }

    public int getNum() {
        return num;
    }
    public void setNum(int num) {
        this.num = num;
    }
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getEmail() {
        return email;
    }
    public void setEmail(String email) {
        this.email = email;
    }
    public String getPassword() {
        return password;
    }
    public void setPassword(String password) {
        this.password = password;
    }

    public String getRole() {
        return role;
    }
    public void setRole(String role) {
        this.role = role;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public Integer getNotifyStock() {
        return notifyStock;
    }

    public void setNotifyStock(Integer notifyStock) {
        this.notifyStock = notifyStock;
    }

    public Integer getNotifyComment() {
        return notifyComment;
    }

    public void setNotifyComment(Integer notifyComment) {
        this.notifyComment = notifyComment;
    }
}
