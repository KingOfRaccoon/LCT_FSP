type Props = {
  enabled: boolean;
  onToggle: (enabled: boolean) => void;
};

export function BlindHiringToggle({ enabled, onToggle }: Props) {
  return (
    <div
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '0.65rem',
        padding: '0.4rem 0.85rem',
        borderRadius: '9999px',
        background: enabled ? 'rgba(99, 102, 241, 0.12)' : 'var(--color-bg-secondary, #f1f5f9)',
        border: `1px solid ${enabled ? '#6366f1' : 'var(--color-border, #cbd5e1)'}`,
        cursor: 'pointer',
        userSelect: 'none',
        transition: 'all 0.2s ease'
      }}
      onClick={() => onToggle(!enabled)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => { if (e.key === ' ' || e.key === 'Enter') onToggle(!enabled); }}
      aria-pressed={enabled}
      title="Скрывает персональные данные и фото для исключения субъективности и когнитивных искажений"
    >
      <span style={{ fontSize: '0.9375rem' }}>{enabled ? '🛡️' : '⚖️'}</span>
      <span style={{ fontSize: '0.8125rem', fontWeight: 600, color: enabled ? '#4338ca' : 'var(--color-text-main, #334155)' }}>
        {enabled ? 'Слепой отбор (Blind Hiring): ВКЛ' : 'Включить слепой отбор'}
      </span>
      <span
        style={{
          width: '28px',
          height: '16px',
          background: enabled ? '#6366f1' : '#cbd5e1',
          borderRadius: '9999px',
          display: 'inline-block',
          position: 'relative',
          transition: 'background 0.2s ease'
        }}
      >
        <span
          style={{
            width: '12px',
            height: '12px',
            background: '#ffffff',
            borderRadius: '50%',
            position: 'absolute',
            top: '2px',
            left: enabled ? '14px' : '2px',
            transition: 'left 0.2s ease'
          }}
        />
      </span>
    </div>
  );
}

export function maskName(name: string, id: string, blindMode: boolean): string {
  if (!blindMode) return name;
  const hash = id.replace(/[^a-f0-9]/gi, '').slice(0, 4).toUpperCase() || '7A2B';
  return `Кандидат #${hash}`;
}
