package cn.xiaofuge.dsh.com.dsh.style.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshToneStylePlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-style-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshToneStylePlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new ProfileTextTool(),
                new CompareTwoTool(),
                new RewriteSuggestTool(),
                new ReadabilityTool(),
                new GlossaryCheckTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("tone-style-style-capabilities", 20, """
                ## DSH Tone Tuner Plugin
                - 所有 st_* 工具都是只读分析：不修改用户原稿，不发送任何文本。
                - st_rewrite_suggest 只输出建议清单，禁止声称"已改好/已替换原稿"。
                - 风格结论必须来自工具返回数据（句长/评分/问题清单），禁止凭感觉下结论。
                - 用户要求"直接帮我改并发出去"时，说明红线：只给建议，发送由用户自己操作。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__st_")) {
                return PluginHookResult.context("DSH Tone Tuner audit: analysis is read-only, original text is never modified.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String get(String path, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Accept", "application/json")
                .GET()
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_STYLE_ADMIN_URL", "http://127.0.0.1:8118")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String textJson(Map<String, Object> args) {
        return "{\"text\":\"" + json(str(args, "text")) + "\"}";
    }

    private class ProfileTextTool extends AbstractTool {
        @Override public String name() { return "st_profile_text"; }
        @Override public String description() { return "样本文本生成文风画像：平均句长/语气类型/词汇风格/标点习惯。分析某人或某团队文风时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("name", stringSchema("画像名称，如 '老板文风'"))
                    .prop("text", stringSchema("样本文本（至少 50 字符）"))
                    .prop("adminBaseUrl", stringSchema("可选，Tone Tuner 后台地址，默认 http://127.0.0.1:8118"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"name\":\"" + json(str(args, "name")) + "\",\"text\":\"" + json(str(args, "text")) + "\"}";
            return ok(post("/api/style/profile", body, args));
        }
    }

    private class CompareTwoTool extends AbstractTool {
        @Override public String name() { return "st_compare_two"; }
        @Override public String description() { return "双文本风格对比：语气/词汇/句长差异清单 + 混排结论。判断两段文字能否合并或是否同一人写时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("textA", stringSchema("文本 A（至少 50 字符）"))
                    .prop("textB", stringSchema("文本 B（至少 50 字符）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("textA", "textB")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"textA\":\"" + json(str(args, "textA")) + "\",\"textB\":\"" + json(str(args, "textB")) + "\"}";
            return ok(post("/api/style/compare", body, args));
        }
    }

    private class RewriteSuggestTool extends AbstractTool {
        @Override public String name() { return "st_rewrite_suggest"; }
        @Override public String description() { return "按目标风格（如 简洁/专业/正式/活泼）输出改写建议清单：拆句/换词 + 通用建议。红线：不改原稿，只给建议。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("待改进文本（至少 30 字符）"))
                    .prop("targetStyle", stringSchema("目标风格，如 简洁专业/正式/活泼"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"text\":\"" + json(str(args, "text")) + "\",\"targetStyle\":\"" + json(str(args, "targetStyle")) + "\"}";
            return ok(post("/api/style/rewrite", body, args));
        }
    }

    private class ReadabilityTool extends AbstractTool {
        @Override public String name() { return "st_readability"; }
        @Override public String description() { return "可读性评分：A/B/C/D 等级 + 分数（长句/长段/括号扣分），附优化提示。发布前检查文案时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("text", stringSchema("待评估文本（至少 30 字符）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/style/readability", textJson(args), args));
        }
    }

    private class GlossaryCheckTool extends AbstractTool {
        @Override public String name() { return "st_glossary_check"; }
        @Override public String description() { return "术语一致性检查：常见错别字（登陆/帐号/其它）与大小写规范（javascript/MySQL），支持自定义词表命中统计。"; }
        @Override public Map<String, Object> parameters() {
            Map<String, Object> items = new java.util.LinkedHashMap<>();
            items.put("type", "string");
            Map<String, Object> arr = new java.util.LinkedHashMap<>();
            arr.put("type", "array");
            arr.put("description", "自定义术语表（可选），检查命中数");
            arr.put("items", items);
            return objectSchema()
                    .prop("text", stringSchema("待检查文本（至少 20 字符）"))
                    .prop("customTerms", arr)
                    .prop("adminBaseUrl", stringSchema("可选，后台地址"))
                    .required("text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            StringBuilder terms = new StringBuilder("[");
            Object custom = args == null ? null : args.get("customTerms");
            if (custom instanceof List<?> list) {
                boolean first = true;
                for (Object t : list) {
                    if (!first) terms.append(",");
                    terms.append("\"").append(json(String.valueOf(t))).append("\"");
                    first = false;
                }
            }
            terms.append("]");
            String body = "{\"text\":\"" + json(str(args, "text")) + "\",\"customTerms\":" + terms + "}";
            return ok(post("/api/style/glossary-check", body, args));
        }
    }
}
