import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';

type Props = {
  taskTitle: string;
  taskDescription: string;
  initialCode: string;
  onClose: () => void;
  onApplyToDraft: (code: string) => void;
};

export function SolutionEditorModal({ taskTitle, taskDescription, initialCode, onClose, onApplyToDraft }: Props) {
  const [code, setCode] = useState(initialCode);
  const dialog = useRef<HTMLDivElement>(null);
  const editor = useRef<HTMLTextAreaElement>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (typeof document === 'undefined') return;
    previouslyFocused.current = typeof HTMLElement !== 'undefined' && document.activeElement instanceof HTMLElement ? document.activeElement : null;
    editor.current?.focus();
    return () => {
      if (previouslyFocused.current?.isConnected) previouslyFocused.current.focus();
      previouslyFocused.current = null;
    };
  }, []);

  const applyToDraft = () => {
    onApplyToDraft(code);
    onClose();
  };

  const onDialogKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') { event.preventDefault(); onClose(); return; }
    if (event.key !== 'Tab') return;
    const focusable = dialog.current?.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])');
    if (!focusable?.length) return;
    const first = focusable[0]; const last = focusable[focusable.length - 1];
    if (!dialog.current?.contains(document.activeElement)) { event.preventDefault(); (event.shiftKey ? last : first).focus(); }
    else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
  };

  return (
    <div className="employer-modal-backdrop" role="presentation" onClick={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <div
        ref={dialog}
        className="card"
        style={{
          maxWidth: '850px',
          width: '92%',
          maxHeight: '92vh',
          overflowY: 'auto',
          background: 'var(--color-bg, #ffffff)',
          borderRadius: '16px',
          padding: '2rem',
          boxShadow: '0 25px 50px -12px rgba(0, 0, 0, 0.25)',
        }}
        role="dialog"
        aria-modal="true"
        aria-label="Редактор черновика решения"
        onKeyDown={onDialogKeyDown}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', borderBottom: '1px solid var(--color-border, #e2e8f0)', paddingBottom: '1rem', marginBottom: '1.25rem' }}>
          <div>
            <div className="eyebrow" style={{ color: '#2563eb' }}>ЧЕРНОВИК РЕШЕНИЯ</div>
            <h2 style={{ margin: '0.25rem 0', fontSize: '1.375rem' }}>{taskTitle}</h2>
            <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--color-text-muted, #64748b)' }}>{taskDescription}</p>
          </div>
          <button type="button" className="text-button" aria-label="Закрыть редактор" onClick={onClose} style={{ fontSize: '1.5rem' }}>×</button>
        </div>

        <p role="note" style={{ color: '#64748b', fontSize: '0.875rem' }}>
          Редактор хранит текст в этом черновике. Код здесь не запускается и не проверяется. Отправка компании выполняется отдельно из формы ответа.
        </p>
        <label style={{ display: 'block', marginBottom: '1.25rem' }}>
          <span style={{ display: 'block', fontSize: '0.8125rem', fontWeight: 600, color: '#475569', marginBottom: '0.5rem' }}>Текст решения</span>
          <textarea
            ref={editor}
            value={code}
            onChange={(event) => setCode(event.target.value)}
            maxLength={20000}
            rows={12}
            style={{
              width: '100%',
              fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
              fontSize: '0.875rem',
              lineHeight: '1.5',
              padding: '1rem',
              borderRadius: '8px',
              border: '1px solid var(--color-border, #cbd5e1)',
              background: '#0f172a',
              color: '#f8fafc',
              boxSizing: 'border-box',
              resize: 'vertical',
            }}
          />
        </label>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '0.75rem' }}>
          <button type="button" className="button button-secondary" onClick={onClose}>Отмена</button>
          <button type="button" className="button button-primary" onClick={applyToDraft}>Перенести в черновик ответа</button>
        </div>
      </div>
    </div>
  );
}
