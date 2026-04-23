package com.Midterm.stock.repository;

import com.Midterm.stock.dto.NewsDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

@Repository
public class NewsDao {

    @Autowired
    private DataSource dataSource;

    private static final String TODAY_FILTER =
        "SUBSTR(pub_date, 1, 10) = TO_CHAR(SYSDATE, 'YYYY-MM-DD')";

    // 애플리케이션 DataSource를 통해 Oracle DB 연결을 생성한다.
    private Connection connect() {
        try { return dataSource.getConnection(); }
        catch (SQLException e) { System.err.println("NewsDao 연결 실패: " + e.getMessage()); return null; }
    }
    // ?????????????????????????????????????????????????????????????
    // ?뱀씪 AI 醫낇빀 遺꾩꽍
    // ?????????????????????????????????????????????????????????????
    // ?ㅻ뒛 ?섏쭛???댁뒪?ㅼ쓽 媛먯꽦/?좊ː??吏묎퀎 寃곌낵瑜?議고쉶?쒕떎.
    public Map<String, Object> getTodayAnalysis() {
        Map<String, Object> r = new HashMap<>();
        r.put("totalCount", 0); r.put("avgTypeProb", 0.0);
        r.put("avgClickbaitProb", 0.0); r.put("positiveCount", 0);
        r.put("negativeCount", 0); r.put("neutralCount", 0);

        String sql =
            "SELECT COUNT(*) as tc, ROUND(AVG(type_prob),1) as atp, " +
            "       ROUND(AVG(clickbait_prob),1) as acp, " +
            "       SUM(CASE WHEN sentiment='?몄옱' THEN 1 ELSE 0 END) as pos, " +
            "       SUM(CASE WHEN sentiment='?낆옱' THEN 1 ELSE 0 END) as neg, " +
            "       SUM(CASE WHEN sentiment='以묐┰' THEN 1 ELSE 0 END) as neu " +
            "FROM (SELECT type_prob,clickbait_prob,sentiment FROM NEWS_DATA WHERE " + TODAY_FILTER +
            "      UNION ALL " +
            "      SELECT type_prob,clickbait_prob,sentiment FROM NEWS_DATA_SEC WHERE " + TODAY_FILTER + ")";

        Connection conn = connect(); if (conn == null) return r;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                r.put("totalCount",      rs.getInt("tc"));
                r.put("avgTypeProb",     rs.getDouble("atp"));
                r.put("avgClickbaitProb",rs.getDouble("acp"));
                r.put("positiveCount",   rs.getInt("pos"));
                r.put("negativeCount",   rs.getInt("neg"));
                r.put("neutralCount",    rs.getInt("neu"));
            }
        } catch (SQLException e) { System.err.println("getTodayAnalysis: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return r;
    }

    // ?ㅻ뒛 ?댁뒪??留롮씠 ?깆옣??醫낅ぉ怨?媛먯꽦 ?붿빟???꾧킅?먯슜?쇰줈 議고쉶
    public List<Map<String, Object>> getTodayTickerCompanies() {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql =
            "SELECT category, COUNT(*) as cnt, " +
            "       SUM(CASE WHEN sentiment='?몄옱' THEN 1 ELSE 0 END) as pos, " +
            "       SUM(CASE WHEN sentiment='?낆옱' THEN 1 ELSE 0 END) as neg " +
            "FROM (SELECT category,sentiment FROM NEWS_DATA WHERE " + TODAY_FILTER +
            "      UNION ALL SELECT category,sentiment FROM NEWS_DATA_SEC WHERE " + TODAY_FILTER + ") " +
            "GROUP BY category ORDER BY cnt DESC";

        Connection conn = connect(); if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString("category");
                int pos = rs.getInt("pos"), neg = rs.getInt("neg");
                String cname = raw;
                int oi = raw.lastIndexOf('('), ci = raw.lastIndexOf(')');
                if (oi > 0 && ci > oi) cname = raw.substring(0, oi).trim();
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("category",    raw);
                item.put("companyName", cname);
                item.put("cnt",         rs.getInt("cnt"));
                item.put("positive",    pos);
                item.put("negative",    neg);
                item.put("sentiment",   pos >= neg ? "?몄옱" : "?낆옱");
                item.put("sentCount",   pos >= neg ? pos : neg);
                list.add(item);
            }
        } catch (SQLException e) { System.err.println("getTodayTickerCompanies: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return list;
    }

    // ?????????????????????????????????????????????????????????????
    // ?뱁꽣?덈꺼 吏묎퀎 (stock ?섏씠吏 移대뱶?? ??湲곗뾽紐??쒓굅, ?뱁꽣留?
    // ?????????????????????????????????????????????????????????????
    public LinkedHashMap<String, Map<String, Object>> getSectorLevelMap() {
        LinkedHashMap<String, Map<String, Object>> result = new LinkedHashMap<>();
        String sql =
            "SELECT sector_name, COUNT(*) as article_count, " +
            "       ROUND(AVG(type_prob),1) as avg_type_prob, " +
            "       ROUND(AVG(clickbait_prob),1) as avg_clickbait_prob, " +
            "       SUM(CASE WHEN sentiment='?몄옱' THEN 1 ELSE 0 END) as pos, " +
            "       SUM(CASE WHEN sentiment='?낆옱' THEN 1 ELSE 0 END) as neg, " +
            "       SUM(CASE WHEN sentiment='以묐┰' THEN 1 ELSE 0 END) as neu " +
            "FROM ( " +
            "    SELECT CASE WHEN INSTR(category,'(')>0 " +
            "                THEN TRIM(SUBSTR(category, INSTR(category,'(')+1, " +
            "                         INSTR(category,')')-INSTR(category,'(')-1)) " +
            "                ELSE TRIM(category) END as sector_name, " +
            "           sentiment, type_prob, clickbait_prob " +
            "    FROM NEWS_DATA WHERE " + TODAY_FILTER +
            "    UNION ALL " +
            "    SELECT CASE WHEN INSTR(category,'(')>0 " +
            "                THEN TRIM(SUBSTR(category, INSTR(category,'(')+1, " +
            "                         INSTR(category,')')-INSTR(category,'(')-1)) " +
            "                ELSE TRIM(category) END as sector_name, " +
            "           sentiment, type_prob, clickbait_prob " +
            "    FROM NEWS_DATA_SEC WHERE " + TODAY_FILTER +
            ") GROUP BY sector_name ORDER BY sector_name";
        Connection conn = connect(); if (conn == null) return result;
        try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String sector = rs.getString("sector_name");
                int pos = rs.getInt("pos"), neg = rs.getInt("neg"), neu = rs.getInt("neu");
                int total = rs.getInt("article_count");
                String dom = (pos>=neg && pos>=neu) ? "?몄옱" : (neg>=pos && neg>=neu) ? "?낆옱" : "以묐┰";
                int posRatio = total > 0 ? (int)Math.round((double)pos/total*100) : 0;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("articleCount",    total);
                m.put("avgTypeProb",     rs.getDouble("avg_type_prob"));
                m.put("avgClickbaitProb",rs.getDouble("avg_clickbait_prob"));
                m.put("positiveCount",   pos);
                m.put("negativeCount",   neg);
                m.put("neutralCount",    neu);
                m.put("dominant",        dom);
                m.put("posRatio",        posRatio);
                result.put(sector, m);
            }
        } catch (SQLException e) { System.err.println("getSectorLevelMap: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return result;
    }

    // ?????????????????????????????????????????????????????????????
    // ?댁뒪 紐⑸줉 (sector/keyword ?꾪꽣 + 醫뗭븘???볤? 珥덇린媛?
    // ?????????????????????????????????????????????????????????????
    public List<NewsDto> getNewsList(String sector, String keyword, int start, int end, int userNum) {
        List<NewsDto> list = new ArrayList<>();
        String where1 = buildWhere(sector, keyword, "1");
        String where2 = buildWhere(sector, keyword, "2");

        String sql =
            "SELECT * FROM ( " +
            "    SELECT ROWNUM as rnum, t.* FROM ( " +
            "        SELECT link, category, title, summary, sentiment, pub_date, " +
            "               clickbait_prob, article_type, type_prob " +
            "        FROM ( " +
            "            SELECT link,category,title,summary,sentiment,pub_date,clickbait_prob,article_type,type_prob " +
            "            FROM NEWS_DATA " + where1 +
            "            UNION ALL " +
            "            SELECT link,category,title,summary,sentiment,pub_date,clickbait_prob,article_type,type_prob " +
            "            FROM NEWS_DATA_SEC " + where2 +
            "        ) ORDER BY pub_date DESC " +
            "    ) t WHERE ROWNUM <= ? " +
            ") WHERE rnum >= ?";

        Connection conn = connect(); if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            idx = setWhereParams(ps, idx, sector, keyword);
            idx = setWhereParams(ps, idx, sector, keyword);
            ps.setInt(idx++, end);
            ps.setInt(idx,   start);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    NewsDto dto = new NewsDto();
                    dto.setLink(rs.getString("link"));
                    dto.setCategory(rs.getString("category"));
                    dto.setTitle(rs.getString("title"));
                    dto.setSummary(rs.getString("summary"));
                    dto.setSentiment(rs.getString("sentiment"));
                    dto.setPubDate(formatPubDate(rs, "pub_date"));
                    dto.setClickbaitProb(rs.getDouble("clickbait_prob"));
                    dto.setArticleType(rs.getString("article_type"));
                    dto.setTypeProb(rs.getDouble("type_prob"));
                    list.add(dto);
                }
            }
        } catch (SQLException e) { System.err.println("getNewsList: " + e.getMessage()); e.printStackTrace(); }
        finally { try { conn.close(); } catch (Exception ignore) {} }

        // 醫뗭븘???볤? ??諛곗튂 濡쒕뱶
        if (!list.isEmpty()) loadLikeCommentCounts(list, userNum);
        return list;
    }

    // ?댁뒪 留곹겕 紐⑸줉????묓븯??醫낅ぉ 留ㅽ븨怨???λ맂 ?곹뼢?꾨? ??踰덉뿉 議고쉶
    public Map<String, Map<String, Object>> getNewsSignalMap(List<String> links) {
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (links == null || links.isEmpty()) {
            return result;
        }

        List<String> uniqueLinks = new ArrayList<>(new LinkedHashSet<>(links));
        String placeholders = String.join(",", Collections.nCopies(uniqueLinks.size(), "?"));

        Connection conn = connect();
        if (conn == null) {
            return result;
        }

        String impactSql =
            "SELECT link, impact_30m FROM ( " +
            "    SELECT link, impact_30m, " +
            "           ROW_NUMBER() OVER (PARTITION BY link ORDER BY created_at DESC NULLS LAST, id DESC) AS rn " +
            "    FROM NEWS_IMPACT " +
            "    WHERE link IN (" + placeholders + ") " +
            ") WHERE rn = 1";

        try (PreparedStatement ps = conn.prepareStatement(impactSql)) {
            bindLinkParams(ps, uniqueLinks);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String link = rs.getString("link");
                    Map<String, Object> row = result.computeIfAbsent(link, key -> new HashMap<>());
                    double impact = rs.getDouble("impact_30m");
                    if (!rs.wasNull()) {
                        row.put("impact30m", impact);
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("getNewsSignalMap(impact): " + e.getMessage());
        } finally {
            try { conn.close(); } catch (Exception ignore) {}
        }

        return result;
    }

    // 湲곗궗 ?곹뼢???덉륫 寃곌낵瑜?NEWS_IMPACT ?뚯씠釉붿뿉 ?낅뜲?댄듃?섍굅???좉퇋 ???
    public void saveNewsImpact(String link, String stockCode, Double impact30m) {
        if (isBlank(link) || isBlank(stockCode) || impact30m == null) {
            return;
        }

        Connection conn = connect();
        if (conn == null) {
            return;
        }
        try {
            String updateSql =
                "UPDATE NEWS_IMPACT " +
                "SET stock_code=?, impact_30m=?, created_at=SYSTIMESTAMP " +
                "WHERE link=?";

            try (PreparedStatement updatePs = conn.prepareStatement(updateSql)) {
                updatePs.setString(1, stockCode);
                updatePs.setDouble(2, impact30m);
                updatePs.setString(3, link);
                int updated = updatePs.executeUpdate();
                if (updated > 0) {
                    return;
                }
            } catch (SQLException e) {
                System.err.println("saveNewsImpact(update): " + e.getMessage());
            }

            String insertNoIdSql =
                "INSERT INTO NEWS_IMPACT (link, stock_code, impact_30m, created_at) " +
                "VALUES (?, ?, ?, SYSTIMESTAMP)";

            try (PreparedStatement insertPs = conn.prepareStatement(insertNoIdSql)) {
                insertPs.setString(1, link);
                insertPs.setString(2, stockCode);
                insertPs.setDouble(3, impact30m);
                insertPs.executeUpdate();
                return;
            } catch (SQLException ignored) {
                // fall back to sequence insert for schemas without identity id
            }

            String insertWithSeqSql =
                "INSERT INTO NEWS_IMPACT (id, link, stock_code, impact_30m, created_at) " +
                "VALUES (NEWS_IMPACT_SEQ.NEXTVAL, ?, ?, ?, SYSTIMESTAMP)";

            try (PreparedStatement insertPs = conn.prepareStatement(insertWithSeqSql)) {
                insertPs.setString(1, link);
                insertPs.setString(2, stockCode);
                insertPs.setDouble(3, impact30m);
                insertPs.executeUpdate();
            } catch (SQLException e) {
                System.err.println("saveNewsImpact(insert): " + e.getMessage());
            }
        } finally {
            try { conn.close(); } catch (Exception ignore) {}
        }
    }

    /** 醫뗭븘?붋룸뙎湲 ?섎? ??踰덉뿉 濡쒕뱶?댁꽌 dto???명똿 */
    private void loadLikeCommentCounts(List<NewsDto> list, int userNum) {
        Connection conn = connect(); if (conn == null) return;
        try {
            for (NewsDto dto : list) {
                String link = dto.getLink();
                // 醫뗭븘??count + liked
                String likeSql = "SELECT COUNT(*) as total, " +
                    "SUM(CASE WHEN user_num=? THEN 1 ELSE 0 END) as mine FROM NEWS_LIKES WHERE news_link=?";
                try (PreparedStatement ps = conn.prepareStatement(likeSql)) {
                    ps.setInt(1, userNum); ps.setString(2, link);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) { dto.setLikeCount(rs.getInt("total")); dto.setLiked(rs.getInt("mine")>0); }
                    }
                }
                // ?볤? count
                String cmtSql = "SELECT COUNT(*) FROM NEWS_COMMENTS WHERE news_link=?";
                try (PreparedStatement ps = conn.prepareStatement(cmtSql)) {
                    ps.setString(1, link);
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) dto.setCommentCount(rs.getInt(1)); }
                }
            }
        } catch (SQLException e) { System.err.println("loadLikeCommentCounts: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
    }

    // ?????????????????????????????????????????????????????????????
    // ?댁뒪 珥?嫄댁닔
    // ?????????????????????????????????????????????????????????????
    public int getNewsCount(String sector, String keyword) {
        String where1 = buildWhere(sector, keyword, "1");
        String where2 = buildWhere(sector, keyword, "2");
        String sql = "SELECT COUNT(*) FROM (SELECT 1 FROM NEWS_DATA " + where1 +
                     " UNION ALL SELECT 1 FROM NEWS_DATA_SEC " + where2 + ")";
        Connection conn = connect(); if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            idx = setWhereParams(ps, idx, sector, keyword);
            setWhereParams(ps, idx, sector, keyword);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getInt(1); }
        } catch (SQLException e) { System.err.println("getNewsCount: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return 0;
    }

    // ?????????????????????????????????????????????????????????????
    // ?ъ씠?쒕컮???뱁꽣?믪쥌紐?留?(湲고? ?쒖쇅)
    // ?????????????????????????????????????????????????????????????
    public LinkedHashMap<String, List<String>> getSidebarSectorMap() {
        LinkedHashMap<String, List<String>> sectorMap = new LinkedHashMap<>();
        String sql = "SELECT DISTINCT category FROM " +
            "(SELECT category FROM NEWS_DATA UNION ALL SELECT category FROM NEWS_DATA_SEC) ORDER BY category";
        Connection conn = connect();
        if (conn == null) return sectorMap;
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String raw = rs.getString("category");
                String company = raw;
                String sector = null;
                int oi = raw.lastIndexOf('('), ci = raw.lastIndexOf(')');
                if (oi > 0 && ci > oi) {
                    company = raw.substring(0, oi).trim(); //news_data --> ?뚯궗  --> news_data_sec --> null
                    sector  = raw.substring(oi + 1, ci).trim();//news_date--> 吏꾩쭨 ?됲꽣 -- news_data_Sec -->吏꾩쭨 ?덉쓬
                }
                if (sector == null || sector.equals("湲고?")) continue; // 湲고? ?쒖쇅
                sectorMap.computeIfAbsent(sector, k -> new ArrayList<>()).add(company);
            }
        } catch (SQLException e) { System.err.println("getSidebarSectorMap: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return sectorMap;
    }

    // ?????????????????????????????????????????????????????????????
    // ?ъ씠?쒕컮 AI 遺꾩꽍 (?꾩옱 ?꾪꽣 湲곗?)
    // ?????????????????????????????????????????????????????????????
    public Map<String, Object> getSidebarAnalysis(String sector, String keyword) {
        Map<String, Object> r = new HashMap<>();
        r.put("totalCount", 0);
        r.put("avgTypeProb", 0.0);
        r.put("avgClickbaitProb", 0.0);
        r.put("positiveCount", 0);
        r.put("negativeCount", 0);

        String where1 = buildWhere(sector, keyword, "1");
        String where2 = buildWhere(sector, keyword, "2");
        String sql =
                "SELECT COUNT(*) as tc, ROUND(AVG(type_prob),1) as atp, " +
                        "       ROUND(AVG(clickbait_prob),1) as acp, " +
                        "       SUM(CASE WHEN sentiment='?몄옱' THEN 1 ELSE 0 END) as pos, " +
                        "       SUM(CASE WHEN sentiment='?낆옱' THEN 1 ELSE 0 END) as neg " +
                        "FROM (SELECT type_prob,clickbait_prob,sentiment FROM NEWS_DATA " + where1 +
                        "      UNION ALL SELECT type_prob,clickbait_prob,sentiment FROM NEWS_DATA_SEC " + where2 + ")";

        Connection conn = connect();
        if (conn == null) return r;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            idx = setWhereParams(ps, idx, sector, keyword);
            setWhereParams(ps, idx, sector, keyword);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    r.put("totalCount", rs.getInt("tc"));
                    r.put("avgTypeProb", rs.getDouble("atp"));
                    r.put("avgClickbaitProb", rs.getDouble("acp"));
                    r.put("positiveCount", rs.getInt("pos"));
                    r.put("negativeCount", rs.getInt("neg"));
                }
            }
        } catch (SQLException e) {
            System.err.println("getSidebarAnalysis: " + e.getMessage());
        } finally {
            try {
                conn.close();
            } catch (Exception ignore) {
            }
        }

        return r;
    }

    // ?????????????????????????????????????????????????????????????
    // 醫뗭븘???좉?
    // ?????????????????????????????????????????????????????????????
    public int toggleLike(String newsLink, int userNum) {
        Connection conn = connect(); if (conn == null) return 0;
        try {
            String chk = "SELECT COUNT(*) FROM NEWS_LIKES WHERE news_link=? AND user_num=?";
            try (PreparedStatement ps = conn.prepareStatement(chk)) {
                ps.setString(1, newsLink); ps.setInt(2, userNum);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        try (PreparedStatement d = conn.prepareStatement(
                                "DELETE FROM NEWS_LIKES WHERE news_link=? AND user_num=?")) {
                            d.setString(1, newsLink); d.setInt(2, userNum); d.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement i = conn.prepareStatement(
                                "INSERT INTO NEWS_LIKES(like_id,news_link,user_num) VALUES(NEWS_LIKES_SEQ.NEXTVAL,?,?)")) {
                            i.setString(1, newsLink); i.setInt(2, userNum); i.executeUpdate();
                        }
                    }
                }
            }
            return getLikeCountInternal(conn, newsLink);
        } catch (SQLException e) { System.err.println("toggleLike: " + e.getMessage()); return 0; }
        finally { try { conn.close(); } catch (Exception ignore) {} }
    }

    private int getLikeCountInternal(Connection conn, String link) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM NEWS_LIKES WHERE news_link=?")) {
            ps.setString(1, link);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    // ?뱀젙 ?댁뒪??醫뗭븘???섏? ?꾩옱 ?ъ슜??醫뗭븘???щ?瑜?議고쉶
    public Map<String, Object> getLikeInfo(String newsLink, int userNum) {
        Map<String, Object> r = new HashMap<>();
        r.put("count", 0); r.put("liked", false);
        Connection conn = connect(); if (conn == null) return r;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) as total, SUM(CASE WHEN user_num=? THEN 1 ELSE 0 END) as mine FROM NEWS_LIKES WHERE news_link=?")) {
            ps.setInt(1, userNum); ps.setString(2, newsLink);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) { r.put("count", rs.getInt("total"));
                    r.put("liked", rs.getInt("mine")>0); }
            }
        } catch (SQLException e) { System.err.println("getLikeInfo: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return r;
    }

    // ?????????????????????????????????????????????????????????????
    // ?볤?
    // ?????????????????????????????????????????????????????????????
    public List<Map<String, Object>> getComments(String newsLink) {
        List<Map<String, Object>> list = new ArrayList<>();
        String sql = "SELECT c.comment_id, c.user_num, u.name as user_name, c.content, " +
                     "TO_CHAR(c.created_at,'YYYY-MM-DD HH24:MI') as created_at " +
                     "FROM NEWS_COMMENTS c LEFT JOIN users u ON c.user_num=u.num " +
                     "WHERE c.news_link=? ORDER BY c.created_at ASC";
        Connection conn = connect(); if (conn == null) return list;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, newsLink);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("commentId", rs.getInt("comment_id"));
                    row.put("userNum",   rs.getInt("user_num"));
                    row.put("userName",  rs.getString("user_name"));
                    row.put("content",   rs.getString("content"));
                    row.put("createdAt", rs.getString("created_at"));
                    list.add(row);
                }
            }
        } catch (SQLException e) { System.err.println("getComments: " + e.getMessage()); }
        finally { try { conn.close(); } catch (Exception ignore) {} }
        return list;
    }

    // ?댁뒪 ?볤??????
    public int insertComment(String link, int userNum, String content) {
        Connection conn = connect(); if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO NEWS_COMMENTS(comment_id,news_link,user_num,content) VALUES(NEWS_COMMENTS_SEQ.NEXTVAL,?,?,?)")) {
            ps.setString(1, link); ps.setInt(2, userNum); ps.setString(3, content);
            return ps.executeUpdate();
        } catch (SQLException e) { System.err.println("insertComment: " + e.getMessage()); return 0; }
        finally { try { conn.close(); } catch (Exception ignore) {} }
    }

    // 蹂몄씤 ?볤?留???젣?섎룄濡?議곌굔??嫄몄뼱 ??젣
    public int deleteComment(int commentId, int userNum) {
        Connection conn = connect(); if (conn == null) return 0;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM NEWS_COMMENTS WHERE comment_id=? AND user_num=?")) {
            ps.setInt(1, commentId); ps.setInt(2, userNum); return ps.executeUpdate();
        } catch (SQLException e) { System.err.println("deleteComment: " + e.getMessage()); return 0; }
        finally { try { conn.close(); } catch (Exception ignore) {} }
    }

    // ?????????????????????????????????????????????????????????????
    // ?대? ?ы띁
    // ?????????????????????????????????????????????????????????????
    /** sector = ?뱁꽣 ?꾪꽣("IT/?뚮옯??), keyword = ?쒕ぉ ?ㅼ썙?? tableAlias 誘몄궗??*/
    private String buildWhere(String sector, String keyword, String alias) {
        List<String> conditions = new ArrayList<>();

        // 1. ?뱁꽣 議곌굔 (愿꾪샇 異붿텧 濡쒖쭅 ?곸슜)
        if (sector != null && !sector.trim().isEmpty()) {
            // category ?꾩껜?먯꽌 李얜뒗寃??꾨땲??異붿텧??sector_name怨??뺥솗???쇱튂?섎뒗吏 ?뺤씤
            conditions.add("(CASE WHEN INSTR(category,'(')>0 " +
                    "THEN TRIM(SUBSTR(category, INSTR(category,'(')+1, INSTR(category,')')-INSTR(category,'(')-1)) " +
                    "ELSE TRIM(category) END) = ?");
        }

        // 2. ?ㅼ썙??議곌굔
        if (keyword != null && !keyword.trim().isEmpty()) {
            conditions.add("title LIKE ?");
        }

        return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
    }

    private int setWhereParams(PreparedStatement ps, int idx, String sector, String keyword) throws SQLException {
        if (sector != null && !sector.trim().isEmpty()) {
            ps.setString(idx++, sector); // LIKE媛 ?꾨땲硫?%瑜?遺숈씠吏 ?딆뒿?덈떎.
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            ps.setString(idx++, "%" + keyword + "%");
        }
        return idx;
    }

    private void bindLinkParams(PreparedStatement ps, List<String> links) throws SQLException {
        for (int i = 0; i < links.size(); i++) {
            ps.setString(i + 1, links.get(i));
        }
    }

    // DB?먯꽌 ?쎌? 諛쒗뻾?쇱쓣 ?붾㈃ ?쒖떆??臾몄옄?대줈 ?뺣━
    private String formatPubDate(ResultSet rs, String col) {
        try {
            String raw = rs.getString(col);
            if (raw != null && raw.length() >= 16) return raw.substring(0, 16);
            return raw != null ? raw : "";
        } catch (SQLException e) {
            try {
                Timestamp ts = rs.getTimestamp(col);
                if (ts != null) return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(ts);
            } catch (SQLException ignore) {}
            return "";
        }
    }

    // 臾몄옄?댁씠 鍮꾩뼱 ?덈뒗吏 怨듯넻?쇰줈 ?뺤씤
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
