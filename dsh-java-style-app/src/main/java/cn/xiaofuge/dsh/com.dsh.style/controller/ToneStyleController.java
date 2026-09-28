package cn.xiaofuge.dsh.com.dsh.style.controller;

import cn.xiaofuge.dsh.com.dsh.style.config.ToneStyleProperties;
import cn.xiaofuge.dsh.com.dsh.style.model.ApiException;
import cn.xiaofuge.dsh.com.dsh.style.service.ToneStyleService;
import cn.xiaofuge.dsh.com.dsh.style.service.HarnessAiStreamService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class ToneStyleController {
    private final ToneStyleService toneService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final ToneStyleProperties properties;

    public ToneStyleController(ToneStyleService toneService,
                               HarnessAiStreamService harnessAiStreamService,
                               ToneStyleProperties properties) {
        this.toneService = toneService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record ProfileRequest(String name, String text) {
    }

    public record CompareRequest(String textA, String textB) {
    }

    public record RewriteRequest(String text, String targetStyle) {
    }

    public record ReadabilityRequest(String text) {
    }

    public record GlossaryRequest(String text, List<String> customTerms) {
    }

    @GetMapping("/api/style/profiles")
    public List<Map<String, Object>> profiles() {
        return toneService.listProfiles();
    }

    @PostMapping("/api/style/profile")
    public Map<String, Object> profile(@RequestBody ProfileRequest request) {
        return toneService.profileText(request.name(), request.text());
    }

    @PostMapping("/api/style/compare")
    public Map<String, Object> compare(@RequestBody CompareRequest request) {
        return toneService.compareTwo(request.textA(), request.textB());
    }

    @PostMapping("/api/style/rewrite")
    public Map<String, Object> rewrite(@RequestBody RewriteRequest request) {
        return toneService.rewriteSuggest(request.text(), request.targetStyle());
    }

    @PostMapping("/api/style/readability")
    public Map<String, Object> readability(@RequestBody ReadabilityRequest request) {
        return toneService.readability(request.text());
    }

    @PostMapping("/api/style/glossary-check")
    public Map<String, Object> glossaryCheck(@RequestBody GlossaryRequest request) {
        return toneService.glossaryCheck(request.text(), request.customTerms());
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(message));
    }

    private String buildPrompt(String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Tone Tuner（文风调音台）：文本风格画像、双文本对比、改写建议、可读性评分、术语一致性检查\n");
        prompt.append("- 你可以使用以下工具（分析文本前必须先调用工具查证，禁止臆造评分或画像结论）：\n");
        prompt.append("  - plugin__dsh-java-style-plugin__st_profile_text(name, text)：样本文本生成文风画像（句长/语气/词汇/标点）\n");
        prompt.append("  - plugin__dsh-java-style-plugin__st_compare_two(textA, textB)：双文本风格对比\n");
        prompt.append("  - plugin__dsh-java-style-plugin__st_rewrite_suggest(text, targetStyle)：按目标风格出改写建议（不修改原稿）\n");
        prompt.append("  - plugin__dsh-java-style-plugin__st_readability(text)：可读性评分（A/B/C/D + 分数）\n");
        prompt.append("  - plugin__dsh-java-style-plugin__st_glossary_check(text, customTerms)：术语一致性检查（错别字/大小写）\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 分析结论只引用工具返回的真实数据（句长/评分/问题清单）；工具没跑就不要下风格结论。\n");
        prompt.append("- 红线：不覆盖、不改写用户原稿本身——只输出建议清单；不替用户发送任何文本。\n");
        return prompt.toString();
    }
}
