package com.Midterm.stock.controller;

import com.Midterm.stock.dto.CommunityCommentDto;
import com.Midterm.stock.dto.CommunityDto;
import com.Midterm.stock.dto.StockResponseDto;
import com.Midterm.stock.dto.UserDto;
import com.Midterm.stock.entity.StockAlert;
import com.Midterm.stock.repository.StockAlertRepository;
import com.Midterm.stock.repository.community.CommunityBoardDao;
import com.Midterm.stock.repository.community.CommunityCommentDao;
import com.Midterm.stock.repository.community.CommunityExtraDao;
import com.Midterm.stock.repository.community.CommunityLikeDao;
import com.Midterm.stock.repository.UserDao;
import com.Midterm.stock.service.WatchListService;
import com.Midterm.stock.service.stock.StockPriceService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Controller
@RequestMapping("/community")
public class CommunityController {

    private static final Map<String, ThemeStockSeed> THEME_STOCKS = new LinkedHashMap<>();

    static {
        THEME_STOCKS.put("반도체·AI", new ThemeStockSeed("005930", "삼성전자"));
        THEME_STOCKS.put("2차전지", new ThemeStockSeed("373220", "LG에너지솔루션"));
        THEME_STOCKS.put("제약·바이오", new ThemeStockSeed("207940", "삼성바이오로직스"));
        THEME_STOCKS.put("금융·밸류업", new ThemeStockSeed("105560", "KB금융"));
        THEME_STOCKS.put("방산·우주항공", new ThemeStockSeed("012450", "한화에어로스페이스"));
        THEME_STOCKS.put("IT·플랫폼", new ThemeStockSeed("035420", "NAVER"));
        THEME_STOCKS.put("엔터·미디어", new ThemeStockSeed("352820", "하이브"));
        THEME_STOCKS.put("자동차·모빌리티", new ThemeStockSeed("005380", "현대차"));
    }

    @Autowired
    private CommunityBoardDao communityBoardDao;

    @Autowired
    private CommunityCommentDao communityCommentDao;

    @Autowired
    private CommunityLikeDao communityLikeDao;

    @Autowired
    private CommunityExtraDao communityExtraDao;

    @Autowired
    private UserDao userDao;

    @Autowired
    private StockAlertRepository stockAlertRepository;

    @Autowired
    private WatchListService watchListService;

    @Autowired
    private StockPriceService stockPriceService;

    @GetMapping({"", "/"})
    public String communityList(@RequestParam(value = "page", defaultValue = "1") int page,
                                @RequestParam(value = "category", required = false) String category,
                                @RequestParam(value = "keyword", required = false) String keyword,
                                @RequestParam(value = "theme", required = false) String theme,
                                Model model,
                                HttpSession session) {
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null) {
            return "redirect:/login";
        }

        String categoryParam = normalize(category);
        String themeParam = normalize(theme);
        String searchKeyword = normalize(keyword);
        if (searchKeyword != null && searchKeyword.startsWith("#")) {
            searchKeyword = normalize(searchKeyword.substring(1));
        }

        boolean isPopularView = "popular".equals(categoryParam);
        String categoryName = convertCategoryParamToName(categoryParam);
        String themeName = convertCategoryParamToName(themeParam);
        boolean hasCategory = categoryName != null && !categoryName.isBlank() && !"전체".equals(categoryName) && !isPopularView;
        boolean hasKeyword = searchKeyword != null;

        boolean isDefaultBoardView = !isPopularView && !hasCategory && !hasKeyword;

        final int firstPageRegularCount = isDefaultBoardView ? 2 : 4;
        final int otherPageRegularCount = 4;

        int start, end;

        if (page <= 1) {
            page = 1;
            start = 1;
            end = firstPageRegularCount;
        } else {
            start = firstPageRegularCount + ((page - 2) * otherPageRegularCount) + 1;
            end = start + otherPageRegularCount - 1;
        }

        ArrayList<CommunityDto> lists;
        int totalCount;

        if (isPopularView) {
            lists = communityBoardDao.getPopularArticles(themeName, searchKeyword, start, end);
            totalCount = communityBoardDao.getPopularArticleCount(themeName, searchKeyword);
        } else if (hasCategory && hasKeyword) {
            lists = communityBoardDao.getArticlesByCategoryAndKeyword(categoryName, searchKeyword, start, end);
            totalCount = communityBoardDao.getArticleCountByCategoryAndKeyword(categoryName, searchKeyword);
        } else if (hasCategory) {
            lists = communityBoardDao.getArticlesByCategory(categoryName, start, end);
            totalCount = communityBoardDao.getArticleCountByCategory(categoryName);
        } else if (hasKeyword) {
            lists = communityBoardDao.searchArticles(searchKeyword, start, end);
            totalCount = communityBoardDao.getArticleCountByKeyword(searchKeyword);
        } else {
            lists = communityBoardDao.getArticles(start, end);
            totalCount = communityBoardDao.getArticleCount();
        }

        int totalPages;

        if (totalCount <= firstPageRegularCount) {
            totalPages = 1;
        } else {
            totalPages = 1 + (int) Math.ceil((double) (totalCount - firstPageRegularCount) / otherPageRegularCount);
        }

        page = Math.min(page, totalPages);

        ArrayList<Map<String, Object>> popularCategories = communityExtraDao.getPopularThemeCategories();
        for (Map<String, Object> item : popularCategories) {
            String categoryNameFromDb = stringValue(item.get("category"));
            item.put("categoryKey", convertCategoryNameToParam(categoryNameFromDb));
        }

        List<Map<String, Object>> popularPriceItems = buildPopularPriceItems(popularCategories);
        ArrayList<CommunityDto> featuredPosts = (page == 1 && isDefaultBoardView)
                ? communityBoardDao.getFeaturedArticles(2)
                : new ArrayList<>();

        model.addAttribute("lists", lists);
        model.addAttribute("selectedCategory", categoryParam);
        model.addAttribute("pageTitle", resolvePageTitle(categoryName, isPopularView, themeName, searchKeyword));
        model.addAttribute("currentPage", "community");
        model.addAttribute("keyword", searchKeyword);
        model.addAttribute("theme", themeParam);
        model.addAttribute("page", page);
        model.addAttribute("currentPageNum", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("popularCategories", popularCategories);
        model.addAttribute("popularPriceItems", popularPriceItems);
        model.addAttribute("featuredPosts", featuredPosts);
        model.addAttribute("isPopularView", isPopularView);

        return "community/list";
    }

    @GetMapping("/insert")
    public String insertForm(HttpSession session, Model model) {
        CommunityDto dto = new CommunityDto();

        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        dto.setUser_num(loginNum);
        model.addAttribute("currentPage", "community");
        model.addAttribute("dto", dto);
        return "community/insert";
    }

    @GetMapping("/news/search")
    @ResponseBody
    public ArrayList<Map<String, String>> searchRelatedNews(@RequestParam("keyword") String keyword,
                                                            HttpSession session) {
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null) {
            return new ArrayList<>();
        }

        String trimmedKeyword = keyword == null ? "" : keyword.trim();
        if (trimmedKeyword.length() < 2) {
            return new ArrayList<>();
        }

        return communityExtraDao.searchRelatedNews(trimmedKeyword);
    }

    @PostMapping("/insert")
    public String insertProc(CommunityDto dto, HttpSession session, Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        dto.setUser_num(loginNum);
        if (dto.getTagNames() != null) {
            dto.setTagNames(dto.getTagNames().trim());
        }
        if (dto.getNews_link() != null) {
            dto.setNews_link(dto.getNews_link().trim());
        }

        String bannedWord = findBannedWord(dto.getTitle(), dto.getContent(), dto.getTagNames());

        if (bannedWord != null) {
            model.addAttribute("currentPage", "community");
            model.addAttribute("dto", dto);
            model.addAttribute("errorMessage", "금지어가 포함되어 있습니다: " + bannedWord);
            return "community/insert";
        }

        int result = communityBoardDao.insertArticle(dto);
        if (result > 0) {
            return "redirect:/community";
        }

        model.addAttribute("currentPage", "community");
        model.addAttribute("dto", dto);
        model.addAttribute("errorMessage", "게시글 저장 중 오류가 발생했습니다.");
        return "community/insert";
    }

    @GetMapping("/detail")
    public String detailProc(@RequestParam("board_id") int boardId,
                             HttpSession session,
                             Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        communityBoardDao.updateViewcount(boardId);
        CommunityDto dto = communityBoardDao.getArticle(boardId);
        if (dto == null) {
            return "redirect:/community";
        }

        int userArticleCount = communityBoardDao.getArticleCountByUserNum(dto.getUser_num());
        int userCommentCount = communityCommentDao.getCommentCountByUserNum(dto.getUser_num());
        boolean isOwner = dto.getUser_num() == loginNum;

        String relatedNewsTitle = resolveSelectedNewsTitle(dto);

        boolean likedByMe = communityLikeDao.existsLike(boardId, loginNum);
        ArrayList<CommunityCommentDto> comments = communityCommentDao.getCommentsByBoardId(boardId);
        ArrayList<CommunityDto> popularSameCategoryPosts =
                communityBoardDao.getPopularSameCategoryArticles(dto.getCategory(), dto.getBoard_id(), 3);

        model.addAttribute("dto", dto);
        model.addAttribute("likedByMe", likedByMe);
        model.addAttribute("comments", comments);
        model.addAttribute("commentCount", comments.size());
        model.addAttribute("isOwner", isOwner);
        model.addAttribute("currentPage", "community");
        model.addAttribute("userArticleCount", userArticleCount);
        model.addAttribute("userCommentCount", userCommentCount);
        model.addAttribute("relatedNewsTitle", relatedNewsTitle);
        model.addAttribute("popularSameCategoryPosts", popularSameCategoryPosts);

        return "community/detail";
    }

    @PostMapping("/comment/insert")
    public String insertComment(@RequestParam("board_id") int boardId,
                                @RequestParam("content") String content,
                                HttpSession session) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        CommunityDto article = communityBoardDao.getArticle(boardId);
        if (article == null) {
            return "redirect:/community";
        }

        String trimmedContent = content == null ? "" : content.trim();
        if (!trimmedContent.isEmpty()) {
            int result = communityCommentDao.insertComment(boardId, loginNum, trimmedContent);
            if (result > 0 && isOwnArticleComment(article, loginNum)) {
                return "redirect:/community/detail?board_id=" + boardId;
            }

            if (result > 0) {
                boolean isCommentAlertEnabled = watchListService.isCommentNotifyEnabled(article.getUser_num());
                System.out.println("댓글 알림 체크 - 작성자: " + article.getUser_num() + " | 상태: " + isCommentAlertEnabled);

                if (isCommentAlertEnabled) {
                    String commenterName = loginUser.contains("@")
                            ? loginUser.substring(0, loginUser.indexOf('@'))
                            : loginUser;

                    StockAlert alert = new StockAlert();
                    alert.setUserNum(article.getUser_num());
                    alert.setStockCode(String.valueOf(boardId));
                    alert.setStockName(article.getTitle());
                    alert.setChangeRate(commenterName);
                    alert.setAlertType("댓글");
                    alert.setPrice("0");
                    alert.setAlertRead(false);
                    stockAlertRepository.save(alert);
                    System.out.println(">>> 댓글 알림이 성공적으로 생성되었습니다.");
                } else {
                    System.out.println(">>> 작성자가 댓글 알림을 꺼두어 알림을 생성하지 않습니다.");
                }
            }
        }

        return "redirect:/community/detail?board_id=" + boardId;
    }

    @PostMapping("/comment/update")
    public String updateComment(@RequestParam("comment_id") int commentId,
                                @RequestParam("content") String content,
                                HttpSession session) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");

        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        CommunityCommentDto comment = communityCommentDao.getComment(commentId);

        if (comment == null) {
            return "redirect:/community";
        }

        if (comment.getUser_num() != loginNum) {
            return "redirect:/community/detail?board_id=" + comment.getBoard_id();
        }

        String trimmedContent = content == null ? "" : content.trim();
        if (trimmedContent.isEmpty()) {
            return "redirect:/community/detail?board_id=" + comment.getBoard_id();
        }

        communityCommentDao.updateComment(commentId, trimmedContent);
        return "redirect:/community/detail?board_id=" + comment.getBoard_id();
    }

    @PostMapping("/comment/delete")
    public String deleteComment(@RequestParam("comment_id") int commentId,
                                @RequestParam(value = "returnUrl", required = false) String returnUrl,
                                HttpSession session) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");

        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        CommunityCommentDto comment = communityCommentDao.getComment(commentId);
        if (comment == null) {
            return "redirect:/community";
        }

        CommunityDto board = communityBoardDao.getArticle(comment.getBoard_id());
        if (board == null) {
            return "redirect:/community";
        }

        boolean isCommentWriter = comment.getUser_num() == loginNum;
        boolean isBoardOwner = board.getUser_num() == loginNum;

        if (returnUrl != null && returnUrl.contains("/community/activity") && returnUrl.contains("tab=replies")) {
            if (!isBoardOwner) {
                return "redirect:/community";
            }
        } else {
            if (!isCommentWriter && !isBoardOwner) {
                return "redirect:/community/detail?board_id=" + comment.getBoard_id();
            }
        }

        int result = communityCommentDao.deleteComment(commentId);
        if (result <= 0) {
            return "redirect:/community/detail?board_id=" + comment.getBoard_id() + "&deleteError=1";
        }

        if (returnUrl != null && !returnUrl.isBlank()) {
            return "redirect:" + returnUrl;
        }

        return "redirect:/community/detail?board_id=" + comment.getBoard_id();
    }

    // 로그인 사용자의 게시글 좋아요를 토글하고,
    // 처리 결과를 JSON 형태로 프론트에 반환합니다.
    @PostMapping("/like")
    @ResponseBody
    public Map<String, Object> likeArticle(@RequestParam("board_id") int boardId,
                                           HttpSession session) {
        Map<String, Object> result = new HashMap<>();

        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            result.put("success", false);
            result.put("message", "로그인이 필요합니다.");
            return result;
        }

        return communityLikeDao.toggleLike(boardId, loginNum);
    }

    @PostMapping("/delete")
    public String deleteProc(@RequestParam("board_id") int boardId,
                             HttpSession session) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        Integer articleOwnerNum = communityBoardDao.getArticleOwnerNum(boardId);
        if (articleOwnerNum == null) {
            return "redirect:/community";
        }

        if (!articleOwnerNum.equals(loginNum)) {
            return "redirect:/community/detail?board_id=" + boardId;
        }

        int result = communityBoardDao.deleteArticle(boardId);

        if (result <= 0) {
            return "redirect:/community/detail?board_id=" + boardId + "&deleteError=1";
        }

        return "redirect:/community";
    }

    @GetMapping("/update")
    public String updateForm(@RequestParam("board_id") int boardId,
                             HttpSession session,
                             Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        CommunityDto dto = communityBoardDao.getArticle(boardId);
        if (dto == null) {
            return "redirect:/community";
        }
        if (dto.getUser_num() != loginNum) {
            return "redirect:/community/detail?board_id=" + boardId;
        }

        // 수정 입력창에서는 사용자가 입력하던 형식과 맞게 #태그 형태로 보여줍니다.
        if (dto.getTagNames() != null && !dto.getTagNames().isBlank()) {
            dto.setTagNames(formatTagsForInput(dto.getTagNames()));
        }

        model.addAttribute("dto", dto);
        addSelectedNewsTitle(model, dto);
        model.addAttribute("currentPage", "community");
        return "community/update";
    }

    @PostMapping("/update")
    public String updateProc(CommunityDto dto, HttpSession session, Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");
        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        CommunityDto origin = communityBoardDao.getArticle(dto.getBoard_id());
        if (origin == null) {
            return "redirect:/community";
        }
        if (origin.getUser_num() != loginNum) {
            return "redirect:/community/detail?board_id=" + dto.getBoard_id();
        }

        if (dto.getTagNames() != null) {
            dto.setTagNames(dto.getTagNames().trim());
        }
        if (dto.getNews_link() != null) {
            dto.setNews_link(dto.getNews_link().trim());
        }

        String bannedWord = findBannedWord(dto.getTitle(), dto.getContent(), dto.getTagNames());

        if (bannedWord != null) {
            model.addAttribute("dto", dto);
            addSelectedNewsTitle(model, dto);
            model.addAttribute("currentPage", "community");
            model.addAttribute("errorMessage", "금지어가 포함되어 있습니다: " + bannedWord);
            return "community/update";
        }

        boolean refreshTags = !hasSameTagNames(origin.getTagNames(), dto.getTagNames());
        communityBoardDao.updateArticle(dto, refreshTags);
        return "redirect:/community/detail?board_id=" + dto.getBoard_id();
    }

    @GetMapping("/activity")
    public String activity(@RequestParam(value = "user_num", required = false) Integer userNum,
                           @RequestParam(value = "tab", defaultValue = "posts") String tab,
                           @RequestParam(value = "category", required = false) String category,
                           HttpSession session,
                           Model model) {
        Integer loginNum = (Integer) session.getAttribute("loginNum");
        String loginUser = (String) session.getAttribute("loginUser");

        if (loginUser == null || loginNum == null) {
            return "redirect:/login";
        }

        int targetUserNum = (userNum != null) ? userNum : loginNum;
        boolean isMyActivity = targetUserNum == loginNum;

        UserDto activityUser = userDao.getUserInfo(targetUserNum);
        if (activityUser == null) {
            return "redirect:/community";
        }

        String currentTab = (tab == null || tab.isBlank()) ? "posts" : tab;
        String selectedCategory = (category == null || category.isBlank() || "all".equals(category)) ? null : category;

        int articleCount = communityBoardDao.getArticleCountByUserNum(targetUserNum);
        int myCommentCount = communityCommentDao.getCommentCountByUserNum(targetUserNum);
        int receivedCommentCount = communityCommentDao.getReceivedCommentCountByBoardOwner(targetUserNum);

        ArrayList<CommunityDto> posts = new ArrayList<>();
        ArrayList<CommunityCommentDto> comments = new ArrayList<>();
        ArrayList<CommunityCommentDto> replies = new ArrayList<>();

        if ("posts".equals(currentTab)) {
            posts = (selectedCategory == null)
                    ? communityBoardDao.getArticlesByUserNum(targetUserNum)
                    : communityBoardDao.getArticlesByUserNumAndCategory(targetUserNum, selectedCategory);

        } else if ("comments".equals(currentTab)) {
            comments = (selectedCategory == null)
                    ? communityCommentDao.getCommentsByUserNum(targetUserNum)
                    : communityCommentDao.getCommentsByUserNumAndCategory(targetUserNum, selectedCategory);

        } else if ("replies".equals(currentTab)) {
            replies = (selectedCategory == null)
                    ? communityCommentDao.getCommentsOnUserBoards(targetUserNum)
                    : communityCommentDao.getCommentsOnUserBoardsByCategory(targetUserNum, selectedCategory);

        } else {
            currentTab = "posts";
            posts = (selectedCategory == null)
                    ? communityBoardDao.getArticlesByUserNum(targetUserNum)
                    : communityBoardDao.getArticlesByUserNumAndCategory(targetUserNum, selectedCategory);
        }

        List<String> activityCategories = communityBoardDao.getUserActivityCategories(targetUserNum);

        model.addAttribute("activityUser", activityUser);
        model.addAttribute("activityPosts", posts);
        model.addAttribute("activityComments", comments);
        model.addAttribute("activityReplies", replies);
        model.addAttribute("activityCategories", activityCategories);
        model.addAttribute("selectedCategory", selectedCategory == null ? "all" : selectedCategory);
        model.addAttribute("currentTab", currentTab);
        model.addAttribute("isMyActivity", isMyActivity);
        model.addAttribute("targetUserNum", targetUserNum);
        model.addAttribute("currentPage", "community");

        model.addAttribute("articleCount", articleCount);
        model.addAttribute("myCommentCount", myCommentCount);
        model.addAttribute("receivedCommentCount", receivedCommentCount);

        return "community/activity";
    }

    private static final List<String> BANNED_WORDS = List.of(
            "시발", "병신", "개새끼", "뒤져", "뒤질", "뒤졌", "존나", "십창", "맘충", "여적여", "개줌마", "빨갱이",
            "찍어야", "낙선시켜", "좌파", "우파", "정치충", "종북", "느금", "개비", "니애미"
    );

    private void addSelectedNewsTitle(Model model, CommunityDto dto) {
        model.addAttribute("selectedNewsTitle", resolveSelectedNewsTitle(dto));
    }

    private boolean isOwnArticleComment(CommunityDto article, Integer loginNum) {
        return article != null && loginNum != null && article.getUser_num() == loginNum;
    }

    private String resolveSelectedNewsTitle(CommunityDto dto) {
        String newsLink = dto == null ? null : normalize(dto.getNews_link());
        if (newsLink == null) {
            return null;
        }

        String selectedNewsTitle = communityExtraDao.getNewsTitleByLink(newsLink);
        if (selectedNewsTitle == null || selectedNewsTitle.isBlank()) {
            return newsLink;
        }

        return selectedNewsTitle;
    }

    private boolean hasSameTagNames(String leftTagNames, String rightTagNames) {
        return normalizeTagNamesForCompare(leftTagNames).equals(normalizeTagNamesForCompare(rightTagNames));
    }

    private String normalizeTagNamesForCompare(String tagNames) {
        String normalized = normalize(tagNames);
        if (normalized == null) {
            return "";
        }

        String[] parts = normalized.contains("#") ? normalized.split("#") : normalized.split(",");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            String token = normalize(part);
            if (token != null) {
                tokens.add(token.toLowerCase(Locale.KOREA));
            }
        }

        tokens.sort(String::compareTo);
        return String.join("|", tokens);
    }

    private String formatTagsForInput(String tagNames) {
        String normalized = normalize(tagNames);
        if (normalized == null) {
            return "";
        }

        String[] parts = normalized.split(",");
        List<String> tokens = new ArrayList<>();

        for (String part : parts) {
            String token = normalize(part);
            if (token != null) {
                tokens.add("#" + token);
            }
        }

        return String.join(" ", tokens);
    }

    private String findBannedWord(String... values) {
        for (String value : values) {
            String text = value == null ? "" : value.trim().toLowerCase();

            for (String bannedWord : BANNED_WORDS) {
                if (text.contains(bannedWord.toLowerCase())) {
                    return bannedWord;
                }
            }
        }

        return null;
    }

    private List<Map<String, Object>> buildPopularPriceItems(List<Map<String, Object>> popularCategories) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (popularCategories == null || popularCategories.isEmpty()) {
            return items;
        }

        for (Map<String, Object> categoryRow : popularCategories) {
            String category = stringValue(categoryRow.get("category"));
            ThemeStockSeed seed = THEME_STOCKS.get(category);
            if (seed == null) {
                continue;
            }

            StockResponseDto quote = stockPriceService.getCurrentPrice(seed.stockCode);
            String currentPrice = quote != null ? quote.getCurrentPrice() : null;
            String changeRate = quote != null ? quote.getChangeRate() : null;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", category);
            item.put("stockName", resolveStockName(seed, quote));
            item.put("stockCode", seed.stockCode);
            item.put("currentPriceText", formatPriceText(currentPrice));
            item.put("changeRateText", formatRateText(changeRate));
            item.put("changeClass", resolveChangeClass(changeRate));
            item.put("postCount", categoryRow.get("postCount"));
            items.add(item);

            if (items.size() >= 4) {
                break;
            }
        }

        return items;
    }

    private String resolveStockName(ThemeStockSeed seed, StockResponseDto quote) {
        String quoteName = quote == null ? null : normalize(quote.getStockName());
        if (quoteName != null && !quoteName.equals(seed.stockCode)) {
            return quoteName;
        }
        return seed.stockName;
    }

    private String resolvePageTitle(String categoryName,
                                    boolean isPopularView,
                                    String themeName,
                                    String searchKeyword) {
        if (isPopularView) {
            if (themeName != null && !themeName.isBlank()) {
                return themeName + " 인기글";
            }
            return "인기글";
        }
        if (categoryName != null && !categoryName.isBlank() && !"전체".equals(categoryName)) {
            return categoryName;
        }
        if (searchKeyword != null && !searchKeyword.isBlank()) {
            return searchKeyword + " 검색 결과";
        }
        return "전체 게시글";
    }

    private String formatPriceText(String rawPrice) {
        double price = parseNumber(rawPrice);
        if (price <= 0) {
            return "-";
        }
        return String.format(Locale.KOREA, "%,.0f원", price);
    }

    private String formatRateText(String rawRate) {
        double rate = parseNumber(rawRate);
        if (!Double.isFinite(rate)) {
            return "-";
        }
        String prefix = rate > 0 ? "+" : "";
        return prefix + String.format(Locale.US, "%.2f%%", rate);
    }

    private String resolveChangeClass(String rawRate) {
        double rate = parseNumber(rawRate);
        if (!Double.isFinite(rate) || rate == 0) {
            return "priceNeutral";
        }
        return rate > 0 ? "priceUp" : "priceDown";
    }

    private double parseNumber(String value) {
        if (value == null || value.isBlank()) {
            return Double.NaN;
        }

        try {
            return Double.parseDouble(value.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String convertCategoryParamToName(String category) {
        if ("free".equals(category)) {
            return "자유게시판";
        } else if ("popular".equals(category)) {
            return "인기글";
        } else if ("beginner".equals(category)) {
            return "초보질문";
        } else if ("semiconductor".equals(category)) {
            return "반도체·AI";
        } else if ("battery".equals(category)) {
            return "2차전지";
        } else if ("bio".equals(category)) {
            return "제약·바이오";
        } else if ("finance".equals(category)) {
            return "금융·밸류업";
        } else if ("defense".equals(category)) {
            return "방산·우주항공";
        } else if ("platform".equals(category)) {
            return "IT·플랫폼";
        } else if ("entertainment".equals(category)) {
            return "엔터·미디어";
        } else if ("mobility".equals(category)) {
            return "자동차·모빌리티";
        }
        return category;
    }

    private String convertCategoryNameToParam(String categoryName) {
        if ("자유게시판".equals(categoryName)) {
            return "free";
        } else if ("인기글".equals(categoryName)) {
            return "popular";
        } else if ("초보질문".equals(categoryName)) {
            return "beginner";
        } else if ("반도체·AI".equals(categoryName)) {
            return "semiconductor";
        } else if ("2차전지".equals(categoryName)) {
            return "battery";
        } else if ("제약·바이오".equals(categoryName)) {
            return "bio";
        } else if ("금융·밸류업".equals(categoryName)) {
            return "finance";
        } else if ("방산·우주항공".equals(categoryName)) {
            return "defense";
        } else if ("IT·플랫폼".equals(categoryName)) {
            return "platform";
        } else if ("엔터·미디어".equals(categoryName)) {
            return "entertainment";
        } else if ("자동차·모빌리티".equals(categoryName)) {
            return "mobility";
        }
        return "";
    }

    private static final class ThemeStockSeed {
        private final String stockCode;
        private final String stockName;

        private ThemeStockSeed(String stockCode, String stockName) {
            this.stockCode = stockCode;
            this.stockName = stockName;
        }
    }
}