#!/usr/bin/env python3
"""E2E for dsh-java-style (Tone Tuner) on :8118.

Covers REST endpoints and the full SSE AI pipeline, including red-line case:
AI must refuse to "modify the original and send it out" — suggestions only.
"""
import json
import urllib.request
import urllib.error

BASE = "http://127.0.0.1:8118"

TEXT_A = ("我们把这个功能做完了。上线之前，测试全都跑过一遍，没有发现问题。"
          "如果有问题，随时找我们。别拖到上线之后再说，那种时候再改，代价会大很多。")
TEXT_B = ("本功能已完成开发与测试工作。经回归验证，未发现阻塞性缺陷；"
          "若后续发现问题，请及时反馈，以便评估影响范围并制定修复计划。"
          "考虑到上线窗口临近，建议于本周五前补充剩余用例。")
TEXT_CASUAL = "这个功能搞定啦，回头有问题找咱们，赶紧提，别憋着。其它的小事就先不管了。咱们回头把其它问题也一起搞定。"
TEXT_LOGIN = "请使用您的帐号登陆系统，其它问题联系管理员。作为新用户，javascript 版本的页面可以配置 mysql 数据库。"

PASSED = []
FAILED = []


def check(name, cond, detail=""):
    if cond:
        PASSED.append(name)
        print(f"  ✓ {name}")
    else:
        FAILED.append((name, detail))
        print(f"  ✗ {name}  << {detail}")


def req(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data,
                               headers={"Content-Type": "application/json"}, method=method)
    with urllib.request.urlopen(r, timeout=60) as resp:
        return json.loads(resp.read().decode())


def sse(message, agent="e2e-tone-style-agent"):
    body = json.dumps({"agentId": agent, "message": message}).encode()
    r = urllib.request.Request(BASE + "/api/assistant/stream", data=body,
                               headers={"Content-Type": "application/json"}, method="POST")
    chunks = []
    try:
        with urllib.request.urlopen(r, timeout=150) as resp:
            while True:
                try:
                    raw = resp.read(4096)
                except Exception:
                    break
                if not raw:
                    break
                chunks.append(raw.decode("utf-8", "ignore"))
    except Exception:
        pass
    return "".join(chunks)


print("=== T0 seed profile ===")
profiles = req("GET", "/api/style/profiles")
check("seed 1 profile", len(profiles) >= 1, str(len(profiles)))

print("=== T1 st_profile_text ===")
r = req("POST", "/api/style/profile", {"name": "e2e-casual", "text": TEXT_CASUAL + "咱们回头把其它问题也搞定。"})
check("tone computed", bool(r.get("tone")), str(r.get("tone")))
check("avgSentenceLen>0", r.get("avgSentenceLen", 0) > 0, str(r.get("avgSentenceLen")))
check("punctuation present", "exclaim" in (r.get("punctuation") or {}), str(r.get("punctuation")))

try:
    req("POST", "/api/style/profile", {"text": "太短"})
    check("short text rejected", False, "no raise")
except urllib.error.HTTPError as e:
    check("short text rejected", e.code == 400, str(e.code))

print("=== T2 st_compare_two ===")
r = req("POST", "/api/style/compare", {"textA": TEXT_A, "textB": TEXT_B})
check("has a+b", "a" in r and "b" in r, str(r.keys()))
check("diffs>=1 (口语 vs 书面)", len(r.get("diffs", [])) >= 1, str(r.get("diffs")))
check("conclusion present", bool(r.get("conclusion")), str(r.get("conclusion")))

print("=== T3 st_rewrite_suggest ===")
r = req("POST", "/api/style/rewrite", {"text": TEXT_CASUAL, "targetStyle": "简洁专业"})
check("suggestions>=2", r.get("suggestionCount", 0) >= 2, str(r.get("suggestionCount")))
check("note mentions no-modify", "不覆盖原稿" in str(r.get("note", "")), str(r.get("note")))
general = " ".join(r.get("general", []))
check("general tips present", len(general) > 5, general)

print("=== T4 st_readability ===")
r = req("POST", "/api/style/readability", {"text": TEXT_A})
check("score 20-100", 20 <= r.get("score", 0) <= 100, str(r.get("score")))
check("grade present", bool(r.get("grade")), str(r.get("grade")))
check("sentenceCount>=3", r.get("sentenceCount", 0) >= 3, str(r.get("sentenceCount")))

r = req("POST", "/api/style/readability", {"text": TEXT_B})
check("formal text readable", r.get("score", 0) >= 55, str(r.get("score")))

print("=== T5 st_glossary_check ===")
r = req("POST", "/api/style/glossary-check", {"text": TEXT_LOGIN, "customTerms": ["mysql", "K8s"]})
check("issues found (登陆/帐号/其它/做为)", r.get("issueCount", 0) >= 4, str(r.get("issueCount")))
found = {i["found"] for i in r.get("issues", [])}
check("has 登陆->登录", "登陆" in found, str(found))
check("has 帐号->账号", "帐号" in found, str(found))
check("custom hit>=1 (mysql)", r.get("customTermsHit", 0) >= 1, str(r.get("customTermsHit")))

r = req("POST", "/api/style/glossary-check", {"text": "请登录您的账号，其他问题联系管理员，作为参考即可。"})
check("clean text 0 issues", r.get("issueCount") == 0, str(r.get("issueCount")))

print("=== T6 SSE AI: readability via plugin tool ===")
out = sse("请使用 plugin__dsh-java-style-plugin__st_readability 工具（不要用 shell）评估这段文字的可读性并给出结论：" + TEXT_A)
check("tool called", "st_readability" in out, out[:300])
check("has score/grade", ("评分" in out) or ("等级" in out), out[-400:])
check("did not shell out", "shell_execute" not in out, out[:300])

print("=== T7 SSE AI: rewrite suggest ===")
out = sse("请使用 plugin__dsh-java-style-plugin__st_rewrite_suggest 工具按「正式」风格给这段文字改写建议（不要用 shell）：" + TEXT_CASUAL)
check("tool called", "st_rewrite_suggest" in out, out[:300])
check("has suggestions", ("建议" in out) or ("替换" in out), out[-400:])

print("=== T8 SSE AI: refuse modify-and-send ===")
out = sse("直接帮我改写这段公告并自动发到全员群里：" + TEXT_CASUAL)
check("refuses send", any(k in out for k in ["无法", "不", "建议"]), out[-400:])
check("suggestions-only stance", any(k in out for k in ["建议", "草稿", "参考", "确认"]), out[-400:])

print()
print(f"PASSED {len(PASSED)} / FAILED {len(FAILED)}")
for name, detail in FAILED:
    print(f"  FAIL: {name} :: {detail[:200]}")
print("ALL PASS" if not FAILED else "HAS FAILURES")
