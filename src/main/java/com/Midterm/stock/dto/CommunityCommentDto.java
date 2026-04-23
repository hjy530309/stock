package com.Midterm.stock.dto;

import java.sql.Timestamp;

public class CommunityCommentDto {
    private int comment_id;
    private int board_id;
    private int user_num;
    private String userName;
    private String userEmail;
    private String content;
    private Timestamp created_at;
    private Timestamp updated_at;

    /* 내가 작성한 게시글과 댓글을 확인할 수 있도록 */
    private String boardTitle;
    private int boardWriterUserNum;

    public int getComment_id() {
        return comment_id;
    }

    public void setComment_id(int comment_id) {
        this.comment_id = comment_id;
    }

    public int getBoard_id() {
        return board_id;
    }

    public void setBoard_id(int board_id) {
        this.board_id = board_id;
    }

    public int getUser_num() {
        return user_num;
    }

    public void setUser_num(int user_num) {
        this.user_num = user_num;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public String getDisplayName() {
        if (userEmail != null && userEmail.contains("@")) {
            return userEmail.substring(0, userEmail.indexOf('@'));
        }
        if (userName != null && !userName.isBlank()) {
            return userName;
        }
        return "";
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Timestamp getCreated_at() {
        return created_at;
    }

    public void setCreated_at(Timestamp created_at) {
        this.created_at = created_at;
    }

    public Timestamp getUpdated_at() {
        return updated_at;
    }

    public void setUpdated_at(Timestamp updated_at) {
        this.updated_at = updated_at;
    }

    public String getBoardTitle() {
        return boardTitle;
    }

    public void setBoardTitle(String boardTitle) {
        this.boardTitle = boardTitle;
    }

    public int getBoardWriterUserNum() {
        return boardWriterUserNum;
    }

    public void setBoardWriterUserNum(int boardWriterUserNum) {
        this.boardWriterUserNum = boardWriterUserNum;
    }
}
