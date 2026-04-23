package com.Midterm.stock.repository.community;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;

@Repository
public class CommunityTagDao {

    @Autowired
    private DataSource dataSource;


    // 寃뚯떆湲???낅젰???쒓렇 臾몄옄?댁쓣 ??ν빀?덈떎.
    // ?몃??먯꽌 而ㅻ꽖?섏쓣 吏곸젒 ?섍린吏 ?딅뒗 ?쇰컲 ?몄텧??硫붿꽌?쒖엯?덈떎.
    public void saveTags(int boardId, String tagNames) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            saveTags(conn, boardId, tagNames);
        }
    }

    // 寃뚯떆湲 ????섏젙 ?몃옖??뀡 ?덉뿉??媛숈? 而ㅻ꽖?섏쓣 怨듭쑀???쒓렇瑜???ν빀?덈떎.
    public void saveTags(Connection conn, int boardId, String tagNames) throws SQLException {
        Set<String> uniqueTags = parseTags(tagNames);

        if (uniqueTags.isEmpty()) {
            return;
        }

        for (String tagName : uniqueTags) {
            int tagId = findTagId(conn, tagName);
            if (tagId == 0) {
                tagId = insertTag(conn, tagName);
            }
            insertBoardTag(conn, boardId, tagId);
        }
    }

    // ?ъ슜?먭? ?낅젰???쒓렇 臾몄옄?댁쓣 以묐났 ?녿뒗 ?쒓렇 吏묓빀?쇰줈 ?뺣━?⑸땲??
    private Set<String> parseTags(String tagNames) {
        Set<String> tags = new LinkedHashSet<>();

        if (tagNames == null || tagNames.trim().isEmpty()) {
            return tags;
        }

        String normalized = tagNames.replace("#", " ");
        String[] split = normalized.split("[,\\s]+");

        for (String raw : split) {
            String tag = raw == null ? "" : raw.trim();
            if (!tag.isEmpty()) {
                tags.add(tag);
            }
        }

        return tags;
    }

    // ?쒓렇 ?대쫫?쇰줈 湲곗〈 ?쒓렇 ID瑜?李얠뒿?덈떎.
    private int findTagId(Connection conn, String tagName) throws SQLException {
        String sql = "select tag_id from community_tag where tag_name = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, tagName);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("tag_id");
                }
            }
        }

        return 0;
    }

    // ???쒓렇瑜?community_tag ?뚯씠釉붿뿉 ?깅줉?섍퀬 ?앹꽦??tag_id瑜?諛섑솚?⑸땲??
    private int insertTag(Connection conn, String tagName) throws SQLException {
        int tagId = 0;

        String seqSql = "select community_tag_seq.nextval from dual";
        try (PreparedStatement pstmt = conn.prepareStatement(seqSql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                tagId = rs.getInt(1);
            }
        }

        String insertSql = "insert into community_tag(tag_id, tag_name) values(?, ?)";
        try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
            pstmt.setInt(1, tagId);
            pstmt.setString(2, tagName);
            pstmt.executeUpdate();
        }

        return tagId;
    }

    // 寃뚯떆湲怨??쒓렇 ?곌껐 ?뺣낫媛 ?놁쓣 ?뚮쭔 community_board_tag??異붽??⑸땲??
    private void insertBoardTag(Connection conn, int boardId, int tagId) throws SQLException {
        String checkSql = "select count(*) from community_board_tag where board_id = ? and tag_id = ?";

        int count = 0;
        try (PreparedStatement pstmt = conn.prepareStatement(checkSql)) {
            pstmt.setInt(1, boardId);
            pstmt.setInt(2, tagId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        }

        if (count == 0) {
            String insertSql = "insert into community_board_tag(board_id, tag_id) values(?, ?)";
            try (PreparedStatement pstmt = conn.prepareStatement(insertSql)) {
                pstmt.setInt(1, boardId);
                pstmt.setInt(2, tagId);
                pstmt.executeUpdate();
            }
        }
    }

    // 寃뚯떆湲???곌껐???쒓렇瑜?紐⑤몢 ??젣?⑸땲??
    // ?쇰컲 ?몄텧??硫붿꽌?쒖엯?덈떎.
    public int deleteBoardTags(int boardId) {
        try (Connection conn = dataSource.getConnection()) {
            return deleteBoardTags(conn, boardId);
        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    // 寃뚯떆湲 ??젣/?섏젙 ?몃옖??뀡 ?덉뿉??媛숈? 而ㅻ꽖?섏쑝濡??쒓렇 ?곌껐????젣?⑸땲??
    public int deleteBoardTags(Connection conn, int boardId) throws SQLException {
        String sql = "delete from community_board_tag where board_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, boardId);
            return pstmt.executeUpdate();
        }
    }

    // 寃뚯떆湲 ?곸꽭 議고쉶 ???곌껐???쒓렇 ?대쫫?ㅼ쓣 臾몄옄?대줈 臾띠뼱??諛섑솚?⑸땲??
    public String getTagNamesByBoardId(int boardId) {
        StringBuilder tagNames = new StringBuilder();

        String sql = "select ct.tag_name "
                + "from community_board_tag cbt "
                + "join community_tag ct on cbt.tag_id = ct.tag_id "
                + "where cbt.board_id = ? "
                + "order by ct.tag_id";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, boardId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    if (tagNames.length() > 0) {
                        tagNames.append(", ");
                    }
                    tagNames.append(rs.getString("tag_name"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return tagNames.toString();
    }
}
