import { signal } from '@preact/signals';
import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { ALLOWED_APP_PRESETS, SUBJECT_PALETTE, type AllowedAppLink } from '../core/model';
import { Icon, type IconName } from './icons';

// region Toasts

export interface Toast {
  id: number;
  text: string;
  action?: { label: string; run: () => void };
  /** Milliseconds; 0 keeps it until dismissed. */
  duration: number;
}

export const toasts = signal<Toast[]>([]);
let toastSeq = 0;

export function showToast(text: string, options: { action?: Toast['action']; duration?: number } = {}) {
  const t: Toast = { id: ++toastSeq, text, action: options.action, duration: options.duration ?? 3500 };
  toasts.value = [...toasts.value.filter((x) => x.text !== text), t].slice(-3);
  if (t.duration > 0) setTimeout(() => dismissToast(t.id), t.duration);
}

export function dismissToast(id: number) {
  toasts.value = toasts.value.filter((t) => t.id !== id);
}

export function ToastHost() {
  return (
    <div class="toast-host" role="status" aria-live="polite">
      {toasts.value.map((t) => (
        <div class="toast" key={t.id}>
          <span class="grow">{t.text}</span>
          {t.action && (
            <button
              type="button"
              onClick={() => {
                t.action?.run();
                dismissToast(t.id);
              }}
            >
              {t.action.label}
            </button>
          )}
          {t.duration === 0 && (
            <button type="button" aria-label="Dismiss" onClick={() => dismissToast(t.id)}>
              <Icon name="close" />
            </button>
          )}
        </div>
      ))}
    </div>
  );
}

// endregion

export function Section({ title, action }: { title: string; action?: { label: string; icon?: IconName; onClick: () => void } }) {
  return (
    <div class="section">
      <h2>{title}</h2>
      {action && (
        <button type="button" class="btn text" onClick={action.onClick}>
          {action.icon && <Icon name={action.icon} />}
          {action.label}
        </button>
      )}
    </div>
  );
}

export function TopBar({ title, onBack }: { title: string; onBack: () => void }) {
  return (
    <div class="topbar">
      <button type="button" class="icon-btn" aria-label="Back" onClick={onBack}>
        <Icon name="back" />
      </button>
      <h1>{title}</h1>
    </div>
  );
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return (
    <span class="switch">
      <input type="checkbox" role="switch" checked={checked} aria-label={label} onChange={(e) => onChange((e.currentTarget as HTMLInputElement).checked)} />
      <span />
    </span>
  );
}

export function ToggleRow({ title, sub, checked, onChange }: { title: string; sub?: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label class="row">
      <span class="grow">
        <span class="title">{title}</span>
        {sub && <span class="sub" style="display:block">{sub}</span>}
      </span>
      <Toggle checked={checked} onChange={onChange} label={title} />
    </label>
  );
}

export function Stepper({ value, min, max, format, onChange, label }: { value: number; min: number; max: number; format?: (v: number) => string; onChange: (v: number) => void; label: string }) {
  return (
    <span class="stepper" role="group" aria-label={label}>
      <button type="button" aria-label={`Decrease ${label}`} disabled={value <= min} onClick={() => onChange(Math.max(min, value - 1))}>
        −
      </button>
      <output aria-live="polite">{format ? format(value) : value}</output>
      <button type="button" aria-label={`Increase ${label}`} disabled={value >= max} onClick={() => onChange(Math.min(max, value + 1))}>
        +
      </button>
    </span>
  );
}

export function StepperRow(props: { title: string; value: number; min: number; max: number; format?: (v: number) => string; onChange: (v: number) => void }) {
  return (
    <div class="row">
      <span class="grow title">{props.title}</span>
      <Stepper {...props} label={props.title} />
    </div>
  );
}

/** A bottom sheet. Closes on the scrim, Escape, or the actions inside. */
export function Sheet({ title, onClose, children }: { title: string; onClose: () => void; children: ComponentChildren }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    const first = ref.current?.querySelector<HTMLElement>('input, select, textarea');
    // iOS only shows the keyboard for focus() inside the tap that opened the sheet; harmless elsewhere.
    first?.focus({ preventScroll: true });
    return () => document.removeEventListener('keydown', onKey);
  }, []);
  return (
    <div class="scrim" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div class="sheet" role="dialog" aria-modal="true" aria-label={title} ref={ref}>
        <div class="grabber" />
        <h3>{title}</h3>
        {children}
      </div>
    </div>
  );
}

export function ConfirmSheet({ title, body, confirm, onConfirm, onClose, danger }: { title: string; body?: string; confirm: string; onConfirm: () => void; onClose: () => void; danger?: boolean }) {
  return (
    <Sheet title={title} onClose={onClose}>
      {body && <p class="muted">{body}</p>}
      <div class="actions">
        <button type="button" class="btn text" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          class={`btn ${danger ? 'danger' : 'primary'}`}
          onClick={() => {
            onConfirm();
            onClose();
          }}
        >
          {confirm}
        </button>
      </div>
    </Sheet>
  );
}

export function ColorPicker({ value, onChange }: { value: string; onChange: (c: string) => void }) {
  return (
    <div class="swatches" role="radiogroup" aria-label="Colour">
      {SUBJECT_PALETTE.map((c) => (
        <button type="button" class="swatch" key={c} style={{ background: c }} aria-pressed={c.toUpperCase() === value.toUpperCase()} aria-label={c} onClick={() => onChange(c)}>
          {c.toUpperCase() === value.toUpperCase() && <Icon name="check" />}
        </button>
      ))}
    </div>
  );
}

/** Name + colour form used for new and edited subjects. */
export function SubjectSheet({
  title,
  initialName,
  initialColor,
  onSave,
  onClose,
  extra,
}: {
  title: string;
  initialName: string;
  initialColor: string;
  onSave: (name: string, color: string) => void;
  onClose: () => void;
  extra?: ComponentChildren;
}) {
  const name = useRef(initialName);
  const [color, setColor] = useState(initialColor);
  return (
    <Sheet title={title} onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const n = name.current.trim();
          if (!n) return;
          onSave(n, color);
          onClose();
        }}
      >
        <label class="stack" for="subject-name">
          Name
        </label>
        <input
          id="subject-name"
          class="field"
          maxLength={40}
          placeholder="Maths, IELTS Reading…"
          defaultValue={initialName}
          enterKeyHint="done"
          onInput={(e) => (name.current = (e.currentTarget as HTMLInputElement).value)}
        />
        <label class="stack">Colour</label>
        <ColorPicker value={color} onChange={setColor} />
        <div class="actions">
          {extra}
          <span style="flex:1" />
          <button type="button" class="btn text" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" class="btn primary">
            Save
          </button>
        </div>
      </form>
    </Sheet>
  );
}

/** Stand-in icon for an allowed app (a web app can't read other apps' icons): its first letter. */
export function AppInitial({ name, small }: { name: string; small?: boolean }) {
  const letter = Array.from(name.trim())[0]?.toUpperCase() ?? '?';
  return (
    <span class={`app-icon${small ? ' small' : ''}`} aria-hidden="true">
      {letter}
    </span>
  );
}

/** Adds an app the user allows during focus: a one-tap pick, or a name plus a link or Shortcut. */
export function AllowedAppSheet({ existing, onAdd, onClose }: { existing: readonly AllowedAppLink[]; onAdd: (app: AllowedAppLink) => void; onClose: () => void }) {
  const name = useRef('');
  const link = useRef('');
  const presets = ALLOWED_APP_PRESETS.filter((p) => !existing.some((e) => e.link === p.link));
  return (
    <Sheet title="Add an app" onClose={onClose}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          const n = name.current.trim();
          const l = link.current.trim();
          if (!n || !l) return;
          onAdd({ name: n, link: l });
          onClose();
        }}
      >
        <label class="stack" for="app-name">
          Name
        </label>
        <input id="app-name" class="field" maxLength={40} placeholder="Checkmate" enterKeyHint="next" onInput={(e) => (name.current = (e.currentTarget as HTMLInputElement).value)} />
        <label class="stack" for="app-link">
          Link or Shortcut name
        </label>
        <input
          id="app-link"
          class="field"
          maxLength={300}
          placeholder="spotify://  or  Open Checkmate"
          autoCapitalize="off"
          autoCorrect="off"
          spellcheck={false}
          enterKeyHint="done"
          onInput={(e) => (link.current = (e.currentTarget as HTMLInputElement).value)}
        />
        <p class="hint">
          An app's link, a website, or the name of a Shortcut that opens the app. For any app: in Shortcuts, make one with <b>Open App</b> and type its name here.
        </p>
        {presets.length > 0 && (
          <>
            <label class="stack">Or pick one</label>
            <div class="chips">
              {presets.map((p) => (
                <button
                  type="button"
                  class="chip"
                  key={p.link}
                  onClick={() => {
                    onAdd(p);
                    onClose();
                  }}
                >
                  {p.name}
                </button>
              ))}
            </div>
          </>
        )}
        <div class="actions">
          <span style="flex:1" />
          <button type="button" class="btn text" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" class="btn primary">
            Add
          </button>
        </div>
      </form>
    </Sheet>
  );
}
