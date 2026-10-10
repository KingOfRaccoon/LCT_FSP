import { useEffect, useState } from 'react';
import { api, errorMessage, type Schemas } from '../../api';

export type Need = Schemas['Need'];

export interface NeedSelectProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  allOptionLabel?: string;
  includeAllOption?: boolean;
  activeOnly?: boolean;
  disabled?: boolean;
  required?: boolean;
  className?: string;
  id?: string;
  name?: string;
}

const gradeLabel = (grade: string): string => {
  if (grade === 'junior') return 'Junior';
  if (grade === 'middle') return 'Middle';
  return grade;
};

export function NeedSelect({
  value,
  onChange,
  placeholder,
  allOptionLabel,
  includeAllOption = true,
  activeOnly = true,
  disabled = false,
  required = false,
  className = '',
  id,
  name,
}: NeedSelectProps) {
  const [needs, setNeeds] = useState<Need[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [retryCount, setRetryCount] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError('');

    const loadNeeds = async () => {
      const pages = new Map<string | null, true>();
      const needsById = new Map<string, Need>();
      let cursor: string | null = null;
      try {
        while (active && !pages.has(cursor)) {
          pages.set(cursor, true);
          const query = new URLSearchParams({ limit: '100' });
          if (cursor) query.set('cursor', cursor);
          const data = await api.get<{
            items: Need[];
            page: { next_cursor: string | null; limit: number };
          }>(`/needs?${query.toString()}`);
          if (!active) return;
          (data.items || []).forEach((need) => needsById.set(need.id, need));
          setNeeds(Array.from(needsById.values()));

          const nextCursor = data.page.next_cursor;
          if (nextCursor === null) break;
          if (!nextCursor) {
            setError('Потребности загружены частично: сервер вернул пустой курсор.');
            break;
          }
          if (pages.has(nextCursor)) {
            setError('Потребности загружены частично: сервер повторил страницу.');
            break;
          }
          cursor = nextCursor;
        }
      } catch (err) {
        if (active) setError(errorMessage(err));
      } finally {
        if (active) setLoading(false);
      }
    };

    void loadNeeds();

    return () => {
      active = false;
    };
  }, [retryCount]);

  const visibleNeeds = needs.filter((need) => {
    if (!activeOnly) return true;
    return need.status === 'active' || need.id === value;
  });

  return (
    <div className="recruiting-need-select-control">
      <select
        id={id}
        name={name}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        disabled={disabled || (loading && needs.length === 0)}
        required={required}
        className={`recruiting-select recruiting-need-select ${className}`.trim()}
        aria-label="Выбор потребности"
      >
        {includeAllOption && (
          <option value="">{allOptionLabel || placeholder || 'Все потребности'}</option>
        )}
        {!includeAllOption && !value && (
          <option value="" disabled>
            {placeholder || 'Выберите потребность'}
          </option>
        )}
        {loading && needs.length === 0 && (
          <option value="" disabled>Загрузка потребностей…</option>
        )}
        {error && (
          <option value="" disabled>{error}</option>
        )}
        {visibleNeeds.map((need) => (
          <option key={need.id} value={need.id}>
            {need.title} ({gradeLabel(need.target_grade)}){need.status !== 'active' ? ` [${need.status}]` : ''}
          </option>
        ))}
      </select>
      {error && (
        <button
          type="button"
          className="button button-secondary button-small"
          disabled={loading}
          onClick={() => setRetryCount((count) => count + 1)}
        >
          Повторить загрузку потребностей
        </button>
      )}
    </div>
  );
}
