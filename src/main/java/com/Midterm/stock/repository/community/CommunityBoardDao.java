package com.Midterm.stock.repository.community;

import com.Midterm.stock.dto.CommunityDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
public class CommunityBoardDao {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private CommunityTagDao communityTagDao;


    public ArrayList<CommunityDto> getArticles(int start, int end) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select * from ( "
                + " select row_number() over(order by c.board_id desc) as rnum, "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + buildTagJoinSql("c")
                + " ) where rnum between ? and ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, start);
            pstmt.setInt(2, end);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public ArrayList<CommunityDto> getArticlesByCategory(String category, int start, int end) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select * from ( "
                + " select row_number() over(order by c.board_id desc) as rnum, "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + buildTagJoinSql("c")
                + " where c.category = ? "
                + " ) where rnum between ? and ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setInt(2, start);
            pstmt.setInt(3, end);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public ArrayList<CommunityDto> searchArticles(String keyword, int start, int end) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select * from ( "
                + " select row_number() over(order by c.board_id desc) as rnum, "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + buildTagJoinSql("c")
                + " where ( "
                + "     c.title like ? "
                + "     or c.content like ? "
                + "     or exists ( "
                + "         select 1 "
                + "         from community_board_tag cbt "
                + "         join community_tag ct on cbt.tag_id = ct.tag_id "
                + "         where cbt.board_id = c.board_id "
                + "         and ct.tag_name like ? "
                + "     ) "
                + " ) "
                + " ) where rnum between ? and ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, "%" + keyword + "%");
            pstmt.setString(2, "%" + keyword + "%");
            pstmt.setString(3, "%" + keyword + "%");
            pstmt.setInt(4, start);
            pstmt.setInt(5, end);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public ArrayList<CommunityDto> getArticlesByCategoryAndKeyword(String category, String keyword, int start, int end) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select * from ( "
                + " select row_number() over(order by c.board_id desc) as rnum, "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + buildTagJoinSql("c")
                + " where c.category = ? "
                + " and ( "
                + "     c.title like ? "
                + "     or c.content like ? "
                + "     or exists ( "
                + "         select 1 "
                + "         from community_board_tag cbt "
                + "         join community_tag ct on cbt.tag_id = ct.tag_id "
                + "         where cbt.board_id = c.board_id "
                + "         and ct.tag_name like ? "
                + "     ) "
                + " ) "
                + " ) where rnum between ? and ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setString(2, "%" + keyword + "%");
            pstmt.setString(3, "%" + keyword + "%");
            pstmt.setString(4, "%" + keyword + "%");
            pstmt.setInt(5, start);
            pstmt.setInt(6, end);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public int getArticleCount() {
        int count = 0;
        String sql = "select count(*) from community_board";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            if (rs.next()) {
                count = rs.getInt(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public ArrayList<CommunityDto> getPopularArticles(int start, int end) {
        return getPopularArticles(null, null, start, end);
    }

    public ArrayList<CommunityDto> searchPopularArticles(String keyword, int start, int end) {
        return getPopularArticles(null, keyword, start, end);
    }

    public int getPopularArticleCount() {
        return getPopularArticleCount(null, null);
    }

    public int getPopularArticleCountByKeyword(String keyword) {
        return getPopularArticleCount(null, keyword);
    }

    public ArrayList<CommunityDto> getPopularArticles(String themeName, String keyword, int start, int end) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        StringBuilder sql = new StringBuilder(
                "select * from ( "
                        + " select row_number() over(order by c.like_count desc, c.created_at desc, c.board_id desc) as rnum, "
                        + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                        + " c.category, c.title, c.news_link, c.content, "
                        + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                        + " nvl(tag_info.tag_names, '') as tag_names "
                        + " from community_board c "
                        + " join users u on c.user_num = u.num "
                        + buildTagJoinSql("c")
                        + " where c.like_count > 0 "
        );

        if (themeName != null && !themeName.isBlank()) {
            sql.append(" and c.category = ? ");
        }

        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and ( ");
            sql.append(" c.title like ? ");
            sql.append(" or c.content like ? ");
            sql.append(" or exists ( ");
            sql.append("     select 1 ");
            sql.append("     from community_board_tag cbt ");
            sql.append("     join community_tag ct on cbt.tag_id = ct.tag_id ");
            sql.append("     where cbt.board_id = c.board_id ");
            sql.append("     and ct.tag_name like ? ");
            sql.append(" ) ");
            sql.append(" ) ");
        }

        sql.append(" ) where rnum between ? and ? ");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {

            int idx = 1;

            if (themeName != null && !themeName.isBlank()) {
                pstmt.setString(idx++, themeName);
            }

            if (keyword != null && !keyword.isBlank()) {
                pstmt.setString(idx++, "%" + keyword + "%");
                pstmt.setString(idx++, "%" + keyword + "%");
                pstmt.setString(idx++, "%" + keyword + "%");
            }

            pstmt.setInt(idx++, start);
            pstmt.setInt(idx, end);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public int getPopularArticleCount(String themeName, String keyword) {
        int count = 0;

        StringBuilder sql = new StringBuilder(
                "select count(*) "
                        + "from community_board c "
                        + "where c.like_count > 0 "
        );

        if (themeName != null && !themeName.isBlank()) {
            sql.append(" and c.category = ? ");
        }

        if (keyword != null && !keyword.isBlank()) {
            sql.append(" and ( ");
            sql.append(" c.title like ? ");
            sql.append(" or c.content like ? ");
            sql.append(" or exists ( ");
            sql.append("     select 1 ");
            sql.append("     from community_board_tag cbt ");
            sql.append("     join community_tag ct on cbt.tag_id = ct.tag_id ");
            sql.append("     where cbt.board_id = c.board_id ");
            sql.append("     and ct.tag_name like ? ");
            sql.append(" ) ");
            sql.append(" ) ");
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql.toString())) {

            int idx = 1;

            if (themeName != null && !themeName.isBlank()) {
                pstmt.setString(idx++, themeName);
            }

            if (keyword != null && !keyword.isBlank()) {
                pstmt.setString(idx++, "%" + keyword + "%");
                pstmt.setString(idx++, "%" + keyword + "%");
                pstmt.setString(idx++, "%" + keyword + "%");
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public ArrayList<CommunityDto> getFeaturedArticles(int limit) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select * from ( "
                + " select "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + buildTagJoinSql("c")
                + " where c.like_count > 0 "
                + " order by c.like_count desc, c.created_at desc, c.board_id desc "
                + " ) where rownum <= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public int getArticleCountByCategory(String category) {
        int count = 0;
        String sql = "select count(*) from community_board where category = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public int getArticleCountByKeyword(String keyword) {
        int count = 0;

        String sql = "select count(*) "
                + "from community_board c "
                + "where (c.title like ? "
                + "or c.content like ? "
                + "or exists ( "
                + "    select 1 "
                + "    from community_board_tag cbt "
                + "    join community_tag ct on cbt.tag_id = ct.tag_id "
                + "    where cbt.board_id = c.board_id "
                + "    and ct.tag_name like ? "
                + "))";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, "%" + keyword + "%");
            pstmt.setString(2, "%" + keyword + "%");
            pstmt.setString(3, "%" + keyword + "%");

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public int getArticleCountByCategoryAndKeyword(String category, String keyword) {
        int count = 0;

        String sql = "select count(*) "
                + "from community_board c "
                + "where c.category = ? "
                + "and ( "
                + "    c.title like ? "
                + "    or c.content like ? "
                + "    or exists ( "
                + "        select 1 "
                + "        from community_board_tag cbt "
                + "        join community_tag ct on cbt.tag_id = ct.tag_id "
                + "        where cbt.board_id = c.board_id "
                + "        and ct.tag_name like ? "
                + "    ) "
                + ")";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setString(2, "%" + keyword + "%");
            pstmt.setString(3, "%" + keyword + "%");
            pstmt.setString(4, "%" + keyword + "%");

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    // 寃뚯떆湲怨??쒓렇瑜??섎굹???묒뾽?쇰줈 ???
    // 以묎컙???쒓렇 ??μ씠 ?ㅽ뙣?섎㈃ 寃뚯떆湲 ??λ룄 ?④퍡 濡ㅻ갚
    public int insertArticle(CommunityDto dto) {
        int count = -1;
        int boardId = 0;

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                String seqSql = "select community_board_seq.nextval from dual";
                try (PreparedStatement seqPstmt = conn.prepareStatement(seqSql);
                     ResultSet seqRs = seqPstmt.executeQuery()) {

                    if (seqRs.next()) {
                        boardId = seqRs.getInt(1);
                    }
                }

                String sql = "insert into community_board(board_id, user_num, category, title, content, news_link) "
                        + "values(?, ?, ?, ?, ?, ?)";

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, boardId);
                    pstmt.setInt(2, dto.getUser_num());
                    pstmt.setString(3, dto.getCategory());
                    pstmt.setString(4, dto.getTitle());
                    pstmt.setString(5, dto.getContent());
                    pstmt.setString(6, dto.getNews_link());

                    count = pstmt.executeUpdate();
                }

                if (count > 0) {
                    communityTagDao.saveTags(conn, boardId, dto.getTagNames());
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public CommunityDto getArticle(int board_id) {
        CommunityDto dto = null;

        String sql = "select c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + "c.category, c.title, c.content, c.news_link, c.view_count, c.like_count, c.created_at, c.updated_at "
                + "from community_board c "
                + "join users u on c.user_num = u.num "
                + "where c.board_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, board_id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    dto = new CommunityDto();
                    dto.setBoard_id(rs.getInt("board_id"));
                    dto.setUser_num(rs.getInt("user_num"));
                    dto.setUserName(rs.getString("user_name"));
                    dto.setUserEmail(rs.getString("user_email"));
                    dto.setCategory(rs.getString("category"));
                    dto.setTitle(rs.getString("title"));
                    dto.setNews_link(rs.getString("news_link"));
                    dto.setContent(rs.getString("content"));
                    dto.setView_count(rs.getInt("view_count"));
                    dto.setLike_count(rs.getInt("like_count"));
                    dto.setCreated_at(rs.getTimestamp("created_at"));
                    dto.setUpdated_at(rs.getTimestamp("updated_at"));
                    dto.setTagNames(communityTagDao.getTagNamesByBoardId(board_id));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return dto;
    }

    public void updateViewcount(int board_id) {
        String sql = "update community_board set view_count = view_count + 1 where board_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, board_id);
            pstmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // 寃뚯떆湲 蹂몃Ц ?섏젙怨??쒓렇 媛깆떊???섎굹???묒뾽?쇰줈 泥섎━
    // ?쒓렇 ??젣 ?먮뒗 ?ъ???以??ㅽ뙣?섎㈃ 寃뚯떆湲 ?섏젙??濡ㅻ갚
    public int updateArticle(CommunityDto dto, boolean refreshTags) {
        int count = -1;

        String sql = "update community_board "
                + "set category = ?, title = ?, content = ?, news_link = ?, updated_at = sysdate "
                + "where board_id = ?";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setString(1, dto.getCategory());
                    pstmt.setString(2, dto.getTitle());
                    pstmt.setString(3, dto.getContent());
                    pstmt.setString(4, dto.getNews_link());
                    pstmt.setInt(5, dto.getBoard_id());

                    count = pstmt.executeUpdate();
                }

                if (count > 0 && refreshTags) {
                    communityTagDao.deleteBoardTags(conn, dto.getBoard_id());
                    communityTagDao.saveTags(conn, dto.getBoard_id(), dto.getTagNames());
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    // 寃뚯떆湲 ??젣 ?꾩뿉 ?곌껐???쒓렇瑜?癒쇱? 吏?곌퀬,
    // ???묒뾽???섎굹???몃옖??뀡?쇰줈 臾띠뼱 以묎컙 ?곹깭媛 ?⑥? ?딄쾶
    public int deleteArticle(int board_id) {
        int count = -1;

        String sql = "delete from community_board where board_id = ?";

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            try {
                communityTagDao.deleteBoardTags(conn, board_id);

                try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                    pstmt.setInt(1, board_id);
                    count = pstmt.executeUpdate();
                }

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public int getArticleCountByUserNum(int user_num) {
        int count = 0;
        String sql = "select count(*) from community_board where user_num = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, user_num);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return count;
    }

    public ArrayList<CommunityDto> getArticlesByUserNum(int user_num) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(cc.comment_count, 0) as comment_count, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + " left join ( "
                + "     select board_id, count(*) as comment_count "
                + "     from community_comment "
                + "     group by board_id "
                + " ) cc on c.board_id = cc.board_id "
                + buildTagJoinSql("c")
                + " where c.user_num = ? "
                + " order by c.created_at desc";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, user_num);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public ArrayList<CommunityDto> getArticlesByUserNumAndCategory(int user_num, String category) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select "
                + " c.board_id, c.user_num, u.name as user_name, u.email as user_email, "
                + " c.category, c.title, c.news_link, c.content, "
                + " c.view_count, c.like_count, c.created_at, c.updated_at, "
                + " nvl(cc.comment_count, 0) as comment_count, "
                + " nvl(tag_info.tag_names, '') as tag_names "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + " left join ( "
                + "     select board_id, count(*) as comment_count "
                + "     from community_comment "
                + "     group by board_id "
                + " ) cc on c.board_id = cc.board_id "
                + buildTagJoinSql("c")
                + " where c.user_num = ? and c.category = ? "
                + " order by c.created_at desc";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, user_num);
            pstmt.setString(2, category);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public List<String> getUserActivityCategories(int user_num) {
        ArrayList<String> categories = new ArrayList<>();

        String sql = "select distinct category "
                + "from community_board "
                + "where user_num = ? "
                + "order by category";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, user_num);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    categories.add(rs.getString("category"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return categories;
    }

    public ArrayList<CommunityDto> getPopularSameCategoryArticles(String category, int currentBoardId, int limit) {
        ArrayList<CommunityDto> lists = new ArrayList<>();

        String sql = "select * from ( "
                + " select "
                + "     c.board_id, "
                + "     c.user_num, "
                + "     u.name as user_name, "
                + "     u.email as user_email, "
                + "     c.category, "
                + "     c.title, "
                + "     c.news_link, "
                + "     c.content, "
                + "     c.view_count, "
                + "     c.like_count, "
                + "     c.created_at, "
                + "     c.updated_at, "
                + "     nvl(tag_info.tag_names, '') as tag_names, "
                + "     nvl(cc.comment_count, 0) as comment_count "
                + " from community_board c "
                + " join users u on c.user_num = u.num "
                + " left join ( "
                + "     select board_id, count(*) as comment_count "
                + "     from community_comment "
                + "     group by board_id "
                + " ) cc on c.board_id = cc.board_id "
                + " left join ( "
                + "     select "
                + "         cbt.board_id, "
                + "         listagg(ct.tag_name, ', ') within group (order by ct.tag_id) as tag_names "
                + "     from community_board_tag cbt "
                + "     join community_tag ct on cbt.tag_id = ct.tag_id "
                + "     group by cbt.board_id "
                + " ) tag_info on c.board_id = tag_info.board_id "
                + " where c.category = ? "
                + "   and c.board_id <> ? "
                + " order by c.like_count desc, nvl(cc.comment_count, 0) desc, c.view_count desc, c.created_at desc "
                + " ) where rownum <= ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, category);
            pstmt.setInt(2, currentBoardId);
            pstmt.setInt(3, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CommunityDto dto = new CommunityDto();
                    fillBoardDto(dto, rs);
                    lists.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return lists;
    }

    public Integer getArticleOwnerNum(int board_id) {
        Integer ownerNum = null;
        String sql = "select user_num from community_board where board_id = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, board_id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ownerNum = rs.getInt("user_num");
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        return ownerNum;
    }

    private String buildTagJoinSql(String boardAlias) {
        return " left join ( "
                + "     select "
                + "         cbt.board_id, "
                + "         listagg(ct.tag_name, ', ') within group (order by ct.tag_id) as tag_names "
                + "     from community_board_tag cbt "
                + "     join community_tag ct on cbt.tag_id = ct.tag_id "
                + "     group by cbt.board_id "
                + " ) tag_info on " + boardAlias + ".board_id = tag_info.board_id ";
    }

    private void fillBoardDto(CommunityDto dto, ResultSet rs) throws SQLException {
        dto.setBoard_id(rs.getInt("board_id"));
        dto.setUser_num(rs.getInt("user_num"));
        dto.setUserName(rs.getString("user_name"));
        dto.setUserEmail(rs.getString("user_email"));
        dto.setCategory(rs.getString("category"));
        dto.setTitle(rs.getString("title"));
        dto.setNews_link(rs.getString("news_link"));
        dto.setContent(rs.getString("content"));
        dto.setView_count(rs.getInt("view_count"));
        dto.setLike_count(rs.getInt("like_count"));
        dto.setCreated_at(rs.getTimestamp("created_at"));
        dto.setUpdated_at(rs.getTimestamp("updated_at"));
        dto.setTagNames(rs.getString("tag_names"));

        try {
            dto.setComment_count(rs.getInt("comment_count"));
        } catch (SQLException ignore) {
        }
    }
}
