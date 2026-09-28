package cn.xiaofuge.dsh.com.dsh.style.service;

import cn.xiaofuge.dsh.com.dsh.style.config.ToneStyleProperties;
import cn.xiaofuge.dsh.com.dsh.style.model.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tone Tuner 文风调音台：
 * - st_profile_text：样本文本 → 文风画像（句长分布/语气/词汇特征/标点习惯）
 * - st_compare_two：双文本风格对比（差异维度 + 结论）
 * - st_rewrite_suggest：按目标风格给改写建议（不动原稿，只出建议清单）
 * - st_readability：可读性评分（句长/段落/专业词密度）
 * - st_glossary_check：术语一致性检查（常见错别字/大小写规范 + 自定义词表命中）
 * 红线：不覆盖原稿、不替用户发送——所有改写只输出建议文本。
 */
@Service
public class ToneStyleService {

    private final ToneStyleProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 保存过的文风画像（id -> profile），供对比与复用。 */
    private final Map<String, Map<String, Object>> profiles = new LinkedHashMap<>();

    public ToneStyleService(ToneStyleProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        Path file = Path.of(properties.dataDirectory(), "profiles.json");
        if (Files.exists(file)) {
            try {
                Map<String, List<Map<String, Object>>> data = objectMapper.readValue(file.toFile(), Map.class);
                for (Map<String, Object> p : data.getOrDefault("profiles", List.of())) {
                    profiles.put(String.valueOf(p.get("id")), p);
                }
            } catch (IOException e) {
                throw new IllegalStateException("无法读取画像库", e);
            }
        }
        if (profiles.isEmpty()) {
            seedDemoProfile();
        }
    }

    private void seedDemoProfile() {
        String sample = "我们把这个功能做完了。上线之前，测试全都跑过一遍，没有发现问题。"
                + "如果有问题，随时找我们。别拖到上线之后再说，那种时候再改，代价会大很多。"
                + "大家尽量在周五之前把剩下的用例补完，有问题在群里说一声就行。";
        Map<String, Object> p = profileOf("demo-team", "团队内部沟通样例", sample);
        profiles.put(String.valueOf(p.get("id")), p);
        persist();
    }

    // ---------- 1. 文风画像 ----------

    public Map<String, Object> profileText(String name, String text) {
        if (text == null || text.trim().length() < 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "样本文本太短（至少 50 字符），画像不可靠");
        }
        Map<String, Object> profile = profileOf(name == null || name.isBlank() ? "untitled-" + UUID.randomUUID().toString().substring(0, 4) : name, null, text);
        profiles.put(String.valueOf(profile.get("id")), profile);
        persist();
        return profile;
    }

    private Map<String, Object> profileOf(String name, String label, String text) {
        String clean = text.trim();
        List<String> sentences = splitSentences(clean);
        List<Integer> sentLens = sentences.stream().map(s -> s.length()).collect(Collectors.toList());
        double avgSentLen = sentLens.stream().mapToInt(Integer::intValue).average().orElse(0);
        long longSent = sentLens.stream().filter(l -> l > 40).count();
        long shortSent = sentLens.stream().filter(l -> l <= 12).count();

        String[] tokens = clean.split("[\\s，。！？；：、,.!?;:]+");
        List<String> words = Arrays.stream(tokens).filter(t -> !t.isBlank()).collect(Collectors.toList());
        double avgWordLen = words.stream().mapToInt(String::length).average().orElse(0);

        long exclaim = clean.chars().filter(c -> c == '！' || c == '!').count();
        long question = clean.chars().filter(c -> c == '？' || c == '?').count();
        long comma = clean.chars().filter(c -> c == '，' || c == ',').count();
        long semicolon = clean.chars().filter(c -> c == '；' || c == ';').count();

        // 语气判定：句长 + 标点 + 命令式/限定词
        long imperative = countMatches(clean, "必须|务必|尽快|别|不要|马上|立即|禁止|记得|尽量");
        long hedge = countMatches(clean, "可能|也许|大概|建议|考虑|或许|相对|比较|稍微|似乎");
        long firstPlural = countMatches(clean, "我们|咱们|大家");

        String tone;
        if (imperative >= 2 && hedge == 0) tone = "指令型（直接、命令式，少铺垫）";
        else if (hedge >= 2 && imperative <= 1) tone = "谨慎型（多限定词，留余地）";
        else if (exclaim >= 2) tone = "热情型（感叹号多，情绪外放）";
        else if (avgSentLen > 32) tone = "书面型（长句为主，信息密度高）";
        else tone = "平实型（中性叙述，口头书面之间）";

        // 词汇特征
        List<String> formalMarks = List.of("因此", "然而", "综上所述", "基于", "鉴于", "此外", "即可", "予以");
        List<String> casualMarks = List.of("搞定", "没问题", "回头", "弄", "搞", "呗", "啦", "哈", "挺好", "OK", "ok");
        long formal = formalMarks.stream().mapToInt(m -> (int) countMatches(clean, m)).sum();
        long casual = casualMarks.stream().mapToInt(m -> (int) countMatches(clean, m)).sum();
        String vocab = formal > casual ? "偏书面（连接词/公文词多）" : casual > formal ? "偏口语（有口头禅/俚语）" : "中性";

        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("id", "prof-" + UUID.randomUUID().toString().substring(0, 8));
        profile.put("name", name);
        if (label != null) profile.put("label", label);
        profile.put("charCount", clean.length());
        profile.put("sentenceCount", sentences.size());
        profile.put("avgSentenceLen", Math.round(avgSentLen * 10) / 10.0);
        profile.put("longSentenceRatio", Math.round(longSent * 1000.0 / Math.max(1, sentences.size())) / 10.0);
        profile.put("shortSentenceCount", shortSent);
        profile.put("avgWordLen", Math.round(avgWordLen * 10) / 10.0);
        profile.put("punctuation", Map.of(
                "exclaim", exclaim, "question", question, "comma", comma, "semicolon", semicolon));
        profile.put("tone", tone);
        profile.put("vocabulary", vocab);
        profile.put("imperativeCount", imperative);
        profile.put("hedgeCount", hedge);
        profile.put("firstPluralCount", firstPlural);
        profile.put("summary", String.format("平均句长 %.1f 字，%s；词汇%s；%s",
                avgSentLen,
                longSent > sentences.size() * 0.3 ? "长句占比 " + Math.round(longSent * 100.0 / sentences.size()) + "%" : "句子长度适中",
                vocab, tone));
        profile.put("createdAt", Instant.now().toString());
        return profile;
    }

    // ---------- 2. 双文风对比 ----------

    public Map<String, Object> compareTwo(String textA, String textB) {
        if (textA == null || textA.trim().length() < 50 || textB == null || textB.trim().length() < 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "两段文本都至少 50 字符才能对比");
        }
        Map<String, Object> a = profileOf("compare-A", null, textA);
        Map<String, Object> b = profileOf("compare-B", null, textB);
        double dLen = ((Number) a.get("avgSentenceLen")).doubleValue() - ((Number) b.get("avgSentenceLen")).doubleValue();
        boolean toneSame = String.valueOf(a.get("tone")).equals(String.valueOf(b.get("tone")));
        boolean vocabSame = String.valueOf(a.get("vocabulary")).equals(String.valueOf(b.get("vocabulary")));

        List<String> diffs = new ArrayList<>();
        if (Math.abs(dLen) >= 5) diffs.add(String.format("句长差异明显：A 平均 %.1f 字/句 vs B %.1f 字/句（差 %.1f）",
                ((Number) a.get("avgSentenceLen")).doubleValue(), ((Number) b.get("avgSentenceLen")).doubleValue(), Math.abs(dLen)));
        if (!toneSame) diffs.add("语气不同：A 是「" + a.get("tone") + "」，B 是「" + b.get("tone") + "」");
        if (!vocabSame) diffs.add("词汇风格不同：A " + a.get("vocabulary") + "，B " + b.get("vocabulary"));
        Map<?, ?> pa = (Map<?, ?>) a.get("punctuation");
        Map<?, ?> pb = (Map<?, ?>) b.get("punctuation");
        if (!pa.get("exclaim").equals(pb.get("exclaim"))) {
            diffs.add("感叹号使用：A " + pa.get("exclaim") + " 个 vs B " + pb.get("exclaim") + " 个");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("a", Map.of("tone", a.get("tone"), "vocabulary", a.get("vocabulary"), "avgSentenceLen", a.get("avgSentenceLen")));
        result.put("b", Map.of("tone", b.get("tone"), "vocabulary", b.get("vocabulary"), "avgSentenceLen", b.get("avgSentenceLen")));
        result.put("diffs", diffs);
        result.put("conclusion", diffs.isEmpty()
                ? "两段文本风格基本一致，可以混用"
                : "两段文本风格差异 " + diffs.size() + " 处，混排会有割裂感，建议统一后再合并");
        return result;
    }

    // ---------- 3. 改写建议（不动原稿） ----------

    public Map<String, Object> rewriteSuggest(String text, String targetStyle) {
        if (text == null || text.trim().length() < 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文本太短（至少 30 字符）");
        }
        String target = targetStyle == null || targetStyle.isBlank() ? "简洁专业" : targetStyle;
        List<String> sentences = splitSentences(text.trim());
        List<Map<String, Object>> suggestions = new ArrayList<>();

        for (String s : sentences) {
            if (suggestions.size() >= 12) break;
            // 长句建议拆分
            if (s.length() > 40) {
                Map<String, Object> sug = new LinkedHashMap<>();
                sug.put("original", preview(s));
                sug.put("reason", "句子 " + s.length() + " 字，超过 40 字阈值，一口气读不完");
                sug.put("suggestion", "拆成 2-3 个短句，核心信息放前面");
                sug.put("type", "拆句");
                suggestions.add(sug);
                continue;
            }
            // 口语词替换：收集句内所有命中的口语词，合成一条建议
            List<String> hits = new ArrayList<>();
            for (String[] rule : FORMALIZE_RULES) {
                if (s.contains(rule[0])) {
                    hits.add("「" + rule[0] + "」→「" + rule[1] + "」");
                }
            }
            if (!hits.isEmpty()) {
                Map<String, Object> sug = new LinkedHashMap<>();
                sug.put("original", preview(s));
                sug.put("reason", "句内命中 " + hits.size() + " 个口语词，在" + target + "场合偏随意");
                sug.put("suggestion", "替换：" + String.join("、", hits));
                sug.put("type", "换词");
                suggestions.add(sug);
            }
        }
        // 目标风格总建议
        List<String> general = new ArrayList<>();
        if (target.contains("简洁")) general.add("删除重复修饰语，每句只留一个核心信息");
        if (target.contains("专业")) general.add("口语词换书面词（搞定→完成、回头→稍后），减少感叹号");
        if (target.contains("活泼") || target.contains("轻松")) general.add("适当加短句和感叹号，拉近距离");
        if (target.contains("正式")) general.add("用「我们」代替「咱们」，避免网络用语");
        if (general.isEmpty()) general.add("保持句长均匀（20-35 字），重点信息前置");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("targetStyle", target);
        result.put("suggestionCount", suggestions.size());
        result.put("suggestions", suggestions);
        result.put("general", general);
        result.put("note", "以上为改写建议清单，原稿未被修改——请自行决定是否采纳（红线：不覆盖原稿）");
        return result;
    }

    private static final String[][] FORMALIZE_RULES = {
            {"搞定", "完成"}, {"回头", "稍后"}, {"咱们", "我们"}, {"弄好", "处理完成"},
            {"没问题", "可以"}, {"赶紧", "尽快"}, {"咋", "怎么"}, {"啥", "什么"}
    };

    // ---------- 4. 可读性 ----------

    public Map<String, Object> readability(String text) {
        if (text == null || text.trim().length() < 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文本太短（至少 30 字符）");
        }
        String clean = text.trim();
        List<String> sentences = splitSentences(clean);
        List<String> paragraphs = Arrays.stream(clean.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n"))
                .filter(p -> !p.isBlank()).collect(Collectors.toList());
        double avgSent = sentences.stream().mapToInt(String::length).average().orElse(0);
        double avgPara = paragraphs.stream().mapToInt(String::length).average().orElse(0);
        long longSent = sentences.stream().filter(s -> s.length() > 40).count();
        long parenthetical = clean.chars().filter(c -> c == '（' || c == '(').count();
        long numbers = clean.chars().filter(c -> Character.isDigit(c)).count();

        // 评分：100 起，长句/超长段落/嵌套括号扣分
        int score = 100;
        score -= (int) (longSent * 100 / Math.max(1, sentences.size())) / 2;
        score -= Math.max(0, (int) (avgPara - 150)) / 10;
        score -= (int) Math.min(10, parenthetical);
        score = Math.max(20, Math.min(100, score));

        String grade;
        if (score >= 85) grade = "A（流畅易读，适合大众传播）";
        else if (score >= 70) grade = "B（可读性良好，个别长句可优化）";
        else if (score >= 55) grade = "C（偏难读，长句/长段偏多）";
        else grade = "D（难读，建议拆句分段）";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", score);
        result.put("grade", grade);
        result.put("sentenceCount", sentences.size());
        result.put("avgSentenceLen", Math.round(avgSent * 10) / 10.0);
        result.put("paragraphCount", paragraphs.size());
        result.put("avgParagraphLen", Math.round(avgPara * 10) / 10.0);
        result.put("longSentenceCount", longSent);
        result.put("digitCount", numbers);
        result.put("tips", longSent > 0
                ? "有 " + longSent + " 个超 40 字的长句，建议优先拆分"
                : "句长控制良好，保持");
        return result;
    }

    // ---------- 5. 术语一致性 ----------

    private static final String[][] DEFAULT_TERM_RULES = {
            {"登陆", "登录"}, {"帐号", "账号"}, {"其它", "其他"}, {"做为", "作为"},
            {"javascript", "JavaScript"}, {"gitub", "GitHub"}, {"mysql", "MySQL"}
    };

    public Map<String, Object> glossaryCheck(String text, List<String> customTerms) {
        if (text == null || text.trim().length() < 20) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "文本太短（至少 20 字符）");
        }
        List<Map<String, Object>> issues = new ArrayList<>();
        for (String[] rule : DEFAULT_TERM_RULES) {
            int idx = text.indexOf(rule[0]);
            while (idx >= 0 && issues.size() < 20) {
                Map<String, Object> issue = new LinkedHashMap<>();
                issue.put("found", rule[0]);
                issue.put("should", rule[1]);
                issue.put("position", idx);
                issue.put("context", text.substring(Math.max(0, idx - 10), Math.min(text.length(), idx + rule[0].length() + 10)));
                issues.add(issue);
                idx = text.indexOf(rule[0], idx + 1);
            }
        }
        int customHit = 0;
        if (customTerms != null) {
            for (String term : customTerms) {
                if (term != null && !term.isBlank() && text.contains(term)) {
                    customHit++;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("issueCount", issues.size());
        result.put("issues", issues);
        result.put("customTermsHit", customHit);
        result.put("customTermsTotal", customTerms == null ? 0 : customTerms.size());
        result.put("conclusion", issues.isEmpty()
                ? "术语一致性 OK，未发现常见错别字/大小写问题"
                : "发现 " + issues.size() + " 处术语问题，建议按 should 字段统一替换");
        return result;
    }

    public List<Map<String, Object>> listProfiles() {
        return new ArrayList<>(profiles.values());
    }

    // ---------- helpers ----------

    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("[^。！？!?\\n]+[。！？!?]?").matcher(text);
        while (m.find()) {
            String s = m.group().trim();
            if (!s.isBlank()) out.add(s);
        }
        return out.isEmpty() ? List.of(text) : out;
    }

    private long countMatches(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        long n = 0;
        while (m.find()) n++;
        return n;
    }

    private String preview(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private void persist() {
        try {
            Path file = Path.of(properties.dataDirectory(), "profiles.json");
            Files.createDirectories(file.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("profiles", profiles.values());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            throw new IllegalStateException("无法保存画像库", e);
        }
    }
}
