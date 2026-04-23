package com.Midterm.stock.repository.community;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Repository
public class CommunityLikeDao {

    @Autowired
    private DataSource dataSource;


    // ?뱀젙 ?ъ슜?먭? 寃뚯떆湲??醫뗭븘?붾? ?뚮??붿? ?뺤씤?⑸땲??
    public boolean existsLike(int board_id, int user_num) {
        String sql = "select count(*) from community_like where board_id = ? and user_num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, board_id);
            pstmt.setInt(2, user_num);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return false;
    }

    // 寃뚯떆湲????λ맂 ?꾩옱 醫뗭븘???섎? 議고쉶?⑸땲??
    public int getLikeCount(int board_id) {
        String sql = "select like_count from community_board where board_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, board_id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("like_count");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 0;
    }

    // 醫뗭븘??異붽?/痍⑥냼? 寃뚯떆湲 like_count 諛섏쁺???섎굹???몃옖??뀡?쇰줈 泥섎━?⑸땲??
    // 媛숈? ?ъ슜?먭? 媛숈? 湲???숈떆??醫뗭븘?붾? ?뚮윭??DB ?좊땲???쒖빟議곌굔??湲곗??쇰줈 以묐났??留됱뒿?덈떎.
    public Map<String, Object> toggleLike(int board_id, int user_num) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("liked", false);
        result.put("likeCount", 0);

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                boolean exists = existsLikeForUpdate(conn, board_id, user_num);

                if (exists) {
                    deleteLike(conn, board_id, user_num);
                    decreaseLikeCount(conn, board_id);
                    result.put("liked", false);
                } else {
                    try {
                        insertLike(conn, board_id, user_num);
                        increaseLikeCount(conn, board_id);
                        result.put("liked", true);
                    } catch (SQLException e) {
                        // ?숈떆 ?붿껌?쇰줈 ?대? 媛숈? 醫뗭븘?붽? 癒쇱? ?ㅼ뼱媛?寃쎌슦瑜?諛⑹뼱?⑸땲??
                        if (isUniqueConstraintViolation(e)) {
                            result.put("liked", true);
                        } else {
                            throw e;
                        }
                    }
                }

                result.put("likeCount", getLikeCount(conn, board_id));
                conn.commit();
                result.put("success", true);

            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    // ?꾩옱 ?몃옖??뀡 ?덉뿉??醫뗭븘??議댁옱 ?щ?瑜??뺤씤?⑸땲??
    private boolean existsLikeForUpdate(Connection conn, int board_id, int user_num) throws SQLException {
        String sql = "select count(*) from community_like where board_id = ? and user_num = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, board_id);
            pstmt.setInt(2, user_num);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    // 醫뗭븘???뚯씠釉붿뿉 醫뗭븘???됱쓣 異붽??⑸땲??
    private void insertLike(Connection conn, int board_id, int user_num) throws SQLException {
        String sql = "insert into community_like(like_id, board_id, user_num, created_at) "
                + "values(community_like_seq.nextval, ?, ?, sysdate)";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, board_id);
            pstmt.setInt(2, user_num);
            pstmt.executeUpdate();
        }
    }

    // 醫뗭븘???뚯씠釉붿뿉???대떦 ?ъ슜?먯쓽 醫뗭븘?붾? ??젣?⑸땲??
    private void deleteLike(Connection conn, int board_id, int user_num) throws SQLException {
        String sql = "delete from community_like where board_id = ? and user_num = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, board_id);
            pstmt.setInt(2, user_num);
            pstmt.executeUpdate();
        }
    }

    // 寃뚯떆湲??醫뗭븘???섎? 1 利앷??쒗궢?덈떎.
    private void increaseLikeCount(Connection conn, int board_id) throws SQLException {
        String sql = "update community_board set like_count = like_count + 1 where board_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, board_id);
            pstmt.executeUpdate();
        }
    }

    // 寃뚯떆湲??醫뗭븘???섎? 1 媛먯냼?쒗궎?? 0 ?꾨옒濡??대젮媛吏 ?딄쾶 ?⑸땲??
    private void decreaseLikeCount(Connection conn, int board_id) throws SQLException {
        String sql = "update community_board "
                + "set like_count = case when like_count > 0 then like_count - 1 else 0 end "
                + "where board_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, board_id);
            pstmt.executeUpdate();
        }
    }

    // ?꾩옱 ?몃옖??뀡 ?덉뿉??理쒖떊 醫뗭븘???섎? ?쎌뼱 諛섑솚?⑸땲??
    private int getLikeCount(Connection conn, int board_id) throws SQLException {
        String sql = "select like_count from community_board where board_id = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, board_id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("like_count");
                }
            }
        }

        return 0;
    }

    // Oracle ?좊땲???쒖빟議곌굔 ?꾨컲(ORA-00001)?몄? ?뺤씤?⑸땲??
    private boolean isUniqueConstraintViolation(SQLException e) {
        return e.getErrorCode() == 1;
    }
}
