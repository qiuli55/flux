/**
 * `completionCore` 的单测。
 *
 * 这里刻意只测纯函数：不 import `vscode`，用 `tsc -p tsconfig.test.json` 编译成
 * commonjs 后直接跑 `node --test`。补全链路的「该不该发请求、发出去的文件安不安全、
 * 模型输出要不要清洗」这些判断，全在这一层兜住。
 */

import test from "node:test";
import assert from "node:assert/strict";

import {
  DEFAULT_BLOCKED_PATTERNS,
  completionKey,
  insideCommentOrString,
  isBlockedLanguage,
  isBlockedPath,
  isCursorOnlyMove,
  isSupportedScheme,
  matchesGlob,
  sanitizeCompletion,
  schemeOf,
  staleSessionKeys,
} from "../src/completionCore";

test("schemeOf 解析 URI scheme", () => {
  assert.equal(schemeOf("file:///root/a.ts"), "file");
  assert.equal(schemeOf("vscode-remote://ssh/x/a.ts"), "vscode-remote");
  assert.equal(schemeOf("untitled:Untitled-1"), "untitled");
  assert.equal(schemeOf("git:/root/a.ts?%7B%22path%22%3A1%7D"), "git");
  assert.equal(schemeOf(""), "");
  assert.equal(schemeOf("not a uri"), "");
});

test("isSupportedScheme 只放行可编辑文档，git 虚拟文档不补全", () => {
  assert.equal(isSupportedScheme("file:///root/a.ts"), true);
  assert.equal(isSupportedScheme("untitled:Untitled-1"), true);
  assert.equal(isSupportedScheme("vscode-remote://ssh/x/a.py"), true);
  assert.equal(isSupportedScheme("git:/root/a.ts?%7B%7D"), false);
  assert.equal(isSupportedScheme("output:extension-output-flux"), false);
  assert.equal(isSupportedScheme("vscode-scm:input"), false);
});

test("isBlockedLanguage 拦掉没有代码语义的语言", () => {
  assert.equal(isBlockedLanguage("plaintext"), true);
  assert.equal(isBlockedLanguage("Log"), true);
  assert.equal(isBlockedLanguage("git-commit"), true);
  assert.equal(isBlockedLanguage("python"), false);
  assert.equal(isBlockedLanguage("typescriptreact"), false);
  assert.equal(isBlockedLanguage(""), false);
});

test("matchesGlob 支持 ** / * / ?、大小写不敏感、按文件名或完整路径匹配", () => {
  assert.equal(matchesGlob("cert.pem", "**/*.pem"), true);
  assert.equal(matchesGlob("certs/prod/cert.pem", "**/*.pem"), true);
  assert.equal(matchesGlob("CERT.PEM", "**/*.pem"), true);
  assert.equal(matchesGlob("src/index.ts", "*.ts"), true);
  assert.equal(matchesGlob("src/index.ts", "*.py"), false);
  assert.equal(matchesGlob("src/deep/index.ts", "*.ts"), true);
  assert.equal(matchesGlob("a/node_modules/x/y.js", "**/node_modules/**"), true);
  assert.equal(matchesGlob("node_modules/x/y.js", "**/node_modules/**"), true);
  assert.equal(matchesGlob("a/node_modules_file.js", "**/node_modules/**"), false);
  assert.equal(matchesGlob("db/schema.sql", "db/*.sql"), true);
  assert.equal(matchesGlob("db/v1/schema.sql", "db/*.sql"), false);
  assert.equal(matchesGlob("db/v1/schema.sql", "db/**/*.sql"), true);
  assert.equal(matchesGlob("a1.ts", "a?.ts"), true);
  assert.equal(matchesGlob("a12.ts", "a?.ts"), false);
  // 反斜杠路径与 ./ 前缀都要能正常工作（Windows 拖进来的路径）
  assert.equal(matchesGlob(".\\src\\a.ts", "src/*.ts"), true);
  assert.equal(matchesGlob("./src/a.ts", "src/*.ts"), true);
  assert.equal(matchesGlob("", "**/*.pem"), false);
  assert.equal(matchesGlob("a.pem", ""), false);
});

test("isBlockedPath 内置规则始终生效", () => {
  assert.equal(isBlockedPath(".env", []), true);
  assert.equal(isBlockedPath("config/.env.production", []), true);
  assert.equal(isBlockedPath("deploy/server.pem", []), true);
  assert.equal(isBlockedPath("deploy/server.key", []), true);
  assert.equal(isBlockedPath("keys/id_rsa", []), true);
  assert.equal(isBlockedPath("config/secrets.yaml", []), true);
  assert.equal(isBlockedPath("config/credentials.json", []), true);
  assert.equal(isBlockedPath(".git/config", []), true);
  assert.equal(isBlockedPath("node_modules/react/index.js", []), true);
  assert.equal(isBlockedPath("web/vendor/app.min.js", []), true);
  assert.equal(isBlockedPath("package-lock.json", []), true);
  assert.equal(isBlockedPath("uv.lock", []), true);
  // 正常源码不拦
  assert.equal(isBlockedPath("src/main.py", []), false);
  assert.equal(isBlockedPath("apps/web/src/App.tsx", []), false);
  assert.equal(isBlockedPath("", []), false);
});

test("isBlockedPath 支持用户追加规则，且空规则被忽略", () => {
  assert.equal(isBlockedPath("db/schema.sql", ["**/*.sql"]), true);
  assert.equal(isBlockedPath("db/schema.sql", ["", "  "]), false);
  assert.equal(isBlockedPath("src/main.py", ["**/*.sql"]), false);
  // 即使追加列表为空，内置规则也还在
  assert.equal(isBlockedPath(".env", []), true);
  assert.ok(DEFAULT_BLOCKED_PATTERNS.length > 0);
});

test("completionKey 区分位置 / 版本 / 模型 / 行数 / 模式", () => {
  const base = {
    uri: "file:///root/a.py",
    version: 3,
    offset: 42,
    model: "follow-agent",
    maxLines: 8,
    mode: "insert" as const,
  };
  const key = completionKey(base);
  assert.equal(key, "file:///root/a.py|3|42|follow-agent|8|insert");
  assert.notEqual(key, completionKey({ ...base, offset: 43 }));
  assert.notEqual(key, completionKey({ ...base, version: 4 }));
  assert.notEqual(key, completionKey({ ...base, model: "deepseek-chat" }));
  assert.notEqual(key, completionKey({ ...base, maxLines: 1 }));
  assert.notEqual(key, completionKey({ ...base, mode: "line" }));
});

test("isCursorOnlyMove 只在版本相同、位置变化时命中", () => {
  assert.equal(isCursorOnlyMove(undefined, { version: 1, offset: 5 }), false);
  assert.equal(isCursorOnlyMove({ version: 1, offset: 5 }, { version: 1, offset: 9 }), true);
  assert.equal(isCursorOnlyMove({ version: 1, offset: 5 }, { version: 1, offset: 5 }), false);
  assert.equal(isCursorOnlyMove({ version: 1, offset: 5 }, { version: 2, offset: 9 }), false);
});

test("staleSessionKeys 只清同一文件里除保留 key 之外的会话", () => {
  const sessions = [
    { key: "a", uri: "file:///x.py" },
    { key: "b", uri: "file:///x.py" },
    { key: "c", uri: "file:///y.py" },
  ];
  assert.deepEqual(staleSessionKeys(sessions, "file:///x.py", "a"), ["b"]);
  assert.deepEqual(staleSessionKeys(sessions, "file:///x.py"), ["a", "b"]);
  assert.deepEqual(staleSessionKeys(sessions, "file:///z.py"), []);
  assert.deepEqual(staleSessionKeys([], "file:///x.py"), []);
});

test("insideCommentOrString 识别 Python 注释与字符串", () => {
  assert.equal(insideCommentOrString("x = 1  # 注释", "python"), true);
  assert.equal(insideCommentOrString("# 整行注释", "python"), true);
  assert.equal(insideCommentOrString('x = "abc"', "python"), false);
  assert.equal(insideCommentOrString('x = "abc', "python"), true);
  // 字符串里的 # 不是注释
  assert.equal(insideCommentOrString('x = "# not a comment"', "python"), false);
  // 转义引号不算闭合
  assert.equal(insideCommentOrString('x = "a\\"b', "python"), true);
  assert.equal(insideCommentOrString('s = """abc', "python"), true);
  assert.equal(insideCommentOrString('s = """abc"""', "python"), false);
  assert.equal(insideCommentOrString("def add(a, b):", "python"), false);
});

test("insideCommentOrString 识别 C 系注释与未闭合块注释", () => {
  assert.equal(insideCommentOrString("// 注释", "javascript"), true);
  assert.equal(insideCommentOrString("/* 还没闭合", "javascript"), true);
  assert.equal(insideCommentOrString("/* ok */ const a = 1", "javascript"), false);
  assert.equal(insideCommentOrString('const s = "a"; // 收尾', "javascript"), true);
  assert.equal(insideCommentOrString("const s = 'a'", "typescript"), false);
  assert.equal(insideCommentOrString("const s = `abc", "typescript"), true);
});

test("insideCommentOrString 识别 SQL / Lua / LaTeX / Lisp 注释", () => {
  assert.equal(insideCommentOrString("-- note", "sql"), true);
  assert.equal(insideCommentOrString("SELECT 'a' FROM t", "sql"), false);
  // SQL 的 '' 是转义，不应被当成未闭合
  assert.equal(insideCommentOrString("SELECT 'it''s' FROM t", "sql"), false);
  assert.equal(insideCommentOrString("SELECT 'abc FROM t", "sql"), true);
  assert.equal(insideCommentOrString("--[[ 注释", "lua"), true);
  assert.equal(insideCommentOrString("% 注释", "latex"), true);
  assert.equal(insideCommentOrString("; 注释", "lisp"), true);
  assert.equal(insideCommentOrString("(defun foo (x)", "lisp"), false);
});

test("insideCommentOrString 未知语言按 C 系处理，纯文本不判", () => {
  assert.equal(insideCommentOrString("// x", "some-new-language"), true);
  assert.equal(insideCommentOrString("# x", "plaintext"), false);
  assert.equal(insideCommentOrString("", "python"), false);
});

test("sanitizeCompletion 插入模式保留内部空行、只裁尾部", () => {
  assert.equal(sanitizeCompletion("foo\n\nbar\n  \n", "insert"), "foo\n\nbar");
  assert.equal(sanitizeCompletion("a\r\nb\r\n", "insert"), "a\nb");
  assert.equal(sanitizeCompletion("    indented\n", "insert"), "    indented");
  assert.equal(sanitizeCompletion("", "insert"), "");
  assert.equal(sanitizeCompletion("   \n\n", "insert"), "");
});

test("sanitizeCompletion 整行模式只留第一行", () => {
  assert.equal(sanitizeCompletion("\ndef foo():\n    pass", "line"), "def foo():");
  assert.equal(sanitizeCompletion("return x   ", "line"), "return x");
  assert.equal(sanitizeCompletion("a\nb", "line"), "a");
  assert.equal(sanitizeCompletion("\n\n", "line"), "");
});
