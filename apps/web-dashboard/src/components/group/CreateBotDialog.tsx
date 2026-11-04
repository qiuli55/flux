/**
 * 创建 Bot：一段对话就是一个 bot，创建时给它起名并挑一组形状 × 颜色作为头像。
 * 头像不可上传图片，避免引入资源存储；同一组合即唯一视觉标识。
 */
import { useCallback, useEffect, useState } from "react";
import type { DecisionMode, Task } from "../../api/types";
import { api } from "../../api/client";
import { Modal } from "../../shell/Overlay";
import { Button, Field, Input } from "../../shell/ui";
import {
  AVATAR_COLORS,
  AVATAR_SHAPES,
  BotAvatar,
  DEFAULT_AVATAR_COLOR,
  DEFAULT_AVATAR_SHAPE,
  avatarColorHex,
} from "./BotAvatar";

interface CreateBotDialogProps {
  open: boolean;
  onClose: () => void;
  onCreated: (task: Task) => void;
  /** 预填名称：从「写下第一条需求即创建」进来时用需求首行做默认名 */
  initialName?: string;
  /** 所属项目 / 决策策略 / 执行 Agent：沿用当前页面的选择 */
  projectId?: string | null;
  decisionMode?: DecisionMode;
  agentId?: string | null;
}

function randomPair() {
  const shape =
    AVATAR_SHAPES[Math.floor(Math.random() * AVATAR_SHAPES.length)] ?? DEFAULT_AVATAR_SHAPE;
  const color =
    AVATAR_COLORS[Math.floor(Math.random() * AVATAR_COLORS.length)] ?? DEFAULT_AVATAR_COLOR;
  return { shape, color };
}

export function CreateBotDialog({
  open,
  onClose,
  onCreated,
  initialName,
  projectId = null,
  decisionMode,
  agentId = null,
}: CreateBotDialogProps) {
  const [name, setName] = useState("");
  const [shape, setShape] = useState<string>(DEFAULT_AVATAR_SHAPE);
  const [color, setColor] = useState<string>(DEFAULT_AVATAR_COLOR);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 每次打开都重置，并随机给一组配色，避免每次都是同一个紫色圆。
  useEffect(() => {
    if (!open) return;
    const pair = randomPair();
    setName((initialName ?? "").trim());
    setShape(pair.shape);
    setColor(pair.color);
    setBusy(false);
    setError(null);
  }, [open, initialName]);

  const trimmed = name.trim();
  const canSubmit = trimmed.length > 0 && !busy;

  const create = useCallback(async () => {
    if (!trimmed || busy) return;
    setBusy(true);
    setError(null);
    try {
      const task = await api.createTask({
        description: trimmed,
        project_id: projectId,
        decision_mode: decisionMode,
        agent_id: agentId,
        bot_name: trimmed,
        avatar_shape: shape,
        avatar_color: color,
        start: false,
      });
      onCreated(task);
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建失败");
      setBusy(false);
    }
  }, [trimmed, busy, shape, color, projectId, decisionMode, agentId, onCreated, onClose]);

  return (
    <Modal
      open={open}
      title="创建新 Bot"
      onClose={() => {
        if (!busy) onClose();
      }}
      footer={
        <>
          <Button variant="ghost" onClick={onClose} disabled={busy}>
            取消
          </Button>
          <Button variant="primary" onClick={() => void create()} disabled={!canSubmit} busy={busy}>
            创建
          </Button>
        </>
      }
    >
      <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        <div style={{ display: "flex", justifyContent: "center", paddingTop: 4 }}>
          <BotAvatar shape={shape} color={color} size={88} name={trimmed || "新 Bot"} />
        </div>

        <Field label="名称" required hint="这个名字就是它的身份，群聊里别人也这样称呼它。">
          <Input
            autoFocus
            value={name}
            maxLength={64}
            placeholder="例如：小红书发布官"
            onChange={(event) => setName(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                void create();
              }
            }}
          />
        </Field>

        <div>
          <div style={{ fontSize: 12, color: "var(--tn-text-2)", marginBottom: 8 }}>形状</div>
          <div
            style={{
              display: "grid",
              gridTemplateColumns: "repeat(7, 1fr)",
              gap: 8,
            }}
          >
            {AVATAR_SHAPES.map((item) => {
              const active = item === shape;
              return (
                <button
                  key={item}
                  type="button"
                  title={item}
                  onClick={() => setShape(item)}
                  style={{
                    display: "flex",
                    alignItems: "center",
                    justifyContent: "center",
                    height: 44,
                    borderRadius: 10,
                    background: active ? "var(--tn-panel-3)" : "transparent",
                    border: `1px solid ${active ? "var(--tn-accent)" : "var(--tn-line)"}`,
                    cursor: "pointer",
                  }}
                >
                  <BotAvatar shape={item} color={color} size={28} />
                </button>
              );
            })}
          </div>
        </div>

        <div>
          <div style={{ fontSize: 12, color: "var(--tn-text-2)", marginBottom: 8 }}>颜色</div>
          <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
            {AVATAR_COLORS.map((item) => {
              const active = item === color;
              return (
                <button
                  key={item}
                  type="button"
                  title={item}
                  aria-label={item}
                  onClick={() => setColor(item)}
                  style={{
                    width: 26,
                    height: 26,
                    borderRadius: 999,
                    padding: 0,
                    cursor: "pointer",
                    background: avatarColorHex(item),
                    border: `2px solid ${active ? "var(--tn-text)" : "transparent"}`,
                    boxShadow: active ? "0 0 0 2px rgba(61,245,168,0.35)" : "none",
                  }}
                />
              );
            })}
          </div>
        </div>

        {error ? <div style={{ fontSize: 12, color: "var(--tn-danger)" }}>{error}</div> : null}
      </div>
    </Modal>
  );
}
